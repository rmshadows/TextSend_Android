package cn.rmshadows.textsend.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import utils.Constant
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.LinkedList


data class ServerState(
    // 猜测的IP地址
    val preferIpAddr: String = "127.0.0.1",
    // 服务运行的端口号
    val serverListenPort: String = "54300",
    // IP列表
    val netIps: LinkedList<String> = LinkedList<String>(),
    // 服务端服务是否正在运行
    val serverRunning: Boolean = false,
    // 客户端最大链接数量
    val maxConnection: Number = 1
)


/**
 * 注意：ViewModel 通常不应引用视图、Lifecycle 或可能存储对 activity 上下文的引用的任何类。由于 ViewModel 的生命周期大于界面的生命周期，因此在 ViewModel 中保留与生命周期相关的 API 可能会导致内存泄漏。
 */
class ServerFragmentViewModel : ViewModel() {
    val TAG = Constant.TAG
    // Expose screen UI state
    private val _uiState = MutableStateFlow(ServerState())
    val uiState: StateFlow<ServerState> = _uiState.asStateFlow()

    val CUSTOM_INPUT_FLAG = "手动指定..."

    fun update(
        preferIpAddrVal: String?,
        serverListenPortVal: String?,
        netIpsVal: LinkedList<String>?,
        serverRunningVal: Boolean?,
        maxConnectionVal: Number?
    ) {
        _uiState.update { currentState ->
            Log.d(TAG, "sfupdate: ${preferIpAddrVal}, ${serverListenPortVal}, ${netIpsVal}" +
                    ", ${serverRunningVal}, ${maxConnectionVal}")
            currentState.copy(
                preferIpAddr = preferIpAddrVal ?: currentState.preferIpAddr,
                serverListenPort = serverListenPortVal ?: currentState.serverListenPort,
                netIps = netIpsVal ?: currentState.netIps,
                serverRunning = serverRunningVal ?: currentState.serverRunning,
                maxConnection = maxConnectionVal ?: currentState.maxConnection
            )
        }
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared: ServerViewModel")
        super.onCleared()
    }

    /**
     * 打印网卡IP并返回可能的局域网IP
     */
    fun getDeviceIP() {
        val ip_addr = LinkedList<String>()
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
//        netIps.value = ip_addr
        // 添加自定义条目
        ip_addr.add(CUSTOM_INPUT_FLAG)
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
//        return prefer_ip
        update(prefer_ip, null, ip_addr, null, null)
    }
}