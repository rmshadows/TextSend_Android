package cn.rmshadows.textsend

import android.app.Application
import android.content.Context

/** 客户端记住上次 PIN 连接（默认关，不记长密钥） */
object ClientConnectionSettings {
    private const val PREF = "textsend_prefs"
    private const val KEY_ENABLED = "client_remember_enabled"
    private const val KEY_IP = "client_last_ip"
    private const val KEY_PORT = "client_last_port"
    private const val KEY_PIN = "client_last_pin"

    private lateinit var app: Application

    fun init(application: Application) {
        app = application
    }

    fun isRememberEnabled(): Boolean {
        if (!::app.isInitialized) {
            return false
        }
        return prefs().getBoolean(KEY_ENABLED, false)
    }

    fun setRememberEnabled(on: Boolean) {
        if (!::app.isInitialized) {
            return
        }
        prefs().edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) {
            clearLast()
        }
    }

    fun getLast(): Triple<String, Int, String>? {
        if (!::app.isInitialized || !isRememberEnabled()) {
            return null
        }
        val ip = prefs().getString(KEY_IP, "")?.trim().orEmpty()
        val port = prefs().getInt(KEY_PORT, 54300)
        val pin = prefs().getString(KEY_PIN, "")?.trim().orEmpty()
        if (ip.isEmpty() || !PinSettings.isValidPin(pin) || port !in 1..65535) {
            return null
        }
        return Triple(ip, port, pin)
    }

    fun saveLast(ip: String, port: Int, pin: String) {
        if (!::app.isInitialized || !isRememberEnabled()) {
            return
        }
        val host = ip.trim()
        val p = pin.trim()
        if (host.isEmpty() || !PinSettings.isValidPin(p) || port !in 1..65535) {
            return
        }
        prefs().edit()
            .putString(KEY_IP, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_PIN, p)
            .apply()
    }

    fun clearLast() {
        if (!::app.isInitialized) {
            return
        }
        prefs().edit()
            .remove(KEY_IP)
            .remove(KEY_PORT)
            .remove(KEY_PIN)
            .apply()
    }

    private fun prefs() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
