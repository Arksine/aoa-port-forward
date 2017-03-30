import usb1

if __name__ == "__main__":
    with usb1.USBContext() as context:
        fd_list = context.getPollFDList()

        if len(fd_list) > 0:
            print("Usb file descriptors:")
            for fd in fd_list:
                print(fd[0])
                print(fd[1])
        else:
            print("No file descriptors in list")
            