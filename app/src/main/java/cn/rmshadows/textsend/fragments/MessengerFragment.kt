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
import cn.rmshadows.textsend.KeepAlive
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentMessengerBinding
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import kotlinx.coroutines.launch
import protocol.TsClient
import protocol.TsServer
import utils.Constant


class MessengerFragment : Fragment() {
    private var _binding: FragmentMessengerBinding? = null
    private val binding get() = _binding!!
    private val tsviewModel: TextsendViewModel by activityViewModels()
    private var lastClipData: String = ""
    private var sending = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessengerBinding.inflate(inflater, container, false)
        tsviewModel.update(3, null, null, null, null, null, null)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tsviewModel.uiState.collect {
                    if (it.messageContent == Constant.CF) {
                        cleanEditText()
                        tsviewModel.update(null, null, null, null, null, "", null)
                    } else if (it.messageContent.isNotEmpty() && it.messageContent != lastClipData) {
                        lastClipData = it.messageContent
                        copyToClickboard(it.messageContent)
                    }
                    if (!it.isClientConnected) {
                        if (findNavController().currentDestination?.id == R.id.messengerFragment) {
                            Toast.makeText(context, "Disconnect.", Toast.LENGTH_SHORT).show()
                            Log.d(Constant.TAG, "onCreateView: 消息界面主动返回")
                            findNavController().popBackStack()
                        }
                    }
                }
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonSend.setOnClickListener {
            val text = binding.editText.text.toString()
            if (text.isBlank() || sending) {
                return@setOnClickListener
            }
            sending = true
            val serverMode = tsviewModel.uiState.value.isServerMode
            Thread({
                try {
                    if (serverMode) {
                        val n = TsServer.sendToAllCurrent(text)
                        if (n < 0) {
                            uiSendDone(false, text, "服务未运行")
                            return@Thread
                        }
                        if (n == 0) {
                            uiSendDone(false, text, "没有已连接的客户端")
                            return@Thread
                        }
                    } else {
                        val peer = TsClient.peer
                        if (peer == null || !peer.isAlive) {
                            uiSendDone(false, text, "尚未握手或已断开")
                            return@Thread
                        }
                        peer.sendText(text)
                    }
                    uiSendDone(true, text, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                    uiSendDone(false, text, "发送失败: ${e.message}")
                }
            }, "ts-send").start()
        }
    }

    private fun uiSendDone(ok: Boolean, sent: String, err: String?) {
        val act = activity ?: return
        act.runOnUiThread {
            sending = false
            if (ok) {
                val now = _binding?.editText?.text?.toString()
                if (sent == now) {
                    tsviewModel.cleanEditText()
                }
            } else if (err != null) {
                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cleanEditText() {
        binding.editText.text.clear()
    }

    private fun copyToClickboard(text: String) {
        val clipboardManager =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = ClipData.newPlainText("copy text", text)
        clipboardManager.setPrimaryClip(clip)
        println("已复制到剪辑板。")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 旋转/重建不要拆连接；只有真正离开消息页才断开
        if (!tsviewModel.uiState.value.isServerMode && (isRemoving || requireActivity().isFinishing)) {
            TsClient.close()
            tsviewModel.update(1, null, false, null, null, null, -1)
            KeepAlive.sessionActive = false
        }
        _binding = null
    }
}
