package xyz.mdhv.asom.lab.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression tests for the fix-bench-exec findings (BRP-06, BRP-10). SIMULATED - NOT DEVICE EVIDENCE. */
class SustainZeroTokenWindowTest {
    private fun run(cfg: FakeConfig, plan: RunPlan = RunPlans.STANDARD.copy(wallCapMs = emptyMap())): Pair<BenchSession, RunOutcome> {
        val host = FakeHost(cfg)
        val sheet = ConsentSheet.forPlan(plan, 0L, emptyList(), false, false)
        val session = BenchSession(host, plan, BenchSets.Q1, sheet.confirm(sheet.textSha256, host.epochMillis()))
        return session to session.run()
    }

    private fun slowPrefill(usPerToken: Long): FakeConfig {
        val base = Scenarios.preset("phone", "standard")
        return base.copy(
            heat = Scenarios.HEAT_PROFILES.getValue("flat"),
            tiers = base.tiers.mapValues { (_, sp) -> sp.copy(prefillUsPerToken = usPerToken) },
        )
    }

    private fun docOf(o: RunOutcome): BenchDoc = (o as RunOutcome.Completed).doc

    @Test
    fun aWindowWhosePrefillOutlastsItIsNeverRecordedWithZeroTokens() {
        val (session, out) = run(slowPrefill(300_000L))
        val doc = docOf(out)
        val sustain = doc.sustain
        if (sustain != null) assertTrue(sustain.windows.all { it.tokens > 0L }, "zero-token window kept: ${sustain.windows}")
        BenchCodec.decode(BenchCodec.encode(doc), RdContext(false))
        assertTrue(session.trace.any { it.startsWith("SUSTAIN") }, "the sustain phase must have run: ${session.trace.takeLast(6)}")
    }

    @Test
    fun aPhaseWhoseEveryWindowIsEmptyIsDroppedNotSigned() {
        val (session, out) = run(slowPrefill(300_000L))
        val doc = docOf(out)
        assertNull(doc.sustain, "no window made progress, so there is no sustain result")
        assertTrue(session.trace.any { it == "SUSTAIN skipped (no window produced a token)" }, session.trace.takeLast(6).toString())
        assertEquals(0, session.trace.count { it.startsWith("SUSTAIN end") })
    }

    @Test
    fun aNormalSustainPhaseIsUnchanged() {
        val base = Scenarios.preset("phone", "standard")
        val (_, out) = run(base.copy(heat = Scenarios.HEAT_PROFILES.getValue("flat")))
        val doc = docOf(out)
        assertNotNull(doc.sustain)
        assertEquals(0L, doc.sustain!!.windows.first().tStartMs)
        assertTrue(doc.sustain!!.windows.all { it.tokens > 0L })
    }

    @Test
    fun aHardCeilingBeforeTheFirstTokenEndsTheRunWithoutAnEmptySustainResult() {
        val c = Counter("hard ceiling at sustain start")
        for (at in 0L..2_000_000L step 250L) {
            val r = Scenarios.run(BJ.parse("""{"preset":"phone","plan":"standard","heat":"flat","injections":[{"atMs":$at,"kind":"THERMAL_CODE","value":3}]}"""))
            val s = r.doc?.sustain
            if (s != null) assertTrue(s.windows.isNotEmpty() && s.windows.all { it.tokens > 0L }, "at=$at kept an empty window list")
            if (r.events.any { it == "SUSTAIN skipped (no window produced a token)" }) {
                c.hit()
                assertNull(s)
                assertTrue(r.leaks.isEmpty(), "at=$at leaked ${r.leaks}")
            }
        }
        c.requireNonVacuous(1)
    }
}
