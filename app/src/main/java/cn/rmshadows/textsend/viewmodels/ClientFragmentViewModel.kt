package cn.rmshadows.textsend.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import utils.Constant
import java.net.Socket


data class ClientState(
    // 作为客户端使用时，生成的Socket
    var clientSocket:Socket = Socket()
)


/**
 * 注意：ViewModel 通常不应引用视图、Lifecycle 或可能存储对 activity 上下文的引用的任何类。由于 ViewModel 的生命周期大于界面的生命周期，因此在 ViewModel 中保留与生命周期相关的 API 可能会导致内存泄漏。
 */
class ClientFragmentViewModel : ViewModel() {
    val TAG = Constant.TAG
    private val _uiState = MutableStateFlow(ClientState())
    val uiState: StateFlow<ClientState> = _uiState.asStateFlow()

    fun update(
        socketVal: Socket?
    ) {
        _uiState.update { currentState ->
            Log.d(TAG, "cfupdate: ${socketVal}")
            currentState.copy(
                clientSocket = socketVal ?: currentState.clientSocket,
            )
        }
    }
}