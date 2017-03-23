package com.arksine.aoaportforward;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

// TODO: might be better to use Selectors for socket I/O instead of spawning a new thread
// for each socket

/**
 *  Connects to the USB Accessory.  After a connections has been established, this class
 *  listens on the provided port for connections.  Connections are then forwarded over USB
 *  to the real server, each socket with its own unique ID.  Socket data is muxed when sending
 *  over USB and demuxed when received.
 */

class AccessoryServer {
    private static final String TAG = AccessoryServer.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final String MANUFACTURER = "Arksine";
    private static final String MODEL = "PortForward";
    private static final String ACTION_USB_PERMISSION = "com.arksine.aoaportforward.USB_PERMISSION";
    private static final int HARD_CONNECTION_LIMIT = 640;

    private final Object WRITE_LOCK = new Object();

    interface Callbacks {
        void onAccessoryConnected(boolean connected, int numClients);
        void onConnectionUpdate(int connectionCount);
        void onError(String error);
        void onClose();
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (accessory != null) {
                            openAccessory(accessory);
                            return;
                        }
                    }

                    Log.d(TAG, "Accessory permission not granted.");
                    AccessoryServer.this.mAccessoryCallbacks.onAccessoryConnected(false, 0);
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (accessory != null && accessory.equals(mAccessory)){
                    AccessoryServer.this.close();
                }
            }
        }
    };

    private Context mContext;
    private Callbacks mAccessoryCallbacks;
    private UsbManager mUsbManger;
    private AtomicBoolean mAccessoryConnected = new AtomicBoolean(false);
    private AtomicBoolean mUsbReceiverRegistered = new AtomicBoolean(false);

    private UsbAccessory mAccessory;
    private ParcelFileDescriptor mFileDescriptor = null;
    private FileInputStream mAccessoryInputStream = null;
    private FileOutputStream mAccessoryOutputStream = null;

    private Selector mSelector = null;
    private volatile ServerSocketChannel mServerChannel = null;
    private volatile AtomicReferenceArray<SocketChannel> mSocketArray;
    private AtomicInteger mConnectionCount = new AtomicInteger(0);
    private int mMaxConnections = 40;
    private int localPort;
    private int remotePort;

    private Thread mAccessoryReadThread = null;
    private Thread mSocketThread = null ;

    AccessoryServer(Context context, Callbacks accCbs) {
        this.mContext = context;
        this.mAccessoryCallbacks = accCbs;
        this.mUsbManger = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        this.mSocketArray = new AtomicReferenceArray<SocketChannel>(mMaxConnections);

        registerReceiver();
    }

    private void registerReceiver() {
        //  register main usb receiver
        if (mUsbReceiverRegistered.compareAndSet(false, true)) {
            IntentFilter usbFilter = new IntentFilter();
            usbFilter.addAction(ACTION_USB_PERMISSION);
            usbFilter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
            mContext.registerReceiver(mUsbReceiver, usbFilter);
        }
    }

    void unregisterReceiver() {
        if (mUsbReceiverRegistered.compareAndSet(true, false)) {
            mContext.unregisterReceiver(mUsbReceiver);
        }
    }

    void open() {
        this.open(null, 8000, 8000);
    }

    void open(UsbAccessory acc) {
        this.open(acc, 8000, 8000);
    }

    void open(UsbAccessory acc, int lPort, int rPort) {

        if (this.mAccessoryConnected.get()) {
            // check to see if listener thread is running, if not start it
            if (mSocketThread != null && mSocketThread.isAlive()) {
                // make sure that the current connection is listening on the correct port
                if (this.localPort == lPort && this.remotePort == rPort) {
                    mAccessoryCallbacks.onAccessoryConnected(true, mConnectionCount.get());
                    return;
                } else {
                    // TODO: I should do this in another thread to prevent blocking,
                    // or just disallow binding to a new socket without stopping the service
                    disconnectAllClients();
                    Utils.closeItem(mServerChannel);
                    Utils.stopThread(mSocketThread);
                }
            }

            this.localPort = lPort;
            this.remotePort = rPort;
            mSocketThread = new Thread(mSocketSelector);
            mSocketThread.start();
            mAccessoryCallbacks.onAccessoryConnected(true, 0);
            return;
        }

        this.localPort = lPort;
        this.remotePort = rPort;

        // No accessory was passed to the activity via intent, so attempt to detect it
        if (acc == null) {
            acc = detectAccessory();
            if (acc == null) {
                Log.d(TAG, "Unable to detect accessory.");
                mAccessoryCallbacks.onAccessoryConnected(false, 0);
                return;
            }
        }

        if (mUsbManger.hasPermission(acc)) {
            openAccessory(acc);
        } else {
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0,
                    new Intent(ACTION_USB_PERMISSION), 0);
            mUsbManger.requestPermission(acc, pi);
        }

    }

    public boolean isOpen() {
        return mAccessoryConnected.get();
    }

    void close() {

        // the stop reading function can block, so close in a new thread to prevent
        // from blocking UI thread
        Thread closeThread = new Thread(mCloseRunnable);
        closeThread.start();

    }


    private boolean isValidAccessory(UsbAccessory acc) {
        if (acc != null) {
            if (MANUFACTURER.equals(acc.getManufacturer()) &&
                    MODEL.equals(acc.getModel())) {
                return true;
            }
        }
        return false;
    }

    private UsbAccessory detectAccessory() {
        UsbManager usbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        UsbAccessory[] accessoryList = usbManager.getAccessoryList();

        if (accessoryList != null && !(accessoryList.length == 0)) {
            UsbAccessory accessory = accessoryList[0];
            if (isValidAccessory(accessory)) {
                return accessory;

            } else {
                Log.e(TAG, "Connected Accessory is not a match for expected accessory\n" +
                        "Expected Accessory: " + MANUFACTURER + ":" + MODEL + "\n" +
                        "Connected Accessory: " + accessory.getManufacturer() + ":" +
                        accessory.getModel());
            }
        }

        Log.i(TAG, "Accessory not found");
        return null;
    }

    private void openAccessory(UsbAccessory accessory) {
        mAccessory = accessory;
        mFileDescriptor = mUsbManger.openAccessory(mAccessory);

        if (mFileDescriptor != null) {
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mAccessoryOutputStream = new FileOutputStream(fd);
            mAccessoryInputStream = new FileInputStream(fd);

            writeCommand(PortCommand.ACCESSORY_CONNECTED, (short)remotePort);
            mAccessoryConnected.set(true);
            mAccessoryReadThread = new Thread(null, mAccessoryReadRunnable, "Accessory Read Thread");
            mAccessoryReadThread.start();
            mSocketThread = new Thread(null, mSocketSelector, "Connection Listener Thread");
            mSocketThread.start();
            mAccessoryCallbacks.onAccessoryConnected(true, 0);

        } else {
            Log.d(TAG, "Unable to open Accessory File Descriptor");
            mAccessoryCallbacks.onAccessoryConnected(false, 0);
        }
    }

    private void writeToAccessory(byte[] data, int packetSize) {
        if (mAccessoryConnected.get()) {
            synchronized (WRITE_LOCK) {
                try {
                    mAccessoryOutputStream.write(data, 0, packetSize);
                } catch (IOException e) {
                    close();
                }
            }
        }
    }

    private void writeCommand(PortCommand command) {
        byte[] commandBuf = ByteBuffer.allocate(4)
                .put(command.getBytes())
                .putShort((short)4)
                .array();

        writeToAccessory(commandBuf, 4);
    }

    private void writeCommand(PortCommand command, short data) {
        byte[] commandBuf = ByteBuffer.allocate(6)
                .put(command.getBytes())
                .putShort((short)6)
                .putShort((data))
                .array();

        writeToAccessory(commandBuf, 6);
    }

    private boolean writeToSocket(SocketChannel sc, byte[] input, int packetsize) {
        int index = 6;  // Don't write the header to the socket
        int bytesWritten;
        ByteBuffer outBuf = ByteBuffer.wrap(input);
        outBuf.limit(packetsize);
        outBuf.position(index);
        try {
            while (index < packetsize) {
                bytesWritten = sc.write(outBuf);
                index += bytesWritten;
                outBuf.position(index);
            }
        } catch (IOException e) {
            Log.i(TAG, "Connection write error");
            return false;
        }
        return true;
    }

    private void disconnectAllClients() {
        if (mConnectionCount.get() > 0) {
            for (short i = 0; i < mMaxConnections; i++) {
                disconnectSocket(i, false);
            }
        }
    }

    private Short createSocketId() {
        int socketId = mConnectionCount.get();

        // Check the size, grow the array if necessary up to the Hard connection limit
        if (socketId == mMaxConnections) {
            if (mMaxConnections >= HARD_CONNECTION_LIMIT) {
                return null;
            }

            // Grow the array
            AtomicReferenceArray<SocketChannel> tempArray =
                    new AtomicReferenceArray<SocketChannel>(mMaxConnections * 2);
            for (int i = 0; i < mMaxConnections; i++) {
                tempArray.set(i, mSocketArray.get(i));
            }
            mMaxConnections *= 2;
            mSocketArray = tempArray;
        }

        for (int j = 0; j < mMaxConnections; j++) {
            if (mSocketArray.get(socketId) == null) {
                return (short)socketId;
            }

            socketId++;
            if (socketId >= mMaxConnections)
                socketId = 0;
        }

        // This should never be reached
        return null;
    }

    private void disconnectSocket(short socketId, boolean updateService) {
        SocketChannel socketChannel = mSocketArray.getAndSet(socketId, null);
        if (socketChannel != null) {
            Utils.closeItem(socketChannel);
            mConnectionCount.decrementAndGet();

            if (updateService) {
                mAccessoryCallbacks.onConnectionUpdate(mConnectionCount.get());
            }
        }
    }

    private final Runnable mSocketSelector = new Runnable() {
        @Override
        public void run() {
            // set up selector and server
            try {
                mSelector = Selector.open();
                mServerChannel = ServerSocketChannel.open();
                mServerChannel.configureBlocking(false);
                mServerChannel.socket().bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort));
                mServerChannel.register(mSelector, SelectionKey.OP_ACCEPT);
            } catch (IOException e) {
                Log.e(TAG, "Unable to Open and configure server socket connection");
                if (mServerChannel != null && mServerChannel.isOpen()) {
                    Utils.closeItem(mServerChannel);
                }
                return;
            }

            byte[] inputArray = new byte[8192];
            ByteBuffer inputBuffer = ByteBuffer.wrap(inputArray);
            inputBuffer.position(6);  // Leave space for the header when reading
            Short nextSocketId = createSocketId();
            int bytesRead;

            while (mServerChannel.isOpen()) {
                try {
                    mSelector.select();
                } catch (IOException e) {
                    Log.e(TAG, "Selector failed, exiting socket loop");
                    break;
                }
                Set selectedKeys = mSelector.selectedKeys();
                Iterator iter = selectedKeys.iterator();

                while (iter.hasNext()) {

                    SelectionKey key = (SelectionKey) iter.next();

                    if (key.isAcceptable()) {
                        if (nextSocketId != null) {
                            SocketChannel client;
                            try {
                                client = mServerChannel.accept();
                                client.configureBlocking(false);
                                client.register(mSelector, SelectionKey.OP_READ, nextSocketId);
                            } catch (IOException e) {
                                Log.i(TAG, "Unable to connect to client");
                                continue;
                            }
                            mSocketArray.set(nextSocketId, client);
                            mConnectionCount.incrementAndGet();
                        }
                        nextSocketId = createSocketId();
                    } else if (key.isReadable()) {
                        SocketChannel client = (SocketChannel) key.channel();
                        try {
                            bytesRead = client.read(inputBuffer);
                        } catch (IOException e) {
                            Log.i(TAG, "Socket read error, id: " + key.attachment());
                            disconnectSocket((short)key.attachment(), true);
                            continue;
                        }
                        if (bytesRead > 0) {
                            // Add the header
                            inputBuffer.flip();
                            inputBuffer.put(PortCommand.DATA_PACKET.getBytes());
                            inputBuffer.putShort((short)inputBuffer.limit());  // Total bytes in packet
                            inputBuffer.putShort((short)key.attachment());
                            writeToAccessory(inputArray, inputBuffer.limit());

                            // Prepare for next read
                            inputBuffer.clear();
                            inputBuffer.position(6);
                        } else if (bytesRead == -1) {
                            // Socket disconnected
                            disconnectSocket((short)key.attachment(), true);
                        }
                    }
                    iter.remove();
                }
            }

            if (mServerChannel.isOpen()){
                Utils.closeItem(mServerChannel);
            }
        }
    };

    private final Runnable mAccessoryReadRunnable = new Runnable() {
        @Override
        public void run() {
            int bytesRead;
            byte[] inputArray = new byte[16384];
            ByteBuffer inputBuffer = ByteBuffer.wrap(inputArray);

            outerloop:
            while (mAccessoryConnected.get()) {
                try {
                    bytesRead = mAccessoryInputStream.read(inputArray);
                } catch (IOException e) {
                    break;
                }

                if (bytesRead >= 4) {
                    // reset buffer
                    inputBuffer.position(0);
                    inputBuffer.limit(bytesRead);

                    PortCommand cmd = PortCommand.getCommandFromValue(inputBuffer.getShort());
                    int packetSize = inputBuffer.getShort() & 0xFFFF;

                    if (packetSize != bytesRead) {
                        Log.i(TAG, "Error, Incorrect number of bytes received");
                        // TODO: Should I try to recover, store in buffer?
                    } else {
                        switch (cmd) {
                            case CONNECT_SOCKET:
                            case DISCONNECT_SOCKET:
                            case ACCESSORY_CONNECTED:
                                Log.i(TAG, "Should not receive command from server: " + cmd);
                                break;
                            case DATA_PACKET: {
                                Short id = inputBuffer.getShort();
                                SocketChannel socketChannel = mSocketArray.get(id);
                                if (socketChannel != null) {
                                    if (!writeToSocket(socketChannel, inputArray, packetSize))
                                        disconnectSocket(id, true);
                                } else {
                                    Log.w(TAG, "No Socket Mapped to id: " + id);
                                }
                                break;
                            }
                            case CONNECTION_RESP: {
                                Short id = inputBuffer.getShort();
                                boolean response = (inputBuffer.getShort() > 0);
                                if (response) {
                                    // TODO: need a callback to update connected list count in
                                    // notification
                                } else {
                                    // Socket didn't connect, remove it from the array and close,
                                    // Don't need to update connection count as it hasn't been added
                                    disconnectSocket(id, false);
                                }
                                break;
                            }
                            case TERMINATE_ACCESSORY:
                                break outerloop;
                            default:
                                Log.i(TAG, "Unknown Command received");
                        }
                    }
                } else if (bytesRead > 0) {
                    Log.e(TAG, "Incoming packet too short");
                }
            }

            if (mAccessoryConnected.get()) {
                // Accessory disconnected, either due to error or socket disconnection
                close();
            }
        }
    };

    private final Runnable mCloseRunnable = new Runnable() {

        @Override
        public void run() {
            // Attempt to close socket items

            mAccessoryConnected.set(false);
            Utils.closeItem(mServerChannel);
            Utils.closeItem(mSelector);

            disconnectAllClients();

            // Stop socket threads
            Utils.stopThread(mSocketThread);
            // send terminate command, stop thread with a longer timeout (1000ms)
            writeCommand(PortCommand.TERMINATE_ACCESSORY);
            Utils.stopThread(mAccessoryReadThread, 1000);
            Utils.closeItem(mAccessoryInputStream);
            Utils.closeItem(mAccessoryOutputStream);
            Utils.closeItem(mFileDescriptor);

            mServerChannel = null;
            mAccessoryInputStream = null;
            mAccessoryOutputStream = null;
            mFileDescriptor = null;
            mSocketThread = null;
            mAccessoryReadThread = null;
            mAccessoryCallbacks.onClose();
        }
    };


}
