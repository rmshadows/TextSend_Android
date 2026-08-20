package cn.rmshadows.textsend

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 可选后台保活。默认关；打开且会话进行中才起前台服务。
 */
object KeepAlive {
    private const val PREF = "textsend_prefs"
    private const val KEY = "keep_alive"

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

    fun sync() {
        if (!::app.isInitialized) {
            return
        }
        val i = Intent(app, KeepAliveService::class.java)
        if (isEnabled() && sessionActive) {
            ContextCompat.startForegroundService(app, i)
        } else {
            app.stopService(i)
        }
    }
}
