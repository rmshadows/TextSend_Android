package cn.rmshadows.textsend.fragments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentServerBinding
import cn.rmshadows.textsend.viewmodels.ServerFragmentViewModel
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import com.king.zxing.util.CodeUtils
import kotlinx.coroutines.launch
import utils.IPAddressFilter
import java.util.LinkedList


/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class ServerFragment : Fragment(), InputPortNumberDialogFragment.OnInputPortReceived,
    InputIpAddressDialogFragment.OnInputIpReceived {
    private var _binding: FragmentServerBinding? = null
    private var onceCreate = true

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private lateinit var adapter: ArrayAdapter<String>

    // https://developer.android.com/codelabs/basic-android-kotlin-training-shared-viewmodel?hl=zh-cn#4
    private val tsviewModel: TextsendViewModel by activityViewModels()
    private lateinit var viewModel: ServerFragmentViewModel

    // 实现接口
    override fun onInputIpReceived(input: String) {
        val oldIpList = viewModel.uiState.value.netIps
        oldIpList.removeLast() // 去除最后一个
        oldIpList.add(input) // 添加新的IP
        oldIpList.add(viewModel.CUSTOM_INPUT_FLAG) // 添加自定义条目
        viewModel.update(input, null, oldIpList, null, null, null) // 更新下拉框列表
    }

    override fun onInputPortReceived(input: String) {
        viewModel.update(null, input, null, null, null, null)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServerBinding.inflate(inflater, container, false)
        // 更新ui状态
        tsviewModel.update(2, true, null, null, null, null, null)
        viewModel = ViewModelProvider(this)[ServerFragmentViewModel::class.java]
        // 防止IP从Message界面返回后还更新
        if (onceCreate) {
            // 更新IP
            viewModel.getDeviceIP()
            onceCreate = false
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
//            repeatOnLifecycle(viewLifecycleOwner.lifecycle.currentState) {
                viewModel.uiState.collect {
                    // 更新二维码
                    if (it.serverRunning) {
                        val tc: String = if(IPAddressFilter.getIpType(it.preferIpAddr) == 2){
                            // ipv6
                            "[${it.preferIpAddr}]:${it.serverListenPort}"
                        }else{
                            "${it.preferIpAddr}:${it.serverListenPort}"
                        }
                        binding.qrImageView.setImageBitmap(generateQRCode(tc))
                    } else {
                        val bitmap =
                            ResourcesCompat.getDrawable(resources, R.mipmap.ic_textsend, null)
                        binding.qrImageView.setImageDrawable(bitmap)
                    }
                    // 更新切换按钮
                    if (it.serverRunning) {
                        binding.serverStartBtn.setText(R.string.server_stop_btn)
                        // 运行的时候只会显示连接数量
                        val connected: String =
                            "前往消息界面/" + it.clientCount.toString()
                        binding.multiClientBtn.text = connected
                    } else {
                        binding.serverStartBtn.setText(R.string.server_start_btn)
                        // 不在运行才会显示切换模式
                        if (it.maxConnection == 1) {
                            binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
                        } else {
                            binding.multiClientBtn.setText(R.string.server_switch_btn_one)
                        }
                    }
                    // 更新下拉框
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

        // 下拉框选择IP
        binding.ipPortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, pos: Int, id: Long) {
                if (parent.getItemAtPosition(pos).toString() == viewModel.CUSTOM_INPUT_FLAG) {
                    // 自定义修改IP
                    val df = InputIpAddressDialogFragment()
                    df.setOnInputListener(this@ServerFragment)
                    df.show(childFragmentManager, InputIpAddressDialogFragment.TAG)
                } else {
                    val sip = parent.getItemAtPosition(pos).toString()
                    // 会调用两次，所以判断一下有没有变化
                    if (viewModel.uiState.value.preferIpAddr != sip) {
                        viewModel.update(sip, null, null, null, null, null)
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
            if (viewModel.uiState.value.serverRunning) {
                if (viewModel.uiState.value.clientCount == 0) {
                    Toast.makeText(
                        context,
                        "当前似乎没有用户连接！",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    findNavController().navigate(R.id.action_ServerFragment_to_messengerFragment)
                }
            } else {
                if (viewModel.uiState.value.maxConnection == 1) {
                    // 多用户模式最多支持7人
                    viewModel.update(null, null, null, null, 7, null)
                    binding.multiClientBtn.setText(R.string.server_switch_btn_one)
                } else {
                    // 返回单用户模式
                    viewModel.update(null, null, null, null, 1, null)
                    binding.multiClientBtn.setText(R.string.server_switch_btn_multi)
                }
            }
        }

        binding.serverStartBtn.setOnClickListener {
            if (viewModel.uiState.value.serverRunning) {
                // 停止服务
                viewModel.stopServer(tsviewModel, viewModel)
            } else {
                // 启动服务
                viewModel.update(null, null, null, true, null, 0)
                // 显示二维码de操作在UI界面
                viewModel.startServer(tsviewModel, viewModel)
            }
        }

        binding.serverStartBtn.setOnLongClickListener {
            if(! viewModel.uiState.value.serverRunning){
                // 长按修改端口号
                val df = InputPortNumberDialogFragment()
                // 传入接口实现
                df.setOnInputListener(this)
                df.show(childFragmentManager, InputPortNumberDialogFragment.TAG)
            }
            true
        }
    }

    /**
     * 更新下拉框列表
     */
    private fun updateSpinnerAdapter(linkedList: LinkedList<String>): ArrayAdapter<String> {
        return ArrayAdapter<String>(
            this.requireContext(),
            android.R.layout.simple_spinner_item,
            linkedList
        )
    }

    /**
     * 生成二维码
     */
    private fun generateQRCode(content: String): Bitmap {
        var logo: Bitmap? = null
        val drawable = ResourcesCompat.getDrawable(resources, R.mipmap.ic_textsend, null)
        if (drawable != null) {
            logo = drawableToBitmap(drawable)
        }
        return CodeUtils.createQRCode(content, 600, logo, 0.1f)
    }

    /**
     * 将 Drawable 转换为 Bitmap 的方法
     */
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onDestroyView() {
        super.onDestroyView()
//        Log.d(Constant.TAG, "onDestroyView: Server ${tsviewModel.uiState.value.uiIndex}")
        // 此处必须检查服务端是去消息界面还是去客户端界面
        if(tsviewModel.uiState.value.uiIndex != 3){
            tsviewModel.update(1, false, false, null, null, null, -1)
        }
        _binding = null
    }
}
