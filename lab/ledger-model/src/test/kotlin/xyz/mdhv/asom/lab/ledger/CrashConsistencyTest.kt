package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.laws.Ev
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.laws.Received
import xyz.mdhv.asom.lab.ledger.laws.Sent
import xyz.mdhv.asom.lab.ledger.sim.ProcessDeath
import xyz.mdhv.asom.lab.ledger.sim.Scenarios
import xyz.mdhv.asom.lab.ledger.sim.SimConfig
import xyz.mdhv.asom.lab.ledger.sim.SimWorld

/**
 * L-L11: under injected process death at EVERY step of the run (every append, send, delivery and engine read), the rows on each node are a prefix of
 * the failure-free run's rows (the table of contract.md 4.3 truncated at the death point), there is never a missing intent for content that moved, and
 * never an outcome for an attempt that did not reach it. Both processes die together; the death of a single node is not modelled (ERRATA ERR-LL-7).
 */
class CrashConsistencyTest {
    private fun runUntilDeath(cfg: SimConfig, script: (SimWorld) -> List<xyz.mdhv.asom.lab.ledger.sim.Step>): SimWorld {
        val w = SimWorld(cfg)
        try {
            w.run(script(w))
        } catch (_: ProcessDeath) {
        }
        return w
    }

    private fun checkOne(name: String, ref: SimWorld, w: SimWorld, k: Int, counts: IntArray) {
        for (node in listOf("A", "B")) {
            val want = ref.rows(node)
            val got = w.rows(node)
            assertEquals(want.take(got.size), got, "$name death at step $k: $node's durable rows are not a prefix of the failure-free run")
        }
        val t = w.trace.events
        for (r in listOf(LedgerLaws.l1(t), LedgerLaws.l2(t), LedgerLaws.l3(t), LedgerLaws.l14(t), LedgerLaws.l4(t))) {
            assertEquals(emptyList(), r.violations, "$name death at step $k: ${r.law}")
        }
        val rows = listOf("A", "B").flatMap { n -> w.rows(n).map { n to it } }
        for ((node, r) in rows) {
            if (r.phase != Phase.OUTCOME || r.attemptId == null || (r.meshKind != MeshKind.INFER_SENT && r.meshKind != MeshKind.INFER_SERVED)) continue
            val hasIntent = rows.any { it.first == node && it.second.attemptId == r.attemptId && it.second.phase == Phase.INTENT && it.second.meshKind == r.meshKind }
            val reached = if (r.meshKind == MeshKind.INFER_SENT) t.any { it is Sent && it.node == node && it.frame.attemptId == r.attemptId && it.frame.kind == FrameKind.INFER_OFFER }
            else t.any { it is Received && it.node == node && it.frame.attemptId == r.attemptId && it.frame.kind == FrameKind.INFER_OFFER }
            assertTrue(reached, "$name death at step $k: an outcome row for attempt ${r.attemptId} on $node that never reached it")
            if (r.meshKind == MeshKind.INFER_SENT) assertTrue(hasIntent, "$name death at step $k: a requester outcome without its intent")
            counts[0]++
        }
        for ((node, r) in rows) {
            if (r.phase == Phase.INTENT && r.attemptId != null && rows.none { it.first == node && it.second.attemptId == r.attemptId && it.second.phase == Phase.OUTCOME }) counts[1]++
        }
        counts[2]++
    }

    private fun sweep(name: String, cfgFor: (Int?) -> SimConfig, script: (SimWorld) -> List<xyz.mdhv.asom.lab.ledger.sim.Step>, counts: IntArray) {
        val ref = SimWorld(cfgFor(null))
        ref.run(script(ref))
        assertEquals(null, ref.abortReason)
        val total = ref.kill.steps
        assertTrue(total > 50, "$name: too few steps ($total)")
        for (k in 0..total) checkOne(name, ref, runUntilDeath(cfgFor(k), script), k, counts)
    }

    @Test
    fun deathAtEveryStepLeavesAPrefixOfTheFailureFreeRowsAndNeverBreaksTheWriteAheadLaws() {
        val counts = IntArray(3)
        sweep("full script", { SimConfig(seed = 1, killAt = it) }, { FullScript.steps(it) }, counts)
        for (seed in listOf(2L, 4L, 6L, 9L, 12L)) sweep("random $seed", { SimConfig(seed = seed, killAt = it) }, { Scenarios.random(it, seed) }, counts)
        assertTrue(counts[0] > 100, "outcome rows examined: ${counts[0]}")
        assertTrue(counts[1] > 20, "crash points that left an intent without an outcome ('outcome unknown'): ${counts[1]}")
        println("L-L11 iterations: ${counts[2]} death points (every step of 6 runs); ${counts[0]} outcome rows checked against their precursors; ${counts[1]} intents left without an outcome (outcome unknown, never guessed)")
    }
}
