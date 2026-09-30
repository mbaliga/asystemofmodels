package xyz.mdhv.asom.desktop.governor

import kotlin.test.Test
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.FakeClock
import xyz.mdhv.asom.desktop.FakePlatform
import xyz.mdhv.asom.desktop.FakePower
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.LawCounter
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.Report
import xyz.mdhv.asom.desktop.ThermalReading

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernorTest {
    private val laws = LawCounter(listOf("dwell-on-boundary", "dwell-off-boundary", "dwell-blind-sample", "governor-events", "nvidia-thermal-only", "runtime-flow"))
    private val cfg = NodeConfig()

    @Test
    fun `drain above 400 for 10 s and eligible below 200 for 60 s, exactly at the boundaries`() {
        val g = DwellGate(400, 10_000, 200, 60_000)
        assertFalse(g.update(0, 401))
        assertFalse(g.update(9_999, 401), "9,999 ms above is not yet 10 s")
        assertTrue(g.update(10_000, 401), "10,000 ms above activates")
        laws.hit("dwell-on-boundary")
        assertTrue(g.update(20_000, 199))
        assertTrue(g.update(79_999, 199), "59,999 ms below is not yet 60 s")
        assertTrue(g.update(80_000, 300), "a value inside the band restarts the eligible dwell")
        assertTrue(g.update(80_001, 199))
        assertTrue(g.update(140_000, 199))
        assertFalse(g.update(140_001, 199), "60,000 ms below deactivates")
        laws.hit("dwell-off-boundary")
    }

    @Test
    fun `an exactly-at-threshold value is neither above nor below`() {
        val g = DwellGate(400, 0, 200, 0)
        assertFalse(g.update(0, 400), "400 is not above 400")
        assertTrue(g.update(1, 401))
        assertTrue(g.update(2, 200), "200 is not below 200")
        assertFalse(g.update(3, 199))
    }

    @Test
    fun `a blind sample restarts both dwell timers and never activates or deactivates`() {
        val g = DwellGate(400, 10_000, 200, 60_000)
        g.update(0, 500)
        g.update(9_000, null)
        assertFalse(g.update(10_000, 500), "the dwell restarted at the blind sample")
        laws.hit("dwell-blind-sample")
    }

    private fun readings(
        power: xyz.mdhv.asom.desktop.PowerReading = FakePower.ac(), band: Int = 0, cpu: Int? = 0, gpu: Int? = null,
        signals: HostSignals = HostSignals(),
    ) = Readings(power, ThermalReading(band, 40_000, 80_000, listOf("s")), signals, cpu, gpu)

    @Test
    fun `governor derives one event per situation`() {
        val g = Governor(cfg, DesktopRules(cfg))
        assertEquals(listOf(FsmEvent.CONDITIONS_MET), g.evaluate(0, readings()).events)
        assertEquals(listOf(FsmEvent.CONDITION_LOST), g.evaluate(1, readings(band = 2)).events)
        assertEquals(listOf(FsmEvent.CONDITIONS_MET), g.evaluate(2, readings(band = 1)).events, "band 1 (QUEUE) does not drain")
        val lap = g.evaluate(3, readings(power = FakePower.battery()))
        assertEquals(listOf(FsmEvent.CONDITION_LOST), lap.events, "laptop on battery: not eligible, stays ARMED")
        assertEquals(listOf("on-battery"), lap.conditionReasons)
        laws.hit("governor-events")
    }

    @Test
    fun `cpu contention above 400 for 10 s becomes presence signals until it stays below 200 for 60 s`() {
        val g = Governor(cfg, DesktopRules(cfg))
        var t = 0L
        val out = ArrayList<List<FsmEvent>>()
        repeat(6) { out += g.evaluate(t, readings(cpu = 600)).events; t += 2_000 }
        assertEquals(List(5) { listOf(FsmEvent.CONDITIONS_MET) }, out.take(5), "the first 8 s: not yet")
        assertEquals(listOf(FsmEvent.PRESENCE_SIGNAL), out[5], "at 10 s: presence")
        assertEquals(listOf(FsmEvent.PRESENCE_SIGNAL), g.evaluate(t, readings(cpu = 300)).events, "between 200 and 400 it stays active")
        assertEquals(listOf(FsmEvent.PRESENCE_SIGNAL), g.evaluate(t + 1, readings(cpu = 100)).events)
        assertEquals(listOf(FsmEvent.PRESENCE_SIGNAL), g.evaluate(t + 60_000, readings(cpu = 100)).events, "59,999 ms below")
        assertEquals(listOf(FsmEvent.CONDITIONS_MET), g.evaluate(t + 60_001, readings(cpu = 100)).events)
        laws.hit("governor-events")
    }

    @Test
    fun `no GPU counter (NVIDIA) means the GPU rule is off and only the thermal band applies`() {
        val g = Governor(cfg, DesktopRules(cfg))
        // gpu = null models NVIDIA: no own-versus-other attribution; a saturated GPU cannot drain.
        repeat(20) { assertEquals(listOf(FsmEvent.CONDITIONS_MET), g.evaluate(it * 2_000L, readings(gpu = null)).events) }
        assertEquals(listOf(FsmEvent.CONDITION_LOST), g.evaluate(100_000, readings(gpu = null, band = 2)).events)
        // with an attributable counter the same load does drain
        val g2 = Governor(cfg, DesktopRules(cfg))
        g2.evaluate(0, readings(gpu = 900))
        assertEquals(listOf(FsmEvent.PRESENCE_SIGNAL), g2.evaluate(10_000, readings(gpu = 900)).events)
        laws.hit("nvidia-thermal-only")
    }

    @Test
    fun `memory pressure above 5 percent for 10 s drains as a condition`() {
        val g = Governor(cfg, DesktopRules(cfg))
        g.evaluate(0, readings(signals = HostSignals(memoryPsiFullCenti = 501)))
        val e = g.evaluate(10_000, readings(signals = HostSignals(memoryPsiFullCenti = 501)))
        assertEquals(listOf(FsmEvent.CONDITION_LOST), e.events)
        assertEquals(listOf("memory-pressure"), e.conditionReasons)
        laws.hit("governor-events")
    }

    @Test
    fun `runtime flow enable serve drain and shut down, with effects recorded and nothing acted on`() {
        val clock = FakeClock()
        val effects = ArrayList<FsmEffect>()
        val platform = FakePlatform()
        val rt = NodeRuntime(platform, HostMode.USER, cfg, clock, onEffect = { effects += it })
        assertEquals(LenderState.OFF, rt.fsm.state)
        rt.tick()
        assertEquals(LenderState.OFF, rt.fsm.state, "lending is OFF until the owner enables it")
        rt.enableLending()
        assertEquals(LenderState.ARMED, rt.fsm.state)
        rt.tick()
        assertEquals(LenderState.SERVING, rt.fsm.state)
        assertEquals(listOf(FsmEffect.OPEN_LISTENER, FsmEffect.TAKE_DELAY_LOCK), effects.toList())
        platform.thermal = ThermalReading(2, 90_000, 80_000, listOf("s"))
        clock.now += 2_000
        rt.tick()
        assertEquals(LenderState.DRAINING, rt.fsm.state)
        rt.shutdown()
        assertEquals(LenderState.OFF, rt.fsm.state)
        assertTrue(FsmEffect.CLOSE_LISTENER in effects && FsmEffect.RELEASE_DELAY_LOCK in effects)
        laws.hit("runtime-flow")
    }

    @Test
    fun `runtime never reports a wire field derived from presence other than fsm and decline`() {
        val clock = FakeClock()
        val platform = FakePlatform(cpuOther = 900)
        val rt = NodeRuntime(platform, HostMode.USER, cfg, clock)
        rt.enableLending()
        repeat(10) { rt.tick(); clock.now += 2_000 }
        assertTrue(rt.fsm.state != LenderState.SERVING, "sustained other-process load: not SERVING")
        assertEquals(wireOf(rt.fsm.state), rt.fsm.wire())
        assertEquals(DECLINE_PEER_UNAVAILABLE, rt.fsm.wire().declineCode)
        laws.hit("governor-events")
    }

    @AfterAll
    fun everyLawExercisedAtLeastOnce() {
        Report.line("governor: ${laws.count("governor-events")} event cases")
        laws.assertAllExercised("governor")
    }
}
