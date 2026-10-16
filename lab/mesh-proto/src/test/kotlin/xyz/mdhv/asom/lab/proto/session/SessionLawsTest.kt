package xyz.mdhv.asom.lab.proto.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.FailMode
import xyz.mdhv.asom.lab.ledger.FrameRows

/**
 * Laws L-L13, L-L14 and L-L16 (and the transport accounting of L-L15) over seeded random SESSION runs of the real session engine, in both TLS roles, over
 * in-memory connections (LAB, oracle: self; NOT DEVICE EVIDENCE). The oracles read recorded bytes and events; each law counts the cases that exercised it and
 * the class fails if a count is zero (R10). L-L15 over real TLS is proven later; here it is checked against RFC 8446 record arithmetic on a counting connection.
 */
class SessionLawsTest {
    class LawRun(val counts: Counts, val runs: Int, val connects: Int, val l13: L13Result, val failureRuns: Int, val pairingRuns: Int)

    companion object {
        const val CLEAN_RUNS = 600
        const val SWEEP_SEEDS = 40
        const val PAIRING_RUNS = 60

        val laws: LawRun by lazy {
            val counts = Counts()
            var connects = 0
            for (seed in 1L..CLEAN_RUNS) {
                val w = RandomRuns.run(seed)
                try {
                    counts.add(RunOracle(w.log, w, strict = true).checkAll())
                } catch (e: AssertionError) {
                    throw AssertionError("clean run seed $seed: ${e.message}", e)
                }
                connects += L14.check(w.log)
            }
            var pairingRuns = 0
            for (seed in 1L..PAIRING_RUNS) {
                val p = PairWorld(seed)
                p.run()
                PairOracle.check(p, counts)
                pairingRuns++
            }
            val l13 = L13Result()
            var failureRuns = 0
            for (seed in 1L..SWEEP_SEEDS) {
                val base = RandomRuns.run(seed)
                val nA = base.log.all<Ev.Appended>().count { it.node == "A" }
                val nB = base.log.all<Ev.Appended>().count { it.node == "B" }
                for ((node, n) in listOf("A" to nA, "B" to nB)) for (k in 0..n) for (variant in 0..1) {
                    val sticky = (k + seed + variant) % 2L == 0L
                    val mode = if (variant == 0) FailMode.BEFORE_WRITE else FailMode.AFTER_WRITE
                    val plan = FailPlan(setOf(k), sticky, mode)
                    val w = RandomRuns.run(seed, failA = if (node == "A") plan else null, failB = if (node == "B") plan else null)
                    try {
                        counts.add(RunOracle(w.log, w, strict = false).checkAll())
                        L13.check(w, mapOf(node to sticky), l13)
                    } catch (e: AssertionError) {
                        throw AssertionError("failure run seed $seed, $node fails append #$k (sticky=$sticky, $mode): ${e.message}", e)
                    }
                    connects += L14.check(w.log)
                    failureRuns++
                }
            }
            println("== session laws (LAB; oracle: self; NOT DEVICE EVIDENCE): $CLEAN_RUNS clean runs, $failureRuns failure-injection runs, $pairingRuns pairing runs")
            println("L-L13 iterations: ${l13.runsWithFailure} (failure events checked: ${l13.failureEvents}; control-row failures: ${l13.controlFailures}; FC-1 requester intent: ${l13.requesterIntentFailures}; FC-4 lender intent: ${l13.lenderIntentFailures}; FC-5 lender outcome before INFER_END: ${l13.lenderEndOutcomeFailures}; lender decline outcome: ${l13.lenderDeclineOutcomeFailures}; DIAL intent: ${l13.dialFailures}; frames after control failure: ${l13.framesAfterControlFailure}; content frames after a sticky failure: ${l13.contentFramesAfterStickyFailure})")
            println("L-L14 iterations: $connects (every connect had a durable DIAL intent before it)")
            println("L-L15 MEASURED sessions: ${counts.measuredSessions} (n > 0), mismatches: 0 (clean runs only; ESTIMATED lane in AccountingAndDialTest)")
            println("L-L16 iterations: ${counts.sessions} sessions; frames matched to a row on the node that sent or received them: ${counts.checks}; rows of failed appends tolerated: ${counts.toleratedFailedRows}")
            println("L-L16 per-frame-type counts: ${counts.perType}")
            println("structural laws (iterations): ${counts.structural}")
            println("LP-1 STATE frames checked producer-strict: ${counts.stateFrames}; frames that carried st to a state-scoped peer: ${counts.stSent}; frames where st was withheld from a peer without scope state: ${counts.stWithheld}")
            LawRun(counts, CLEAN_RUNS, connects, l13, failureRuns, pairingRuns)
        }
    }

    @Test
    fun l13ZeroFramesAfterAControlRowFailureAndNoContentAfterAFailedIntentOrOutcome() {
        val r = laws.l13
        assertTrue(r.runsWithFailure > 0, "L-L13 exercised zero runs")
        assertTrue(r.controlFailures > 0, "no control-row failure was injected")
        assertTrue(r.requesterIntentFailures > 0, "no FC-1 case")
        assertTrue(r.lenderIntentFailures > 0, "no FC-4 case")
        assertTrue(r.lenderEndOutcomeFailures > 0, "no FC-5 case")
        assertTrue(r.lenderDeclineOutcomeFailures > 0, "no failed decline outcome case")
        assertTrue(r.dialFailures > 0, "no failed DIAL intent")
        assertEquals(0, r.framesAfterControlFailure)
        assertEquals(0, r.contentFramesAfterStickyFailure)
    }

    @Test
    fun l14EveryConnectHadADurableDialIntentBeforeIt() {
        assertTrue(laws.connects > 0, "L-L14 exercised zero connects")
    }

    @Test
    fun l15TheAccountingIsExactOnMeasuredSessions() {
        assertTrue(laws.counts.measuredSessions > 0, "L-L15 exercised zero MEASURED sessions")
    }

    @Test
    fun l16EveryListedFrameTypeWasExercisedAndMatchedToARowOnEachNode() {
        val zero = FrameRows.L16_KINDS.filter { (laws.counts.perType[it] ?: 0) == 0 }
        assertEquals(emptyList(), zero, "L-L16 never exercised these listed frame types")
        assertTrue(laws.counts.sessions > 0 && laws.counts.checks > 0)
        assertEquals(0, laws.counts.residualViolations)
    }

    @Test
    fun theStructuralLawsEachExercisedCases() {
        for (law in listOf("L-L1", "L-L2", "L-L3", "L-L6", "L-L7", "L-L8", "L-L9", "L-L12")) assertTrue((laws.counts.structural[law] ?: 0) > 0, "$law exercised zero cases")
    }

    @Test
    fun lp1TheStateFramesAreProducerStrictAndStIsOnlyForAPeerWithScopeState() {
        assertTrue(laws.counts.stateFrames > 0, "no STATE frame was produced")
        assertTrue(laws.counts.stSent > 0, "no frame carried st to a state-scoped peer")
        assertTrue(laws.counts.stWithheld > 0, "st was never withheld from a peer without scope state")
    }
}
