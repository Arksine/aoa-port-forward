package com.arksine.aoaportforward;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Static Utility Functions
 */

public class Utils {
    private static final String TAG = Utils.class.getSimpleName();
    private static final Boolean DEBUG = true;
    private Utils(){}

    public static boolean isServiceRunning(Class<?> serviceClass, Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static void closeItem(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                if (DEBUG)
                    e.printStackTrace();

            }
        }
    }

    public static void stopThread(Thread thread) {
        stopThread(thread, 100);
    }

    public static void stopThread(Thread thread, int timeout) {
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(timeout);
            } catch (InterruptedException e) {
                if (DEBUG)
                    e.printStackTrace();
            } finally {
                if (thread.isAlive())
                    thread.interrupt();
            }
        }
    }

    public static Bitmap generateLargeNotificationIcon(Context context, int resource) {
        Bitmap icon = BitmapFactory.decodeResource(context.getResources(),
                resource);

        float scaleMultiplier = context.getResources().getDisplayMetrics().density / 3f;

        return Bitmap.createScaledBitmap(icon, (int)(icon.getWidth() * scaleMultiplier),
                (int)(icon.getHeight() * scaleMultiplier), false);
    }

    final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 3];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 3] = hexArray[v >>> 4];
            hexChars[j * 3 + 1] = hexArray[v & 0x0F];
            if (j > 0 && j % 14 == 0 ) {
                // newline every 15 bytes (15th byte is 14th index)
                hexChars[j * 3 + 2] = '\n';
            } else {
                hexChars[j * 3 + 2] = ' ';
            }
        }
        return new String(hexChars);
    }

    public static String bytesToHex(byte[] bytes, int length) {
        char[] hexChars = new char[length * 3];
        for ( int j = 0; j < length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 3] = hexArray[v >>> 4];
            hexChars[j * 3 + 1] = hexArray[v & 0x0F];
            if (j > 0 && j % 14 == 0 ) {
                // newline every 15 bytes (15th byte is 14th index)
                hexChars[j * 3 + 2] = '\n';
            } else {
                hexChars[j * 3 + 2] = ' ';
            }
        }
        return new String(hexChars);
    }

    public static void bufferFill(ByteBuffer dest, ByteBuffer source) {
        int destRem = dest.remaining();
        if (destRem >= source.remaining()) {
            dest.put(source);
        } else {
            byte[] sourceArray;
            if (source.hasArray()) {
                sourceArray = source.array();
            } else {
                sourceArray = new byte[destRem];
                source.get(sourceArray);
            }
            int sourceEnd = source.position() + destRem;
            dest.put(sourceArray, source.position(), destRem);
            source.position(sourceEnd);
        }
    }

    // Like the above, but always reads from source array
    public static void bufferFill(ByteBuffer dest, ByteBuffer source, byte[] sourceArray) {
        int transfer = (dest.remaining() < source.remaining())
                ? dest.remaining() : source.remaining();
        int sourceEnd = source.position() + transfer;
        dest.put(sourceArray, source.position(), transfer);
        source.position(sourceEnd);
    }

}
