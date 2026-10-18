package xyz.mdhv.asom.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.mdhv.asom.Settings

/**
 * Boot-start toggle (brief P8): default OFF — nothing runs unless the user
 * explicitly starts it or has explicitly enabled this toggle beforehand.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings(context).bootStartEnabled) {
            AsomService.start(context)
        }
    }
}
