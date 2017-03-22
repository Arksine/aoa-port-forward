""" 
TODO
"""
#! python3
# android_accessory_test.py
#

# pylint: disable=no-member
# pylint: disable=W0511,W0622,W0613,W0603,R0902

from struct import pack, unpack
import sys
import time
import signal
import threading
import os
import socket
import select
import usb1
from constants import *

if sys.version_info > (3, 5):  # Python 3.5+
    import selectors
else:  # Python 2.6 - 3.4
    import selectors2 as selectors


class ReadCallback(object):
    """
    TODO: Docstring
    """
    def __init__(self, acc):
        self._accessory = acc
        # TODO: probably need to share socket list (or just
        # access it through the accessory)

    def __call__(self, transfer):
        """
        TODO: Docstring
        """
        length = transfer.getActualLength()
        if not length:
            return True
        data = memoryview(transfer.getBuffer()[:length])

        # TODO: implement the if statements below
        if length >= 4:
            header = unpack('>2sH', data[:4])
            if header[1] != length:
                eprint("Incoming packet does not match expected size")
            elif header[0] == CMD_CONNECT_SOCKET:
                socket_id = unpack('>H', data[4:6])[0]
                self._accessory.connect_socket(socket_id)
            elif header[0] == CMD_DISCONNECT_SOCKET:
                socket_id = unpack('>H', data[4:6])[0]
                self._accessory.disconnect_socket(socket_id)
            elif header[0] == CMD_DATA_PACKET:
                # Demux and write to socket
                socket_id = unpack('>H', data[4:6])[0]
                sock = self._accessory.get_socket(socket_id)
                if sock:
                    index = 6
                    while index < length:
                        r, w, x = select.select([[], [sock.fileno()], []])
                        if w:
                            bytes_sent = sock.send(data[index:])
                            if bytes_sent == 0:
                                eprint("Write error, socket broken")
                                self._accessory.disconnect_socket(socket_id)
                                break
                            else:
                                index = index + bytes_sent
                else:
                    eprint("Socket not valid: {0}".format(socket_id))
            elif header[0] == CMD_ACCESSORY_CONNECTED:
                port = unpack('>H', data[4:6])[0]
                self._accessory.app_connected = True
                self._accessory.port = port
            elif header[0] == CMD_CLOSE_ACCESSORY:
                self._accessory.signal_app_exit()
            else:
                eprint("Unknown Command:")
                eprint(header[0])
        else:
            eprint("Incoming packet too small")

        return True


