package xyz.mdhv.asom

import android.app.Application

class AsomApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Runs in every process start, including the one WorkManager creates
        // just to finish a queued download (§1.3: no unledgered egress).
        ServiceLocator.installDownloadLedgerSink()
    }
}
