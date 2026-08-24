package cn.rmshadows.textsend

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 连局域网 IP 时绑到同网段的 Wi‑Fi / 有线，避免默认走蜂窝。
 * 努比亚双通道仍可能拦上行数据，需飞行模式或关掉网络加速（见 README）。
 */
object LanSocket {
    private const val TAG = "Textsend"

    fun connect(context: Context, host: String, port: Int, timeoutMs: Int): Socket {
        val dest = InetAddress.getByName(host)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.let { pickLanNetwork(it, dest) }
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.keepAlive = true
        if (net != null) {
            try {
                net.bindSocket(socket)
                val lp = cm.getLinkProperties(net)
                Log.i(TAG, "bindSocket iface=${lp?.interfaceName} dest=$host")
            } catch (e: Exception) {
                Log.i(TAG, "bindSocket 失败: ${e.message}")
            }
        }
        socket.connect(InetSocketAddress(dest, port), timeoutMs)
        socket.tcpNoDelay = true
        Log.i(TAG, "TCP 已通 local=${socket.localSocketAddress} remote=${socket.remoteSocketAddress}")
        return socket
    }

    private fun pickLanNetwork(cm: ConnectivityManager, dest: InetAddress): Network? {
        var fallback: Network? = null
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue
            }
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!lan) {
                continue
            }
            if (fallback == null) {
                fallback = n
            }
            val lp = cm.getLinkProperties(n) ?: continue
            for (la in lp.linkAddresses) {
                if (sameSubnet(la.address, dest, la.prefixLength)) {
                    return n
                }
            }
        }
        return fallback
    }

    private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val aa = a.address
        val bb = b.address
        if (aa.size != bb.size) {
            return false
        }
        if (prefix < 0 || prefix > aa.size * 8) {
            return false
        }
        val fullBytes = prefix / 8
        val remBits = prefix % 8
        for (i in 0 until fullBytes) {
            if (aa[i] != bb[i]) {
                return false
            }
        }
        if (remBits == 0) {
            return true
        }
        val mask = (0xFF shl (8 - remBits)) and 0xFF
        return (aa[fullBytes].toInt() and mask) == (bb[fullBytes].toInt() and mask)
    }
}
