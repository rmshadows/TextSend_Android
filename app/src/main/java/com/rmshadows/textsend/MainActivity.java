package com.rmshadows.textsend;

import androidx.appcompat.app.AppCompatActivity;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

public class MainActivity extends AppCompatActivity {

    private Button back;
    private Button send;
    private EditText toSend;
    private String LASTEST="";

    private String ip_addr = null;
    private String ports = null;

    private ConnectThread connectThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        back = (Button)findViewById(R.id.back);
        send = (Button)findViewById(R.id.send);
        toSend = findViewById(R.id.toSend);

        try {
            Bundle bundle = getIntent().getExtras();
            ip_addr = bundle.getString("ip_addr");
            ports = bundle.getString("ports");
            System.out.println("====>>>>连接IP为"+ip_addr+"的电脑，端口号是"+ports+"。");
            connectThread = new ConnectThread();
            if(ip_addr!=null) {
                try {
                    connectThread.start();
                }
                catch (Exception e){
                    String E = e.toString();
                    System.out.println("====>>>>连接失败-onCreate()"+E);
                    Toast.makeText(getBaseContext(),"连接失效！",Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                    startActivity(intent);
                    MainActivity.this.onStop();
                }
            }
            else{
                Toast.makeText(getBaseContext(),"未获取到IP",Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                startActivity(intent);
                MainActivity.this.onStop();
            }
        }
        catch (Exception e) {
            Toast.makeText(getBaseContext(),"请重新登录！",Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            startActivity(intent);
            MainActivity.this.onStop();
        }

        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    connectThread.socket.close();
                }catch (IOException e){
                    e.printStackTrace();
                }
                connectThread.socket=null;
                Intent intent = new Intent(MainActivity.this,LoginActivity.class);
                startActivity(intent);
                MainActivity.this.onStop();
            }
        });

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(connectThread.socket!=null) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            String sendtext = "(i386@received):"+toSend.getText().toString();
//                            System.out.println("==<<"+sendtext);
                            sendtext = AES_Util.encrypt("RmY@TextSend!",sendtext);
                            LASTEST = sendtext;
//                            System.out.println("==<<"+sendtext);
//                            System.out.println("==<<Decode:"+AES_Util.decrypt("RmY@TextSend!",sendtext));
                            if (sendtext.equals("(i386@received):")) {
                                sendtext = "";
                                return;
                            }
                            try {
                                LASTEST = sendtext;
                                connectThread.ops.write(sendtext.getBytes());
                                connectThread.ops.flush();
                                System.out.println("====<<Sended.");
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }).start();
//                    toSend.setText("");
                }
            }
        });
    }

    class ConnectThread extends Thread {

        private Socket socket = null;
        OutputStream ops = null;
        InputStream ips = null;

        public void run() {
            boolean IP_NULL = false;
            try {
                InetAddress ip = InetAddress.getByName(ip_addr);
                final int port;
                if (ports.equals("")) {
                    port = 54300;
                } else {
                    port = Integer.valueOf(ports);
                }
                if (ip.toString().equals("localhost/::1")) {
                    System.out.println("====>>>>IP=null");
                    IP_NULL = true;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                            builder.setTitle("提示");
                            builder.setMessage("IP地址不正确。");
                            builder.setPositiveButton("是", null);
                            builder.show();
                            new Handler(new Handler.Callback() {
                                // 处理接收到消息的方法
                                @Override
                                public boolean handleMessage(Message msg) {
                                    Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                                    startActivity(intent);
                                    MainActivity.this.onStop();
                                    return false;
                                }
                            }).sendEmptyMessageDelayed(0,1000);
                        }
                    });
                }
//                socket = new Socket(ip, port);
//                socket.setSoTimeout(5000);
                socket = new Socket();
                SocketAddress socAddress = new InetSocketAddress(ip, port);
                socket.connect(socAddress, 5000); //超时时间!

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getBaseContext(),"连接成功！",Toast.LENGTH_LONG).show();
                    }
                });

                if (null == socket) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            System.out.println("====>>>>Socket_null:" + socket);
                            Toast.makeText(getApplicationContext(), "连接失败(Socket=Null)", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                ops = socket.getOutputStream();
                while (true) {
                    final byte[] buffer = new byte[1024000];
                    ips = socket.getInputStream();
                    final int len = ips.read(buffer);
                    if (len != 0) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String rec = new String(buffer,0,len);
                                String Frec = AES_Util.decrypt("RmY@TextSend!",LASTEST).substring(16,17);
                                rec = AES_Util.decrypt("RmY@TextSend!",rec);
                                System.out.println("<<>>手机端收到消息:"+rec);
                                if(rec.equals("(i386@RETRY)")){
                                    try {
                                        ops.write(LASTEST.getBytes());
                                        ops.flush();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                                else if(rec.substring(0,16).equals("(i386@received):")){
                                    if(rec.substring(16,17).equals(Frec)) {
                                        System.out.println("==>>电脑端已收到.");
                                        toSend.setText("");
                                    }
                                    else{
                                        try {
                                            ops.write(LASTEST.getBytes());
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                                else if (rec.substring(0,16).equals("(amd6@received):")){
                                    rec = rec.substring(16);
                                    try{
                                        //获取剪贴板管理器：
                                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                        // 创建普通字符型ClipData
                                        ClipData mClipData = ClipData.newPlainText("Label", rec);
                                        // 将ClipData内容放到系统剪贴板里。
                                        cm.setPrimaryClip(mClipData);
                                    }catch (Exception e){
                                        System.out.println(e.toString());
                                    }
                                }
                                else{
                                    try {
                                        ops.write(LASTEST.getBytes());
                                        ops.flush();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        });
                    }
                }
            }
            catch (UnknownHostException e) {
                System.out.println("====>>>>ERROR-0.");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                        builder.setTitle("提示");
                        builder.setMessage("未知主机。");
                        builder.setPositiveButton("是", null);
                        builder.show();
                        new Handler(new Handler.Callback() {
                            // 处理接收到消息的方法
                            @Override
                            public boolean handleMessage(Message msg) {
                                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                                startActivity(intent);
                                MainActivity.this.onStop();
                                return false;
                            }
                        }).sendEmptyMessageDelayed(0,2000);
                    }
                });
                e.printStackTrace();
            }
            catch (SocketTimeoutException e){
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                        builder.setTitle("提示");
                        builder.setMessage("连接超时。");
                        builder.setPositiveButton("是", null);
                        builder.show();
                        new Handler(new Handler.Callback() {
                            // 处理接收到消息的方法
                            @Override
                            public boolean handleMessage(Message msg) {
                                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                                startActivity(intent);
                                MainActivity.this.onStop();
                                return false;
                            }
                        }).sendEmptyMessageDelayed(0,2000);
                    }
                });
            }
            catch (IOException e) {
                System.out.println("====>>>>ERROR-1.");
                if (IP_NULL) {

                } else {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                            builder.setTitle("提示");
                            builder.setMessage("连接失败。");
                            builder.setPositiveButton("是", null);
                            builder.show();
                            new Handler(new Handler.Callback() {
                                // 处理接收到消息的方法
                                @Override
                                public boolean handleMessage(Message msg) {
                                    Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                                    startActivity(intent);
                                    MainActivity.this.onStop();
                                    return false;
                                }
                            }).sendEmptyMessageDelayed(0,2000);
                        }
                    });
                }
                e.printStackTrace();
            }
        }
    }
}