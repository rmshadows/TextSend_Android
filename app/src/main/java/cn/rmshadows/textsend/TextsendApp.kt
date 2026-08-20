package cn.rmshadows.textsend

import android.app.Application

class TextsendApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KeepAlive.init(this)
    }
}
