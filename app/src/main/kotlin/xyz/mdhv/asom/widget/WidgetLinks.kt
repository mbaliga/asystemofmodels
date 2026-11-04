package xyz.mdhv.asom.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import xyz.mdhv.asom.ui.MainActivity
import xyz.mdhv.asom.ui.PairingActivity
import xyz.mdhv.asom.widget.model.DaemonAction
import xyz.mdhv.asom.widget.model.Destination
import xyz.mdhv.asom.widget.model.RequestCodes

/**
 * Widget taps. Every intent is explicit (asom's own components), immutable,
 * and carries only a destination or an action name, which the receiving side
 * validates against a closed set. No URL schemes.
 */
object WidgetLinks {

    /** A [Destination.wire] naming the dashboard tab to open. */
    const val EXTRA_DESTINATION = "xyz.mdhv.asom.widget.extra.DESTINATION"

    /** A [DaemonAction.wire] for [WidgetActionActivity]. */
    const val EXTRA_ACTION = "xyz.mdhv.asom.widget.extra.ACTION"

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun open(context: Context, widgetId: Int, destination: Destination): PendingIntent {
        val intent = if (destination == Destination.CONSENT) {
            // The consent sheet shows only binder-verified pending rows (§5.7);
            // nothing in this intent is read by it.
            Intent(context, PairingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination.wire)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(context, RequestCodes.open(widgetId), intent, FLAGS)
    }

    /**
     * Start/Stop go through a translucent activity rather than a broadcast:
     * Android 12+ refuses to start a foreground service from the background,
     * and an activity the user just tapped is in the foreground.
     */
    fun action(context: Context, widgetId: Int, action: DaemonAction): PendingIntent {
        val intent = Intent(context, WidgetActionActivity::class.java)
            .putExtra(EXTRA_ACTION, action.wire)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, RequestCodes.action(widgetId, action), intent, FLAGS)
    }
}
