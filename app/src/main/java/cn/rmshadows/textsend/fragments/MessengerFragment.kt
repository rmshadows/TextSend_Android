package cn.rmshadows.textsend.fragments

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import cn.rmshadows.textsend.FileTransfer
import cn.rmshadows.textsend.FolderPickActivity
import cn.rmshadows.textsend.GalleryPickActivity
import cn.rmshadows.textsend.KeepAlive
import cn.rmshadows.textsend.R
import cn.rmshadows.textsend.databinding.FragmentMessengerBinding
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import kotlinx.coroutines.launch
import protocol.FileIoCallback
import protocol.Protocol
import protocol.TsClient
import protocol.TsPeer
import protocol.TsServer
import utils.Constant
import java.util.concurrent.atomic.AtomicBoolean


class MessengerFragment : Fragment() {
    private var _binding: FragmentMessengerBinding? = null
    private val binding get() = _binding!!
    private val tsviewModel: TextsendViewModel by activityViewModels()
    private var lastClipData: String = ""
    private var sending = false
    private val cancelSend = AtomicBoolean(false)
    private val cancelling = AtomicBoolean(false)
    private val cancelUiDone = AtomicBoolean(false)
    private var fileLoopRunning = false
    private var lastProgBytes = -1L
    private var lastProgAt = 0L
    private var lastUiAt = 0L
    private var speedWindowBytes = 0L
    private var speedWindowAt = 0L
    private var speedText = ""
    private var shownName = ""
    private var ignoreProgressUntil = 0L
    private var pendingCapListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var leaving = false

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        enqueueUris(uris)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            enqueueTree(uri)
        }
    }

    private val pickFolderApp = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val data = result.data
        if (data?.getBooleanExtra(FolderPickActivity.EXTRA_USE_SAF, false) == true) {
            pickFolder.launch(null)
            return@registerForActivityResult
        }
        val paths = data?.getStringArrayListExtra(FolderPickActivity.EXTRA_PATHS)
        if (!paths.isNullOrEmpty()) {
            enqueueLocalDirs(paths)
        }
    }

    private val pickGallery = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val extras = if (Build.VERSION.SDK_INT >= 33) {
            result.data?.getParcelableArrayListExtra(GalleryPickActivity.EXTRA_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableArrayListExtra(GalleryPickActivity.EXTRA_URIS)
        }
        enqueueUris(extras ?: urisFromPicker(result.data))
    }

    private val askMedia = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (hasGalleryAccess()) {
            startGallery()
        } else {
            Toast.makeText(context, "需要相册权限才能多选图片", Toast.LENGTH_SHORT).show()
        }
    }

    private val fileIo = object : FileIoCallback {
        override fun onProgress(incoming: Boolean, name: String, done: Long, total: Long, seq: Int, of: Int) {
            uiProgress(incoming, name, done, total, seq, of)
        }

        override fun onReceived(name: String, where: String) {
            uiFileDone("已保存 $name", where)
        }

        override fun onReceiveFailed(name: String, err: String) {
            when {
                err.contains("冷却") -> uiFileDone(err, null)
                err.contains("对端取消") -> uiFileDone("对面已取消", null)
                err.contains("已取消") -> uiFileDone("已取消", null)
                else -> uiFileDone("接收失败 $name: $err", null)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessengerBinding.inflate(inflater, container, false)
        tsviewModel.update(3, null, null, null, null, null, null)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tsviewModel.uiState.collect {
                    if (it.messageContent == Constant.CF) {
                        cleanEditText()
                        tsviewModel.update(null, null, null, null, null, "", null)
                    } else if (it.messageContent.isNotEmpty() && it.messageContent != lastClipData) {
                        lastClipData = it.messageContent
                        copyToClickboard(it.messageContent)
                    }
                    if (!it.isServerMode && !it.isClientConnected && TsClient.peer?.isAlive != true) {
                        if (findNavController().currentDestination?.id == R.id.messengerFragment) {
                            Log.d(Constant.TAG, "onCreateView: 消息界面主动返回")
                            leaveMessenger(remote = true)
                        }
                    }
                }
            }
        }
        return binding.root
    }

    private lateinit var appCtx: Context

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appCtx = requireContext().applicationContext
        FileTransfer.callback = fileIo
        FileTransfer.onPendingChanged = {
            activity?.runOnUiThread { refreshPendingList() }
        }
        TsClient.peer?.setFileIo(fileIo)
        TsServer.setFileIoCurrent(fileIo)
        pendingCapListener = ViewTreeObserver.OnGlobalLayoutListener { capPendingHeight() }
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(pendingCapListener)
        refreshPendingList()

        binding.buttonClearPending.setOnClickListener {
            if (!FileTransfer.clearPending()) {
                Toast.makeText(context, "发送中不能清空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(context, "已清空待发列表", Toast.LENGTH_SHORT).show()
        }

        binding.buttonSend.setOnClickListener {
            if (FileTransfer.pendingCount() > 0) {
                if (fileLoopRunning) {
                    Toast.makeText(context, "正在发送文件", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startFileLoop()
                return@setOnClickListener
            }
            val text = binding.editText.text.toString()
            if (text.isBlank() || sending) {
                return@setOnClickListener
            }
            sending = true
            val serverMode = tsviewModel.uiState.value.isServerMode
            Thread({
                try {
                    if (serverMode) {
                        val n = TsServer.sendToAllCurrent(text)
                        if (n < 0) {
                            uiSendDone(false, text, "服务未运行")
                            return@Thread
                        }
                        if (n == 0) {
                            uiSendDone(false, text, "没有已连接的客户端")
                            return@Thread
                        }
                    } else {
                        val peer = TsClient.peer
                        if (peer == null || !peer.isAlive) {
                            uiSendDone(false, text, "尚未握手或已断开")
                            return@Thread
                        }
                        peer.sendText(text)
                    }
                    uiSendDone(true, text, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                    uiSendDone(false, text, "发送失败: ${e.message}")
                }
            }, "ts-send").start()
        }

        binding.buttonFile.setOnClickListener {
            openSystemGallery()
        }
        binding.buttonFile.setOnLongClickListener {
            openFileManager()
            true
        }
        binding.buttonSend.setOnLongClickListener {
            pickFolderApp.launch(Intent(requireContext(), FolderPickActivity::class.java))
            true
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    leaveMessenger(remote = false)
                }
            }
        )

        binding.buttonCancelFile.setOnClickListener {
            cancelSend.set(true)
            cancelling.set(true)
            TsClient.peer?.cancelFile()
            TsServer.cancelFilesCurrent()
            if (fileLoopRunning) {
                _binding?.filePercent?.text = "取消中"
                _binding?.fileMeta?.text = "正在停止…"
            } else {
                finishCancelUi()
            }
        }
    }

    private fun openSystemGallery() {
        val perms = mediaPermissions()
        val need = perms.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) {
            startGallery()
        } else {
            askMedia.launch(need.toTypedArray())
        }
    }

    private fun startGallery() {
        val left = (Protocol.FILE_BATCH_MAX - FileTransfer.flatCount()).coerceAtLeast(1)
        val i = Intent(requireContext(), GalleryPickActivity::class.java)
        i.putExtra(GalleryPickActivity.EXTRA_MAX, left)
        pickGallery.launch(i)
    }

    private fun hasGalleryAccess(): Boolean {
        val ctx = requireContext()
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun mediaPermissions(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            list.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        return list.toTypedArray()
    }

    private fun openFileManager() {
        pickFiles.launch(arrayOf("*/*"))
    }

    private fun urisFromPicker(data: Intent?): List<Uri> {
        if (data == null) {
            return emptyList()
        }
        val out = LinkedHashSet<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { out.add(it) }
            }
        }
        data.data?.let { out.add(it) }
        val streams = if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        streams?.forEach { uri ->
            if (uri != null) {
                out.add(uri)
            }
        }
        @Suppress("DEPRECATION")
        data.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
        return out.toList()
    }

    private fun enqueueLocalDirs(paths: List<String>) {
        val app = requireContext().applicationContext
        Toast.makeText(app, "正在扫描文件夹…", Toast.LENGTH_SHORT).show()
        Thread({
            var name = ""
            var added = 0
            for (p in paths) {
                val dir = java.io.File(p)
                val r = try {
                    FileTransfer.ingestLocalDir(dir)
                } catch (e: Exception) {
                    e.printStackTrace()
                    "" to 0
                }
                if (r.second > 0) {
                    name = r.first
                    added += r.second
                }
            }
            activity?.runOnUiThread {
                if (_binding == null) {
                    return@runOnUiThread
                }
                if (added == 0) {
                    Toast.makeText(app, "文件夹是空的或无法读取", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    app,
                    "已加入文件夹 $name（$added 个文件），点发送才传",
                    Toast.LENGTH_SHORT
                ).show()
                refreshPendingList()
            }
        }, "ts-ingest-local").start()
    }

    private fun enqueueTree(uri: Uri) {
        val app = requireContext().applicationContext
        try {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        Toast.makeText(app, "正在扫描文件夹…", Toast.LENGTH_SHORT).show()
        Thread({
            val (treeName, added) = try {
                FileTransfer.ingestSharedFolder(app, uri)
            } catch (e: Exception) {
                e.printStackTrace()
                "" to 0
            }
            activity?.runOnUiThread {
                if (_binding == null) {
                    return@runOnUiThread
                }
                if (added == 0) {
                    Toast.makeText(app, "文件夹是空的或无法读取", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    app,
                    "已加入文件夹 $treeName（$added 个文件），点发送才传",
                    Toast.LENGTH_SHORT
                ).show()
                refreshPendingList()
            }
        }, "ts-ingest-tree").start()
    }

    private fun enqueueUris(uris: List<android.net.Uri>?) {
        if (uris.isNullOrEmpty()) {
            return
        }
        val act = requireContext()
        val app = act.applicationContext
        if (uris.size == 1 && FileTransfer.isFolderUri(app, uris[0])) {
            enqueueTree(uris[0])
            return
        }
        for (uri in uris) {
            try {
                act.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        Toast.makeText(app, "正在加入待发…", Toast.LENGTH_SHORT).show()
        Thread({
            var added = 0
            var overflow = false
            for (uri in uris) {
                if (FileTransfer.isFolderUri(app, uri)) {
                    added += FileTransfer.ingestSharedFolder(app, uri).second
                    continue
                }
                if (FileTransfer.flatCount() >= Protocol.FILE_BATCH_MAX) {
                    overflow = true
                    break
                }
                val item = try {
                    FileTransfer.ingest(app, uri)
                } catch (_: Exception) {
                    null
                } ?: continue
                if (FileTransfer.add(item)) {
                    added++
                }
            }
            val a = added
            val over = overflow
            activity?.runOnUiThread {
                if (_binding == null) {
                    return@runOnUiThread
                }
                if (a == 0 && !over) {
                    Toast.makeText(app, "无法读取所选文件", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (over) {
                    Toast.makeText(app, "待发最多 ${Protocol.FILE_BATCH_MAX} 个", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(app, "已加入待发（+$a），点发送才传", Toast.LENGTH_SHORT).show()
                }
                refreshPendingList()
            }
        }, "ts-ingest").start()
    }

    private fun refreshPendingList() {
        val b = _binding ?: return
        val items = FileTransfer.snapshot()
        b.pendingList.removeAllViews()
        if (items.isEmpty()) {
            b.pendingBox.visibility = View.GONE
            return
        }
        b.pendingBox.visibility = View.VISIBLE
        b.pendingTitle.text = getString(R.string.messager_pending_title, items.size)
        capPendingHeight()
        val inflater = layoutInflater
        for ((i, item) in items.withIndex()) {
            val row = inflater.inflate(R.layout.item_pending_file, b.pendingList, false)
            row.findViewById<android.widget.TextView>(R.id.pendingIndex).text = "${i + 1}."
            row.findViewById<android.widget.TextView>(R.id.pendingName).text =
                if (!item.treeName.isNullOrBlank() && !item.relPath.isNullOrBlank()) {
                    "${item.treeName}/${item.relPath}"
                } else {
                    item.name
                }
            row.findViewById<android.widget.ImageButton>(R.id.pendingRemove).setOnClickListener {
                if (fileLoopRunning) {
                    Toast.makeText(context, "发送中不能移除", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                FileTransfer.removeId(item.id)
            }
            b.pendingList.addView(row)
        }
    }

    private fun startFileLoop() {
        if (fileLoopRunning) {
            return
        }
        if (FileTransfer.pendingCount() == 0) {
            return
        }
        fileLoopRunning = true
        FileTransfer.beginSend()
        cancelSend.set(false)
        cancelling.set(false)
        cancelUiDone.set(false)
        ignoreProgressUntil = 0L
        TsClient.peer?.prepareOutgoingSend()
        TsServer.prepareOutgoingCurrent()
        val serverMode = tsviewModel.uiState.value.isServerMode
        val resolver = appCtx.contentResolver
        val sentByGroup = HashMap<String, Int>()
        Thread({
            try {
                while (!cancelSend.get()) {
                    val batch = FileTransfer.snapshot()
                    if (batch.isEmpty()) {
                        break
                    }
                    for (item in batch) {
                        if (cancelSend.get()) {
                            break
                        }
                        val g = item.treeName ?: ""
                        val left = FileTransfer.snapshot().count { (it.treeName ?: "") == g }
                        val done = sentByGroup[g] ?: 0
                        sendOne(serverMode, resolver, item, done + 1, done + left)
                        sentByGroup[g] = done + 1
                        FileTransfer.remove(item)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val msg = e.message ?: e.javaClass.simpleName
                when {
                    cancelSend.get() -> { }
                    msg.contains("cancelled") -> uiFileDone("对面已取消", null)
                    msg.contains("no-inbox") -> uiFileDone("对面无法保存这个文件（路径无效或已取消）", null)
                    else -> uiFileDone("发送失败: $msg", null)
                }
            } finally {
                val localCancel = cancelSend.get()
                fileLoopRunning = false
                FileTransfer.endSend()
                val act = activity
                act?.runOnUiThread {
                    if (localCancel) {
                        finishCancelUi()
                    } else {
                        hideFileBar()
                    }
                    refreshPendingList()
                }
            }
        }, "ts-file").start()
    }

    private fun sendOne(
        serverMode: Boolean,
        resolver: android.content.ContentResolver,
        item: FileTransfer.Item,
        seq: Int,
        of: Int
    ) {
        val opener: TsPeer.StreamOpener = if (item.cacheFile != null) {
            TsPeer.StreamOpener { java.io.FileInputStream(item.cacheFile) }
        } else {
            val uri = item.uri ?: throw java.io.IOException("无法读取文件")
            val cr = activity?.contentResolver ?: resolver
            TsPeer.StreamOpener {
                try {
                    cr.openInputStream(uri)
                } catch (_: Exception) {
                    null
                } ?: if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                    java.io.FileInputStream(java.io.File(uri.path!!))
                } else {
                    throw java.io.IOException("无法读取文件")
                }
            }
        }
        val cancelled = java.util.function.BooleanSupplier { cancelSend.get() }
        if (serverMode) {
            val n = TsServer.sendFileToAllCurrent(
                item.name, item.size, opener, cancelled, item.treeName, item.relPath, seq, of
            )
            if (n < 0) {
                throw java.io.IOException("服务未运行")
            }
            if (n == 0) {
                throw java.io.IOException("没有已连接的客户端")
            }
            if (n > 1) {
                uiToast("已发给 $n 端：${item.name}")
            }
        } else {
            val peer = TsClient.peer
            if (peer == null || !peer.isAlive) {
                throw java.io.IOException("尚未握手或已断开")
            }
            if (!peer.supportsFile()) {
                throw java.io.IOException("对端还不支持文件传输")
            }
            peer.sendFile(item.name, item.size, opener, cancelled, item.treeName, item.relPath, seq, of)
        }
        item.cacheFile?.delete()
    }

    private fun uiProgress(incoming: Boolean, name: String, done: Long, total: Long, seq: Int, of: Int) {
        val act = activity ?: return
        act.runOnUiThread {
            if (cancelling.get() || System.currentTimeMillis() < ignoreProgressUntil) {
                return@runOnUiThread
            }
            if (incoming && done == 0L) {
                cancelUiDone.set(false)
            }
            val b = _binding ?: return@runOnUiThread
            b.fileBar.visibility = View.VISIBLE
            val now = System.currentTimeMillis()
            if (lastProgBytes >= 0 && done >= lastProgBytes) {
                speedWindowBytes += done - lastProgBytes
            }
            lastProgBytes = done
            lastProgAt = now
            if (speedWindowAt == 0L) {
                speedWindowAt = now
            }
            val elapsed = now - speedWindowAt
            if (elapsed >= 400) {
                speedText = formatSpeed(speedWindowBytes * 1000.0 / elapsed)
                speedWindowBytes = 0
                speedWindowAt = now
            }
            val finished = total > 0 && done >= total
            if (!finished && lastUiAt > 0 && now - lastUiAt < 200 && name == shownName) {
                return@runOnUiThread
            }
            lastUiAt = now
            val pct = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
            b.fileProgress.progress = pct * 10
            b.filePercent.text = "$pct%"
            val dir = if (incoming) "收" else "发"
            val xy = if (of > 1 && seq > 0) "  $seq/$of" else ""
            val spd = if (speedText.isNotEmpty()) "  ·  $speedText" else ""
            b.fileMeta.text = "$dir$xy$spd"
            if (name != shownName) {
                shownName = name
                b.fileName.text = name
            }
        }
    }

    private fun hideFileBar() {
        cancelling.set(false)
        ignoreProgressUntil = System.currentTimeMillis() + 300L
        lastProgBytes = -1L
        lastUiAt = 0L
        speedWindowBytes = 0
        speedWindowAt = 0L
        speedText = ""
        shownName = ""
        val b = _binding ?: return
        b.fileBar.visibility = View.GONE
        b.filePercent.text = ""
        b.fileMeta.text = ""
        b.fileName.text = ""
        b.fileProgress.progress = 0
    }

    private fun finishCancelUi() {
        val first = cancelUiDone.compareAndSet(false, true)
        hideFileBar()
        if (first) {
            Toast.makeText(context, "已取消", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uiFileDone(msg: String, where: String?) {
        val act = activity ?: return
        act.runOnUiThread {
            if (msg.contains("冷却")) {
                cancelUiDone.set(true)
                hideFileBar()
                refreshPendingList()
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            if (msg == "对面已取消") {
                cancelUiDone.set(true)
                hideFileBar()
                refreshPendingList()
                Toast.makeText(context, "对面已取消", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            if (msg == "已取消" || cancelling.get()) {
                finishCancelUi()
                refreshPendingList()
                return@runOnUiThread
            }
            val b = _binding
            if (!fileLoopRunning && b != null) {
                hideFileBar()
            }
            refreshPendingList()
            val extra = if (where.isNullOrBlank()) "" else "\n$where"
            Toast.makeText(context, msg + extra, Toast.LENGTH_SHORT).show()
        }
    }

    private fun uiToast(msg: String) {
        val act = activity ?: return
        act.runOnUiThread {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatSpeed(bps: Double): String {
        if (bps < 0) {
            return ""
        }
        if (bps < 1024) {
            return String.format("%.0f B/s", bps)
        }
        if (bps < 1024 * 1024) {
            return String.format("%.1f KB/s", bps / 1024)
        }
        return String.format("%.1f MB/s", bps / (1024 * 1024))
    }

    private fun uiSendDone(ok: Boolean, sent: String, err: String?) {
        val act = activity ?: return
        act.runOnUiThread {
            sending = false
            if (ok) {
                val now = _binding?.editText?.text?.toString()
                if (sent == now) {
                    tsviewModel.cleanEditText()
                }
            } else if (err != null) {
                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun leaveMessenger(remote: Boolean) {
        if (leaving) {
            return
        }
        leaving = true
        hideIme()
        if (fileLoopRunning) {
            cancelSend.set(true)
            cancelling.set(true)
            TsClient.peer?.cancelFile()
            TsServer.cancelFilesCurrent()
        }
        if (!tsviewModel.uiState.value.isServerMode) {
            TsClient.close()
            tsviewModel.update(1, null, false, null, null, null, -1)
            KeepAlive.sessionActive = false
        }
        if (remote) {
            Toast.makeText(context, "Disconnect.", Toast.LENGTH_SHORT).show()
        }
        val nav = findNavController()
        if (nav.currentDestination?.id == R.id.messengerFragment) {
            nav.popBackStack()
        }
    }

    private fun hideIme() {
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        val token = activity?.currentFocus?.windowToken ?: view?.windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun cleanEditText() {
        binding.editText.text.clear()
    }

    private fun copyToClickboard(text: String) {
        val clipboardManager =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = ClipData.newPlainText("copy text", text)
        clipboardManager.setPrimaryClip(clip)
        println("已复制到剪辑板。")
    }

    private fun capPendingHeight() {
        val b = _binding ?: return
        val h = b.root.height
        if (h <= 0) {
            return
        }
        val max = (h * 0.5f).toInt().coerceAtLeast(120)
        val lp = b.pendingBox.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (lp.matchConstraintMaxHeight != max) {
            lp.matchConstraintMaxHeight = max
            b.pendingBox.layoutParams = lp
        }
    }

    override fun onDestroyView() {
        _binding?.root?.viewTreeObserver?.let { obs ->
            pendingCapListener?.let { obs.removeOnGlobalLayoutListener(it) }
        }
        pendingCapListener = null
        val destId = try {
            findNavController().currentDestination?.id
        } catch (_: Exception) {
            null
        }
        super.onDestroyView()
        if (FileTransfer.callback === fileIo) {
            FileTransfer.callback = null
        }
        FileTransfer.onPendingChanged = null
        // 去设置页只是 replace 当前页，isRemoving 也会为 true，不能当断开。
        // 只有回到连接页或退出 Activity 才关连接。
        val leaveToHome = destId == R.id.ClientFragment || destId == R.id.ServerFragment
        if (!tsviewModel.uiState.value.isServerMode && (leaving || leaveToHome)) {
            TsClient.close()
            tsviewModel.update(1, null, false, null, null, null, -1)
            KeepAlive.sessionActive = false
        }
        _binding = null
    }
}
