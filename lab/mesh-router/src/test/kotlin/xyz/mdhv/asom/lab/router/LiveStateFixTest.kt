package xyz.mdhv.asom.lab.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor

/** Review fixes LTQ-02, LTQ-06 and LTQ-13 (live state reducer, probe-only partition). Each test fails on the code before the fix. */
class LiveStateFixTest {
    private fun digest(seq: Long, fsm: Fsm = Fsm.SERVING) = StDigestDoc(seq, fsm, 0, Governor.RUN, 0)

    private fun open(): PeerStateCache = LiveStateCache.onSessionOpen(PeerStateCache())

    @Test
    fun aGoawayStillExpiresTheStateAfterTheRedial() {
        var c = LiveStateCache.onState(open(), W.state(seq = 5), 0)
        c = LiveStateCache.onGoaway(c)
        assertEquals(Freshness.EXPIRED, LiveStateCache.freshness(c, 1_000))
        c = LiveStateCache.onSessionOpen(LiveStateCache.onSessionClose(c))
        assertEquals(Freshness.EXPIRED, LiveStateCache.freshness(c, 2_000), "no new state has arrived: the pre-GOAWAY state must not become FRESH again")
        assertEquals(Freshness.EXPIRED, LiveStateCache.powerFreshness(c, 2_000))
    }

    @Test
    fun aNewStateAfterTheRedialClearsTheGoaway() {
        var c = LiveStateCache.onGoaway(LiveStateCache.onState(open(), W.state(seq = 5), 0))
        c = LiveStateCache.onSessionOpen(LiveStateCache.onSessionClose(c))
        c = LiveStateCache.onState(c, W.state(seq = 1), 2_500)
        assertEquals(Freshness.FRESH, LiveStateCache.freshness(c, 3_000))
        assertEquals(Freshness.FRESH, LiveStateCache.powerFreshness(c, 3_000))
    }

    @Test
    fun aDigestAfterTheRedialClearsTheGoawayForItsOwnFieldsOnly() {
        var c = LiveStateCache.onGoaway(LiveStateCache.onState(open(), W.state(seq = 5), 0))
        c = LiveStateCache.onSessionOpen(LiveStateCache.onSessionClose(c))
        c = LiveStateCache.onPiggyback(c, digest(1), 2_500)
        assertEquals(Freshness.FRESH, LiveStateCache.freshness(c, 3_000))
        assertEquals(Freshness.EXPIRED, LiveStateCache.powerFreshness(c, 3_000), "the power fields still date from before the GOAWAY")
    }

    @Test
    fun aRegressedStateStaysExpiredAcrossTheRedial() {
        var c = LiveStateCache.onState(LiveStateCache.onState(open(), W.state(seq = 9), 0), W.state(seq = 4), 100)
        assertEquals(Freshness.EXPIRED, LiveStateCache.freshness(c, 200))
        c = LiveStateCache.onSessionOpen(LiveStateCache.onSessionClose(c))
        assertEquals(Freshness.EXPIRED, LiveStateCache.freshness(c, 300))
        c = LiveStateCache.onState(c, W.state(seq = 1), 400)
        assertEquals(Freshness.FRESH, LiveStateCache.freshness(c, 500))
    }

    @Test
    fun aDigestWithARegressedSeqExpiresTheState() {
        var c = LiveStateCache.onState(open(), W.state(seq = 9), 1_000)
        c = LiveStateCache.onPiggyback(c, digest(4), 1_100)
        assertTrue(c.regressed)
        assertEquals(Freshness.EXPIRED, LiveStateCache.freshness(c, 1_200))
        c = LiveStateCache.onPiggyback(c, digest(10), 1_300)
        assertEquals(false, c.regressed)
        assertEquals(Freshness.FRESH, LiveStateCache.freshness(c, 1_400))
    }

    @Test
    fun aDigestDoesNotRefreshThePowerFieldsItDoesNotCarry() {
        val full = W.state(seq = 5, source = "battery", band = BatteryBand.B50_79)
        var c = LiveStateCache.onState(open(), full, 0)
        assertEquals(Freshness.FRESH, LiveStateCache.powerFreshness(c, 1_000))
        c = LiveStateCache.onPiggyback(c, digest(6), 600_000)
        assertEquals(Freshness.FRESH, LiveStateCache.freshness(c, 600_100), "the digest fields are fresh")
        assertEquals(Freshness.EXPIRED, LiveStateCache.powerFreshness(c, 600_100), "the power fields are ten minutes old")
        assertEquals(Freshness.STALE, LiveStateCache.powerFreshness(c, 300_000))
        assertEquals(Freshness.WARM, LiveStateCache.powerFreshness(c, 30_000))
        assertEquals(Freshness.WARM, LiveStateCache.powerFreshness(c, 5_001))
        c = LiveStateCache.onState(c, full.copy(seq = 7), 600_200)
        assertEquals(Freshness.FRESH, LiveStateCache.powerFreshness(c, 600_300))
    }

