package cn.rmshadows.textsend

import protocol.B64
import protocol.EcdhP256
import protocol.Hkdf
import protocol.Protocol
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 局域网临时授权（TSA1），与 Desktop / uTools 一致。
 * PENDING 先带服务端公钥（短核对码），GRANT 再确认；密钥由 ECDH 各自算出。
 */
object LanAuth {
    val MAGIC: ByteArray = "TSA1".toByteArray(StandardCharsets.US_ASCII)
    const val VERSION: Byte = 2
    const val PUB_LEN = 65
    const val HKDF_INFO = "textsend-v1|lan-auth"
    const val TYPE_REQUEST: Byte = 1
    const val TYPE_GRANT: Byte = 2
    const val TYPE_DENY: Byte = 3
    const val TYPE_PENDING: Byte = 4
    /** 客户端取消这次申请。服务端丢掉待授权，不记冷却。 */
    const val TYPE_CANCEL: Byte = 5
    const val NONCE_LEN = 16
    const val NAME_MAX = 48
    const val GRANT_TTL_MS = 60_000L
    const val REQUEST_WAIT_MS = 60_000L
    /** DENY 尾部两个字节：0xFFFF 表示拉黑或服务没开；其余是还能再请求的秒数。 */
    const val DENY_HARD = 0xFFFF

    class AuthDeniedException(message: String, val waitSec: Int = -1) : IllegalStateException(message)

    class AuthCancelledException : IllegalStateException("已取消授权申请")

    private class Active {
        @Volatile var tcp: java.net.Socket? = null
        @Volatile var udp: java.net.DatagramSocket? = null
        @Volatile var host: String? = null
        @Volatile var udpPort: Int = 0
        @Volatile var nonce: ByteArray? = null
        @Volatile var cancelled: Boolean = false
    }

    @Volatile private var active: Active? = null

    private val rng = SecureRandom()

    private class Waiter(
        val keys: KeyPair,
        val clientPub: ByteArray,
        val fut: CompletableFuture<Grant>,
        @Volatile var pendingServerPub: ByteArray? = null,
        @Volatile var onSas: ((String) -> Unit)? = null
    )

    private val waiting = ConcurrentHashMap<ByteBuffer, Waiter>()

    data class Grant(val psk: ByteArray, val tcpPort: Int) {
        fun pskB64(): String = B64.enc(psk)
    }

    data class Request(
        val host: String,
        val replyPort: Int,
        val name: String,
        val nonce: ByteArray,
        val clientPub: ByteArray,
        val tcpReply: CompletableFuture<ByteArray>? = null
    ) {
        @Volatile var earlyPending: ByteArray? = null
    }

    fun isAuthPacket(buf: ByteArray, length: Int): Boolean {
        return length >= 6
            && buf[0] == 'T'.code.toByte() && buf[1] == 'S'.code.toByte()
            && buf[2] == 'A'.code.toByte() && buf[3] == '1'.code.toByte()
            && buf[4] == VERSION
    }

    fun typeOf(buf: ByteArray, length: Int): Byte {
        return if (isAuthPacket(buf, length)) buf[5] else 0
    }

    fun parseRequest(buf: ByteArray, length: Int, host: String, replyPort: Int): Request? {
        if (!isAuthPacket(buf, length) || buf[5] != TYPE_REQUEST || length < 7 + NONCE_LEN) {
            return null
        }
        val nameLen = buf[6].toInt() and 0xFF
        val base = 7 + nameLen + NONCE_LEN
        if (nameLen > NAME_MAX || length != base + PUB_LEN) {
            return null
        }
        val name = String(buf, 7, nameLen, StandardCharsets.UTF_8)
        val nonce = buf.copyOfRange(7 + nameLen, base)
        val pub = buf.copyOfRange(base, base + PUB_LEN)
        if (pub[0] != 0x04.toByte()) {
            return null
        }
        return Request(host, replyPort, name, nonce, pub)
    }

