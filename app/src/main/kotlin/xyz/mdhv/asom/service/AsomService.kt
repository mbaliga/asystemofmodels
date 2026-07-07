package xyz.mdhv.asom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.server.AsomServer
import xyz.mdhv.asom.ui.MainActivity

/**
 * Foreground service hosting the asom daemon (brief P5): FGS type
 * `specialUse` with the manifest property declaration (§3). Nothing runs
 * unless the user starts it (boot-start is a later, default-OFF toggle, §11 P8).
 */
class AsomService : Service() {

    private var server: AsomServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("serving on 127.0.0.1:${Asom.DEFAULT_PORT}"), type)

        server = AsomServer(ServiceLocator.serverConfig()).also { it.start(wait = false) }
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "asom daemon", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("asom")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "asom-daemon"
        const val NOTIFICATION_ID = 1

        /** Observed by the dashboard Status tab. */
        val running = MutableStateFlow(false)

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, AsomService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, AsomService::class.java))
        }
    }
}
