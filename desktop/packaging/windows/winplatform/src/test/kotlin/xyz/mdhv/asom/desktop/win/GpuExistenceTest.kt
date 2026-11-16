package xyz.mdhv.asom.desktop.win

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.win.api.CounterValue
import xyz.mdhv.asom.desktop.win.api.NotificationState
import xyz.mdhv.asom.desktop.win.api.WtsSession
import xyz.mdhv.asom.desktop.win.fakes.FakeClock
import xyz.mdhv.asom.desktop.win.fakes.FakeNative
import xyz.mdhv.asom.desktop.win.fakes.FakePdh
import xyz.mdhv.asom.desktop.win.fakes.FakeSessions
import xyz.mdhv.asom.desktop.win.fakes.FakeUserInput
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.fakeEnv
import xyz.mdhv.asom.desktop.win.gpu.GpuEngineProbe

/**
 * HC-R3: whether the `GPU Engine` counters exist is decided over time, not by one open. A service started at boot can open the
 * query before the GPU Engine performance provider is loaded; that one failure must not switch the GPU presence rule off for
 * the life of the process.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GpuExistenceTest {
    private val laws = LawCounter(
        listOf("one-failed-open-does-not-switch-the-rule-off", "persistent-failure-switches-it-off", "late-counter-is-picked-up", "undecided-is-blind-not-off"),
    )

    private fun busy(permille: Int) = CounterValue("pid_4242_luid_0x00000000_0x0000E0A4_phys_0_eng_0_engtype_3D", permille / 10.0)

    private class Rig(val native: FakeNative) {
        val clock = FakeClock(10_000_000L)
        val platform = WinPlatform(fakeEnv(), native.bundle(), WinOptions(), clock)
        val rt = NodeRuntime(platform, HostMode.USER, NodeConfig(), clock)

        fun advance() {
            clock.now += 2_000L
            rt.tick()
        }
    }

    private fun native(pdh: FakePdh) = FakeNative(
        userInput = FakeUserInput(3_600_000, NotificationState.ACCEPTS_NOTIFICATIONS),
        sessions = FakeSessions(current = WtsSession(1, 0, "alice", false, 0)),
        pdh = pdh,
    )

    @Test
    fun `one failed open at the first tick does not switch the GPU rule off, so contention at 300 permille never serves`() {
        val pdh = FakePdh(mapOf(GpuEngineProbe.COUNTER_PATH to listOf(listOf(busy(300)))), failFirstOpens = 1, failOpensOf = GpuEngineProbe.COUNTER_PATH)
        val rig = Rig(native(pdh))
        rig.rt.enableLending()
        repeat(300) {
            rig.advance()
            assertFalse(rig.rt.fsm.state == LenderState.SERVING, "the GPU counter exists and sits between 200 and 400 permille (tick $it)")
        }
        assertNotNull(rig.platform.gpuContention())
        laws.hit("one-failed-open-does-not-switch-the-rule-off")
    }

    @Test
    fun `an undecided counter is blind, not off`() {
        val rig = Rig(native(FakePdh(emptyMap())))
        assertNotNull(rig.platform.gpuContention(), "one miss does not decide")
        assertNull(rig.platform.gpuContention()!!.sample(), "a port that is not yet known to be absent is blind")
        laws.hit("undecided-is-blind-not-off")
    }

    @Test
    fun `failures that keep coming do end in an absent counter, and the node then serves after the warm-up`() {
        val rig = Rig(native(FakePdh(emptyMap())))
        rig.rt.enableLending()
        val t0 = rig.clock.now
        var firstServing = -1L
        repeat(60) {
            rig.advance()
            if (rig.rt.fsm.state == LenderState.SERVING && firstServing < 0) firstServing = rig.clock.now - t0
        }
        assertEquals(64_000L, firstServing, "an absent counter leaves the rule off: the first CPU sample at 2 s is blind, the dwell runs from 4 s")
        assertNull(rig.platform.gpuContention())
        laws.hit("persistent-failure-switches-it-off")
    }

    @Test
    fun `a counter that appears after the rule was decided off is picked up again`() {
        val pdh = FakePdh(mapOf(GpuEngineProbe.COUNTER_PATH to listOf(listOf(busy(300)))), failFirstOpens = 3, failOpensOf = GpuEngineProbe.COUNTER_PATH)
        val rig = Rig(native(pdh))
        repeat(5) { rig.platform.gpuContention() }
        assertNull(rig.platform.gpuContention(), "decided absent")
        var found = false
        repeat(40) { if (rig.platform.gpuContention() != null) found = true }
        assertEquals(true, found, "the existence question is asked again")
        laws.hit("late-counter-is-picked-up")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("gpu-existence")
}
