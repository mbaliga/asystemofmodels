package xyz.mdhv.asom.desktop.mac

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.BatteryBand
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.PowerSource
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.governor.FsmConfig
import xyz.mdhv.asom.desktop.governor.FsmEffect
import xyz.mdhv.asom.desktop.governor.FsmEvent
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.governor.ProviderFsm
import xyz.mdhv.asom.desktop.mac.fakes.FakeHelper
import xyz.mdhv.asom.desktop.mac.gpu.MacGpuProbe
import xyz.mdhv.asom.desktop.mac.gpu.OwnGpuBusy
import xyz.mdhv.asom.desktop.mac.helper.Event
import xyz.mdhv.asom.desktop.mac.helper.Fields
import xyz.mdhv.asom.desktop.mac.helper.HValue
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.power.MacPowerPort
import xyz.mdhv.asom.desktop.mac.power.PowerMapping
import xyz.mdhv.asom.desktop.mac.presence.CpuLoadSource
import xyz.mdhv.asom.desktop.mac.presence.MacPresencePort
import xyz.mdhv.asom.desktop.mac.presence.MacPresenceReader
import xyz.mdhv.asom.desktop.mac.presence.MacRules
import xyz.mdhv.asom.desktop.mac.thermal.MacThermalPort

/** Power, thermal, GPU: what the helper says becomes what the governor reads, and every unknown is the conservative answer. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortsTest {
    private val laws = LawCounter(
        listOf(
            "power-map", "power-ups-is-battery", "power-unreadable-blocks", "hold-shared", "hold-refused-is-state", "hold-fixed-reason",
            "thermal-bands", "thermal-unknown-holds", "gpu-other", "gpu-off", "gpu-null-when-blind",
        ),
    )

    @AfterAll
    fun report() = laws.assertAllExercised("ports")

    private fun power(source: String, charging: Boolean, permille: Int?, low: Boolean = false) =
        HelperClient.PowerInfo(source, charging, permille, low)

    @Test
    fun `power maps source, battery band, charging and low power`() {
        val ac = PowerMapping.map(power("ac", true, 870))
        assertEquals(PowerSource.AC, ac.source); assertTrue(ac.charging); assertTrue(ac.hasBattery)
        assertEquals(87, ac.batteryPercent); assertEquals(BatteryBand.GE80, ac.batteryBand); assertEquals(false, ac.saver)
        val bat = PowerMapping.map(power("battery", false, 795, low = true))
        assertEquals(PowerSource.BATTERY, bat.source)
        assertEquals(79, bat.batteryPercent, "rounded DOWN: a band is never higher than the truth")
        assertEquals(BatteryBand.B50_79, bat.batteryBand)
        assertEquals(true, bat.saver)
        assertEquals(BatteryBand.LT20, PowerMapping.map(power("battery", false, 199)).batteryBand)
        assertEquals(BatteryBand.B20_49, PowerMapping.map(power("battery", false, 200)).batteryBand)
        assertEquals(BatteryBand.GE80, PowerMapping.map(power("ac", false, 1000)).batteryBand)
        val desktop = PowerMapping.map(power("ac", false, null))
        assertFalse(desktop.hasBattery); assertNull(desktop.batteryPercent); assertNull(desktop.batteryBand)
        laws.hit("power-map", 6)
    }

    @Test
    fun `a desktop on a UPS reports battery power and is treated as having a battery, so it does not lend`() {
        val ups = PowerMapping.map(power("battery", false, null))
        assertEquals(PowerSource.BATTERY, ups.source)
        assertTrue(ups.hasBattery, "otherwise the desktop rules would let a UPS-backed Mac serve")
        val rules = MacRules(NodeConfig()) { xyz.mdhv.asom.desktop.mac.presence.MacPresenceVerdict(emptyList()) }
        assertEquals(listOf("on-battery"), rules.evaluate(ups, xyz.mdhv.asom.desktop.governor.HostSignals()).conditionBlocks)
        laws.hit("power-ups-is-battery")
    }

    @Test
    fun `an unreadable power source blocks`() {
        val h = FakeHelper()
        h.failures["power.get"] = Reply.Failure(null, "UNAVAILABLE", "power sources unreadable")
        val port = MacPowerPort(HelperClient(h))
        val r = port.read()
        assertEquals(PowerSource.UNKNOWN, r.source)
        assertNotNull(port.lastReadError)
        val rules = MacRules(NodeConfig()) { xyz.mdhv.asom.desktop.mac.presence.MacPresenceVerdict(emptyList()) }
        assertTrue("power-unknown" in rules.evaluate(r, xyz.mdhv.asom.desktop.governor.HostSignals()).conditionBlocks)
        h.lost = "gone"
        assertEquals(PowerSource.UNKNOWN, port.read().source)
        laws.hit("power-unreadable-blocks", 2)
    }

    @Test
    fun `Low Power Mode is a condition block`() {
        val rules = MacRules(NodeConfig()) { xyz.mdhv.asom.desktop.mac.presence.MacPresenceVerdict(emptyList()) }
        val v = rules.evaluate(PowerMapping.map(power("ac", true, 900, low = true)), xyz.mdhv.asom.desktop.governor.HostSignals())
        assertEquals(listOf("low-power-mode"), v.conditionBlocks)
        assertEquals(30_000L, rules.graceMs)
    }

    @Test
    fun `holds share one assertion, taken by the first and released by the last, and both lock kinds map to it`() {
        val h = FakeHelper()
        val port = MacPowerPort(HelperClient(h))
        val a = port.hold(LockKind.DELAY)
        val b = port.hold(LockKind.BLOCK)
        assertEquals(1, h.opCount("assert.hold"), "one assertion however many holds")
        assertEquals(2, port.activeHolds)
        a.release(); a.release()
        assertEquals(0, h.opCount("assert.release"))
        assertEquals(1, port.activeHolds)
        b.release()
        assertEquals(1, h.opCount("assert.release"))
        assertEquals(0, port.activeHolds)
        val c = port.hold(LockKind.DELAY)
        assertEquals(2, h.opCount("assert.hold"))
        c.release()
        laws.hit("hold-shared", 3)
    }

    @Test
    fun `a refused hold is a state, not an error loop, and a lost helper cannot break a release`() {
        val h = FakeHelper()
        h.failures["assert.hold"] = Reply.Failure(null, "FAILED", "IOPMAssertionCreateWithName failed")
        val port = MacPowerPort(HelperClient(h))
        val hold = port.hold(LockKind.DELAY)
        assertEquals(0, port.activeHolds)
        assertTrue(port.lastHoldError!!.contains("IOPMAssertionCreateWithName"))
        hold.release()
        assertEquals(0, h.opCount("assert.release"), "a hold that holds nothing releases nothing")
        h.failures.clear()
        val ok = port.hold(LockKind.BLOCK)
        assertNull(port.lastHoldError)
        h.lost = "the helper died"
        ok.release() // must not throw: a dead helper has released its assertion by dying
        assertEquals(0, port.activeHolds)
        laws.hit("hold-refused-is-state", 2)
    }

    @Test
    fun `the assertion always carries the fixed reason, and any other is not even encodable`() {
        val h = FakeHelper()
        HelperClient(h).assertHold()
        val bad = xyz.mdhv.asom.desktop.mac.helper.Request("assert.hold", 1, Fields.of("reason" to HValue.T("keep awake")))
        val e = kotlin.test.assertFailsWith<xyz.mdhv.asom.desktop.mac.helper.Reject> { xyz.mdhv.asom.desktop.mac.helper.HelperCodec.encode(bad) }
        assertEquals("BAD_FIELD", e.code.wire)
        assertEquals("asom: lending compute to your paired devices", xyz.mdhv.asom.desktop.mac.helper.ProtocolSpec.HOLD_REASON)
        laws.hit("hold-fixed-reason")
    }

    // ---- sleep --------------------------------------------------------------------------------------------------------------

    private fun sleepWill(token: Long) = Event("sleep.will", Fields.of("token" to HValue.I(token)))

    @Test
    fun `sleep will reaches the listener and is acknowledged once the drain returns`() {
        val h = FakeHelper()
        val port = MacPowerPort(HelperClient(h), ackBudgetMs = 2_000)
        val events = CopyOnWriteArrayList<SleepEvent>()
        val sub = port.onSleepEvents { events += it }
        val t0 = System.nanoTime()
        h.emit(sleepWill(7))
        waitFor { port.acksSent.get() == 1 }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 1_500, "a quick drain is acknowledged at once, not after the budget: $ms ms")
        assertEquals(listOf(SleepEvent.SLEEP_IMMINENT), events.toList())
        assertEquals(1, h.opCount("sleep.ack"))
        h.emit(Event("wake"))
        waitFor { events.size == 2 }
        assertEquals(SleepEvent.RESUMED, events[1])
        sub.close()
        h.emit(sleepWill(7))
        Thread.sleep(200)
        assertEquals(1, h.opCount("sleep.ack"), "an unsubscribed port answers nothing")
    }

    @Test
    fun `a drain that takes too long is cut off at the budget and the ack is sent once, never later than budget plus slack`() {
        val h = FakeHelper()
        val port = MacPowerPort(HelperClient(h), ackBudgetMs = 300)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val sub = port.onSleepEvents {
            if (it == SleepEvent.SLEEP_IMMINENT) {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        val t0 = System.nanoTime()
        h.emit(sleepWill(7))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        waitFor { port.acksSent.get() == 1 }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms in 250..2_500, "acknowledged after about the 300 ms budget while the drain was still running: $ms ms")
        release.countDown()
        Thread.sleep(300)
        assertEquals(1, port.acksSent.get(), "exactly one ack per token")
        sub.close()
    }

    @Test
    fun `a listener that throws does not stop the acknowledgement`() {
        val h = FakeHelper()
        val port = MacPowerPort(HelperClient(h), ackBudgetMs = 2_000)
        port.onSleepEvents { throw IllegalStateException("boom") }
        h.emit(sleepWill(7))
        waitFor { port.acksSent.get() == 1 }
    }

    @Test
    fun `with the real FSM a sleep drain closes the listener at once and its budget is 2 seconds`() {
        val fsm = ProviderFsm(FsmConfig(graceMs = 30_000, sleepBudgetMs = MacRules.SLEEP_BUDGET_MS))
        var now = 10_000_000L
        fsm.apply(FsmEvent.USER_ENABLE, now)
        assertEquals(LenderState.SERVING, fsm.apply(FsmEvent.CONDITIONS_MET, now).to)
        val h = FakeHelper()
        val port = MacPowerPort(HelperClient(h))
        val effects = CopyOnWriteArrayList<FsmEffect>()
        port.onSleepEvents { ev ->
            if (ev == SleepEvent.SLEEP_IMMINENT) {
                val step = fsm.apply(FsmEvent.SLEEP_IMMINENT, now)
                effects += step.effects
            }
        }
        now += 5
        h.emit(sleepWill(7))
        waitFor { port.acksSent.get() == 1 }
        assertEquals(LenderState.DRAINING, fsm.state)
        assertTrue(FsmEffect.CLOSE_LISTENER in effects, "the listener is closed in the same step")
        assertEquals(now + 2_000, fsm.snapshot().drainDeadlineMs, "the drain deadline is the 2 s budget, not the 30 s grace")
        assertEquals(1, h.opCount("sleep.ack"))
    }

    @Test
    fun `the node-core FSM's own default budget is 5 s, which is why MAC-SLEEP-1 is recorded`() {
        val defaultBudget = FsmConfig(graceMs = 30_000).sleepBudgetMs
        assertEquals(5_000L, defaultBudget)
        assertTrue(MacRules.SLEEP_BUDGET_MS < defaultBudget)
    }

    // ---- thermal --------------------------------------------------------------------------------------------------------------

    @Test
    fun `thermal states map to bands 0, 1, 2, 2 and the reading carries no temperature it did not measure`() {
        val h = FakeHelper()
        val port = MacThermalPort(HelperClient(h))
        val expected = mapOf("nominal" to 0, "fair" to 1, "serious" to 2, "critical" to 2)
        for ((state, band) in expected) {
            h.handler = { r -> if (r.op == "thermal.get") Reply.Success("thermal.get", r.id, Fields.of("state" to HValue.T(state))) else null }
            val r = port.read()
            assertEquals(band, r.band, state)
            assertNull(r.hottestMilliC); assertNull(r.holdThresholdMilliC)
            assertEquals(state, port.lastState)
        }
        laws.hit("thermal-bands", 4)
        assertEquals(2, MacThermalPort.bandOf("scorching"), "a state this code does not know is HOLD")
    }

    @Test
    fun `an unreadable thermal state is HOLD, because unknown heat is the unsafe answer`() {
        val h = FakeHelper()
        h.failures["thermal.get"] = Reply.Failure(null, "UNAVAILABLE", "thermal state not recognised")
        val port = MacThermalPort(HelperClient(h))
        assertEquals(2, port.read().band)
        assertNotNull(port.lastError)
        h.failures.clear(); h.lost = "gone"
        assertEquals(2, port.read().band)
        laws.hit("thermal-unknown-holds", 2)
    }

    // ---- GPU ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `GPU other-busy is the device figure minus the node's own, never negative`() {
        val h = FakeHelper()
        var device = 600
        h.handler = { r -> if (r.op == "gpu.get") Reply.Success("gpu.get", r.id, Fields.of("deviceUtilPermille" to HValue.I(device.toLong()))) else null }
        var own = 100
        val probe = MacGpuProbe(HelperClient(h), OwnGpuBusy { own })
        var s = probe.sample()!!
        assertEquals(600, s.deviceBusyPermille); assertEquals(100, s.ownBusyPermille); assertEquals(500, s.otherBusyPermille)
        own = 900; device = 300
        s = probe.sample()!!
        assertEquals(0, s.otherBusyPermille, "own above device (sampling skew) is 0, not negative")
        own = 5000
        assertEquals(1000, probe.sample()!!.ownBusyPermille, "own is clamped to 1000")
        laws.hit("gpu-other", 3)
    }

    @Test
    fun `no GPU counter means the rule is off, and a blind tick is null, never zero`() {
        val h = FakeHelper()
        h.failures["gpu.get"] = Reply.Failure(null, "UNAVAILABLE", "no GPU utilisation counter")
        assertEquals(false, MacGpuProbe.counterExists(HelperClient(h)))
        assertNull(MacGpuProbe(HelperClient(h)).sample())
        laws.hit("gpu-off")
        h.failures.clear()
        assertEquals(true, MacGpuProbe.counterExists(HelperClient(h)))
        h.lost = "gone"
        assertNull(MacGpuProbe.counterExists(HelperClient(h)), "a lost helper does not decide that the counter is absent")
        assertNull(MacGpuProbe(HelperClient(h)).sample())
        laws.hit("gpu-null-when-blind")
    }

    // ---- the CPU-contention presence input ------------------------------------------------------------------------------------

    @Test
    fun `the first CPU sample is null, never zero, and other is system minus process`() {
        var sys: Double? = 0.50
        var proc: Double? = 0.10
        val port = MacPresencePort(object : CpuLoadSource {
            override fun systemLoad() = sys
            override fun processLoad() = proc
        })
        assertNull(port.sample().cpuOtherPermille)
        assertEquals(400, port.sample().cpuOtherPermille)
        sys = null
        assertNull(port.sample().cpuOtherPermille)
        sys = 0.05; proc = 0.30
        assertEquals(0, port.sample().cpuOtherPermille)
    }

    @Test
    fun `the presence reader refuses an idle threshold below ten minutes`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { MacPresenceReader(HelperClient(FakeHelper()), 599_999) }
        MacPresenceReader(HelperClient(FakeHelper()), 600_000)
    }

    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!cond() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(cond(), "condition not reached in time")
    }
}
