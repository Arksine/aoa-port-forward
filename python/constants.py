import sys

DEBUG = True

# USB Definitions
MANUFACTURER = "Arksine"
MODEL_NAME = "PortForward"
DESCRIPTION = "Host port forward receiver"
VERSION = "0.1"
URI = "https://github.com/Arksine/aoa-port-forward/"
SERIAL_NUMBER = "1245346"

# TODO: need all known compatible android smartphone vendors, so I can attempt
# to force accessory mode
COMPATIBLE_VIDS = (0x18D1, 0x0FCE, 0x0E0F, 0x04E8)
ACCESSORY_VID = 0x18D1
ACCESSORY_PID = (0x2D00, 0x2D01, 0x2D04, 0x2D05)


# Command values to be sent to or received from the accessory
CMD_NONE = b'\x00\x00'
CMD_CONNECT_SOCKET = b'\x01\x01'
CMD_CONNECTION_RESP = b'\x01\x02'
CMD_DISCONNECT_SOCKET = b'\x02\x01'
CMD_DATA_PACKET = b'\x03\x01'
CMD_ACCESSORY_CONNECTED = b'\x04\x01'
CMD_CLOSE_ACCESSORY = b'\x05\x0F'



# TODO: currently unused constant for linux, needed to listen for usb connected events
NETLINK_KOBJECT_UEVENT = 15

def eprint(*args, **kwargs):
    """
    Simple helper to print to stderr.  Should work python 2 an 3.
    """
    if DEBUG:
        print(*args, file=sys.stderr, **kwargs)
    