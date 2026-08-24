package cn.rmshadows.textsend

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import cn.rmshadows.textsend.databinding.ActivityFolderPickBinding
import cn.rmshadows.textsend.databinding.ItemFolderPickBinding
import java.io.File

/** 打勾选文件夹。系统 DocumentsUI 在不少机型上很难对准「使用此文件夹」。 */
class FolderPickActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFolderPickBinding
    private lateinit var root: File
    private var current: File? = null
    private val selected = LinkedHashSet<String>()
    private val rows = ArrayList<Row>()
    private val adapter = FolderAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeSettings.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivityFolderPickBinding.inflate(layoutInflater)
        setContentView(binding.root)
        root = Environment.getExternalStorageDirectory()
        current = root
        binding.folderPickList.adapter = adapter
        binding.folderPickList.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) ?: return@setOnItemClickListener
            if (row.up) {
                goUp()
                return@setOnItemClickListener
            }
            if (row.file.isDirectory) {
                current = row.file
                reload()
            }
        }
        binding.folderPickCurrent.setOnClickListener {
            val dir = current ?: root
            if (FileTransfer.isExternalRoot(dir)) {
                Toast.makeText(this, R.string.folder_pick_root, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            finishWith(listOf(dir.absolutePath))
        }
        binding.folderPickDone.setOnClickListener {
            if (selected.isEmpty()) {
                return@setOnClickListener
            }
            finishWith(selected.toList())
        }
        binding.folderPickSaf.setOnClickListener { useSystemPicker() }
        binding.folderPickEmptySaf.setOnClickListener { useSystemPicker() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goUp()) {
                    finish()
                }
            }
        })
        reload()
    }

    private fun useSystemPicker() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_USE_SAF, true))
        finish()
    }

    private fun finishWith(paths: List<String>) {
        val i = Intent()
        i.putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
        setResult(RESULT_OK, i)
        finish()
    }

    private fun atRoot(): Boolean {
        val cur = current ?: return true
        return FileTransfer.isExternalRoot(cur) || cur.absolutePath == root.absolutePath
    }

    private fun goUp(): Boolean {
        if (atRoot()) {
            return false
        }
        val parent = current?.parentFile
        current = if (parent != null && parent.absolutePath.startsWith(root.absolutePath)) {
            parent
        } else {
            root
        }
        reload()
        return true
    }

    private fun listKids(dir: File): List<File> {
        val out = LinkedHashMap<String, File>()
        dir.listFiles()?.forEach { f ->
            if (!FileTransfer.skipEntry(f.name)) {
                out[f.name] = f
            }
        }
        if (FileTransfer.isExternalRoot(dir)) {
            for (hint in ROOT_HINTS) {
                if (hint in out) {
                    continue
                }
                val f = File(dir, hint)
                if (f.isDirectory) {
                    out[hint] = f
                }
            }
        }
        for (hint in HIDDEN_HINTS) {
            if (hint in out || FileTransfer.skipEntry(hint)) {
                continue
            }
            val f = File(dir, hint)
            if (f.exists()) {
                out[hint] = f
            }
        }
        return out.values.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun reload() {
        val dir = current ?: root
        binding.folderPickPath.text = dir.absolutePath
        rows.clear()
        if (!atRoot()) {
            rows.add(Row(dir.parentFile ?: root, up = true))
        }
        val kids = listKids(dir)
        rows.addAll(kids.map { Row(it) })
        adapter.notifyDataSetChanged()
        val empty = kids.isEmpty()
        val showEmpty = empty && atRoot()
        binding.folderPickEmptyBox.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.folderPickList.visibility = if (showEmpty) View.GONE else View.VISIBLE
        refreshButtons()
    }

    private fun refreshButtons() {
        binding.folderPickCurrent.isEnabled = !atRoot()
        binding.folderPickDone.isEnabled = selected.isNotEmpty()
        binding.folderPickDone.text = getString(R.string.folder_pick_done, selected.size)
    }

    private fun toggle(dir: File, on: Boolean) {
        if (FileTransfer.isExternalRoot(dir)) {
            return
        }
        if (on) {
            selected.add(dir.absolutePath)
        } else {
            selected.remove(dir.absolutePath)
        }
        refreshButtons()
    }

    private data class Row(val file: File, val up: Boolean = false)

    private inner class FolderAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView != null) {
                ItemFolderPickBinding.bind(convertView)
            } else {
                ItemFolderPickBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            }
            val item = rows[position]
            row.folderPickCheck.setOnCheckedChangeListener(null)
            if (item.up) {
                row.folderPickCheck.visibility = View.INVISIBLE
                row.folderPickCheck.isChecked = false
                row.folderPickName.text = getString(R.string.settings_inbox_up)
                row.folderPickMeta.text = ""
                return row.root
            }
            val f = item.file
            if (f.isDirectory && !FileTransfer.isExternalRoot(f)) {
                row.folderPickCheck.visibility = View.VISIBLE
                row.folderPickCheck.isChecked = selected.contains(f.absolutePath)
                row.folderPickCheck.setOnCheckedChangeListener { _, checked ->
                    toggle(f, checked)
                }
            } else {
                row.folderPickCheck.visibility = View.INVISIBLE
                row.folderPickCheck.isChecked = false
            }
            row.folderPickName.text = f.name
            row.folderPickMeta.text = if (f.isDirectory) {
                val n = listKids(f).size
                getString(R.string.folder_pick_dir_count, n)
            } else {
                FileTransfer.formatBytes(f.length())
            }
            return row.root
        }
    }

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_USE_SAF = "use_saf"

        private val ROOT_HINTS = arrayOf(
            "Download", "Downloads", "Documents", "DCIM", "Pictures",
            "nubialog", "nubia_log"
        )
        private val HIDDEN_HINTS = arrayOf(
            ".logger", ".log", ".logs", ".logcat",
            ".nubia", ".nubia_log", ".nubialog"
        )
    }
}
