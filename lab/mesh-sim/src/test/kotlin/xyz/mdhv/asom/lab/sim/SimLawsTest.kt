package xyz.mdhv.asom.lab.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.router.Tier

/**
 * The simulator-level laws RL4, RL14..RL19, RL21 and RL22 (LAB_SPEC 6.8) with their non-vacuity counts, and the ledger laws L-L1..L-L14 over the simulated traces.
 * Evidence label: SIMULATED — NOT DEVICE EVIDENCE.
 */
class SimLawsTest {
    private val floor = 100

    @Test
    fun rl4HoldsAtTheInstantTheBodyIsSentEvenWhenTheRegistryChangesBetweenOfferAndAccept() {
        val t = Tally()
        var cancelled = 0
        for (seed in 1L..10L) {
            var n = 0
            val run = Sim.runTest("T-RL4", seed) { sim ->
                sim.onOffer = { peer ->
                    n++
                    if (n % 3 == 0) {
                        sim.afterForTest(1) { sim.emitForTest("registry", "peer" to peer, "paired" to true, "route" to false, "grant" to true, "requireCharging" to false) }
                        sim.afterForTest(60) { sim.emitForTest("registry", "peer" to peer, "paired" to true, "route" to true, "grant" to true, "requireCharging" to false) }
                    }
                    if (n % 7 == 0) {
                        sim.afterForTest(1) { sim.emitForTest("registry", "peer" to peer, "paired" to true, "route" to true, "grant" to false, "requireCharging" to false) }
                        sim.afterForTest(60) { sim.emitForTest("registry", "peer" to peer, "paired" to true, "route" to true, "grant" to true, "requireCharging" to false) }
                    }
                }
            }
            SimChecks.rl4(run, t, "T-RL4 seed $seed")
            SimChecks.rl22(run, t, "T-RL4 seed $seed")
            cancelled += run.res.requests.flatMap { it.attempts }.count { it.status == "CANCELLED_BEFORE_BODY" && it.registryOkAtBody == false && !it.bodySent }
        }
        t.print("T-RL4 (registry flipped 1 ms after the offer), seeds 1..10")
        println("attempts cancelled before the body because the registry no longer allowed the peer: $cancelled")
        assertTrue(cancelled >= 10, "the registry flips never hit an offer in flight: $cancelled")
        assertTrue((t.cases["RL4"] ?: 0) >= floor)
        assertEquals(emptyList(), t.violations)
    }

    @Test
    fun rl18AFailedPeerIsExcludedUntilItsCooldownEndsAndReturnsAfterIt() {
        val t = Tally()
        for (seed in 1L..10L) {
            val run = Sim.runTest("T-RL18", seed)
            SimChecks.rl18(run, t, "T-RL18 seed $seed")
            SimChecks.rl21(run, t, "T-RL18 seed $seed")
        }
        t.print("T-RL18 (peers vanish, decline storm), seeds 1..10")
        assertTrue((t.cases["RL18"] ?: 0) >= floor, "RL18 iterations ${t.cases["RL18"]}")
        assertTrue((t.cases["RL18/returned-after-cooldown"] ?: 0) > 0, "no peer ever came back after its cooldown")
        assertEquals(emptyList(), t.violations)
    }

    @Test
    fun rl19FairnessUnderSaturationJainAtLeast900Permille() {
        var runs = 0
        var min = 1000L
        var maxOnTime = 0L
        for (seed in 1L..100L) {
            val run = Sim.runTest("T-RL19", seed)
            val (jain, onTime) = SimChecks.jainWindows(run, 1).single()
            assertTrue(onTime < 900, "seed $seed: the run is not saturated (on-time $onTime permille)")
            assertTrue(jain >= 900, "seed $seed: Jain's index is $jain permille")
            runs++
            min = minOf(min, jain)
            maxOnTime = maxOf(maxOnTime, onTime)
        }
        println("RL19 iterations: $runs violations: 0 (minimum Jain $min permille over 100 saturated runs, on-time at most $maxOnTime permille, SIMULATED — NOT DEVICE EVIDENCE)")
        assertTrue(runs >= floor, "RL19 iterations $runs")
    }

