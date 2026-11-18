package cn.rmshadows.textsend

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import protocol.TsClient
import protocol.TsServer
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 局域网 UDP 探测。默认关。
 * 包格式（三端一致）：TSD1 | version=1 | tcpPort uint16 BE | nameLen | name | 8 字节实例号。
 * 只广播正在监听的 TCP 端口和设备名，不带 PIN / 密钥。
 * 探测口默认跟随连接端口（协议仍是 UDP）；关掉跟随后可单独改。绑不上时在快照里带占用提示。
 * 「有连接时关闭」打开后，已经有会话就停广播、停监听。
 */
object LanDiscover {
    const val DEFAULT_PORT = 54300
    private const val PREF = "textsend_prefs"
    private const val KEY_ENABLED = "lan_discover"
    private const val KEY_PAUSE = "lan_discover_pause"
    private const val KEY_PORT = "lan_discover_port"
    private const val KEY_PORT_FOLLOW = "lan_discover_port_follow"
    private const val KEY_CONNECT_PORT = "connect_port"
    private const val KEY_AUTO = "lan_discover_auto"
    private const val KEY_ANNOUNCE = "lan_discover_announce"
    private const val MANUAL_PROBE_MS = 8_000L
    private const val MAGIC = "TSD1"
    private const val VERSION: Byte = 1
    private const val NAME_MAX = 48
    private const val INTERVAL_MS = 2000L
    private const val PEER_TTL_MS = 7000L

    data class Peer(
        val host: String,
        val tcpPort: Int,
        val name: String,
        /** 实例号 hex，同机多网卡只留一条 */
        val idKey: String = ""
    )

    data class Snapshot(
        val enabled: Boolean = false,
        val paused: Boolean = false,
        val peers: List<Peer> = emptyList(),
        val bindError: String = "",
        val udpPort: Int = DEFAULT_PORT,
        /** true=持续探测；false=要点「探测」才扫一轮 */
        val auto: Boolean = true,
        val probing: Boolean = false
    )

    private lateinit var app: Application
    private val instanceId = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
    private val generation = AtomicInteger()
    private val probeUntil = java.util.concurrent.atomic.AtomicLong()
    private val peers = LinkedHashMap<String, PeerSeen>()
    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()

    @Volatile
    private var socket: DatagramSocket? = null
    @Volatile
    private var boundPort = -1
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile
    private var authRequestHandler: ((LanAuth.Request) -> Unit)? = null

    private data class PeerSeen(val peer: Peer, val seenAt: Long)

    fun init(application: Application) {
        app = application
        if (isEnabled()) {
            publish(true, false, emptyList(), "", getPort())
            startLoop()
        }
    }

    fun setAuthRequestHandler(handler: ((LanAuth.Request) -> Unit)?) {
        authRequestHandler = handler
    }

    fun deviceNamePublic(): String = deviceName()

