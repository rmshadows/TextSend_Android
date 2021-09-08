package cn.rmshadows.textsend;

import android.content.DialogInterface;
import android.os.Bundle;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import cn.rmshadows.textsend.databinding.ActivityMainBinding;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.LinkedList;

public class MainActivity extends AppCompatActivity {
    /**
     * 通用参数
      */
    // 服务器消息自带的ID
    final public static int SERVER_ID = -200;
    // 服务器成功接收的反馈信息
    final public static String FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback";
    // 单个Msg拆分的长度
    final public static int MSG_LEN = 1000;
    // 加密用的Token
    final public static String AES_TOKEN = "cn.rmshadows.TS_TOKEN";
    // 是否是服务界面
    public static boolean is_server = false;
    // 服务运行的端口号
    public static String server_port = "54300";
    // 猜测的IP地址
    public static String Prefer_Addr;
    // 位于的页面
    /**
     *  索引、页面
     *
     * -2 = ServerQR 服务端二维码显示页面
     * -1 = ServerConfig 服务端连接页面
     * 0 = MainActivity
     * 1 = ClientConfig 客户端连接页面
     * 2 = ClientMsg 客户端发送页面
     * 3 = Capture 扫一扫连接
     */
    public static int page_view_index = 0;
    /**
     * 客户端
      */
    // 作为客户端使用时，生成的Socket
    public static Socket socket_of_client = null;
    // 客户端是否连接
    public static boolean is_client_connected = false;
    // 扫码结果
    public static String client_qr_scan_result = "SCAN_RESULT";
    // 是否启用过二维码扫描
    public static boolean qr_coding_yet = false;
    /**
     * 服务端
     */
    // 服务端是否连接
    public static boolean is_server_connected = false;
    // Socket Server服务是否正在运行
    public static boolean is_server_running = false;

    /**
     * 下面是App组件
     */
    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;
    public static MainActivity mainActivity;

    // 载入视图时
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        // 检测是否已连接， 没有就返回
        new Thread(new Runnable() {
            @Override
            public void run() {
                while(true){
                    // 检查间隔
                    try {
                        Thread.sleep(350);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    // 这里是检测是否处于服务端
                    if(page_view_index == 2){
                        // 不做处理
                    }
                    else if(page_view_index >= 1){
                        is_server = false;
                    }else if (page_view_index <=-1){
                        is_server =true;
                    }

                    // 下面是检测客户端页面
                    if(!is_server){
                        // 这里需要用到RunOnUIThread，要不报错
                        // 当客户端未连接
                        if(!MainActivity.is_client_connected) {
                            // 在发送页面
                            if(MainActivity.page_view_index == 2){
                                MainActivity.this.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Thread.sleep(200);
                                        }catch (Exception e){
                                            e.printStackTrace();
                                        }
                                        if(!is_client_connected && MainActivity.page_view_index == 2){
                                            ClientMsgFragment.back2PreviousNav();
                                            System.out.println("Client未连接，返回连接页面");
                                        }
                                    }
                                });
                            }
                        }else{//客户端已连接，但是不在发送页面
                            if(MainActivity.page_view_index != 2){
                                try {
                                    Thread.sleep(200);
                                }catch (Exception e){
                                    e.printStackTrace();
                                }
                                if(MainActivity.is_client_connected && MainActivity.page_view_index != 2){
                                    closeClientSocket();
                                }
                            }
                        }
                    }else{// 服务端
                        if(MainActivity.page_view_index == 2 && !MainActivity.is_server_connected){
                            ClientMsgFragment.back2PreviousNav();
                        }
                    }
                }
            }
        }).start();

        Prefer_Addr = getIP();

        binding.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(page_view_index == 1 && !qr_coding_yet){
                    // 打开二维码扫描
                    ClientFragment.goScanQR();
                }else{
                    if(!qr_coding_yet){
                        Snackbar.make(view, "请在客户端连接界面使用二维码扫描功能！", Snackbar.LENGTH_LONG)
                                .setAction("Action", null).show();
                    }
                }
            }
        });
    }

    //载入视图后
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
         mainActivity = MainActivity.this;
        return true;
    }

    // 菜单被选择时
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_mode) {
//            Log.d("==>>Menu", "模式");
            if(page_view_index == 1 || page_view_index == -1) {
                if(is_server){// 转客户端
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ServerFragment.goClient();
                        }
                    });
                }else{//转服务端
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ClientFragment.goServer();
                        }
                    });
                }
            }
