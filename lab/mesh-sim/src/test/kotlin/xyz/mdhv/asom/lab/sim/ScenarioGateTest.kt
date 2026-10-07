package xyz.mdhv.asom.lab.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.ledger.laws.ContentSent
import xyz.mdhv.asom.lab.router.Tier

/**
 * The L0.6 first slice (LAB_SPEC 6.9): SC01, SC04, SC06 and SC09 for seeds 1..20. Every run also passes the simulator laws (RL4, RL14..RL18, RL21, RL22) and the ledger laws
 * L-L1..L-L14 over its trace. Evidence label: SIMULATED — NOT DEVICE EVIDENCE.
 */
class ScenarioGateTest {
    companion object {
        val SEEDS = 1L..20L
    }

    private fun placementPermille(run: SimRun, node: String): Long {
        val ok = run.res.requests.filter { it.status == 200 }
        return ok.count { it.servedBy == node } * 1000L / ok.size
    }

    private fun expectations(run: SimRun, ctx: String) {
        val e = run.sc.expect
        for ((node, min) in e.minPlacementPermille) assertTrue(placementPermille(run, node) >= min, "$ctx: $node served ${placementPermille(run, node)} permille of chat, expected >= $min")
    }

    private fun everyRequestEnded(run: SimRun, ctx: String) {
        assertTrue(run.res.requests.isNotEmpty(), "$ctx: no request arrived")
        for (r in run.res.requests) assertTrue(r.status != 0, "$ctx: ${r.id} never ended")
    }

    @Test
    fun sc01PhoneAndMac() {
        val t = Tally()
        for (seed in SEEDS) {
            val ctx = "SC01 seed $seed"
            val run = Sim.runMain("SC01", seed)
            everyRequestEnded(run, ctx)
            expectations(run, ctx)
            val b1 = Sim.runMain("SC01", seed, Variant.B1)
            assertTrue(b1.res.selfEnergyMilliJ > 0, "$ctx: B1 used no battery")
            val reduction = (b1.res.selfEnergyMilliJ - run.res.selfEnergyMilliJ) * 1000 / b1.res.selfEnergyMilliJ
            assertTrue(reduction >= run.sc.expect.minBatteryReductionPermille!!, "$ctx: the phone's energy is ${run.res.selfEnergyMilliJ} mJ against B1's ${b1.res.selfEnergyMilliJ} mJ (reduction $reduction permille)")
            t.add("SC01/battery-reduction-permille-min", 1)
            SimChecks.all(run, t, ctx)
        }
        t.print("SC01 seeds 1..20")
        assertEquals(emptyList(), t.violations)
    }

    @Test
    fun sc04DellVanishesMidStream() {
        val t = Tally()
        var interrupted = 0
        for (seed in SEEDS) {
            val ctx = "SC04 seed $seed"
            val run = Sim.runMain("SC04", seed)
            everyRequestEnded(run, ctx)
            val hit = run.res.requests.filter { it.interrupted }
            assertTrue(hit.isNotEmpty(), "$ctx: the mid-stream vanish interrupted no request")
            interrupted += hit.size
            for (r in hit) {
                assertEquals("MESH_STREAM_INTERRUPTED", r.error, "$ctx: ${r.id}")
                assertEquals(502, r.status, "$ctx: ${r.id}")
                val last = r.attempts.last()
                assertTrue(last.deliveredToClient, "$ctx: ${r.id}: the interrupted attempt had delivered bytes")
                assertEquals(r.attempts.size - 1, r.attempts.indexOfFirst { it.deliveredToClient }, "$ctx: ${r.id}: no attempt after the delivered bytes")
                assertEquals("dell", last.target)
                joinedLedgers(run, last.attemptId!!, ctx)
            }
            SimChecks.all(run, t, ctx, frames = true)
        }
        t.print("SC04 seeds 1..20")
        println("SC04 interrupted requests over 20 seeds: $interrupted")
        assertEquals(emptyList(), t.violations)
    }

