package xyz.mdhv.asom.lab.sim

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Characterisation tests for behaviour the simulator found in the specified mechanism. They pin what the spec's rules DO, so that a change of the rule shows up here; they do not
 * claim the behaviour is desirable. Evidence label: SIMULATED — NOT DEVICE EVIDENCE.
 */
class FindingsTest {
    /**
     * R6-FINDING-COLD. LAB_SPEC 6.6 computes `predicted` from the requester's link estimate, E5 and E7 of the claim row, and no load time, and none of its discard reasons
     * covers a cold start. A lender unloads a model after `idleUnloadMs` (300 s). An HONEST lender that this requester uses less often than that is therefore cold at every
     * observation, its `elapsed` holds the load, and the ratios fall to about 500 permille: its key reaches DISCREPANT after five kept observations although its claim is true.
     * Placement stays sane (a DISCREPANT key is scaled down, not removed) but the state, the 400 permille peer-wide discount and the peer penalty are then set on an honest device.
     */
    @Test
    fun anHonestLenderUsedLessOftenThanItsIdleUnloadTimeIsCalledDiscrepant() {
        var discrepant = 0
        val kept = ArrayList<Int>()
        for (seed in 1L..8L) {
            val run = Sim.runTest("T-COLD", seed)
            val k = run.res.keptObservationsToDiscrepant["mac"]
            if (k != null) {
                discrepant++
                kept += k
            }
            val obs = run.res.events.filter { it.kind == "obs" && it.str("peer") == "mac" }.map { it.long("tEnd") - it.long("tBody") }
            println("R6-FINDING-COLD seed $seed: ${obs.size} observations, median elapsed ${obs.sorted().getOrNull(obs.size / 2)} ms, honest lender DISCREPANT after ${k ?: "never"} kept observations")
        }
        println("R6-FINDING-COLD: an honest lender was DISCREPANT in $discrepant of 8 seeds (kept observations at that point: $kept) (SIMULATED — NOT DEVICE EVIDENCE)")
        assertTrue(discrepant >= 4, "the finding did not reproduce: $discrepant of 8 seeds")
    }
}
