package cn.rmshadows.textsend.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import cn.rmshadows.textsend.KeepAlive
import protocol.PairingMaterial
import protocol.TsServer
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.LinkedList


data class ServerState(
    val preferIpAddr: String = "",
    val serverListenPort: String = "54300",
    val netIps: LinkedList<String> = LinkedList<String>(),
    val serverRunning: Boolean = false,
    val maxConnection: Number = 1,
    val clientCount: Number = 0,
    val pin: String = "",
    val pskB64: String = "",
    val listenError: String = ""
)


class ServerFragmentViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ServerState())
    val uiState: StateFlow<ServerState> = _uiState.asStateFlow()

    val CUSTOM_INPUT_FLAG = "手动指定..."
    private var serverGen = 0

    fun update(
        preferIpAddrVal: String?,
        serverListenPortVal: String?,
        netIpsVal: LinkedList<String>?,
        serverRunningVal: Boolean?,
        maxConnectionVal: Number?,
        clientCountVal: Number?,
        pinVal: String? = null,
        pskB64Val: String? = null,
        listenErrorVal: String? = null
    ) {
        _uiState.update { currentState ->
            currentState.copy(
                preferIpAddr = preferIpAddrVal ?: currentState.preferIpAddr,
                serverListenPort = serverListenPortVal ?: currentState.serverListenPort,
                netIps = netIpsVal ?: currentState.netIps,
                serverRunning = serverRunningVal ?: currentState.serverRunning,
                maxConnection = maxConnectionVal ?: currentState.maxConnection,
                clientCount = clientCountVal ?: currentState.clientCount,
                pin = pinVal ?: currentState.pin,
                pskB64 = pskB64Val ?: currentState.pskB64,
                listenError = listenErrorVal ?: currentState.listenError
            )
        }
    }

    fun getDeviceIP() {
        val lan = LinkedList<String>()
        val junk = LinkedList<String>()
        val interfaces: List<NetworkInterface> =
            Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (addr.isAnyLocalAddress || addr.isMulticastAddress) {
                    continue
                }
                val host = addr.hostAddress?.split("%")?.get(0) ?: continue
                val bucket = if (isJunkHost(addr, host)) junk else lan
                if (!bucket.contains(host)) {
                    bucket.add(host)
                }
            }
        }
        var preferIp: String? = null
        var find172 = false
        for (ip in lan) {
            if (ip.startsWith("192.168")) {
                preferIp = ip
                break
            } else if (ip.startsWith("172.")) {
                preferIp = ip
                find172 = true
            } else if (ip.startsWith("10.") && !find172) {
                preferIp = ip
            }
        }
        if (preferIp == null && lan.isNotEmpty()) {
            preferIp = lan.first
        }
        val ipAddr = LinkedList<String>()
        ipAddr.addAll(lan)
        ipAddr.addAll(junk)
        ipAddr.add(CUSTOM_INPUT_FLAG)
        println("请选择与对端同一局域网的 IP。127.0.0.1 / fe80 / 169.254 仍在列表末尾，默认不选。")
        if (preferIp != null) {
            System.out.printf("猜测局域网 IP：%s%n", preferIp)
        }
        update(preferIp ?: "", null, ipAddr, null, null, null)
    }

    /** 回环、链路本地：列表里留着，不当默认 */
    private fun isJunkHost(addr: InetAddress, raw: String): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress) {
            return true
        }
        if (raw.equals("127.0.0.1", ignoreCase = true) || raw == "::1") {
            return true
        }
        return raw.startsWith("fe80", ignoreCase = true) || raw.startsWith("169.254")
    }

    fun startServer(tsviewModel: TextsendViewModel, viewModel: ServerFragmentViewModel) {
        serverGen++
        val gen = serverGen
        TsServer.stopCurrent()
        val material = PairingMaterial.generate()
        val port = uiState.value.serverListenPort.toInt()
        val maxConn = uiState.value.maxConnection.toInt()
        update(null, null, null, true, null, 0, material.pin, material.pskB64, "")
        KeepAlive.sessionActive = true
        val server = TsServer(port, maxConn, material, { count ->
            if (gen == serverGen) {
                viewModel.update(null, null, null, null, null, count)
                tsviewModel.update(null, null, count > 0, null, null, null, null)
            }
        }, { text ->
            if (gen == serverGen) {
                tsviewModel.update(null, null, null, null, null, text, null)
            }
        }, { err ->
            if (gen == serverGen) {
                viewModel.update(null, null, null, false, null, 0, "", "", err ?: "")
                tsviewModel.update(null, null, false, null, null, null, -1)
                KeepAlive.sessionActive = false
            }
        })
        Thread(server, "ts-server").start()
    }

    fun stopServer(tsviewModel: TextsendViewModel, viewModel: ServerFragmentViewModel) {
        serverGen++
        TsServer.stopCurrent()
        viewModel.update(null, null, null, false, null, 0, "", "", "")
        tsviewModel.update(null, null, false, null, null, null, -1)
        KeepAlive.sessionActive = false
    }
}
