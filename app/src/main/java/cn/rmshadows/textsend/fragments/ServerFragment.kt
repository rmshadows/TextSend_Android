package cn.rmshadows.textsend.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import cn.rmshadows.textsend.LanAuthGate
import cn.rmshadows.textsend.LanDiscover
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentServerBinding
import cn.rmshadows.textsend.viewmodels.ServerFragmentViewModel
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import com.google.android.material.button.MaterialButton
import com.king.zxing.util.CodeUtils
import kotlinx.coroutines.launch
import protocol.TsServer
import protocol.TsUri
import java.util.LinkedList


/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class ServerFragment : Fragment(), InputPortNumberDialogFragment.OnInputPortReceived,
    InputIpAddressDialogFragment.OnInputIpReceived {
    private var _binding: FragmentServerBinding? = null
    private var onceCreate = true
    private var spinnerIps: List<String>? = null
    private var spinnerPrefer: String? = null
    private var lastAutoCopiedPsk = ""
    private var authSig = ""
    private var aliveSig = ""
    private var authDialog: AlertDialog? = null
    private var authDialogHost: String? = null

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
        input.toIntOrNull()?.takeIf { it in 1..65535 }?.let { LanDiscover.setConnectPort(it) }
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
                    if (_binding == null) {
                        return@collect
                    }
                    if (it.listenError.isNotEmpty()) {
                        val ctx = context
                        if (ctx != null) {
                            Toast.makeText(ctx, it.listenError, Toast.LENGTH_LONG).show()
                            viewModel.update(null, null, null, null, null, null, null, null, "")
                        }
                    }
                    // 更新二维码。生成失败只提示，不能让收集协程把进程带走。
                    try {
                        if (it.serverRunning && it.pskB64.isNotEmpty()) {
                            val port = it.serverListenPort.toIntOrNull()
                            if (port != null) {
                                val uri = TsUri.format(it.preferIpAddr, port, it.pskB64)
                                binding.qrImageView.setImageBitmap(generateQRCode(uri))
                            }
                        } else {
                            val bitmap =
                                ResourcesCompat.getDrawable(resources, R.mipmap.ic_textsend, null)
                            binding.qrImageView.setImageDrawable(bitmap)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
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
                    binding.showPortTextView.text = "端口 ${it.serverListenPort}"
                    if (it.serverRunning && it.pin.isNotEmpty()) {
                        binding.pinButton.visibility = View.VISIBLE
                        binding.pinButton.text = "PIN  ${it.pin}"
                    } else {
                        binding.pinButton.visibility = View.GONE
                    }
                    if (it.serverRunning && it.pskB64.isNotEmpty() && it.pskB64 != lastAutoCopiedPsk) {
                        lastAutoCopiedPsk = it.pskB64
                        try {
                            copyPskUri()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (!it.serverRunning) {
                        lastAutoCopiedPsk = ""
                    }
                    binding.serverTextView.setText(
                        if (it.serverRunning) R.string.server_copy_hint
                        else R.string.server_port_hint
                    )
                    val ips = it.netIps
                    val prefer = it.preferIpAddr
                    if (ips !== spinnerIps || prefer != spinnerPrefer) {
                        spinnerIps = ips
                        spinnerPrefer = prefer
                        adapter = updateSpinnerAdapter(ips)
                        binding.ipPortSpinner.setAdapter(adapter)
                        val idx = ips.indexOf(prefer)
                        if (idx >= 0) {
                            binding.ipPortSpinner.setText(ips[idx], false)
                        }
                    }
                    refreshAliveClients(it.serverRunning)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LanAuthGate.snap.collect { snap ->
                    if (_binding == null) {
                        return@collect
                    }
                    renderAuthPending(viewModel.uiState.value.serverRunning, snap.pending)
                }
            }
        }

        adapter = updateSpinnerAdapter(viewModel.uiState.value.netIps)
        binding.ipPortSpinner.setAdapter(adapter)
        binding.ipPortSpinner.keyListener = null
        binding.ipPortSpinner.setOnItemClickListener { parent, _, pos, _ ->
            if (_binding == null || pos < 0) {
                return@setOnItemClickListener
            }
            val sip = parent.getItemAtPosition(pos).toString()
            if (sip == viewModel.CUSTOM_INPUT_FLAG) {
                val df = InputIpAddressDialogFragment()
                df.setOnInputListener(this@ServerFragment)
                df.show(childFragmentManager, InputIpAddressDialogFragment.TAG)
                return@setOnItemClickListener
            }
            if (viewModel.uiState.value.preferIpAddr != sip) {
                viewModel.update(sip, null, null, null, null, null)
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
                viewModel.startServer(tsviewModel, viewModel)
            }
        }

        binding.serverStartBtn.setOnLongClickListener {
            if (!viewModel.uiState.value.serverRunning) {
                showPortDialog()
            }
            true
        }

        binding.showPortTextView.setOnClickListener {
            if (!viewModel.uiState.value.serverRunning) {
                showPortDialog()
            }
        }
        binding.showPortTextView.setOnLongClickListener {
            if (!viewModel.uiState.value.serverRunning) {
                showPortDialog()
            }
            true
        }
        binding.pinButton.setOnClickListener {
            copyPinUri()
        }
        binding.pinButton.setOnLongClickListener {
            copyPinOnly()
            true
        }
        binding.qrImageView.setOnClickListener {
            copyPskUri()
        }
    }

    private fun showPortDialog() {
        val df = InputPortNumberDialogFragment()
        df.setOnInputListener(this)
        df.show(childFragmentManager, InputPortNumberDialogFragment.TAG)
    }

    private fun renderAuthPending(serverRunning: Boolean, pending: List<LanAuthGate.PendingItem>) {
        if (_binding == null) {
            return
        }
        if (!serverRunning || pending.isEmpty()) {
            authSig = ""
            binding.authPendingBox.visibility = View.GONE
            binding.authPendingList.removeAllViews()
            dismissAuthDialog()
            return
        }
        val sig = pending.joinToString("|") { "${it.host}\u0000${it.name}\u0000${it.at}" }
        val changed = sig != authSig
        if (changed || binding.authPendingBox.visibility != View.VISIBLE) {
            authSig = sig
            binding.authPendingBox.visibility = View.VISIBLE
            binding.authPendingTitle.text = getString(R.string.server_auth_pending_title) + " ${pending.size}"
            binding.authPendingList.removeAllViews()
            val density = resources.displayMetrics.density
            for (item in pending) {
                binding.authPendingList.addView(buildAuthRow(item, density))
            }
            if (pending.size > 1) {
                val denyAll = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                denyAll.text = getString(R.string.server_auth_deny_all)
                denyAll.isAllCaps = false
                denyAll.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
                denyAll.setOnClickListener { LanAuthGate.denyAll() }
                binding.authPendingList.addView(denyAll)
            }
        }
        // 新请求弹窗，避免只在二维码下面、不滚动就看不到
        val newest = pending.last()
        if (changed || authDialogHost != newest.host || authDialog?.isShowing != true) {
            showAuthDialog(newest)
        }
    }

    private fun showAuthDialog(item: LanAuthGate.PendingItem) {
        if (!isAdded || _binding == null) {
            return
        }
        if (authDialog?.isShowing == true && authDialogHost == item.host) {
            return
        }
        dismissAuthDialog()
        authDialogHost = item.host
        authDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.server_auth_pending_title)
            .setMessage("${item.name}\n${item.host}")
            .setPositiveButton(R.string.server_auth_allow) { _, _ ->
                LanAuthGate.decide(item.host, "allow")
            }
            .setNegativeButton(R.string.server_auth_deny) { _, _ ->
                LanAuthGate.decide(item.host, "deny")
            }
            .setNeutralButton(R.string.server_auth_blacklist) { _, _ ->
                LanAuthGate.decide(item.host, "blacklist")
            }
            .setOnDismissListener {
                if (authDialogHost == item.host) {
                    authDialog = null
                    authDialogHost = null
                }
            }
            .setCancelable(true)
            .show()
        Toast.makeText(
            context,
            getString(R.string.server_auth_pending_title) + "：${item.name}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dismissAuthDialog() {
        try {
            authDialog?.dismiss()
        } catch (_: Exception) {
        }
        authDialog = null
        authDialogHost = null
    }

    private fun buildAuthRow(item: LanAuthGate.PendingItem, density: Float): View {
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        }
        val label = TextView(requireContext()).apply {
            text = "${item.name}  ·  ${item.host}"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        col.addView(label)
        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        actions.addView(authActionBtn(R.string.server_auth_allow, density) {
            LanAuthGate.decide(item.host, "allow")
        })
        actions.addView(authActionBtn(R.string.server_auth_deny, density) {
            LanAuthGate.decide(item.host, "deny")
        })
        actions.addView(authActionBtn(R.string.server_auth_blacklist, density) {
            LanAuthGate.decide(item.host, "blacklist")
        })
        col.addView(actions)
        return col
    }

    private fun authActionBtn(labelRes: Int, density: Float, onClick: () -> Unit): MaterialButton {
        val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btn.text = getString(labelRes)
        btn.isAllCaps = false
        btn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = (4 * density).toInt()
        }
        btn.setOnClickListener { onClick() }
        return btn
    }

    private fun refreshAliveClients(serverRunning: Boolean) {
        if (_binding == null) {
            return
        }
        if (!serverRunning) {
            aliveSig = ""
            binding.aliveClientsBox.visibility = View.GONE
            binding.aliveClientsList.removeAllViews()
            return
        }
        val clients = TsServer.listAliveClientsCurrent()
        if (clients.isEmpty()) {
            aliveSig = ""
            binding.aliveClientsBox.visibility = View.GONE
            binding.aliveClientsList.removeAllViews()
            return
        }
        val max = viewModel.uiState.value.maxConnection.toInt().coerceAtLeast(1)
        val sig = clients.joinToString("|") { "${it.host}\u0000${it.port}\u0000${it.name}" } + "|$max"
        if (sig == aliveSig && binding.aliveClientsBox.visibility == View.VISIBLE) {
            return
        }
        aliveSig = sig
        binding.aliveClientsBox.visibility = View.VISIBLE
        val title = if (max > 1) {
            getString(R.string.server_alive_clients_title) + " ${clients.size} / $max"
        } else {
            getString(R.string.server_alive_clients_title) + " ${clients.size}"
        }
        binding.aliveClientsTitle.text = title
        binding.aliveClientsList.removeAllViews()
        val density = resources.displayMetrics.density
        for (c in clients) {
            binding.aliveClientsList.addView(buildAliveRow(c, density))
        }
        if (clients.size > 1) {
            val kickAll = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            kickAll.text = getString(R.string.server_kick_all)
            kickAll.isAllCaps = false
            kickAll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
            kickAll.setOnClickListener {
                for (c in TsServer.listAliveClientsCurrent()) {
                    TsServer.kickHostCurrent(c.host)
                }
                aliveSig = ""
                refreshAliveClients(true)
            }
            binding.aliveClientsList.addView(kickAll)
        }
    }

    private fun buildAliveRow(client: TsServer.AliveClient, density: Float): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
        }
        val label = TextView(requireContext()).apply {
            text = client.displayLabel()
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val kick = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        kick.text = getString(R.string.server_kick)
        kick.isAllCaps = false
        kick.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        kick.setOnClickListener {
            TsServer.kickHostCurrent(client.host)
            aliveSig = ""
            refreshAliveClients(true)
        }
        row.addView(label)
        row.addView(kick)
        return row
    }

    private fun copyPinUri() {
        val st = viewModel.uiState.value
        if (!st.serverRunning || st.pin.isEmpty()) {
            return
        }
        if (st.preferIpAddr.isBlank() || st.preferIpAddr == viewModel.CUSTOM_INPUT_FLAG) {
            showCopyToast("请先选择局域网 IP")
            return
        }
        copyText(TsUri.format(st.preferIpAddr, st.serverListenPort.toInt(), st.pin), "已复制 PIN 连接串")
    }

    private fun copyPinOnly() {
        val pin = viewModel.uiState.value.pin
        if (pin.isEmpty()) {
            return
        }
        copyText(pin, "已复制 PIN")
    }

    private fun copyPskUri() {
        val st = viewModel.uiState.value
        if (!st.serverRunning || st.pskB64.isEmpty()) {
            return
        }
        if (st.preferIpAddr.isBlank() || st.preferIpAddr == viewModel.CUSTOM_INPUT_FLAG) {
            showCopyToast("请先选择局域网 IP")
            return
        }
        copyText(TsUri.format(st.preferIpAddr, st.serverListenPort.toInt(), st.pskB64), "已复制密钥连接串")
    }

    private fun copyText(text: String, toast: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("textsend", text))
        showCopyToast(toast)
    }

    private fun showCopyToast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /**
     * 更新下拉框列表
     */
    private fun updateSpinnerAdapter(linkedList: LinkedList<String>): ArrayAdapter<String> {
        return ArrayAdapter(
            requireContext(),
            R.layout.item_ip_dropdown,
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
        dismissAuthDialog()
        super.onDestroyView()
//        Log.d(Constant.TAG, "onDestroyView: Server ${tsviewModel.uiState.value.uiIndex}")
        // 此处必须检查服务端是去消息界面还是去客户端界面
        if(tsviewModel.uiState.value.uiIndex != 3){
            tsviewModel.update(1, false, false, null, null, null, -1)
        }
        _binding = null
    }
}
