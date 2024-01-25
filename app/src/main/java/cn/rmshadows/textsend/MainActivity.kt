package cn.rmshadows.textsend

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import cn.rmshadows.textsend.databinding.ActivityMainBinding
import cn.rmshadows.textsend.viewmodels.ServerFragmentViewModel
import cn.rmshadows.textsend.viewmodels.TextsendViewModel
import kotlinx.coroutines.launch
import utils.Constant
import java.net.Socket
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {
    val TAG = Constant.TAG
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: TextsendViewModel
    private lateinit var sfviewModel: ServerFragmentViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        viewModel = ViewModelProvider(this).get(TextsendViewModel::class.java)
        sfviewModel = ViewModelProvider(this).get(ServerFragmentViewModel::class.java)
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
                // 切换模式 TODO: 模式切换还不写
                // https://stackoverflow.com/questions/58686104/why-does-my-navcontroller-cannot-find-an-id-that-i-already-have
                // 必须有上面这一句
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                if (viewModel.uiState.value.isServerMode) {
                    // 如果已经连接会断开
                    if (sfviewModel.uiState.value.serverRunning) {
                        // 关闭服务
                        TODO()
                    }
                    // 只能让用户手动返回
//                    navController.navigate(R.id.action_ServerFragment_to_ClientFragment)
                } else {
                    // 如果已经连接会断开
                    if (viewModel.uiState.value.isClientConnected) {
                        // 断开
                        TODO()
                    }
                    navController.navigate(R.id.action_ClientFragment_to_ServerFragment)
                }
                true
            }

            R.id.action_about -> {
                // 关于
                val alertDialogBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
                // 如果import中有R，则失效
                alertDialogBuilder.setIcon(R.mipmap.ic_textsend)
                val message = StringBuilder()
                message.append(resources.getString(R.string.app_name_full))
                message.append("\n\nVersion: ")
                message.append(resources.getString(R.string.app_version))
                message.append("\nAuthor: ")
                message.append(resources.getString(R.string.app_version))
                message.append("\nLICENSE: ")
                message.append(resources.getString(R.string.app_License))
                alertDialogBuilder.setTitle("About")
                alertDialogBuilder.setMessage(message)
                alertDialogBuilder.setNegativeButton("Close") { _, _ -> } //...To-do
                alertDialogBuilder.show()
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
                    // 服务端、二维码端禁用切换按钮
                    // https://developer.android.com/develop/ui/views/components/menus?hl=zh-cn
                    if (menu != null) {
                        if (it.isServerMode || it.uiIndex == -1) {
                            // 服务端禁用
                            menu.getItem(0).setEnabled(false)
                        } else {
                            menu.getItem(0).setEnabled(true)
                        }
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

    fun startClient(socket: Socket): Unit {

    }

    fun startServer(socket: Socket): Unit {

    }
}