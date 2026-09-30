package xyz.mdhv.asom.desktop.win.power

import java.util.concurrent.atomic.AtomicBoolean
import xyz.mdhv.asom.desktop.BatteryBand
import xyz.mdhv.asom.desktop.KeepAwakeHold
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.PowerPort
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.PowerSource
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.win.WinOptions
import xyz.mdhv.asom.desktop.win.api.PowerRequestApi
import xyz.mdhv.asom.desktop.win.api.SuspendNotificationApi
import xyz.mdhv.asom.desktop.win.api.SystemPowerStatusSource
import xyz.mdhv.asom.desktop.win.api.RawPowerStatus

/**
 * `GetSystemPowerStatus` to the seam's [PowerReading] (windows.md 2.1, [FW12]).
 *  - AC: `ACLineStatus = 1`, or no system battery (`BatteryFlag = 128`) with `ACLineStatus` 1 or 255.
 *  - Battery: `ACLineStatus = 0` with a battery.
 *  - Anything else, including a machine that reports no battery and offline mains (a contradiction), is UNKNOWN, which the
 *    desktop rules treat as a block (desktop/ERRATA.md ERR-POWER-1's spirit, and windows ERRATA WIN-POWER-1).
 *  - `BatteryFlag = 255` (unknown) counts as "has a battery", so an unreadable battery can never look like a desktop.
 *  - Battery saver: `SystemStatusFlag = 1`.
 */
object PowerStatusParser {
    private const val AC_OFFLINE = 0
    private const val AC_ONLINE = 1
    private const val AC_UNKNOWN = 255
    private const val FLAG_CHARGING = 8
    private const val FLAG_NO_BATTERY = 128

    fun parse(raw: RawPowerStatus): PowerReading {
        val noBattery = raw.batteryFlag == FLAG_NO_BATTERY
        val hasBattery = !noBattery
        val source = when {
            raw.acLineStatus == AC_ONLINE -> PowerSource.AC
            noBattery && raw.acLineStatus == AC_UNKNOWN -> PowerSource.AC
            raw.acLineStatus == AC_OFFLINE && hasBattery -> PowerSource.BATTERY
            else -> PowerSource.UNKNOWN
        }
        val percent = raw.batteryLifePercent.takeIf { hasBattery && it in 0..100 }
        val charging = hasBattery && raw.batteryFlag != 255 && (raw.batteryFlag and FLAG_CHARGING) != 0
        return PowerReading(
            source = source,
            charging = charging,
            hasBattery = hasBattery,
            batteryPercent = percent,
            batteryBand = percent?.let { BatteryBand.ofPercent(it) },
            saver = raw.systemStatusFlag.takeIf { it == 0 || it == 1 }?.let { it == 1 },
        )
    }

    /** The reading when the call itself failed: nothing is known, so nothing is assumed. */
    fun unreadable(): PowerReading = PowerReading(PowerSource.UNKNOWN, false, true, null, null, null)
}

/**
 * Keep-awake while SERVING: one power request created with the reason [WinOptions.HOLD_REASON] and set to
 * `PowerRequestSystemRequired`, cleared when the hold is released [FW07][FW08].
 *
 * This cannot prevent user-initiated sleep (power button, lid, Start > Sleep) and does not try to; the sleep watcher
 * drains instead. On a Modern Standby machine on DC power the OS terminates the request itself [FW08]. Windows has no
 * delay/block lock distinction, so both [LockKind]s take the same request.
 *
 * A refused request is a state, not an error loop (linux.md 3.3, as on the other hosts): [hold] then returns a hold that
 * holds nothing, [lastHoldError] says why, and `asom doctor` shows it. The node keeps working; the machine may sleep.
 */
class WinPowerPort(
    private val status: SystemPowerStatusSource,
    private val requests: PowerRequestApi,
    private val suspend: SuspendNotificationApi,
) : PowerPort {
    @Volatile
    var lastHoldError: String? = null
        private set

    @Volatile
    private var active: Int = 0

    /** Number of holds that actually hold a power request right now. */
    val activeHolds: Int get() = active

    override fun read(): PowerReading = try {
        status.read()?.let(PowerStatusParser::parse) ?: PowerStatusParser.unreadable()
    } catch (_: Exception) {
        PowerStatusParser.unreadable()
    }

    override fun hold(kind: LockKind): KeepAwakeHold {
        val handle = try {
            val h = requests.create(WinOptions.HOLD_REASON)
            try {
                h.setSystemRequired()
            } catch (e: Exception) {
                runCatching { h.close() }
                throw e
            }
            h
        } catch (e: Exception) {
            lastHoldError = e.message ?: e::class.simpleName
            return object : KeepAwakeHold {
                override val kind: LockKind = kind
                override fun release() {}
            }
        }
        lastHoldError = null
        synchronized(this) { active++ }
        val released = AtomicBoolean(false)
        return object : KeepAwakeHold {
            override val kind: LockKind = kind
            override fun release() {
                if (!released.compareAndSet(false, true)) return
                try {
                    handle.clearSystemRequired()
                } finally {
                    try {
                        handle.close()
                    } finally {
                        synchronized(this@WinPowerPort) { active-- }
                    }
                }
            }
        }
    }

    override fun onSleepEvents(listener: (SleepEvent) -> Unit): AutoCloseable = SuspendWatcher(suspend).watch(listener)
}
