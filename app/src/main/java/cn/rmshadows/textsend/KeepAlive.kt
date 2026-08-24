package cn.rmshadows.textsend

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 会话进行中自动拉前台通知，避免进相册/切后台时被系统掐掉连接。
 * 设置里的开关只申请安卓 13+ 通知权限，关不掉保活本身。
 */
object KeepAlive {
    private const val PREF = "textsend_prefs"
    private const val KEY = "keep_alive"
    private const val KEY_QR_AUTO = "qr_auto_connect"

    private lateinit var app: Application

    @Volatile
    var sessionActive: Boolean = false
        set(value) {
            field = value
            sync()
        }

    fun init(application: Application) {
        app = application
    }

    fun isEnabled(): Boolean {
        if (!::app.isInitialized) {
            return false
        }
        return app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun setEnabled(on: Boolean) {
        if (!::app.isInitialized) {
            return
        }
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        sync()
    }

    /** 扫码成功后自动连接。默认开。 */
    fun isQrAutoConnect(): Boolean {
        if (!::app.isInitialized) {
            return true
        }
        return app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_QR_AUTO, true)
    }

    fun setQrAutoConnect(on: Boolean) {
        if (!::app.isInitialized) {
            return
        }
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_QR_AUTO, on).apply()
    }

    fun sync() {
        if (!::app.isInitialized) {
            return
        }
        val i = Intent(app, KeepAliveService::class.java)
        try {
            // 会话进行中必须拉前台服务：选相册会把主界面停掉，努比亚等机会直接掐掉局域网套接字。
            if (sessionActive) {
                ContextCompat.startForegroundService(app, i)
            } else {
                app.stopService(i)
            }
        } catch (_: Exception) {
        }
    }
}
