package cn.rmshadows.textsend

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cn.rmshadows.textsend.databinding.ActivityInboxBrowseBinding
import cn.rmshadows.textsend.databinding.ItemInboxFileBinding
import java.io.File

/** 应用内查看接收目录。系统文件管理器在不少机型上打不开指定文件夹。 */
class InboxBrowseActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInboxBrowseBinding
    private lateinit var root: File
    private var current: File? = null
    private val rows = ArrayList<Row>()
    private val adapter = InboxAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeSettings.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivityInboxBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        root = try {
            val d = ReceiveFolder.dir()
            if (!d.isDirectory) {
                d.mkdirs()
            }
            d
        } catch (_: Exception) {
            Toast.makeText(this, R.string.settings_inbox_fail, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        current = root
        binding.inboxList.adapter = adapter
        binding.inboxList.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) ?: return@setOnItemClickListener
            if (row.up) {
                goUp()
                return@setOnItemClickListener
            }
            val f = row.file
            if (f.isDirectory) {
                current = f
                reload()
            } else {
                startActivity(
                    Intent(this, InboxPreviewActivity::class.java)
                        .putExtra(InboxPreviewActivity.EXTRA_PATH, f.absolutePath)
                )
            }
        }
        binding.inboxCopy.setOnClickListener {
            ReceiveFolder.copyPath(this, current ?: root)
        }
        binding.inboxClear.setOnClickListener {
            confirmClear()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goUp()) {
                    finish()
                }
            }
        })
        reload()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) {
            reload()
        }
    }

    private fun confirmClear() {
        if (visibleKids(root).isEmpty()) {
            Toast.makeText(this, R.string.settings_inbox_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.settings_inbox_clear_confirm)
            .setPositiveButton(R.string.settings_inbox_clear) { _, _ ->
                val ok = ReceiveFolder.clearInbox()
                current = root
                reload()
                Toast.makeText(
                    this,
                    if (ok) R.string.settings_inbox_cleared else R.string.settings_inbox_clear_fail,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(file: File) {
        val msg = if (file.isDirectory) {
            getString(R.string.settings_inbox_delete_dir_confirm, file.name)
        } else {
            getString(R.string.settings_inbox_delete_confirm, file.name)
        }
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(R.string.settings_inbox_delete) { _, _ ->
                if (ReceiveFolder.delete(file)) {
                    Toast.makeText(this, R.string.settings_inbox_deleted, Toast.LENGTH_SHORT).show()
                    reload()
                } else {
                    Toast.makeText(this, R.string.settings_inbox_delete_fail, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun atRoot(): Boolean {
        val cur = current ?: return true
        return cur.absolutePath == root.absolutePath
    }

    /** @return true 已回到上一级 */
    private fun goUp(): Boolean {
        if (atRoot()) {
            return false
        }
        val cur = current ?: return false
        val parent = cur.parentFile
        current = if (parent != null && parent.absolutePath.startsWith(root.absolutePath)) {
            parent
        } else {
            root
        }
        reload()
        return true
    }

    private fun visibleKids(dir: File): List<File> {
        return (dir.listFiles() ?: emptyArray())
            .filter { !FileTransfer.skipEntry(it.name) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun reload() {
        val dir = current ?: root
        binding.inboxPath.text = dir.absolutePath
        rows.clear()
        if (!atRoot()) {
            rows.add(Row(dir.parentFile ?: root, up = true))
        }
        val kids = visibleKids(dir)
        rows.addAll(kids.map { Row(it) })
        adapter.notifyDataSetChanged()
        val empty = kids.isEmpty() && atRoot()
        binding.inboxEmpty.text = getString(
            if (atRoot()) R.string.settings_inbox_empty else R.string.settings_inbox_dir_empty
        )
        binding.inboxEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.inboxList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.inboxClear.isEnabled = visibleKids(root).isNotEmpty()
    }

    private data class Row(val file: File, val up: Boolean = false)

    private inner class InboxAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView != null) {
                ItemInboxFileBinding.bind(convertView)
            } else {
                ItemInboxFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            }
            val item = rows[position]
            if (item.up) {
                row.inboxItemName.text = getString(R.string.settings_inbox_up)
                row.inboxItemMeta.text = ""
                row.inboxItemDelete.visibility = View.GONE
                row.inboxItemDelete.setOnClickListener(null)
                return row.root
            }
            val f = item.file
            row.inboxItemName.text = f.name
            row.inboxItemMeta.text = if (f.isDirectory) {
                val n = visibleKids(f).size
                getString(R.string.settings_inbox_dir_count, n)
            } else {
                FileTransfer.formatBytes(f.length())
            }
            row.inboxItemDelete.visibility = View.VISIBLE
            row.inboxItemDelete.setOnClickListener {
                confirmDelete(f)
            }
            return row.root
        }
    }
}
