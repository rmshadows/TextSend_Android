package cn.rmshadows.textsend.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Socket
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicBoolean


data class TextsendState(// 是否是服务界面
    val isServerMode: Boolean = true,
    // 客户端是否连接(服务端共用)
    val isClientConnected: Boolean = false,
    // dialog fragment flag
    val isDialogFragmentAtomClose: AtomicBoolean = AtomicBoolean(true)
)


/**
 * 注意：ViewModel 通常不应引用视图、Lifecycle 或可能存储对 activity 上下文的引用的任何类。由于 ViewModel 的生命周期大于界面的生命周期，因此在 ViewModel 中保留与生命周期相关的 API 可能会导致内存泄漏。
 */
class TextsendViewModel : ViewModel() {
    // Expose screen UI state
    private val _uiState = MutableStateFlow(TextsendState())
    val uiState: StateFlow<TextsendState> = _uiState.asStateFlow()

    fun cleanEditText() {

    }

    // Server
    // 服务器消息自带的ID
    val SERVER_ID = "-200"

    // 服务器成功接收的反馈信息
    val FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback"

    // Client
    // 作为客户端使用时，生成的Socket
    var clientSocket = MutableStateFlow<Socket>(Socket())

    // 计时器停止信号
    var scheduleControl = MutableStateFlow(AtomicBoolean(false))

    // Public
    // 单个Msg拆分的长度
    val MSG_LEN = 1000
    // 加密用的Token
    val AES_TOKEN = "cn.rmshadows.TS_TOKEN"

    // Handle business logic
    fun update(
        isServerModeVal: Boolean?,
        netIpsVal: LinkedList<String>?,
        isClientConnectedVal: Boolean?,
        isDialogFragmentAtomCloseVal: AtomicBoolean?
    ) {
        _uiState.update { currentState ->
            currentState.copy(
                isServerMode = isServerModeVal ?: currentState.isServerMode,
                isClientConnected = isClientConnectedVal ?: currentState.isClientConnected,
                isDialogFragmentAtomClose = isDialogFragmentAtomCloseVal
                    ?: currentState.isDialogFragmentAtomClose
            )
        }
    }
}