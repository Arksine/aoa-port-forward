package com.arksine.aoaportforward;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Handler;
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

    private final Object ACC_WRITE_LOCK = new Object();

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
    private Handler mSocketEventHandler;

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
                    Utils.closeItem(mSelector);
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

            mAccessoryConnected.set(true);
            writeCommand(PortCommand.ACCESSORY_CONNECTED, remotePort);
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

    private void writeToAccessory(byte[] data, int length) {
        if (mAccessoryOutputStream != null) {
            synchronized (ACC_WRITE_LOCK) {
                try {
                    mAccessoryOutputStream.write(data, 0, length);
                    mAccessoryOutputStream.flush();
                } catch (IOException e) {
                    close();
                }
            }
        }
    }

    private void writeCommand(PortCommand command) {
        byte[] commandBuf = ByteBuffer.allocate(4)
                .put(command.getBytes())
                .putShort((short)0) // empty payload
                .array();

        writeToAccessory(commandBuf, 4);
    }

    private void writeCommand(PortCommand command, short data) {
        byte[] commandBuf = ByteBuffer.allocate(6)
                .put(command.getBytes())
                .putShort((short)2)  // two byte payload (sizeof short)
                .putShort((data))
                .array();

        writeToAccessory(commandBuf, 6);
    }

    private void writeCommand(PortCommand command, int data) {
        byte[] commandBuf = ByteBuffer.allocate(8)
                .put(command.getBytes())
                .putShort((short)4)  // four byte payload (sizeof integer)
                .putInt(data)
                .array();

        writeToAccessory(commandBuf, 8);
    }


    private boolean writeToSocket(short socket_id, ByteBuffer outBuf) {
        // TODO: Need to synchronize writes and disconnects, so a socket that reads EOF doesn't
        // disconnect in the middle of a write
        SocketChannel socketChannel = mSocketArray.get(socket_id);
        if (socketChannel != null) {
            synchronized (socketChannel.socket()) {
                if (DEBUG)
                    Log.d(TAG, "Writing to socket: " + socket_id + "\n" +
                            " Length: " + outBuf.remaining());
                try {
                    while (outBuf.hasRemaining()) {
                        socketChannel.write(outBuf);
                    }
                } catch (IOException e) {
                    Log.i(TAG, "Connection write error");
                    // because the connection failed, whatever is left in this buffer
                    outBuf.position(outBuf.limit());
                    return false;
                }
            }

        } else {
            if (DEBUG)
                Log.w(TAG, "No Socket Mapped to id: " + socket_id);

        }
        return true;
    }

    private void disconnectAllClients() {
        if (mConnectionCount.get() > 0) {
            for (int i = 0; i < mMaxConnections; i++) {
                disconnectSocket((short)i, true, false);
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

    private void disconnectSocket(short socketId, boolean sendResponse, boolean updateService) {
        // TODO: need to synchronize with writes so a socket isn't disconnected
        SocketChannel socketChannel = mSocketArray.getAndSet(socketId, null);
        if (socketChannel != null) {
            if (DEBUG)
                Log.d(TAG, "Disconnect socket id: "+ socketId);

            // TODO: Id rather synchronize on a field of the sc
            synchronized (socketChannel.socket()) {
                Utils.closeItem(socketChannel);
            }
            int count = mConnectionCount.decrementAndGet();

            if (sendResponse) {
            writeCommand(PortCommand.DISCONNECT_SOCKET, socketId);
            }

            if (updateService) {
                mAccessoryCallbacks.onConnectionUpdate(count);
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
                if (DEBUG) {
                    e.printStackTrace();
                }
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
                //  TODO: closed selector exception
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
                            writeCommand(PortCommand.CONNECT_SOCKET, nextSocketId); // tell connection to start
                            mConnectionCount.incrementAndGet(); // Increment current connection count
                            mSocketArray.set(nextSocketId, client);
                        }
                        nextSocketId = createSocketId();
                    } else if (key.isReadable()) {
                        SocketChannel client = (SocketChannel) key.channel();
                        try {
                            bytesRead = client.read(inputBuffer);
                        } catch (IOException e) {
                            Log.i(TAG, "Socket read error, id: " + key.attachment());
                            disconnectSocket((short)key.attachment(),true, true);
                            continue;
                        }
                        if (bytesRead > 0) {
                            // Add the header
                            inputBuffer.flip();
                            inputBuffer.put(PortCommand.DATA_PACKET.getBytes());
                            inputBuffer.putShort((short)(bytesRead + 2));  // Payload = bytes read + socket id
                            inputBuffer.putShort((short)key.attachment());
                            writeToAccessory(inputArray, inputBuffer.limit());

                            // Prepare for next read
                            inputBuffer.clear();
                            inputBuffer.position(6);
                        } else if (bytesRead == -1) {
                            // Socket disconnected
                            if (DEBUG)
                                Log.d(TAG, "EOF Reached, Socket Id: " + key.attachment());
                            // TODO: In theory I shouldn't need to send a disconnect, as the server
                            // Should know to disconnect, correct?  Is this always applicable to
                            // EOF reads? (ie: if the socket sends EOF, we are sure that
                            // the server's respose will send an EOF, and the server will never
                            // send EOF before the socket
                            disconnectSocket((short)key.attachment(), true, true);
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
        private int mBytesRead;
        private PortCommand mCurrentCommand = PortCommand.NONE;
        private int mPayloadSize = 0;
        private byte[] mInputArray = new byte[16384];
        private ByteBuffer mInputBuffer;
        private ByteBuffer mSplitHeaderBuffer = ByteBuffer.allocate(4);
        private ByteBuffer mSplitPayloadBuffer = ByteBuffer.allocateDirect(8192);
        private boolean mPayloadSplit = false;
        private boolean mHeaderSplit = false;

        @Override
        public void run() {
            mInputBuffer = ByteBuffer.wrap(mInputArray);
            outerloop:
            while (mAccessoryConnected.get()) {
                try {
                    mBytesRead = mAccessoryInputStream.read(mInputArray);
                } catch (IOException e) {
                    break;
                }

                if (mBytesRead > 0) {
                    if (DEBUG)
                        Log.d(TAG, "Bytes read: " + mBytesRead);
                    // reset buffer
                    mInputBuffer.position(0);
                    mInputBuffer.limit(mBytesRead);

                    while (mInputBuffer.remaining() >= 4) {
                        if (mHeaderSplit) {
                            processSplitHeader();
                        } else if (mPayloadSplit) {
                            // Payload split between packets, assemble and process
                            Utils.bufferFill(mSplitPayloadBuffer, mInputBuffer);
                            if (mSplitPayloadBuffer.hasRemaining()) {
                                Log.w(TAG, "Payload remaining larger than incoming packet.\n" +
                                        "This should not happen as usb transfers are larger than" +
                                        " Socket transfers");
                                break;
                            } else {
                                // overflow payload is in current buffer process
                                mSplitPayloadBuffer.flip();

                                // Process packet, check for termination
                                if (!processPacket(mCurrentCommand, mSplitPayloadBuffer))
                                    break outerloop;

                                mSplitPayloadBuffer.clear();
                                mPayloadSplit = false;

                                // Continue the next loop to check input buffer size and get
                                // next command
                                continue;
                            }
                        } else {
                            // Header is the next part of the buffer, retreive it
                            mCurrentCommand = PortCommand.getCommandFromValue(mInputBuffer.getShort());
                            mPayloadSize = mInputBuffer.getShort() & 0xFFFF;
                        }

                        if (mPayloadSize == 0) {
                            // There is no payload, process
                            if (DEBUG)
                                Log.i(TAG, "Empty payload");
                            if (!processPacket(mCurrentCommand, null))
                                break outerloop;
                        } else if (mPayloadSize <= mInputBuffer.remaining()) {
                            // The buffer contains the entire payload, process

                            // Reset Limit to payload size
                            int cur_limit = mInputBuffer.limit();
                            mInputBuffer.limit(mInputBuffer.position() + mPayloadSize);
                            if (!processPacket(mCurrentCommand, mInputBuffer))
                                break outerloop;

                            // reset Input buffer limit
                            mInputBuffer.limit(cur_limit);

                        } else {
                            // The buffer only contains a partial section of the payload,
                            // split and store it
                            storeSplitPayload();
                            break;
                        }
                    }

                    if (mInputBuffer.hasRemaining()) {
                        Log.w(TAG, "Buffer not empty after processing, packet header is split");
                        Utils.bufferFill(mSplitHeaderBuffer, mInputBuffer);
                        mHeaderSplit = true;
                        if (mInputBuffer.hasRemaining()) {
                            // If the Input buffer has remaining after the fill, Process the
                            // header and store the remaining bytes in the split payload buffer

                            processSplitHeader();
                            storeSplitPayload();
                        }
                    }
                }
            }

            if (mAccessoryConnected.compareAndSet(true, false)) {
                // Accessory disconnected, either due to error or socket disconnection
                close();
            }
        }

        private void processSplitHeader() {
            if (DEBUG)
                Log.d(TAG, "Processing Split Header");
            int headerRem = mSplitHeaderBuffer.remaining();
            if (headerRem > 0) {
                Utils.bufferFill(mSplitHeaderBuffer, mInputBuffer);
            }

            mSplitHeaderBuffer.flip();
            mCurrentCommand = PortCommand.getCommandFromValue(mSplitHeaderBuffer.getShort());
            mPayloadSize = mSplitHeaderBuffer.getShort() & 0xFFFF;
            mSplitHeaderBuffer.clear();
            mHeaderSplit = false;
        }

        private void storeSplitPayload() {
            if (DEBUG)
                Log.d(TAG, "Split Packet Detected");
            mSplitPayloadBuffer.limit(mPayloadSize);
            if (mInputBuffer.hasRemaining()) {
                Utils.bufferFill(mSplitPayloadBuffer, mInputBuffer);
            }
            mPayloadSplit = true;
        }

        private boolean processPacket(PortCommand cmd ,ByteBuffer packetBuffer) {
            switch (cmd) {
                case CONNECT_SOCKET:
                case ACCESSORY_CONNECTED:
                    Log.i(TAG, "Should not receive command from server: " + cmd);
                    break;
                case DISCONNECT_SOCKET: {
                    Log.i(TAG, "Server disconnected socket");
                    Short id = packetBuffer.getShort();
                    disconnectSocket(id, false, true);
                    break;
                }
                case DATA_PACKET: {
                    Short id = packetBuffer.getShort();
                    if (!writeToSocket(id, packetBuffer))
                        disconnectSocket(id, true, true);

                    break;
                }
                case CONNECTION_RESP: {
                    Short id = packetBuffer.getShort();
                    boolean response = (packetBuffer.getShort() > 0);

                    if (response) {
                        if (DEBUG)
                            Log.d(TAG, "Response success, Socket Id: " + id);
                        mAccessoryCallbacks.onConnectionUpdate(mConnectionCount.get());
                    } else {
                        // Socket didn't connect, remove it from the array and close,
                        // Don't need to update connection count as it hasn't been added
                        if (DEBUG)
                            Log.d(TAG, "Response failure, Socket Id: " + id);

                        // This will disconnect the current socket and decrement the connection count
                        disconnectSocket(id, false, false);
                    }
                    break;
                }
                case TERMINATE_ACCESSORY:
                    Log.d(TAG, "Terminating Server");
                    return false;
                default:
                    Log.i(TAG, "Unknown Command received");
            }

            return true;
        }
    };

    private final Runnable mCloseRunnable = new Runnable() {

        @Override
        public void run() {

            if (DEBUG)
                Log.d(TAG, "Closing Accessory");
            if (mAccessoryConnected.compareAndSet(true, false)) {
                if (DEBUG)
                    Log.d(TAG, "Sending Termination Command");
                writeCommand(PortCommand.TERMINATE_ACCESSORY);
            }

            // Attempt to close socket items
            Utils.closeItem(mServerChannel);
            disconnectAllClients();
            Utils.closeItem(mSelector);

            // Stop socket threads
            Utils.stopThread(mSocketThread);
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
