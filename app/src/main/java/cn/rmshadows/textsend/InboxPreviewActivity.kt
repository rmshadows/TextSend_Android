package cn.rmshadows.textsend

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import cn.rmshadows.textsend.databinding.ActivityInboxPreviewBinding
import protocol.FileNames
import java.io.File

/** 应用内预览接收到的图片、视频、文本；其它类型可交给系统打开。 */
class InboxPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInboxPreviewBinding
    private var bitmap: Bitmap? = null
    private var file: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeSettings.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivityInboxPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val path = intent.getStringExtra(EXTRA_PATH)
        val f = if (path.isNullOrBlank()) null else File(path)
        if (f == null || !f.isFile || !insideInbox(f)) {
            Toast.makeText(this, R.string.settings_inbox_fail, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        file = f
        binding.previewTitle.text = f.name
        binding.previewOpen.setOnClickListener { openExternal(f) }
        binding.previewDelete.setOnClickListener { confirmDelete(f) }
        when {
            FileNames.isImageName(f.name) -> showImage(f)
            isVideo(f) -> showVideo(f)
            isText(f) -> showText(f)
            else -> showFallback(f)
        }
    }

    override fun onDestroy() {
        binding.previewVideo.stopPlayback()
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }

    private fun showImage(f: File) {
        binding.previewImage.visibility = View.VISIBLE
        try {
            val src = ImageDecoder.createSource(f)
            bitmap = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val max = 2048
                val scale = maxOf(info.size.width / max, info.size.height / max, 1)
                decoder.setTargetSampleSize(scale)
            }
            binding.previewImage.setImageBitmap(bitmap)
        } catch (_: Exception) {
            showFallback(f)
        }
    }

    private fun showVideo(f: File) {
        binding.previewVideo.visibility = View.VISIBLE
        val mc = MediaController(this)
        mc.setAnchorView(binding.previewVideo)
        binding.previewVideo.setMediaController(mc)
        binding.previewVideo.setOnErrorListener { _: MediaPlayer?, _: Int, _: Int ->
            binding.previewVideo.visibility = View.GONE
            showFallback(f)
            true
        }
        binding.previewVideo.setVideoPath(f.absolutePath)
        binding.previewVideo.start()
    }

    override fun onPause() {
        if (binding.previewVideo.visibility == View.VISIBLE) {
            binding.previewVideo.pause()
        }
        super.onPause()
    }

    private fun showText(f: File) {
        binding.previewTextScroll.visibility = View.VISIBLE
        try {
            val cap = 256 * 1024
            val bytes = if (f.length() > cap) {
                val buf = ByteArray(cap)
                val n = f.inputStream().use { it.read(buf) }
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            } else {
                f.readBytes()
            }
            var text = String(bytes, Charsets.UTF_8)
            if (f.length() > cap) {
                text += "\n\n…（只显示前 256 KB）"
            }
            binding.previewText.text = text
        } catch (_: Exception) {
            showFallback(f)
        }
    }

    private fun showFallback(f: File) {
        binding.previewFallback.visibility = View.VISIBLE
        binding.previewFallback.text = getString(
            R.string.settings_inbox_no_preview,
            f.name,
            FileTransfer.formatBytes(f.length())
        )
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.settings_inbox_delete_confirm, f.name))
            .setPositiveButton(R.string.settings_inbox_delete) { _, _ ->
                binding.previewVideo.stopPlayback()
                bitmap?.recycle()
                bitmap = null
                if (ReceiveFolder.delete(f)) {
                    Toast.makeText(this, R.string.settings_inbox_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, R.string.settings_inbox_delete_fail, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openExternal(f: File) {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.files", f)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.settings_inbox_fail, Toast.LENGTH_SHORT).show()
            return
        }
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(android.content.Intent.createChooser(view, f.name))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.settings_inbox_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun insideInbox(f: File): Boolean {
        return try {
            val root = ReceiveFolder.dir().canonicalFile
            val cur = f.canonicalFile
            cur == root || cur.path.startsWith(root.path + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val EXTRA_PATH = "path"

        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")
        private val TEXT_EXT = setOf(
            "txt", "log", "json", "xml", "csv", "md", "html", "htm",
            "kt", "java", "py", "sh", "ini", "conf", "yaml", "yml"
        )

        fun isVideo(f: File): Boolean = f.extension.lowercase() in VIDEO_EXT
        fun isText(f: File): Boolean = f.extension.lowercase() in TEXT_EXT
    }
}
