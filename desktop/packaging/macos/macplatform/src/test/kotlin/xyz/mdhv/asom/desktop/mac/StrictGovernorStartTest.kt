package xyz.mdhv.asom.desktop.mac

import java.nio.file.Files
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
import xyz.mdhv.asom.desktop.mac.fakes.FakeHelper
import xyz.mdhv.asom.desktop.mac.helper.Fields
import xyz.mdhv.asom.desktop.mac.helper.HValue
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.presence.CpuLoadSource

/**
 * PLATFORM_PLAN R4-P-12 (desktop ERR-FX-5): a freshly enabled governor is neither drained nor eligible. The first
 * CONDITIONS_MET needs 60 s of non-blind samples under 200 permille on every presence counter that exists, so 62 s with the
 * 2 s tick (the first CPU sample is always blind). The expectations come from the spec numbers, not from the implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StrictGovernorStartTest {
    private val laws = LawCounter(
        listOf(
            "marker-declared", "first-serving-after-warm-up", "cpu-contention-band-never-serves", "gpu-contention-band-never-serves",
            "blind-cpu-never-serves", "blind-gpu-never-serves", "contention-resets-the-dwell",
        ),
    )

    private class Cpu(var system: Double? = 0.1, var process: Double? = 0.05) : CpuLoadSource {
        override fun systemLoad() = system
        override fun processLoad() = process
    }

    private class Rig(system: Double? = 0.1, gpuPermille: Int? = 137) {
        val clock = FakeClock(50_000_000L)
        val cpu = Cpu(system)
        var gpu: Int? = gpuPermille
        val helper = FakeHelper(handler = { r ->
            if (r.op == "gpu.get") gpu?.let { Reply.Success("gpu.get", r.id, Fields.of("deviceUtilPermille" to HValue.I(it.toLong()))) } else null
        })
        val platform: MacPlatform
        val rt: NodeRuntime

        init {
            val home = Files.createTempDirectory(shortTempBase(), "sg-")
            val tmp = Files.createDirectory(home.resolve("T"))
            val env = MacEnv("me", home.toString(), emptyMap(), { tmp.toString() }, { 501 })
            platform = MacPlatform(helperTransport = helper, env = env, options = MacOptions(null), cpu = cpu)
            rt = NodeRuntime(platform, HostMode.USER, NodeConfig(), clock)
        }

        fun advance() {
            clock.now += 2_000L
            rt.tick()
        }
    }

    @Test
    fun `the macOS host declares that it settles before serving`() {
        val host: Any = Rig().platform
        assertTrue(host is SettlesBeforeServing)
        laws.hit("marker-declared")
    }

    @Test
    fun `an idle Mac is not serving at the first evaluation, and first serves 62 s after enabling`() {
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
    fun `CPU contention between 200 and 400 permille never serves`() {
        val rig = Rig()
        rig.cpu.system = 0.4
        rig.cpu.process = 0.1
        rig.rt.enableLending()
        repeat(600) {
            rig.advance()
            assertFalse(rig.rt.fsm.state == LenderState.SERVING, "300 permille is neither drained nor eligible (tick $it)")
        }
        laws.hit("cpu-contention-band-never-serves")
    }

    @Test
    fun `GPU contention between 200 and 400 permille never serves`() {
        val rig = Rig(gpuPermille = 300)
        rig.rt.enableLending()
        repeat(600) {
            rig.advance()
            assertFalse(rig.rt.fsm.state == LenderState.SERVING, "a GPU counter that exists must settle too (tick $it)")
        }
        laws.hit("gpu-contention-band-never-serves")
    }

    @Test
    fun `an unreadable CPU counter or an unreadable GPU counter never serves`() {
        val noCpu = Rig(system = null)
        noCpu.rt.enableLending()
        repeat(300) { noCpu.advance(); assertEquals(LenderState.ARMED, noCpu.rt.fsm.state, "blind CPU (tick $it)") }
        laws.hit("blind-cpu-never-serves")
        val noGpu = Rig()
        noGpu.helper.failures["gpu.get"] = Reply.Failure(null, "FAILED", "no reading")
        noGpu.rt.enableLending()
        repeat(300) { noGpu.advance(); assertEquals(LenderState.ARMED, noGpu.rt.fsm.state, "blind GPU (tick $it)") }
        laws.hit("blind-gpu-never-serves")
    }

    @Test
    fun `a contention burst during the warm-up restarts the 60 s dwell`() {
        val rig = Rig()
        rig.rt.enableLending()
        rig.rt.tick()
        repeat(15) { rig.advance() }
        rig.cpu.system = 0.3
        rig.advance()
        assertEquals(LenderState.ARMED, rig.rt.fsm.state)
        val burstAt = rig.clock.now
        rig.cpu.system = 0.1
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
