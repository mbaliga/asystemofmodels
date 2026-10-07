package xyz.mdhv.asom.lab.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.policy.Freshness

/** Review fixes LTQ-01, LTQ-05, LTQ-12 and LTQ-15 (claim tracker). Each test fails on the code before the fix. */
class TrackerFixTest {
    private val metal = ClaimKey("peer-x", W.SHA_A, "metal")
    private val vulkan = ClaimKey("peer-x", W.SHA_A, "vulkan")
    private val claim = PerfPrior(listOf(512 to 20_000L), 100_000, 0, 20_000, null, null, 100_000, 6_000_000_000, emptySet())
    private val bpt = Bpt(4_000, 8_000)
    private val link = LinkStats(10, 100_000, xyz.mdhv.asom.lab.ledger.PeerPath.LAN, false, true, 5)

    private fun obs(key: ClaimKey = metal, bytes: Long = 1_200, elapsed: Long = 20_500) = Observation(
        key, true, 0, elapsed, bytes, 500, 1_024, Estimator.warmNetMs(link, 5_120, MeshConfig()), false, null, null, null, null,
    )

    private fun bothBackends(): Map<ClaimKey, PerfPrior> = mapOf(metal to W.prior(), vulkan to W.prior())

    private fun peerWithBackend(backend: String) = W.peer(
        "peer-x", priors = bothBackends(), state = W.state(backend = backend), lastSame = mapOf(W.SHA_A to 1_000_400L),
    )

    private fun peerAttempt(snap: MeshSnapshot): PlannedAttempt? = MeshRouter().plan(W.query(), snap).attempts.firstOrNull { it.tier == Tier.PEER }

    private fun excludedCodes(snap: MeshSnapshot): List<String> = MeshRouter().plan(W.query(), snap).excluded.filter { it.nodeId == "peer-x" }.map { it.code + ":" + it.detail }

    @Test
    fun aBackendSwitchInTheLiveStateKeepsTheDiscrepantStateAndThePlacementRate() {
        val tracker = mapOf(metal to TrackerState(claimSeq = 1, ratios = List(6) { 400L }))
        val onMetal = peerAttempt(W.snapshot(peers = listOf(peerWithBackend("metal")), tracker = tracker))!!
        val onVulkan = peerAttempt(W.snapshot(peers = listOf(peerWithBackend("vulkan")), tracker = tracker))!!
        assertEquals(ClaimState.DISCREPANT, onMetal.claimState)
        assertEquals(ClaimState.DISCREPANT, onVulkan.claimState, "a peer cannot shed DISCREPANT by reporting another backend")
        assertEquals(onMetal.estimate!!.decEff, onVulkan.estimate!!.decEff)
    }

    @Test
    fun aBackendSwitchInTheLiveStateKeepsTheMemoryExclusion() {
        val tracker = mapOf(metal to TrackerState(claimSeq = 1, memoryDiscrepant = true))
        val onMetal = excludedCodes(W.snapshot(peers = listOf(peerWithBackend("metal")), tracker = tracker))
        val onVulkan = excludedCodes(W.snapshot(peers = listOf(peerWithBackend("vulkan")), tracker = tracker))
        assertEquals(listOf("F8_CLAIM:memory-discrepant"), onMetal)
        assertEquals(onMetal, onVulkan, "terminal=oom on one backend still excludes the file after a backend switch")
    }

    private fun sov(node: String, s1: Long, claimState: ClaimState, k: ClaimKey?) = Scored(
        Tier.PEER, node, FileKey("m", W.SHA_A, null, 0, null), Estimate.ZERO, ScoreBreakdown(s1, 0, 0, 0, 0, 0), true, false, claimState, Freshness.FRESH, k,
    )

    @Test
    fun theCapCountersAreSharedByTheBackendsOfOneFile() {
        val caps = mapOf(metal to CapCounter(wouldWin = 3, won = 1))
        val r = Merge.order(Policy.AUTO, listOf(sov("peer-x", 10, ClaimState.UNVERIFIED, vulkan), sov("peer-y", 20, ClaimState.CORROBORATED, null)), emptyList(), false, caps)
        assertTrue(r.cappedSwap, "won 1 of would-win 4 already reaches ceilDiv(4, 4): the cap must swap whatever backend the live state reports")
        assertEquals("peer-y", (r.items.first() as MergeItem.Sov).c.nodeId)
    }

