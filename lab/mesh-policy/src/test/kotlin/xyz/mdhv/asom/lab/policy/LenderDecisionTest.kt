package xyz.mdhv.asom.lab.policy

import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.JInt

class LenderDecisionTest {
    private val offer = OfferView("AAAAAAAAAAAAAAAAAAAAAA", "qwen3-8b", "chat", 1_200, 512, 60_000, true)
    private val fine = LenderSituation(
        PeerStatus.PAIRED, inferScopeGranted = true, attemptSeenWithin24h = false, modelAllowedAndLoadable = true, limits = LenderLimits(), servingConditionsOk = true,
        presenceActive = false, presenceHoldRemainingMs = 0, predictedThermalHold = false, estStartMs = 0, inflight = 0, requestsThisMinute = 0,
    )

    private fun decide(s: LenderSituation, o: OfferView = offer) = LenderDecisionTable.decide(o, s)

    @Test
    fun everyRowOfTheTableOnItsOwn() {
        val d1 = decide(fine.copy(registryStatus = PeerStatus.REVOKED))
        assertEquals("1", d1.row)
        assertEquals(LenderReply.Error(MeshErrorCode.PEER_NOT_PAIRED, closeConnection = true), d1.reply)
        assertEquals(LenderReply.Decline(DeclineCode.SCOPE_DENIED, 30_000), decide(fine.copy(inferScopeGranted = false)).reply)
        assertEquals(LenderReply.Decline(DeclineCode.DUPLICATE_ATTEMPT, 5_000), decide(fine.copy(attemptSeenWithin24h = true)).reply)
        assertEquals(LenderReply.Decline(DeclineCode.MODEL_NOT_OFFERED, 30_000), decide(fine.copy(modelAllowedAndLoadable = false)).reply)
        assertEquals(LenderReply.Error(MeshErrorCode.FRAME_TOO_LARGE, false), decide(fine, offer.copy(promptBytes = 8_388_609)).reply)
        assertEquals(LenderReply.Error(MeshErrorCode.FRAME_TOO_LARGE, false), decide(fine, offer.copy(maxTokens = 4_097)).reply)
        assertEquals("6", decide(fine.copy(servingConditionsOk = false)).row)
        assertEquals(LenderReply.Decline(DeclineCode.PEER_UNAVAILABLE, 30_000), decide(fine.copy(servingConditionsOk = false)).reply)
        assertEquals("6a", decide(fine.copy(predictedThermalHold = true)).row)
        assertEquals(LenderReply.Decline(DeclineCode.PEER_UNAVAILABLE, 30_000), decide(fine.copy(predictedThermalHold = true)).reply)
        assertEquals("6b", decide(fine.copy(estStartMs = 60_001)).row)
        assertEquals(LenderReply.Decline(DeclineCode.PEER_BUSY, 5_000), decide(fine.copy(estStartMs = 60_001)).reply)
        assertEquals("8", decide(fine.copy(estStartMs = 60_000)).row, "accept at estStartMs == deadlineMs")
        assertEquals("7", decide(fine.copy(inflight = 1)).row)
        assertEquals("7", decide(fine.copy(requestsThisMinute = 30)).row)
        assertEquals("8", decide(fine.copy(requestsThisMinute = 29)).row)
        assertEquals(LenderReply.Accept("none"), decide(fine).reply, "row 8: retain is always none")
    }

    @Test
    fun aPresenceCauseIsAlwaysPeerUnavailableWithTheRemainingHoldDownAsRetryAfter() {
        val d = decide(fine.copy(presenceActive = true, presenceHoldRemainingMs = 123_456))
        assertEquals(LenderReply.Decline(DeclineCode.PEER_UNAVAILABLE, 123_456), d.reply)
        assertEquals(LenderReply.Decline(DeclineCode.PEER_UNAVAILABLE, 5_000), decide(fine.copy(presenceActive = true, presenceHoldRemainingMs = 10)).reply, "clamped up to 5,000")
        assertEquals(LenderReply.Decline(DeclineCode.PEER_UNAVAILABLE, 600_000), decide(fine.copy(presenceActive = true, presenceHoldRemainingMs = 10_000_000)).reply, "clamped down to 600,000")
    }

