package com.rmshadows.textsend;

import androidx.appcompat.app.AppCompatActivity;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

public class LoginActivity extends AppCompatActivity {

    private Button connect;
    private Button help;
    private EditText ip_addr;
    private EditText port;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        connect = (Button)findViewById(R.id.connect);
        help = (Button)findViewById(R.id.help);
        ip_addr = findViewById(R.id.ip_addr);
        port = findViewById(R.id.port);

        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                System.out.println("====>>>>Connecting...");
                Intent intent = new Intent(LoginActivity.this,MainActivity.class);
                Bundle bundle = new Bundle();
                bundle.putString("ip_addr",ip_addr.getText().toString());
                bundle.putString("ports",port.getText().toString());
//                System.out.println(ip_addr.getText().toString()+"<= =>"+port.getText().toString());
                intent.putExtras(bundle);
                System.out.println("==>>"+bundle+"==>>"+intent);
                startActivity(intent);
                finish();
                System.out.println("====>>>>Back to MainActivity.");
            }
        });

        help.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(LoginActivity.this,HelpActivity.class);
                startActivity(intent);
                LoginActivity.this.onStop();
            }
        });
    }
}

