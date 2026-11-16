package xyz.mdhv.asom.lab.sim

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LTQ-06 (ERR-FX-RT-6) in the simulator: a piggybacked digest keeps the digest fields fresh but not the power fields, so a peer in steady use is asked for a
 * full STATE again once its last one is STALE (30 s). Read from the event log only: at every request, the last full state of every peer is at most 36 s old
 * in scenarios whose peers stay reachable. Evidence label: SIMULATED, NOT DEVICE EVIDENCE.
 */
class PowerFieldsTest {
    private fun num(l: String, k: String): Long? = Regex("\"$k\":(-?[0-9]+)").find(l)?.groupValues?.get(1)?.toLong()

    private fun str(l: String, k: String): String? = Regex("\"$k\":\"([^\"]*)\"").find(l)?.groupValues?.get(1)

    @Test
    fun aPeerInSteadyUseIsPulledAgainBeforeItsPowerFieldsGoStale() {
        var checked = 0
        for ((name, seed) in listOf("SC01" to 1L, "SC01" to 2L, "SC06" to 1L, "SC06" to 2L)) {
            val lastState = HashMap<String, Long>()
            for (l in Sim.runMain(name, seed).res.eventsJsonl()) {
                when (str(l, "kind")) {
                    "state" -> lastState[str(l, "peer")!!] = num(l, "t")!!
                    "request" -> {
                        val t = num(l, "t")!!
                        for ((peer, at) in lastState) {
                            assertTrue(t - at <= 36_000, "$name seed $seed: the full state of $peer is ${t - at} ms old at a request")
                            checked++
                        }
                    }
                }
            }
        }
        assertTrue(checked >= 1_000, "non-vacuity: $checked (request, peer) pairs checked")
        println("power-fields iterations: $checked violations: 0")
    }
}
