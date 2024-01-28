package cn.rmshadows.textsend.fragments

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
                viewModel.uiState.collect {
                    tsviewModel.uiState.collect {
                        // 要求客户端模式才运行！！！
                        if(! it.isServerMode){
                            // 已连接才会跳转
                            if(it.isClientConnected){
                                findNavController().navigate(R.id.action_ClientFragment_to_messengerFragment)
                            }
                            if(it.connectionStat == -2){
                                binding.buttonConnect.text = "正在连接..."
                            }else{
                                binding.buttonConnect.text = "连接"
                            }
                        }
                    }
                }
            }
        }

        // 获取二维码扫描值 Use the Kotlin extension in the fragment-ktx artifact. QRCODE是识别符
        setFragmentResultListener("QRCODE") { _, bundle ->
            // We use a String here, but any type that can be put in a Bundle is supported. key是ScanResult
            val result = bundle.getString("ScanResult")
            // Do something with the result.
            val error = viewModel.qrScanResult(result.toString())
            if (error != null) {
                showToast(error)
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
                    // 客户端模式才运行
                    if(!tsviewModel.uiState.value.isServerMode){
                        binding.clientIpAddress.setText(it.serverIp)
                        binding.clientPort.setText(it.serverPort.toString())
                    }
                }
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 客户端连接
        binding.buttonConnect.setOnClickListener {
            viewModel.update(binding.clientIpAddress.text.toString(), binding.clientPort.text.toString().toInt())
            if(tsviewModel.uiState.value.connectionStat == -1){
                viewModel.startClient(tsviewModel)
            }else{
                viewModel.stopClient(tsviewModel)
            }
        }

        // 长按重置输入框
        binding.buttonConnect.setOnLongClickListener {
            viewModel.resetInput()
            // 返回true消耗点击
            true
        }

        // 跳转扫描二维码界面
        binding.fab.setOnClickListener {
            findNavController().navigate(R.id.action_ClientFragment_to_qrScannerFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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