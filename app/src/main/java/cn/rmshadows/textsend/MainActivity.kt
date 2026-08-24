package cn.rmshadows.textsend

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import cn.rmshadows.textsend.databinding.ActivityMainBinding
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import kotlinx.coroutines.launch
import protocol.Protocol
import protocol.TsClient
import protocol.TsServer
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: TextsendViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeSettings.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val navController = navController()
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        viewModel = ViewModelProvider(this)[TextsendViewModel::class.java]
        restoreLiveSession()
        if (savedInstanceState == null) {
            handleShare(intent)
            goMessengerIfConnected()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restoreLiveSession()
        handleShare(intent)
        goMessengerIfConnected()
    }

    private fun sessionAlive(): Boolean {
        val client = TsClient.peer?.isAlive == true
        val server = TsServer.isRunningCurrent()
        return client || server
    }

    private fun restoreLiveSession() {
        val peer = TsClient.peer
        if (peer != null && peer.isAlive) {
            viewModel.update(3, false, true, null, null, null, 2)
            return
        }
        if (TsServer.isRunningCurrent()) {
            val n = TsServer.aliveCountCurrent()
            viewModel.update(3, true, n > 0, null, null, null, if (n > 0) 2 else -1)
        }
    }

    private fun goMessengerIfConnected() {
        if (!sessionAlive()) {
            return
        }
        binding.root.post {
            val nav = navController()
            when (nav.currentDestination?.id) {
                R.id.messengerFragment -> { }
                R.id.ClientFragment -> nav.navigate(R.id.action_ClientFragment_to_messengerFragment)
                R.id.ServerFragment -> nav.navigate(R.id.action_ServerFragment_to_messengerFragment)
            }
        }
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null) {
            return
        }
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }
        val hint = FileTransfer.folderHint(
            intent.getStringExtra(Intent.EXTRA_SUBJECT)
                ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                ?: intent.clipData?.description?.label?.toString()
        )
        val uris = shareUris(intent)
        if (uris.isEmpty()) {
            return
        }
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.data = null
        val app = applicationContext
        Toast.makeText(this, "正在加入待发…", Toast.LENGTH_SHORT).show()
        Thread({
            var added = 0
            var overflow = false
            var folderName: String? = null
            val asFolder = FileTransfer.ingestShareAsFolder(app, uris, hint)
            if (asFolder != null) {
                folderName = asFolder.first
                added = asFolder.second
            } else {
                for (uri in uris) {
                    if (FileTransfer.flatCount() >= Protocol.FILE_BATCH_MAX) {
                        overflow = true
                        break
                    }
                    val item = FileTransfer.ingest(app, uri) ?: continue
                    if (FileTransfer.add(item)) {
                        added++
                    }
                }
            }
            val a = added
            val over = overflow
            val folder = folderName
            runOnUiThread {
                if (a == 0 && !over) {
                    Toast.makeText(
                        this,
                        if (folder != null) "无法读取分享的文件夹" else "无法读取分享的文件",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }
                if (over) {
                    Toast.makeText(this, "待发最多 ${Protocol.FILE_BATCH_MAX} 个", Toast.LENGTH_SHORT).show()
                }
                val connected = sessionAlive() || viewModel.uiState.value.isClientConnected
                val msg = when {
                    folder != null && a > 0 ->
                        "已加入文件夹 $folder（$a 个文件），" +
                            if (connected) "点发送才传" else "连上后到消息页点发送"
                    connected -> getString(R.string.messager_share_added_connected) + "（+$a）"
                    else -> getString(R.string.messager_share_added) + "（+$a）"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }, "ts-share-ingest").start()
    }

    private fun shareUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { out.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )?.forEach { if (it != null) out.add(it) }
            }
        }
        intent.data?.let { out.add(it) }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_switch -> {
                switchClientServer()
                true
            }

            R.id.action_settings -> {
                val navController = navController()
                if (navController.currentDestination?.id != R.id.settingsFragment) {
                    navController.navigate(R.id.action_global_to_settings)
                }
                true
            }

            R.id.action_about -> {
                // 如果使用HTMLL:
                val alertDialogBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
                alertDialogBuilder.setIcon(R.mipmap.ic_textsend)
                val message = StringBuilder()
                message.append("<b>&nbsp;&nbsp;")
                message.append(resources.getString(R.string.app_name_full))
                message.append("</b><br/><br/>Version: &nbsp;&nbsp;&nbsp;&nbsp;")
                message.append(resources.getString(R.string.app_version))
                message.append("<br/>Author: &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;")
                message.append(resources.getString(R.string.app_Author))
                message.append("<br/>LICENSE: &nbsp;&nbsp;")
                message.append(resources.getString(R.string.app_License))
                message.append("<br/>Github: &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;")
                message.append("<a href='${resources.getString(R.string.github)}'>${resources.getString(R.string.github)}</a>")
                alertDialogBuilder.setTitle("About")
                alertDialogBuilder.setMessage(HtmlCompat.fromHtml(message.toString(), HtmlCompat.FROM_HTML_MODE_LEGACY))
                alertDialogBuilder.setNegativeButton("Close") { _, _ -> } //...To-do
                alertDialogBuilder.show()

                // 使用新建Textview
//                // 关于
//                val alertDialogBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
//                // 如果import中有R，则失效
//                alertDialogBuilder.setIcon(R.mipmap.ic_textsend)
//                val message = StringBuilder()
//                message.append(resources.getString(R.string.app_name_full))
//                message.append("\n\nVersion: ")
//                message.append(resources.getString(R.string.app_version))
//                message.append("\nAuthor: ")
//                message.append(resources.getString(R.string.app_Author))
//                message.append("\nLICENSE: ")
//                message.append(resources.getString(R.string.app_License))
//                message.append("\nGithub: ")
//                message.append(resources.getString(R.string.github))
//                // alertDialogBuilder.setMessage(message)
//
//                // 创建一个可点击的链接
//                val githubTextView = TextView(this)
//                githubTextView.text = message.toString()
//                Linkify.addLinks(githubTextView, Linkify.WEB_URLS)
//                val githubClickableText = SpannableString(githubTextView.text)
//                githubTextView.text = githubClickableText
//                githubTextView.linksClickable = true
//                githubTextView.movementMethod = LinkMovementMethod.getInstance()
//                githubTextView.setPadding(80,80,80,80)
//                // 将 TextView 添加到对话框中
//                alertDialogBuilder.setView(githubTextView)
//
//                alertDialogBuilder.setTitle("About")
//                alertDialogBuilder.setNegativeButton("Close") { _, _ -> } //...To-do
//                alertDialogBuilder.show()
                true
            }

            R.id.action_quit -> {
                // 退出
                val alertDialogBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
                // 如果import中有R，则失效
                alertDialogBuilder.setIcon(R.mipmap.ic_launcher)
                alertDialogBuilder.setTitle("Textsend for Android")
                alertDialogBuilder.setMessage("Quit Textsend for Android ?")
                alertDialogBuilder.setPositiveButton("Quit") { _, _ -> run { exitProcess(0) } }
                alertDialogBuilder.setNegativeButton("Cancel") { _, _ -> } //...To-do
                alertDialogBuilder.show()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect {
                    // 非客户端禁用切换按钮
                    // https://developer.android.com/develop/ui/views/components/menus?hl=zh-cn
                    if (menu != null) {
                        menu.getItem(0).isEnabled = it.uiIndex == 1
                    }
                }
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    /** 设置页没有 Client→Server 这条边，硬跳会闪退。先回到主页再切。 */
    private fun switchClientServer() {
        val nav = navController()
        when (nav.currentDestination?.id) {
            R.id.ClientFragment -> nav.navigate(R.id.action_ClientFragment_to_ServerFragment)
            R.id.ServerFragment -> nav.navigate(R.id.action_ServerFragment_to_ClientFragment)
            R.id.settingsFragment, R.id.qrScannerFragment -> {
                if (nav.popBackStack()) {
                    switchClientServer()
                }
            }
        }
    }

    private fun navController(): NavController {
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
        return (host as NavHostFragment).navController
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = navController()
        if (navController.currentDestination?.id == R.id.messengerFragment) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}