package xyz.mdhv.asom.desktop.win

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.BatteryBand
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.PowerSource
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.win.api.RawPowerStatus
import xyz.mdhv.asom.desktop.win.api.SuspendSignal
import xyz.mdhv.asom.desktop.win.fakes.FakePowerRequests
import xyz.mdhv.asom.desktop.win.fakes.FakePowerStatus
import xyz.mdhv.asom.desktop.win.fakes.FakeSuspendApi
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.jna.PowerBroadcastDecoder
import xyz.mdhv.asom.desktop.win.power.PowerStatusParser
import xyz.mdhv.asom.desktop.win.power.SuspendWatcher
import xyz.mdhv.asom.desktop.win.power.WinPowerPort

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PowerTest {
    private val laws = LawCounter(
        listOf(
            "status-table", "hold-reason-and-type", "hold-release-clears", "hold-double-release", "hold-refused-is-a-state",
            "hold-set-failure-closes", "suspend-mapping", "suspend-unsubscribes", "suspend-listener-exception-contained",
            "broadcast-guid", "broadcast-display-decode", "broadcast-rejects-noise",
        ),
    )

    @Test
    fun `GetSystemPowerStatus values map exactly as windows dot md 2 dot 1 says`() {
        fun p(ac: Int, flag: Int, pct: Int = 255, sys: Int = 0) = PowerStatusParser.parse(RawPowerStatus(ac, flag, pct, sys))
        // desktop: no battery, mains 1 or 255 -> AC and NOT a battery machine
        for (ac in listOf(1, 255)) {
            val r = p(ac, 128)
            assertEquals(PowerSource.AC, r.source, "ac=$ac")
            assertFalse(r.hasBattery)
            assertNull(r.batteryBand)
            laws.hit("status-table")
        }
        // a desktop that reports offline mains is a contradiction: unknown, which blocks
        assertEquals(PowerSource.UNKNOWN, p(0, 128).source)
        // laptop on AC, charging, 87 %
        val lap = p(1, 9, 87)
        assertEquals(PowerSource.AC, lap.source)
        assertTrue(lap.hasBattery && lap.charging)
        assertEquals(87, lap.batteryPercent)
        assertEquals(BatteryBand.GE80, lap.batteryBand)
        // laptop on battery
        val bat = p(0, 1, 60)
        assertEquals(PowerSource.BATTERY, bat.source)
        assertFalse(bat.charging)
        assertEquals(BatteryBand.B50_79, bat.batteryBand)
        // a laptop whose mains state is unknown must not look like a desktop: 255 with a battery is unknown
        assertEquals(PowerSource.UNKNOWN, p(255, 1, 50).source)
        // a battery flag of 255 (unknown) still counts as "has a battery"
        val unk = p(255, 255)
        assertTrue(unk.hasBattery)
        assertEquals(PowerSource.UNKNOWN, unk.source)
        assertFalse(unk.charging)
        // percentage 255 = unknown, out of range dropped
        assertNull(p(1, 9, 255).batteryPercent)
        assertNull(p(1, 9, 101).batteryPercent)
        // battery saver
        assertEquals(true, p(0, 1, 40, 1).saver)
        assertEquals(false, p(0, 1, 40, 0).saver)
        assertNull(p(0, 1, 40, 7).saver)
        assertEquals(PowerSource.UNKNOWN, PowerStatusParser.unreadable().source)
        assertTrue(PowerStatusParser.unreadable().hasBattery, "unreadable must never look like a desktop")
        laws.hit("status-table")
    }

    @Test
    fun `read returns unknown when the call fails or throws`() {
        val port = WinPowerPort(FakePowerStatus(null), FakePowerRequests(), FakeSuspendApi())
        assertEquals(PowerSource.UNKNOWN, port.read().source)
        val throwing = WinPowerPort(object : xyz.mdhv.asom.desktop.win.api.SystemPowerStatusSource { override fun read() = error("boom") }, FakePowerRequests(), FakeSuspendApi())
        assertEquals(PowerSource.UNKNOWN, throwing.read().source)
    }

    @Test
    fun `hold creates one request with the spec's reason, sets SystemRequired, and release clears and closes it`() {
        val reqs = FakePowerRequests()
        val port = WinPowerPort(FakePowerStatus(), reqs, FakeSuspendApi())
        val h = port.hold(LockKind.BLOCK)
        assertEquals(1, reqs.requests.size)
        assertEquals("asom: lending compute to your paired devices", reqs.requests[0].reason)
        assertTrue(reqs.requests[0].required)
        assertEquals(1, port.activeHolds)
        assertEquals(LockKind.BLOCK, h.kind)
        laws.hit("hold-reason-and-type")
        h.release()
        assertFalse(reqs.requests[0].required, "cleared")
        assertTrue(reqs.requests[0].closed, "closed")
        assertEquals(0, port.activeHolds)
        laws.hit("hold-release-clears")
        h.release()
        assertEquals(0, port.activeHolds, "a second release must not go negative")
        laws.hit("hold-double-release")
    }

    @Test
    fun `a refused power request is a state, not an error loop, and set failure closes the handle`() {
        val reqs = FakePowerRequests().also { it.refuse = "Access is denied" }
        val port = WinPowerPort(FakePowerStatus(), reqs, FakeSuspendApi())
        val h = port.hold(LockKind.DELAY)
        h.release()
        assertEquals("Access is denied", port.lastHoldError)
        assertEquals(0, port.activeHolds)
        laws.hit("hold-refused-is-a-state")
        reqs.refuse = null
        reqs.failOnSet = true
        port.hold(LockKind.DELAY)
        assertTrue(reqs.requests.single().closed, "a request that could not be set must not leak")
        assertEquals("PowerSetRequest failed", port.lastHoldError)
        assertEquals(0, port.activeHolds)
        laws.hit("hold-set-failure-closes")
        reqs.failOnSet = false
        port.hold(LockKind.DELAY)
        assertEquals(null, port.lastHoldError, "a later success clears the error")
    }

    @Test
    fun `suspend and display-off drain, resume and display-on resume, dimming is neither`() {
        val table = mapOf(
            SuspendSignal.SUSPEND to SleepEvent.SLEEP_IMMINENT,
            SuspendSignal.DISPLAY_OFF to SleepEvent.SLEEP_IMMINENT,
            SuspendSignal.RESUME_SUSPEND to SleepEvent.RESUMED,
            SuspendSignal.RESUME_AUTOMATIC to SleepEvent.RESUMED,
            SuspendSignal.DISPLAY_ON to SleepEvent.RESUMED,
            SuspendSignal.DISPLAY_DIMMED to null,
        )
        assertEquals(SuspendSignal.entries.toSet(), table.keys, "every signal is classified")
        for ((sig, ev) in table) {
            assertEquals(ev, SuspendWatcher.map(sig), "$sig")
            laws.hit("suspend-mapping")
        }
        val api = FakeSuspendApi()
        val seen = ArrayList<SleepEvent>()
        val handle = WinPowerPort(FakePowerStatus(), FakePowerRequests(), api).onSleepEvents { seen.add(it) }
        api.keepCallback()
        api.fire(SuspendSignal.SUSPEND)
        api.fire(SuspendSignal.DISPLAY_DIMMED)
        api.fire(SuspendSignal.DISPLAY_ON)
        assertEquals(listOf(SleepEvent.SLEEP_IMMINENT, SleepEvent.RESUMED), seen)
        handle.close()
        handle.close()
        assertEquals(1, api.unregistrations, "close is idempotent")
        api.fire(SuspendSignal.SUSPEND)
        assertEquals(2, seen.size, "a callback already in flight after close must not reach the listener")
        laws.hit("suspend-unsubscribes")
    }

    @Test
    fun `a listener that throws never unwinds into the OS callback`() {
        val api = FakeSuspendApi()
        var calls = 0
        SuspendWatcher(api).watch { calls++; error("boom") }
        api.fire(SuspendSignal.SUSPEND)
        api.fire(SuspendSignal.SUSPEND)
        assertEquals(2, calls)
        laws.hit("suspend-listener-exception-contained")
    }

    // ---- PowerBroadcastDecoder ---------------------------------------------------------------------------------------

    @Test
    fun `the console display state GUID has the in-memory layout the OS compares`() {
        // 6fe69556-704a-47a0-8f24-c28d936fda47: Data1, Data2, Data3 little-endian, Data4 as written.
        assertContentEquals(
            byteArrayOf(0x56, 0x95.toByte(), 0xe6.toByte(), 0x6f, 0x4a, 0x70, 0xa0.toByte(), 0x47, 0x8f.toByte(), 0x24, 0xc2.toByte(), 0x8d.toByte(), 0x93.toByte(), 0x6f, 0xda.toByte(), 0x47),
            PowerBroadcastDecoder.guidBytes(PowerBroadcastDecoder.CONSOLE_DISPLAY_STATE),
        )
        laws.hit("broadcast-guid")
    }

    private fun setting(value: Int, guid: ByteArray = PowerBroadcastDecoder.guidBytes(PowerBroadcastDecoder.CONSOLE_DISPLAY_STATE), len: Int = 4): ByteArray =
        guid + byteArrayOf(len.toByte(), 0, 0, 0) + byteArrayOf(value.toByte(), 0, 0, 0)

    @Test
    fun `power broadcasts decode to signals and noise decodes to nothing`() {
        assertEquals(SuspendSignal.SUSPEND, PowerBroadcastDecoder.signalFor(0x4, null))
        assertEquals(SuspendSignal.RESUME_SUSPEND, PowerBroadcastDecoder.signalFor(0x7, null))
        assertEquals(SuspendSignal.RESUME_AUTOMATIC, PowerBroadcastDecoder.signalFor(0x12, null))
        assertEquals(SuspendSignal.DISPLAY_OFF, PowerBroadcastDecoder.signalFor(0x8013, setting(0)))
        assertEquals(SuspendSignal.DISPLAY_ON, PowerBroadcastDecoder.signalFor(0x8013, setting(1)))
        assertEquals(SuspendSignal.DISPLAY_DIMMED, PowerBroadcastDecoder.signalFor(0x8013, setting(2)))
        laws.hit("broadcast-display-decode")
        assertNull(PowerBroadcastDecoder.signalFor(0x8013, setting(3)), "an unknown display value is no signal")
        assertNull(PowerBroadcastDecoder.signalFor(0x8013, setting(0, guid = ByteArray(16))), "another setting's GUID is no signal")
        assertNull(PowerBroadcastDecoder.signalFor(0x8013, setting(0, len = 0)), "a zero-length payload is no signal")
        assertNull(PowerBroadcastDecoder.signalFor(0x8013, setting(0).copyOf(20)), "a short buffer is no signal")
        assertNull(PowerBroadcastDecoder.signalFor(0x8013, null))
        assertNull(PowerBroadcastDecoder.signalFor(0x9999, setting(0)))
        assertNull(PowerBroadcastDecoder.signalFor(0x0A, null), "PBT_APMPOWERSTATUSCHANGE is not a suspend signal")
        laws.hit("broadcast-rejects-noise")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("power")
}
