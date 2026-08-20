package cn.rmshadows.textsend.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import cn.rmshadows.textsend.KeepAlive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import protocol.TsClient
import protocol.TsPeer
import protocol.TsUri
import utils.Constant.Companion.TAG
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException


data class ClientState(
    val serverIp: String = "192.168.",
    val serverPort: Int = 54300,
    /** 8 位 PIN 或扫码/粘贴得到的长密钥 */
    val keyMaterial: String = "",
    /** 扫码 / 粘贴 / 重置时 +1，界面只在这时回填，避免盖住用户改 IP */
    val fillSeq: Int = 0,
    /** 连接失败时 +1，界面弹短提示 */
    val errorSeq: Int = 0,
    val lastError: String? = null
)


class ClientFragmentViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ClientState())
    val uiState: StateFlow<ClientState> = _uiState.asStateFlow()

    fun applyFields(ip: String, port: Int, secret: String) {
        val uri = parseTs(ip) ?: parseTs(secret)
        if (uri != null) {
            applyUri(uri)
            return
        }
        _uiState.update {
            it.copy(
                serverIp = ip.trim(),
                serverPort = port,
                keyMaterial = secret.trim()
            )
        }
    }

    fun applyUri(uri: TsUri) {
        _uiState.update {
            it.copy(
                serverIp = uri.host,
                serverPort = uri.port,
                keyMaterial = uri.k,
                fillSeq = it.fillSeq + 1
            )
        }
    }

    /** @return 错误信息；成功为 null */
    fun applyRaw(raw: String): String? {
        val uri = parseTs(raw) ?: return "剪贴板不是 ts:// 连接串"
        applyUri(uri)
        return null
    }

    fun qrScanResult(result: String): String? {
        val uri = parseTs(result) ?: return "请扫描新版 ts:// 二维码"
        applyUri(uri)
        return null
    }

    fun resetInput() {
        _uiState.update {
            ClientState(fillSeq = it.fillSeq + 1)
        }
    }

    /** @return 立即失败时的错误信息；成功启动后台连接则 null */
    fun startClient(tsviewModel: TextsendViewModel): String? {
        val st = uiState.value
        val k = st.keyMaterial.trim()
        if (k.isEmpty()) {
            return "请填 PIN 或长密钥，或粘贴 / 扫描 ts://"
        }
        val uri: TsUri
        try {
            uri = TsUri.parse(TsUri.format(st.serverIp.trim(), st.serverPort, k))
        } catch (e: Exception) {
            return e.message ?: "连接串无效"
        }
        tsviewModel.update(null, null, false, null, null, null, -2)
        KeepAlive.sessionActive = true
        TsClient.close()
        TsClient.abort = false
        Thread({
            try {
                Log.i(TAG, "startClient: ${uri.host}:${uri.port} mode=" + if (uri.pinMode) "PIN" else "PSK")
                val sock = Socket()
                TsClient.socket = sock
                sock.connect(InetSocketAddress(uri.host, uri.port), 5000)
                if (TsClient.abort) {
                    sock.close()
                    return@Thread
                }
                val peer = TsPeer(sock, uri, { msg -> Log.i(TAG, msg) }, {
                    tsviewModel.update(null, null, true, null, null, null, 2)
                }, {
                    val reached = tsviewModel.uiState.value.isClientConnected
                    TsClient.peer = null
                    tsviewModel.update(null, null, false, null, null, null, -1)
                    KeepAlive.sessionActive = false
                    if (!reached && !TsClient.abort) {
                        reportFail("握手失败，请确认 PIN 或密钥")
                    }
                }, { text ->
                    tsviewModel.update(null, null, null, null, null, text, null)
                })
                TsClient.peer = peer
                if (TsClient.abort) {
                    peer.close()
                    return@Thread
                }
                peer.run()
            } catch (e: Exception) {
                e.printStackTrace()
                Log.i(TAG, "客户端连接失败: ${e.message}")
                val aborted = TsClient.abort
                TsClient.close()
                tsviewModel.update(null, null, false, null, null, null, -1)
                KeepAlive.sessionActive = false
                if (!aborted) {
                    reportFail(connectFailText(e))
                }
            }
        }, "ts-client").start()
        return null
    }

    fun stopClient(tsviewModel: TextsendViewModel) {
        TsClient.close()
        tsviewModel.update(null, null, false, null, null, null, -1)
        KeepAlive.sessionActive = false
    }

    private fun reportFail(msg: String) {
        _uiState.update {
            it.copy(errorSeq = it.errorSeq + 1, lastError = msg)
        }
    }

    private fun connectFailText(e: Exception): String {
        return when (e) {
            is SocketTimeoutException -> "连不上（超时），请确认 IP 和端口"
            is ConnectException -> "连不上，请确认电脑已启动且同一网段"
            is UnknownHostException -> "IP 无效"
            else -> "连接失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun parseTs(raw: String?): TsUri? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val line = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return try {
            TsUri.parse(line)
        } catch (_: Exception) {
            null
        }
    }
}
