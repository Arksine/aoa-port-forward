import socket
import sys
import os
import signal
from constants import eprint, NETLINK_KOBJECT_UEVENT

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

        eprint("Binding to uevent socket...")
        sock.bind((os.getpid(), -1))
        vid = 0
        pid = 0
        while not SHUTDOWN:
            eprint("Waiting for uevent data...")
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

if __name__ == '__main__':
    setup_signal_exit(None)
    while not SHUTDOWN:
        nvid, npid = check_uevent()
        eprint('Vid: {0} Pid: {1}'.format(nvid, npid))