    @Test
    fun observationsOnAnotherBackendLandInTheSameWindow() {
        var book = TrackerBook()
        repeat(3) { book = ClaimTracker.onObservation(book, obs(metal), claim, bpt, 1_000) }
        book = ClaimTracker.onObservation(book, obs(vulkan), claim, bpt, 1_000)
        assertEquals(1, book.states.size, "one tracker state per (peer, file)")
        assertEquals(4, ClaimTracker.stateAt(book.states, vulkan)!!.ratios.size)
        assertEquals(4, ClaimTracker.stateAt(book.states, metal)!!.ratios.size)
    }

    @Test
    fun whenSiblingsDisagreeTheWorstStateWins() {
        val tracker = mapOf(
            metal to TrackerState(ratios = List(6) { 1000L }),
            vulkan to TrackerState(ratios = List(6) { 400L }),
        )
        assertEquals(ClaimState.DISCREPANT, ClaimTracker.stateOf(ClaimTracker.stateAt(tracker, metal)))
        assertEquals(ClaimState.DISCREPANT, ClaimTracker.stateOf(ClaimTracker.stateAt(tracker, vulkan)))
        val other = ClaimKey("peer-x", W.SHA_B, "metal")
        assertEquals(null, ClaimTracker.stateAt(tracker, other))
    }

    @Test
    fun theMemoryMarkOfAnySiblingAppliesEvenWhenAnotherSiblingIsTheWorstState() {
        val tracker = mapOf(
            metal to TrackerState(memoryDiscrepant = true, strikes = 2),
            vulkan to TrackerState(ratios = List(6) { 400L }, strikes = 1),
        )
        for (k in listOf(metal, vulkan, ClaimKey("peer-x", W.SHA_A, "cpu"))) {
            val s = ClaimTracker.stateAt(tracker, k)!!
            assertEquals(ClaimState.DISCREPANT, ClaimTracker.stateOf(s))
            assertTrue(s.memoryDiscrepant, "memory mark seen from $k")
            assertEquals(2, s.strikes)
        }
    }

    @Test
    fun twoBackendEntriesOfOneFileCountOnceTowardsThePeerWidePenalty() {
        val bad = TrackerState(ratios = List(6) { 400L })
        val tracker = mapOf(metal to bad, vulkan to bad)
        assertEquals(700, ClaimTracker.disc("peer-x", tracker, emptyMap(), 1_000))
        val second = ClaimKey("peer-x", W.SHA_B, "metal")
        assertEquals(400, ClaimTracker.disc("peer-x", tracker + (second to bad), emptyMap(), 1_000))
    }

    @Test
    fun aNewClaimSeqKeepsTheDiscardBudgetClamp() {
        var book = TrackerBook()
        repeat(4) { book = ClaimTracker.onObservation(book, obs(bytes = 40, elapsed = 200_000), claim, bpt, 1_000) }
        val before = book.states.getValue(metal)
        assertEquals(ClaimState.WEAK, ClaimTracker.stateOf(before))
        val clamp = ClaimTracker.trackedRate(40_000, before, null, 700)
        val (b1, ok1) = ClaimTracker.onClaimBody(book, metal, 1, 1_000)
        assertTrue(ok1)
        val (b2, ok2) = ClaimTracker.onClaimBody(b1, metal, 2, 1_000 + 86_400_000L + 1)
        assertTrue(ok2)
        val after = b2.states.getValue(metal)
        assertEquals(ClaimState.WEAK, ClaimTracker.stateOf(after), "publishing a new claim seq must not clear a tripped discard budget")
        assertEquals(clamp, ClaimTracker.trackedRate(40_000, after, null, 700))
        assertTrue(after.ratios.isEmpty())
    }

    @Test
    fun anInheritedDiscrepantStateNeedsTenObservationsNotFive() {
        var book = ClaimTracker.onClaimBody(TrackerBook(), metal, 1, 1_000).first
        repeat(5) { book = ClaimTracker.onObservation(book, obs(elapsed = 39_911), claim, bpt, 1_000) }
        val (b1, accepted) = ClaimTracker.onClaimBody(book, metal, 2, 1_000 + 86_400_000L)
        assertTrue(accepted)
        book = b1
        for (n in 1..9) {
            book = ClaimTracker.onObservation(book, obs(elapsed = 19_000), claim, bpt, 2_000_000_000)
            assertEquals(ClaimState.DISCREPANT, ClaimTracker.stateOf(book.states.getValue(metal)), "still inherited after $n good observations")
        }
        book = ClaimTracker.onObservation(book, obs(elapsed = 19_000), claim, bpt, 2_000_000_000)
        assertTrue(ClaimTracker.stateOf(book.states.getValue(metal)) != ClaimState.DISCREPANT, "cleared by the tenth")
    }

