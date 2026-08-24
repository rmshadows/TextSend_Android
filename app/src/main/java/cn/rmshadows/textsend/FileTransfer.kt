package cn.rmshadows.textsend

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import protocol.FileIoCallback
import protocol.FileNames
import protocol.Protocol
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 待发列表 + 进度回调。分享进来的文件也进这里，点发送才传。 */
object FileTransfer {
    /** 大于此大小不进 cache，发送时直接读原 URI，避免选 1GB 文件时卡死。 */
    private const val CACHE_COPY_MAX = 16L * 1024 * 1024
    @Volatile
    var callback: FileIoCallback? = null

    @Volatile
    var onPendingChanged: (() -> Unit)? = null

    @Volatile
    var sending: Boolean = false
        private set

    fun beginSend() {
        sending = true
    }

    fun endSend() {
        sending = false
    }

    data class Item(
        val id: String,
        val name: String,
        val size: Long,
        val uri: Uri?,
        val cacheFile: File?,
        val treeName: String? = null,
        val relPath: String? = null
    )

    private val lock = Any()
    private val pending = ArrayList<Item>()
    @Volatile
    private var suppressNotify = false

    fun pendingCount(): Int {
        synchronized(lock) {
            return pending.size
        }
    }

    fun snapshot(): List<Item> {
        synchronized(lock) {
            return ArrayList(pending)
        }
    }

    fun flatCount(): Int {
        synchronized(lock) {
            return pending.count { it.treeName.isNullOrBlank() }
        }
    }

    fun add(item: Item): Boolean {
        synchronized(lock) {
            if (item.treeName.isNullOrBlank() && pending.count { it.treeName.isNullOrBlank() } >= Protocol.FILE_BATCH_MAX) {
                return false
            }
            if (pending.any { same(it, item) }) {
                return false
            }
            pending.add(item)
        }
        if (!suppressNotify) {
            notifyPending()
        }
        return true
    }

    fun <T> withBatchNotify(block: () -> T): T {
        if (suppressNotify) {
            return block()
        }
        suppressNotify = true
        try {
            return block()
        } finally {
            suppressNotify = false
            notifyPending()
        }
    }

    fun remove(item: Item) {
        val gone: Item?
        synchronized(lock) {
            gone = pending.firstOrNull { it.id == item.id }
            pending.removeAll { it.id == item.id }
        }
        gone?.cacheFile?.delete()
        notifyPending()
    }

    fun removeId(id: String) {
        val gone: Item?
        synchronized(lock) {
            gone = pending.firstOrNull { it.id == id }
            pending.removeAll { it.id == id }
        }
        gone?.cacheFile?.delete()
        notifyPending()
    }

    fun clearPending(): Boolean {
        if (sending) {
            return false
        }
        val files: List<File>
        synchronized(lock) {
            files = pending.mapNotNull { it.cacheFile }
            pending.clear()
        }
        for (f in files) {
            f.delete()
        }
        notifyPending()
        return true
    }

    fun sendCacheDir(ctx: Context): File {
        return File(ctx.cacheDir, "send")
    }

    fun sendCacheBytes(ctx: Context): Long {
        return dirSize(sendCacheDir(ctx))
    }

    /** 成功返回清除前的字节数；发送中返回 -1。 */
    fun clearSendCache(ctx: Context): Long {
        if (sending) {
            return -1
        }
        val dir = sendCacheDir(ctx)
        val before = dirSize(dir)
        synchronized(lock) {
            pending.clear()
        }
        deleteDirContents(dir)
        notifyPending()
        return before
    }

