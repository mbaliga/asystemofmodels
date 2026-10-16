package xyz.mdhv.asom.desktop.mac

import java.nio.file.Files
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
import xyz.mdhv.asom.desktop.mac.fakes.FakeHelper
import xyz.mdhv.asom.desktop.mac.helper.Fields
import xyz.mdhv.asom.desktop.mac.helper.HValue
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.presence.CpuLoadSource

/**
 * HC-R3: whether the GPU counter exists is decided over time, not by one probe. One UNAVAILABLE answer (the helper found no
 * IOAccelerator PerformanceStatistics at that instant) must not switch the GPU presence rule off for the life of the process.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GpuExistenceTest {
    private val laws = LawCounter(
        listOf("one-unavailable-does-not-switch-the-rule-off", "persistent-unavailable-switches-it-off", "late-counter-is-picked-up", "undecided-is-blind-not-off"),
    )

    private class Cpu : CpuLoadSource {
        override fun systemLoad() = 0.1
        override fun processLoad() = 0.05
    }

    private class Rig(permille: () -> Int?) {
        val clock = FakeClock(50_000_000L)
        val helper = FakeHelper(handler = { r ->
            if (r.op == "gpu.get") {
                permille()?.let { Reply.Success("gpu.get", r.id, Fields.of("deviceUtilPermille" to HValue.I(it.toLong()))) }
                    ?: Reply.Failure(r.id, "UNAVAILABLE", "no GPU utilisation counter")
            } else null
        })
        val platform: MacPlatform
        val rt: NodeRuntime

        init {
            val home = Files.createTempDirectory(shortTempBase(), "gx-")
            val tmp = Files.createDirectory(home.resolve("T"))
            val env = MacEnv("me", home.toString(), emptyMap(), { tmp.toString() }, { 501 })
            platform = MacPlatform(helperTransport = helper, env = env, options = MacOptions(null), cpu = Cpu())
            rt = NodeRuntime(platform, HostMode.USER, NodeConfig(), clock)
        }

        fun advance() {
            clock.now += 2_000L
            rt.tick()
        }
    }

    @Test
    fun `one UNAVAILABLE answer at the first tick does not switch the GPU rule off, so contention at 300 permille never serves`() {
        var gpuCalls = 0
        val rig = Rig { if (gpuCalls++ == 0) null else 300 }
        rig.rt.enableLending()
        repeat(300) {
            rig.advance()
            assertFalse(rig.rt.fsm.state == LenderState.SERVING, "the GPU counter exists and sits between 200 and 400 permille (tick $it)")
        }
        assertNotNull(rig.platform.gpuContention())
        laws.hit("one-unavailable-does-not-switch-the-rule-off")
    }

    @Test
    fun `an undecided counter is blind, not off`() {
        val rig = Rig { null }
        assertNotNull(rig.platform.gpuContention(), "one miss does not decide")
        assertNull(rig.platform.gpuContention()!!.sample(), "a port that is not yet known to be absent is blind")
        laws.hit("undecided-is-blind-not-off")
    }

    @Test
    fun `UNAVAILABLE answers that keep coming do end in an absent counter, and the node then serves after the warm-up`() {
        val rig = Rig { null }
        rig.rt.enableLending()
        var firstServing = -1L
        val t0 = rig.clock.now
        repeat(60) {
            rig.advance()
            if (rig.rt.fsm.state == LenderState.SERVING && firstServing < 0) firstServing = rig.clock.now - t0
        }
        assertEquals(64_000L, firstServing, "an absent counter leaves the rule off: the first CPU sample at 2 s is blind, the dwell runs from 4 s")
        assertNull(rig.platform.gpuContention())
        laws.hit("persistent-unavailable-switches-it-off")
    }

    @Test
    fun `a counter that appears after the rule was decided off is picked up again, and then has to settle`() {
        var present = false
        val rig = Rig { if (present) 300 else null }
        repeat(10) { rig.platform.gpuContention() }
        assertNull(rig.platform.gpuContention(), "decided absent")
        present = true
        var found = false
        repeat(40) { if (rig.platform.gpuContention() != null) found = true }
        assertEquals(true, found, "the existence question is asked again")
        assertEquals(300, rig.platform.gpuContention()!!.sample()!!.otherBusyPermille)
        laws.hit("late-counter-is-picked-up")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("gpu-existence")
}
