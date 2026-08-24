package cn.rmshadows.textsend

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.provider.DocumentsContract
import android.widget.Toast
import protocol.FileNames
import java.io.File

/** 接收目录：系统下载/TextSend，写不进去则用应用专属目录。 */
object ReceiveFolder {
    fun dir(): File {
        return FileNames.inboxDir().toFile()
    }

    fun pathLabel(): String {
        return try {
            dir().absolutePath
        } catch (_: Exception) {
            FileNames.publicInbox().absolutePath
        }
    }

    fun browse(context: Context) {
        val folder = ensureDir(context) ?: return
        context.startActivity(Intent(context, InboxBrowseActivity::class.java).also {
            if (context !is Activity) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        })
    }

    /** 用系统/第三方文件管理器打开，和别的下载类 App 同一套路。 */
    fun openManager(context: Context) {
        val folder = ensureDir(context) ?: return
        allowFileUri()
        val path = folder.absolutePath
        val doc = documentUri(folder)
        val tries = ArrayList<Intent>()
        if (doc != null) {
            tries.add(viewDir(doc, DocumentsContract.Document.MIME_TYPE_DIR, path))
            tries.add(viewDir(treeDocUri(doc), DocumentsContract.Document.MIME_TYPE_DIR, path))
            tries.add(openTree(doc))
        }
        tries.add(viewDir(Uri.parse("file://$path"), "resource/folder", path))
        tries.add(viewDir(Uri.parse("file://$path"), DocumentsContract.Document.MIME_TYPE_DIR, path))
        for (intent in tries) {
            if (launch(context, intent)) {
                return
            }
        }
        Toast.makeText(context, R.string.settings_inbox_manager_fail, Toast.LENGTH_SHORT).show()
        browse(context)
    }

    fun copyPath(context: Context, folder: File = try {
        dir()
    } catch (_: Exception) {
        FileNames.publicInbox()
    }) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", folder.absolutePath))
        Toast.makeText(
            context,
            context.getString(R.string.settings_inbox_copied, folder.absolutePath),
            Toast.LENGTH_LONG
        ).show()
    }

    fun delete(file: File): Boolean {
        return try {
            val root = dir().canonicalFile
            val target = file.canonicalFile
            if (target == root || !target.path.startsWith(root.path + File.separator)) {
                return false
            }
            target.deleteRecursively()
        } catch (_: Exception) {
            false
        }
    }

    /** 清空接收根目录里的内容，不删目录本身。 */
    fun clearInbox(): Boolean {
        return try {
            val root = dir()
            if (!root.isDirectory) {
                return true
            }
            val files = root.listFiles() ?: return true
            var ok = true
            for (f in files) {
                if (!f.deleteRecursively()) {
                    ok = false
                }
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureDir(context: Context): File? {
        return try {
            val d = dir()
            if (!d.isDirectory && !d.mkdirs()) {
                throw java.io.IOException("无法创建接收目录")
            }
            d
        } catch (_: Exception) {
            Toast.makeText(context, R.string.settings_inbox_fail, Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun viewDir(uri: Uri, mime: String, path: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("org.openintents.extra.ABSOLUTE_PATH", path)
            putExtra("path", path)
            putExtra("folder_path", path)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }
    }

    private fun openTree(initial: Uri): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val toStart = if (intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) {
            intent
        } else {
            Intent.createChooser(intent, context.getString(R.string.settings_inbox_chooser)).also {
                if (context !is Activity) {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        return try {
            context.startActivity(toStart)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun allowFileUri() {
        try {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        } catch (_: Exception) {
        }
    }

    private fun documentUri(dir: File): Uri? {
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val path = dir.absolutePath
        if (path != root && !path.startsWith("$root/")) {
            return null
        }
        val rel = path.substring(root.length).trimStart('/')
        if (rel.isEmpty()) {
            return null
        }
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$rel"
        )
    }

    private fun treeDocUri(doc: Uri): Uri {
        val id = DocumentsContract.getDocumentId(doc)
        val tree = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            id
        )
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }
}