    fun formatBytes(n: Long): String {
        if (n < 1024) {
            return "$n B"
        }
        if (n < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
        }
        if (n < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024))
        }
        return String.format(java.util.Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024))
    }

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) {
            return 0
        }
        var n = 0L
        val files = dir.listFiles() ?: return 0
        for (f in files) {
            n += if (f.isDirectory) dirSize(f) else f.length()
        }
        return n
    }

    private fun deleteDirContents(dir: File) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                deleteDirContents(f)
            }
            f.delete()
        }
    }

    fun isTreeUri(uri: Uri): Boolean {
        return try {
            DocumentsContract.isTreeUri(uri)
        } catch (_: Exception) {
            false
        }
    }

    fun isDirMime(type: String?): Boolean {
        if (type.isNullOrBlank()) {
            return false
        }
        val t = type.lowercase()
        return t == DocumentsContract.Document.MIME_TYPE_DIR
            || t == "resource/folder"
            || t == "inode/directory"
            || t == "application/vnd.android.document/directory"
            || t == "vnd.android.cursor.dir"
            || t.endsWith("/directory")
            || t.endsWith("/folder")
    }

    fun isFolderUri(ctx: Context, uri: Uri): Boolean {
        if (isTreeUri(uri)) {
            return true
        }
        val local = guessLocalFile(ctx, uri)
        if (local != null && local.isDirectory) {
            return true
        }
        val mime = try {
            ctx.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        if (isDirMime(mime)) {
            return true
        }
        return queryDocMime(ctx, uri)?.let { isDirMime(it) } == true
    }

    /** 文件管理器分享的目录，或 SAF 树。按相对路径入队。 */
    fun ingestSharedFolder(ctx: Context, uri: Uri): Pair<String, Int> {
        return withBatchNotify {
            ingestSharedFolderInner(ctx, uri)
        }
    }

    private fun ingestSharedFolderInner(ctx: Context, uri: Uri): Pair<String, Int> {
        grantRead(ctx, uri)
        if (isTreeUri(uri)) {
            val r = ingestTree(ctx, uri)
            if (r.second > 0) {
                return r
            }
        }
        val tree = asTreeUri(ctx, uri)
        if (tree != null && tree != uri) {
            grantRead(ctx, tree)
            val r = ingestTree(ctx, tree)
            if (r.second > 0) {
                return r
            }
        }
        val local = guessLocalFile(ctx, uri)
        if (local != null && local.isDirectory) {
            val r = ingestFileDir(local)
            if (r.second > 0) {
                return r
            }
        }
        val loose = ingestDocumentDir(ctx, uri)
        if (loose.second > 0) {
            return loose
        }
        return folderName(ctx, uri) to 0
    }

    /** 分享标题里像文件夹名的留下来，像「3 个文件」的丢掉。 */
    fun folderHint(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val n = raw.trim()
        if (n.matches(Regex("""\d+\s*(个|张)?\s*(文件|图片|视频|items?|files?).?""", RegexOption.IGNORE_CASE))) {
            return null
        }
        val base = FileNames.sanitize(n.substringAfterLast('/').substringAfterLast('\\'))
        return if (base.isBlank() || base.equals("file", true)) null else base
    }

    /**
     * 分享进来的 URI。能还原成文件夹树则入队并返回树名和个数，否则 null。
     * 文件管理器常把「分享文件夹」拆成一堆文件 URI，这里按共同父目录拼回树。
     */
    fun ingestShareAsFolder(ctx: Context, uris: List<Uri>, hint: String?): Pair<String, Int>? {
        if (uris.isEmpty()) {
            return null
        }
        return withBatchNotify {
            ingestShareAsFolderLocked(ctx, uris, hint)
        }
    }

    private fun ingestShareAsFolderLocked(ctx: Context, uris: List<Uri>, hint: String?): Pair<String, Int>? {
        if (uris.isEmpty()) {
            return null
        }
        val diskRoot = findSharedRoot(ctx, uris, hint)
        android.util.Log.i(
            "Textsend",
            "folder: hint=$hint uris=${uris.size} diskRoot=${diskRoot?.absolutePath ?: "-"}"
        )
        if (diskRoot != null) {
            val r = ingestFileDir(diskRoot)
            android.util.Log.i("Textsend", "folder: disk walk ${r.first} +${r.second}")
            if (r.second > 0) {
                return r
            }
        }
        val folders = uris.filter { isFolderUri(ctx, it) }.distinct()
        if (folders.isNotEmpty()) {
            var name = hint ?: ""
            var added = 0
            for (f in folders) {
                val r = ingestSharedFolder(ctx, f)
                if (r.second > 0) {
                    name = r.first
                    added += r.second
                }
            }
            return if (added > 0) name to added else null
        }
        if (uris.size == 1) {
            val r = ingestSharedFolder(ctx, uris[0])
            return if (r.second > 0) r else null
        }
        return ingestFilesAsTree(ctx, uris, hint)
    }

    private fun ingestFilesAsTree(ctx: Context, uris: List<Uri>, hint: String?): Pair<String, Int>? {
        val locals = uris.map { guessLocalFile(ctx, it) }
        if (locals.all { it != null && it.isFile }) {
            val root = commonLocalDir(locals.requireNoNulls())
            if (root != null && !isStorageRoot(root)) {
                val treeName = FileNames.sanitize(hint ?: root.name)
                val rootPath = try {
                    root.canonicalPath.trimEnd('/') + "/"
                } catch (_: Exception) {
                    root.absolutePath.trimEnd('/') + "/"
                }
                var added = 0
                for (f in locals.requireNoNulls()) {
                    val full = try {
                        f.canonicalPath
                    } catch (_: Exception) {
                        f.absolutePath
                    }
                    if (!full.startsWith(rootPath)) {
                        continue
                    }
                    val rel = FileNames.sanitizeRelPath(full.substring(rootPath.length)) ?: continue
                    if (f.length() <= 0 || f.length() > Protocol.FILE_SIZE_MAX) {
                        continue
                    }
                    val item = Item(
                        UUID.randomUUID().toString(),
                        FileNames.sanitize(f.name),
                        f.length(),
                        Uri.fromFile(f),
                        null,
                        treeName,
                        rel
                    )
                    if (add(item)) {
                        added++
                    }
                }
                if (added > 0) {
                    return treeName to added
                }
            }
        }
        val paths = uris.map { storageRelPath(ctx, it) }
        val prefix = if (paths.all { it != null }) {
            commonFolderPrefix(paths.requireNoNulls())
        } else {
            null
        }
        val treeName = when {
            prefix != null -> FileNames.sanitize(hint ?: prefix.substringAfterLast('/'))
            hint != null -> FileNames.sanitize(hint)
            else -> return null
        }
        if (treeName.isBlank()) {
            return null
        }
        var added = 0
        for (i in uris.indices) {
            val rel = if (prefix != null && paths[i] != null) {
                FileNames.sanitizeRelPath(paths[i]!!.removePrefix("$prefix/").trim('/'))
            } else {
                FileNames.sanitizeRelPath(queryDisplayName(ctx.contentResolver, uris[i]))
            } ?: continue
            if (looksSmashed(rel.substringAfterLast('/'))) {
                continue
            }
            val item = ingest(ctx, uris[i])?.copy(treeName = treeName, relPath = rel) ?: continue
            if (add(item)) {
                added++
            }
        }
        return if (added > 0) treeName to added else null
    }

    /**
     * 文件管理器给的 URI 经常没有目录权限、显示名还会把两个文件粘在一起。
     * 能在磁盘上找到真实文件夹就走磁盘枚举。
     */
    private fun findSharedRoot(ctx: Context, uris: List<Uri>, hint: String?): File? {
        val names = LinkedHashSet<String>()
        if (!hint.isNullOrBlank()) {
            names.add(hint)
        }
        for (uri in uris) {
            val local = guessLocalFile(ctx, uri)
            if (local != null) {
                var p: File? = if (local.isDirectory) local else local.parentFile
                while (p != null && !isStorageRoot(p)) {
                    if (local.isFile && looksSmashed(local.name) && p.isDirectory) {
                        return if (hint != null) walkUpToName(p, hint) ?: p else p
                    }
                    if (hint != null && p.name.equals(hint, ignoreCase = true) && p.isDirectory) {
                        return p
                    }
                    p = p.parentFile
                }
                if (local.isDirectory && !isStorageRoot(local)) {
                    return local
                }
            }
            val rel = storageRelPath(ctx, uri)
            if (!rel.isNullOrBlank()) {
                names.add(rel.substringBefore('/').ifBlank { rel })
            }
        }
        val storage = Environment.getExternalStorageDirectory()
        val extra = listOf(
            "",
            "Download",
            "Downloads",
            "Documents",
            "Android/data",
            "nubialog",
            "nubia_log"
        )
        for (name in names) {
            val raw = name.trim().trimStart('/')
            val safe = FileNames.sanitize(raw.substringBefore('/'))
            if (safe.isBlank() || safe.equals("file", true)) {
                continue
            }
            for (sub in extra) {
                val f = if (sub.isEmpty()) File(storage, safe) else File(storage, "$sub/$safe")
                if (f.isDirectory) {
                    return f
                }
            }
            val logger = File(storage, "$safe/.logger")
            if (logger.isDirectory) {
                return logger.parentFile
            }
        }
        val shallow = findDirNamed(storage, names, 3)
        if (shallow != null) {
            return shallow
        }
        return null
    }

    private fun findDirNamed(root: File, names: Set<String>, depth: Int): File? {
        if (depth < 0 || !root.isDirectory) {
            return null
        }
        val kids = root.listFiles() ?: return null
        for (f in kids) {
            if (!f.isDirectory || skipEntry(f.name)) {
                continue
            }
            if (names.any { f.name.equals(it, ignoreCase = true) }) {
                return f
            }
        }
        if (depth == 0) {
            return null
        }
        for (f in kids) {
            if (!f.isDirectory || skipEntry(f.name) || f.name.startsWith("Android")) {
                continue
            }
            findDirNamed(f, names, depth - 1)?.let { return it }
        }
        return null
    }

    private fun walkUpToName(start: File, name: String): File? {
        var p: File? = start
        while (p != null && !isStorageRoot(p)) {
            if (p.name.equals(name, ignoreCase = true) && p.isDirectory) {
                return p
            }
            p = p.parentFile
        }
        return null
    }

    private fun looksSmashed(name: String): Boolean {
        return Regex("""\.[A-Za-z0-9]{1,8}[A-Za-z_].+\.[A-Za-z0-9]{1,8}$""").containsMatchIn(name)
    }

    /** kernel.txt + CNCommon_V3.25-xxx.txt 被文件管理器粘成一个名字。 */
    private fun splitSmashedName(name: String): List<String> {
        val parts = ArrayList<String>()
        var rest = name
        val sep = Regex("""\.[A-Za-z0-9]{1,8}(?=[A-Z])""")
        while (true) {
            val m = sep.find(rest) ?: break
            val end = m.range.last + 1
            if (end <= 0 || end >= rest.length) {
                break
            }
            parts.add(rest.substring(0, end))
            rest = rest.substring(end)
        }
        if (rest.isNotEmpty()) {
            parts.add(rest)
        }
        return if (parts.size >= 2) parts else emptyList()
    }

    private fun storageRelPath(ctx: Context, uri: Uri): String? {
        val local = guessLocalFile(ctx, uri)
        if (local != null && local.isFile) {
            val root = try {
                Environment.getExternalStorageDirectory().canonicalPath.trimEnd('/')
            } catch (_: Exception) {
                Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
            }
            val full = try {
                local.canonicalPath
            } catch (_: Exception) {
                local.absolutePath
            }
            if (full.startsWith("$root/")) {
                return full.substring(root.length + 1).replace('\\', '/')
            }
        }
        val id = try {
            when {
                isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.isDocumentUri(ctx, uri) -> DocumentsContract.getDocumentId(uri)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (!id.isNullOrBlank()) {
            val decoded = Uri.decode(id)
            when {
                decoded.startsWith("primary:") ->
                    return decoded.substringAfter("primary:").replace('\\', '/').trim('/')
                decoded.startsWith("raw:") -> {
                    val f = File(decoded.substring(4))
                    return storageRelPath(ctx, Uri.fromFile(f))
                }
                decoded.contains('/') -> {
                    val cut = decoded.substringAfter(':', decoded).replace('\\', '/').trim('/')
                    if (cut.contains('/')) {
                        return cut
                    }
                }
            }
        }
        val decodedPath = Uri.decode(uri.path ?: "")
        for (marker in listOf("/storage/emulated/0/", "/sdcard/", "/external_files/", "/external_root/")) {
            val i = decodedPath.indexOf(marker)
            if (i >= 0) {
                val rest = decodedPath.substring(i + marker.length).trim('/')
                if (rest.isNotEmpty()) {
                    return rest
                }
            }
        }
        val named = queryDisplayName(ctx.contentResolver, uri)
        if (named.contains('/')) {
            return named.replace('\\', '/').trim('/')
        }
        return null
    }

    private fun commonLocalDir(files: List<File>): File? {
        var dir = files[0].parentFile ?: return null
        for (f in files) {
            val p = f.parentFile ?: return null
            dir = commonAncestor(dir, p) ?: return null
        }
        return dir
    }

    private fun commonAncestor(a: File, b: File): File? {
        val aCanon = try {
            a.canonicalFile
        } catch (_: Exception) {
            a
        }
        val bPath = try {
            b.canonicalPath
        } catch (_: Exception) {
            b.absolutePath
        }
        var x = aCanon
        while (true) {
            val xp = x.absolutePath.trimEnd('/')
            if (bPath == xp || bPath.startsWith("$xp/")) {
                return x
            }
            x = x.parentFile ?: return null
        }
    }

    private fun isStorageRoot(dir: File): Boolean {
        val path = try {
            dir.canonicalPath.trimEnd('/')
        } catch (_: Exception) {
            dir.absolutePath.trimEnd('/')
        }
        if (path.isEmpty() || path == "/") {
            return true
        }
        val roots = ArrayList<String>()
        try {
            roots.add(Environment.getExternalStorageDirectory().canonicalPath.trimEnd('/'))
        } catch (_: Exception) {
            roots.add(Environment.getExternalStorageDirectory().absolutePath.trimEnd('/'))
        }
        roots.add("/storage/emulated/0")
        roots.add("/sdcard")
        return roots.any { it == path }
    }

    private fun commonFolderPrefix(rels: List<String>): String? {
        val parents = rels.map { raw ->
            val n = raw.replace('\\', '/').trim('/')
            val slash = n.lastIndexOf('/')
            if (slash <= 0) "" else n.substring(0, slash)
        }
        if (parents.any { it.isEmpty() }) {
            return null
        }
        val parts = parents.map { it.split('/').filter { s -> s.isNotEmpty() } }
        val first = parts[0]
        var n = 0
        while (n < first.size && parts.all { it.size > n && it[n] == first[n] }) {
            n++
        }
        if (n == 0) {
            return null
        }
        return first.subList(0, n).joinToString("/")
    }

    /** 扫描 SAF 文件夹，按相对路径入队。返回树名和加入个数。 */
    fun ingestTree(ctx: Context, treeUri: Uri): Pair<String, Int> {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        val local = guessLocalFile(ctx, treeUri)
        android.util.Log.i(
            "Textsend",
            "tree: uri=$treeUri local=${local?.absolutePath ?: "-"}"
        )
        var treeName = ""
        var added = 0
        if (local != null && local.isDirectory) {
            val disk = ingestFileDir(local)
            treeName = disk.first
            added = disk.second
            android.util.Log.i("Textsend", "tree: disk ${disk.first} +${disk.second}")
        }
        val saf = walkTree(ctx, treeUri)
        if (treeName.isBlank()) {
            treeName = saf.first
        }
        for ((uri, rel) in saf.second) {
            val item = ingest(ctx, uri)?.copy(treeName = treeName, relPath = rel) ?: continue
            if (add(item)) {
                added++
            }
        }
        android.util.Log.i("Textsend", "tree: saf ${saf.first} files=${saf.second.size} total=$added")
        return treeName.ifBlank { saf.first } to added
    }

    private fun walkTree(ctx: Context, treeUri: Uri): Pair<String, List<Pair<Uri, String>>> {
        val cr = ctx.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        var treeName = FileNames.sanitize(queryDisplayName(cr, rootUri))
        if (treeName.isBlank() || treeName.equals("file", true)) {
            treeName = "folder"
        }
        val out = ArrayList<Pair<Uri, String>>()
        walkDocs(cr, treeUri, treeId, "", treeName, out)
        return treeName to out
    }

    private fun walkDocs(
        cr: ContentResolver,
        treeUri: Uri,
        parentId: String,
        relPrefix: String,
        treeName: String,
        out: ArrayList<Pair<Uri, String>>
    ) {
        val children = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        } catch (_: Exception) {
            return
        }
        val rows = try {
            cr.query(children, null, null, null, null)
        } catch (_: Exception) {
            null
        }
        val seen = HashSet<String>()
        rows?.use { c ->
            val iId = col(c, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val iName = col(c, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val iMime = col(c, DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (iId >= 0) {
                while (c.moveToNext()) {
                    val id = c.getString(iId) ?: continue
                    val raw = if (iName >= 0 && !c.isNull(iName)) c.getString(iName) ?: "" else ""
                    if (skipEntry(raw)) {
                        continue
                    }
                    if (raw.isNotEmpty()) {
                        seen.add(raw)
                    }
                    val mime = if (iMime >= 0 && !c.isNull(iMime)) c.getString(iMime) else null
                    val dir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val parentSeg = if (relPrefix.isEmpty()) treeName else relPrefix.substringAfterLast('/')
                    val name = stripSmashedPrefix(raw, parentSeg)
                    val safeName = FileNames.sanitize(name.ifBlank { if (dir) "folder" else "file" })
                    seen.add(safeName)
                    val rel = if (relPrefix.isEmpty()) safeName else "$relPrefix/$safeName"
                    if (dir) {
                        walkDocs(cr, treeUri, id, rel, treeName, out)
                        continue
                    }
                val path = FileNames.sanitizeRelPath(rel) ?: continue
                val uri = try {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                } catch (_: Exception) {
                    continue
                }
                out.add(uri to path)
                }
            }
        }
        walkHiddenDocs(cr, treeUri, parentId, relPrefix, treeName, out, seen)
    }

    /**
     * 系统选文件夹（SAF）和部分机型的 listFiles 都不报以 `.` 开头的目录。
     * 按文档 ID / 磁盘路径去探，补上 `.logger` 这类真实存在的隐藏目录。
     */
    private val hiddenDirHints = arrayOf(
        ".logger", ".log", ".logs", ".logcat",
        ".nubia", ".nubia_log", ".nubialog"
    )

    private fun walkHiddenDocs(
        cr: ContentResolver,
        treeUri: Uri,
        parentId: String,
        relPrefix: String,
        treeName: String,
        out: ArrayList<Pair<Uri, String>>,
        seen: Set<String>
    ) {
        for (hint in hiddenDirHints) {
            if (skipEntry(hint) || seen.any { it.equals(hint, true) }) {
                continue
            }
            val childId = "$parentId/$hint"
            val uri = try {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
            } catch (_: Exception) {
                continue
            }
            val localChild = fileFromDocId(childId)
                ?: fileFromDocId(parentId)?.let { File(it, hint) }?.takeIf { it.exists() }
            if (!documentExists(cr, uri) && !childQueryHasRows(cr, treeUri, childId) && localChild == null) {
                continue
            }
            android.util.Log.i("Textsend", "tree: 补上隐藏目录 $hint id=$childId")
            val safeName = FileNames.sanitize(hint)
            val rel = if (relPrefix.isEmpty()) safeName else "$relPrefix/$safeName"
            val before = out.size
            walkDocs(cr, treeUri, childId, rel, treeName, out)
            if (out.size == before && localChild != null && localChild.isDirectory) {
                val treeRoot = try {
                    fileFromDocId(DocumentsContract.getTreeDocumentId(treeUri))
                } catch (_: Exception) {
                    null
                }
                if (treeRoot != null && treeRoot.isDirectory) {
                    val extra = ArrayList<Pair<File, String>>()
                    walkLocal(treeRoot, localChild, extra)
                    for ((f, path) in extra) {
                        out.add(Uri.fromFile(f) to path)
                    }
                }
            }
        }
    }

    private fun childQueryHasRows(cr: ContentResolver, treeUri: Uri, docId: String): Boolean {
        val kids = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        } catch (_: Exception) {
            return false
        }
        return try {
            cr.query(
                kids,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun documentExists(cr: ContentResolver, uri: Uri): Boolean {
        return try {
            cr.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
        } catch (_: Exception) {
            false
        }
    }

    /** listFiles 可能不报隐藏项；存在的 `.logger` 等仍要扫进去。 */
    private fun listDirFiles(dir: File): List<File> {
        val out = LinkedHashMap<String, File>()
        dir.listFiles()?.forEach { out[it.name] = it }
        for (hint in hiddenDirHints) {
            if (hint in out || skipEntry(hint)) {
                continue
            }
            val f = File(dir, hint)
            if (f.exists()) {
                android.util.Log.i("Textsend", "folder: 磁盘补上隐藏项 ${f.absolutePath}")
                out[hint] = f
            }
        }
        return out.values.toList()
    }

    /** 只跳过系统垃圾和本应用残留，`.logger` 这类隐藏目录要发、也要能看。 */
    fun skipEntry(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") {
            return true
        }
        val lower = name.lowercase()
        if (lower == ".ds_store" || lower == "thumbs.db" || lower == "desktop.ini") {
            return true
        }
        if (lower.startsWith(".textsend.")
            || lower.endsWith(".textsend.part")
            || lower.endsWith(".textsend.json")
            || lower.endsWith(".textsend.receiving")
        ) {
            return true
        }
        return false
    }

    private fun stripSmashedPrefix(raw: String, parent: String): String {
        if (raw.isEmpty() || parent.isEmpty() || !parent.contains('.')) {
            return raw
        }
        if (raw.startsWith("$parent/") || raw.startsWith("$parent\\")) {
            return raw.substring(parent.length + 1)
        }
        if (raw.startsWith(parent) && raw.length > parent.length) {
            val rest = raw.substring(parent.length)
            if (rest.isNotEmpty() && rest[0] != '/' && rest[0] != '\\' && rest.contains('.')) {
                return rest
            }
        }
        return raw
    }

    private fun col(c: android.database.Cursor, name: String): Int {
        return try {
            c.getColumnIndex(name)
        } catch (_: Exception) {
            -1
        }
    }

    private fun grantRead(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun asTreeUri(ctx: Context, uri: Uri): Uri? {
        if (isTreeUri(uri)) {
            return uri
        }
        if (!DocumentsContract.isDocumentUri(ctx, uri)) {
            return null
        }
        return try {
            val id = DocumentsContract.getDocumentId(uri)
            val auth = uri.authority ?: return null
            DocumentsContract.buildTreeDocumentUri(auth, id)
        } catch (_: Exception) {
            null
        }
    }

    private fun folderName(ctx: Context, uri: Uri): String {
        val n = FileNames.sanitize(queryDisplayName(ctx.contentResolver, uri))
        return if (n.isBlank() || n.equals("file", true)) "folder" else n
    }

    private fun queryDocMime(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )?.use { c ->
                if (!c.moveToFirst()) {
                    return null
                }
                val i = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (i < 0 || c.isNull(i)) null else c.getString(i)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessLocalFile(ctx: Context, uri: Uri): File? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val p = uri.path ?: return null
            val f = File(p)
            return if (f.exists()) f else null
        }
        val id = try {
            when {
                isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.isDocumentUri(ctx, uri) -> DocumentsContract.getDocumentId(uri)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        fileFromDocId(id)?.let { return it }
        queryColumn(ctx.contentResolver, uri, MediaStore.MediaColumns.DATA)?.let {
            val f = File(it)
            if (f.exists()) {
                return f
            }
        }
        return fileFromEncodedPath(uri.path) ?: fileFromEncodedPath(uri.toString())
    }

    private fun fileFromEncodedPath(raw: String?): File? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val decoded = Uri.decode(raw)
        for (marker in listOf("/storage/emulated/0/", "/sdcard/")) {
            val i = decoded.indexOf(marker)
            if (i >= 0) {
                val f = File(decoded.substring(i))
                if (f.exists()) {
                    return f
                }
            }
        }
        val roots = listOf("/external_files/", "/external_root/", "/root/")
        for (marker in roots) {
            val i = decoded.indexOf(marker)
            if (i < 0) {
                continue
            }
            val rest = decoded.substring(i + marker.length).trimStart('/')
            if (rest.isEmpty()) {
                continue
            }
            val f = File(Environment.getExternalStorageDirectory(), rest)
            if (f.exists()) {
                return f
            }
        }
        return null
    }

    private fun fileFromDocId(id: String?): File? {
        if (id.isNullOrBlank()) {
            return null
        }
        val decoded = Uri.decode(id)
        when {
            decoded.startsWith("primary:") -> {
                val rel = decoded.substringAfter("primary:")
                val f = File(Environment.getExternalStorageDirectory(), rel)
                if (f.exists()) {
                    return f
                }
            }
            decoded.startsWith("raw:") -> {
                val f = File(decoded.substring(4))
                if (f.exists()) {
                    return f
                }
            }
            decoded.startsWith("/") -> {
                val f = File(decoded)
                if (f.exists()) {
                    return f
                }
            }
        }
        return null
    }

    fun ingestLocalDir(root: File): Pair<String, Int> {
        if (!root.isDirectory) {
            return FileNames.sanitize(root.name.ifBlank { "folder" }) to 0
        }
        return withBatchNotify {
            ingestFileDir(root)
        }
    }

    fun isExternalRoot(dir: File): Boolean {
        return isStorageRoot(dir)
    }

    private fun ingestFileDir(root: File): Pair<String, Int> {
        val treeName = FileNames.sanitize(root.name.ifBlank { "folder" })
        val rootCanon = try {
            root.canonicalFile
        } catch (_: Exception) {
            root
        }
        val files = ArrayList<Pair<File, String>>()
        walkLocal(rootCanon, rootCanon, files)
        var added = 0
        for ((f, rel) in files) {
            if (f.length() <= 0 || f.length() > Protocol.FILE_SIZE_MAX) {
                continue
            }
            val item = Item(
                UUID.randomUUID().toString(),
                FileNames.sanitize(f.name),
                f.length(),
                Uri.fromFile(f),
                null,
                treeName,
                rel
            )
            if (add(item)) {
                added++
            }
        }
        return treeName to added
    }

    private fun walkLocal(root: File, dir: File, out: ArrayList<Pair<File, String>>) {
        val kids = listDirFiles(dir)
        if (kids.isEmpty()) {
            return
        }
        val rootPath = try {
            root.canonicalPath.trimEnd('/') + "/"
        } catch (_: Exception) {
            root.absolutePath.trimEnd('/') + "/"
        }
        for (f in kids) {
            if (skipEntry(f.name)) {
                continue
            }
            if (f.isDirectory) {
                walkLocal(root, f, out)
                continue
            }
            if (!f.isFile) {
                continue
            }
            val full = try {
                f.canonicalPath
            } catch (_: Exception) {
                f.absolutePath
            }
            if (!full.startsWith(rootPath)) {
                continue
            }
            if (looksSmashed(f.name)) {
                val parent = f.parentFile
                val parts = splitSmashedName(f.name)
                if (parent != null && parts.size >= 2) {
                    var expanded = false
                    for (part in parts) {
                        val sib = File(parent, part)
                        if (!sib.isFile || sib.length() <= 0) {
                            continue
                        }
                        val sibFull = try {
                            sib.canonicalPath
                        } catch (_: Exception) {
                            sib.absolutePath
                        }
                        if (!sibFull.startsWith(rootPath)) {
                            continue
                        }
                        val sibRel = FileNames.sanitizeRelPath(sibFull.substring(rootPath.length)) ?: continue
                        out.add(sib to sibRel)
                        expanded = true
                    }
                    if (expanded) {
                        android.util.Log.i("Textsend", "folder: 拆开粘名 ${f.name} → ${parts.joinToString()}")
                        continue
                    }
                }
                android.util.Log.i("Textsend", "folder: 磁盘上就是这个名字，照发 ${f.name}")
            }
            val rel = FileNames.sanitizeRelPath(full.substring(rootPath.length)) ?: continue
            out.add(f to rel)
        }
    }

    private fun ingestDocumentDir(ctx: Context, uri: Uri): Pair<String, Int> {
        val auth = uri.authority ?: return folderName(ctx, uri) to 0
        val parentId = try {
            if (isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        } catch (_: Exception) {
            return folderName(ctx, uri) to 0
        }
        val treeName = folderName(ctx, uri)
        val found = ArrayList<Pair<Uri, String>>()
        walkLoose(ctx.contentResolver, auth, parentId, "", treeName, found)
        var added = 0
        for ((child, rel) in found) {
            val item = ingest(ctx, child)?.copy(treeName = treeName, relPath = rel) ?: continue
            if (add(item)) {
                added++
            }
        }
        return treeName to added
    }

    private fun walkLoose(
        cr: ContentResolver,
        authority: String,
        parentId: String,
        relPrefix: String,
        treeName: String,
        out: ArrayList<Pair<Uri, String>>
    ) {
        val children = try {
            DocumentsContract.buildChildDocumentsUri(authority, parentId)
        } catch (_: Exception) {
            return
        }
        val rows = try {
            cr.query(children, null, null, null, null)
        } catch (_: Exception) {
            null
        } ?: return
        rows.use { c ->
            val iId = col(c, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val iName = col(c, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val iMime = col(c, DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (iId < 0) {
                return
            }
            while (c.moveToNext()) {
                val id = c.getString(iId) ?: continue
                val raw = if (iName >= 0 && !c.isNull(iName)) c.getString(iName) ?: "" else ""
                if (skipEntry(raw)) {
                    continue
                }
                val mime = if (iMime >= 0 && !c.isNull(iMime)) c.getString(iMime) else null
                val dir = isDirMime(mime)
                val parentSeg = if (relPrefix.isEmpty()) treeName else relPrefix.substringAfterLast('/')
                val name = stripSmashedPrefix(raw, parentSeg)
                val safeName = FileNames.sanitize(name.ifBlank { if (dir) "folder" else "file" })
                val rel = if (relPrefix.isEmpty()) safeName else "$relPrefix/$safeName"
                if (dir) {
                    walkLoose(cr, authority, id, rel, treeName, out)
                    continue
                }
                val path = FileNames.sanitizeRelPath(rel) ?: continue
                val child = try {
                    DocumentsContract.buildDocumentUri(authority, id)
                } catch (_: Exception) {
                    continue
                }
                out.add(child to path)
            }
        }
    }

    fun ingest(ctx: Context, uri: Uri): Item? {
        if (isFolderUri(ctx, uri)) {
            return null
        }
        val local = guessLocalFile(ctx, uri)
        if (local != null && local.isDirectory) {
            return null
        }
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        return try {
            resolve(ctx, uri)
        } catch (_: Exception) {
            null
        }
    }

    fun resolve(ctx: Context, uri: Uri): Item? {
        val cr = ctx.contentResolver
        var name = queryDisplayName(cr, uri)
        var size = querySize(cr, uri)
        if (size < 0) {
            size = afdSize(cr, uri)
        }
        name = FileNames.sanitize(ensureExt(cr, uri, name))
        val dest = if (size < 0 || size > CACHE_COPY_MAX) {
            null
        } else {
            copyToCache(ctx, uri, name)
        }
        if (dest == null) {
            if (size < 0 || size > Protocol.FILE_SIZE_MAX) {
                return null
            }
            return Item(UUID.randomUUID().toString(), name, size, uri, null)
        }
        val actual = dest.length()
        if (actual <= 0 || actual > Protocol.FILE_SIZE_MAX) {
            dest.delete()
            return null
        }
        return Item(UUID.randomUUID().toString(), name, actual, null, dest)
    }

    private fun queryDisplayName(cr: ContentResolver, uri: Uri): String {
        queryColumn(cr, uri, OpenableColumns.DISPLAY_NAME)?.let {
            val n = basename(it)
            if (isRealName(n)) {
                return n
            }
        }
        dumpNameColumns(cr, uri)?.let { return it }
        queryColumn(cr, uri, MediaStore.MediaColumns.DISPLAY_NAME)?.let {
            val n = basename(it)
            if (isRealName(n)) {
                return n
            }
        }
        nameFromDocumentId(cr, uri)?.let { return it }
        queryColumn(cr, uri, MediaStore.MediaColumns.DATA)?.let {
            val n = basename(it)
            if (isRealName(n)) {
                return n
            }
        }
        val decoded = Uri.decode(uri.lastPathSegment ?: "")
        val fromPath = basename(decoded.substringAfterLast(':'))
        if (isRealName(fromPath)) {
            return fromPath
        }
        return ""
    }

    private fun nameFromDocumentId(cr: ContentResolver, uri: Uri): String? {
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        if (docId.isNullOrBlank()) {
            return null
        }
        val decoded = Uri.decode(docId)
        if (decoded.contains('/') || decoded.contains(':')) {
            val afterColon = decoded.substringAfterLast(':')
            val fromPath = basename(afterColon)
            if (isRealName(fromPath)) {
                return fromPath
            }
            val id = afterColon.trim()
            val kind = decoded.substringBefore(':').lowercase()
            if (id.all { it.isDigit() }) {
                val collection = when {
                    "image" in kind -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" in kind -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" in kind -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                nameFromMediaId(cr, collection, id)?.let { return it }
                if ("image" in kind || "video" in kind) {
                    nameFromMediaId(cr, MediaStore.Files.getContentUri("external"), id)?.let { return it }
                }
            }
        }
        return null
    }

    private fun nameFromMediaId(cr: ContentResolver, collection: Uri, id: String): String? {
        return try {
            cr.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
                "${MediaStore.MediaColumns._ID}=?",
                arrayOf(id),
                null
            )?.use { c ->
                if (!c.moveToFirst()) {
                    return null
                }
                val ni = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (ni >= 0 && !c.isNull(ni)) {
                    val n = basename(c.getString(ni))
                    if (isRealName(n)) {
                        return n
                    }
                }
                val di = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (di >= 0 && !c.isNull(di)) {
                    val n = basename(c.getString(di))
                    if (isRealName(n)) {
                        return n
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isRealName(n: String): Boolean {
        if (n.isBlank() || n.equals("file", true)) {
            return false
        }
        if (n.all { it.isDigit() }) {
            return false
        }
        val lower = n.lowercase()
        if (lower.startsWith("image:") || lower.startsWith("video:") || lower.startsWith("audio:")
            || lower.startsWith("msf:")
        ) {
            return false
        }
        return true
    }

    private fun dumpNameColumns(cr: ContentResolver, uri: Uri): String? {
        return try {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) {
                    return null
                }
                for (col in c.columnNames) {
                    val lower = col.lowercase()
                    if ("name" !in lower && "title" !in lower && "data" != lower && "_data" != lower) {
                        continue
                    }
                    val i = c.getColumnIndex(col)
                    if (i < 0 || c.isNull(i)) {
                        continue
                    }
                    val n = basename(c.getString(i) ?: "")
                    if (isRealName(n)) {
                        return n
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryColumn(cr: ContentResolver, uri: Uri, column: String): String? {
        return try {
            cr.query(uri, arrayOf(column), null, null, null)?.use { c ->
                if (!c.moveToFirst()) {
                    return null
                }
                val i = c.getColumnIndex(column)
                if (i < 0 || c.isNull(i)) {
                    return null
                }
                val n = c.getString(i)
                if (n.isNullOrBlank()) null else n
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun basename(raw: String): String {
        var n = raw.replace('\\', '/').trim()
        val slash = n.lastIndexOf('/')
        if (slash >= 0) {
            n = n.substring(slash + 1)
        }
        return n.trim()
    }

    private fun querySize(cr: ContentResolver, uri: Uri): Long {
        try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) {
                        val n = c.getLong(i)
                        if (n >= 0) {
                            return n
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    private fun afdSize(cr: ContentResolver, uri: Uri): Long {
        return try {
            cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun ensureExt(cr: ContentResolver, uri: Uri, raw: String): String {
        var n = raw.trim()
        val mime = try {
            cr.getType(uri)
        } catch (_: Exception) {
            null
        }
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (n.isEmpty() || n.equals("file", true) || n.all { it.isDigit() }) {
            n = if (!ext.isNullOrBlank()) "file.$ext" else "file"
        } else if (!n.contains('.') && !ext.isNullOrBlank()) {
            n = "$n.$ext"
        }
        return n
    }

    private fun copyToCache(ctx: Context, uri: Uri, name: String): File? {
        val dir = File(ctx.cacheDir, "send")
        if (!dir.isDirectory && !dir.mkdirs()) {
            return null
        }
        val dest = File(dir, UUID.randomUUID().toString() + "-" + name)
        val tried = linkedSetOf(originalMediaUri(ctx, uri), uri)
        for (src in tried) {
            try {
                ctx.contentResolver.openInputStream(src)?.use { inn ->
                    FileOutputStream(dest).use { out -> inn.copyTo(out) }
                } ?: continue
                if (dest.length() > 0) {
                    return dest
                }
            } catch (_: Exception) {
            }
            dest.delete()
        }
        return null
    }

    /**
     * Android 10+ 默认会去掉照片里的地理位置；仅 MediaStore URI 才能 setRequireOriginal。
     * SAF / MediaDocumentsProvider 的 document URI 加上 ?requireOriginal=1 会在打开时抛 SecurityException。
     */
    private fun originalMediaUri(ctx: Context, uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < 29) {
            return uri
        }
        if (!isMediaStoreUri(uri)) {
            return uri
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return uri
        }
        return try {
            MediaStore.setRequireOriginal(uri)
        } catch (_: Exception) {
            uri
        }
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return false
        }
        val auth = uri.authority ?: return false
        return auth == MediaStore.AUTHORITY || auth == "media"
    }

    private fun same(a: Item, b: Item): Boolean {
        if (!a.treeName.isNullOrBlank() && a.treeName == b.treeName
            && !a.relPath.isNullOrBlank() && a.relPath == b.relPath
        ) {
            return true
        }
        if (a.uri != null && b.uri != null) {
            return a.uri == b.uri
        }
        if (a.cacheFile != null && b.cacheFile != null) {
            return a.cacheFile.absolutePath == b.cacheFile.absolutePath
        }
        return false
    }

    private fun notifyPending() {
        try {
            onPendingChanged?.invoke()
        } catch (_: Exception) {
        }
    }
}
