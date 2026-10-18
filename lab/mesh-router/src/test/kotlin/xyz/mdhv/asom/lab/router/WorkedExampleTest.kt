package xyz.mdhv.asom.lab.router

import kotlin.test.Test
import kotlin.test.assertEquals

/** R02-r3-001 (LAB_SPEC 6.4): every number below is the spec's own, computed by hand there. */
class WorkedExampleTest {
    private fun world(): MeshSnapshot {
        val deck = W.peer(
            "deck-node", DeviceClass.HANDHELD, prior = W.prior(prefill = 60_000, decode = 12_000, ttft0 = 300, steady = 12_000),
            lastSame = mapOf(W.SHA_A to 1_000_400L),
        )
        val self = W.self(
            situation = W.selfSituation(permille = 600, thermal = 1, active = true, design = 19_000),
        )
        return W.snapshot(self = self, peers = listOf(deck))
    }

    @Test
    fun deckWinsWithTheSpecsNumbers() {
        val plan = MeshRouter().plan(W.query(), world())
        assertEquals(listOf("deck-node", "self-node"), plan.attempts.map { it.nodeId })
        val deck = plan.attempts[0]
        val d = deck.estimate!!
        assertEquals(300, d.outTokens)
        assertEquals(11, d.netMs)
        assertEquals(12_205, d.prefillMs)
        assertEquals(35_596, d.decodeMs)
        assertEquals(12_221, d.ttftMs)
        assertEquals(47_817, d.totalMs)
        val ds = deck.score!!
        assertEquals(listOf(60_038L, 0L, 0L, 1_000L, 800L, 0L), (1..6).map { ds.term(it) })
        assertEquals(61_838, ds.total)
        val self = plan.attempts[1]
        val e = self.estimate!!
        assertEquals(0, e.netMs)
        assertEquals(16_867, e.prefillMs)
        assertEquals(59_800, e.decodeMs)
        assertEquals(16_867, e.ttftMs)
        assertEquals(76_667, e.totalMs)
        assertEquals(383_335, e.energyMilliJ)
        assertEquals(6, e.batteryUsedPermille)
        val ss = self.score!!
        assertEquals(listOf(93_534L, 12_000L, 38_333L, 0L, 800L, 0L), (1..6).map { ss.term(it) })
        assertEquals(144_667, ss.total)
        assertEquals("peer:best-score/heat", deck.reason)
    }

    @Test
    fun withNoSovereignCandidateThePlanIsTheV1CloudPlan() {
        val snap = world().copy(cloud = W.cloud(keys = setOf("openrouter", "groq")))
        val plan = MeshRouter().plan(W.query(model = "llama-3.3-70b"), snap.copy(peers = emptyList(), self = W.self(files = emptyList())))
        assertEquals(true, plan.attempts.isNotEmpty())
        assertEquals(setOf(Tier.CLOUD), plan.attempts.map { it.tier }.toSet())
        assertEquals("v1:policy", plan.attempts[0].reason)
    }
}