//            return true;
        }else if(id == R.id.action_about){
            /* @setIcon 设置对话框图标
             * @setTitle 设置对话框标题
             * @setMessage 设置对话框消息提示
             * setXXX方法返回Dialog对象，因此可以链式设置属性
             */
            final AlertDialog.Builder normalDialog =
                    new AlertDialog.Builder(MainActivity.this);
            normalDialog.setIcon(R.mipmap.ic_launcher_logo);
            normalDialog.setTitle("About");
            normalDialog.setMessage("Text Send for Android\n\n" +
                    "Version: 3.1.3\n" +
                    "Author: Ryan Yim\n" +
                    "LICENSE: GPLv3");
            normalDialog.setPositiveButton("Close",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            //...To-do
                        }
                    });
            // 显示
            normalDialog.show();
        }else if(id == R.id.action_quit){
            // 退出
            final AlertDialog.Builder quitDialog =
                    new AlertDialog.Builder(MainActivity.this);
            quitDialog.setIcon(R.mipmap.ic_launcher);
            quitDialog.setTitle("Text Send");
            quitDialog.setMessage("Quit Text Send for Android ?");
            quitDialog.setPositiveButton("Quit", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            System.exit(0);
                        }
                    });
            quitDialog.setNegativeButton("Cancel",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            //...To-do
                        }
                    });
            // 显示
            quitDialog.show();
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }


    /**
     *  客户端关闭socket
     */
    public static void closeClientSocket(){
        Log.d("==>>APP_LOG<<==","closeClientSocket():开始关闭Client.");
        try{
            socket_of_client.close();
            is_client_connected = false;
            socket_of_client = null;
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * 显示Toast提示信息
     * @param tip 信息
     * @param Toast_len 显示时长
     */
    public static void showToast(String tip, int Toast_len){
        //Toast.LENGTH_SHORT
        // Can't toast on a thread that has not called Looper.prepare()
        mainActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.mainActivity, tip, Toast_len).show();
            }
        });
    }

    /**
     * 获取IP
     * @return
     */
    public static String getIP() {// get all local ips
        try{
            LinkedList<String> ip_addr = new LinkedList<>();
            Enumeration<NetworkInterface> interfs = NetworkInterface.getNetworkInterfaces();
            System.out.println("正在獲取电脑本地IP....");
            int n = 1;
            boolean getStatus = false;
            while (interfs.hasMoreElements()) {
                NetworkInterface interf = interfs.nextElement();
                Enumeration<InetAddress> addres = interf.getInetAddresses();
                if (n == 1 | getStatus) {
                    System.out.println("<------第" + n + "组网卡------>");
                    getStatus = false;
                }
                while (addres.hasMoreElements()) {
                    InetAddress in = addres.nextElement();
                    if (in instanceof Inet4Address) {
                        System.out.println(" - IPv4地址:" + in.getHostAddress());
                        ip_addr.add(in.getHostAddress());
                        getStatus = true;
                    } else if (in instanceof Inet6Address) {
                        System.out.println(" - IPv6地址:" + in.getHostAddress());
                        ip_addr.add(in.getHostAddress());
                        getStatus = true;
                    }
                }
                if (getStatus) {
                    n += 1;
                }
            }
            String prefer_ip = null;
            boolean find172 = false;

            for (String ip : ip_addr) {
                try{
                    if (ip.substring(0, 7).equals("192.168")) {
                        prefer_ip = ip;
                        break;
                    } else if (ip.substring(0, 4).equals("172.")) {
                        prefer_ip = ip;
                        find172 = true;
                    } else if (ip.substring(0, 3).equals("10.")) {
                        if (!find172) {
                            prefer_ip = ip;
                        }
                    }
                }
                catch (Exception e){
//                    e.printStackTrace();
                    Log.d("无影响警告：",e.toString());
                }
            }

            System.out.println("<--没有第" + n + "组网卡，如果以上结果没有显示出你所在局域网的IP地址。请手动查看您的IPv4地址谢谢-->\n");
            System.out.println("请在您的TextSend安卓客户端中输入手机与电脑同在的局域网的IPv4地址(不出问题的话上面应该有你需要的IP)。");
            if (prefer_ip.substring(0, 7).equals("192.168")) {
                System.out.println(String.format("猜测您当前的局域网IP是：%s ，具体请根据实际情况进行选择。", prefer_ip));
            } else {
                System.out.println(String.format("未能猜测到您当前的局域网IP，将使用：%s 作为启动二维码地址！具体可将实际IP填入文本框后启动!", prefer_ip));
            }

            System.out.println("\n======>>>>>> 好的，准备就绪<<<<<<======");
            return prefer_ip;
        }catch (Exception e){
            e.printStackTrace();
            return null;
        }
    }

}