    fun sendAuthPacket(host: String, port: Int, buf: ByteArray): Boolean {
        val sock = socket
        if (sock == null || sock.isClosed || host.isBlank() || port !in 1..65535) {
            return false
        }
        return try {
            sock.send(DatagramPacket(buf, buf.size, InetAddress.getByName(host), port))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 客户端请求临时授权；探测未开或 socket 未就绪时抛错。 */
    fun requestAuth(host: String, udpPort: Int = getPort()): LanAuth.Grant {
        val sock = socket
        if (sock == null || sock.isClosed) {
            throw IllegalStateException("请先打开局域网探测")
        }
        return LanAuth.requestAuth(sock, host, udpPort, deviceName(), broadcastTargets())
    }

    /** 授权走 TCP 监听口。手机发往电脑的 UDP 经常进不去。 */
    fun requestAuthTcp(host: String, tcpPort: Int): LanAuth.Grant {
        return LanAuth.requestAuthTcp(host, tcpPort, deviceName())
    }

    fun isEnabled(): Boolean {
        if (!::app.isInitialized) {
            return false
        }
        return prefs().getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(on: Boolean) {
        if (!::app.isInitialized) {
            return
        }
        prefs().edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) {
            publish(true, false, emptyList(), "", getPort())
            startLoop()
        } else {
            generation.incrementAndGet()
            probeUntil.set(0)
            closeSocket()
            LanAuthGate.clearPending(true)
            publish(false, false, emptyList(), "", getPort())
        }
    }

    /** 默认自动。手动时只有点「探测」才扫一轮。 */
    fun isAuto(): Boolean {
        if (!::app.isInitialized) {
            return true
        }
        return prefs().getBoolean(KEY_AUTO, true)
    }

    fun setAuto(on: Boolean) {
        if (!::app.isInitialized) {
            return
        }
        prefs().edit().putBoolean(KEY_AUTO, on).apply()
        if (!isEnabled()) {
            return
        }
        probeUntil.set(0)
        synchronized(peers) { peers.clear() }
        if (!isEnabled()) {
            return
        }
        if (!on) {
            generation.incrementAndGet()
            closeSocket()
            publish(true, false, emptyList(), "", getPort())
            startLoop()
        } else {
            startLoop()
        }
    }

    /** 默认开。关掉后不主动广播，只在收到客户端探测时回复。 */
    fun isAnnounce(): Boolean {
        if (!::app.isInitialized) {
            return true
        }
        return prefs().getBoolean(KEY_ANNOUNCE, true)
    }

    fun setAnnounce(on: Boolean) {
        if (!::app.isInitialized) {
            return
        }
        prefs().edit().putBoolean(KEY_ANNOUNCE, on).apply()
    }

    /** 手动模式扫一轮（约 8 秒）。监听一直开着，方便随后发授权。 */
    fun probeNow() {
        if (!::app.isInitialized || !isEnabled() || isAuto()) {
            return
        }
        synchronized(peers) { peers.clear() }
        probeUntil.set(System.currentTimeMillis() + MANUAL_PROBE_MS)
        publish(true, false, emptyList(), "", getPort())
    }

    private fun collectingPeers(): Boolean {
        return isAuto() || System.currentTimeMillis() < probeUntil.get()
    }

    fun isPauseWhenConnected(): Boolean {
        if (!::app.isInitialized) {
            return false
        }
        return prefs().getBoolean(KEY_PAUSE, false)
    }

    fun setPauseWhenConnected(on: Boolean) {
        if (!::app.isInitialized) {
            return
        }
        prefs().edit().putBoolean(KEY_PAUSE, on).apply()
    }

    /** 默认开：探测 UDP 口等于连接端口（服务端监听口 / 客户端填写的对端口）。 */
    fun isPortFollow(): Boolean {
        if (!::app.isInitialized) {
            return true
        }
        return prefs().getBoolean(KEY_PORT_FOLLOW, true)
    }

    fun setPortFollow(on: Boolean) {
        if (!::app.isInitialized) {
            return
        }
        if (isPortFollow() == on) {
            return
        }
        prefs().edit().putBoolean(KEY_PORT_FOLLOW, on).apply()
        if (on) {
            prefs().edit().putInt(KEY_PORT, getConnectPort()).apply()
        }
        if (isEnabled()) {
            startLoop()
        } else {
            publish(false, false, emptyList(), "", getPort())
        }
    }

    /** 主界面连接端口：服务端监听口或客户端填写的对端口。 */
    fun getConnectPort(): Int {
        if (!::app.isInitialized) {
            return DEFAULT_PORT
        }
        val live = TsServer.listenPortCurrent()
        if (live in 1..65535) {
            return live
        }
        val port = prefs().getInt(KEY_CONNECT_PORT, DEFAULT_PORT)
        return if (port in 1..65535) port else DEFAULT_PORT
    }

    fun setConnectPort(port: Int) {
        if (!::app.isInitialized || port !in 1..65535) {
            return
        }
        val prev = prefs().getInt(KEY_CONNECT_PORT, DEFAULT_PORT)
        if (prev != port) {
            prefs().edit().putInt(KEY_CONNECT_PORT, port).apply()
        }
        if (isPortFollow()) {
            prefs().edit().putInt(KEY_PORT, port).apply()
            if (isEnabled() && prev != port) {
                startLoop()
            } else if (!isEnabled()) {
                publish(false, false, emptyList(), "", port)
            }
        }
    }

    fun getPort(): Int {
        if (!::app.isInitialized) {
            return DEFAULT_PORT
        }
        if (isPortFollow()) {
            return getConnectPort()
        }
        var port = prefs().getInt(KEY_PORT, DEFAULT_PORT)
        if (port == 54301) {
            port = DEFAULT_PORT
            prefs().edit().putInt(KEY_PORT, port).apply()
        }
        return if (port in 1..65535) port else DEFAULT_PORT
    }

    fun setPort(port: Int): Boolean {
        if (!::app.isInitialized || port !in 1..65535) {
            return false
        }
        if (isPortFollow()) {
            // 跟随模式下改探测口无效；实际口始终等于连接口
            return true
        }
        if (getPort() == port) {
            return true
        }
        prefs().edit().putInt(KEY_PORT, port).apply()
        if (isEnabled()) {
            startLoop()
        } else {
            publish(false, false, emptyList(), "", port)
        }
        return true
    }

    private fun startLoop() {
        val gen = generation.incrementAndGet()
        closeSocket()
        synchronized(peers) { peers.clear() }
        publish(true, false, emptyList(), "", getPort())
        thread(name = "ts-lan", isDaemon = true) {
            loop(gen)
        }
    }

    private fun loop(gen: Int) {
        var nextSend = 0L
        var nextProbe = 0L
        var lastBindTip = ""
        while (generation.get() == gen && isEnabled()) {
            val udpPort = getPort()
            val paused = isPauseWhenConnected() && hasLink()
            if (paused) {
                synchronized(peers) { peers.clear() }
                publish(true, true, emptyList(), "", udpPort)
                lastBindTip = ""
                try {
                    receiveOne(ensureSocket(udpPort))
                } catch (e: Exception) {
                    closeSocket()
                    val tip = bindTip(udpPort, e)
                    if (tip != lastBindTip) {
                        lastBindTip = tip
                        publish(true, true, emptyList(), tip, udpPort)
                    }
                    if (!sleep(1500)) {
                        break
                    }
                }
                continue
            }
            try {
                val sock = ensureSocket(udpPort)
                lastBindTip = ""
                val now = System.currentTimeMillis()
                val port = TsServer.listenPortCurrent()
                if (isAnnounce() && port in 1..65535 && now >= nextSend) {
                    sendBeacon(sock, port, udpPort)
                    nextSend = now + INTERVAL_MS
                }
                if (now >= nextProbe) {
                    sendProbe(sock, udpPort)
                    nextProbe = now + INTERVAL_MS
                }
                receiveOne(sock)
                prune(now)
                publish(true, false, currentPeers(), "", udpPort)
            } catch (e: Exception) {
                closeSocket()
                val tip = bindTip(udpPort, e)
                if (tip != lastBindTip) {
                    lastBindTip = tip
                    publish(true, false, emptyList(), tip, udpPort)
                }
                if (!sleep(1500)) {
                    break
                }
            }
        }
        closeSocket()
        synchronized(peers) { peers.clear() }
        if (generation.get() == gen) {
            publish(isEnabled(), false, emptyList(), "", getPort())
        }
    }

    private fun bindTip(udpPort: Int, e: Exception): String {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is BindException || cur.message?.contains("EADDRINUSE", true) == true
                || cur.message?.contains("Address already in use", true) == true
            ) {
                return "探测端口 $udpPort 已被占用"
            }
            cur = cur.cause
        }
        return "探测端口 $udpPort 打不开"
    }

