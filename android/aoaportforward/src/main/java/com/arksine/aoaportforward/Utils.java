package com.arksine.aoaportforward;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.Closeable;
import java.io.IOException;

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
}
