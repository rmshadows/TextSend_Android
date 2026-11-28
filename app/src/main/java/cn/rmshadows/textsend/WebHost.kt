package cn.rmshadows.textsend

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import protocol.AesGcm
import protocol.B64
import protocol.FileNames
import protocol.Hkdf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket

/**
 * 手机当服务端时的网页桥。A 明文、B 加密、C 证书，共用 54301，同时只开一档。
 * 不走 ts://。C 是 HTTPS，正文仍和 B 同一套 AES-GCM。
 * 手机发给浏览器的文字和文件走这里，浏览器上传到手机的文件也走这里。
 */
object WebHost {
    const val DEFAULT_PORT = 54301
    private const val INFO = "textsend-webbridge-v1"
    private const val MAX_PLAIN = 64 * 1024
    private const val MAX_SEALED = MAX_PLAIN + 4096
    private const val MAX_CLIENTS = 8
    private const val MAX_OUTBOUND = 20
    private const val MAX_OUT_ITEMS = 100
    private const val CHUNK = 1024 * 1024
    private const val MAX_FILE = 20L * 1024 * 1024 * 1024
    private const val IDLE_MS = 60L * 60L * 1000L
    private const val WARN =
        "消息以明文在局域网传输，同一网络中的其他人可能看到内容。请仅在可信网络使用；不要发送密码、验证码等敏感信息。"

    private val rng = SecureRandom()
    private val lock = Any()
    private val clients = CopyOnWriteArrayList<Sse>()
    private val outbound = ArrayList<ByteArray>()
    private val outbox = LinkedHashMap<String, OutItem>()
    private val running = AtomicBoolean(false)

    /** 浏览器连上或断开时，界面刷新在线人数。 */
    var onChanged: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null
    private var server: ServerSocket? = null
    private var pool = Executors.newCachedThreadPool { r ->
        Thread(r, "web-host").apply { isDaemon = true }
    }
    private var tick: ScheduledExecutorService? = null

    @Volatile private var way: Char = 'B'
    @Volatile private var boundPort = 0
    @Volatile private var sid: String? = null
    @Volatile private var token: String? = null
    private var ikm: ByteArray? = null
    private var aesKey: ByteArray? = null
    private var caPem: ByteArray? = null
    @Volatile private var touched = 0L

    fun isRunning(): Boolean = running.get() && server?.isClosed == false

    fun currentWay(): Char = way

    fun port(): Int = boundPort

    fun listenerCount(): Int = clients.size

    fun listenerHosts(): List<String> = clients.map { it.host }

    fun hasSession(): Boolean {
        synchronized(lock) {
            if (!isRunning()) {
                return false
            }
            return if (way == 'A') token != null else aesKey != null
        }
    }

    /** 断开一条网页长连接。下标对不上时按地址断开第一条。 */
    fun dropListener(index: Int, host: String) {
        val list = clients.toList()
        val hit = if (index in list.indices && list[index].host == host) {
            list[index]
        } else {
            list.firstOrNull { it.host == host }
        } ?: return
        hit.write(sse("bye", "kicked"))
        hit.close()
        clients.remove(hit)
        notifyChanged()
    }

    fun dropAllListeners() {
        dropClients("kicked")
        notifyChanged()
    }

    /** @return 1 已交给网页；0 会话无效；-1 超过 64KB */
    fun pushText(text: String): Int {
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        if (raw.size > MAX_PLAIN) {
            return -1
        }
        val o = JsonObject()
        o.addProperty("v", 1)
        o.addProperty("type", "text")
        o.addProperty("id", newId())
        o.addProperty("ts", System.currentTimeMillis())
        o.addProperty("body", text)
        return if (publish(o, remember = true)) 1 else 0
    }