    private fun hasLink(): Boolean {
        if (TsServer.aliveCountCurrent() > 0) {
            return true
        }
        val peer = TsClient.peer
        return peer != null && peer.isAlive()
    }

    private fun ensureSocket(udpPort: Int): DatagramSocket {
        val existing = socket
        if (existing != null && !existing.isClosed && boundPort == udpPort) {
            return existing
        }
        closeSocket()
        val created = DatagramSocket(null)
        created.reuseAddress = true
        created.broadcast = true
        created.soTimeout = 800
        created.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), udpPort))
        socket = created
        boundPort = udpPort
        acquireLock()
        return created
    }

    private fun sendProbe(sock: DatagramSocket, udpPort: Int) {
        val packet = byteArrayOf(
            'T'.code.toByte(), 'S'.code.toByte(), 'Q'.code.toByte(), '1'.code.toByte(), 1, 0
        )
        for (addr in broadcastTargets()) {
            try {
                sock.send(DatagramPacket(packet, packet.size, addr, udpPort))
            } catch (_: Exception) {
            }
        }
    }

    private fun isQuery(buf: ByteArray, len: Int): Boolean {
        return len >= 6
            && buf[0] == 'T'.code.toByte() && buf[1] == 'S'.code.toByte()
            && buf[2] == 'Q'.code.toByte() && buf[3] == '1'.code.toByte()
            && buf[4] == 1.toByte()
    }

    private fun sendBeacon(sock: DatagramSocket, tcpPort: Int, udpPort: Int) {
        val packet = encode(tcpPort, deviceName(), instanceId)
        for (addr in broadcastTargets()) {
            try {
                sock.send(DatagramPacket(packet, packet.size, addr, udpPort))
            } catch (_: Exception) {
            }
        }
    }

    private fun receiveOne(sock: DatagramSocket) {
        val buf = ByteArray(256)
        val pkt = DatagramPacket(buf, buf.size)
        try {
            sock.receive(pkt)
        } catch (_: java.net.SocketTimeoutException) {
            return
        }
        val src = pkt.address
        if (src !is Inet4Address || src.isAnyLocalAddress || src.isLoopbackAddress) {
            return
        }
        val len = pkt.length
        if (isQuery(buf, len)) {
            val from = src.hostAddress
            val port = TsServer.listenPortCurrent()
            if (from != null && !isLocalAddress(from) && port in 1..65535) {
                val packet = encode(port, deviceName(), instanceId)
                try {
                    sock.send(DatagramPacket(packet, packet.size, src, pkt.port))
                } catch (_: Exception) {
                }
            }
            return
        }
        if (LanAuth.isAuthPacket(buf, len)) {
            val type = LanAuth.typeOf(buf, len)
            if (type == LanAuth.TYPE_GRANT || type == LanAuth.TYPE_DENY || type == LanAuth.TYPE_PENDING) {
                LanAuth.onAuthReply(buf, len)
                return
            }
            if (type == LanAuth.TYPE_REQUEST) {
                val host = src.hostAddress ?: return
                if (isLocalAddress(host)) {
                    return
                }
                val req = LanAuth.parseRequest(buf, len, host, pkt.port) ?: return
                authRequestHandler?.invoke(req)
                return
            }
            return
        }
        val decoded = decode(buf, len) ?: return
        if (decoded.id.contentEquals(instanceId)) {
            return
        }
        val host = src.hostAddress ?: return
        if (isLocalAddress(host)) {
            return
        }
        if (!collectingPeers()) {
            return
        }
        val idKey = decoded.id.joinToString("") { b -> "%02x".format(b) }
        val peer = Peer(host, decoded.tcpPort, decoded.name, idKey)
        synchronized(peers) {
            // 同实例多网卡会从不同源 IP 进来：两条都留，靠 idKey 提示可能是同一设备
            peers["$host:${peer.tcpPort}"] = PeerSeen(peer, System.currentTimeMillis())
        }
    }

    private fun isLocalAddress(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty()) {
            return false
        }
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (nif in interfaces) {
                for (addr in nif.inetAddresses) {
                    val local = addr.hostAddress?.substringBefore('%')?.lowercase() ?: continue
                    if (local == h) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun prune(now: Long) {
        if (!collectingPeers()) {
            return
        }
        synchronized(peers) {
            val it = peers.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.seenAt > PEER_TTL_MS) {
                    it.remove()
                }
            }
        }
    }

    private fun currentPeers(): List<Peer> {
        synchronized(peers) {
            return peers.values.map { it.peer }.sortedBy { it.name.ifBlank { it.host } }
        }
    }

    private fun publish(
        enabled: Boolean,
        paused: Boolean,
        list: List<Peer>,
        bindError: String,
        udpPort: Int
    ) {
        val auto = if (::app.isInitialized) isAuto() else true
        val probing = enabled && !paused && bindError.isEmpty() && collectingPeers()
        val cur = _snap.value
        if (cur.enabled == enabled && cur.paused == paused && cur.peers == list
            && cur.bindError == bindError && cur.udpPort == udpPort
            && cur.auto == auto && cur.probing == probing
        ) {
            return
        }
        _snap.value = Snapshot(enabled, paused, list, bindError, udpPort, auto, probing)
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        boundPort = -1
        releaseLock()
    }

    private fun acquireLock() {
        if (multicastLock?.isHeld == true) {
            return
        }
        try {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wifi.createMulticastLock("textsend-lan")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (_: Exception) {
        }
    }

    private fun releaseLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?: return listOf(InetAddress.getByName("255.255.255.255"))
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) {
                    continue
                }
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b is Inet4Address) {
                        out.add(b)
                    }
                }
            }
        } catch (_: Exception) {
        }
        out.add(InetAddress.getByName("255.255.255.255"))
        return out.toList()
    }

    private fun deviceName(): String {
        val global = try {
            Settings.Global.getString(app.contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        val raw = global?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"
        return raw.replace(Regex("[\\p{Cntrl}]"), "").trim().ifBlank { "Android" }
    }

    private fun sleep(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun prefs() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private data class Decoded(val tcpPort: Int, val name: String, val id: ByteArray)

    private fun encode(tcpPort: Int, name: String, id: ByteArray): ByteArray {
        val nameBytes = clipName(name)
        val out = ByteArray(4 + 1 + 2 + 1 + nameBytes.size + 8)
        MAGIC.toByteArray(StandardCharsets.US_ASCII).copyInto(out, 0)
        out[4] = VERSION
        out[5] = (tcpPort shr 8).toByte()
        out[6] = tcpPort.toByte()
        out[7] = nameBytes.size.toByte()
        nameBytes.copyInto(out, 8)
        id.copyInto(out, 8 + nameBytes.size, 0, 8)
        return out
    }

    private fun decode(buf: ByteArray, length: Int): Decoded? {
        if (length < 16) {
            return null
        }
        if (buf[0] != 'T'.code.toByte() || buf[1] != 'S'.code.toByte()
            || buf[2] != 'D'.code.toByte() || buf[3] != '1'.code.toByte()
        ) {
            return null
        }
        if (buf[4] != VERSION) {
            return null
        }
        val port = ((buf[5].toInt() and 0xFF) shl 8) or (buf[6].toInt() and 0xFF)
        if (port !in 1..65535) {
            return null
        }
        val nameLen = buf[7].toInt() and 0xFF
        if (nameLen > NAME_MAX || length != 16 + nameLen) {
            return null
        }
        val name = String(buf, 8, nameLen, StandardCharsets.UTF_8)
        val id = buf.copyOfRange(8 + nameLen, 16 + nameLen)
        return Decoded(port, name, id)
    }

    private fun clipName(name: String): ByteArray {
        val all = name.toByteArray(StandardCharsets.UTF_8)
        if (all.size <= NAME_MAX) {
            return all
        }
        var n = NAME_MAX
        while (n > 0 && (all[n].toInt() and 0xC0) == 0x80) {
            n--
        }
        return all.copyOf(n)
    }
}
