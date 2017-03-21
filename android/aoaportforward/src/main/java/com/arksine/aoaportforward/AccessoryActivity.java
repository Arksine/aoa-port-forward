package com.arksine.aoaportforward;

import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

public class AccessoryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent accIntent = getIntent();
        UsbAccessory accessory = accIntent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        // TODO: get accessory from parcelable and send it to Service, OR use a static
        // singleton to store the accessory
        if (!Utils.isServiceRunning(PortForwardService.class, this)) {
            Intent startIntent = new Intent(this, PortForwardService.class);
            startIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            this.startService(startIntent);
        }else {
            // Broadcast
            Intent connectIntent = new Intent(getString(R.string.ACTION_CONNECT_ACCESSORY));
            connectIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            sendBroadcast(connectIntent);
        }

        finish();
    }


}
