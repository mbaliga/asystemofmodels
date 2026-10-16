package xyz.mdhv.asom.desktop.win.power

import java.util.concurrent.atomic.AtomicBoolean
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.win.api.SuspendNotificationApi
import xyz.mdhv.asom.desktop.win.api.SuspendSignal

/**
 * `PBT_APMSUSPEND` and the console display turning off become `os_sleep_imminent`; resume and the display turning on
 * become `resumed` (windows.md 2.1, 3.4; [FW10], AW18).
 *
 * Display-off drains on EVERY Windows machine, including a desktop on S3 where nothing suspends. That is the conservative
 * reading of R3-OVERCLAIM-8, which found the spec asks for both "serve through idle display-off" and "display-off is a
 * hard drain"; the drain is chosen, and serving through display-off (service mode on AC only) is NOT implemented. A dimmed
 * display is not a drain. Consequence, stated in ERRATA WIN-SLEEP-1: with a display timeout shorter than the presence
 * idle threshold the lending window can be empty.
 */
class SuspendWatcher(private val api: SuspendNotificationApi) {
    fun watch(listener: (SleepEvent) -> Unit): AutoCloseable {
        val closed = AtomicBoolean(false)
        val handle = api.register { signal ->
            if (!closed.get()) {
                map(signal)?.let { event ->
                    try {
                        listener(event)
                    } catch (_: Exception) {
                        // A listener that throws must not unwind into an OS callback thread.
                    }
                }
            }
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) handle.close()
        }
    }

    companion object {
        fun map(signal: SuspendSignal): SleepEvent? = when (signal) {
            SuspendSignal.SUSPEND, SuspendSignal.DISPLAY_OFF -> SleepEvent.SLEEP_IMMINENT
            SuspendSignal.RESUME_SUSPEND, SuspendSignal.RESUME_AUTOMATIC, SuspendSignal.DISPLAY_ON -> SleepEvent.RESUMED
            SuspendSignal.DISPLAY_DIMMED -> null
        }
    }
}
