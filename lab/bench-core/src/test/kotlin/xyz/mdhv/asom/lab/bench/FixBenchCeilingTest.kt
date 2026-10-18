package xyz.mdhv.asom.lab.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Track fix4-bench-docs, A-F1: benchmark.md 11.3 reads "on laptops, AC lost during the sustained phase" for macOS, and 11.5 asks for no charger
 * on a quick run. A laptop on battery is not a ceiling outside the sustained phase. SIMULATED - NOT DEVICE EVIDENCE.
 */
class FixBenchCeilingTest {
    private val cool = ThermalReading(true, 0, 300, 300)
    private val live = Presence(foreground = true, screenOn = true, batterySaver = false, lowPowerMode = false)

    private fun name(c: Ceiling?): String = when (c) {
        is Ceiling.Hard -> "hard ${c.reason}"
        is Ceiling.Soft -> "soft ${c.reason}"
        null -> "none"
    }

    private fun macLaptop(plan: String, source: String, injections: String = "[]"): Scenarios.TraceResult {
        val base = BJ.parse("""{"preset":"desktop","plan":"$plan","platform":"macos","injections":$injections}""")
        val (cfg0, pack) = Scenarios.parse(base)
        val cfg = cfg0.copy(
            device = cfg0.device.copy(platform = "macos", form = "laptop"),
            power = PowerReading(source, 900, true),
        )
        val host = FakeHost(cfg)
        val token = Scenarios.consentFor(base, pack.first, host.epochMillis())
        val session = BenchSession(host, pack.first, BenchSets.Q1, token, pack.second)
        host.onStop = { session.stop() }
        val out = session.run()
        val outcome = when (out) {
            is RunOutcome.Completed -> "completed"
            is RunOutcome.Aborted -> "aborted ${out.reason}"
            is RunOutcome.Refused -> "refused ${out.reasons.joinToString(",")}"
        }
        return Scenarios.TraceResult(session.trace.toList(), outcome, null, host.leaks(), null)
    }

    @Test
    fun aQuickOrCiRunOnAMacLaptopOnBatteryIsNotAbortedForAChargerThatWasNeverRequired() {
        val c = Counter("a-f1 quick and ci on battery")
        for (plan in listOf("quick", "ci")) {
            val r = macLaptop(plan, "battery")
            assertEquals("completed", r.outcome, "$plan: ${r.events.takeLast(4)}")
            assertTrue(r.leaks.isEmpty(), "${r.leaks}")
            c.hit()
        }
        c.requireNonVacuous(2)
    }

    @Test
    fun theLaptopAcCeilingFiresOnlyWhenTheCallerSaysTheSustainedPhaseIsRunning() {
        val c = Counter("a-f1 evaluate")
        fun eval(source: String, acLossCounts: Boolean) =
            name(Ceilings.evaluate("macos", "laptop", cool, PowerReading(source, 700, true), live, 0L, acLossCounts))
        assertEquals("hard CHARGER_REMOVED", eval("battery", true)); c.hit()
        assertEquals("none", eval("battery", false)); c.hit()
        assertEquals("none", eval("ac", true)); c.hit()
        assertEquals("none", eval("ac", false)); c.hit()
        assertEquals("hard THERMAL_HARD", name(Ceilings.evaluate("macos", "laptop", ThermalReading(true, 4, null, null), PowerReading("battery", 700, true), live, 0L, false))); c.hit()
        c.requireNonVacuous(5)
    }

    @Test
    fun aStandardRunOnAMacLaptopStillAbortsWhenAcIsLostAndAcWasRequired() {
        val c = Counter("a-f1 standard keeps CHARGER_REMOVED")
        val r = macLaptop("standard", "ac", """[{"atMs":100000,"kind":"CHARGER_REMOVED"}]""")
        assertEquals("aborted CHARGER_REMOVED", r.outcome, "${r.events.takeLast(4)}")
        c.hit()
        c.requireNonVacuous(1)
    }
}
