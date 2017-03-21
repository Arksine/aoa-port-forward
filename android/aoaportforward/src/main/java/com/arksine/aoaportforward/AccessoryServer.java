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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

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
        void onSocketConnected(int numClients);
        void onSocketDisconnected(int numClients);
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

    private volatile ServerSocket mServerSocket = null;
    private AtomicReferenceArray<ClientSocket> mSocketArray;
    private AtomicInteger mConnectionCount = new AtomicInteger(0);
    private int mMaxConnections = 40;
    private int localPort;
    private int remotePort;

    private Thread mAccessoryReadThread = null;
    private Thread mConnectionListenerThread = null ;

    AccessoryServer(Context context, Callbacks accCbs) {
        this.mContext = context;
        this.mAccessoryCallbacks = accCbs;
        this.mUsbManger = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        this.mSocketArray = new AtomicReferenceArray<ClientSocket>(mMaxConnections);

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
            if (mConnectionListenerThread != null && mConnectionListenerThread.isAlive()) {
                // make sure that the current connection is listening on the correct port
                if (this.localPort == lPort && this.remotePort == rPort) {
                    mAccessoryCallbacks.onAccessoryConnected(true, mConnectionCount.get());
                    return;
                } else {
                    // TODO: I should do this in another thread to prevent blocking,
                    // or just disallow binding to a new socket without stopping the service
                    disconnectAllClients();
                    Utils.closeItem(mServerSocket);
                    Utils.stopThread(mConnectionListenerThread);
                }
            }

            this.localPort = lPort;
            this.remotePort = rPort;
            mConnectionListenerThread = new Thread(mConnectionListener);
            mConnectionListenerThread.start();
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
            mConnectionListenerThread = new Thread(null, mConnectionListener, "Connection Listener Thread");
            mConnectionListenerThread.start();
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

    private void writeCommand(PortCommand command, short data) {
        byte[] commandBuf = ByteBuffer.allocate(6)
                .put(command.getBytes())
                .putShort((short)6)
                .putShort((data))
                .array();

        writeToAccessory(commandBuf, 6);
    }

    private void disconnectAllClients() {
        if (mConnectionCount.get() > 0) {
            for (int i = 0; i < mMaxConnections; i++) {
                ClientSocket csocket = mSocketArray.getAndSet(i, null);
                if (csocket != null)
                    csocket.disconnect();
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
            AtomicReferenceArray<ClientSocket> tempArray =
                    new AtomicReferenceArray<ClientSocket>(mMaxConnections * 2);
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

    private final ClientSocket.Callbacks mClientSocketCallbacks = new ClientSocket.Callbacks() {
        @Override
        public void onWrite(final byte[] data, final int size) {
            writeToAccessory(data, size);
        }

        @Override
        public void onDisconnect(final int socketId) {
            int count = mConnectionCount.decrementAndGet();
            mSocketArray.set(socketId, null);
            mAccessoryCallbacks.onSocketDisconnected(count);
        }

        @Override
        public void onError(final String error) {
            // TODO:
        }
    };


    private final Runnable mConnectionListener = new Runnable() {
        @Override
        public void run() {

            try{
                mServerSocket = new ServerSocket(localPort, 0, InetAddress.getByName("127.0.0.1"));
            } catch (IOException e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                return;
            }

            while (!mServerSocket.isClosed()) {
                Short socketid = createSocketId();
                ClientSocket cSocket;
                if (socketid == null) {
                    // Max connections reached, stop the thread, sleep for 100ms and continue
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    continue;
                }

                try {

                    Socket connection = mServerSocket.accept();
                    cSocket = new ClientSocket(connection, socketid,
                            mClientSocketCallbacks);
                } catch (IOException e) {
                    if (DEBUG) {
                        e.printStackTrace();
                    }
                    mAccessoryCallbacks.onError("Unable to connect to socket");
                    continue;
                }

                int count = mConnectionCount.incrementAndGet();
                mSocketArray.set(socketid, cSocket);
                writeCommand(PortCommand.CONNECT_SOCKET, socketid);
                mAccessoryCallbacks.onSocketConnected(count);
            }
        }
    };

    private final Runnable mAccessoryReadRunnable = new Runnable() {
        @Override
        public void run() {
            int bytesRead;
            byte[] inputBuffer = new byte[16384];

            outerloop:
            while (mAccessoryConnected.get()) {
                try {
                    bytesRead = mAccessoryInputStream.read(inputBuffer);
                } catch (IOException e) {
                    break;
                }

                if (bytesRead >= 4) {
                    ByteBuffer inBuf = ByteBuffer.wrap(inputBuffer);
                    PortCommand cmd = PortCommand.getCommandFromValue(inBuf.getShort());
                    int packetSize = inBuf.getShort() & 0xFFFF;

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
                            case DATA_PACKET:
                                Short id = inBuf.getShort();
                                ClientSocket socket = mSocketArray.get(id);
                                if (socket != null) {
                                    socket.writeToClient(inputBuffer, packetSize);
                                } else {
                                    Log.w(TAG, "No Socket Mapped to id: " + id);
                                }
                                break;
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
            Utils.closeItem(mServerSocket);

            disconnectAllClients();

            // Stop socket threads
            Utils.stopThread(mConnectionListenerThread);
            // send terminate command, stop thread with a longer timeout (1000ms)
            writeCommand(PortCommand.TERMINATE_ACCESSORY, (short)0);
            Utils.stopThread(mAccessoryReadThread, 1000);
            Utils.closeItem(mAccessoryInputStream);
            Utils.closeItem(mAccessoryOutputStream);
            Utils.closeItem(mFileDescriptor);

            mServerSocket = null;
            mAccessoryInputStream = null;
            mAccessoryOutputStream = null;
            mFileDescriptor = null;
            mConnectionListenerThread = null;
            mAccessoryReadThread = null;
            mAccessoryCallbacks.onClose();
        }
    };


}
