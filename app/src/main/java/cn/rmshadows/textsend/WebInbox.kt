package cn.rmshadows.textsend

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.google.gson.JsonObject
import protocol.FileNames
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 浏览器分片上传到手机。协议与电脑端网页桥相同：同一 id 再 begin 会从已写入的位置续传。
 * 完成后的文件只在系统下载目录的 TextSend 里。半截在传完之前不对外显示。
 */
object WebInbox {
    const val CHUNK = 1024 * 1024
    private const val FORCE_EVERY = 4L * 1024 * 1024
    private const val MAX_SIZE = 20L * 1024 * 1024 * 1024
    private const val KEEP_MS = 72L * 60L * 60L * 1000L
    private val ID_RE = Regex("[0-9a-fA-F]{16,64}")

    private val parts = HashMap<String, Part>()
    private var app: Context? = null

    class Saved(val name: String, val size: Long, val scanPath: String?)

    private class Part(
        val id: String,
        val name: String,
        val rel: String,
        val size: Long,
        var written: Long,
        val file: Path?,
        val uri: Uri?
    ) {
        var channel: FileChannel? = null
        var pfd: ParcelFileDescriptor? = null
        var forced: Long = 0
        var aborted: Boolean = false
        var paused: Boolean = false
    }

    fun attach(ctx: Context) {
        app = ctx.applicationContext
    }

