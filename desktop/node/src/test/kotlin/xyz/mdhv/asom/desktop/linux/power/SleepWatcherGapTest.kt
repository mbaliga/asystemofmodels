package xyz.mdhv.asom.desktop.linux.power

import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.linux.LawCounter

/** The clock-gap fallback (LA25) with fake clocks: `/proc/uptime` grows over a suspend, the monotonic clock does not. */
class SleepWatcherGapTest {
    private class Clocks(var uptime: Long? = 1_000_000, var mono: Long = 5_000)

    private fun watcher(c: Clocks, out: MutableList<SleepEvent>, onEvent: (SleepEvent) -> Unit = {}): SleepWatcher =
        SleepWatcher(
            busSocket = Path.of("/nonexistent/asom-no-bus"),
            uid = { 0 },
            listener = { out += it; onEvent(it) },
            connector = { _, _ -> throw IOException("no bus in this test") },
            uptimeMs = { c.uptime },
            monotonicMs = { c.mono },
            retryMs = 3_600_000,
            gapPollMs = 3_600_000,
            gapThresholdMs = 10_000,
            announceWindowMs = 15_000,
        ).start()

    private fun advance(c: Clocks, awakeMs: Long, suspendedMs: Long) {
        c.mono += awakeMs
        c.uptime = c.uptime?.plus(awakeMs + suspendedMs)
    }

    @Test
    fun `gap law, the threshold, announced sleeps and unreadable uptime`() {
        val laws = LawCounter(listOf("no-gap-quiet", "unannounced-gap-emits-pair", "threshold-exact", "announced-gap-no-duplicate", "stale-announce-does-not-mask", "uptime-unreadable", "listener-exception-survived"))

        // no gap: quiet
        run {
            val c = Clocks(); val ev = mutableListOf<SleepEvent>()
            watcher(c, ev).use { w -> repeat(5) { advance(c, 5_000, 0); w.pollGap() } }
            assertEquals(emptyList(), ev); laws.hit("no-gap-quiet")
        }
        // unannounced 60 s suspend
        run {
            val c = Clocks(); val ev = mutableListOf<SleepEvent>()
            watcher(c, ev).use { w -> advance(c, 5_000, 60_000); w.pollGap(); advance(c, 5_000, 0); w.pollGap() }
            assertEquals(listOf(SleepEvent.SLEEP_IMMINENT, SleepEvent.RESUMED), ev); laws.hit("unannounced-gap-emits-pair")
        }
        // exactly at the threshold: not enough; one millisecond over: enough
        run {
            val c = Clocks(); val ev = mutableListOf<SleepEvent>()
            watcher(c, ev).use { w -> advance(c, 5_000, 10_000); w.pollGap() }
            assertEquals(emptyList(), ev, "a gap of exactly 10,000 ms is not a suspend")
            val c2 = Clocks(); val ev2 = mutableListOf<SleepEvent>()
            watcher(c2, ev2).use { w -> advance(c2, 5_000, 10_001); w.pollGap() }
            assertEquals(2, ev2.size, "a gap of 10,001 ms is a suspend"); laws.hit("threshold-exact")
        }
        // announced by the bus: the signal path reports it; the gap poll adds nothing
        run {
            val c = Clocks(); val ev = mutableListOf<SleepEvent>()
            watcher(c, ev).use { w ->
                w.onSignal(true)
                advance(c, 2_000, 60_000)
                w.pollGap()
                w.onSignal(false)
                advance(c, 5_000, 0); w.pollGap()
            }
            assertEquals(listOf(SleepEvent.SLEEP_IMMINENT, SleepEvent.RESUMED), ev, "exactly one pair"); laws.hit("announced-gap-no-duplicate")
        }
        // an announcement long before the poll window does not hide a later unannounced suspend
        run {
            val c = Clocks(); val ev = mutableListOf<SleepEvent>()
            watcher(c, ev).use { w ->
                w.onSignal(true); w.onSignal(false)
                ev.clear()
                advance(c, 100_000, 0); w.pollGap()
                advance(c, 5_000, 60_000); w.pollGap()
            }
            assertEquals(listOf(SleepEvent.SLEEP_IMMINENT, SleepEvent.RESUMED), ev); laws.hit("stale-announce-does-not-mask")
        }
        // uptime unreadable: nothing is invented, and the poll after it does not compare against a missing baseline
        run {
            val c = Clocks(); val ev = mutableListOf<SleepEvent>()
            watcher(c, ev).use { w ->
                c.uptime = null; advance(c, 5_000, 0); w.pollGap()
                c.uptime = 9_000_000; advance(c, 5_000, 0); w.pollGap()
            }
            assertEquals(emptyList(), ev); laws.hit("uptime-unreadable")
        }
        // a throwing listener does not stop the watcher
        run {
            val c = Clocks(); val ev = mutableListOf<SleepEvent>()
            watcher(c, ev) { error("listener bug") }.use { w ->
                advance(c, 5_000, 60_000); w.pollGap()
                advance(c, 5_000, 60_000); w.pollGap()
            }
            assertEquals(4, ev.size); laws.hit("listener-exception-survived")
        }
        laws.assertAllExercised("SleepWatcherGap")
    }

    @Test
    fun `uptime parsing`() {
        assertEquals(12_345_670L, ProcUptime.parse("12345.67 5432.10\n"))
        assertEquals(0L, ProcUptime.parse("0.00 0.00"))
        assertNull(ProcUptime.parse(""))
        assertNull(ProcUptime.parse(null))
        assertNull(ProcUptime.parse("banana 1"))
        assertNull(ProcUptime.parse("-5.0 1"))
        assertNull(ProcUptime.parse("NaN 1"))
        assertNull(ProcUptime.parse("Infinity 1"))
    }
}
