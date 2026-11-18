package cn.rmshadows.textsend.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
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
import cn.rmshadows.textsend.ButtonFeel
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
    private var sessionDialog: AlertDialog? = null
    private var sessionList: LinearLayout? = null
    private var sessionSig = ""
    private var sessionTargets: List<SessionTarget> = emptyList()
    private var sessionCursor = 0

    private class SessionTarget(
        val pending: Boolean,
        val host: String,
        val card: LinearLayout
    )

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
                    updateSessionButton(it.serverRunning)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LanAuthGate.snap.collect { snap ->
                    if (_binding == null) {
                        return@collect
                    }
                    updateSessionButton(viewModel.uiState.value.serverRunning)
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
        binding.sessionButton.setOnClickListener {
            showSessionDialog()
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

    private fun updateSessionButton(serverRunning: Boolean) {
        if (_binding == null) {
            return
        }
        binding.authPendingBox.visibility = View.GONE
        binding.aliveClientsBox.visibility = View.GONE
        val pending = if (serverRunning) LanAuthGate.snap.value.pending else emptyList()
        val alive = if (serverRunning) TsServer.listAliveClientsCurrent() else emptyList()
        binding.sessionButton.text = when {
            !serverRunning -> getString(R.string.server_session_idle)
            pending.isNotEmpty() -> getString(R.string.server_session_pending, pending.size, alive.size)
            else -> getString(R.string.server_session_online, alive.size)
        }
        if (!serverRunning) {
            dismissSessionDialog()
            return
        }
        if (sessionDialog?.isShowing == true) {
            fillSessionList(pending, alive)
        }
    }

    private fun showSessionDialog() {
        if (!isAdded || _binding == null) {
            return
        }
        if (!viewModel.uiState.value.serverRunning) {
            Toast.makeText(context, R.string.server_session_need_start, Toast.LENGTH_SHORT).show()
            return
        }
        if (sessionDialog?.isShowing == true) {
            updateSessionButton(true)
            return
        }
        val density = resources.displayMetrics.density
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        sessionList = box
        root.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val close = reviewButton(getString(R.string.server_session_close), true) {
            dismissSessionDialog()
        }
        (close.layoutParams as LinearLayout.LayoutParams).topMargin = (12 * density).toInt()
        root.addView(close)
        val scroll = ScrollView(requireContext()).apply {
            addView(root)
        }
        fillSessionList(LanAuthGate.snap.value.pending, TsServer.listAliveClientsCurrent())
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.server_session_title)
            .setView(scroll)
            .setOnDismissListener {
                sessionDialog = null
                sessionList = null
                sessionSig = ""
                sessionTargets = emptyList()
                sessionCursor = 0
            }
            .create()
        dialog.setOnKeyListener { _, keyCode, event -> onSessionKey(keyCode, event) }
        sessionDialog = dialog
        dialog.show()
    }

    private fun onSessionKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.isCtrlPressed || event.isMetaPressed) {
            return false
        }
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_Y -> "allow"
            KeyEvent.KEYCODE_N -> "deny"
            KeyEvent.KEYCODE_B -> "block"
            KeyEvent.KEYCODE_K -> "kick"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            else -> return false
        }
        if (event.repeatCount > 0) {
            return true
        }
        when (handled) {
            "up" -> moveSessionCursor(-1)
            "down" -> moveSessionCursor(1)
            else -> sessionAct(handled)
        }
        return true
    }

    private fun fillSessionList(
        pending: List<LanAuthGate.PendingItem>,
        alive: List<TsServer.AliveClient>
    ) {
        val box = sessionList ?: return
        val max = viewModel.uiState.value.maxConnection.toInt().coerceAtLeast(1)
        val sig = pending.joinToString("|") { "${it.host}\u0000${it.name}\u0000${it.at}\u0000${it.sas}" } +
            "#" + alive.joinToString("|") { "${it.host}\u0000${it.port}\u0000${it.name}" } + "|$max"
        if (sig == sessionSig && box.childCount > 0) {
            return
        }
        sessionSig = sig
        box.removeAllViews()
        val keepHost = sessionTargets.getOrNull(sessionCursor)?.host
        val built = ArrayList<SessionTarget>()
        val density = resources.displayMetrics.density
        box.addView(sectionTitle(getString(R.string.server_auth_pending_title) + "  ${pending.size}"))
        if (pending.isEmpty()) {
            box.addView(mutedLine(getString(R.string.server_session_no_pending)))
        } else {
            for (item in pending) {
                val card = buildAuthRow(item)
                built.add(SessionTarget(true, item.host, card))
                box.addView(card)
            }
            if (pending.size > 1) {
                val denyAll = reviewButton(getString(R.string.server_auth_deny_all), true) {
                    LanAuthGate.denyAll()
                }
                (denyAll.layoutParams as LinearLayout.LayoutParams).topMargin = (8 * density).toInt()
                box.addView(denyAll)
            }
        }
        val aliveTitle = if (max > 1) {
            getString(R.string.server_alive_clients_title) + "  ${alive.size} / $max"
        } else {
            getString(R.string.server_alive_clients_title) + "  ${alive.size}"
        }
        box.addView(sectionTitle(aliveTitle).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = (12 * density).toInt()
        })
        if (alive.isEmpty()) {
            box.addView(mutedLine(getString(R.string.server_session_no_clients)))
        } else {
            for (c in alive) {
                val card = buildAliveRow(c)
                built.add(SessionTarget(false, c.host, card))
                box.addView(card)
            }
            if (alive.size > 1) {
                val kickAll = reviewButton(getString(R.string.server_kick_all), true) {
                    for (c in TsServer.listAliveClientsCurrent()) {
                        TsServer.kickHostCurrent(c.host)
                    }
                    sessionSig = ""
                    updateSessionButton(true)
                }
                (kickAll.layoutParams as LinearLayout.LayoutParams).topMargin = (8 * density).toInt()
                box.addView(kickAll)
            }
        }
        sessionTargets = built
        val kept = built.indexOfFirst { it.host == keepHost }
        sessionCursor = when {
            built.isEmpty() -> 0
            kept >= 0 -> kept
            else -> sessionCursor.coerceIn(0, built.lastIndex)
        }
        markSessionCursor()
        box.addView(TextView(requireContext()).apply {
            text = getString(R.string.server_session_keys)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            alpha = 0.65f
            setPadding(0, (12 * density).toInt(), 0, 0)
        })
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun mutedLine(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            alpha = 0.6f
        }
    }

    private fun dismissSessionDialog() {
        try {
            sessionDialog?.dismiss()
        } catch (_: Exception) {
        }
        sessionDialog = null
        sessionList = null
        sessionSig = ""
    }

    private fun buildAuthRow(item: LanAuthGate.PendingItem): LinearLayout {
        val hostLine = if (item.sas.isBlank()) item.host else "${item.host}  ·  核对 ${item.sas}"
        return deviceCard(
            item.name, hostLine,
            getString(R.string.server_auth_keys_line),
            listOf(
                reviewButton(getString(R.string.server_auth_allow), false, "Y") {
                    LanAuthGate.decide(item.host, "allow")
                },
                reviewButton(getString(R.string.server_auth_deny), false, "N") {
                    LanAuthGate.decide(item.host, "deny")
                },
                reviewButton(getString(R.string.server_auth_blacklist), false, "B") {
                    LanAuthGate.decide(item.host, "blacklist")
                }
            )
        )
    }

    private fun buildAliveRow(client: TsServer.AliveClient): LinearLayout {
        val title = client.name.ifBlank { client.host }
        val host = if (client.name.isBlank()) "" else client.host
        return deviceCard(
            title, host,
            getString(R.string.server_alive_keys_line),
            listOf(
                reviewButton(getString(R.string.server_kick), false, "K") {
                    TsServer.kickHostCurrent(client.host)
                    sessionSig = ""
                    updateSessionButton(true)
                },
                reviewButton(getString(R.string.server_auth_blacklist), false, "B") {
                    LanAuthGate.addBlacklist(client.host)
                    TsServer.kickHostCurrent(client.host)
                    sessionSig = ""
                    updateSessionButton(true)
                }
            )
        )
    }

    private fun moveSessionCursor(delta: Int) {
        if (sessionTargets.isEmpty()) {
            return
        }
        val n = sessionTargets.size
        sessionCursor = (sessionCursor + delta + n) % n
        markSessionCursor()
    }

    private fun markSessionCursor() {
        val density = resources.displayMetrics.density
        val normal = themeColor(com.google.android.material.R.attr.colorOutline)
        val hot = themeColor(com.google.android.material.R.attr.colorPrimary)
        sessionTargets.forEachIndexed { index, target ->
            val bg = target.card.background as? GradientDrawable ?: return@forEachIndexed
            val on = index == sessionCursor
            bg.setStroke(((if (on) 2f else 1f) * density).toInt().coerceAtLeast(1), if (on) hot else normal)
        }
    }

    private fun sessionAct(action: String) {
        val target = sessionTargets.getOrNull(sessionCursor)
        if (target == null) {
            Toast.makeText(context, R.string.server_key_none, Toast.LENGTH_SHORT).show()
            return
        }
        when (action) {
            "allow", "deny" -> {
                if (!target.pending) {
                    Toast.makeText(context, R.string.server_key_need_pending, Toast.LENGTH_SHORT).show()
                    return
                }
                LanAuthGate.decide(target.host, if (action == "allow") "allow" else "deny")
            }
            "block" -> {
                if (target.pending) {
                    LanAuthGate.decide(target.host, "blacklist")
                } else {
                    LanAuthGate.addBlacklist(target.host)
                    TsServer.kickHostCurrent(target.host)
                    sessionSig = ""
                    updateSessionButton(true)
                }
            }
            "kick" -> {
                if (target.pending) {
                    Toast.makeText(context, R.string.server_key_kick_pending, Toast.LENGTH_SHORT).show()
                    return
                }
                TsServer.kickHostCurrent(target.host)
                sessionSig = ""
                updateSessionButton(true)
            }
        }
    }

    /** 一台设备一个直角方框。快捷键说明直接写在按钮下面，不靠悬停或长按。 */
    private fun deviceCard(
        title: String,
        host: String,
        keysLine: String?,
        buttons: List<MaterialButton>
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
                setStroke((1 * density).toInt().coerceAtLeast(1), themeColor(com.google.android.material.R.attr.colorOutline))
                cornerRadius = 0f
            }
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }
        card.addView(TextView(requireContext()).apply {
            text = title.ifBlank { host }
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        })
        if (host.isNotBlank() && host != title) {
            card.addView(TextView(requireContext()).apply {
                text = host
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                alpha = 0.7f
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }
        buttons.forEachIndexed { index, button ->
            val lp = button.layoutParams as LinearLayout.LayoutParams
            if (index > 0) {
                lp.marginStart = (6 * density).toInt()
            }
            row.addView(button)
        }
        card.addView(row)
        if (!keysLine.isNullOrBlank()) {
            card.addView(TextView(requireContext()).apply {
                text = keysLine
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                alpha = 0.65f
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
        }
        return card
    }

    private fun reviewButton(
        label: String,
        fillWidth: Boolean,
        key: String? = null,
        onClick: () -> Unit
    ): MaterialButton {
        val density = resources.displayMetrics.density
        val height = (40 * density).toInt()
        return MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = if (key.isNullOrBlank()) label else "$label  $key"
            contentDescription = label
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(0f).build()
            cornerRadius = 0
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            minHeight = height
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = if (fillWidth) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
            } else {
                LinearLayout.LayoutParams(0, height, 1f)
            }
            setOnClickListener { onClick() }
            ButtonFeel.arm(this)
        }
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        val theme = requireContext().theme
        if (!theme.resolveAttribute(attr, tv, true)) {
            return 0xFF888888.toInt()
        }
        return if (tv.resourceId != 0) requireContext().getColor(tv.resourceId) else tv.data
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
        dismissSessionDialog()
        val dest = runCatching { findNavController().currentDestination?.id }.getOrNull()
        // 进聊天或设置时服务还要留着。左上角返回、系统返回回到客户端时要停。
        val keepRunning = dest == R.id.messengerFragment || dest == R.id.settingsFragment
        super.onDestroyView()
        if (!keepRunning && viewModel.uiState.value.serverRunning) {
            viewModel.stopServer(tsviewModel, viewModel)
        }
        if (!keepRunning && tsviewModel.uiState.value.uiIndex.toInt() != 3) {
            tsviewModel.update(1, false, false, null, null, null, -1)
        }
        _binding = null
    }
}
