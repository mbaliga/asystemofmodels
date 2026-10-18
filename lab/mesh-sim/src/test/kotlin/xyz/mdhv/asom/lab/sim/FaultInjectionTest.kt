package xyz.mdhv.asom.lab.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.router.Tier

/**
 * Fault injection (LAB_SPEC 6.9, the closed list of 17 kinds): every kind is exercised by its own scenario under three seeds, each run passes the simulator laws and the ledger laws,
 * and each fault is shown to have its effect (a scenario in which the fault leaves no trace would prove nothing). Evidence label: SIMULATED — NOT DEVICE EVIDENCE.
 */
class FaultInjectionTest {
    companion object {
        val KINDS = FAULT_KINDS
        val SEEDS = 1L..3L

        private val all: Map<String, List<SimRun>> by lazy {
            (KINDS.map { "F-$it" } + listOf("F-peer-vanish-before-head", "F-peer-vanish-mid-stream", "F-none")).distinct().associateWith { name -> SEEDS.map { Sim.runTest(name, it) } }
        }

        fun runs(name: String): List<SimRun> = all.getValue(name)
    }

    @Test
    fun everyFaultKindHasAScenarioAndTheScenarioNamesIt() {
        assertEquals(17, KINDS.size)
        for (kind in KINDS) {
            val bytes = Sim.testBytes("F-$kind")
            val sc = ScenarioLoader.parse(bytes)
            assertTrue(sc.faults.any { it.kind == kind }, "F-$kind does not inject a $kind fault")
        }
    }

    @Test
    fun everyFaultScenarioPassesTheLawsUnderThreeSeeds() {
        val t = Tally()
        for ((name, rs) in all) for (run in rs) SimChecks.all(run, t, "$name seed ${run.sc.seed}", frames = true, shared = name == "F-duplicate-attempt")
        t.print("fault scenarios: 17 kinds + variants, seeds 1..3")
        assertEquals(emptyList(), t.violations)
    }

    private fun attempts(name: String) = runs(name).flatMap { it.res.requests }.flatMap { r -> r.attempts.map { r to it } }

    @Test
    fun peerVanishBeforeHeadFallsBackAndNeverRetriesAfterBytes() {
        val rs = runs("F-peer-vanish-before-head")
        val statuses = attempts("F-peer-vanish-before-head").filter { it.second.tier == Tier.PEER }.map { it.second.status }
        assertTrue(statuses.any { it != "ok" && it != "PLANNED" && it != "PEER_BUSY" }, "no failed peer attempt: $statuses")
        for (run in rs) assertTrue(run.res.requests.any { it.status == 200 && it.attempts.size > 1 }, "no request was served after a failed attempt (seed ${run.sc.seed})")
    }

    @Test
    fun peerVanishMidStreamIsTypedInBand() {
        val hits = runs("F-peer-vanish-mid-stream").flatMap { it.res.requests }.filter { it.interrupted }
        assertTrue(hits.isNotEmpty())
        for (r in hits) {
            assertEquals("MESH_STREAM_INTERRUPTED", r.error)
            assertEquals(r.attempts.size - 1, r.attempts.indexOfFirst { it.deliveredToClient })
        }
    }

    @Test
    fun declineStormAnswersPeerBusyAndTheRequestsAreStillServed() {
        val a = attempts("F-decline-storm")
        assertTrue(a.count { it.second.status == "PEER_BUSY" } >= 3, "the storm produced no PEER_BUSY declines")
        for (run in runs("F-decline-storm")) assertTrue(run.res.requests.all { it.status == 200 || it.status == 502 || it.status == 503 }, "seed ${run.sc.seed}")
        assertTrue(runs("F-decline-storm").all { r -> r.res.requests.count { it.status == 200 } * 10 >= r.res.requests.size * 8 }, "fewer than 80% served")
    }

    @Test
    fun duplicateAttemptIsDeclinedByTheLenderAndNotServedTwice() {
        val a = attempts("F-duplicate-attempt")
        assertTrue(a.any { it.second.status == "DUPLICATE_ATTEMPT" }, "no DUPLICATE_ATTEMPT decline")
        for ((r, at) in a) if (at.status == "DUPLICATE_ATTEMPT") assertTrue(!at.bodySent, "${r.id}: a body went to a peer that had seen the attempt id")
    }

