package xyz.mdhv.asom.desktop.governor

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.DesktopPlatform
import xyz.mdhv.asom.desktop.FakeClock
import xyz.mdhv.asom.desktop.FakePlatform
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.LawCounter
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeRuntime

/** HC-R1 and HC-R4: the strict start (R4-P-12) holds for every enable, and for every host whether or not it re-declares a marker. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StrictStartReenableTest {
    private val laws = LawCounter(listOf("reenable-restarts-settling", "unmarked-host-is-strict", "wrapper-host-is-strict"))
    private val cfg = NodeConfig()

    private class Wrapper(val d: FakePlatform) : DesktopPlatform by d

    private class Marked(val d: FakePlatform) : DesktopPlatform by d, SettlesBeforeServing

    private fun serveOnce(rt: NodeRuntime, clock: FakeClock) {
        rt.enableLending()
        repeat(32) { rt.tick(); clock.now += 2_000 }
        assertEquals(LenderState.SERVING, rt.fsm.state, "62 s of quiet serves")
    }

    private fun disableToOff(rt: NodeRuntime, clock: FakeClock) {
        rt.disableLending()
        rt.fsm.apply(FsmEvent.GRACE_EXPIRED, clock.nowMs())
        assertEquals(LenderState.OFF, rt.fsm.state)
    }

    @Test
    fun `a re-enable while other-process load sits between 200 and 400 permille does not serve at the first tick (HC-R1)`() {
        val clock = FakeClock()
        val platform = FakePlatform(cpuOther = 0)
        val rt = NodeRuntime(Marked(platform), HostMode.USER, cfg, clock)
        serveOnce(rt, clock)
        disableToOff(rt, clock)
        platform.cpuOther = 350
        repeat(300) { rt.tick(); clock.now += 2_000 }
        rt.enableLending()
        rt.tick()
        assertEquals(LenderState.ARMED, rt.fsm.state, "a node enabled while the machine is in use never serves at the first evaluation")
        repeat(40) { rt.tick(); clock.now += 2_000 }
        assertEquals(LenderState.ARMED, rt.fsm.state, "350 permille is never eligible")
        platform.cpuOther = 50
        val t0 = clock.now
        rt.tick()
        clock.now = t0 + 59_999
        rt.tick()
        assertEquals(LenderState.ARMED, rt.fsm.state, "59,999 ms below 200 permille is not 60 s")
        clock.now = t0 + 60_000
        rt.tick()
        assertEquals(LenderState.SERVING, rt.fsm.state)
        laws.hit("reenable-restarts-settling")
    }

    @Test
    fun `a re-enable after a quiet disabled period still settles for the whole dwell (HC-R1)`() {
        val clock = FakeClock()
        val platform = FakePlatform(cpuOther = 0)
        val rt = NodeRuntime(Marked(platform), HostMode.USER, cfg, clock)
        serveOnce(rt, clock)
        disableToOff(rt, clock)
        repeat(300) { rt.tick(); clock.now += 2_000 }
        rt.enableLending()
        rt.tick()
        assertEquals(LenderState.ARMED, rt.fsm.state, "enable starts a fresh governor: neither drained nor eligible")
        laws.hit("reenable-restarts-settling")
    }

    @Test
    fun `a host that declares no marker gets the strict start (HC-R4)`() {
        val rt = NodeRuntime(FakePlatform(cpuOther = 0), HostMode.USER, cfg, FakeClock())
        rt.enableLending()
        rt.tick()
        assertEquals(LenderState.ARMED, rt.fsm.state)
        laws.hit("unmarked-host-is-strict")
    }

    @Test
    fun `a delegating wrapper does not lose the strict start (HC-R4)`() {
        val clock = FakeClock()
        val rt = NodeRuntime(Wrapper(FakePlatform(cpuOther = 0)), HostMode.USER, cfg, clock)
        rt.enableLending()
        rt.tick()
        assertEquals(LenderState.ARMED, rt.fsm.state)
        clock.now += 62_000
        rt.tick()
        assertEquals(LenderState.SERVING, rt.fsm.state)
        laws.hit("wrapper-host-is-strict")
    }

    @AfterAll
    fun everyLawExercisedAtLeastOnce() {
        laws.assertAllExercised("strict-start-reenable")
    }
}