class AndroidAccessory(object):
    """docstring for AndroidAccessory."""
    def __init__(self, usb_context, vendor_id=None, product_id=None):
        self._context = usb_context
        isconfigured, self._handle = self._find_handle(vendor_id, product_id)

        if isconfigured:
            print("Device is in accessory mode")
            # TODO: should I reset the device?
            # self._handle.claimInterface(0)
            # self._handle.resetDevice()
            # time.sleep(2)
            # isconfigured, self._handle = self._find_handle(vendor_id, product_id)
            # self._handle = self._configure_accessory_mode()
        else:
            self._handle = self._configure_accessory_mode()

        self._handle.claimInterface(0)

        # pause for one second so the android device can react to changes
        time.sleep(1)

        device = self._handle.getDevice()
        config = device[0]
        interface = config[0]
        self._in_endpoint, self._out_endpoint = self._get_endpoints(interface[0])
        if self._in_endpoint is None or self._out_endpoint is None:
            self._handle.releaseInterface(0)
            raise usb1.USBError(
                'Unable to retreive endpoints for accessory device'
            )

        read_callback = usb1.USBTransferHelper()
        callback_obj = ReadCallback(self)
        read_callback.setEventCallback(
            usb1.TRANSFER_COMPLETED,
            callback_obj,
        )

        self._read_list = []
        for _ in range(64):
            data_reader = self._handle.getTransfer()
            data_reader.setBulk(
                self._in_endpoint,
                0x4000,
                callback=read_callback,
            )
            data_reader.submit()
            self._read_list.append(data_reader)


        self.port = 8000  # port to forward sockets to
        self.app_connected = False
        self._is_running = True
        self._socket_dict = {}
        self._socket_selector = selectors.DefaultSelector()
        self._socket_read_thread = threading.Thread(target=self._socket_read_thread_proc)
        self._socket_read_thread.start()

    def _find_handle(self, vendor_id=None, product_id=None, attempts_left=5):
        handle = None
        found_pid = None
        for device in self._context.getDeviceList():
            if vendor_id and product_id:
                # match by vendor and product id
                if (device.getVendorID() == vendor_id and
                        device.getProductID == product_id):
                    try:
                        handle = device.open()
                        found_vid = vendor_id
                        found_pid = device.getProductID()
                    except usb1.USBError as err:
                        print(err.args)
                    break
            elif device.getVendorID() in COMPATIBLE_VIDS:
                # attempt to get the first compatible vendor id
                try:
                    handle = device.open()
                    found_vid = device.getVendorID()
                    found_pid = device.getProductID()
                except usb1.USBError as err:
                    print(err.args)
                break

        if handle:
            print('Found {0:x}:{1:x}'.format(found_vid, found_pid))
            if found_pid in ACCESSORY_PID:
                return True, handle
            else:
                return False, handle
        elif attempts_left:
            time.sleep(1)
            return self._find_handle(vendor_id, product_id, attempts_left-1)
        else:
            raise usb1.USBError('Device not connected')

    def _configure_accessory_mode(self):
        # Don't need to claim interface to do control read/write, and the
        # original driver prevents it
        # self._handle.claimInterface(0)

        version = self._handle.controlRead(
            usb1.TYPE_VENDOR | usb1.RECIPIENT_DEVICE,
            51, 0, 0, 2
        )

        adk_ver = unpack('<H', version)[0]
        print("ADK version is: %d" % adk_ver)

        # enter accessory information
        for i, data in enumerate((MANUFACTURER, MODEL_NAME, DESCRIPTION,
                                  VERSION, URI, SERIAL_NUMBER)):
            assert self._handle.controlWrite(
                usb1.TYPE_VENDOR | usb1.RECIPIENT_DEVICE,
                52, 0, i, data.encode()
            ) == len(data)

        if adk_ver == 2:
            # enable 2 channel audio
            assert self._handle.controlWrite(
                usb1.TYPE_VENDOR | usb1.RECIPIENT_DEVICE,
                58, 1, 0, b''
            ) == 0

        # start device in accessory mode
        self._handle.controlWrite(
            usb1.TYPE_VENDOR | usb1.RECIPIENT_DEVICE,
            53, 0, 0, b''
        )

        time.sleep(1)

        isconfigured, newhandle = self._find_handle()
        if isconfigured:
            return newhandle
        else:
            raise usb1.USBError('Error configuring accessory mode')

    def _get_endpoints(self, interface):
        inep = None
        outep = None
        for endpoint in interface:
            addr = endpoint.getAddress()
            if (addr & 0x80) == 0x80:
                inep = addr
                print('In endpoint address: %02x' % addr)
            elif (addr & 0x80) == 0x00:
                outep = addr
                print('Out endpoint address: %02x' % addr)
        return inep, outep

    def _socket_read_thread_proc(self):
        """
        Uses a selector to loop through all connected sockets, listening
        for data.  If data is found, it is muxed and sent back to the
        android device via usb
        """
        buffer = bytearray(8192)
        buff_view = memoryview(buffer)
        buff_view[0:2] = CMD_DATA_PACKET
        while self._is_running:
            events = self._socket_selector.select()
            for key, event in events:
                if event & selectors.EVENT_READ:
                    try:
                        bytesRead = key.fileobj.recv_into(buff_view[6:])
                    except EOFError:
                        # This socket has been closed, disconnect it
                        self.disconnect_socket(key.data)
                        continue
                    length = bytesRead + 6  # add header to length
                    len_bytes = pack('>H', length)
                    id_bytes = pack('>H', key.data)
                    buff_view[2:4] = len_bytes
                    buff_view[4:6] = id_bytes
                    try:
                        self._handle.bulkWrite(self._out_endpoint, buff_view[:length])
                    except usb1.USBError as err:
                        eprint("Error writing data")
                        eprint(err.args)

    def connect_socket(self, session_id):
        """
        Attempts to connect a new socket on the requested port.  If successful,
        to socket is registered to the selector, with its session ID, and the
        socket is added to the dictionary
        """
        eprint("Connecting to Socket")
        new_sock = socket.socket()
        new_sock.setblocking(False)
        try:
            new_sock.connect(('localhost', self.port))
        except socket.error:
            eprint("Unable to connect to socket")
            self.send_accessory_command(CMD_DISCONNECT_SOCKET, session_id)
            return False
        else:
            eprint("Socket Connected")
            try:
                # store the socket Id in the selector
                self._socket_selector.register(new_sock, selectors.EVENT_READ, session_id)
            except KeyError:
                # somehow selector already registered
                pass

            # Add to map associating socket IDs with sockets
            self._socket_dict[session_id] = new_sock
            return True


    def disconnect_socket(self, session_id):
        try:
            sock = self._socket_dict[session_id]
            self._socket_selector.unregister(sock)
            del self._socket_dict[session_id]
        except KeyError:
            pass
        finally:
            # TODO: send command back to android?
            if sock:
                sock.close()

    def get_socket(self, session_id):
        """
        Retreives a socket from the stored dictionary
        """
        return self._socket_dict.get(session_id)

    def send_accessory_command(self, command, data=None):
        length = 4      # base packet size
        if not data:
            packet = command + pack('>H', length)
        elif isinstance(data, bytes) or isinstance(data, bytearray):
            length += len(data)
            packet = command + pack('>H', length) + data
        elif isinstance(data, int):
            length = 6
            packet = command + pack('>H', length) + pack('>H', data)
        else:
            eprint('Data type not acceptable')
            return
        self._handle.bulkWrite(self._out_endpoint, packet)

    def signal_app_exit(self):
        """
        Sends an exit command to the application.  This is necessary for
        Android to cleanly exit.
        """
        if self.app_connected:
            self.send_accessory_command(CMD_CLOSE_ACCESSORY)
            self.app_connected = False

    def stop(self):
        """
        Signals device if connected, Stops all threads, disconnects all sockets
        """
        if self._handle:
            self.signal_app_exit()
            # give one second for transfers to complete
            time.sleep(1)
            self._is_running = False
            for sock in self._socket_dict.values():
                sock.close()
            self._socket_dict.clear()
            # TODO: should reset device
            self._handle.releaseInterface(0)
            self._handle.close()
            self._handle = None

    def run(self):
        try:
            while any(x.isSubmitted() for x in self._read_list):
                try:
                    self._context.handleEvents()
                except usb1.USBErrorInterrupted:
                    pass
                if not self._is_running:
                    for xfer in self._read_list:
                        if xfer.isSubmitted():
                            xfer.cancel()
                    break
        finally:
            self.stop()

