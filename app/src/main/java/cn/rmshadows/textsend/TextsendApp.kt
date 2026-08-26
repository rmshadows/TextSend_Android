package cn.rmshadows.textsend

import android.app.Application
import protocol.FileNames

class TextsendApp : Application() {
    override fun onCreate() {
        ThemeSettings.applyNightMode(ThemeSettings.get(this))
        super.onCreate()
        FileNames.init(this)
        KeepAlive.init(this)
        PinSettings.init(this)
        ClientConnectionSettings.init(this)
    }
}
