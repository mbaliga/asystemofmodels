package xyz.mdhv.asom.desktop.governor

import java.util.SplittableRandom
import kotlin.test.Test
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.FakePower
import xyz.mdhv.asom.desktop.GpuSample
import xyz.mdhv.asom.desktop.LawCounter
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.PresenceSample
import xyz.mdhv.asom.desktop.PresenceTagged
import xyz.mdhv.asom.desktop.Report
import xyz.mdhv.asom.desktop.ThermalReading

/** LP-0, LP-1 and LP-2 (design 7.4, LAB_SPEC 6.5) as sequences, a classification table and a seeded property test. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresenceLawsTest {
    private val hold = 600_000L
    private val cfg = FsmConfig(graceMs = 30_000)
    private val deck = FsmConfig(graceMs = 2_000)
    private val pf = FsmConfig(graceMs = 30_000, presenceFirst = true)

    private val laws = LawCounter(
        listOf(
            "LP-0-classification", "LP-1-wire-shape", "LP-1-wire-depends-on-state-only", "LP-2-presence-drains-at-once",
            "LP-2-hold-down-599999", "LP-2-hold-down-600000", "LP-2-second-presence-restarts", "LP-2-condition-drain-returns-at-once",
            "LP-2-sleep-drain-not-presence", "LP-2-pf-needs-explicit-start", "PF-consent-not-presence", "deck-grace-2s",
            "prop-serve-entry-guarded", "prop-listener-effects", "prop-lock-balance", "prop-drain-exits", "prop-off-entry",
            "prop-config-rejects-short-hold-down", "startup-mid-band-never-serves", "startup-quiet-dwell-60s", "startup-blind-sample-not-eligible",
            "startup-gpu-counter-must-settle",
        ),
    )

    private fun servingAt(config: FsmConfig, t: Long): ProviderFsm {
        val f = ProviderFsm(config)
        f.apply(FsmEvent.USER_ENABLE, t)
        f.apply(FsmEvent.CONDITIONS_MET, t)
        assertEquals(LenderState.SERVING, f.state)
        return f
    }

    @Test
    fun `LP-2 presence drains at once and SERVING returns at exactly 600000 ms and not before`() {
        val t = 10_000_000L
        val f = servingAt(cfg, t - 1_000_000)
        val step = f.apply(FsmEvent.PRESENCE_SIGNAL, t)
        assertEquals(LenderState.DRAINING, step.to, "same step")
        laws.hit("LP-2-presence-drains-at-once")
        f.apply(FsmEvent.INFLIGHT_DONE, t + 10)
        assertEquals(LenderState.ARMED, f.state)
        f.apply(FsmEvent.CONDITIONS_MET, t + hold - 1)
        assertEquals(LenderState.ARMED, f.state, "t + 599,999 must not serve")
        laws.hit("LP-2-hold-down-599999")
        f.apply(FsmEvent.CONDITIONS_MET, t + hold)
        assertEquals(LenderState.SERVING, f.state, "the first evaluation at t + 600,000 serves")
        laws.hit("LP-2-hold-down-600000")
    }

    @Test
    fun `a second presence signal restarts the clock`() {
        val t = 10_000_000L
        val f = servingAt(cfg, t - 1_000_000)
        f.apply(FsmEvent.PRESENCE_SIGNAL, t)
        f.apply(FsmEvent.INFLIGHT_DONE, t + 1)
        val t2 = t + hold - 1
        f.apply(FsmEvent.PRESENCE_SIGNAL, t2) // ARMED: recorded
        f.apply(FsmEvent.CONDITIONS_MET, t2 + hold - 1)
        assertEquals(LenderState.ARMED, f.state, "not SERVING at t' + 599,999")
        f.apply(FsmEvent.CONDITIONS_MET, t2 + hold)
        assertEquals(LenderState.SERVING, f.state)
        laws.hit("LP-2-second-presence-restarts")
    }

    @Test
    fun `a condition drain (heat) and a sleep drain return as soon as conditions allow`() {
        val t = 10_000_000L
        val heat = servingAt(cfg, t - 1_000_000)
        heat.apply(FsmEvent.CONDITION_LOST, t)
        heat.apply(FsmEvent.INFLIGHT_DONE, t + 1)
        heat.apply(FsmEvent.CONDITIONS_MET, t + 2)
        assertEquals(LenderState.SERVING, heat.state, "no hold-down after a thermal drain")
        laws.hit("LP-2-condition-drain-returns-at-once")

        val sleep = servingAt(cfg, t - 1_000_000)
        sleep.apply(FsmEvent.SLEEP_IMMINENT, t)
        sleep.apply(FsmEvent.INFLIGHT_DONE, t + 1)
        sleep.apply(FsmEvent.CONDITIONS_MET, t + 2)
        assertEquals(LenderState.ARMED, sleep.state, "asleep: must not serve")
        sleep.apply(FsmEvent.RESUMED, t + 3_000)
        sleep.apply(FsmEvent.CONDITIONS_MET, t + 3_001)
        assertEquals(LenderState.SERVING, sleep.state, "a sleep-caused drain has no 10-minute hold-down")
        assertNull(sleep.snapshot().lastPresenceMs)
        laws.hit("LP-2-sleep-drain-not-presence")
    }

    @Test
    fun `a PF node returns only after presence hold-down AND a new explicit start`() {
        val t = 10_000_000L
        val f = servingAt(pf, t - 1_000_000)
        f.apply(FsmEvent.PRESENCE_SIGNAL, t)
        f.apply(FsmEvent.INFLIGHT_DONE, t + 1)
        f.apply(FsmEvent.CONDITIONS_MET, t + hold + 60_000)
        assertEquals(LenderState.ARMED, f.state, "hold-down elapsed but no explicit Start lending")
        f.apply(FsmEvent.USER_ENABLE, t + hold + 61_000)
        f.apply(FsmEvent.CONDITIONS_MET, t + hold + 62_000)
        assertEquals(LenderState.SERVING, f.state)
        laws.hit("LP-2-pf-needs-explicit-start")

        // PA control: the same sequence needs no explicit start.
        val pa = servingAt(cfg, t - 1_000_000)
        pa.apply(FsmEvent.PRESENCE_SIGNAL, t)
        pa.apply(FsmEvent.INFLIGHT_DONE, t + 1)
        pa.apply(FsmEvent.CONDITIONS_MET, t + hold)
        assertEquals(LenderState.SERVING, pa.state)
    }

    @Test
    fun `PF consent inputs never signal presence and presence inputs do`() {
        // Frontmost lend screen + screen on + a touch inside it: stays SERVING.
        val f = servingAt(pf, 1_000_000)
        for (i in listOf(GovernorInput.LEND_SCREEN_FRONTMOST, GovernorInput.SCREEN_ON, GovernorInput.INPUT_INSIDE_LEND_SCREEN)) {
            assertNull(presenceEventFor(i, NodeKind.PF), "$i is consent on a PF node")
            laws.hit("PF-consent-not-presence")
        }
        assertEquals(LenderState.SERVING, f.state)
        // A touch outside it: DRAINING, then no SERVING without a new Start.
        val ev = assertNotNull(presenceEventFor(GovernorInput.INPUT_OUTSIDE_LEND_SCREEN, NodeKind.PF))
        val t = 20_000_000L
        assertEquals(LenderState.DRAINING, f.apply(ev, t).to)
        f.apply(FsmEvent.INFLIGHT_DONE, t + 1)
        f.apply(FsmEvent.CONDITIONS_MET, t + hold + 1)
        assertEquals(LenderState.ARMED, f.state)
        // Same inputs on a PA node are presence.
        assertNotNull(presenceEventFor(GovernorInput.SCREEN_ON, NodeKind.PA))
        laws.hit("PF-consent-not-presence")
    }

    @Test
    fun `Deck grace is 2 seconds`() {
        val t = 10_000_000L
        val f = servingAt(deck, t - 1_000_000)
        f.apply(FsmEvent.PRESENCE_SIGNAL, t)
        assertEquals(t + 2_000, f.snapshot().drainDeadlineMs)
        laws.hit("deck-grace-2s")
    }

    @Test
    fun `LP-0 classification table equals the spec lists`() {
        val presencePA = GovernorInput.entries.filter { it.classOn(NodeKind.PA) == InputClass.PRESENCE }.toSet()
        assertEquals(
            setOf(
                GovernorInput.SCREEN_ON, GovernorInput.INPUT_IDLE_TIME, GovernorInput.KEYGUARD_DISMISSAL, GovernorInput.FOREGROUND_APP,
                GovernorInput.HEAVY_FOREGROUND_PROCESS, GovernorInput.CONSOLE_USER, GovernorInput.LOGIN_STATE, GovernorInput.OTHER_PROCESS_CONTENTION,
            ),
            presencePA,
        )
        val conditionPA = GovernorInput.entries.filter { it.classOn(NodeKind.PA) == InputClass.CONDITION }.toSet()
        assertEquals(
            setOf(
                GovernorInput.POWER_SOURCE_AND_CHARGING, GovernorInput.BATTERY_LEVEL, GovernorInput.BATTERY_TEMPERATURE,
                GovernorInput.THERMAL_BAND, GovernorInput.MEMORY, GovernorInput.PATH, GovernorInput.SLEEP_IMMINENT,
            ),
            conditionPA,
        )
        val consentPF = GovernorInput.entries.filter { it.classOn(NodeKind.PF) == InputClass.CONSENT }.toSet()
        assertEquals(setOf(GovernorInput.SCREEN_ON, GovernorInput.LEND_SCREEN_FRONTMOST, GovernorInput.INPUT_INSIDE_LEND_SCREEN), consentPF)
        val presencePFOnly = GovernorInput.entries.filter { it.classOn(NodeKind.PA) == null && it.classOn(NodeKind.PF) == InputClass.PRESENCE }.toSet()
        assertEquals(setOf(GovernorInput.LEND_SCREEN_LEFT_FOREGROUND, GovernorInput.SCREEN_OFF, GovernorInput.INPUT_OUTSIDE_LEND_SCREEN), presencePFOnly)
        for (i in GovernorInput.entries) {
            assertTrue(i.classOn(NodeKind.PA) != null || i.classOn(NodeKind.PF) != null, "$i must be classified on some node kind")
            laws.hit("LP-0-classification")
        }
    }

    @Test
    fun `LP-1 the wire projection carries only fsm and the decline decision`() {
        val fields = AvailabilityWire::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("fsm", "declineCode", "retryAfterMs"), fields)
        laws.hit("LP-1-wire-shape")
        // Presence-tagged types are never serialisable.
        for (c in listOf(PresenceSample::class.java, GpuSample::class.java)) {
            assertNotNull(c.getAnnotation(PresenceTagged::class.java), "${c.simpleName} must carry @PresenceTagged")
            assertTrue(runCatching { Class.forName(c.name + "\$\$serializer") }.isFailure, "${c.simpleName} must not be @Serializable")
            laws.hit("LP-1-wire-shape")
        }
        for (s in LenderState.entries) {
            val w = wireOf(s)
            assertEquals(s, w.fsm)
            assertEquals(if (s == LenderState.SERVING) null else DECLINE_PEER_UNAVAILABLE, w.declineCode)
            assertNull(w.retryAfterMs)
        }
    }

    @Test
    fun `the hold-down cannot be configured below 600000 ms`() {
        assertFailsWith<IllegalArgumentException> { FsmConfig(graceMs = 1, holdDownMs = hold - 1) }
        laws.hit("prop-config-rejects-short-hold-down")
    }

    @Test
    fun `property test over random event sequences`() {
        val rnd = SplittableRandom(20260930)
        val events = FsmEvent.entries
        val boundaries = longArrayOf(0, 1, 599_999, 600_000, 600_001, 2_000, 5_000, 30_000)
        var sequences = 0
        var steps = 0L
        repeat(20_000) {
            val config = when (rnd.nextInt(3)) { 0 -> cfg; 1 -> deck; else -> pf }
            val f = ProviderFsm(config)
            var now = 1_000_000L
            var locks = 0
            repeat(40) {
                now += if (rnd.nextInt(3) == 0) boundaries[rnd.nextInt(boundaries.size)] else rnd.nextLong(0, 90_000)
                val ev = events[rnd.nextInt(events.size)]
                val before = f.snapshot()
                val step = f.apply(ev, now)
                val after = f.snapshot()
                steps++

                // serve entry is guarded by all three LP-2 conditions
                if (step.from != LenderState.SERVING && step.to == LenderState.SERVING) {
                    assertEquals(LenderState.ARMED, step.from)
                    assertEquals(FsmEvent.CONDITIONS_MET, ev)
                    assertFalse(before.asleep)
                    assertFalse(before.needsExplicitStart)
                    val last = before.lastPresenceMs
                    assertTrue(last == null || now - last >= hold, "SERVING entered inside the hold-down")
                    laws.hit("prop-serve-entry-guarded")
                }
                // listener effects
                val opens = step.effects.contains(FsmEffect.OPEN_LISTENER)
                val closes = step.effects.contains(FsmEffect.CLOSE_LISTENER)
                assertEquals(step.from != LenderState.SERVING && step.to == LenderState.SERVING, opens)
                assertEquals(step.from == LenderState.SERVING && step.to != LenderState.SERVING, closes)
                laws.hit("prop-listener-effects")
                // presence in SERVING drains in the same step
                if (ev == FsmEvent.PRESENCE_SIGNAL && step.from == LenderState.SERVING) {
                    assertEquals(LenderState.DRAINING, step.to)
                    laws.hit("LP-2-presence-drains-at-once")
                }
                // a delay lock is held exactly while SERVING or DRAINING
                step.effects.forEach {
                    if (it == FsmEffect.TAKE_DELAY_LOCK) locks++
                    if (it == FsmEffect.RELEASE_DELAY_LOCK) locks--
                }
                assertEquals(if (f.state == LenderState.SERVING || f.state == LenderState.DRAINING) 1 else 0, locks)
                laws.hit("prop-lock-balance")
                // DRAINING exits only through INFLIGHT_DONE / GRACE_EXPIRED; OFF is entered only by a user disable path
                if (step.from == LenderState.DRAINING && step.to != LenderState.DRAINING) {
                    assertTrue(ev == FsmEvent.INFLIGHT_DONE || ev == FsmEvent.GRACE_EXPIRED)
                    laws.hit("prop-drain-exits")
                }
                if (step.from != LenderState.OFF && step.to == LenderState.OFF) {
                    assertTrue(
                        (step.from == LenderState.ARMED && ev == FsmEvent.USER_DISABLE) ||
                            (step.from == LenderState.DRAINING && before.disableRequested),
                    )
                    laws.hit("prop-off-entry")
                }
                // a presence signal never lowers lastPresenceMs; only a presence signal moves it
                if (ev != FsmEvent.PRESENCE_SIGNAL) assertEquals(before.lastPresenceMs, after.lastPresenceMs)
                // the wire depends on the state alone: same state, same wire, whatever the cause history
                assertEquals(wireOf(f.state), f.wire())
                laws.hit("LP-1-wire-depends-on-state-only")
            }
            sequences++
        }
        Report.line("property test: $sequences sequences, $steps steps, seed 20260930")
        assertTrue(sequences > 0 && steps > 0)
    }

    /**
     * HLU-4: a node enabled while the owner is at the machine must not serve. The gate starts UNSETTLED (neither drained nor
     * eligible); only 60 s below 200 permille makes it eligible, whatever the first, blind, sample said.
     */
    private fun startupRig(): Triple<Governor, ProviderFsm, (Long, Int?, Int?, Boolean) -> List<FsmEvent>> {
        val cfg0 = NodeConfig()
        val g = Governor(cfg0, DesktopRules(cfg0))
        val f = ProviderFsm(FsmConfig(graceMs = 30_000, holdDownMs = cfg0.presenceHoldDownMs))
        val run = { t: Long, cpu: Int?, gpu: Int?, gpuCounter: Boolean ->
            val ev = g.evaluate(t, Readings(FakePower.ac(), ThermalReading(0, 40_000, 80_000, listOf("s")), HostSignals(), cpu, gpu, gpuCounter)).events
            ev.forEach { f.apply(it, t) }
            ev
        }
        return Triple(g, f, run)
    }

    @Test
    fun `startup while the machine is in use never serves, whatever the first sample was`() {
        val (_, f, run) = startupRig()
        f.apply(FsmEvent.USER_ENABLE, 0)
        assertEquals(emptyList(), run(0, null, null, false), "a blind first sample is not eligibility")
        var t = 2_000L
        repeat(150) {
            assertEquals(emptyList(), run(t, 390, null, false), "390 permille at t=$t: neither eligible nor drained")
            assertEquals(LenderState.ARMED, f.state, "must not be SERVING at t=$t while contention sits between 200 and 400")
            t += 2_000
        }
        laws.hit("startup-mid-band-never-serves")
        // genuinely heavy use is presence, as before
        var last = emptyList<FsmEvent>()
        repeat(6) { last = run(t, 700, null, false); t += 2_000 }
        assertEquals(listOf(FsmEvent.PRESENCE_SIGNAL), last)
        laws.hit("startup-mid-band-never-serves")
    }

    @Test
    fun `startup on a quiet machine serves only after 60 s below 200 permille, exactly`() {
        val (_, f, run) = startupRig()
        f.apply(FsmEvent.USER_ENABLE, 0)
        run(0, null, null, false)
        run(2_000, 100, null, false)
        assertEquals(emptyList(), run(2_000 + 59_999, 100, null, false), "59,999 ms below 200 is not 60 s")
        assertEquals(LenderState.ARMED, f.state)
        assertEquals(listOf(FsmEvent.CONDITIONS_MET), run(2_000 + 60_000, 100, null, false))
        assertEquals(LenderState.SERVING, f.state)
        laws.hit("startup-quiet-dwell-60s")
        // a reading back in the 200 to 400 band restarts the dwell
        val (_, f2, run2) = startupRig()
        f2.apply(FsmEvent.USER_ENABLE, 0)
        run2(0, 100, null, false)
        run2(30_000, 300, null, false)
        assertEquals(emptyList(), run2(60_000, 100, null, false), "the band reading restarted the dwell")
        assertEquals(listOf(FsmEvent.CONDITIONS_MET), run2(120_000, 100, null, false))
        laws.hit("startup-quiet-dwell-60s")
    }

    @Test
    fun `an unreadable sample is not known to be eligible and drains nobody`() {
        val (_, f, run) = startupRig()
        f.apply(FsmEvent.USER_ENABLE, 0)
        run(0, 100, null, false)
        assertEquals(listOf(FsmEvent.CONDITIONS_MET), run(60_000, 100, null, false))
        assertEquals(LenderState.SERVING, f.state)
        assertEquals(emptyList(), run(62_000, null, null, false), "blind while SERVING: no event, so no drain")
        assertEquals(LenderState.SERVING, f.state)
        f.apply(FsmEvent.CONDITION_LOST, 63_000)
        f.apply(FsmEvent.INFLIGHT_DONE, 63_001)
        assertEquals(LenderState.ARMED, f.state)
        assertEquals(emptyList(), run(64_000, null, null, false), "blind while ARMED: not eligible this tick")
        assertEquals(LenderState.ARMED, f.state)
        assertEquals(listOf(FsmEvent.CONDITIONS_MET), run(66_000, 100, null, false))
        laws.hit("startup-blind-sample-not-eligible")
    }

    @Test
    fun `a GPU counter that exists must settle too, and one that does not exist is off`() {
        val (_, f, run) = startupRig()
        f.apply(FsmEvent.USER_ENABLE, 0)
        var t = 0L
        while (t < 40_000) { assertEquals(emptyList(), run(t, 100, 100, true)); t += 2_000 }
        assertEquals(emptyList(), run(40_000, 100, null, true), "the counter exists but this sample is blind: the GPU dwell restarts")
        t = 42_000
        while (t < 102_000) { assertEquals(emptyList(), run(t, 100, 100, true), "t=$t: the CPU is long eligible but the GPU dwell began at 42,000"); t += 2_000 }
        assertEquals(LenderState.ARMED, f.state)
        assertEquals(listOf(FsmEvent.CONDITIONS_MET), run(102_000, 100, 100, true))
        laws.hit("startup-gpu-counter-must-settle")
        val (_, f2, run2) = startupRig()
        f2.apply(FsmEvent.USER_ENABLE, 0)
        run2(0, 100, null, false)
        assertEquals(listOf(FsmEvent.CONDITIONS_MET), run2(60_000, 100, null, false), "no GPU counter (NVIDIA): the rule is off")
        laws.hit("startup-gpu-counter-must-settle")
    }

    @Test
    fun `wire is identical whatever caused the drain`() {
        val t = 10_000_000L
        val wires = listOf(FsmEvent.PRESENCE_SIGNAL, FsmEvent.CONDITION_LOST, FsmEvent.SLEEP_IMMINENT, FsmEvent.USER_DISABLE).map { cause ->
            val f = servingAt(cfg, t - 1_000_000)
            f.apply(cause, t)
            assertEquals(LenderState.DRAINING, f.state)
            f.wire()
        }
        assertEquals(1, wires.toSet().size, "the wire must not reveal why the node is draining")
        laws.hit("LP-1-wire-depends-on-state-only")
    }

    @AfterAll
    fun everyLawExercisedAtLeastOnce() {
        laws.assertAllExercised("presence-laws")
    }
}