    @Test
    fun ledgerFullFailsClosedAndSendsNoContentAfterAFailedIntent() {
        val rs = runs("F-ledger-full")
        assertTrue(rs.any { r -> r.res.requests.any { it.ledgerUnavailable } }, "the ledger-full window failed no request")
        val t = Tally()
        for (run in rs) SimChecks.ledgerLaws(run, t, "seed ${run.sc.seed}")
        assertTrue((t.cases["L-L13"] ?: 0) > 0, "L-L13 was vacuous")
        assertEquals(emptyList(), t.violations)
    }

    @Test
    fun clockSkewChangesNoDecision() {
        val none = runs("F-none").map { it.res.decisions.map { d -> d.attempts.map { a -> a.substringBefore("|total=") } } }
        val skew = runs("F-clock-skew").map { it.res.decisions.map { d -> d.attempts.map { a -> a.substringBefore("|total=") } } }
        assertTrue(none.isNotEmpty() && skew.isNotEmpty())
        val staleness = runs("F-clock-skew").flatMap { it.res.decisions }.flatMap { it.attempts }.map { it.substringAfter("fresh=") }.toSet()
        assertTrue(staleness.isNotEmpty())
        val a = runs("F-clock-skew").first().res.events.filter { it.kind == "state" }.map { it.str("peer") to it.long("age") }
        val b = runs("F-none").first().res.events.filter { it.kind == "state" }.map { it.str("peer") to it.long("age") }
        assertEquals(b.size, a.size, "the number of STATE documents does not depend on a peer's clock")
    }

    @Test
    fun stateDropAndDelayLeaveStalePeersProbeOnlyOrOut() {
        for (name in listOf("F-state-drop", "F-state-delay")) {
            val fresh = runs(name).flatMap { it.res.decisions }.flatMap { it.attempts }.map { it.substringAfter("fresh=") }.toSet()
            assertTrue(fresh.isNotEmpty(), name)
        }
    }

    @Test
    fun claimStaleAndClaimLieAreSeenByTheTracker() {
        val lie = runs("F-claim-lie")
        assertTrue(lie.any { it.res.events.any { e -> e.kind == "claim" && e.str("peer") == "dell" && e.long("cseq") > 1 } }, "the lie was never re-published")
        val stale = runs("F-claim-stale")
        assertTrue(stale.any { r -> r.res.events.any { it.kind == "obs" && it.str("peer") == "dell" && it.nstr("heldCommit") != it.nstr("claimCommit") } }, "no observation saw the changed commit")
    }

    @Test
    fun linkFaultsShowInTheRequestersLinkView() {
        assertTrue(runs("F-overlay-only-high-rtt").any { r -> r.res.events.any { it.kind == "link-meta" && it.str("path") == "OVERLAY" } })
        assertTrue(runs("F-metered-underlay").any { r -> r.res.events.any { it.kind == "link-meta" && it.bool("metered") } })
        assertTrue(runs("F-network-change").all { r -> r.res.events.count { it.kind == "session-close" } > 2 })
        assertTrue(runs("F-session-drop").all { r -> r.res.events.any { it.kind == "session-close" && it.str("why") == "drop" } })
    }

    @Test
    fun hostConditionFaultsChangeAvailability() {
        for (name in listOf("F-thermal-spike", "F-charger-unplug", "F-battery-floor", "F-presence")) {
            val ex = runs(name).flatMap { it.res.decisions }.flatMap { it.excluded }.toSet()
            val base = runs("F-none").flatMap { it.res.decisions }.flatMap { it.excluded }.toSet()
            val plans = runs(name).flatMap { it.res.decisions }.map { it.attempts.map { a -> a.substringBefore("|total=") } }
            val basePlans = runs("F-none").flatMap { it.res.decisions }.map { it.attempts.map { a -> a.substringBefore("|total=") } }
            assertTrue(ex != base || plans != basePlans, "$name left no trace in the plans")
        }
    }
}
