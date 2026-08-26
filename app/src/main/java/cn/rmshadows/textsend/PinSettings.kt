package cn.rmshadows.textsend

import android.app.Application
import android.content.Context

/** 服务端固定 PIN（默认关，每次随机） */
object PinSettings {
    private const val PREF = "textsend_prefs"
    private const val KEY_ENABLED = "custom_pin_enabled"
    private const val KEY_PIN = "custom_pin"

    private lateinit var app: Application

    fun init(application: Application) {
        app = application
    }

    fun isValidPin(pin: String?): Boolean {
        return pin != null && pin.matches(Regex("^\\d{8}$"))
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
    }

    fun getPin(): String {
        if (!::app.isInitialized) {
            return ""
        }
        val pin = prefs().getString(KEY_PIN, "") ?: ""
        return if (isValidPin(pin)) pin else ""
    }

    fun setPin(pin: String) {
        if (!::app.isInitialized) {
            return
        }
        val s = pin.trim()
        if (s.isNotEmpty() && !isValidPin(s)) {
            return
        }
        prefs().edit().putString(KEY_PIN, s).apply()
    }

    private fun prefs() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
