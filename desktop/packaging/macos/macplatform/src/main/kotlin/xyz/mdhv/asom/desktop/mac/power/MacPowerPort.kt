package xyz.mdhv.asom.desktop.mac.power

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import xyz.mdhv.asom.desktop.BatteryBand
import xyz.mdhv.asom.desktop.KeepAwakeHold
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.PowerPort
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.PowerSource
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HValue

/**
 * `power.get` to the seam's [PowerReading] (macos.md 2.1, 3.5).
 *  - `source` `ac` is AC and `battery` is battery (the helper maps a UPS to `battery`). An unreadable source is an error reply, which
 *    becomes UNKNOWN, which the desktop rules treat as a block.
 *  - A Mac has a battery when it reports a battery level, OR when it is running on battery power at all: a desktop Mac on a UPS
 *    reports `battery` with no battery level, and it must not look like an always-on desktop (mac ERRATA MAC-POWER-1).
 *  - The percentage is the level rounded DOWN, so a band is never higher than the truth.
 *  - `lowPower` is Low Power Mode, which is a condition to serve (macos.md 2.1); it maps to `saver`.
 */
object PowerMapping {
    fun map(p: HelperClient.PowerInfo): PowerReading {
        val source = when (p.source) {
            "ac" -> PowerSource.AC
            "battery" -> PowerSource.BATTERY
            else -> PowerSource.UNKNOWN
        }
        val percent = p.batteryPermille?.let { it / 10 }
        val hasBattery = p.batteryPermille != null || source == PowerSource.BATTERY
        return PowerReading(
            source = source,
            charging = p.charging,
            hasBattery = hasBattery,
            batteryPercent = percent,
            batteryBand = percent?.let { BatteryBand.ofPercent(it) },
            saver = p.lowPower,
        )
    }

    /** The reading when the helper cannot answer: nothing is known, so nothing is assumed (and a battery is presumed, so it blocks). */
    fun unreadable(): PowerReading = PowerReading(PowerSource.UNKNOWN, false, true, null, null, null)
}

/**
 * Power for the node: readings, the keep-awake hold, and sleep notifications, all through the helper.
 *
 * Keep-awake while SERVING is ONE assertion named `asom: lending compute to your paired devices`, `PreventUserIdleSystemSleep`,
 * never `PreventSystemSleep` (macos.md 2.1). Several holds share it: the helper is asked for it on the first hold and told to
 * release it on the last release. macOS has no delay or block lock distinction, so both [LockKind]s take the same assertion.
 * It cannot prevent forced sleep (lid, Apple menu, low battery, thermal emergency) and does not try to; the sleep watcher drains.
 * A refused hold is a state, not an error loop (linux.md 3.3, as on the other hosts): [hold] then returns a hold that holds
 * nothing and [lastHoldError] says why.
 *
 * Sleep: `ev:"sleep.will"` becomes `SLEEP_IMMINENT` for the listener (which closes the listener socket, ends in-flight streams and
 * writes outcome rows), and the node answers `sleep.ack` for that token when the listener returns OR after [ackBudgetMs]
 * (2 s), whichever is first: the user closed the lid expecting sleep, and holding a hot laptop awake for the 30 s macOS allows is
 * the wrong trade (macos.md 2.1). Exactly one ack per token. `ev:"wake"` becomes `RESUMED`.
 */
class MacPowerPort(
    private val client: HelperClient,
    private val ackBudgetMs: Long = ACK_BUDGET_MS,
    private val executor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "asom-mac-sleep").also { it.isDaemon = true } },
) : PowerPort {
    @Volatile
    var lastHoldError: String? = null
        private set

    @Volatile
    var lastReadError: String? = null
        private set

    private val holds = AtomicInteger()
    private val lock = Any()

    /** Holds that currently share the helper's assertion. */
    val activeHolds: Int get() = holds.get()

    /** Sleep acknowledgements sent so far (for the tests and `asom doctor`). */
    val acksSent = AtomicInteger()

    override fun read(): PowerReading = try {
        PowerMapping.map(client.power()).also { lastReadError = null }
    } catch (e: Exception) {
        lastReadError = e.message ?: e::class.simpleName
        PowerMapping.unreadable()
    }

    override fun hold(kind: LockKind): KeepAwakeHold {
        val ok = synchronized(lock) {
            try {
                if (holds.get() == 0) client.assertHold()
                holds.incrementAndGet()
                lastHoldError = null
                true
            } catch (e: Exception) {
                lastHoldError = e.message ?: e::class.simpleName
                false
            }
        }
        if (!ok) {
            return object : KeepAwakeHold {
                override val kind: LockKind = kind
                override fun release() {}
            }
        }
        val released = AtomicBoolean(false)
        return object : KeepAwakeHold {
            override val kind: LockKind = kind
            override fun release() {
                if (!released.compareAndSet(false, true)) return
                synchronized(lock) {
                    if (holds.decrementAndGet() == 0) {
                        try {
                            client.assertRelease()
                        } catch (_: Exception) {
                            // A lost helper has released its assertion by dying.
                        }
                    }
                }
            }
        }
    }

    override fun onSleepEvents(listener: (SleepEvent) -> Unit): AutoCloseable {
        val closed = AtomicBoolean(false)
        val sub = client.subscribe { ev ->
            if (closed.get()) return@subscribe
            when (ev.name) {
                "sleep.will" -> {
                    val token = (ev.fields["token"] as HValue.I).v
                    executor.execute { drainAndAck(token, listener) }
                }
                "wake" -> executor.execute { runCatching { listener(SleepEvent.RESUMED) } }
            }
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) sub.close()
        }
    }

    private fun drainAndAck(token: Long, listener: (SleepEvent) -> Unit) {
        val drain = CompletableFuture.runAsync({ runCatching { listener(SleepEvent.SLEEP_IMMINENT) } }, executor)
        try {
            drain.get(ackBudgetMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // The budget is spent: the helper is told to let the Mac sleep even though the drain is still running.
        } catch (_: Exception) {
        }
        try {
            client.sleepAck(token)
            acksSent.incrementAndGet()
        } catch (_: Exception) {
            // The helper allows the change itself after its own 2 s timeout.
        }
    }

    companion object {
        /** macos.md 2.1: the helper calls `IOAllowPowerChange` after this long whether or not the node answered. */
        const val ACK_BUDGET_MS = 2_000L
    }
}