SHUTDOWN = False

def parse_uevent(data):
    lines = data.split('\0')
    keys = []
    for line in lines:
        val = line.split('=')
        if len(val) == 2:
            keys.append((val[0], val[1]))

    attributes = dict(keys)
    if 'ACTION' in attributes and 'PRODUCT' in attributes:
        if attributes['ACTION'] == 'add':
            parts = attributes['PRODUCT'].split('/')
            return int(parts[0], 16), int(parts[1], 16)

    return None, None

def check_uevent():
    try:
        sock = socket.socket(socket.AF_NETLINK, socket.SOCK_RAW,
                             NETLINK_KOBJECT_UEVENT)

        sock.bind((os.getpid(), -1))
        vid = 0
        pid = 0
        while True:
            data = sock.recv(512)
            try:
                vid, pid = parse_uevent(data)
                if vid != None and pid != None:
                    break
            except ValueError:
                eprint("unable to parse uevent")

        sock.close()
        return vid, pid
    except ValueError as err:
        eprint(err)

def setup_signal_exit(accessory):
    def _exit(sig, stack):
        eprint('Exiting...')
        global SHUTDOWN
        SHUTDOWN = True
        if accessory is not None:
            accessory.stop()

    for signum in (signal.SIGTERM, signal.SIGINT):
        signal.signal(signum, _exit)


def open_accessory(context, vid, pid):
    try:
        accessory = AndroidAccessory(context, vid, pid)
    except usb1.USBError:
        pass
    else:
        setup_signal_exit(accessory)
        accessory.run()

def main():
    # check args
    vid = None
    pid = None
    if len(sys.argv) == 3:
        # Arguments should be hex vendor id and product id, convert
        # them to int
        vid = int(sys.argv[1], 16)
        pid = int(sys.argv[2], 16)
        if vid not in COMPATIBLE_VIDS:
            eprint("Vendor Id not a compatible Android Device")
            return

    # setup a base signal exit
    setup_signal_exit(None)
    with usb1.USBContext() as context:
        while not SHUTDOWN:
            # Initial Attempt to open
            open_accessory(context, vid, pid)

            if sys.platform == 'linux':
                # listen for USB attach events (linux only)
                while not SHUTDOWN:
                    nvid, npid = check_uevent()
                    if nvid in COMPATIBLE_VIDS:
                        if (not vid and not pid) or (vid == nvid and pid == npid):
                            open_accessory(context, nvid, npid)

            else:
                # sleep for 5 seconds between connection attempts
                time.sleep(5)

if __name__ == '__main__':
    main()
