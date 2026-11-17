package cn.rmshadows.textsend

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import protocol.TsServer
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端待授权队列 + 限流 / 冷却 / 黑名单。
 */
object LanAuthGate {
    private const val PREF = "textsend_prefs"
    private const val KEY_RATE = "auth_rate_per_min"
    private const val KEY_COOL = "auth_cooldown_sec"
    private const val KEY_BLACK = "auth_blacklist"
    private const val PENDING_MAX = 16

    data class PendingItem(val host: String, val name: String, val at: Long, val replyPort: Int)

    data class Snapshot(val pending: List<PendingItem> = emptyList())

    private lateinit var app: Application
    private val pendingByHost = ConcurrentHashMap<String, Pending>()
    private val hitsByHost = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()

    private data class Pending(val req: LanAuth.Request, val at: Long)

    fun init(application: Application) {
        app = application
        LanDiscover.setAuthRequestHandler { onRequest(it) }
        publish()
    }

    fun getRatePerMin(): Int {
        if (!::app.isInitialized) return 3
        val n = prefs().getInt(KEY_RATE, 3)
        return if (n in 1..60) n else 3
    }

    fun setRatePerMin(n: Int): Boolean {
        if (!::app.isInitialized || n !in 1..60) return false
        prefs().edit().putInt(KEY_RATE, n).apply()
        return true
    }

    fun getCooldownSec(): Int {
        if (!::app.isInitialized) return 120
        val n = prefs().getInt(KEY_COOL, 120)
        return n.coerceIn(0, 86400)
    }

    fun setCooldownSec(n: Int): Boolean {
        if (!::app.isInitialized || n !in 0..86400) return false
        prefs().edit().putInt(KEY_COOL, n).apply()
        return true
    }

    fun getBlacklistText(): String {
        if (!::app.isInitialized) return ""
        return prefs().getString(KEY_BLACK, "")?.replace(",", "\n")?.trim().orEmpty()
    }

    fun setBlacklistText(text: String) {
        if (!::app.isInitialized) return
        val list = text.split(Regex("[,;\\s]+")).map { normalizeHost(it) }.filter { it.isNotEmpty() }.distinct()
        prefs().edit().putString(KEY_BLACK, list.joinToString(",")).apply()
        dropBlacklistedPending()
        publish()
    }

