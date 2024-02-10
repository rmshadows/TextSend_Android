package cn.rmshadows.textsend

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import cn.rmshadows.textsend.databinding.ActivityMainBinding
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import kotlinx.coroutines.launch
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: TextsendViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        viewModel = ViewModelProvider(this)[TextsendViewModel::class.java]
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
                // https://stackoverflow.com/questions/58686104/why-does-my-navcontroller-cannot-find-an-id-that-i-already-have
                // 必须有上面这一句
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                navController.navigate(R.id.action_ClientFragment_to_ServerFragment)
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
                message.append("<a href='${resources.getString(R.string.github)}'>${resources.getString(R.string.github)}</a><br/><br/>")
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

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}