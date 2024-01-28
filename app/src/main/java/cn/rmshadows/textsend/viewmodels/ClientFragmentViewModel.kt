package cn.rmshadows.textsend.viewmodels

import ScheduleTask.ScheduleTask
import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import utils.ClientMessageController
import utils.Constant.Companion.TAG
import utils.IPAddressFilter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


data class ClientState(
    // 服务端IP
    val serverIp:String = "192.168.1.",
    // 服务端端口
    val serverPort:Number = 54300
)


/**
 * 注意：ViewModel 通常不应引用视图、Lifecycle 或可能存储对 activity 上下文的引用的任何类。由于 ViewModel 的生命周期大于界面的生命周期，因此在 ViewModel 中保留与生命周期相关的 API 可能会导致内存泄漏。
 */
class ClientFragmentViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ClientState())
    val uiState: StateFlow<ClientState> = _uiState.asStateFlow()
    // 计时器停止信号
    private val scheduleControl = AtomicBoolean(false)

    fun update(
        serverIpVal: String?,
        serverPortVal: Number?
    ) {
        _uiState.update { currentState ->
            currentState.copy(
                serverIp = serverIpVal?:currentState.serverIp,
                serverPort = serverPortVal?:currentState.serverPort
            )
        }
    }

    fun startClient(tsviewModel:TextsendViewModel) {
        Thread {
            try {
                val clientSocket = Socket()
                val sip = uiState.value.serverIp
                val sport = uiState.value.serverPort
                Log.i(TAG, "startClient: IP:${sip}, Port:${sport}")
                clientSocket.connect(InetSocketAddress(sip, sport.toInt()), 5000)
                // 如果连接成功
                tsviewModel.update(null, null, true, null, null, null, 0)
                Thread(ClientMessageController(clientSocket, tsviewModel)).start()
                // 开始监视连接状况
                scheduleControl.set(true)
                // 监视连接断开就恢复按钮状态
                Thread {
                    // 周期任务
                    val checkConnection = Runnable {
                        // 如果断开连接
                        if (!tsviewModel.uiState.value.isClientConnected) {
                            stopClient(tsviewModel)
                        }
                        Log.d(TAG, "checkConnection检查客户端连接......")
                    }
                    // 运行周期任务，并在clientconnect未false时停止
                    val scheduleTask = ScheduleTask(
                        checkConnection,
                        1,
                        1,
                        scheduleControl,
                        TimeUnit.SECONDS
                    )
                    scheduleTask.startTask()
                }.start()
            } catch (e: Exception) {
                // 连接失败
                e.printStackTrace()
                Log.i(TAG, "客户端连接失败。")
                stopClient(tsviewModel)
            }
        }.start()
    }

    fun stopClient(tsviewModel:TextsendViewModel) {
        ClientMessageController.closeClientSocket(tsviewModel)
        scheduleControl.set(false)
    }

    /**
     * 重置输入
     */
    fun resetInput(){
        update("192.168.1.", 54300)
    }

    // 返回的是错误
    fun qrScanResult(result:String) : String?{
        // 设置扫描结果:二维码内容必须 IP+端口
        if(IPAddressFilter.getIpType(result) != 5){
            try {
                val pair = IPAddressFilter.splitIpAndPort(result)
                if(pair != null){
                    update(pair.first, pair.second.toInt())
                }else{
                    return "请检查IP格式是否正确: $result"
                }
            }catch (e:Exception){
                return "请检查二维码内容是否正确：$result"
            }
        }else{
            return "不是有效的(IPv4/[Ipv6]:端口号) ：$result"
        }
        return null
    }
}