    fun encodeRequest(name: String, nonce: ByteArray, clientPub: ByteArray): ByteArray {
        val nameBytes = clipName(name)
        val out = ByteArray(7 + nameBytes.size + NONCE_LEN + PUB_LEN)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION
        out[5] = TYPE_REQUEST
        out[6] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, out, 7, nameBytes.size)
        System.arraycopy(nonce, 0, out, 7 + nameBytes.size, NONCE_LEN)
        System.arraycopy(clientPub, 0, out, 7 + nameBytes.size + NONCE_LEN, PUB_LEN)
        return out
    }

    fun encodeGrant(nonce: ByteArray, serverPub: ByteArray, tcpPort: Int, expireAtMs: Long): ByteArray {
        val out = ByteArray(6 + NONCE_LEN + PUB_LEN + 2 + 4)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION
        out[5] = TYPE_GRANT
        System.arraycopy(nonce, 0, out, 6, NONCE_LEN)
        System.arraycopy(serverPub, 0, out, 6 + NONCE_LEN, PUB_LEN)
        var o = 6 + NONCE_LEN + PUB_LEN
        out[o] = (tcpPort shr 8).toByte()
        out[o + 1] = tcpPort.toByte()
        val expSec = minOf(Int.MAX_VALUE.toLong(), expireAtMs / 1000L).toInt()
        out[o + 2] = (expSec shr 24).toByte()
        out[o + 3] = (expSec shr 16).toByte()
        out[o + 4] = (expSec shr 8).toByte()
        out[o + 5] = expSec.toByte()
        return out
    }

    fun encodeDeny(nonce: ByteArray, waitSec: Int = DENY_HARD): ByteArray {
        val sec = if (waitSec < 0) DENY_HARD else waitSec.coerceAtMost(DENY_HARD)
        val out = ByteArray(6 + NONCE_LEN + 2)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION
        out[5] = TYPE_DENY
        System.arraycopy(nonce, 0, out, 6, NONCE_LEN)
        val o = 6 + NONCE_LEN
        out[o] = (sec shr 8).toByte()
        out[o + 1] = sec.toByte()
        return out
    }

    fun encodeCancel(nonce: ByteArray): ByteArray {
        val out = ByteArray(6 + NONCE_LEN)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION
        out[5] = TYPE_CANCEL
        if (nonce.size >= NONCE_LEN) {
            System.arraycopy(nonce, 0, out, 6, NONCE_LEN)
        }
        return out
    }

    fun isCancel(buf: ByteArray, length: Int): Boolean {
        return isAuthPacket(buf, length) && length >= 6 + NONCE_LEN && buf[5] == TYPE_CANCEL
    }

    fun isCancelled(e: Throwable?): Boolean {
        var cur = e
        while (cur != null) {
            if (cur is AuthCancelledException) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    fun isWaiting(host: String): Boolean {
        val a = active ?: return false
        return !a.cancelled && sameHost(a.host, host)
    }

    fun cancelActive(): Boolean {
        val a = active ?: return false
        if (a.cancelled) {
            return false
        }
        a.cancelled = true
        val nonce = a.nonce
        val pkt = if (nonce == null) null else encodeCancel(nonce)
        val tcp = a.tcp
        if (pkt != null && tcp != null && tcp.isConnected) {
            try {
                tcp.getOutputStream().apply {
                    write(pkt)
                    flush()
                }
                tcp.shutdownOutput()
            } catch (_: Exception) {
            }
        }
        val udp = a.udp
        if (pkt != null && udp != null && !a.host.isNullOrBlank() && a.udpPort > 0) {
            try {
                val addr = java.net.InetAddress.getByName(a.host)
                udp.send(java.net.DatagramPacket(pkt, pkt.size, addr, a.udpPort))
            } catch (_: Exception) {
            }
        }
        if (nonce != null) {
            waiting[ByteBuffer.wrap(nonce)]?.fut?.completeExceptionally(AuthCancelledException())
        }
        try {
            tcp?.close()
        } catch (_: Exception) {
        }
        return true
    }

    private fun sameHost(a: String?, b: String?): Boolean {
        return normHost(a) == normHost(b)
    }

    private fun normHost(host: String?): String {
        var h = host?.trim()?.lowercase().orEmpty()
        if (h.startsWith("::ffff:") && h.length > 7) {
            h = h.substring(7)
        }
        return h
    }

    private fun beginActive(host: String, udpPort: Int, nonce: ByteArray): Active {
        val mine = Active()
        mine.host = host
        mine.udpPort = udpPort
        mine.nonce = nonce
        active = mine
        return mine
    }

    private fun endActive(mine: Active) {
        if (active === mine) {
            active = null
        }
    }

    /** 旧包没有尾部秒数时返回 -1。 */
    fun denyWaitSec(buf: ByteArray, length: Int): Int {
        val base = 6 + NONCE_LEN
        if (length < base + 2) {
            return -1
        }
        return ((buf[base].toInt() and 0xFF) shl 8) or (buf[base + 1].toInt() and 0xFF)
    }

    fun denyMessage(waitSec: Int): String {
        if (waitSec < 0) {
            return "对方拒绝了这次授权。冷却结束后可以再请求，没有拉黑"
        }
        if (waitSec == DENY_HARD) {
            return "对方拒绝了授权"
        }
        if (waitSec == 0) {
            return "对方拒绝了这次授权，可以再请求"
        }
        return "对方暂时拒绝，约 $waitSec 秒后可以再请求（没有拉黑）"
    }

    fun isDenied(e: Throwable?): Boolean {
        var cur = e
        while (cur != null) {
            if (cur is AuthDeniedException) {
                return true
            }
            val m = cur.message
            if (m != null && (m.contains("拒绝") || m.contains("冷却"))) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    fun encodePending(nonce: ByteArray, serverPub: ByteArray): ByteArray {
        require(serverPub.size == PUB_LEN) { "PENDING 需要服务端公钥" }
        val out = ByteArray(6 + NONCE_LEN + PUB_LEN)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION
        out[5] = TYPE_PENDING
        System.arraycopy(nonce, 0, out, 6, NONCE_LEN)
        System.arraycopy(serverPub, 0, out, 6 + NONCE_LEN, PUB_LEN)
        return out
    }

    fun pendingServerPub(buf: ByteArray, length: Int): ByteArray? {
        val need = 6 + NONCE_LEN + PUB_LEN
        if (length < need || buf[5] != TYPE_PENDING) {
            return null
        }
        val pub = buf.copyOfRange(6 + NONCE_LEN, need)
        return if (pub[0] == 0x04.toByte()) pub else null
    }

    /** 短核对码：SHA-256(clientPub‖serverPub‖nonce) 前 4 字节 → ABCD-EF01。 */
    fun sasCode(clientPub: ByteArray?, serverPub: ByteArray?, nonce: ByteArray?): String {
        if (clientPub == null || serverPub == null || nonce == null
            || clientPub.size != PUB_LEN || serverPub.size != PUB_LEN || nonce.size != NONCE_LEN
        ) {
            return ""
        }
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(clientPub)
            md.update(serverPub)
            md.update(nonce)
            val dig = md.digest()
            val hex = String.format(
                "%02X%02X%02X%02X",
                dig[0].toInt() and 0xFF,
                dig[1].toInt() and 0xFF,
                dig[2].toInt() and 0xFF,
                dig[3].toInt() and 0xFF
            )
            hex.substring(0, 4) + "-" + hex.substring(4)
        } catch (_: Exception) {
            ""
        }
    }

    fun onAuthReply(buf: ByteArray, length: Int) {
        if (!isAuthPacket(buf, length) || length < 6 + NONCE_LEN) {
            return
        }
        val type = buf[5]
        val nonce = buf.copyOfRange(6, 6 + NONCE_LEN)
        val key = ByteBuffer.wrap(nonce)
        val waiter = waiting[key] ?: return
        when (type) {
            TYPE_DENY -> {
                waiting.remove(key)
                val wait = denyWaitSec(buf, length)
                waiter.fut.completeExceptionally(AuthDeniedException(denyMessage(wait), wait))
            }
            TYPE_PENDING -> notePending(waiter, buf, length, nonce)
            TYPE_GRANT -> {
                try {
                    val grant = grantFromPacket(buf, length, waiter, nonce)
                    waiting.remove(key)
                    waiter.fut.complete(grant)
                } catch (e: Exception) {
                    waiting.remove(key)
                    waiter.fut.completeExceptionally(e)
                }
            }
        }
    }

    private fun notePending(waiter: Waiter, buf: ByteArray, length: Int, nonce: ByteArray) {
        val serverPub = pendingServerPub(buf, length) ?: return
        waiter.pendingServerPub = serverPub
        val sas = sasCode(waiter.clientPub, serverPub, nonce)
        if (sas.isNotEmpty()) {
            try {
                waiter.onSas?.invoke(sas)
            } catch (_: Exception) {
            }
        }
    }

    fun deriveGrantPsk(local: KeyPair, remotePub: ByteArray, nonce: ByteArray): ByteArray {
        if (remotePub.size != PUB_LEN || remotePub[0] != 0x04.toByte()) {
            throw IllegalArgumentException("授权公钥无效")
        }
        val secret = EcdhP256.sharedSecret(local, EcdhP256.decodePublic(remotePub))
        return Hkdf.derive(secret, nonce, HKDF_INFO, Protocol.PSK_LEN)
    }

    private fun grantFromPacket(buf: ByteArray, length: Int, waiter: Waiter, expectNonce: ByteArray): Grant {
        val need = 6 + NONCE_LEN + PUB_LEN + 2 + 4
        if (length < need) {
            throw IllegalStateException("授权回应太短")
        }
        val nonce = buf.copyOfRange(6, 6 + NONCE_LEN)
        if (!nonce.contentEquals(expectNonce)) {
            throw IllegalStateException("授权回应和这次请求对不上")
        }
        val serverPub = buf.copyOfRange(6 + NONCE_LEN, 6 + NONCE_LEN + PUB_LEN)
        if (serverPub[0] != 0x04.toByte()) {
            throw IllegalStateException("授权公钥无效")
        }
        val pending = waiter.pendingServerPub
        if (pending != null && !pending.contentEquals(serverPub)) {
            throw IllegalStateException("核对码对应的公钥变了，已中止")
        }
        val o = 6 + NONCE_LEN + PUB_LEN
        val tcpPort = ((buf[o].toInt() and 0xFF) shl 8) or (buf[o + 1].toInt() and 0xFF)
        return Grant(deriveGrantPsk(waiter.keys, serverPub, nonce), tcpPort)
    }

    fun requestAuth(
        sock: java.net.DatagramSocket,
        host: String,
        udpPort: Int,
        deviceName: String,
        alsoSendTo: List<java.net.InetAddress> = emptyList(),
        onSas: ((String) -> Unit)? = null
    ): Grant {
        val nonce = ByteArray(NONCE_LEN)
        rng.nextBytes(nonce)
        val keys = EcdhP256.generate()
        val pub = EcdhP256.encodePublic(keys.public as ECPublicKey)
        val key = ByteBuffer.wrap(nonce)
        val fut = CompletableFuture<Grant>()
        waiting[key] = Waiter(keys, pub, fut, onSas = onSas)
        val mine = beginActive(host, udpPort, nonce)
        mine.udp = sock
        try {
            val packet = encodeRequest(deviceName, nonce, pub)
            val addr = java.net.InetAddress.getByName(host)
            // 先广播再单播。单播若被拦，电脑仍能从探测口收到；单播放最后，避免套接字被连到广播地址后收不到回复。
            for (extra in alsoSendTo) {
                if (extra.hostAddress == addr.hostAddress) {
                    continue
                }
                try {
                    sock.send(java.net.DatagramPacket(packet, packet.size, extra, udpPort))
                } catch (_: Exception) {
                }
            }
            sock.send(java.net.DatagramPacket(packet, packet.size, addr, udpPort))
            return fut.get(REQUEST_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            waiting.remove(key)
            if (mine.cancelled) {
                throw AuthCancelledException()
            }
            throw e
        } finally {
            endActive(mine)
        }
    }

    private val primed = java.util.Collections.synchronizedMap(java.util.WeakHashMap<java.net.Socket, ByteArray>())

    /**
     * true 表示这是授权连接，已经接手。
     * 正常握手由服务端先发 HELLO。只等很短一会儿，没字节就当普通连接。
     */
    @JvmStatic
    fun divertTcp(sock: java.net.Socket): Boolean {
        return try {
            sock.soTimeout = 400
            val input = sock.getInputStream()
            val head = readN(input, 4)
            if (head[0] == 'T'.code.toByte() && head[1] == 'S'.code.toByte()
                && head[2] == 'A'.code.toByte() && head[3] == '1'.code.toByte()
            ) {
                Thread({ finishTcp(sock, input) }, "ts-auth-tcp").start()
                true
            } else {
                primed[sock] = head
                false
            }
        } catch (_: java.net.SocketTimeoutException) {
            try {
                sock.soTimeout = 0
            } catch (_: Exception) {
            }
            false
        } catch (_: Exception) {
            try {
                sock.close()
            } catch (_: Exception) {
            }
            true
        }
    }

    @JvmStatic
    fun takePrime(sock: java.net.Socket): ByteArray? = primed.remove(sock)

    private fun finishTcp(sock: java.net.Socket, input: java.io.InputStream) {
        try {
            val meta = readN(input, 3)
            if (meta[0] != VERSION || meta[1] != TYPE_REQUEST) {
                return
            }
            val nameLen = meta[2].toInt() and 0xFF
            if (nameLen > NAME_MAX) {
                return
            }
            val tail = readN(input, nameLen + NONCE_LEN + PUB_LEN)
            val full = ByteArray(7 + nameLen + NONCE_LEN + PUB_LEN)
            full[0] = 'T'.code.toByte()
            full[1] = 'S'.code.toByte()
            full[2] = 'A'.code.toByte()
            full[3] = '1'.code.toByte()
            System.arraycopy(meta, 0, full, 4, 3)
            System.arraycopy(tail, 0, full, 7, tail.size)
            val ip = sock.inetAddress?.hostAddress ?: ""
            val parsed = parseRequest(full, full.size, ip, sock.port) ?: return
            val fut = CompletableFuture<ByteArray>()
            val req = parsed.copy(tcpReply = fut)
            sock.soTimeout = 0
            LanAuthGate.acceptTcp(req)
            val out = sock.getOutputStream()
            req.earlyPending?.let {
                out.write(it)
                out.flush()
            }
            val resp = waitAuthReply(sock, input, fut, req)
            if (resp != null) {
                out.write(resp)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun waitAuthReply(
        sock: java.net.Socket,
        input: java.io.InputStream,
        fut: CompletableFuture<ByteArray>,
        req: Request
    ): ByteArray? {
        val end = System.currentTimeMillis() + REQUEST_WAIT_MS
        val buf = ByteArray(64)
        val extra = java.io.ByteArrayOutputStream()
        sock.soTimeout = 400
        while (!fut.isDone && System.currentTimeMillis() < end) {
            try {
                val n = input.read(buf)
                if (n < 0) {
                    LanAuthGate.drop(req.host, req.nonce)
                    return null
                }
                extra.write(buf, 0, n)
                val got = extra.toByteArray()
                if (isCancel(got, got.size)) {
                    LanAuthGate.drop(req.host, req.nonce)
                    return null
                }
            } catch (_: java.net.SocketTimeoutException) {
            } catch (_: java.io.IOException) {
                LanAuthGate.drop(req.host, req.nonce)
                return null
            }
        }
        if (!fut.isDone || fut.isCompletedExceptionally || fut.isCancelled) {
            return null
        }
        return fut.getNow(null)
    }

    fun requestAuthTcp(
        host: String,
        tcpPort: Int,
        deviceName: String,
        onSas: ((String) -> Unit)? = null
    ): Grant {
        val nonce = ByteArray(NONCE_LEN)
        rng.nextBytes(nonce)
        val keys = EcdhP256.generate()
        val pub = EcdhP256.encodePublic(keys.public as ECPublicKey)
        val packet = encodeRequest(deviceName, nonce, pub)
        val waiter = Waiter(keys, pub, CompletableFuture(), onSas = onSas)
        val sock = java.net.Socket()
        val mine = beginActive(host, 0, nonce)
        mine.tcp = sock
        try {
            sock.connect(java.net.InetSocketAddress(host, tcpPort), 5000)
            sock.soTimeout = REQUEST_WAIT_MS.toInt()
            sock.getOutputStream().apply {
                write(packet)
                flush()
            }
            val input = sock.getInputStream()
            while (true) {
                val head = readN(input, 6)
                if (!isAuthPacket(head, 6)) {
                    throw IllegalStateException("服务端没有按授权回应")
                }
                val type = head[5]
                if (type == TYPE_DENY) {
                    val rest = readDenyRest(sock, input)
                    val all = ByteArray(head.size + rest.size)
                    System.arraycopy(head, 0, all, 0, head.size)
                    System.arraycopy(rest, 0, all, head.size, rest.size)
                    val wait = denyWaitSec(all, all.size)
                    throw AuthDeniedException(denyMessage(wait), wait)
                }
                if (type == TYPE_PENDING) {
                    val rest = readN(input, NONCE_LEN + PUB_LEN)
                    val all = ByteArray(head.size + rest.size)
                    System.arraycopy(head, 0, all, 0, head.size)
                    System.arraycopy(rest, 0, all, head.size, rest.size)
                    notePending(waiter, all, all.size, nonce)
                    continue
                }
                if (type != TYPE_GRANT) {
                    throw IllegalStateException("服务端没有按授权回应")
                }
                val rest = readN(input, NONCE_LEN + PUB_LEN + 2 + 4)
                val all = ByteArray(head.size + rest.size)
                System.arraycopy(head, 0, all, 0, head.size)
                System.arraycopy(rest, 0, all, head.size, rest.size)
                return grantFromPacket(all, all.size, waiter, nonce)
            }
        } catch (e: Exception) {
            if (mine.cancelled) {
                throw AuthCancelledException()
            }
            throw e
        } finally {
            endActive(mine)
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readDenyRest(sock: java.net.Socket, input: java.io.InputStream): ByteArray {
        val nonce = readN(input, NONCE_LEN)
        val prev = sock.soTimeout
        sock.soTimeout = 200
        try {
            val extra = ByteArray(2)
            var n = input.read(extra)
            if (n == 1) {
                val n2 = input.read(extra, 1, 1)
                n = if (n2 < 0) 1 else 2
            }
            if (n == 2) {
                return nonce + extra
            }
            return nonce
        } catch (_: java.net.SocketTimeoutException) {
            return nonce
        } finally {
            try {
                sock.soTimeout = prev
            } catch (_: Exception) {
            }
        }
    }

    private fun readN(input: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) {
                throw java.io.EOFException("short read")
            }
            off += r
        }
        return buf
    }

    fun newPsk(): ByteArray {
        val psk = ByteArray(Protocol.PSK_LEN)
        rng.nextBytes(psk)
        return psk
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
