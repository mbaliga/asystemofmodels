package xyz.mdhv.asom.widget

import android.app.Activity
import android.os.Bundle
import xyz.mdhv.asom.service.AsomService
import xyz.mdhv.asom.widget.model.DaemonAction
import xyz.mdhv.asom.widget.model.Source

/**
 * The daemon widget's Start/Stop pad. Translucent, shows nothing, finishes at
 * once. It exists because Android 12+ refuses to start a foreground service
 * from the background, while an activity the user just tapped is in the
 * foreground. Not exported: only asom's own PendingIntent reaches it, and the
 * action extra is checked against the closed [DaemonAction] set.
 *
 * Starting here is the user starting the daemon (the same act as the
 * dashboard's Start button or the quick-settings tile); nothing starts unasked.
 */
class WidgetActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            when (DaemonAction.fromWire(intent?.getStringExtra(WidgetLinks.EXTRA_ACTION))) {
                DaemonAction.START -> AsomService.start(this)
                DaemonAction.STOP -> AsomService.stop(this)
                null -> Unit
            }
            WidgetRefresh.request(this, Source.DAEMON)
        }
        finish()
    }
}