    fun isBlacklisted(host: String): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty() || !::app.isInitialized) return false
        val raw = prefs().getString(KEY_BLACK, "") ?: ""
        return raw.split(",").any { normalizeHost(it) == h }
    }

    fun addBlacklist(host: String) {
        if (!::app.isInitialized) return
        val h = normalizeHost(host)
        if (h.isEmpty()) return
        val set = LinkedHashSet(
            (prefs().getString(KEY_BLACK, "") ?: "").split(",").map { normalizeHost(it) }.filter { it.isNotEmpty() }
        )
        if (set.add(h)) {
            prefs().edit().putString(KEY_BLACK, set.joinToString(",")).apply()
        }
    }

    private fun onRequest(req: LanAuth.Request) {
        if (!LanDiscover.isEnabled() || !TsServer.isRunningCurrent()) {
            denySilent(req)
            return
        }
        val host = normalizeHost(req.host)
        if (host.isEmpty() || isBlacklisted(host)) {
            denySilent(req)
            return
        }
        val now = System.currentTimeMillis()
        val until = cooldownUntil[host] ?: 0L
        if (until > now) {
            denySilent(req)
            return
        }
        if (!noteHit(host, now)) {
            denySilent(req)
            return
        }
        val prev = pendingByHost.put(host, Pending(req, now))
        if (prev != null && !prev.req.nonce.contentEquals(req.nonce)) {
            denySilent(prev.req)
        }
        trimOverflow()
        if (req.tcpReply == null) {
            LanDiscover.sendAuthPacket(req.host, req.replyPort, LanAuth.encodePending(req.nonce))
        }
        publish()
    }

    fun acceptTcp(req: LanAuth.Request) {
        onRequest(req)
    }

    fun listPending(): List<PendingItem> {
        val now = System.currentTimeMillis()
        for ((host, p) in pendingByHost.toMap()) {
            if (now - p.at > LanAuth.REQUEST_WAIT_MS) {
                pendingByHost.remove(host, p)
                denySilent(p.req)
            }
        }
        return pendingByHost.entries
            .map { (h, p) ->
                PendingItem(h, p.req.name.ifBlank { "未知设备" }, p.at, p.req.replyPort)
            }
            .sortedBy { it.at }
    }

    fun decide(host: String, action: String): Boolean {
        val key = normalizeHost(host)
        val pending = pendingByHost.remove(key)
        if (action == "blacklist") {
            addBlacklist(key)
        }
        if (pending == null) {
            publish()
            return false
        }
        val req = pending.req
        val name = req.name.ifBlank { "未知设备" }
        if (action == "allow" && TsServer.isRunningCurrent() && !isBlacklisted(key)) {
            cooldownUntil.remove(key)
            val psk = LanAuth.newPsk()
            val exp = System.currentTimeMillis() + LanAuth.GRANT_TTL_MS
            TsServer.putLanGrantCurrent(req.host, psk, LanAuth.GRANT_TTL_MS, name)
            val port = TsServer.listenPortCurrent()
            reply(req, LanAuth.encodeGrant(req.nonce, psk, port, exp))
        } else {
            TsServer.revokeLanGrantCurrent(req.host)
            denySilent(req)
            applyCooldown(key)
        }
        publish()
        return true
    }

    fun denyAll() {
        for (h in pendingByHost.keys().toList()) {
            decide(h, "deny")
        }
    }

    fun clearPending(deny: Boolean) {
        if (deny) {
            for (p in pendingByHost.values) {
                denySilent(p.req)
            }
        }
        pendingByHost.clear()
        publish()
    }

    private fun noteHit(host: String, now: Long): Boolean {
        val limit = getRatePerMin()
        val hits = hitsByHost.getOrPut(host) { ArrayDeque() }
        while (hits.isNotEmpty() && now - hits.first() >= 60_000L) {
            hits.removeFirst()
        }
        if (hits.size >= limit) {
            val cool = getCooldownSec()
            if (cool > 0) {
                cooldownUntil[host] = now + cool * 1000L
            }
            return false
        }
        hits.addLast(now)
        return true
    }

    private fun trimOverflow() {
        while (pendingByHost.size > PENDING_MAX) {
            val oldest = pendingByHost.minByOrNull { it.value.at } ?: break
            pendingByHost.remove(oldest.key)
            denySilent(oldest.value.req)
        }
    }

    private fun applyCooldown(host: String) {
        val cool = getCooldownSec()
        if (cool > 0) {
            cooldownUntil[normalizeHost(host)] = System.currentTimeMillis() + cool * 1000L
        }
    }

    private fun reply(req: LanAuth.Request, packet: ByteArray) {
        val tcp = req.tcpReply
        if (tcp != null) {
            tcp.complete(packet)
            return
        }
        LanDiscover.sendAuthPacket(req.host, req.replyPort, packet)
    }

    private fun denySilent(req: LanAuth.Request?) {
        if (req == null) return
        reply(req, LanAuth.encodeDeny(req.nonce))
    }

    private fun dropBlacklistedPending() {
        for (host in pendingByHost.keys().toList()) {
            if (isBlacklisted(host)) {
                val p = pendingByHost.remove(host)
                if (p != null) denySilent(p.req)
            }
        }
    }

    private fun publish() {
        _snap.value = Snapshot(listPending())
    }

    fun normalizeHost(host: String?): String {
        var h = host?.trim().orEmpty()
        if (h.startsWith("[") && h.endsWith("]") && h.length > 2) {
            h = h.substring(1, h.length - 1)
        }
        if (h.startsWith("::ffff:", ignoreCase = true)) {
            h = h.substring(7)
        }
        return h.lowercase()
    }

    private fun prefs() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