    @Synchronized
    fun begin(id: String?, name: String?, rel: String?, size: Long): String {
        if (id == null || !ID_RE.matches(id)) {
            throw IOException("id")
        }
        if (size < 0 || size > MAX_SIZE) {
            throw IOException("size")
        }
        sweep()
        val existing = parts[id]
        if (existing != null && existing.size == size && backingExists(existing)) {
            existing.aborted = false
            existing.paused = false
            return json(existing)
        }
        if (existing != null) {
            closeChannel(existing)
            deleteBacking(existing)
            parts.remove(id)
        }
        val part = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openStore(id, name ?: "", rel ?: "", size)
        } else {
            openFile(id, name ?: "", rel ?: "", size)
        }
        parts[id] = part
        return json(part)
    }

    /** 必须按序。已经写过的块直接忽略，便于重试。 */
    @Synchronized
    fun chunk(id: String?, index: Int, data: ByteArray?) {
        val part = parts[id] ?: throw IOException("missing")
        if (part.aborted) {
            throw IOException("cancelled")
        }
        if (index < 0 || data == null) {
            throw IOException("index")
        }
        val off = index.toLong() * CHUNK
        if (off < 0 || data.size > CHUNK || off > part.size || off + data.size > part.size) {
            throw IOException("size")
        }
        if (off + data.size <= part.written) {
            return
        }
        if (off != part.written) {
            throw IOException("order")
        }
        val last = off + data.size == part.size
        if (!last && data.size != CHUNK) {
            throw IOException("short")
        }
        val ch = channel(part)
        ch.position(off)
        val buf = ByteBuffer.wrap(data)
        while (buf.hasRemaining()) {
            ch.write(buf)
        }
        part.written = off + data.size
        part.paused = false
        if (last || part.written - part.forced >= FORCE_EVERY) {
            ch.force(false)
            part.forced = part.written
        }
    }

    @Synchronized
    fun pause(id: String?) {
        for (part in parts.values) {
            if (id != null && id.isNotEmpty() && id != part.id) {
                continue
            }
            if (!part.aborted) {
                part.paused = true
            }
        }
    }

    @Synchronized
    fun finish(id: String?): Saved {
        val part = parts[id] ?: throw IOException("missing")
        if (part.aborted) {
            throw IOException("cancelled")
        }
        if (part.written != part.size) {
            throw IOException("incomplete")
        }
        val ch = part.channel
        if (ch != null && ch.isOpen && ch.size() > part.size) {
            ch.truncate(part.size)
        }
        closeChannel(part)
        val saved = if (part.uri != null) {
            publishStore(part)
        } else {
            publishFile(part)
        }
        parts.remove(id)
        return saved
    }

    /** 只清内存登记。半截还在下载目录里，同一 id 再 begin 会接着写。 */
    @Synchronized
    fun release() {
        for (part in parts.values) {
            closeChannel(part)
        }
        parts.clear()
    }

    private fun openFile(id: String, name: String, rel: String, size: Long): Part {
        val dir = publicRoot().resolve(".webpart")
        Files.createDirectories(dir)
        val file = dir.resolve("$id.part")
        if (!Files.exists(file)) {
            Files.write(file, ByteArray(0))
        }
        val channel = FileChannel.open(file, StandardOpenOption.WRITE)
        val written = try {
            align(channel, size)
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        val part = Part(id, name, rel, size, written, file, null)
        part.channel = channel
        return part
    }

    private fun openStore(id: String, name: String, rel: String, size: Long): Part {
        val ctx = needCtx()
        val resolver = ctx.contentResolver
        var uri = findPart(resolver, id)
        if (uri == null) {
            uri = insertPart(resolver, id)
        }
        val opened = openFd(resolver, uri)
        val written = try {
            align(opened.first, size)
        } catch (_: IOException) {
            try {
                opened.first.close()
            } catch (_: IOException) {
            }
            try {
                opened.second.close()
            } catch (_: IOException) {
            }
            resolver.delete(uri, null, null)
            val fresh = insertPart(resolver, id)
            val again = openFd(resolver, fresh)
            val part = Part(id, name, rel, size, 0, null, fresh)
            part.channel = again.first
            part.pfd = again.second
            return part
        }
        val part = Part(id, name, rel, size, written, null, uri)
        part.channel = opened.first
        part.pfd = opened.second
        return part
    }

    private fun publishFile(part: Part): Saved {
        val file = part.file ?: throw IOException("disk")
        val (display, relative) = place(part.name, part.rel)
        val root = publicRoot()
        val parentRel = relative.removePrefix(downloadFolder()).trim('/')
        val parent = if (parentRel.isEmpty()) root else FileNames.resolveUnder(root, parentRel)
        Files.createDirectories(parent)
        var dest = parent.resolve(display)
        if (Files.exists(dest)) {
            dest = FileNames.unique(parent, display)
        }
        try {
            Files.move(file, dest, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(file, dest)
        }
        return Saved(dest.fileName.toString(), part.size, dest.toAbsolutePath().toString())
    }

    private fun publishStore(part: Part): Saved {
        val uri = part.uri ?: throw IOException("disk")
        val (display, relative) = place(part.name, part.rel)
        val finalName = uniqueDisplay(display, relative, uri)
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(finalName))
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        val updated = needCtx().contentResolver.update(uri, values, null, null)
        if (updated < 1) {
            throw IOException("disk")
        }
        return Saved(finalName, part.size, null)
    }

    private fun channel(part: Part): FileChannel {
        val current = part.channel
        if (current != null && current.isOpen) {
            return current
        }
        val file = part.file
        if (file != null) {
            val ch = FileChannel.open(file, StandardOpenOption.WRITE)
            part.channel = ch
            return ch
        }
        val uri = part.uri ?: throw IOException("disk")
        val opened = openFd(needCtx().contentResolver, uri)
        part.channel = opened.first
        part.pfd = opened.second
        return opened.first
    }

    private fun align(channel: FileChannel, size: Long): Long {
        var written = channel.size()
        if (written > size) {
            channel.truncate(0)
            written = 0
        }
        val aligned = (written / CHUNK) * CHUNK
        if (aligned != written) {
            channel.truncate(aligned)
            written = aligned
        }
        return written
    }

    private fun backingExists(part: Part): Boolean {
        val file = part.file
        if (file != null) {
            return Files.isRegularFile(file)
        }
        val uri = part.uri ?: return false
        return try {
            needCtx().contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteBacking(part: Part) {
        val file = part.file
        if (file != null) {
            try {
                Files.deleteIfExists(file)
            } catch (_: IOException) {
            }
            return
        }
        val uri = part.uri ?: return
        try {
            needCtx().contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun closeChannel(part: Part) {
        try {
            val ch = part.channel
            if (ch != null && ch.isOpen) {
                ch.force(false)
                ch.close()
            }
        } catch (_: IOException) {
        }
        part.channel = null
        try {
            part.pfd?.close()
        } catch (_: IOException) {
        }
        part.pfd = null
    }

    private fun publicRoot(): Path {
        val dir = FileNames.publicInbox()
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("disk")
        }
        val probe = File(dir, ".textsend.write")
        try {
            if (!probe.exists() && !probe.createNewFile()) {
                throw IOException("disk")
            }
            if (!probe.delete() && probe.exists()) {
                throw IOException("disk")
            }
        } catch (e: IOException) {
            if (e.message == "disk") {
                throw e
            }
            throw IOException("disk")
        }
        return dir.toPath()
    }

    private fun needCtx(): Context {
        return app ?: throw IOException("disk")
    }

    private fun downloadFolder(): String {
        return Environment.DIRECTORY_DOWNLOADS + "/" + FileNames.FOLDER + "/"
    }

    private fun place(name: String, rel: String): Pair<String, String> {
        if (rel.isNotBlank()) {
            val safe = FileNames.sanitizeRelPath(rel) ?: throw IOException("path")
            val slash = safe.lastIndexOf('/')
            val display = if (slash >= 0) safe.substring(slash + 1) else safe
            val parent = if (slash >= 0) safe.substring(0, slash) else ""
            val relative = if (parent.isEmpty()) downloadFolder() else downloadFolder() + parent + "/"
            return display to relative
        }
        return FileNames.sanitize(if (name.isBlank()) "file" else name) to downloadFolder()
    }

    private fun downloads(): Uri {
        return MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    private fun insertPart(resolver: android.content.ContentResolver, id: String): Uri {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "$id.part")
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, downloadFolder())
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        return resolver.insert(downloads(), values) ?: throw IOException("disk")
    }

    private fun findPart(resolver: android.content.ContentResolver, id: String): Uri? {
        val name = "$id.part"
        for (rel in arrayOf(downloadFolder(), downloadFolder().trimEnd('/'))) {
            resolver.query(
                downloads(),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(rel, name),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val col = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (col >= 0) {
                        return ContentUris.withAppendedId(downloads(), c.getLong(col))
                    }
                }
            }
        }
        return null
    }

    private fun openFd(resolver: android.content.ContentResolver, uri: Uri): Pair<FileChannel, ParcelFileDescriptor> {
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("disk")
        return try {
            FileOutputStream(pfd.fileDescriptor).channel to pfd
        } catch (e: IOException) {
            pfd.close()
            throw e
        }
    }

    private fun uniqueDisplay(display: String, relative: String, self: Uri): String {
        val taken = HashSet<String>()
        val resolver = needCtx().contentResolver
        for (rel in arrayOf(relative, relative.trimEnd('/'))) {
            resolver.query(
                downloads(),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(rel),
                null
            )?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (idCol >= 0 && nameCol >= 0) {
                        val row = ContentUris.withAppendedId(downloads(), c.getLong(idCol))
                        if (row != self) {
                            c.getString(nameCol)?.let { taken.add(it) }
                        }
                    }
                }
            }
        }
        if (display !in taken) {
            return display
        }
        val dot = display.lastIndexOf('.')
        val stem = if (dot > 0) display.substring(0, dot) else display
        val ext = if (dot > 0) display.substring(dot) else ""
        for (i in 1 until 10_000) {
            val cand = "$stem ($i)$ext"
            if (cand !in taken) {
                return cand
            }
        }
        throw IOException("disk")
    }

    private fun mimeOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) {
            return "application/octet-stream"
        }
        val ext = name.substring(dot + 1).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun json(part: Part): String {
        val o = JsonObject()
        o.addProperty("v", 1)
        o.addProperty("id", part.id)
        o.addProperty("next", part.written)
        o.addProperty("chunk", CHUNK)
        return o.toString()
    }

    private fun sweep() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sweepStore()
        } else {
            sweepFile()
        }
    }

    private fun sweepFile() {
        try {
            val dir = FileNames.publicInbox().toPath().resolve(".webpart")
            if (!Files.isDirectory(dir)) {
                return
            }
            val cutoff = System.currentTimeMillis() - KEEP_MS
            Files.list(dir).use { stream ->
                stream.forEach { p ->
                    val fn = p.fileName.toString()
                    if (!fn.endsWith(".part")) {
                        return@forEach
                    }
                    val id = fn.substring(0, fn.length - 5)
                    if (parts.containsKey(id)) {
                        return@forEach
                    }
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.deleteIfExists(p)
                    }
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun sweepStore() {
        val ctx = app ?: return
        try {
            val cutoff = (System.currentTimeMillis() - KEEP_MS) / 1000L
            ctx.contentResolver.query(
                downloads(),
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1",
                arrayOf(downloadFolder()),
                null
            )?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val timeCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    if (idCol < 0 || nameCol < 0 || timeCol < 0) {
                        continue
                    }
                    val name = c.getString(nameCol) ?: continue
                    if (!name.endsWith(".part")) {
                        continue
                    }
                    val id = name.substring(0, name.length - 5)
                    if (!ID_RE.matches(id) || parts.containsKey(id)) {
                        continue
                    }
                    if (c.getLong(timeCol) < cutoff) {
                        val uri = ContentUris.withAppendedId(downloads(), c.getLong(idCol))
                        ctx.contentResolver.delete(uri, null, null)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
