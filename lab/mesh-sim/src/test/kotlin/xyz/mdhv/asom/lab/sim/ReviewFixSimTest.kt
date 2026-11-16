package xyz.mdhv.asom.lab.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.router.MeshErrorCodes
import xyz.mdhv.asom.lab.router.RouteReasons

/** ERR-FX4-RT-*: the simulator side of the router review (RP-2, RP-6, RP-7). Evidence label: SIMULATED — NOT DEVICE EVIDENCE. */
class ReviewFixSimTest {
    private val sseAndLedger = setOf("MESH_STREAM_INTERRUPTED", "LEDGER_UNAVAILABLE")

    private fun allowed(code: String) = code in MeshErrorCodes.V1 || code in MeshErrorCodes.V2 || code in sseAndLedger || code.startsWith("UNMAPPED(owner-decision)")

    @Test
    fun rp6_noInventedErrorCodeIsRecordedForAFailedRequest() {
        val errors = java.util.TreeMap<String, Int>()
        val runs = ArrayList<SimRun>()
        for (seed in 1L..4L) {
            for (v in listOf(Variant.B0, Variant.B3)) runs += Sim.runTest("T-ERR", seed, v)
            for (name in listOf("F-decline-storm", "F-peer-vanish", "F-peer-vanish-before-head", "F-battery-floor", "F-thermal-spike")) runs += Sim.runTest(name, seed)
            runs += Sim.runTest("T-RL18", seed)
        }
        for (r in runs.flatMap { it.res.requests }) r.error?.let { errors.merge(it, 1, Int::plus) }
        println("RP-6 recorded request errors: $errors")
        val bad = errors.keys.filter { !allowed(it) }
        assertTrue(bad.isEmpty(), "codes outside the frozen v1 set, MeshErrorCodes and the spec'd SSE value: $bad")
        assertTrue(errors.values.sum() >= 10, "the scenarios produced too few failed requests to say anything: $errors")
    }

    @Test
    fun rp2_aPeerThatWasUsefulServesAgainAfterItsOutage() {
        val t = Tally()
        val probes = ArrayList<AttemptLog>()
        var closedAtAccept = 0
        for (seed in 1L..6L) {
            val run = Sim.runTest("T-RL18", seed)
            for (a in run.res.requests.flatMap { it.attempts }.filter { it.probeOk }) {
                val ok = run.res.events.any { it.kind == "breaker-ok" && it.str("peer") == a.target && it.t == a.answeredAt }
                assertTrue(ok, "seed $seed: the probe of ${a.target} accepted at ${a.answeredAt} did not close its breaker")
                closedAtAccept++
            }
            SimChecks.rl18(run, t, "T-RL18 seed $seed")
            SimChecks.rl22(run, t, "T-RL18 seed $seed")
            SimChecks.rl16(run, t, "T-RL18 seed $seed")
            probes += run.res.requests.flatMap { it.attempts }.filter { it.probe }
        }
        println("RP-2 offer-only probes over 6 seeds: ${probes.size}, accepted ${probes.count { it.probeOk }}")
        assertTrue(probes.count { it.probeOk } >= 6, "no probe was ever accepted: the starvation path is not exercised")
        assertTrue(probes.none { it.bodySent }, "a probe carried content")
        assertTrue(closedAtAccept >= 6, "breaker closes checked: $closedAtAccept")
        t.print("T-RL18, peers serve again after an outage")
        assertTrue((t.cases["RL18/serves-again"] ?: 0) >= 6, "RL18/serves-again iterations ${t.cases["RL18/serves-again"]}")
        assertEquals(emptyList(), t.violations)
    }

    @Test
    fun rp7_everyExecutedReasonCarriesItsPrevAndNeverLeaksAPeersSituation() {
        val reasons = ArrayList<String>()
        for (seed in 1L..4L) {
            for (name in listOf("T-RL18", "F-peer-vanish", "F-peer-vanish-before-head", "F-decline-storm", "T-ERR")) reasons += Sim.runTest(name, seed).res.requests.flatMap { it.attempts }.map { it.reason }
        }
        val retried = reasons.filter { ";prev:" in it }
        println("RP-7 executed attempts: ${reasons.size}, with prev: ${retried.size}, probes: ${reasons.count { ";probe" in it }}")
        assertTrue(reasons.all { it.isNotEmpty() })
        assertTrue(retried.size >= 20, "no executed retry carried prev: (${retried.size})")
        for (r in reasons) {
            val app = RouteReasons.appFacing(r)
            assertTrue("PEER_" !in app && "/battery" !in app && "/heat" !in app && "/uncertainty" !in app && ";stale" !in app && "cap:" !in app && ";probe" !in app, "leaks: $r -> $app")
        }
        assertTrue(retried.any { RouteReasons.appFacing(it).contains(";prev:peer-unavailable") }, "a peer failure never reached the projection as peer-unavailable")
    }

    @Test
    fun rp6_aPlanWithNoCloudAttemptThatAllFailedHasNoCodeAndTheSimulatorSaysSo() {
        val t = Tally()
        val errors = java.util.TreeMap<String, Int>()
        for (seed in 1L..4L) {
            val run = Sim.runTest("T-FX4-NOCLOUD", seed)
            SimChecks.all(run, t, "T-FX4-NOCLOUD seed $seed", frames = true)
            for (r in run.res.requests) r.error?.let { errors.merge(it, 1, Int::plus) }
        }
        println("RP-6 no-cloud scenario errors: $errors")
        assertEquals(emptyList(), t.violations)
        assertTrue(errors.keys.all { allowed(it) }, "$errors")
        assertTrue(errors.keys.any { it.startsWith("UNMAPPED(owner-decision)") }, "the scenario never reached the unmapped situation: $errors")
    }

    @Test
    fun rp2_aFailedProbeIsNotRepeatedWithinTheRequest() {
        var failed = 0
        var probes = 0
        for (seed in 1L..4L) for (name in listOf("F-peer-vanish", "F-peer-vanish-before-head", "T-RL18", "F-decline-storm", "F-session-drop")) {
            for (r in Sim.runTest(name, seed).res.requests) {
                for ((i, a) in r.attempts.withIndex()) {
                    if (!a.probe) continue
                    probes++
                    if (a.probeOk) continue
                    failed++
                    assertTrue(r.attempts.drop(i + 1).none { it.target == a.target }, "$name seed $seed ${r.id}: ${a.target} was asked again after its probe failed (${a.status})")
                }
            }
        }
        println("RP-2 probes $probes, failed $failed")
        assertTrue(failed >= 1, "no probe ever failed in these scenarios")
    }
}
