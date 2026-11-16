package xyz.mdhv.asom.lab.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.router.Tier

/**
 * Review finding B-TL-8 (ERRATA ERR-FX4-LG-13): no run flipped `paired`, so the PAIRED leg of RL4 was never false in any simulation and a requester that
 * ignored a suspend or a revoke would have passed every law. These runs suspend or revoke a peer at three instants (while the offer is in flight, between
 * the accept and the body, while the answer streams), restore it, or leave it revoked for the rest of the run, and judge the requester's own records:
 * the body is sent only to a PAIRED peer, a cancelled attempt is recorded as cancelled before the body, and a revoked peer is neither planned nor pulled.
 * Evidence label: SIMULATED, NOT DEVICE EVIDENCE.
 */
class LifecycleRevokeTest {
    private fun registry(sim: Simulation, peer: String, paired: Boolean) =
        sim.emitForTest("registry", "peer" to peer, "paired" to paired, "route" to true, "grant" to true, "requireCharging" to false)

    @Test
    fun rl4HoldsAndThePairedLegIsFalseWhenAPeerIsSuspendedOrRevokedAtThreeInstantsAndRestored() {
        val t = Tally()
        var pairedFalseFlips = 0
        var cancelledBeforeBody = 0
        var bodiesSent = 0
        var pairedFalseSeenBySimChecks = 0
        for (seed in 1L..10L) {
            var n = 0
            val run = Sim.runTest("T-RL4", seed) { sim ->
                sim.onOffer = { peer ->
                    n++
                    val delay = when {
                        n % 3 == 0 -> 1L
                        n % 5 == 0 -> 25L
                        n % 7 == 0 -> 70L
                        else -> null
                    }
                    if (delay != null) {
                        sim.afterForTest(delay) {
                            pairedFalseFlips++
                            registry(sim, peer, paired = false)
                        }
                        sim.afterForTest(delay + 200) { registry(sim, peer, paired = true) }
                    }
                }
            }
            SimChecks.rl4(run, t, "lifecycle seed $seed")
            SimChecks.rl22(run, t, "lifecycle seed $seed")
            pairedFalseSeenBySimChecks += run.res.events.count { it.kind == "registry" && !it.bool("paired") }
            val peerAttempts = run.res.requests.flatMap { it.attempts }.filter { it.tier == Tier.PEER }
            cancelledBeforeBody += peerAttempts.count { it.status == "CANCELLED_BEFORE_BODY" && it.registryOkAtBody == false && !it.bodySent }
            bodiesSent += peerAttempts.count { it.bodySent }
            for (a in peerAttempts.filter { it.bodySent }) {
                val reg = run.res.events.filter { it.kind == "registry" && it.str("peer") == a.target && it.t <= a.bodyAt!! }.lastOrNull()
                assertTrue(reg != null && reg.bool("paired"), "seed $seed: a body went to ${a.target} at ${a.bodyAt} while its registry row said paired=${reg?.bool("paired")}")
            }
        }
        t.print("lifecycle (suspend or revoke 1, 25 and 70 ms after an offer, restored 200 ms later), seeds 1..10")
        println("paired=false flips: $pairedFalseFlips; registry events with paired=false: $pairedFalseSeenBySimChecks; cancelled before the body: $cancelledBeforeBody; bodies sent: $bodiesSent")
        assertTrue(pairedFalseSeenBySimChecks >= 10, "the PAIRED leg of RL4 was false in only $pairedFalseSeenBySimChecks registry events")
        assertTrue(cancelledBeforeBody >= 5, "no accepted attempt was cancelled because the peer was no longer paired: $cancelledBeforeBody")
        assertTrue(bodiesSent >= 50, "bodies sent: $bodiesSent")
        assertTrue((t.cases["RL4"] ?: 0) >= 50)
        assertEquals(emptyList(), t.violations)
    }

    @Test
    fun aRevokedPeerIsNeverSentABodyNeverOpenedASessionToAndNeverPulledAgain() {
        var revokedAt = 0L
        val revokeAtMs = 600_000L
        val settleMs = 10_000L
        var bodiesBefore = 0
        var requestsAfter = 0
        var controlStatesAfter = 0
        var statesAfterRevoke = 0
        var sessionsAfterRevoke = 0
        val t = Tally()
        for (seed in 1L..6L) {
            val control = Sim.runTest("T-RL4", seed)
            controlStatesAfter += control.res.events.count { it.kind == "state" && it.str("peer") == "mac" && it.t > revokeAtMs + settleMs }
            val run = Sim.runTest("T-RL4", seed) { sim ->
                sim.scheduleForTest(revokeAtMs) {
                    revokedAt = sim.nowForTest()
                    registry(sim, "mac", paired = false)
                }
            }
            assertEquals(revokeAtMs, revokedAt)
            SimChecks.rl4(run, t, "revoke seed $seed")
            val toMac = run.res.requests.flatMap { it.attempts }.filter { it.target == "mac" }
            bodiesBefore += toMac.count { it.bodySent && it.bodyAt!! <= revokeAtMs }
            requestsAfter += run.res.requests.count { it.startT > revokeAtMs + settleMs }
            val late = toMac.filter { it.bodySent && it.bodyAt!! > revokeAtMs }
            assertEquals(emptyList(), late.map { it.bodyAt }, "seed $seed: a body went to the revoked peer after the revoke instant")
            val planned = run.res.requests.filter { it.startT > revokeAtMs + settleMs }.flatMap { it.attempts }.filter { it.target == "mac" }
            assertEquals(emptyList(), planned.map { it.startT }, "seed $seed: the revoked peer was still attempted")
            statesAfterRevoke += run.res.events.count { it.kind == "state" && it.str("peer") == "mac" && it.t > revokeAtMs + settleMs }
            sessionsAfterRevoke += run.res.events.count { it.kind == "session-open" && it.str("peer") == "mac" && it.t > revokeAtMs + settleMs }
        }
        println("revoke at $revokeAtMs ms: bodies to the peer before ${bodiesBefore}, requests after $requestsAfter, STATE answers after the revoke $statesAfterRevoke (control without a revoke: $controlStatesAfter), sessions opened to it after $sessionsAfterRevoke")
        assertTrue(bodiesBefore >= 3, "the peer was not in use before the revoke: $bodiesBefore")
        assertTrue(requestsAfter >= 20, "requests after the revoke: $requestsAfter")
        assertTrue(controlStatesAfter >= 5, "the control run (no revoke) never pulled the peer after the same instant, so this test could not see a pull: $controlStatesAfter")
        assertEquals(0, statesAfterRevoke, "a revoked peer was pulled again")
        assertEquals(0, sessionsAfterRevoke, "a session was opened to a revoked peer")
        assertEquals(emptyList(), t.violations)
    }
}
