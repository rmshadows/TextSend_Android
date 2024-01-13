package cn.rmshadows.textsend.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import cn.rmshadows.textsend.MainActivity
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentClientBinding
import cn.rmshadows.textsend.viewmodels.TextsendViewModel

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class ClientFragment : Fragment() {
    private val TAG: String = MainActivity.TAG
    private var _binding: FragmentClientBinding? = null
    private var toast: Toast? = null
    private lateinit var viewModel: TextsendViewModel

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use the Kotlin extension in the fragment-ktx artifact. QRCODE是识别符
        // 获取二维码扫描值
        setFragmentResultListener("QRCODE") { requestKey, bundle ->
            // We use a String here, but any type that can be put in a Bundle is supported. key是ScanResult
            val result = bundle.getString("ScanResult")
            // Do something with the result.
            Log.d(TAG, "ScanResult : $result")
            // 设置扫描结果
            val tsr = result?.split(":")
            if (tsr != null) {
                if (tsr.size != 2) {
                    // 如果分隔出来不是2个，可能是错误的
                    showToast("请检查二维码内容是否正确：$result")
                } else {
                    binding.clientIpAddress.setText(tsr[0])
                    binding.clientPort.setText(tsr[1])
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClientBinding.inflate(inflater, container, false)
        viewModel = ViewModelProvider(this).get(TextsendViewModel::class.java)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 更改状态
        viewModel.isServerMode.value = false
        // 客户端连接
        binding.buttonConnect.setOnClickListener {
            findNavController().navigate(R.id.action_ClientFragment_to_ServerFragment)
        }

        // 长按重置输入框
        binding.buttonConnect.setOnLongClickListener {
            binding.clientIpAddress.setText(R.string.client_ip_addr_prefix)
            binding.clientPort.setText(R.string.client_port_default)
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