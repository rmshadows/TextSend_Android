package cn.rmshadows.textsend.fragments

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentClientBinding
import cn.rmshadows.textsend.viewmodels.ClientFragmentViewModel
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import kotlinx.coroutines.launch

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class ClientFragment : Fragment() {
    private var _binding: FragmentClientBinding? = null
    private var toast: Toast? = null
    private var appliedFillSeq = -1
    private var appliedErrorSeq = 0

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!
    private val tsviewModel: TextsendViewModel by activityViewModels()
    private lateinit var viewModel: ClientFragmentViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ClientFragmentViewModel::class.java]
        // 初始化状态（接下来都以这个为主，不会重复运行这个）
        tsviewModel.update(1, false, false, null, null, null, null)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tsviewModel.uiState.collect {
                    if (it.isServerMode) {
                        return@collect
                    }
                    if (it.isClientConnected) {
                        val dest = findNavController().currentDestination?.id
                        if (dest == R.id.ClientFragment) {
                            findNavController().navigate(R.id.action_ClientFragment_to_messengerFragment)
                        }
                    }
                    if (_binding != null) {
                        binding.buttonConnect.text = if (it.connectionStat == -2) "正在连接..." else "连接"
                    }
                }
            }
        }

        setFragmentResultListener("QRCODE") { _, bundle ->
            val result = bundle.getString("ScanResult")
            val error = viewModel.qrScanResult(result.toString())
            if (error != null) {
                showToast(error)
            } else {
                showToast("已填入 IP / 端口 / 密钥，请确认 IP 后点连接")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClientBinding.inflate(inflater, container, false)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect {
                    if (tsviewModel.uiState.value.isServerMode) {
                        return@collect
                    }
                    if (it.errorSeq != appliedErrorSeq) {
                        appliedErrorSeq = it.errorSeq
                        val err = it.lastError
                        if (!err.isNullOrBlank()) {
                            showToast(err)
                        }
                    }
                    if (it.fillSeq == appliedFillSeq) {
                        return@collect
                    }
                    appliedFillSeq = it.fillSeq
                    binding.clientIpAddress.setText(it.serverIp)
                    binding.clientPort.setText(it.serverPort.toString())
                    binding.clientPin.setText(it.keyMaterial)
                }
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonPaste.setOnClickListener {
            val text = readClipboard()
            if (text.isNullOrBlank()) {
                showToast("剪贴板是空的")
                return@setOnClickListener
            }
            val err = viewModel.applyRaw(text)
            if (err != null) {
                showToast(err)
            } else {
                showToast("已从连接串填入，请确认 IP 后点连接")
            }
        }

        binding.buttonConnect.setOnClickListener {
            viewModel.applyFields(
                binding.clientIpAddress.text.toString(),
                binding.clientPort.text.toString().toIntOrNull() ?: 54300,
                binding.clientPin.text.toString()
            )
            if (tsviewModel.uiState.value.connectionStat == -1) {
                val err = viewModel.startClient(tsviewModel)
                if (err != null) {
                    showToast(err)
                }
            } else {
                viewModel.stopClient(tsviewModel)
            }
        }

        binding.buttonConnect.setOnLongClickListener {
            viewModel.resetInput()
            true
        }

        binding.fab.setOnClickListener {
            findNavController().navigate(R.id.action_ClientFragment_to_qrScannerFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun readClipboard(): String? {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount < 1) {
            return null
        }
        return clip.getItemAt(0).coerceToText(requireContext())?.toString()
    }

    private fun showToast(text: String) {
        if (toast == null) {
            toast = Toast.makeText(this.context, text, Toast.LENGTH_LONG)
        } else {
            toast?.setText(text)
        }
        toast?.show()
    }
}