    /** Both ledgers, joined on `attemptId`: the requester wrote its intent and its outcome; the lender wrote its intent before reading the body; the classes agree. */
    private fun joinedLedgers(run: SimRun, attemptId: String, ctx: String) {
        val req = run.res.ledgerRows.getValue(run.sc.self.id).filter { it.attemptId == attemptId }
        val lend = run.res.ledgerRows.getValue("dell").filter { it.attemptId == attemptId }
        val reqIntent = req.filter { it.meshKind == MeshKind.INFER_SENT && it.phase == Phase.INTENT }
        val reqOutcome = req.filter { it.meshKind == MeshKind.INFER_SENT && it.phase == Phase.OUTCOME }
        assertEquals(1, reqIntent.size, "$ctx: requester intent rows for $attemptId")
        assertEquals(1, reqOutcome.size, "$ctx: requester outcome rows for $attemptId")
        assertTrue(reqOutcome.single().bytesOut > 0, "$ctx: the requester's outcome row records the body it sent")
        assertEquals(LabEgress.peerClass, reqOutcome.single().egress, "$ctx: the requester's egress for the attempt")
        assertTrue(lend.any { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.INTENT }, "$ctx: the lender's intent row for $attemptId")
    }

    @Test
    fun sc06LyingManifest() {
        val t = Tally()
        for (seed in SEEDS) {
            val ctx = "SC06 seed $seed"
            val run = Sim.runMain("SC06", seed)
            everyRequestEnded(run, ctx)
            val kept = run.res.keptObservationsToDiscrepant["liar"]
            assertNotNull(kept, "$ctx: the liar never reached DISCREPANT")
            assertTrue(kept <= 5, "$ctx: DISCREPANT after $kept kept observations, expected <= 5")
            assertEquals(null, run.res.keptObservationsToDiscrepant["mac"], "$ctx: the honest peer was never DISCREPANT")
            val after = run.res.requests.drop(50).filter { it.hindsight != null }
            assertTrue(after.size >= 50, "$ctx: too few requests after the 50th")
            val share = after.count { it.servedBy == "liar" } * 1000L / after.size
            val hindsight = after.count { it.hindsight == "liar" } * 1000L / after.size
            val bound = hindsight + run.sc.expect.maxShareOverHindsightPermille.getValue("liar")
            assertTrue(share <= bound, "$ctx: the liar's share after 50 requests is $share permille, B4's is $hindsight (bound $bound)")
            t.add("SC06/share-vs-B4", after.size)
            SimChecks.all(run, t, ctx)
        }
        t.print("SC06 seeds 1..20")
        assertEquals(emptyList(), t.violations)
    }

    @Test
    fun sc09DeviceOnlyAndLocalOnlyNeverReachAPeer() {
        val t = Tally()
        for (seed in SEEDS) {
            val ctx = "SC09 seed $seed"
            val run = Sim.runMain("SC09", seed)
            everyRequestEnded(run, ctx)
            val restricted = run.res.requests.filter { it.app.pkg == "app.private" || it.app.pkg == "app.local" }
            assertTrue(restricted.size >= 30, "$ctx: only ${restricted.size} restricted requests")
            val content = run.res.trace.events.filterIsInstance<ContentSent>().filter { it.cls == LabEgress.peerClass }.map { it.requestId }.toSet()
            for (r in restricted) {
                assertTrue(r.attempts.none { it.tier != Tier.SELF }, "$ctx: ${r.id} (${r.app.pkg}) had a non-local attempt")
                assertTrue(r.planned.none { it.startsWith("PEER") || it.startsWith("CLOUD") }, "$ctx: ${r.id} planned ${r.planned}")
                assertTrue(r.id !in content, "$ctx: ${r.id} sent content to a peer")
            }
            val open = run.res.requests.filter { it.app.pkg == "app.open" }
            assertTrue(open.any { r -> r.attempts.any { it.tier == Tier.PEER } }, "$ctx: the open app never used the peer (the scenario would be vacuous)")
            t.add("SC09/restricted-requests", restricted.size)
            SimChecks.all(run, t, ctx)
        }
        t.print("SC09 seeds 1..20")
        assertEquals(emptyList(), t.violations)
    }
}