    @Test
    fun theSampledAgeOfTheFullStateCountsForThePowerFields() {
        var c = LiveStateCache.onState(open(), W.state(seq = 5, age = 40_000), 0)
        c = LiveStateCache.onPiggyback(c, digest(6), 1_000)
        assertEquals(Freshness.FRESH, LiveStateCache.freshness(c, 1_000))
        assertEquals(Freshness.STALE, LiveStateCache.powerFreshness(c, 1_000))
    }

    @Test
    fun aFullStateOfTheSeqOfAKnownDigestRefreshesThePowerFieldsAndNothingElse() {
        val full = W.state(seq = 5, source = "battery", band = BatteryBand.B50_79)
        var c = LiveStateCache.onState(open(), full, 0)
        c = LiveStateCache.onPiggyback(c, StDigestDoc(6, Fsm.DRAINING, 1, Governor.RUN, 1), 100_000)
        assertEquals(Freshness.STALE, LiveStateCache.powerFreshness(c, 100_100))
        val reply = W.state(seq = 6, source = "battery", band = BatteryBand.B20_49, fsm = Fsm.SERVING, thermal = 0, queue = 0)
        c = LiveStateCache.onState(c, reply, 100_200)
        assertEquals(Freshness.FRESH, LiveStateCache.powerFreshness(c, 100_300), "the pull must be able to refresh the power fields")
        assertEquals(BatteryBand.B20_49, c.doc!!.batteryBand)
        assertEquals(Fsm.DRAINING, c.doc!!.fsm, "the digest fields keep what the digest said")
        assertEquals(1, c.doc!!.thermalBand)
        assertEquals(6, c.doc!!.seq)
        assertEquals(100_000, c.rxMonoMs, "an equal seq is still not fresher for the digest fields (ERR-LP-4)")
        val again = LiveStateCache.onState(c, reply, 100_900)
        assertEquals(c, again, "a second full state of the same seq changes nothing")
    }

    private val now = 1_000_500L

    private fun batteryPeer(id: String, power: Freshness?) = W.peer(
        id, state = W.state(source = "battery", band = BatteryBand.B50_79), rxMonoMs = now - 100, powerFreshness = power, lastSame = mapOf(W.SHA_A to now - 1_000), design = 40_000,
    )

    private fun peerAttempt(power: Freshness?): PlannedAttempt? =
        MeshRouter().plan(W.query(), W.snapshot(peers = listOf(batteryPeer("peer-b", power)), now = now)).attempts.firstOrNull { it.tier == Tier.PEER }

    @Test
    fun staleOrExpiredPowerFieldsAreAppliedToTheFiltersEvenWhenTheDigestIsFresh() {
        val fresh = peerAttempt(null)!!
        assertEquals(Freshness.FRESH, fresh.freshness)
        assertEquals(false, fresh.probeOnly)
        val stale = MeshRouter().plan(W.query(), W.snapshot(peers = listOf(batteryPeer("peer-b", Freshness.STALE)), now = now))
        assertEquals(listOf("F11_POWER"), stale.excluded.filter { it.nodeId == "peer-b" }.map { it.code }, "a stale band 50-79 reads one band lower: 20-49 is refused on battery")
        val expired = peerAttempt(Freshness.EXPIRED)!!
        assertEquals(true, expired.probeOnly, "power unknown: the offer is the probe")
        assertEquals(Freshness.EXPIRED, expired.freshness)
    }

    private fun twoPeers(halfOpenA: Boolean): List<PlannedAttempt> {
        val a = W.peer("peer-a", breaker = BreakerView(null, null, halfOpen = halfOpenA), lastSame = mapOf(W.SHA_A to now - 1_000))
        val b = W.peer("peer-b", lastSame = mapOf(W.SHA_A to now - 1_000))
        return MeshRouter().plan(W.query(), W.snapshot(peers = listOf(a, b), now = now)).attempts.filter { it.tier == Tier.PEER }
    }

    @Test
    fun aHalfOpenPeerIsProbeOnlyAndPlacedAfterItsEqualInTheBlock() {
        val closed = twoPeers(false)
        assertEquals(listOf("peer-a", "peer-b"), closed.map { it.nodeId })
        assertEquals(listOf(false, false), closed.map { it.probeOnly })
        val half = twoPeers(true)
        assertEquals(listOf("peer-b", "peer-a"), half.map { it.nodeId }, "the half-open peer goes last among equal primary keys")
        assertEquals(listOf(false, true), half.map { it.probeOnly })
    }
}
