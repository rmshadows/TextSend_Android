package cn.rmshadows.textsend

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import cn.rmshadows.textsend.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {
    companion object {
        // TAG
        const val TAG: String = "==>>APP DEBUG<<=="
        val instance = MainActivity()
        fun cleanEditText() {

        }
        fun getMoreInfo():String { return "This is more fun" }

        // Server
        // 服务器消息自带的ID
        const val SERVER_ID = "-200"
        // 服务器成功接收的反馈信息
        const val FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback"
        // 服务运行的端口号
        var serverListenPort: String? = null
        // 猜测的IP地址
        var preferIpAddr: String? = null
        // 服务端服务是否正在运行
        var serverRunning = false
        // 客户端最大链接数量
        var maxConnection = 1
        const val CUSTOM_INPUT_FLAG = "自定义输入..."

        // Client
        // 作为客户端使用时，生成的Socket
        private val clientSocket: Socket? = null
        // 计时器停止信号
        var scheduleControl = AtomicBoolean(false)
        
        // Public
        // 单个Msg拆分的长度
        const val MSG_LEN = 1000
        // 加密用的Token
        const val AES_TOKEN = "cn.rmshadows.TS_TOKEN"
        // 是否是服务界面
        var isServerMode = true
        // 网卡IP地址
        var netIps = LinkedList<String>()
        // 客户端是否连接(服务端共用)
        var isClientConnected = false

        /**
         * 打印网卡IP并返回可能的局域网IP
         */
        fun getIP(): String? {
            var ip_addr = LinkedList<String>()
            val interfaces: List<NetworkInterface> =
                Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address) { // !addr.isLoopbackAddress &&
                        addr.getHostAddress()?.let { ip_addr.add(it) }
                    }
                }
            }
            var prefer_ip: String? = null
            var find172 = false
            netIps = ip_addr
            netIps.add(CUSTOM_INPUT_FLAG)
            for (ip in ip_addr) {
                try {
                    if (ip.startsWith("192.168")) {
                        prefer_ip = ip
                        break
                    } else if (ip.startsWith("172.")) {
                        prefer_ip = ip
                        find172 = true
                    } else if (ip.startsWith("10.")) {
                        if (!find172) {
                            prefer_ip = ip
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            println("请在您的TextSend安卓客户端中输入手机与电脑同在的局域网的IPv4地址(不出问题的话上面应该有你需要的IP)。")
            if (prefer_ip != null) {
                if (prefer_ip.startsWith("192.168")) {
                    System.out.printf(
                        "猜测您当前的局域网IP是：%s ，具体请根据实际情况进行选择。%n",
                        prefer_ip
                    )
                } else {
                    System.out.printf(
                        "未能猜测到您当前的局域网IP，将使用：%s 作为启动二维码地址！具体可将实际IP填入文本框后启动!%n",
                        prefer_ip
                    )
                }
            }
            try {
                println("\n正在初始化...")
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            return prefer_ip
        }
    }

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_switch -> {
                // 切换模式 TODO: 模式切换还不写
                // https://stackoverflow.com/questions/58686104/why-does-my-navcontroller-cannot-find-an-id-that-i-already-have
                // 必须有上面这一句
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                if(isServerMode){
                    // 如果已经连接会断开
                    if(serverRunning){
                        // 关闭服务
                        TODO()
                    }
                    navController.navigate(R.id.action_ServerFragment_to_ClientFragment)
                }else{
                    // 如果已经连接会断开
                    if(isClientConnected){
                        // 断开
                        TODO()
                    }
                    navController.navigate(R.id.action_ClientFragment_to_ServerFragment)
                }
                true
            }
            R.id.action_about -> {
                // 关于
                val alertDialogBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
                // 如果import中有R，则失效
                alertDialogBuilder.setIcon(R.mipmap.ic_textsend)
                val message = StringBuilder()
                message.append(resources.getString(R.string.app_name_full))
                message.append("\n\nVersion: ")
                message.append(resources.getString(R.string.app_version))
                message.append("\nAuthor: ")
                message.append(resources.getString(R.string.app_version))
                message.append("\nLICENSE: ")
                message.append(resources.getString(R.string.app_License))
                alertDialogBuilder.setTitle("About")
                alertDialogBuilder.setMessage(message)
                alertDialogBuilder.setNegativeButton("Close") { _, _ -> } //...To-do
                alertDialogBuilder.show()
                true
            }
            R.id.action_quit -> {
                // 退出
                val alertDialogBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
                // 如果import中有R，则失效
                alertDialogBuilder.setIcon(R.mipmap.ic_launcher)
                alertDialogBuilder.setTitle("Textsend for Android")
                alertDialogBuilder.setMessage("Quit Textsend for Android ?")
                alertDialogBuilder.setPositiveButton("Quit") { _, _ -> run { exitProcess(0) } }
                alertDialogBuilder.setNegativeButton("Cancel") { _, _ -> } //...To-do
                alertDialogBuilder.show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    fun startClient(socket: Socket): Unit {
        
    }

    fun startServer(socket: Socket): Unit {

    }
}