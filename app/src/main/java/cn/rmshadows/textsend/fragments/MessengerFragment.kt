package cn.rmshadows.textsend.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import cn.rmshadows.textsend.databinding.FragmentMessengerBinding
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import kotlinx.coroutines.launch
import utils.ClientMessageController
import utils.Constant
import utils.Message
import utils.SocketDeliver


class MessengerFragment : Fragment() {
    private var _binding: FragmentMessengerBinding? = null
    private val binding get() = _binding!!
    private val tsviewModel: TextsendViewModel by activityViewModels()
    private var lastClipData: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessengerBinding.inflate(inflater, container, false)
        tsviewModel.update(3, null, null, null, null, null, null)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tsviewModel.uiState.collect {
                    // 复制到剪贴板
                    if (it.messageContent == Constant.CF) {
                        // 清空文本框
                        cleanEditText()
                        tsviewModel.update(null,null,null,null,null, "",null)
                    } else if (it.messageContent != lastClipData) {
                        lastClipData = it.messageContent
                        copyToClickboard(it.messageContent)
                    }
                    // 客户端未连接返回上级
                    if (!it.isClientConnected){
                        // 模拟用户返回上级
                        Toast.makeText(
                            context,
                            "Opps...",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.d(Constant.TAG, "onCreateView: 消息界面主动返回")
                        // androidx.navigation.fragment
                        findNavController().popBackStack()
                    }
                }
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 发送
        binding.buttonSend.setOnClickListener {
            if (tsviewModel.uiState.value.isServerMode) {
                // 如果是服务模式
                val m = Message(
                    Constant.SERVER_ID,
                    binding.editText.text.toString(),
                    Constant.MSG_LEN,
                    null
                )
                SocketDeliver.sendMessageToAllClients(m)
            } else {
                ClientMessageController.sendMessageToServer(
                    Message(
                        ClientMessageController.clientId,
                        binding.editText.text.toString(),
                        Constant.MSG_LEN,
                        null
                    ),
                    tsviewModel
                )
            }
        }
    }

    /**
     * 清空文本框
     */
    private fun cleanEditText() {
        binding.editText.text.clear()
    }

    /**
     * 复制到剪贴板
     */
    private fun copyToClickboard(text: String) {
        // Gets a handle to the clipboard service. 获取剪贴板管理器
//        val clipboard = ContextCompat.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipboardManager =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Creates a new text clip to put on the clipboard. 创建ClipData对象
        val clip: ClipData = ClipData.newPlainText("copy text", text)
        // Set the clipboard's primary clip. 设置剪贴板内容
        clipboardManager.setPrimaryClip(clip)
        println("已复制到剪辑板。")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if(! tsviewModel.uiState.value.isServerMode){
            // 客户端
            tsviewModel.update(1, null, false, null, null, null, -1)
        }
        _binding = null
    }
}