    @Test
    fun jainIndexIsNotAConstant() {
        val fair = SimRun(Sim.make(Sim.testBytes("T-RL19"), 1), fakeResult(listOf(100, 100, 100)))
        val unfair = SimRun(Sim.make(Sim.testBytes("T-RL19"), 1), fakeResult(listOf(300, 0, 0)))
        assertEquals(1000L, SimChecks.jainWindows(fair, 6).single().first)
        assertTrue(SimChecks.jainWindows(unfair, 6).single().first < 500, "one app taking everything must score below 500 permille")
    }

    private fun fakeResult(onTime: List<Int>): SimResult {
        val base = Sim.runTest("T-RL19", 1)
        val apps = base.sc.apps
        val reqs = ArrayList<ReqRecord>()
        for ((i, app) in apps.withIndex()) repeat(300) { k ->
            val r = ReqRecord("x-$i-$k", app, 0, "auto", 10, 40, 5, 10, true, 120_000)
            r.status = if (k < onTime[i]) 200 else 503
            r.endT = 1000
            reqs += r
        }
        return SimResult("fake", 1, Variant.B3, reqs, emptyList(), emptyList(), base.res.trace, emptyMap(), 0, null, emptyMap())
    }

    private val canonical = linkedMapOf(
        "RL4" to listOf("RL4"),
        "RL14" to listOf("RL14/L-L1", "RL14/L-L2", "RL14/L-L3", "RL14/L-L16", "RL14/join-on-attemptId"),
        "RL15" to listOf("RL15/L-L4", "RL15/L-L5"),
        "RL15b" to listOf("RL15b/L-L5b", "RL15b/error-path"),
        "RL16" to listOf("RL16"),
        "RL17" to listOf("RL17"),
        "RL18" to listOf("RL18"),
        "RL21" to listOf("RL21"),
        "RL22" to listOf("RL22"),
    )

    @Test
    fun theLawsAreNotVacuousAcrossTheSuiteAndHoldOnEveryTrace() {
        val t = Tally()
        for (seed in 1L..5L) for (name in listOf("SC01", "SC04", "SC06", "SC09")) SimChecks.all(Sim.runMain(name, seed), t, "$name seed $seed", frames = name != "SC01")
        for (seed in 1L..3L) for (name in listOf("F-none", "F-decline-storm", "F-peer-vanish", "F-peer-vanish-before-head", "F-peer-vanish-mid-stream", "F-ledger-full", "F-session-drop", "F-state-drop")) {
            SimChecks.all(Sim.runTest(name, seed), t, "$name seed $seed", frames = true)
        }
        for (seed in 1L..5L) SimChecks.all(Sim.runTest("T-RL18", seed), t, "T-RL18 seed $seed", frames = true)
        for (seed in 1L..5L) for (v in listOf(Variant.B0, Variant.B3)) SimChecks.all(Sim.runTest("T-ERR", seed, v), t, "T-ERR $v seed $seed", frames = true)
        t.print("simulator laws over the suite (gate scenarios seeds 1..5, fault scenarios seeds 1..3, T-RL18 and T-ERR seeds 1..5)")
        assertEquals(emptyList(), t.violations)
        println("== canonical simulator laws (SIMULATED — NOT DEVICE EVIDENCE)")
        for ((law, parts) in canonical) {
            val n = parts.sumOf { t.cases[it] ?: 0 }
            println("$law iterations: $n violations: 0")
            assertTrue(n >= floor, "$law iterations $n is below the floor $floor")
        }
        for (law in listOf("L-L1", "L-L2", "L-L3", "L-L4", "L-L5", "L-L5b", "L-L7", "L-L9", "L-L10", "L-L12", "L-L14")) {
            assertTrue((t.cases[law] ?: 0) >= floor, "$law iterations ${t.cases[law]} is below the floor $floor")
        }
        assertTrue((t.cases["L-L13"] ?: 0) > 0, "L-L13 (the ledger-full fail-closed law) was vacuous")
        assertTrue((t.cases["RL15b/error-path"] ?: 0) >= floor)
    }

    @Test
    fun requestsThatNeverLeaveTheDeviceCarryNoPeerAttempt() {
        val run = Sim.runMain("SC09", 1)
        val local = run.res.requests.filter { it.app.pkg != "app.open" }
        assertTrue(local.isNotEmpty())
        assertTrue(local.all { r -> r.attempts.all { it.tier == Tier.SELF } })
    }
}
