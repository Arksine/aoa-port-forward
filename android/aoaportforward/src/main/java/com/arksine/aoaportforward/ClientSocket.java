package com.arksine.aoaportforward;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *  Represents a Socket Connected to a Android local client.  It reads data from the socket,
 *  adds the appropriate header, and redirects over USB via the Write Handler provided.  It
 *  also writes data recieved from the USB connection back to the client.  It is expected
 *  that the data has already been demuxed when write is called
 */

public class ClientSocket {
    private static final String TAG = ClientSocket.class.getSimpleName();
    private static final int HEADER_SIZE = 6;

    interface Callbacks {
        void onWrite(final byte[] data, final int size);
        void onDisconnect(final int socketId);
        void onError(final String error);
    }

    private final short mSocketId;
    private final Socket mSocket;
    private final Callbacks mCallbacks;
    private final InputStream mInputStream;
    private final OutputStream mOutputStream;
    private final Thread mSocketReadThread;
    private AtomicBoolean mConnected = new AtomicBoolean(false);

    ClientSocket(Socket socket, int id, Callbacks cbs) throws IOException {
        this.mSocket = socket;
        this.mSocketId = (short) id;
        this.mCallbacks = cbs;
        this.mInputStream = mSocket.getInputStream();
        this.mOutputStream = mSocket.getOutputStream();
        this.mSocketReadThread = new Thread(mSocketReadRunnable);
        // TODO: set priority?
        this.mSocketReadThread.start();
        this.mConnected.set(true);
    }

    void writeToClient(byte[] data, int packetsize) {
        if (mSocket.isConnected()) {
            try {
                // Don't write the header, only the payload
                mOutputStream.write(data, HEADER_SIZE, (packetsize - HEADER_SIZE));
            } catch (IOException e) {
                Log.w(TAG, "Error writing to socket");
                // TODO: disconnect socket?
            }
        }
    }

    // TODO: close socket and all streams.  Notify the socket list so this item is removed
    // from it.  The socket list should be one of the concurrent data structures (CopyOnWriteArraylist)
    void disconnect() {
        Thread disconnectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (mConnected.compareAndSet(true, false)) {
                    Utils.closeItem(mSocket);
                    Utils.closeItem(mInputStream);
                    Utils.closeItem(mOutputStream);
                    Utils.stopThread(mSocketReadThread);
                    mCallbacks.onDisconnect(mSocketId);
                }
            }
        });
        disconnectThread.start();
    }

    private final Runnable mSocketReadRunnable = new Runnable() {
        @Override
        public void run() {
            byte[] inBuffer = new byte[8096];
            final int readSize = inBuffer.length - HEADER_SIZE;
            int bytesRead;

            while (mSocket.isConnected()) {
                try {
                    bytesRead = mInputStream.read(inBuffer, HEADER_SIZE, readSize);
                } catch (IOException e) {
                    break;
                }

                if (bytesRead > 0) {
                    // Prepare the header
                    short packetSize = (short)(bytesRead + HEADER_SIZE);
                    ByteBuffer outBuf = ByteBuffer.wrap(inBuffer);
                    outBuf.put(PortCommand.DATA_PACKET.getBytes());
                    outBuf.putShort(mSocketId);     // socket Id = 2 byte unsigned short
                    outBuf.putShort(packetSize);    // packet size = 2 byte unsigned short

                    //  synchronized write callback
                    mCallbacks.onWrite(inBuffer, packetSize);
                }
            }

            if (mConnected.get())
                disconnect();
        }
    };
}
