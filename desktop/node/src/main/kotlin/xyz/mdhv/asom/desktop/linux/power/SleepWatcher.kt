package xyz.mdhv.asom.desktop.linux.power

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.linux.dbus.MiniDbus

enum class SleepWatcherStatus { STARTING, CONNECTED, UNAVAILABLE }

/**
 * `PrepareForSleep` from logind becomes SLEEP_IMMINENT and RESUMED (linux.md 3.3). Two sources:
 *  - the announced path, the bus signal, seen through [MiniDbus] (reconnects every [retryMs] when the bus is down);
 *  - the fallback (LA25): `/proc/uptime` counts suspended time and `System.nanoTime` does not, so when the first grows
 *    more than [gapThresholdMs] beyond the second between two polls and NO signal announced that sleep, the node
 *    reports SLEEP_IMMINENT then RESUMED at once, which closes every session (in-flight requesters fail over on their
 *    own timeouts).
 * A sleep-caused drain is not a presence drain (LP-2's hold-down does not apply); that is the FSM's rule, not this class's.
 * [listener] runs on this class's threads, one event at a time, and must return quickly; an exception from it is swallowed.
 */
class SleepWatcher(
    private val busSocket: Path,
    private val uid: () -> Int,
    private val listener: (SleepEvent) -> Unit,
    private val connector: (Path, Int) -> MiniDbus = { p, u -> MiniDbus.connect(p, u) },
    private val uptimeMs: () -> Long? = { ProcUptime.read() },
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val retryMs: Long = 30_000,
    private val gapPollMs: Long = 5_000,
    private val gapThresholdMs: Long = 10_000,
    private val announceWindowMs: Long = 15_000,
) : AutoCloseable {
    @Volatile
    var status: SleepWatcherStatus = SleepWatcherStatus.STARTING
        private set

    /** The words `asom status` shows (linux.md 7.3 failure mode). */
    val statusText: String
        get() = if (status == SleepWatcherStatus.CONNECTED) "sleep is detected in advance" else "sleep not detected in advance"

    @Volatile
    private var closed = false
    private val lock = Any()
    private var sleeping = false
    private var lastAnnounceMono: Long? = null
    private var baseUptime: Long? = null
    private var baseMono: Long = 0
    @Volatile
    private var connection: MiniDbus? = null
    private var busThread: Thread? = null
    private var gapTimer: ScheduledExecutorService? = null

    fun start(): SleepWatcher {
        check(busThread == null) { "already started" }
        baseUptime = uptimeMs()
        baseMono = monotonicMs()
        busThread = Thread(::busLoop, "asom-sleep-watcher").apply { isDaemon = true; start() }
        gapTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "asom-sleep-gap").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ pollGap() }, gapPollMs, gapPollMs, TimeUnit.MILLISECONDS)
        }
        return this
    }

    private fun busLoop() {
        while (!closed) {
            try {
                val conn = connector(busSocket, uid())
                connection = conn
                if (closed) {
                    conn.close()
                    return
                }
                status = SleepWatcherStatus.CONNECTED
                resumeIfStuckSleeping()
                while (!closed) {
                    val start = conn.nextPrepareForSleep() ?: break
                    onSignal(start)
                }
                conn.close()
            } catch (_: Exception) {
                // an unreachable, hostile or broken bus is a state, not a crash
            }
            connection = null
            if (closed) return
            status = SleepWatcherStatus.UNAVAILABLE
            try {
                Thread.sleep(retryMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** The announced path; internal so a test can drive it without a bus. */
    internal fun onSignal(start: Boolean) {
        synchronized(lock) {
            if (start) {
                lastAnnounceMono = monotonicMs()
                if (!sleeping) {
                    sleeping = true
                    emit(SleepEvent.SLEEP_IMMINENT)
                }
            } else {
                sleeping = false
                emit(SleepEvent.RESUMED)
            }
        }
    }

    /** We are running, so the machine is awake: a RESUMED that was lost with a dropped bus connection is made up now. */
    private fun resumeIfStuckSleeping() {
        synchronized(lock) {
            if (sleeping) {
                sleeping = false
                emit(SleepEvent.RESUMED)
            }
        }
    }

    /** One poll of the clock-gap fallback. Public for the tests, which drive it with fake clocks. */
    fun pollGap() {
        synchronized(lock) {
            val u = uptimeMs()
            val m = monotonicMs()
            val u0 = baseUptime
            val m0 = baseMono
            baseUptime = u
            baseMono = m
            if (u == null || u0 == null) return
            val gap = (u - u0) - (m - m0)
            if (gap <= gapThresholdMs) return
            val announced = lastAnnounceMono?.let { it >= m0 - announceWindowMs } ?: false
            if (announced) return
            sleeping = true
            emit(SleepEvent.SLEEP_IMMINENT)
            sleeping = false
            emit(SleepEvent.RESUMED)
        }
    }

    private fun emit(e: SleepEvent) {
        try {
            listener(e)
        } catch (_: Exception) {
            // the watcher must survive a bad listener
        }
    }

    override fun close() {
        closed = true
        gapTimer?.shutdownNow()
        connection?.close()
        busThread?.interrupt()
        busThread?.join(2_000)
    }
}

object ProcUptime {
    /** `/proc/uptime` first field (seconds, with a fraction) in milliseconds; null when unreadable or malformed. */
    fun parse(text: String?): Long? {
        val first = text?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: return null
        val seconds = first.toDoubleOrNull() ?: return null
        if (seconds < 0 || seconds.isNaN() || seconds.isInfinite()) return null
        return (seconds * 1000.0).toLong()
    }

    fun read(path: Path = Path.of("/proc/uptime")): Long? = try {
        parse(Files.readString(path, Charsets.UTF_8))
    } catch (_: IOException) {
        null
    }
}
