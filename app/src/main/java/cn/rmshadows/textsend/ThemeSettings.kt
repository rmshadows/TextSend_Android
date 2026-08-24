package cn.rmshadows.textsend

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** 设置里选的配色。四个主题共用同一套排版。 */
object ThemeSettings {
    const val DEFAULT = "default"
    const val DARK = "dark"
    const val PAPER = "paper"
    const val SLATE = "slate"

    private const val PREF = "textsend_prefs"
    private const val KEY = "ui_theme"

    fun get(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT)
            ?: DEFAULT
    }

    fun styleRes(context: Context): Int {
        return when (get(context)) {
            DARK -> R.style.Theme_Textsend_Dark
            PAPER -> R.style.Theme_Textsend_Paper
            SLATE -> R.style.Theme_Textsend_Slate
            else -> R.style.Theme_Textsend
        }
    }

    fun applyTo(activity: Activity) {
        activity.setTheme(styleRes(activity))
    }

    /** 只在 Application 启动时调用，不要在 Activity.onCreate 里改夜间模式。 */
    fun applyNightMode(id: String) {
        val mode = nightModeOf(id)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    /**
     * 换主题后一定 recreate。只改夜间模式常量时（默认↔纸墨都是浅色），
     * AppCompat 不会自己重建，必须我们来，否则点了没反应。
     */
    fun applyChoice(activity: Activity, id: String) {
        val next = normalize(id)
        if (next == get(activity)) {
            return
        }
        activity.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, next)
            .commit()
        applyNightMode(next)
        activity.recreate()
    }

    private fun nightModeOf(id: String): Int {
        return when (normalize(id)) {
            DARK, SLATE -> AppCompatDelegate.MODE_NIGHT_YES
            PAPER -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    private fun normalize(id: String): String {
        return when (id) {
            DARK, PAPER, SLATE -> id
            else -> DEFAULT
        }
    }
}
