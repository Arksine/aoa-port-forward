package com.arksine.aoaportforward;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;

import java.util.Locale;


public class PortForwardService extends Service {
    private static final String TAG = PortForwardService.class.getSimpleName();

    private NotificationManager mNotificationManager;
    private Notification.Builder mNotificationBuilder;
    private AccessoryServer mAccessoryServer;
    private int mLocalPort = 0;
    private int mRemotePort = 0;


    @Override
    public void onCreate() {
        super.onCreate();

        IntentFilter filter = new IntentFilter(getString(R.string.ACTION_STOP_SERVICE));
        filter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(mServiceReciever, filter);

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Bitmap largeIcon = Utils.generateLargeNotificationIcon(this, R.drawable.ic_notifcation_large);
        Intent stopIntent = new Intent(getString(R.string.ACTION_STOP_SERVICE));
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(this, R.integer.REQUEST_STOP_SERVICE,
                stopIntent, 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Icon stopIcon = Icon.createWithResource(this, R.drawable.ic_stop);
            Notification.Action stopAction = new Notification.Action.Builder(stopIcon,
                    "Stop Service", stopPendingIntent).build();
            mNotificationBuilder = new Notification.Builder(this)
                    .setContentTitle(getText(R.string.service_name))
                    .setContentText(getText((R.string.NOTIFICATION_NOT_CONNECTED)))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setLargeIcon(largeIcon)
                    .addAction(stopAction)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH);

        } else {
            mNotificationBuilder = new Notification.Builder(this)
                    .setContentTitle(getText(R.string.service_name))
                    .setContentText(getText(R.string.NOTIFICATION_NOT_CONNECTED))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setLargeIcon(largeIcon)
                    .addAction(R.drawable.ic_stop,
                            "Stop Service", stopPendingIntent)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH);
        }

        mAccessoryServer = new AccessoryServer(this, mAccessoryCallbacks);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // No binding allowed to this service
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        connectServer(intent);
        startForeground(R.integer.ONGOING_NOTIFICATION_ID, mNotificationBuilder.build());
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mAccessoryServer != null) {
            if (mAccessoryServer.isOpen())
                mAccessoryServer.close();
            mAccessoryServer.unregisterReceiver();
        }
        unregisterReceiver(mServiceReciever);
    }

    private void connectServer(Intent intent) {
        if (!mAccessoryServer.isOpen()) {
            mLocalPort = intent.getIntExtra(PortForwardManager.EXTRA_LOCAL_PORT, -1);
            mRemotePort = intent.getIntExtra(PortForwardManager.EXTRA_REMOTE_PORT, -1);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            if (mLocalPort == -1)
                mLocalPort = prefs.getInt(PortForwardManager.EXTRA_LOCAL_PORT, 8000);
            if (mRemotePort == -1)
                mRemotePort = prefs.getInt(PortForwardManager.EXTRA_REMOTE_PORT, 8000);

            UsbAccessory acc = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            mAccessoryServer.open(acc, mLocalPort, mRemotePort);
        }
    }

    private synchronized void updateNotificationContent(String update) {
        mNotificationBuilder.setContentText(update);
        mNotificationManager.notify(R.integer.ONGOING_NOTIFICATION_ID,
                mNotificationBuilder.build());
    }

    private final AccessoryServer.Callbacks mAccessoryCallbacks
            = new AccessoryServer.Callbacks() {
        @Override
        public void onAccessoryConnected(boolean connected, int numClients) {
            String notification;
            if (connected) {
                notification = String.format(Locale.US, "Forwarding Port %1$d over USB\n" +
                        "%2$d Client(s) connected", mLocalPort, 0);
            } else {
                notification = "Accessory not connected";
            }

            updateNotificationContent(notification);
        }

        @Override
        public void onSocketConnected(int numClients) {
            updateNotificationContent(
                    String.format(Locale.US, "Forwarding Port %1$d over USB\n" +
                    "%2$d Client(s) connected", mLocalPort, numClients));
        }

        @Override
        public void onSocketDisconnected(int numClients) {
            updateNotificationContent(
                    String.format(Locale.US, "Forwarding Port %1$d over USB\n" +
                    "%2$d Client(s) connected", mLocalPort, numClients));
        }

        @Override
        public void onError(String error) {
            // TODO: Update notification?
        }

        @Override
        public void onClose() {
            // save ports to shared preferences
            PortForwardManager.setPorts(PortForwardService.this, mLocalPort, mRemotePort);
            stopSelf();
        }
    };

    private final BroadcastReceiver mServiceReciever = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(getString(R.string.ACTION_STOP_SERVICE)) ||
                    action.equals(Intent.ACTION_SHUTDOWN)) {
                if (mAccessoryServer != null && mAccessoryServer.isOpen()) {
                    mAccessoryServer.close();  // close the service, but not the accessory
                } else {
                    stopSelf();
                }
            } else if (action.equals(getString(R.string.ACTION_CONNECT_ACCESSORY))) {
                connectServer(intent);
            }
        }
    };

}