    @Test
    fun theSecondPenaltyOfAPeerLastsFourteenDays() {
        var book = TrackerBook()
        val k2 = ClaimKey("peer-x", W.SHA_B, "metal")
        val day = 86_400_000L
        repeat(5) { book = ClaimTracker.onObservation(book, obs(metal, elapsed = 39_911), claim, bpt, 1_000) }
        repeat(5) { book = ClaimTracker.onObservation(book, obs(k2, elapsed = 39_911), claim, bpt, 2_000) }
        assertEquals(PeerPenalty(2_000 + 7 * day, 1), book.penalties.getValue("peer-x"))
        val later = 2_000 + 7 * day + 5
        book = ClaimTracker.onObservation(book, obs(k2, elapsed = 39_911), claim, bpt, later)
        assertEquals(PeerPenalty(later + 14 * day, 2), book.penalties.getValue("peer-x"))
        val third = later + 14 * day + 5
        book = ClaimTracker.onObservation(book, obs(k2, elapsed = 39_911), claim, bpt, third)
        assertEquals(PeerPenalty(third + 28 * day, 3), book.penalties.getValue("peer-x"))
    }

    @Test
    fun bestIsTheUpperQuartileByNearestRankAtEveryLength() {
        for (n in 1..20) {
            val ts = TrackerState(ratios = (1..n).map { it * 100L }.shuffled(java.util.Random(n.toLong())))
            assertEquals(((3 * n) / 4 + 1) * 100L, ClaimTracker.best(ts), "n = $n")
        }
    }

    @Test
    fun twoDiscrepantKeysReachedWithoutAnObservationStillDropTheDiscountTo400() {
        val inherited = TrackerState(inheritedDiscrepant = true)
        val tracker = mapOf(metal to inherited, ClaimKey("peer-x", W.SHA_B, "metal") to inherited)
        assertEquals(400, ClaimTracker.disc("peer-x", tracker, emptyMap(), 1_000))
        assertEquals(700, ClaimTracker.disc("peer-x", mapOf(metal to inherited), emptyMap(), 1_000))
        assertEquals(700, ClaimTracker.disc("peer-y", tracker, emptyMap(), 1_000))
    }

    @Test
    fun aMalformedClaimCurveExcludesThePeerWithF8InsteadOfThrowing() {
        val curves = mapOf(
            "descending" to listOf(1024 to 10_000L, 512 to 12_000L),
            "duplicate" to listOf(512 to 10_000L, 512 to 12_000L),
            "five points" to (1..5).map { it * 256 to 10_000L },
        )
        for ((name, curve) in curves) {
            val bad = W.peer("peer-x", prior = W.prior(curve = curve), lastSame = mapOf(W.SHA_A to 1_000_400L))
            val plan = MeshRouter().plan(W.query(), W.snapshot(peers = listOf(bad)))
            val ex = plan.excluded.single { it.nodeId == "peer-x" }
            assertEquals("F8_CLAIM", ex.code, name)
            assertEquals("claim-curve-invalid", ex.detail, name)
            assertNotNull(plan.attempts.firstOrNull { it.tier == Tier.SELF }, "SELF is still planned: $name")
        }
    }

    @Test
    fun aWellFormedFourPointCurveIsStillAccepted() {
        val ok = W.peer("peer-x", prior = W.prior(curve = listOf(256 to 14_000L, 512 to 12_000L, 1024 to 10_000L, 4096 to 8_000L)), lastSame = mapOf(W.SHA_A to 1_000_400L))
        val plan = MeshRouter().plan(W.query(), W.snapshot(peers = listOf(ok)))
        assertTrue(plan.excluded.none { it.nodeId == "peer-x" })
        assertFailsWith<IllegalArgumentException> { Estimator.decodeAtCtx(listOf(1024 to 10_000L, 512 to 12_000L), 600) }
    }
}