    /**
     * 把本机文件交给网页收件列表。正文不进长连接，浏览器按 id 分片来拉。
     * @return 1 已通知；0 会话无效；-1 超过 20GB；-2 列表已满
     */
    fun pushFile(
        name: String,
        size: Long,
        tree: String?,
        rel: String?,
        open: () -> InputStream
    ): Int {
        if (size < 0 || size > MAX_FILE) {
            return -1
        }
        if (!hasSession()) {
            return 0
        }
        val shown = safeName(name)
        val path = safeRel(tree, rel)
        val item = OutItem(
            newId(),
            shown,
            path,
            size,
            kindOf(shown),
            System.currentTimeMillis(),
            open
        )
        synchronized(lock) {
            if (!hasSessionLocked()) {
                return 0
            }
            if (outbox.size >= MAX_OUT_ITEMS) {
                return -2
            }
            outbox[item.id] = item
        }
        if (!publish(item.event("file"), remember = false)) {
            synchronized(lock) { outbox.remove(item.id) }
            return 0
        }
        return 1
    }

    /** 失败时返回原因。已经以同一档在听时，只换端口会重开会话。 */
    fun start(context: Context, port: Int, nextWay: Char): String? {
        if (port !in 1..65535) {
            return "端口不对"
        }
        if (nextWay != 'A' && nextWay != 'B' && nextWay != 'C') {
            return "这一档还没接上"
        }
        stop()
        val ctx = context.applicationContext
        app = ctx
        WebInbox.attach(ctx)
        way = nextWay
        mint()
        return try {
            val ss = openServer(ctx, port, nextWay)
            server = ss
            boundPort = ss.localPort
            running.set(true)
            if (pool.isShutdown) {
                pool = Executors.newCachedThreadPool { r ->
                    Thread(r, "web-host").apply { isDaemon = true }
                }
            }
            Thread({
                while (running.get()) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handle(sock) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "web-host-accept").apply { isDaemon = true }.start()
            tick = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "web-host-idle").apply { isDaemon = true }
            }.also { it.scheduleWithFixedDelay({ ping() }, 15, 15, TimeUnit.SECONDS) }
            notifyChanged()
            null
        } catch (e: Exception) {
            stop()
            e.message ?: "启动失败"
        }
    }

    /** 根证书文件路径。C 档才有。 */
    fun caCertFile(): java.io.File? {
        if (way != 'C' || !isRunning()) {
            return null
        }
        val ctx = app ?: return null
        return try {
            LanCert.caCertificate(ctx).toFile()
        } catch (_: Exception) {
            null
        }
    }

    private fun openServer(ctx: Context, port: Int, nextWay: Char): ServerSocket {
        if (nextWay != 'C') {
            caPem = null
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port), 16)
            return ss
        }
        val material = LanCert.material(ctx)
        caPem = Files.readAllBytes(material.caCert)
        val factory = material.context.serverSocketFactory
        val ss = factory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 16)
        return ss
    }

    fun stop() {
        running.set(false)
        dropClients("stopped")
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        boundPort = 0
        tick?.shutdownNow()
        tick = null
        synchronized(lock) {
            sid = null
            token = null
            aesKey = null
            ikm?.fill(0)
            ikm = null
            outbound.clear()
            outbox.clear()
            caPem = null
        }
        WebInbox.release()
        notifyChanged()
    }

    fun pageUrl(host: String): String? {
        val id = sid ?: return null
        if (!isRunning()) {
            return null
        }
        var h = host.trim()
        if (h.isEmpty() || h == "手动指定...") {
            h = lanHost()
        }
        if (h.isEmpty()) {
            h = "127.0.0.1"
        }
        if (h.indexOf(':') >= 0 && !h.startsWith("[")) {
            h = "[$h]"
        }
        val scheme = if (way == 'C') "https" else "http"
        val base = "$scheme://$h:$boundPort/?s=$id"
        return if (way == 'A') {
            val t = token ?: return null
            "$base&t=$t&m=A"
        } else {
            val key = ikm ?: return null
            "$base#k=${B64.enc(key)}"
        }
    }

    private fun mint() {
        val rawSid = ByteArray(16)
        rng.nextBytes(rawSid)
        val id = B64.enc(rawSid)
        synchronized(lock) {
            sid = id
            touched = System.currentTimeMillis()
            if (way == 'A') {
                val raw = ByteArray(32)
                rng.nextBytes(raw)
                token = B64.enc(raw)
                aesKey = null
                ikm = null
            } else {
                val material = ByteArray(32)
                rng.nextBytes(material)
                ikm = material
                token = null
                aesKey = Hkdf.derive(material, id.toByteArray(StandardCharsets.UTF_8), INFO, 32)
            }
        }
    }

    private fun ping() {
        val expire = synchronized(lock) {
            isRunning() && clients.isEmpty() && (aesKey != null || token != null) &&
                System.currentTimeMillis() - touched >= IDLE_MS
        }
        if (expire) {
            synchronized(lock) {
                sid = null
                token = null
                aesKey = null
                ikm?.fill(0)
                ikm = null
                outbound.clear()
                outbox.clear()
            }
            WebInbox.release()
            dropClients("expired")
            notifyChanged()
            return
        }
        val ping = ": ping\n\n".toByteArray(StandardCharsets.UTF_8)
        for (c in clients) {
            if (!c.write(ping)) {
                clients.remove(c)
                c.close()
            }
        }
    }

    private fun dropClients(reason: String) {
        val bye = sse("bye", reason)
        for (c in clients) {
            c.write(bye)
            c.close()
        }
        clients.clear()
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 20_000
            val host = remoteHost(sock)
            if (SendPrefs.isWebBlacklisted(host)) {
                try {
                    writeText(sock.getOutputStream(), 403, "blocked")
                } catch (_: Exception) {
                }
                return
            }
            val input = sock.getInputStream()
            val header = readHeaders(input) ?: return
            val lines = header.split("\r\n")
            if (lines.isEmpty()) {
                return
            }
            val req = lines[0].split(" ")
            if (req.size < 2) {
                writeText(sock.getOutputStream(), 400, "bad")
                return
            }
            val method = req[0]
            val raw = req[1]
            val q = raw.indexOf('?')
            val path = if (q < 0) raw else raw.substring(0, q)
            val query = parseQuery(if (q < 0) "" else raw.substring(q + 1))
            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val p = lines[i].indexOf(':')
                if (p > 0) {
                    headers[lines[i].substring(0, p).trim().lowercase()] = lines[i].substring(p + 1).trim()
                }
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val cap = when (path) {
                "/api/file/chunk" -> WebInbox.CHUNK + 4096
                "/api/file/begin" -> 16 * 1024
                else -> MAX_SEALED
            }
            if (path.startsWith("/api/file/")) {
                sock.soTimeout = 120_000
            }
            if (len < 0 || len > cap) {
                writeText(sock.getOutputStream(), 413, "too big")
                return
            }
            val body = if (len == 0) ByteArray(0) else readN(input, len)
            route(sock, method, path, query, headers, body)
        } catch (_: Throwable) {
        } finally {
            if (sock.getInetAddress() != null) {
                try {
                    if (!sock.isClosed && sock.soTimeout != 0) {
                        // events 把超时清掉后自己关
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun remoteHost(sock: Socket): String {
        val addr = sock.inetAddress?.hostAddress ?: return ""
        return SendPrefs.normalizeHost(addr)
    }

    /** 踢掉已在网页黑名单里的长连接。 */
    fun dropBlacklisted() {
        val gone = clients.filter { SendPrefs.isWebBlacklisted(it.host) }
        for (c in gone) {
            c.write(sse("bye", "blocked"))
            c.close()
            clients.remove(c)
        }
        if (gone.isNotEmpty()) {
            notifyChanged()
        }
    }

    private fun route(
        sock: Socket,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        val out = sock.getOutputStream()
        when (path) {
            "/", "/index.html" -> staticFile(out, method, "webbridge/index.html", "text/html; charset=utf-8")
            "/bridge.js" -> staticFile(out, method, "webbridge/bridge.js", "text/javascript; charset=utf-8")
            "/ca.crt" -> caCert(out, method, query, headers)
            "/api/meta" -> meta(out, method, query, headers)
            "/api/send" -> send(out, method, query, headers, body)
            "/api/file/begin" -> fileBegin(out, method, query, headers, body)
            "/api/file/chunk" -> fileChunk(out, method, query, headers, body)
            "/api/file/finish" -> fileFinish(out, method, query, headers)
            "/api/file/cancel" -> fileCancel(out, method, query, headers)
            "/api/out/list" -> outList(out, method, query, headers)
            "/api/out/chunk" -> outChunk(out, method, query, headers)
            "/api/out/drop" -> outDrop(out, method, query, headers)
            "/api/out/clear" -> outClear(out, method, query, headers)
            "/api/events" -> events(sock, method, query, headers)
            else -> writeText(out, 404, "not found")
        }
        if (path != "/api/events") {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun staticFile(out: OutputStream, method: String, name: String, type: String) {
        if (method != "GET" && method != "HEAD") {
            writeText(out, 405, "method")
            return
        }
        val ctx = app
        val bytes = if (ctx == null) null else ctx.assets.open(name).use { it.readBytes() }
        if (bytes == null) {
            writeText(out, 500, "missing page")
            return
        }
        writeBytes(out, 200, "OK", type, if (method == "HEAD") null else bytes, headOnly = method == "HEAD", length = bytes.size, cache = "no-cache")
    }

    private fun meta(out: OutputStream, method: String, query: Map<String, String>, headers: Map<String, String>) {
        if (method != "GET") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        val ver = appVersion()
        val json = when (way) {
            'A' -> "{\"v\":1,\"mode\":\"A\",\"encrypt\":false,\"pageVersion\":\"1\",\"app\":\"$ver\",\"host\":\"手机\",\"warning\":\"${WARN.replace("\"", "\\\"")}\"}"
            'C' -> "{\"v\":1,\"mode\":\"C\",\"encrypt\":true,\"pageVersion\":\"1\",\"app\":\"$ver\",\"host\":\"手机\"}"
            else -> "{\"v\":1,\"mode\":\"B\",\"encrypt\":true,\"pageVersion\":\"1\",\"app\":\"$ver\",\"host\":\"手机\"}"
        }
        writeBytes(out, 200, "OK", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
    }

    /** 只下发根证书。私钥留在手机上。 */
    private fun caCert(
        out: OutputStream,
        method: String,
        query: Map<String, String>,
        headers: Map<String, String>
    ) {
        if (method != "GET" && method != "HEAD") {
            writeText(out, 405, "method")
            return
        }
        if (way != 'C' || !authOk(query, headers)) {
            writeText(out, 404, "not found")
            return
        }
        val body = caPem
        if (body == null) {
            writeText(out, 500, "error")
            return
        }
        writeBytes(
            out,
            200,
            "OK",
            "application/x-x509-ca-cert",
            if (method == "HEAD") null else body,
            headOnly = method == "HEAD",
            length = body.size,
            disposition = "attachment; filename=\"textsend-ca.crt\""
        )
    }

    private fun outList(out: OutputStream, method: String, query: Map<String, String>, headers: Map<String, String>) {
        if (method != "GET") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        val json = listJson()
        val payload = try {
            seal(json)
        } catch (_: Exception) {
            writeText(out, 500, "seal")
            return
        }
        writeBytes(out, 200, "OK", "application/octet-stream", payload)
    }

    private fun send(
        out: OutputStream,
        method: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        if (method != "POST") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        if (body.isEmpty() || body.size > MAX_SEALED) {
            writeText(out, if (body.size > MAX_SEALED) 413 else 400, "body")
            return
        }
        val plain = try {
            if (way == 'A') {
                body
            } else {
                val key = aesKey ?: throw IllegalStateException("no key")
                AesGcm.open(key, body)
            }
        } catch (_: Exception) {
            writeText(out, 400, "seal")
            return
        }
        if (plain.size > MAX_PLAIN + 1024) {
            writeText(out, 413, "too big")
            return
        }
        val text = try {
            val obj = JsonParser.parseString(String(plain, StandardCharsets.UTF_8)).asJsonObject
            val ver = obj.get("v")
            val type = obj.get("type")
            val raw = obj.get("body")
            if (ver == null || ver.asInt != 1 || type == null || type.asString != "text" || raw == null || !raw.isJsonPrimitive) {
                writeText(out, 400, "type")
                return
            }
            raw.asString
        } catch (_: Exception) {
            writeText(out, 400, "json")
            return
        }
        if (text.isEmpty() || text.toByteArray(StandardCharsets.UTF_8).size > MAX_PLAIN) {
            writeText(out, if (text.isEmpty()) 400 else 413, "text")
            return
        }
        synchronized(lock) { touched = System.currentTimeMillis() }
        deliver(text)
        writeBytes(out, 204, "No Content", null, null, length = 0)
    }

    private fun fileBegin(
        out: OutputStream,
        method: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        if (method != "POST") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        if (body.isEmpty()) {
            writeText(out, 400, "body")
            return
        }
        val plain = openUpload(out, body, 16 * 1024) ?: return
        val id: String
        val name: String
        val rel: String
        val size: Long
        try {
            val obj = JsonParser.parseString(String(plain, StandardCharsets.UTF_8)).asJsonObject
            id = if (obj.has("id")) obj.get("id").asString else ""
            name = if (obj.has("name")) obj.get("name").asString else ""
            rel = if (obj.has("rel")) obj.get("rel").asString else ""
            size = if (obj.has("size")) obj.get("size").asLong else -1
        } catch (_: Exception) {
            writeText(out, 400, "json")
            return
        }
        val json = try {
            WebInbox.begin(id, name, rel, size)
        } catch (e: IOException) {
            val m = e.message ?: "begin"
            if (m == "disk") noteDisk()
            writeText(out, if (m == "size") 413 else 400, m)
            return
        }
        synchronized(lock) { touched = System.currentTimeMillis() }
        writeBytes(out, 200, "OK", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun fileChunk(
        out: OutputStream,
        method: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        if (method != "POST") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        val id = query["id"]
        val index = query["i"]?.toIntOrNull()
        if (index == null) {
            writeText(out, 400, "index")
            return
        }
        val plain = openUpload(out, body, WebInbox.CHUNK) ?: return
        try {
            WebInbox.chunk(id, index, plain)
        } catch (e: IOException) {
            val m = e.message ?: "chunk"
            if (m == "disk") noteDisk()
            val code = when (m) {
                "cancelled", "order" -> 409
                "size" -> 413
                "missing" -> 404
                else -> 400
            }
            writeText(out, code, m)
            return
        }
        synchronized(lock) { touched = System.currentTimeMillis() }
        writeBytes(out, 204, "No Content", null, null, length = 0)
    }

    private fun fileFinish(
        out: OutputStream,
        method: String,
        query: Map<String, String>,
        headers: Map<String, String>
    ) {
        if (method != "POST") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        val saved = try {
            WebInbox.finish(query["id"])
        } catch (e: IOException) {
            val m = e.message ?: "finish"
            if (m == "disk") noteDisk()
            val code = when (m) {
                "missing" -> 404
                "cancelled", "incomplete" -> 409
                else -> 400
            }
            writeText(out, code, m)
            return
        }
        synchronized(lock) { touched = System.currentTimeMillis() }
        noteSaved(saved.name, saved.scanPath)
        writeBytes(out, 204, "No Content", null, null, length = 0)
    }

    private fun fileCancel(
        out: OutputStream,
        method: String,
        query: Map<String, String>,
        headers: Map<String, String>
    ) {
        if (method != "POST") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        WebInbox.pause(query["id"])
        synchronized(lock) { touched = System.currentTimeMillis() }
        writeBytes(out, 204, "No Content", null, null, length = 0)
    }

    /** A 档正文就是原文。B 档先解开。超长在这里拒绝，调用方拿到 null 时响应已经写过。 */
    private fun openUpload(out: OutputStream, body: ByteArray, maxPlain: Int): ByteArray? {
        if (body.isEmpty()) {
            writeText(out, 400, "body")
            return null
        }
        val plain = try {
            if (way == 'A') {
                body
            } else {
                val key = aesKey ?: throw IllegalStateException("no key")
                AesGcm.open(key, body)
            }
        } catch (_: Exception) {
            writeText(out, 400, "seal")
            return null
        }
        if (plain.size > maxPlain) {
            writeText(out, 413, "size")
            return null
        }
        return plain
    }

    private fun noteSaved(name: String, scanPath: String?) {
        val ctx = app ?: return
        val kind = if (FileNames.isImageName(name)) "图片" else "文件"
        main.post {
            Toast.makeText(ctx, "网页发来$kind $name，已存到下载/TextSend", Toast.LENGTH_SHORT).show()
            if (scanPath != null) {
                MediaScannerConnection.scanFile(ctx, arrayOf(scanPath), null, null)
            }
        }
    }

    private fun noteDisk() {
        val ctx = app ?: return
        main.post {
            Toast.makeText(ctx, "存不到下载/TextSend", Toast.LENGTH_LONG).show()
        }
    }

    private fun events(sock: Socket, method: String, query: Map<String, String>, headers: Map<String, String>) {
        if (method != "GET") {
            writeText(sock.getOutputStream(), 405, "method")
            sock.close()
            return
        }
        if (!authOk(query, headers)) {
            writeText(sock.getOutputStream(), 401, "session")
            sock.close()
            return
        }
        if (clients.size >= MAX_CLIENTS) {
            writeText(sock.getOutputStream(), 429, "busy")
            sock.close()
            return
        }
        sock.soTimeout = 0
        val out = sock.getOutputStream()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\nConnection: keep-alive\r\n\r\nretry: 2000\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
        val host = remoteHost(sock).ifBlank { "未知" }
        val sse = Sse(sock, out, host)
        val backlog = synchronized(lock) {
            touched = System.currentTimeMillis()
            clients.add(sse)
            ArrayList(outbound)
        }
        for (ev in backlog) {
            if (!sse.write(ev)) {
                clients.remove(sse)
                sse.close()
                notifyChanged()
                return
            }
        }
        notifyChanged()
    }

    private fun outChunk(out: OutputStream, method: String, query: Map<String, String>, headers: Map<String, String>) {
        if (method != "GET") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        val index = query["i"]?.toIntOrNull()
        if (index == null) {
            writeText(out, 400, "index")
            return
        }
        val plain = try {
            readChunk(query["id"], index)
        } catch (e: IOException) {
            val m = e.message ?: ""
            writeText(out, if (m == "missing") 404 else 400, if (m.isEmpty()) "chunk" else m)
            return
        }
        val payload = try {
            seal(plain)
        } catch (_: Exception) {
            writeText(out, 500, "seal")
            return
        }
        synchronized(lock) { touched = System.currentTimeMillis() }
        writeBytes(out, 200, "OK", "application/octet-stream", payload)
    }

    private fun outDrop(out: OutputStream, method: String, query: Map<String, String>, headers: Map<String, String>) {
        if (method != "POST") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        val id = query["id"]
        val removed = synchronized(lock) { if (id == null) null else outbox.remove(id) }
        if (removed != null) {
            val o = JsonObject()
            o.addProperty("v", 1)
            o.addProperty("type", "file-gone")
            o.addProperty("id", id)
            publish(o, remember = false)
        }
        synchronized(lock) { touched = System.currentTimeMillis() }
        writeBytes(out, 204, "No Content", null, null, length = 0)
    }

    private fun outClear(out: OutputStream, method: String, query: Map<String, String>, headers: Map<String, String>) {
        if (method != "POST") {
            writeText(out, 405, "method")
            return
        }
        if (!authOk(query, headers)) {
            writeText(out, 401, "session")
            return
        }
        synchronized(lock) {
            outbox.clear()
            touched = System.currentTimeMillis()
        }
        val o = JsonObject()
        o.addProperty("v", 1)
        o.addProperty("type", "file-clear")
        publish(o, remember = false)
        writeBytes(out, 204, "No Content", null, null, length = 0)
    }

    private fun deliver(text: String) {
        val ctx = app ?: return
        main.post {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("textsend", text))
            Toast.makeText(ctx, "网页发来文字，已放进剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun authOk(query: Map<String, String>, headers: Map<String, String>): Boolean {
        val id = sid ?: return false
        if (!same(id, query["s"])) {
            return false
        }
        if (way == 'A') {
            val got = query["t"] ?: bearer(headers["authorization"])
            return same(token, got)
        }
        return aesKey != null
    }

    private fun bearer(header: String?): String? {
        if (header == null) {
            return null
        }
        val p = "Bearer "
        return if (header.startsWith(p)) header.substring(p.length).trim() else null
    }

    private fun same(expect: String?, got: String?): Boolean {
        if (expect == null || got == null) {
            return false
        }
        return MessageDigest.isEqual(
            expect.toByteArray(StandardCharsets.UTF_8),
            got.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun appVersion(): String {
        val ctx = app ?: return ""
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun lanHost(): String {
        try {
            val nics = NetworkInterface.getNetworkInterfaces() ?: return ""
            for (nic in nics) {
                if (!nic.isUp || nic.isLoopback) {
                    continue
                }
                for (addr in nic.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ""
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) {
            return emptyMap()
        }
        val out = HashMap<String, String>()
        for (part in raw.split("&")) {
            val i = part.indexOf('=')
            // decode(String, Charset) 要 API 33。这台系统只有 decode(String, String)。
            val k = URLDecoder.decode(if (i < 0) part else part.substring(0, i), "UTF-8")
            val v = URLDecoder.decode(if (i < 0) "" else part.substring(i + 1), "UTF-8")
            out[k] = v
        }
        return out
    }

    private fun readHeaders(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() < 8192) {
            val b = input.read()
            if (b < 0) {
                return null
            }
            buf.write(b)
            val n = buf.size()
            if (n >= 4) {
                val a = buf.toByteArray()
                if (a[n - 4] == 13.toByte() && a[n - 3] == 10.toByte() && a[n - 2] == 13.toByte() && a[n - 1] == 10.toByte()) {
                    return String(a, StandardCharsets.ISO_8859_1)
                }
            }
        }
        return null
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) {
                break
            }
            off += r
        }
        return if (off == n) out else out.copyOf(off)
    }

    private fun writeText(out: OutputStream, code: Int, body: String) {
        writeBytes(out, code, reason(code), "text/plain; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeBytes(
        out: OutputStream,
        code: Int,
        reason: String,
        type: String?,
        body: ByteArray?,
        headOnly: Boolean = false,
        length: Int = body?.size ?: 0,
        cache: String = "no-store",
        disposition: String? = null
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        if (type != null) {
            sb.append("Content-Type: ").append(type).append("\r\n")
        }
        if (disposition != null) {
            sb.append("Content-Disposition: ").append(disposition).append("\r\n")
        }
        sb.append("Cache-Control: ").append(cache).append("\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (!headOnly && body != null) {
            out.write(body)
        }
        out.flush()
    }

    private fun reason(code: Int): String {
        return when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            else -> "Error"
        }
    }

    private fun sse(event: String, data: String): ByteArray {
        return "event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8)
    }

    private fun notifyChanged() {
        main.post { onChanged?.invoke() }
    }

    private fun hasSessionLocked(): Boolean {
        return if (way == 'A') token != null else aesKey != null
    }

    private fun publish(o: JsonObject, remember: Boolean): Boolean {
        val plain = o.toString().toByteArray(StandardCharsets.UTF_8)
        val frame: ByteArray
        val copy: List<Sse>
        synchronized(lock) {
            if (!hasSessionLocked()) {
                return false
            }
            touched = System.currentTimeMillis()
            val key = aesKey
            val data = if (way == 'A') {
                o.toString()
            } else if (key != null) {
                Base64.getEncoder().encodeToString(AesGcm.seal(key, plain))
            } else {
                return false
            }
            frame = sse("msg", data)
            if (remember) {
                outbound.add(frame)
                while (outbound.size > MAX_OUTBOUND) {
                    outbound.removeAt(0)
                }
            }
            copy = clients.toList()
        }
        for (c in copy) {
            if (!c.write(frame)) {
                clients.remove(c)
                c.close()
            }
        }
        return true
    }

    private fun listJson(): ByteArray {
        val o = JsonObject()
        o.addProperty("v", 1)
        val arr = JsonArray()
        synchronized(lock) {
            for (item in outbox.values) {
                arr.add(item.event("file"))
            }
        }
        o.add("items", arr)
        return o.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun seal(plain: ByteArray): ByteArray {
        if (way == 'A') {
            return plain
        }
        val key = aesKey ?: throw IllegalStateException("no key")
        return AesGcm.seal(key, plain)
    }

    private fun readChunk(id: String?, index: Int): ByteArray {
        if (id == null || !id.matches(Regex("[0-9a-f]{32}"))) {
            throw IOException("missing")
        }
        val item = synchronized(lock) { outbox[id] } ?: throw IOException("missing")
        if (index < 0) {
            throw IOException("range")
        }
        val off = index.toLong() * CHUNK
        if (off >= item.size) {
            throw IOException("range")
        }
        val n = minOf(CHUNK.toLong(), item.size - off).toInt()
        val input = try {
            item.open()
        } catch (_: Exception) {
            throw IOException("missing")
        }
        input.use {
            var left = off
            while (left > 0) {
                val skipped = it.skip(left)
                if (skipped > 0) {
                    left -= skipped
                    continue
                }
                if (it.read() < 0) {
                    throw IOException("changed")
                }
                left -= 1
            }
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = it.read(buf, got, n - got)
                if (r < 0) {
                    throw IOException("changed")
                }
                got += r
            }
            return buf
        }
    }

    private fun newId(): String {
        val raw = ByteArray(16)
        rng.nextBytes(raw)
        val sb = StringBuilder(32)
        for (b in raw) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }

    private fun safeName(raw: String): String {
        val cut = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = cut.filter { it.code >= 32 && it != '/' && it != '\\' }.take(180)
        return cleaned.ifBlank { "file" }
    }

    private fun safeRel(tree: String?, rel: String?): String {
        fun parts(raw: String) = raw.split('/', '\\').filter { it.isNotBlank() && it != "." && it != ".." }
        val all = ArrayList<String>()
        if (!tree.isNullOrBlank()) {
            all.addAll(parts(tree))
        }
        if (!rel.isNullOrBlank()) {
            all.addAll(parts(rel))
        }
        return all.joinToString("/")
    }

    private fun kindOf(name: String): String {
        val n = name.lowercase()
        val image = n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".heic") ||
            n.endsWith(".heif") || n.endsWith(".bmp")
        return if (image) "image" else "file"
    }

    private class OutItem(
        val id: String,
        val name: String,
        val rel: String,
        val size: Long,
        val kind: String,
        val ts: Long,
        val open: () -> InputStream
    ) {
        fun event(type: String): JsonObject {
            val o = JsonObject()
            o.addProperty("v", 1)
            o.addProperty("type", type)
            o.addProperty("id", id)
            o.addProperty("name", name)
            o.addProperty("rel", rel)
            o.addProperty("size", size)
            o.addProperty("kind", kind)
            o.addProperty("ts", ts)
            return o
        }
    }

    private class Sse(val sock: Socket, val out: OutputStream, val host: String) {
        private val writeLock = Any()

        fun write(b: ByteArray): Boolean {
            synchronized(writeLock) {
                return try {
                    out.write(b)
                    out.flush()
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        fun close() {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }
}
