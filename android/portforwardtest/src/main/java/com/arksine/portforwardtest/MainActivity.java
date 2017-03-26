package com.arksine.portforwardtest;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.arksine.aoaportforward.PortForwardManager;

public class MainActivity extends AppCompatActivity {

    private EditText mLocalPort;
    private EditText mRemotePort;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mLocalPort = (EditText) findViewById(R.id.txt_local_port);
        mRemotePort = (EditText) findViewById(R.id.txt_remote_port);
        Button btn = (Button) findViewById(R.id.btnStart);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String lPort = mLocalPort.getText().toString();
                String rPort = mRemotePort.getText().toString();

                int localPort = 8000;
                int remotePort = 8000;
                if (!lPort.equals("")) {
                    localPort = Integer.parseInt(lPort);
                    if (localPort < 0 || localPort > 99999)
                        localPort = 8000;
                }

                if (!rPort.equals("")) {
                    remotePort = Integer.parseInt(rPort);
                    if (remotePort < 0 || remotePort > 99999)
                        remotePort = 8000;
                }

                PortForwardManager.startPortForwardService(getApplicationContext(), localPort, remotePort);


            }
        });

    }

}
