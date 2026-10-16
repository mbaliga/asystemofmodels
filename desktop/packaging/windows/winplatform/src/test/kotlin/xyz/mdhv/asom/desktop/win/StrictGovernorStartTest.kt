package xyz.mdhv.asom.desktop.win

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.governor.SettlesBeforeServing
import xyz.mdhv.asom.desktop.win.api.NotificationState
import xyz.mdhv.asom.desktop.win.api.WtsSession
import xyz.mdhv.asom.desktop.win.fakes.FakeClock
import xyz.mdhv.asom.desktop.win.fakes.FakeNative
import xyz.mdhv.asom.desktop.win.fakes.FakeSessions
import xyz.mdhv.asom.desktop.win.fakes.FakeUserInput
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.fakeEnv

/**
 * PLATFORM_PLAN R4-P-12 (desktop ERR-FX-5): a freshly enabled governor is neither drained nor eligible. The first
 * CONDITIONS_MET needs 60 s of non-blind samples under 200 permille, so 62 s with the 2 s tick (the first CPU sample is
 * always blind). The expectations come from the spec numbers, not from the implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StrictGovernorStartTest {
    private val laws = LawCounter(
        listOf("marker-declared", "first-serving-after-warm-up", "contention-band-never-serves", "blind-cpu-never-serves", "contention-resets-the-dwell"),
    )

    private class Rig(system: Double? = 0.1, process: Double? = 0.05) {
        val clock = FakeClock(10_000_000L)
        val input = FakeUserInput(3_600_000, NotificationState.ACCEPTS_NOTIFICATIONS)
        val sessions = FakeSessions(current = WtsSession(1, 0, "alice", false, 0))
        val native = FakeNative(userInput = input, sessions = sessions).also { it.cpu.system = system; it.cpu.process = process }
        val platform = WinPlatform(fakeEnv(), native.bundle(), WinOptions(), clock)
        val rt = NodeRuntime(platform, HostMode.USER, NodeConfig(), clock)

        fun advance() {
            clock.now += 2_000L
            rt.tick()
        }
    }

    @Test
    fun `the Windows host declares that it settles before serving`() {
        val host: Any = Rig().platform
        assertTrue(host is SettlesBeforeServing)
        laws.hit("marker-declared")
    }

    @Test
    fun `an idle machine is not serving at the first evaluation, and first serves 62 s after enabling`() {
        val rig = Rig()
        val t0 = rig.clock.now
        rig.rt.enableLending()
        rig.rt.tick()
        assertEquals(LenderState.ARMED, rig.rt.fsm.state, "the first evaluation cannot serve: nothing has shown the owner is away")
        var firstServing = -1L
        for (i in 1..60) {
            rig.advance()
            if (rig.rt.fsm.state == LenderState.SERVING && firstServing < 0) firstServing = rig.clock.now - t0
        }
        assertEquals(62_000L, firstServing, "the first CPU sample is blind, then 60 s below 200 permille")
        laws.hit("first-serving-after-warm-up")
    }

    @Test
    fun `contention between 200 and 400 permille never serves, however long the node waits`() {
        val rig = Rig(system = 0.4, process = 0.1)
        rig.rt.enableLending()
        repeat(600) {
            rig.advance()
            assertFalse(rig.rt.fsm.state == LenderState.SERVING, "300 permille is neither drained nor eligible (tick $it)")
        }
        assertEquals(LenderState.ARMED, rig.rt.fsm.state)
        laws.hit("contention-band-never-serves")
    }

    @Test
    fun `an unreadable CPU counter never serves`() {
        val rig = Rig(system = null)
        rig.rt.enableLending()
        repeat(600) {
            rig.advance()
            assertEquals(LenderState.ARMED, rig.rt.fsm.state, "a blind sample counts neither way (tick $it)")
        }
        laws.hit("blind-cpu-never-serves")
    }

    @Test
    fun `a contention burst during the warm-up restarts the 60 s dwell`() {
        val rig = Rig()
        rig.rt.enableLending()
        rig.rt.tick()
        repeat(15) { rig.advance() }
        rig.native.cpu.system = 0.3
        rig.advance()
        assertEquals(LenderState.ARMED, rig.rt.fsm.state)
        val burstAt = rig.clock.now
        rig.native.cpu.system = 0.1
        var firstServing = -1L
        for (i in 1..80) {
            rig.advance()
            if (rig.rt.fsm.state == LenderState.SERVING && firstServing < 0) firstServing = rig.clock.now - burstAt
        }
        assertEquals(62_000L, firstServing, "the dwell restarted at the first quiet sample (+2 s) and ran its full 60 s")
        laws.hit("contention-resets-the-dwell")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("strict-governor-start")
}
