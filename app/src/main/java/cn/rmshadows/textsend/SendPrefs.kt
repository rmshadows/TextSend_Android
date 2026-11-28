package cn.rmshadows.textsend

import android.app.Application
import android.content.Context
import protocol.TsServer

/**
 * 发送目标、兼容互斥、网页黑名单。和电脑端 / uTools 对齐。
 */
object SendPrefs {
    private const val PREF = "textsend_prefs"
    private const val KEY_TARGET = "send_target"
    private const val KEY_EXCLUSIVE = "web_app_exclusive"
    private const val KEY_WEB_BLACK = "web_blacklist"

    /** app | web | all */
    const val TARGET_APP = "app"
    const val TARGET_WEB = "web"
    const val TARGET_ALL = "all"

    data class Route(val app: Boolean, val web: Boolean, val block: String?)

    private lateinit var app: Application

    fun init(application: Application) {
        app = application
    }

    fun getSendTarget(): String {
        if (!::app.isInitialized) {
            return TARGET_ALL
        }
        val v = prefs().getString(KEY_TARGET, TARGET_ALL)
        return if (v == TARGET_WEB || v == TARGET_APP || v == TARGET_ALL) v else TARGET_ALL
    }

    fun setSendTarget(v: String): String {
        val next = when (v) {
            TARGET_WEB, TARGET_APP, TARGET_ALL -> v
            else -> TARGET_ALL
        }
        if (::app.isInitialized) {
            prefs().edit().putString(KEY_TARGET, next).apply()
        }
        return next
    }

    /** 0=只发 App，1=只发兼容，2=发给所有 */
    fun getSendTargetIndex(): Int {
        return when (getSendTarget()) {
            TARGET_WEB -> 1
            TARGET_ALL -> 2
            else -> 0
        }
    }

    fun setSendTargetIndex(index: Int): String {
        return setSendTarget(
            when (index) {
                1 -> TARGET_WEB
                2 -> TARGET_ALL
                else -> TARGET_APP
            }
        )
    }

    fun isWebAppExclusive(): Boolean {
        if (!::app.isInitialized) {
            return false
        }
        return prefs().getBoolean(KEY_EXCLUSIVE, false)
    }

    fun setWebAppExclusive(on: Boolean) {
        if (!::app.isInitialized) {
            return
        }
        prefs().edit().putBoolean(KEY_EXCLUSIVE, on).apply()
    }

    fun getWebBlacklist(): List<String> {
        if (!::app.isInitialized) {
            return emptyList()
        }
        val raw = prefs().getString(KEY_WEB_BLACK, "") ?: ""
        if (raw.isBlank()) {
            return emptyList()
        }
        return raw.split(Regex("[,;\\s]+")).map { normalizeHost(it) }.filter { it.isNotEmpty() }.distinct()
    }

    fun setWebBlacklist(hosts: Collection<String>) {
        if (!::app.isInitialized) {
            return
        }
        val list = hosts.map { normalizeHost(it) }.filter { it.isNotEmpty() }.distinct()
        prefs().edit().putString(KEY_WEB_BLACK, list.joinToString(",")).apply()
        WebHost.dropBlacklisted()
    }

    fun addWebBlacklist(host: String): Boolean {
        if (!::app.isInitialized) {
            return false
        }
        val h = normalizeHost(host)
        if (h.isEmpty()) {
            return false
        }
        val set = LinkedHashSet(getWebBlacklist())
        if (!set.add(h)) {
            return false
        }
        prefs().edit().putString(KEY_WEB_BLACK, set.joinToString(",")).apply()
        WebHost.dropBlacklisted()
        return true
    }

    fun isWebBlacklisted(host: String): Boolean {
        val h = normalizeHost(host)
        return h.isNotEmpty() && getWebBlacklist().contains(h)
    }

    fun normalizeHost(host: String?): String {
        var h = (host ?: "").trim().lowercase()
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length - 1)
        }
        if (h.startsWith("::ffff:")) {
            h = h.substring(7)
        }
        return h
    }

    /**
     * 服务端发送路由。只发 App 绝不回落网页。
     */
    fun routeSend(): Route {
        val appUp = TsServer.isRunningCurrent()
        val alive = if (appUp) TsServer.aliveCountCurrent() else -1
        val appClients = alive > 0
        val web = WebHost.isRunning() && WebHost.hasSession()
        val webProc = WebHost.isRunning()
        val target = getSendTarget()

        if (target == TARGET_WEB) {
            if (web) {
                return Route(app = false, web = true, block = null)
            }
            return Route(false, false, if (webProc) "兼容模式会话已过期" else "兼容模式未开")
        }
        if (target == TARGET_APP) {
            if (appClients) {
                return Route(app = true, web = false, block = null)
            }
            if (!appUp) {
                return Route(false, false, "服务未运行")
            }
            return Route(false, false, "没有已连接的客户端")
        }
        if (!appUp) {
            if (web) {
                return Route(app = false, web = true, block = null)
            }
            return Route(false, false, if (webProc) "兼容模式会话已过期" else "服务未运行")
        }
        if (!appClients && !web) {
            return Route(false, false, if (webProc) "兼容模式会话已过期" else "没有已连接的客户端")
        }
        return Route(appClients, web, null)
    }

    private fun prefs() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
