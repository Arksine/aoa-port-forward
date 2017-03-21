package com.arksine.aoaportforward;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.preference.PreferenceManager;

/**
 * Manager for the library
 */

public class PortForwardManager {
    public static final String EXTRA_LOCAL_PORT = "com.arksine.aoaportforward.EXTRA_LOCAL_PORT";
    public static final String EXTRA_REMOTE_PORT = "com.arksine.aoaportforward.EXTRA_REMOTE_PORT";

    private PortForwardManager() {}

    public static void startPortForwardService(Context context) {
        if (!Utils.isServiceRunning(PortForwardService.class, context)) {
            Intent startIntent = new Intent(context, PortForwardService.class);
            context.startService(startIntent);
        } else {
            // Broadcast
            Intent connectIntent = new Intent(context.getString(R.string.ACTION_CONNECT_ACCESSORY));
            context.sendBroadcast(connectIntent);
        }
    }

    public static void startPortForwardService(Context context, UsbAccessory accessory) {
        if (!Utils.isServiceRunning(PortForwardService.class, context)) {
            Intent startIntent = new Intent(context, PortForwardService.class);
            startIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            context.startService(startIntent);
        } else {
            // Broadcast
            Intent connectIntent = new Intent(context.getString(R.string.ACTION_CONNECT_ACCESSORY));
            connectIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            context.sendBroadcast(connectIntent);
        }
    }

    public static void startPortForwardService(Context context, UsbAccessory accessory,
                                               int localPort, int remotePort) {
        if (!Utils.isServiceRunning(PortForwardService.class, context)) {
            Intent startIntent = new Intent(context, PortForwardService.class);
            startIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            startIntent.putExtra(EXTRA_LOCAL_PORT, localPort);
            startIntent.putExtra(EXTRA_REMOTE_PORT, remotePort);
            context.startService(startIntent);
        } else {
            // Broadcast
            Intent connectIntent = new Intent(context.getString(R.string.ACTION_CONNECT_ACCESSORY));
            connectIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            connectIntent.putExtra(EXTRA_LOCAL_PORT, localPort);
            connectIntent.putExtra(EXTRA_REMOTE_PORT, remotePort);
            context.sendBroadcast(connectIntent);
        }
    }

    public static void stopPortForwardService(Context context) {
        Intent stopIntent = new Intent(context.getString(R.string.ACTION_STOP_SERVICE));
        context.sendBroadcast(stopIntent);
    }

    public static void setPorts(Context context, int localPort, int remotePort) {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPrefs.edit()
                .putInt(EXTRA_LOCAL_PORT, localPort)
                .putInt(EXTRA_REMOTE_PORT, remotePort)
                .apply();
    }
}
