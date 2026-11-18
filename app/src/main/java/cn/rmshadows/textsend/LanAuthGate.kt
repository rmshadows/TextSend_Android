package cn.rmshadows.textsend

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import protocol.EcdhP256
import protocol.TsServer
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
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
    private const val DENY_SOFT_MAX = LanAuth.DENY_HARD - 1

    data class PendingItem(
        val host: String,
        val name: String,
        val at: Long,
        val replyPort: Int,
        val sas: String = ""
    )

    data class Snapshot(val pending: List<PendingItem> = emptyList())

    private lateinit var app: Application
    private val pendingByHost = ConcurrentHashMap<String, Pending>()
    private val hitsByHost = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()

    private data class Pending(
        val req: LanAuth.Request,
        val at: Long,
        val serverKp: KeyPair,
        val serverPub: ByteArray,
        val sas: String
    )

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
        if (!::app.isInitialized) return 10
        val n = prefs().getInt(KEY_COOL, 10)
        return n.coerceIn(0, 86400)
    }

    fun setCooldownSec(n: Int): Boolean {
        if (!::app.isInitialized || n !in 0..86400) return false
        prefs().edit().putInt(KEY_COOL, n).apply()
        return true
    }

    fun getBlacklist(): List<String> {
        if (!::app.isInitialized) return emptyList()
        val raw = prefs().getString(KEY_BLACK, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { normalizeHost(it) }.filter { it.isNotEmpty() }.distinct()
    }

    fun getBlacklistText(): String = getBlacklist().joinToString("\n")

    fun setBlacklistText(text: String) {
        if (!::app.isInitialized) return
        val list = text.split(Regex("[,;\\s]+")).map { normalizeHost(it) }.filter { it.isNotEmpty() }.distinct()
        prefs().edit().putString(KEY_BLACK, list.joinToString(",")).apply()
        dropBlacklistedPending()
        publish()
    }

    fun setBlacklist(hosts: Collection<String>) {
        if (!::app.isInitialized) return
        val list = hosts.map { normalizeHost(it) }.filter { it.isNotEmpty() }.distinct()
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
        val left = cooldownLeftSec(host, now)
        if (left > 0) {
            denySilent(req, left)
            return
        }
        if (!noteHit(host, now)) {
            denySilent(req, cooldownLeftSec(host, System.currentTimeMillis()))
            return
        }
        if (req.clientPub.size != LanAuth.PUB_LEN) {
            denySilent(req, 0)
            return
        }
        val serverKp: KeyPair
        val serverPub: ByteArray
        val sas: String
        try {
            serverKp = EcdhP256.generate()
            serverPub = EcdhP256.encodePublic(serverKp.public as ECPublicKey)
            sas = LanAuth.sasCode(req.clientPub, serverPub, req.nonce)
        } catch (_: Exception) {
            denySilent(req, 0)
            return
        }
        val pendingPkt = LanAuth.encodePending(req.nonce, serverPub)
        val prev = pendingByHost.put(host, Pending(req, now, serverKp, serverPub, sas))
        if (prev != null && !prev.req.nonce.contentEquals(req.nonce)) {
            denySilent(prev.req, 0)
        }
        trimOverflow()
        if (req.tcpReply != null) {
            req.earlyPending = pendingPkt
        } else {
            LanDiscover.sendAuthPacket(req.host, req.replyPort, pendingPkt)
        }
        publish()
    }

    fun acceptTcp(req: LanAuth.Request) {
        onRequest(req)
    }

    /** 客户端取消。对得上这次 nonce 才丢掉，不拒绝、不计冷却。 */
    fun drop(host: String, nonce: ByteArray?) {
        val key = normalizeHost(host)
        val pending = pendingByHost[key] ?: return
        if (nonce == null || !pending.req.nonce.contentEquals(nonce)) {
            return
        }
        if (pendingByHost.remove(key, pending)) {
            val reply = pending.req.tcpReply
            if (reply != null && !reply.isDone) {
                reply.completeExceptionally(LanAuth.AuthCancelledException())
            }
            publish()
        }
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
                PendingItem(h, p.req.name.ifBlank { "未知设备" }, p.at, p.req.replyPort, p.sas)
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
            if (req.clientPub.size != LanAuth.PUB_LEN) {
                denySilent(req, 0)
            } else {
                try {
                    val psk = LanAuth.deriveGrantPsk(pending.serverKp, req.clientPub, req.nonce)
                    cooldownUntil.remove(key)
                    val exp = System.currentTimeMillis() + LanAuth.GRANT_TTL_MS
                    TsServer.putLanGrantCurrent(req.host, psk, LanAuth.GRANT_TTL_MS, name)
                    val port = TsServer.listenPortCurrent()
                    reply(req, LanAuth.encodeGrant(req.nonce, pending.serverPub, port, exp))
                } catch (_: Exception) {
                    denySilent(req, 0)
                }
            }
        } else {
            TsServer.revokeLanGrantCurrent(req.host)
            if (action == "blacklist" || isBlacklisted(key)) {
                denySilent(req, LanAuth.DENY_HARD)
            } else {
                applyCooldown(key)
                denySilent(req, getCooldownSec().coerceIn(0, DENY_SOFT_MAX))
            }
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
            applyCooldown(host)
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
        val key = normalizeHost(host)
        if (getCooldownSec() > 0 && key.isNotEmpty()) {
            // 记下拒绝时刻。剩余时间按当前设置算，120 改成 10 会立刻缩短。
            cooldownUntil[key] = System.currentTimeMillis()
            hitsByHost.remove(key)
        }
    }

    private fun cooldownLeftSec(host: String, now: Long): Int {
        val key = normalizeHost(host)
        val started = cooldownUntil[key] ?: return 0
        val cool = getCooldownSec()
        if (cool <= 0) {
            cooldownUntil.remove(key)
            return 0
        }
        val until = started + cool * 1000L
        if (until <= now) {
            cooldownUntil.remove(key)
            return 0
        }
        return ((until - now + 999) / 1000).toInt().coerceIn(1, DENY_SOFT_MAX)
    }

    private fun reply(req: LanAuth.Request, packet: ByteArray) {
        val tcp = req.tcpReply
        if (tcp != null) {
            tcp.complete(packet)
            return
        }
        LanDiscover.sendAuthPacket(req.host, req.replyPort, packet)
    }

    private fun denySilent(req: LanAuth.Request?, waitSec: Int = LanAuth.DENY_HARD) {
        if (req == null) return
        reply(req, LanAuth.encodeDeny(req.nonce, waitSec))
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
