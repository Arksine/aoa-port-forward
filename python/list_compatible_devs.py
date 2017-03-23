import usb1
from constants import COMPATIBLE_VIDS

if __name__ == '__main__':
    with usb1.USBContext() as context:
        dev_list = context.getDeviceList()

        for device in dev_list:
            if device.getVendorID() in COMPATIBLE_VIDS:
                print("Device Found: {0}".format(device))
                print("Class: {0:x}, Subclass: {1:x}".format(
                    device.getDeviceClass(), device.getDeviceSubClass()
                ))
                