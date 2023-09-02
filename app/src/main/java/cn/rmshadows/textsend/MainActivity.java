package cn.rmshadows.textsend;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import cn.rmshadows.textsend.databinding.ActivityMainBinding;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    final public static String VERSION = String.valueOf(R.string.version);
    // Public
    // 单个Msg拆分的长度
    final public static int MSG_LEN = 1000;
    // 加密用的Token
    final public static String AES_TOKEN = "cn.rmshadows.TS_TOKEN";
    // 是否是处于服务界面
    public static boolean isServerMode = false;

    // Android
    // 位于的页面
    /**
     * 索引、页面
     * <p>
     * -2 = ServerQR 服务端二维码显示页面
     * -1 = ServerConfig 服务端连接页面
     * 0 = MainActivity
     * 1 = ClientConfig 客户端连接页面
     * 2 = ClientMsg 客户端发送页面
     * 3 = Capture 扫一扫连接
     * </p>
     */
    public static int pageViewIndex = 0;
    // 发送页面
    protected static ClientMsgFragment clientMessageFragment;
    // 发送页面文本框 // TODO:Do not place Android context classes in static fields (static reference to `MainActivity` which has field `editTextSending` pointing to `EditText`); this is a memory leak
    protected static EditText editTextSending;

    /**
     * 客户端
     */
    // 作为客户端使用时，生成的Socket
    public static Socket clientSocket = null;
    // 客户端是否连接
    public static boolean isClientConnected = false;
    // 扫码结果
    public static String clientQrScanResult = "SCAN_RESULT";
    // 是否启用过二维码扫描 进入二维码扫描页面会将qrCodingYet变为true 如果下次进入客户端连接页面发现此值是true，
    // 会将二维码扫描结果填入框中，并设置qrCodingYet为false
    public static boolean qrCodingYet = false;
    // 计时器停止信号
    public static AtomicBoolean scheduleControl = new AtomicBoolean(false);

    /**
     * 服务端
     */
    // 服务端是否被连接
    public static boolean isServerConnected = false;
    // 服务端服务是否正在运行(Socket Server服务是否正在运行)
    private static boolean serverRunning = false;
    // 服务器消息自带的ID
    final public static String SERVER_ID = "-200";
    // 服务器成功接收的反馈信息
    final public static String FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback";
    // 服务运行的端口号
    private static String serverListenPort;
    // 猜测的IP地址
    public static String preferIpAddr;
    // 客户端最大链接数量
    public static int maxConnection = 1;

    /**
     * 下面是App组件
     */
    private AppBarConfiguration appBarConfiguration;
    public static MainActivity mainActivity;

    /**
     * 停止服务端
     */
    public static void stopServer() {

    }

    /**
     * 清空文本框
     */
    public static void cleanTextArea() {
        // 清空
        if (MainActivity.isClientConnected || MainActivity.isServerRunning()) {
            MainActivity.mainActivity.runOnUiThread(() -> {
                //Accessibility content change on non-UI thread. Future Android versions will throw an exception.
                if (!editTextSending.getText().toString().equals("")) {
                    editTextSending.setText("");
                }
            });
        }
    }

    // 载入视图时
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cn.rmshadows.textsend.databinding.ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        // 检测是否已连接， 没有就返回
        new Thread(() -> {
            while (true) {
                // 检查间隔
                try {
                    Thread.sleep(350);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // 这里是检测是否处于服务端 分配参数isServerMode
                if (pageViewIndex >= 1) {
                    isServerMode = false;
                } else if (pageViewIndex <= -1) {
                    isServerMode = true;
                }

                // 下面是检测客户端页面
                if (!isServerMode) {
                    // 这里需要用到RunOnUIThread，要不报错
                    // 当客户端未连接
                    if (!MainActivity.isClientConnected) {
                        // 如果在发送页面 如果没有连接则返回连接页面
                        if (MainActivity.pageViewIndex == 2) {
                            MainActivity.this.runOnUiThread(() -> {
                                try {
                                    Thread.sleep(200);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                if (!isClientConnected && MainActivity.pageViewIndex == 2) {
                                    ClientMsgFragment.backToPreviousNav();
                                    System.out.println("Client未连接，返回连接页面");
                                }
                            });
                        }
                    } else {
                        //客户端已连接，但是不在发送页面 断开连接
                        if (MainActivity.pageViewIndex != 2) {
                            try {
                                Thread.sleep(200);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            if (MainActivity.isClientConnected && MainActivity.pageViewIndex != 2) {
                                closeClientSocket();
                            }
                        }
                    }
                } else {
                    // 服务端 不再发送页面 且服务端未连接
                    if (MainActivity.pageViewIndex == 2 && !MainActivity.isServerConnected) {
                        ClientMsgFragment.backToPreviousNav();
                    }
                }
            }
        }).start();

        // 获取默认IP
        preferIpAddr = getIP();

        // 当照相机按钮被点击
        binding.fab.setOnClickListener(view -> {
            // 如果是连接页面 且未扫描过二维码, 打开二维码扫描
            if (pageViewIndex == 1 && !qrCodingYet) {
                ClientFragment.goScanQR();
            } else {
                // 如果是其他页面
                if (!qrCodingYet) {
                    Snackbar.make(view, "请在客户端连接界面使用二维码扫描功能！", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 载入菜单
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
        // 获取菜单index
        int id = item.getItemId();
        //noinspection SimplifiableIfStatement
        if (id == R.id.action_mode) {
            // 切换模式
            if (pageViewIndex == 1 || pageViewIndex == -1) {
                if (isServerMode) {
                    // 转客户端
                    MainActivity.this.runOnUiThread(ServerFragment::goClient);
                } else {
                    // 转服务端
                    MainActivity.this.runOnUiThread(ClientFragment::goServer);
                }
            }
        } else if (id == R.id.action_about) {
            // 关于
            /* @setIcon 设置对话框图标
             * @setTitle 设置对话框标题
             * @setMessage 设置对话框消息提示
             * setXXX方法返回Dialog对象，因此可以链式设置属性
             */
            final AlertDialog.Builder normalDialog =
                    new AlertDialog.Builder(MainActivity.this);
            normalDialog.setIcon(R.mipmap.ic_launcher_logo);
            normalDialog.setTitle("About");
            normalDialog.setMessage(R.string.app_full_name + "\n\n" +
                    "Version: " + R.string.version + "\n" +
                    "Author: " + R.string.author + "\n" +
                    "LICENSE: GPLv3");
            normalDialog.setPositiveButton("Close", (dialog, which) -> {
                //...To-do
            });
            // 显示
            normalDialog.show();
        } else if (id == R.id.action_quit) {
            // 菜单退出
            final AlertDialog.Builder quitDialog =
                    new AlertDialog.Builder(MainActivity.this);
            quitDialog.setIcon(R.mipmap.ic_launcher);
            quitDialog.setTitle("Textsend Android");
            quitDialog.setMessage("Quit Text Send for Android ?");
            quitDialog.setPositiveButton("Quit", (dialog, which) -> System.exit(0));
            quitDialog.setNegativeButton("Cancel", (dialog, which) -> {
            });
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
     * 客户端关闭socket
     */
    public static void closeClientSocket() {
        Log.d("==>>APP_LOG<<==", "closeClientSocket():开始关闭Client.");
        try {
            clientSocket.close();
            isClientConnected = false;
            clientSocket = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 显示Toast提示信息
     *
     * @param tip       信息
     * @param Toast_len 显示时长
     */
    public static void showToast(String tip, int Toast_len) {
        // Toast.LENGTH_SHORT
        // Can't toast on a thread that has not called Looper.prepare()
        mainActivity.runOnUiThread(() -> Toast.makeText(MainActivity.mainActivity, tip, Toast_len).show());
    }

    /**
     * 复制收到的消息到剪贴板
     */
    public static void copyToClickboardAndroid(String text) {
        MainActivity.mainActivity.runOnUiThread(() -> {
            try {
                //获取剪贴板管理器：
                ClipboardManager cm = (ClipboardManager) MainActivity.mainActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                // 创建普通字符型ClipData
                ClipData mClipData = ClipData.newPlainText("Label", text);
                // 将ClipData内容放到系统剪贴板里。
                cm.setPrimaryClip(mClipData);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 获取IP  get all local ips
     */
    public static String getIP() {
        try {
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
            // 网卡IP地址
            for (String ip : ip_addr) {
                try {
                    if (ip.startsWith("192.168")) {
                        prefer_ip = ip;
                        break;
                    } else if (ip.startsWith("172.")) {
                        prefer_ip = ip;
                        find172 = true;
                    } else if (ip.startsWith("10.")) {
                        if (!find172) {
                            prefer_ip = ip;
                        }
                    }
                } catch (Exception e) {
//                    e.printStackTrace();
                    Log.d("无影响警告：", e.toString());
                }
            }

            System.out.println("<--没有第" + n + "组网卡，如果以上结果没有显示出你所在局域网的IP地址。请手动查看您的IPv4地址谢谢-->\n");
            System.out.println("请在您的TextSend安卓客户端中输入手机与电脑同在的局域网的IPv4地址(不出问题的话上面应该有你需要的IP)。");
            assert prefer_ip != null;
            if (prefer_ip.startsWith("192.168")) {
                System.out.printf("猜测您当前的局域网IP是：%s ，具体请根据实际情况进行选择。%n", prefer_ip);
            } else {
                System.out.printf("未能猜测到您当前的局域网IP，将使用：%s 作为启动二维码地址！具体可将实际IP填入文本框后启动!%n", prefer_ip);
            }
            System.out.println("\n======>>>>>> 好的，准备就绪<<<<<<======");
            return prefer_ip;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // getter setter

    public static Socket getClientSocket() {
        return clientSocket;
    }

    public static void setClientSocket(Socket clientSocket) {
        MainActivity.clientSocket = clientSocket;
    }

    public static boolean isServerRunning() {
        return serverRunning;
    }

    public static void setServerRunning(boolean serverRunning) {
        MainActivity.serverRunning = serverRunning;
    }

    public static int getServerListenPort() {
        return Integer.parseInt(serverListenPort);
    }

    public static void setServerListenPort(String serverListenPort) {
        MainActivity.serverListenPort = serverListenPort;
    }
}