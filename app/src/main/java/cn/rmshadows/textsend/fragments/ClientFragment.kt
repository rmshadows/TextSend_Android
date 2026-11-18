package cn.rmshadows.textsend.fragments

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import cn.rmshadows.textsend.ButtonFeel
import cn.rmshadows.textsend.KeepAlive
import cn.rmshadows.textsend.LanAuth
import cn.rmshadows.textsend.LanDiscover
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentClientBinding
import cn.rmshadows.textsend.viewmodels.ClientFragmentViewModel
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import protocol.TsClient
import protocol.TsServer

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class ClientFragment : Fragment() {
    private var _binding: FragmentClientBinding? = null
    private var toast: Toast? = null
    private var appliedFillSeq = -1
    private var appliedErrorSeq = 0
    private var pendingQrAutoConnect = false
    private var peerSig = ""
    private var authBusy = false
    private var authWaitHost: String? = null
    private var authCancelEarly = false
    private var probeBreath: ObjectAnimator? = null
    /** 等待允许时，设备列表上常驻显示的核对码。 */
    private val authSasByHost = LinkedHashMap<String, String>()

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!
    private val tsviewModel: TextsendViewModel by activityViewModels()
    private lateinit var viewModel: ClientFragmentViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ClientFragmentViewModel::class.java]
        // 选文件时 Activity 可能被重建并回到本页，不要把正在跑的服务端打成客户端。
        if (!TsServer.isRunningCurrent()) {
            tsviewModel.update(1, false, null, null, null, null, null)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tsviewModel.uiState.collect {
                    if (it.isServerMode) {
                        return@collect
                    }
                    if (it.isClientConnected && TsClient.peer?.isAlive == true) {
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
            } else if (KeepAlive.isQrAutoConnect()) {
                // 等扫码页相机关掉再连：部分 Android 14 相机会短暂占住 Wi‑Fi
                pendingQrAutoConnect = true
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    scheduleQrAutoConnect()
                }
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

        binding.lanProbeBtn.setOnClickListener {
            LanDiscover.probeNow()
            peerSig = ""
            showToast("正在探测…")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LanDiscover.snap.collect { snap ->
                    renderLanPeers(snap)
                }
            }
        }
    }

    private fun renderLanPeers(snap: LanDiscover.Snapshot) {
        if (_binding == null) {
            return
        }
        syncProbeBreath(snap.enabled && !snap.paused && snap.bindError.isBlank() && snap.probing)
        if (!snap.enabled) {
            peerSig = ""
            binding.lanPeerBox.visibility = View.GONE
            binding.lanPeerList.removeAllViews()
            return
        }
        binding.lanPeerBox.visibility = View.VISIBLE
        if (snap.paused) {
            peerSig = "paused"
            binding.lanPeerTitle.setText(R.string.client_lan_paused)
            binding.lanPeerList.removeAllViews()
            return
        }
        if (snap.bindError.isNotBlank()) {
            peerSig = "err:${snap.bindError}"
            binding.lanPeerTitle.text = snap.bindError
            binding.lanPeerList.removeAllViews()
            return
        }
        val manual = !snap.auto
        binding.lanProbeBtn.visibility = if (manual) View.VISIBLE else View.GONE
        val sig = buildString {
            append(if (snap.probing) "probing" else "idle")
            append(':').append(snap.udpPort).append(':').append(snap.auto).append('|')
            append(snap.peers.joinToString("|") {
                val sas = authSasByHost[it.host] ?: authSasByHost[normalizePeerHost(it.host)] ?: ""
                "${it.idKey}\u0000${it.name}\u0000${it.host}:${it.tcpPort}\u0000$sas"
            })
        }
        if (sig == peerSig) {
            return
        }
        peerSig = sig
        binding.lanPeerTitle.setText(
            when {
                snap.peers.isNotEmpty() -> R.string.client_lan_peers
                snap.probing -> R.string.client_lan_scanning
                manual -> R.string.client_lan_manual_idle
                else -> R.string.client_lan_scanning
            }
        )
        binding.lanPeerList.removeAllViews()
        if (snap.peers.isEmpty()) {
            return
        }
        val density = resources.displayMetrics.density
        val groups = LinkedHashMap<String, MutableList<LanDiscover.Peer>>()
        for (peer in snap.peers) {
            val key = if (peer.idKey.isBlank()) "solo:${peer.host}:${peer.tcpPort}" else peer.idKey
            groups.getOrPut(key) { mutableListOf() }.add(peer)
        }
        var colorIdx = 0
        for ((_, members) in groups) {
            if (members.size > 1) {
                val box = sameDeviceBox(groupColors[colorIdx % groupColors.size], density)
                colorIdx++
                for (peer in members) {
                    box.addView(peerButton(peer, density, 0))
                }
                binding.lanPeerList.addView(box)
            } else {
                binding.lanPeerList.addView(peerButton(members[0], density, (6 * density).toInt()))
            }
        }
    }

    private val groupColors = intArrayOf(0xFF3D7EFF.toInt(), 0xFFE08A2E.toInt(), 0xFF2E9B62.toInt(), 0xFFB15CFF.toInt())

    /** 探测进行中，标题前一颗灯慢慢亮灭。 */
    private fun syncProbeBreath(on: Boolean) {
        val light = _binding?.lanProbeLight ?: return
        if (!on) {
            probeBreath?.cancel()
            probeBreath = null
            light.alpha = 1f
            light.visibility = View.GONE
            return
        }
        light.visibility = View.VISIBLE
        if (probeBreath?.isRunning == true) {
            return
        }
        if (light.background == null) {
            val ta = light.context.obtainStyledAttributes(
                intArrayOf(com.google.android.material.R.attr.colorPrimary)
            )
            val color = ta.getColor(0, 0xFF5C00FF.toInt())
            ta.recycle()
            light.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        probeBreath = ObjectAnimator.ofFloat(light, View.ALPHA, 0.15f, 1f).apply {
            duration = 850
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun sameDeviceBox(color: Int, density: Float): LinearLayout {
        val box = LinearLayout(requireContext())
        box.orientation = LinearLayout.VERTICAL
        val pad = (8 * density).toInt()
        box.setPadding(pad, pad, pad, pad)
        box.background = GradientDrawable().apply {
            setColor((color and 0x00FFFFFF) or 0x22000000)
            setStroke((2 * density).toInt(), color)
            cornerRadius = 0f
        }
        box.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() }
        return box
    }

    private fun peerButton(peer: LanDiscover.Peer, density: Float, top: Int): MaterialButton {
        val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val base = if (peer.name.isBlank()) {
            "${peer.host}:${peer.tcpPort}"
        } else {
            "${peer.name}  ${peer.host}:${peer.tcpPort}"
        }
        val sas = authSasByHost[peer.host] ?: authSasByHost[normalizePeerHost(peer.host)]
        btn.text = if (sas.isNullOrBlank()) base else "$base\n核对 $sas · 再点取消"
        btn.isAllCaps = false
        btn.cornerRadius = 0
        btn.insetTop = 0
        btn.insetBottom = 0
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        btn.isSingleLine = false
        btn.maxLines = 3
        btn.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        btn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
        ButtonFeel.arm(btn)
        btn.setOnClickListener {
            requestAuthAndConnect(peer.host, peer.tcpPort)
        }
        return btn
    }

    private fun normalizePeerHost(host: String): String {
        return host.trim().lowercase().removePrefix("::ffff:")
    }

    private fun rememberSas(host: String, code: String) {
        if (code.isBlank()) {
            return
        }
        authSasByHost[host] = code
        authSasByHost[normalizePeerHost(host)] = code
        peerSig = ""
        view?.post {
            if (isAdded && _binding != null) {
                renderLanPeers(LanDiscover.snap.value)
            }
        }
    }

    private fun clearSas(host: String) {
        authSasByHost.remove(host)
        authSasByHost.remove(normalizePeerHost(host))
        peerSig = ""
    }

    override fun onResume() {
        super.onResume()
        scheduleQrAutoConnect()
    }

    private fun scheduleQrAutoConnect() {
        if (!pendingQrAutoConnect) {
            return
        }
        pendingQrAutoConnect = false
        view?.postDelayed({
            if (!isAdded || _binding == null) {
                return@postDelayed
            }
            if (tsviewModel.uiState.value.connectionStat != -1) {
                return@postDelayed
            }
            val err = viewModel.startClient(tsviewModel)
            if (err != null) {
                showToast(err)
            }
        }, 500)
    }

    private fun requestAuthAndConnect(host: String, tcpPort: Int) {
        if (authBusy && (LanAuth.isWaiting(host) || host == authWaitHost)) {
            authCancelEarly = true
            LanAuth.cancelActive()
            return
        }
        if (authBusy) {
            showToast("正在请求授权…再点正在等的那一台可取消")
            return
        }
        if (tsviewModel.uiState.value.connectionStat != -1) {
            showToast("已连接或正在连接")
            return
        }
        if (!LanDiscover.isEnabled()) {
            showToast("请先在设置里打开局域网探测")
            return
        }
        authBusy = true
        authWaitHost = host
        authCancelEarly = false
        showToast("正在请求授权 $host…")
        binding.clientIpAddress.setText(host)
        binding.clientPort.setText(tcpPort.toString())
        val udpPort = LanDiscover.getPort()
        val onSas: (String) -> Unit = { code -> rememberSas(host, code) }
        Thread({
            try {
                if (authCancelEarly) {
                    throw LanAuth.AuthCancelledException()
                }
                val grant = try {
                    LanDiscover.requestAuthTcp(host, tcpPort, onSas)
                } catch (tcpErr: Exception) {
                    if (LanAuth.isDenied(tcpErr) || LanAuth.isCancelled(tcpErr)) {
                        throw tcpErr
                    }
                    LanDiscover.requestAuth(host, udpPort, onSas)
                }
                val port = if (grant.tcpPort > 0) grant.tcpPort else tcpPort
                val psk = grant.pskB64()
                view?.post {
                    if (!isAdded || _binding == null) {
                        authBusy = false
                        return@post
                    }
                    authBusy = false
                    clearSas(host)
                    renderLanPeers(LanDiscover.snap.value)
                    viewModel.applyFields(host, port, psk)
                    binding.clientIpAddress.setText(host)
                    binding.clientPort.setText(port.toString())
                    binding.clientPin.setText(psk)
                    showToast("已获授权，正在连接 $host")
                    if (tsviewModel.uiState.value.connectionStat == -1) {
                        val err = viewModel.startClient(tsviewModel)
                        if (err != null) {
                            showToast(err)
                        }
                    }
                }
            } catch (e: Exception) {
                if (LanAuth.isCancelled(e)) {
                    view?.post {
                        authBusy = false
                        clearSas(host)
                        if (_binding != null) {
                            renderLanPeers(LanDiscover.snap.value)
                        }
                        showToast("已取消授权申请")
                    }
                    return@Thread
                }
                var msg = e.message ?: "授权失败"
                val cause = e.cause
                val denied = findDenied(e)
                if (e is java.util.concurrent.TimeoutException
                    || cause is java.util.concurrent.TimeoutException
                    || e is java.util.concurrent.ExecutionException && cause is java.util.concurrent.TimeoutException
                ) {
                    msg = "等待服务端允许超时"
                } else if (denied != null) {
                    msg = denied.message ?: msg
                } else if (e is java.util.concurrent.ExecutionException && cause != null) {
                    msg = cause.message ?: msg
                }
                val tip = msg
                val waitSec = denied?.waitSec ?: -1
                view?.post {
                    authBusy = false
                    clearSas(host)
                    if (_binding != null) {
                        renderLanPeers(LanDiscover.snap.value)
                    }
                    if (!isAdded) {
                        return@post
                    }
                    if (waitSec > 0) {
                        showCooldownDialog(waitSec)
                    } else if (denied != null) {
                        showDeniedDialog(tip)
                    } else {
                        showToast(tip)
                    }
                }
            } finally {
                if (authWaitHost == host) {
                    authWaitHost = null
                }
            }
        }, "ts-lan-auth").start()
    }

    override fun onDestroyView() {
        probeBreath?.cancel()
        probeBreath = null
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

    private fun findDenied(e: Throwable?): LanAuth.AuthDeniedException? {
        var cur = e
        while (cur != null) {
            if (cur is LanAuth.AuthDeniedException) {
                return cur
            }
            cur = cur.cause
        }
        return null
    }

    private fun showCooldownDialog(waitSec: Int) {
        if (!isAdded) {
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("还在冷却")
            .setMessage("对方刚刚拒绝了授权，大约还有 $waitSec 秒可以再点这个设备。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showDeniedDialog(message: String) {
        if (!isAdded) {
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("授权被拒绝")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
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
