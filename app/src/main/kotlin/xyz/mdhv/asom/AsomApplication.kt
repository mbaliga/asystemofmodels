package xyz.mdhv.asom

import android.app.Application

class AsomApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
