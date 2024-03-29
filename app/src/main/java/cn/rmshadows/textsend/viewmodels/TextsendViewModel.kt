package cn.rmshadows.textsend.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import utils.Constant
import java.util.concurrent.atomic.AtomicBoolean


data class TextsendState(
    // 界面 0：Activity 1客户端 2服务端 3消息界面 -1 二维码界面
    val uiIndex:Number = 0,
    // 是否是服务界面
    val isServerMode: Boolean = true,
    // 客户端是否连接(服务端共用)
    val isClientConnected: Boolean = false,
    // atom flag
    val atomFlagA: AtomicBoolean = AtomicBoolean(true),
    val atomFlagB: AtomicBoolean = AtomicBoolean(true),
    // 文本框信息
    val messageContent: String = "",
    // 连接状态 -1:初始化 0:连接成功准备接受ID 1:ID接受成功，准备接受模式（已经将支持的模式发出）2:收到服务器返回的模式 进入正常通信 -2:正在连接
    val connectionStat:Number = -1
)


/**
 * 注意：ViewModel 通常不应引用视图、Lifecycle 或可能存储对 activity 上下文的引用的任何类。由于 ViewModel 的生命周期大于界面的生命周期，因此在 ViewModel 中保留与生命周期相关的 API 可能会导致内存泄漏。
 */
class TextsendViewModel : ViewModel() {
    // Expose screen UI state
    private val _uiState = MutableStateFlow(TextsendState())
    val uiState: StateFlow<TextsendState> = _uiState.asStateFlow()

    fun cleanEditText() {
        update(null, null, null, null, null, Constant.CF, null)
    }

    // Handle business logic
    // 更新数据（替换类）
    fun update(
        uiIndexVal: Number?,
        isServerModeVal: Boolean?,
        isClientConnectedVal: Boolean?,
        atomFlagAVal: AtomicBoolean?,
        atomFlagBVal: AtomicBoolean?,
        messageContentVal: String?,
        connectionStatVal: Number?
    ) {
//        Log.d(Constant.TAG, "updateTs: ui索引: ${uiIndexVal}," +
//                " is服务端:${isServerModeVal}," +
//                " is客户端连接:${isClientConnectedVal}," +
//                " 连接状态: $connectionStatVal")
        _uiState.update { currentState ->
            currentState.copy(
                uiIndex = uiIndexVal ?: currentState.uiIndex,
                isServerMode = isServerModeVal ?: currentState.isServerMode,
                isClientConnected = isClientConnectedVal ?: currentState.isClientConnected,
                atomFlagA = atomFlagAVal?:currentState.atomFlagA,
                atomFlagB = atomFlagBVal?:currentState.atomFlagB,
                messageContent = messageContentVal?:currentState.messageContent,
                connectionStat = connectionStatVal?:currentState.connectionStat
            )
        }
    }
}