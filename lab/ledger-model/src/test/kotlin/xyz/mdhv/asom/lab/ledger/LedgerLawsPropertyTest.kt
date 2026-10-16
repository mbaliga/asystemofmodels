package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.sim.SimConfig

/**
 * Laws L-L1..L-L16 as property tests over random valid protocol runs (LAB, oracle: self). Each law counts the cases that exercised it and the
 * run fails if a count is zero (R10). The runs go through the ledger's own classes; the instruments that feed the oracles sit outside them.
 */
class LedgerLawsPropertyTest {
    companion object {
        const val WORLDS = 400

        val measuredRun: LawTally by lazy {
            val t = LawTally()
            for (seed in 1L..WORLDS) t.add(runWorld(seed).trace.events)
            println("== ledger laws (LAB; oracle: self; NOT DEVICE EVIDENCE): $WORLDS random worlds, measured lane")
            t.all().filter { it.law != "L-L13" }.forEach { println("${it.law} iterations: ${it.cases}" + if (it.detail.isEmpty()) "" else " ${it.detail}") }
            println("L-L15 MEASURED sessions: ${t.measuredSessions} (n > 0), mismatches: ${t.result("L-L15").violations.size}; ESTIMATED sessions excluded: ${t.estimatedSessions}; closed by ledger failure: ${t.excludedSessions}")
            println("L-L16 per-frame-type counts: ${t.perType}")
            t
        }

        val estimatedRun: LawTally by lazy {
            val t = LawTally()
            for (seed in 1L..80L) t.add(runWorld(seed, SimConfig(seed = seed, measured = false)).trace.events)
            t
        }
    }

    @Test
    fun everyTraceLawHoldsAndExercisedAtLeastOneCase() {
        val t = measuredRun
        val bad = t.all().filter { it.law != "L-L13" && !it.ok }
        assertEquals(emptyList(), bad.map { it.law to it.violations.take(3) })
        for (r in t.all().filter { it.law != "L-L13" }) assertTrue(r.cases > 0, "${r.law} exercised zero cases")
    }

    @Test
    fun everyListedLawIsPresent() {
        val names = measuredRun.all().map { it.law }.toSet()
        for (n in listOf("L-L1", "L-L2", "L-L3", "L-L4", "L-L5", "L-L5b", "L-L7", "L-L8", "L-L9", "L-L10", "L-L12", "L-L14", "L-L15", "L-L16")) assertTrue(n in names, "$n missing")
    }

    @Test
    fun l15RunsOnMeasuredSessionsAndFailsWhenAllAreEstimated() {
        val m = measuredRun
        assertTrue(m.measuredSessions > 0, "no MEASURED session")
        assertEquals(emptyList(), LedgerLaws.l15NonVacuity(m.measuredSessions, m.estimatedSessions).violations)
        val e = estimatedRun
        assertEquals(0, e.measuredSessions, "the estimated lane must hold no MEASURED session")
        assertTrue(e.estimatedSessions > 0, "the estimated lane must hold ESTIMATED sessions")
        assertEquals(emptyList(), e.result("L-L15").violations, "ESTIMATED sessions are excluded from the equality, not counted as mismatches")
        val v = LedgerLaws.l15NonVacuity(e.measuredSessions, e.estimatedSessions)
        assertEquals(1, v.violations.size, "a run of only ESTIMATED sessions fails L-L15")
    }

    @Test
    fun l16CountsEveryListedFrameType() {
        val zero = FrameRows.L16_KINDS.filter { (measuredRun.perType[it] ?: 0) == 0 }
        assertEquals(emptyList(), zero, "L-L16 never exercised these listed frame types")
    }

    @Test
    fun estimatedSessionsStillProduceOneRowPerFrame() {
        val r = estimatedRun
        assertEquals(emptyList(), r.result("L-L16").violations)
        assertEquals(emptyList(), r.result("L-L14").violations)
    }

    @Test
    fun l7NoFrameShapeHasARequestIdMember() {
        val fields = FrameSpec::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { "request" in it }, "FrameSpec has a request member: $fields")
        assertTrue(measuredRun.result("L-L7").cases > 0)
    }
}
