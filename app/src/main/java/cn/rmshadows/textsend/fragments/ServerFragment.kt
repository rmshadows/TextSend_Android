package cn.rmshadows.textsend.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentServerBinding
import cn.rmshadows.textsend.viewmodels.ServerFragmentViewModel
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import kotlinx.coroutines.launch
import utils.Constant
import java.util.LinkedList


/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class ServerFragment : Fragment(), InputPortNumberDialogFragment.OnInputPortReceived,
    InputIpAddressDialogFragment.OnInputIpReceived {
    private var _binding: FragmentServerBinding? = null
    private val TAG = Constant.TAG

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private lateinit var adapter: ArrayAdapter<String>

    // https://developer.android.com/codelabs/basic-android-kotlin-training-shared-viewmodel?hl=zh-cn#4
    private val tsviewModel: TextsendViewModel by activityViewModels()
    private lateinit var viewModel: ServerFragmentViewModel

    // 实现接口
    override fun onInputIpReceived(input: String) {
        val oips = viewModel.uiState.value.netIps
        oips.removeLast() // 去除最后一个
        oips.add(input) // 添加新的IP
        oips.add(viewModel.CUSTOM_INPUT_FLAG) // 添加自定义条目
        viewModel.update(input, null, oips, null, null) // 更新下拉框列表
    }

    override fun onInputPortReceived(input: String) {
        viewModel.update(null, input, null, null, null)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServerBinding.inflate(inflater, container, false)
        // 更新ui状态
        tsviewModel.update(2 ,true, null, null)
        viewModel = ViewModelProvider(this).get(ServerFragmentViewModel::class.java)
        // 更新IP
        viewModel.getDeviceIP()

        lifecycleScope.launch {
            // 每秒执行
            repeatOnLifecycle(Lifecycle.State.STARTED) {
//            repeatOnLifecycle(viewLifecycleOwner.lifecycle.currentState) {
                viewModel.uiState.collect {
                    // 更新组件
                    if (it.maxConnection == 1) {
                        binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
                    } else {
                        binding.multiClientBtn.setText(R.string.server_switch_btn_one)
                    }
                    if (it.serverRunning) {
                        binding.serverStartBtn.setText(R.string.server_stop_btn)
                    } else {
                        binding.serverStartBtn.setText(R.string.server_start_btn)
                    }
                    binding.showPortTextView.text = it.serverListenPort
                    adapter = updateSpinnerAdapter(viewModel.uiState.value.netIps)
                    binding.ipPortSpinner.adapter = adapter
                    binding.ipPortSpinner.setSelection(
                        viewModel.uiState.value.netIps.indexOf(
                            viewModel.uiState.value.preferIpAddr
                        )
                    )
                }
            }
        }

        adapter = updateSpinnerAdapter(viewModel.uiState.value.netIps)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        // 默认IP
//        binding.ipPortSpinner.setSelection(viewModel.uiState.value.netIps.indexOf(viewModel.uiState.value.preferIpAddr))

        // 下拉框选择IP
        binding.ipPortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, pos: Int, id: Long) {
//                Toast.makeText(
//                    context,
//                    "你点击的是:" + pos + parent.getItemAtPosition(pos).toString(),
//                    Toast.LENGTH_SHORT
//                ).show()
                if (parent.getItemAtPosition(pos).toString().equals(viewModel.CUSTOM_INPUT_FLAG)) {
                    // 自定义修改IP
                    val df = InputIpAddressDialogFragment()
                    df.setOnInputListener(this@ServerFragment)
                    df.show(childFragmentManager, InputIpAddressDialogFragment.TAG)
                } else {
                    val sip = parent.getItemAtPosition(pos).toString()
                    // 会调用两次，所以判断一下有没有变化
                    if (viewModel.uiState.value.preferIpAddr != sip) {
                        viewModel.update(sip, null, null, null, null)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Another interface callback
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 用户模式切换
        binding.multiClientBtn.setOnClickListener {
            if (viewModel.uiState.value.maxConnection == 1) {
                // 多用户模式最多支持7人
                viewModel.update(null, null, null, null, 7)
                binding.multiClientBtn.setText(R.string.server_switch_btn_one)
            } else {
                // 返回单用户模式
                viewModel.update(null, null, null, null, 1)
                binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
            }
        }

        binding.serverStartBtn.setOnClickListener {
            if (viewModel.uiState.value.serverRunning) {
                // 停止服务
                TODO()
            } else {
                // 启动服务
                TODO()
            }
        }

        binding.serverStartBtn.setOnLongClickListener {
            // 长按修改端口号
            val df = InputPortNumberDialogFragment()
            // 传入接口实现
            df.setOnInputListener(this)
            df.show(childFragmentManager, InputPortNumberDialogFragment.TAG)
            true
        }
    }

    fun updateSpinnerAdapter(newlist: LinkedList<String>): ArrayAdapter<String> {
        return ArrayAdapter<String>(
            this.requireContext(),
            android.R.layout.simple_spinner_item,
            newlist
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
