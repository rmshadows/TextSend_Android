package cn.rmshadows.textsend.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentServerBinding
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import cn.rmshadows.textsend.viewmodels.ServerFragmentViewModel
import kotlinx.coroutines.launch


/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class ServerFragment : Fragment() {
    private var _binding: FragmentServerBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private lateinit var tsviewModel: TextsendViewModel
    private lateinit var viewModel: ServerFragment

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val tsviewModel: TextsendViewModel by viewModels()
        val viewModel: ServerFragmentViewModel by viewModels()

        tsviewModel.update(true, null, null, null)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect {
                    // Update UI elements
                    if (it.maxConnection == 1) {
                        binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
                    } else {
                        binding.multiClientBtn.setText(R.string.server_switch_btn_one)
                    }
                    viewModel.getDeviceIP()
                }
            }
        }


//        viewModel = ViewModelProvider(this).get(TextsendViewModel::class.java)
//        viewLifecycleOwner.lifecycleScope.launch {
//            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
//
//
//            }
//        }

        // Livedata DEBUG
//        // 初始化按钮
//        viewModel.serverRunning.observe(viewLifecycleOwner,  { i ->
//            if (i) {
//                binding.serverStartBtn.setText(R.string.server_stop_btn)
//            } else {
//                binding.serverStartBtn.setText(R.string.server_start_btn)
//            }
//        })
//
//        // 初始化按钮
//        viewModel.maxConnection.observe(viewLifecycleOwner) { i ->
//            if (i == 1) {
//                binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
//            } else {
//                binding.multiClientBtn.setText(R.string.server_switch_btn_one)
//            }
//        }
//
//        // 初始化端口号
//        viewModel.serverListenPort.observe(viewLifecycleOwner) { i ->
//            binding.showPortTextView.text = i
//        }

        _binding = FragmentServerBinding.inflate(inflater, container, false)
        // 获取IP

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.ipPortSpinner.adapter = adapter
        // 默认IP
        binding.ipPortSpinner.setSelection(viewModel.netIps.indexOf(viewModel.preferIpAddr.value))
        // 下拉框选择IP
        binding.ipPortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, pos: Int, id: Long) {
                Toast.makeText(
                    context,
                    "你点击的是:" + pos + parent.getItemAtPosition(pos).toString(),
                    Toast.LENGTH_SHORT
                ).show()
                if (parent.getItemAtPosition(pos).toString().equals(viewModel.CUSTOM_INPUT_FLAG)) {
                    // 自定义修改IP
//                    viewModel.isDialogFragmentAtomClose.set(false)
                    InputIpAddressDialogFragment().show(
                        childFragmentManager,
                        InputIpAddressDialogFragment.TAG
                    )
                    val run: Runnable = Runnable { }
                    // 等待对话框关闭
//                    val scheduleTask = ScheduleTask()
                } else {
//                    viewModel.preferIpAddr.value = parent.getItemAtPosition(pos).toString()
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
            if ( == 1) {
                // 多用户模式最多支持7人
                viewModel.maxConnection.value = 7
                binding.multiClientBtn.setText(R.string.server_switch_btn_one)
            } else {
                // 返回单用户模式
                viewModel.maxConnection.value = 1
                binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
            }
        }

//        // 用户模式切换
//        binding.multiClientBtn.setOnClickListener {
//            if (viewModel.maxConnection.value == 1) {
//                // 多用户模式最多支持7人
//                viewModel.maxConnection.value = 7
//                binding.multiClientBtn.setText(R.string.server_switch_btn_one)
//            } else {
//                // 返回单用户模式
//                viewModel.maxConnection.value = 1
//                binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
//            }
//        }

//        binding.serverStartBtn.setOnClickListener {
//            if (viewModel.serverRunning.value == true) {
//                binding.serverStartBtn.setText(R.string.server_stop_btn)
//            } else {
//                binding.serverStartBtn.setText(R.string.server_start_btn)
//                // 启动服务
//            }
//        }

        binding.serverStartBtn.setOnLongClickListener {
            // 长按修改端口号
            InputPortNumberDialogFragment().show(
                childFragmentManager,
                InputPortNumberDialogFragment.TAG
            )
            // 更新端口号
            binding.showPortTextView.setText(viewModel.serverListenPort.value)
            true
        }
    }

    fun getAtapter(){
        val adapter: ArrayAdapter<String> = ArrayAdapter<String>(
            this.requireContext(),
            android.R.layout.simple_spinner_item,
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.uiState.collect {
                        // Update UI elements
                        if (it.maxConnection == 1) {
                            binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
                        } else {
                            binding.multiClientBtn.setText(R.string.server_switch_btn_one)
                        }
                        viewModel.getDeviceIP()
                    }
                }
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}