package cn.rmshadows.textsend.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import cn.rmshadows.textsend.InputDialogFragment
import cn.rmshadows.textsend.MainActivity
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentServerBinding


/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class ServerFragment : Fragment() {
    private var _binding: FragmentServerBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        MainActivity.isServerMode = true
        _binding = FragmentServerBinding.inflate(inflater, container, false)
        // 获取IP
        MainActivity.preferIpAddr = MainActivity.getIP()
        val adapter: ArrayAdapter<String> = ArrayAdapter<String>(this.requireContext(), android.R.layout.simple_spinner_item, MainActivity.netIps)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.ipPortSpinner.adapter = adapter
        // 默认IP
        binding.ipPortSpinner.setSelection(MainActivity.netIps.indexOf(MainActivity.preferIpAddr))
        // 下拉框选择IP
        binding.ipPortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, pos: Int, id: Long) {
                Toast.makeText(
                    context,
                    "你点击的是:" + pos + parent.getItemAtPosition(pos).toString(),
                    Toast.LENGTH_SHORT
                ).show()
                if(parent.getItemAtPosition(pos).toString().equals(MainActivity.CUSTOM_INPUT_FLAG)){
                    // TODO: 后面要分开写！
                    InputDialogFragment().show(childFragmentManager, "InputDialog")
                }else{
                    MainActivity.preferIpAddr = parent.getItemAtPosition(pos).toString()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Another interface callback
            }
        }

        // 初始化按钮状态
        if (MainActivity.serverRunning) {
            binding.serverStartBtn.setText(R.string.server_stop_btn)
        } else {
            binding.serverStartBtn.setText(R.string.server_start_btn)
        }
        if (MainActivity.maxConnection == 1) {
            binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
        } else {
            binding.multiClientBtn.setText(R.string.server_switch_btn_one)
        }
        // 初始化端口号
        binding.showPortTextView.text = MainActivity.serverListenPort
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 用户模式切换
        binding.multiClientBtn.setOnClickListener {
            if (MainActivity.maxConnection == 1) {
                // 多用户模式最多支持7人
                MainActivity.maxConnection = 7
                binding.multiClientBtn.setText(R.string.server_switch_btn_one)
            } else {
                // 返回单用户模式
                MainActivity.maxConnection = 1
                binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
            }
        }

        binding.serverStartBtn.setOnClickListener {
            if (MainActivity.serverRunning) {
                binding.serverStartBtn.setText(R.string.server_stop_btn)
            } else {
                binding.serverStartBtn.setText(R.string.server_start_btn)
                // 启动服务
            }
        }

        binding.serverStartBtn.setOnLongClickListener{
            // 长按修改端口号
            InputDialogFragment().show(childFragmentManager, "InputDialog")
            true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


}