    @Test
    fun theFirstFailingRowDecidesOverRandomSituationsAndEveryRowIsReached() {
        val rng = SplittableRandom(5)
        val hits = HashMap<String, Int>()
        var cases = 0
        repeat(20_000) {
            val s = LenderSituation(
                registryStatus = PeerStatus.entries[rng.nextInt(3)].takeIf { rng.nextInt(4) == 0 } ?: PeerStatus.PAIRED,
                inferScopeGranted = rng.nextInt(6) != 0, attemptSeenWithin24h = rng.nextInt(8) == 0, modelAllowedAndLoadable = rng.nextInt(6) != 0,
                limits = LenderLimits(), servingConditionsOk = rng.nextInt(5) != 0, presenceActive = rng.nextInt(5) == 0, presenceHoldRemainingMs = rng.nextLong(0, 700_000),
                predictedThermalHold = rng.nextInt(6) == 0, estStartMs = rng.nextLong(0, 120_000), inflight = rng.nextInt(2), requestsThisMinute = rng.nextInt(40),
            )
            val o = offer.copy(promptBytes = if (rng.nextInt(10) == 0) 9_000_000 else 1_200, maxTokens = if (rng.nextInt(12) == 0) 5_000 else 512)
            val d = LenderDecisionTable.decide(o, s)
            // The oracle lists the failing rows and takes the first.
            val failing = buildList {
                if (s.registryStatus != PeerStatus.PAIRED) add("1")
                if (!s.inferScopeGranted) add("2")
                if (s.attemptSeenWithin24h) add("3")
                if (!s.modelAllowedAndLoadable) add("4")
                if (o.promptBytes > 8_388_608 || o.maxTokens > 4_096) add("5")
                if (s.presenceActive || !s.servingConditionsOk) add("6")
                if (s.predictedThermalHold) add("6a")
                if (s.estStartMs > o.deadlineMs) add("6b")
                if (s.inflight >= 1 || s.requestsThisMinute >= 30) add("7")
            }
            assertEquals(failing.firstOrNull() ?: "8", d.row, "situation $s")
            hits[d.row] = (hits[d.row] ?: 0) + 1
            cases++
            val r = d.reply
            if (r is LenderReply.Decline) assertTrue(r.retryAfterMs in 5_000..600_000, "retryAfterMs ${r.retryAfterMs}")
            if (s.presenceActive && failing.firstOrNull() == "6") assertEquals(DeclineCode.PEER_UNAVAILABLE, (r as LenderReply.Decline).code)
        }
        val rows = listOf("1", "2", "3", "4", "5", "6", "6a", "6b", "7", "8")
        assertEquals(emptyList(), rows.filter { (hits[it] ?: 0) == 0 }, "every row of the decision table must be the deciding row in some case")
        println("decision table iterations: $cases random situations; deciding-row counts: ${hits.toSortedMap()}")
    }

    @Test
    fun theWireHasNoQueuePositionAndNoEstimatedStart() {
        val members = mutableSetOf<String>()
        for (d in listOf(decide(fine), decide(fine.copy(inferScopeGranted = false)), decide(fine.copy(registryStatus = PeerStatus.SUSPENDED)), decide(fine.copy(estStartMs = 99_999)))) {
            val o = LenderWire.of(offer, d)
            members += o.members.map { it.first }
            assertTrue(o.members.none { it.first in setOf("queuePos", "estStartMs", "ttftMs", "totalMs", "usage") })
        }
        assertEquals(setOf("attemptId", "fileSha256", "servedModel", "code", "retryAfterMs"), members)
        val declineText = Jcs.serializeToString(LenderWire.decline(offer, LenderReply.Decline(DeclineCode.PEER_BUSY, 5_000)))
        assertEquals("""{"attemptId":"AAAAAAAAAAAAAAAAAAAAAA","code":"PEER_BUSY","retryAfterMs":5000}""", declineText)
        assertTrue((LenderWire.decline(offer, LenderReply.Decline(DeclineCode.PEER_BUSY, 5_000))["retryAfterMs"] as JInt).value == 5_000L)
    }
}
