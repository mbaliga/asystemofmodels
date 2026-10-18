package xyz.mdhv.asom.lab.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.policy.Freshness

/** ERR-FX4-RT-*: the findings of the single-pass router review (RP-1 .. RP-11). Each test names the finding it reproduces. */
class ReviewFixTest {
    private val now = 1_000_500L
    private val other = "d4".repeat(32)

    private fun fastPrior() = W.prior(prefill = 400_000, decode = 40_000)

    private fun liar(id: String, vararg shas: String) =
        W.peer(id, files = shas.map { W.file(sha = it, quant = if (it == W.SHA_A) "Q4_K_M" else "Q8_0") }, prior = fastPrior(), state = W.state(held = shas.toList()), lastSame = shas.associateWith { now })

    private class Run(val winners: List<String>, val deltas: List<CapDelta>)

    private fun runPlans(k: Int, peers: List<NodeView>, self: NodeView = W.self(), tracker: Map<ClaimKey, TrackerState> = emptyMap(), q: MeshQuery = W.query(model = "auto", cap = 300)): Run {
        var snap = W.snapshot(self = self, peers = peers, now = now, tracker = tracker, cloud = W.cloud(keys = setOf("openrouter")))
        val winners = ArrayList<String>()
        val deltas = ArrayList<CapDelta>()
        repeat(k) {
            val plan = MeshRouter().plan(q, snap)
            winners += plan.attempts.first().let { if (it.tier == Tier.CLOUD) "cloud" else it.nodeId!! }
            deltas += plan.capDelta
            snap = snap.copy(caps = CapReducer.commit(snap.caps, plan.capDelta))
        }
        return Run(winners, deltas)
    }

    private fun bound(k: Int) = (k + 3) / 4

    @Test
    fun rp1_aLiarWithTwoFilesCannotWinMoreThanOneInFourBySwappingWithItsOtherFile() {
        val run = runPlans(20, listOf(liar("liar", W.SHA_A, W.SHA_B)))
        val wins = run.winners.count { it == "liar" }
        assertTrue(wins <= bound(20), "the liar won $wins of 20 placements (bound ${bound(20)}): ${run.winners}")
        assertTrue(run.winners.count { it == "self-node" } >= 20 - bound(20), "the verified device takes the rest: ${run.winners}")
    }

    @Test
    fun rp1_twoUnverifiedPeersCannotShareTheCapByHandingOverToEachOther() {
        val run = runPlans(20, listOf(liar("liar", W.SHA_A), liar("liar2", W.SHA_A)))
        val unverified = run.winners.count { it == "liar" || it == "liar2" }
        assertTrue(unverified <= bound(20), "UNVERIFIED peers won $unverified of 20 (bound ${bound(20)}): ${run.winners}")
    }

    @Test
    fun rp1_aSingleFileLiarKeepsItsBound() {
        val run = runPlans(20, listOf(liar("liar", W.SHA_A)))
        assertTrue(run.winners.count { it == "liar" } <= bound(20))
    }

    @Test
    fun rp1_aVerifiedSecondFileOfTheSameNodeIsNoPartnerEither() {
        val calmB = ClaimKey("liar", W.SHA_B, "metal")
        val tracker = mapOf(calmB to TrackerState(claimSeq = 1, ratios = List(6) { 1000L }))
        val peer = W.peer(
            "liar", files = listOf(W.file(sha = W.SHA_A, quant = "Q8_0"), W.file(sha = W.SHA_B, quant = "Q4_K_M")), state = W.state(held = listOf(W.SHA_A, W.SHA_B)),
            priors = mapOf(ClaimKey("liar", W.SHA_A, "metal") to W.prior(prefill = 900_000, decode = 90_000), ClaimKey("liar", W.SHA_B, "metal") to fastPrior()),
            lastSame = mapOf(W.SHA_A to now, W.SHA_B to now),
        )
        val run = runPlans(20, listOf(peer), tracker = tracker, q = W.query(model = "auto", cap = 300))
        val liarWins = run.winners.count { it == "liar" }
        assertTrue(run.deltas.count { it.entries.isNotEmpty() } >= 10, "the cap was exercised: ${run.winners} ${run.deltas.map { it.entries.size }}")
        assertTrue(liarWins <= 20, "control")
        val unverifiedWins = run.deltas.indices.count { run.winners[it] == "liar" && run.deltas[it].entries.any { e -> !e.swapped } }
        assertTrue(unverifiedWins <= bound(20), "the UNVERIFIED file won $unverifiedWins of 20 would-win placements (bound ${bound(20)}): ${run.winners}")
        assertTrue(run.winners.count { it == "self-node" } >= 20 - bound(20), "the capped placements go to the verified device, not to the liar's other file: ${run.winners}")
    }

    @Test
    fun rp1_theCounterOfOnePeerIsOneCounterWhateverTheFile() {
        val a = ClaimKey("liar", W.SHA_A, "metal")
        val snap = W.snapshot(self = W.self(), peers = listOf(liar("liar", W.SHA_B)), now = now, caps = mapOf(a to CapCounter(wouldWin = 8, won = 3)), cloud = W.cloud(keys = setOf("openrouter")))
        val plan = MeshRouter().plan(W.query(model = "auto", cap = 300), snap)
        assertEquals("self-node", plan.attempts.first().nodeId, "3 wins of 8 on file A leave no win for file B of the same peer")
        assertTrue(plan.capDelta.entries.single().swapped)
    }

    @Test
    fun rp1_withNoVerifiedPartnerTheCapDoesNotApply() {
        val noEngine = W.self(situation = W.selfSituation(engine = false))
        val run = runPlans(12, listOf(liar("liar", W.SHA_A), liar("liar2", W.SHA_A)), self = noEngine)
        assertTrue(run.deltas.all { it.entries.isEmpty() }, "an UNVERIFIED partner moves nothing: no counter moves")
        assertEquals(1, run.winners.toSet().size, "the best score keeps the head: ${run.winners}")
    }

    @Test
    fun rp1_lawRl11bTheNodeBoundHoldsWithManyFilesAndVerifiedPartners() {
        Laws.run("RL11b", perSeed = 6, floor = 40) { gen, _ ->
            val shas = listOf(W.SHA_A, W.SHA_B, W.SHA_C).take(gen.int(1, 3))
            val peers = mutableListOf(liar("liar", *shas.toTypedArray()))
            val verifiedKey = ClaimKey("cal", W.SHA_A, "metal")
            val tracker = mapOf(verifiedKey to TrackerState(claimSeq = 1, ratios = List(6) { 1000L }))
            val partnerIsPeer = gen.chance(50)
            if (partnerIsPeer) peers += W.peer("cal", prior = W.prior(prefill = 300_000, decode = 30_000), lastSame = mapOf(W.SHA_A to now))
            val self = if (partnerIsPeer) W.self(situation = W.selfSituation(engine = false)) else W.self()
            val k = gen.int(12, 40)
            val run = runPlans(k, peers, self, tracker)
            val wouldWin = run.deltas.count { it.entries.isNotEmpty() }
            if (wouldWin == 0) return@run Outcome.NotApplicable
            val wins = run.winners.count { it == "liar" }
            Laws.check(wins <= bound(wouldWin)) { "the liar won $wins of $wouldWin would-win plans over ${shas.size} files (bound ${bound(wouldWin)})" }
        }
    }

    private val cheapBattery = W.selfSituation(permille = 150, design = 19_000)

    @Test
    fun rp4_thisDeviceOnBatteryWithAnUnknownDesignCapacityIsNotFree() {
        val laptop = W.peer("laptop", cls = DeviceClass.LAPTOP, prior = W.prior(prefill = 60_000, decode = 12_000), lastSame = mapOf(W.SHA_A to now))
        val known = MeshRouter().plan(W.query(cap = 300), W.snapshot(self = W.self(situation = cheapBattery), peers = listOf(laptop), now = now))
        val unknown = MeshRouter().plan(W.query(cap = 300), W.snapshot(self = W.self(situation = W.selfSituation(permille = 150, design = null)), peers = listOf(laptop), now = now))
        val selfKnown = known.attempts.first { it.tier == Tier.SELF }.score!!
        val selfUnknown = unknown.attempts.first { it.tier == Tier.SELF }.score!!
        assertTrue(selfKnown.s2Battery > 0, "control: a known capacity charges the battery")
        assertTrue(selfUnknown.s2Battery >= selfKnown.s2Battery, "an unknown capacity is charged at least as much as a known one (S2 ${selfUnknown.s2Battery} vs ${selfKnown.s2Battery})")
        assertEquals(known.attempts.map { it.tier }, unknown.attempts.map { it.tier }, "the order does not flip to this device because its capacity is unknown")
    }

    @Test
    fun rp5_aCloudBannedAppWithNoEngineAndAnUnusablePeerIsNeverToldToAddAKey() {
        val noEngine = W.self(files = emptyList(), situation = W.selfSituation(engine = false))
        val draining = W.peer("deck", files = listOf(W.file(model = "llama-3.3-70b")), state = W.state(fsm = xyz.mdhv.asom.lab.policy.Fsm.DRAINING), lastSame = mapOf(W.SHA_A to now))
        val app = W.app.copy(cloudBanned = true)
        val q = W.query(model = "llama-3.3-70b", app = app)
        val cloud = W.cloud(keys = setOf("openrouter", "groq"))
        for (mesh in listOf(true, false)) {
            val r = tryPlan(q, W.snapshot(self = noEngine, peers = listOf(draining), now = now, meshOn = mesh, cloud = cloud))
            assertEquals("LOCAL_ENGINE_ABSENT", (r as PlanRes.Err).code, "mesh=$mesh")
        }
    }

    @Test
    fun rp8_aCloudRateOfZeroIsUnmeasuredNotACrash() {
        val noEngine = W.self(files = emptyList(), situation = W.selfSituation(engine = false))
        val cloud = FrozenCloudView(W.catalogue, setOf("groq", "openrouter"), emptyMap(), mapOf("groq" to 400.0, "openrouter" to 500.0), mapOf("groq" to 0L, "openrouter" to -5L))
        val q = W.query(model = "llama-3.3-70b", policyHeader = Policy.FASTEST)
        val plan = MeshRouter().plan(q, W.snapshot(self = noEngine, now = now, cloud = cloud))
        assertTrue(plan.attempts.isNotEmpty() && plan.attempts.all { it.tier == Tier.CLOUD })
    }

    private fun dupFiles(order: Boolean): NodeView {
        val small = W.file(ctx = 512)
        val big = W.file(ctx = 32_768)
        return W.peer("peer-d", files = if (order) listOf(small, big) else listOf(big, small), lastSame = mapOf(W.SHA_A to now))
    }

    @Test
    fun rp9_aDuplicatedFileKeyIsResolvedTheSameWayInEveryInputOrder() {
        val q = W.query(model = "auto", cap = 300, prompt = 1_000)
        val none = W.self(files = emptyList(), situation = W.selfSituation(engine = false))
        val a = tryPlan(q, W.snapshot(self = none, peers = listOf(dupFiles(true)), now = now))
        val b = tryPlan(q, W.snapshot(self = none, peers = listOf(dupFiles(false)), now = now))
        fun shape(r: PlanRes) = when (r) {
            is PlanRes.Ok -> r.plan.attempts.map { it.id() } to r.plan.excluded.map { it.code }
            is PlanRes.Err -> listOf(r.code) to emptyList()
        }
        assertEquals(shape(a), shape(b), "the survivor of two rows with one sha must not depend on their order")
    }

    @Test
    fun rp9_aDuplicatedNodeIdIsResolvedTheSameWayInEveryInputOrder() {
        val p1 = W.peer("peer-d", prior = W.prior(prefill = 400_000, decode = 40_000), lastSame = mapOf(W.SHA_A to now))
        val p2 = W.peer("peer-d", prior = W.prior(prefill = 20_000, decode = 5_000), lastSame = mapOf(W.SHA_A to now))
        val none = W.self(files = emptyList(), situation = W.selfSituation(engine = false))
        val q = W.query(model = "auto", cap = 300)
        fun shape(ps: List<NodeView>) = (tryPlan(q, W.snapshot(self = none, peers = ps, now = now)) as? PlanRes.Ok)?.plan?.attempts?.map { it.id() to it.score!!.total }
        assertEquals(shape(listOf(p1, p2)), shape(listOf(p2, p1)))
    }

    @Test
    fun rp7_theRunnerUpOfTheDetailIsTheRunnerUpTheDominantTermWasComputedAgainst() {
        val slow = W.peer("deck", cls = DeviceClass.HANDHELD, prior = W.prior(prefill = 60_000, decode = 3_000, steady = 3_000), lastSame = mapOf(W.SHA_A to now))
        val plan = MeshRouter().plan(W.query(model = "auto", cap = 300), W.snapshot(self = W.self(), peers = listOf(slow), now = now))
        val sov = plan.attempts.filter { it.tier != Tier.CLOUD }
        assertEquals(Tier.SELF, sov[0].tier)
        assertFalse(sov[1].usable, "the second sovereign attempt is in the unusable block")
        assertEquals("self:best-score", sov[0].reason, "no term: nothing in the same block to compare with")
        assertFalse("runner-up" in plan.detail, "the detail must not name a runner-up the reason was not computed against: ${plan.detail}")
    }

    @Test
    fun rp11_thePlanLevelOutcomesForABandOneLenderAtEachFreshnessAreDocumented() {
        fun world(rx: Long) = W.snapshot(peers = listOf(W.peer("peer-b", state = W.state(thermal = 1), rxMonoMs = rx, lastSame = mapOf(W.SHA_A to now - 1_000))), now = now)
        val fresh = MeshRouter().plan(W.query(), world(now - 1_000))
        val stale = MeshRouter().plan(W.query(), world(now - 100_000))
        val expired = MeshRouter().plan(W.query(), world(now - 400_000))
        assertEquals(Freshness.FRESH, fresh.attempts.first { it.tier == Tier.PEER }.freshness)
        assertEquals(listOf("F10_THERMAL"), stale.excluded.map { it.code })
        val probe = expired.attempts.first { it.tier == Tier.PEER }
        assertTrue(probe.probeOnly && probe.freshness == Freshness.EXPIRED)
        assertNotNull(expired.attempts.firstOrNull { it.tier == Tier.SELF })
        assertEquals(Tier.SELF, expired.attempts.first().tier, "probe-only: placed after every non-probe candidate")
    }

    private fun planOf(vararg tiers: Tier): List<PlannedAttempt> {
        val cloud = (MeshRouter().plan(W.query(model = "llama-3.3-70b"), W.snapshot(self = W.self(files = emptyList(), situation = W.selfSituation(engine = false)), now = now, cloud = W.cloud(keys = setOf("groq", "openrouter")))).attempts)
        val sov = MeshRouter().plan(W.query(model = "auto"), W.snapshot(peers = listOf(W.peer("p", lastSame = mapOf(W.SHA_A to now))), now = now)).attempts
        var c = 0
        return tiers.map { t -> if (t == Tier.CLOUD) cloud[c++ % cloud.size] else sov.first { it.tier == t } }
    }

    @Test
    fun rp6_everyAttemptFailedMapsOnlyToAnExistingCodeOrToNone() {
        val mixed = planOf(Tier.PEER, Tier.SELF, Tier.CLOUD, Tier.CLOUD)
        assertEquals("ALL_PROVIDERS_COOLING", AttemptErrors.errorAfterAttempts(mixed, setOf(0, 1, 2, 3), false).code, "every cloud attempt failed: each is in the v1 cooldown")
        assertEquals(null, AttemptErrors.errorAfterAttempts(mixed, setOf(0, 1, 2), true).code, "a deadline with a cloud attempt untried: ALL_PROVIDERS_COOLING would be untrue")
        val sovereign = planOf(Tier.PEER, Tier.SELF)
        val none = AttemptErrors.errorAfterAttempts(sovereign, setOf(0, 1), false)
        assertEquals(null, none.code, "no cloud attempt in the plan: no existing code fits (owner decision)")
        assertEquals(AttemptErrors.ALL_ATTEMPTS_FAILED, none.why)
        assertEquals(AttemptErrors.DEADLINE, AttemptErrors.errorAfterAttempts(sovereign, setOf(0), true).why)
        var cases = 0
        for (plan in listOf(mixed, sovereign, planOf(Tier.CLOUD), planOf(Tier.SELF, Tier.CLOUD, Tier.PEER))) for (mask in 0 until (1 shl plan.size)) for (d in listOf(false, true)) {
            val code = AttemptErrors.errorAfterAttempts(plan, plan.indices.filter { mask and (1 shl it) != 0 }.toSet(), d).code
            cases++
            assertTrue(code == null || code in MeshErrorCodes.V1 || code in MeshErrorCodes.V2, "invented code $code")
        }
        assertTrue(cases >= 40)
    }

    private fun mac(breaker: BreakerView = BreakerView(null, null), rx: Long = now - 1_000, open: Boolean = true, prefill: Long = 400_000, decode: Long = 40_000) =
        W.peer("mac", prior = W.prior(prefill = prefill, decode = decode), lastSame = mapOf(W.SHA_A to now), breaker = breaker, rxMonoMs = rx, sessionOpen = open)

    private fun planWith(m: NodeView, self: NodeView = W.self()) = MeshRouter().plan(W.query(model = "auto", cap = 300), W.snapshot(self = self, peers = listOf(m), now = now, tracker = mapOf(ClaimKey("mac", W.SHA_A, "metal") to TrackerState(claimSeq = 1, ratios = List(6) { 1000L }))))

    @Test
    fun rp2_aHalfOpenPeerThatWouldWinIsNamedForAnOfferOnlyProbe() {
        val fresh = planWith(mac())
        assertEquals(listOf(Tier.PEER, Tier.SELF), fresh.attempts.map { it.tier })
        assertEquals(null, fresh.probeIndex, "control: a fresh peer heads the plan and needs no probe")
        val half = planWith(mac(breaker = BreakerView(null, null, halfOpen = true)))
        assertEquals(listOf(Tier.SELF, Tier.PEER), half.attempts.map { it.tier }, "the order is unchanged: probe-only is behind the working device (RL13)")
        assertEquals(1, half.probeIndex)
        assertEquals(fresh.attempts.first().score!!.total, half.attempts[1].score!!.total, "the probe is chosen on the score it would have had")
        val expired = planWith(mac(rx = now - 31_000, open = false))
        assertEquals(Freshness.EXPIRED, expired.attempts.first { it.tier == Tier.PEER }.freshness)
        assertEquals(expired.attempts.indexOfFirst { it.tier == Tier.PEER }, expired.probeIndex)
    }

    @Test
    fun rp2_aProbeIsNamedOnlyWhenItWouldBeatTheHeadOfItsBlock() {
        val slow = planWith(mac(breaker = BreakerView(null, null, halfOpen = true), prefill = 30_000, decode = 4_100))
        assertTrue(slow.attempts[1].usable && slow.attempts[1].score!!.total > slow.attempts[0].score!!.total, "the probe-only peer is usable and loses: ${slow.attempts.map { it.score?.total }}")
        assertEquals(null, slow.probeIndex, "a half-open peer that would lose anyway costs nothing to leave alone")
        val alone = planWith(mac(breaker = BreakerView(null, null, halfOpen = true)), W.self(situation = W.selfSituation(engine = false)))
        assertEquals(listOf(Tier.PEER), alone.attempts.map { it.tier })
        assertEquals(null, alone.probeIndex, "it already heads the plan: its offer is the probe")
    }

    @Test
    fun rp2_lawRl18bAnyBestPeerMadeHalfOpenIsStillOfferedAProbe() {
        Laws.run("RL18b", perSeed = 12, floor = 100) { gen, _ ->
            val peers = (0 until gen.int(1, 3)).map { i ->
                W.peer("peer-$i", prior = W.prior(prefill = gen.long(100_000, 900_000), decode = gen.long(20_000, 90_000)), lastSame = mapOf(W.SHA_A to now), rxMonoMs = now - gen.long(0, 4_000))
            }
            val tracker = peers.associate { ClaimKey(it.nodeId, W.SHA_A, "metal") to TrackerState(claimSeq = 1, ratios = List(6) { 1000L }) }
            val self = W.self(prior = W.prior(prefill = gen.long(20_000, 400_000), decode = gen.long(4_000, 50_000), steady = 4_000))
            val snap = W.snapshot(self = self, peers = peers, now = now, tracker = tracker)
            val q = W.query(model = "auto", cap = 300)
            val base = MeshRouter().plan(q, snap)
            val best = base.attempts.first()
            if (best.tier != Tier.PEER || !best.usable) return@run Outcome.NotApplicable
            if (base.attempts.none { it.tier == Tier.SELF }) return@run Outcome.NotApplicable
            val half = snap.copy(peers = peers.map { if (it.nodeId == best.nodeId) it.copy(breaker = BreakerView(null, null, halfOpen = true)) else it })
            val plan = MeshRouter().plan(q, half)
            val at = plan.attempts.indexOfFirst { it.id() == best.id() }
            if (at < 0) return@run Outcome.Violated("the half-open peer left the plan")
            val lead = plan.attempts.indexOfFirst { it.tier != Tier.CLOUD && it.usable && !it.probeOnly }
            val ok = plan.attempts[at].probeOnly && (at < lead || (plan.probeIndex != null && plan.attempts[plan.probeIndex!!].score!!.total <= best.score!!.total))
            Laws.check(ok) { "half-open ${best.id()} at $at of ${plan.attempts.map { it.id() }}, probeIndex ${plan.probeIndex}" }
        }
    }

    @Test
    fun rp2_aProbeIsDueOncePerInterval() {
        val cfg = MeshConfig()
        assertTrue(ProbePolicy.due(null, now, cfg))
        assertFalse(ProbePolicy.due(now - cfg.probeIntervalMs + 1, now, cfg))
        assertTrue(ProbePolicy.due(now - cfg.probeIntervalMs, now, cfg))
    }

    @Test
    fun theIdleUnloadBoundaryIsInclusive() {
        fun load(ago: Long): Long {
            val peer = W.peer("mac", lastSame = mapOf(W.SHA_A to now - ago))
            return MeshRouter().plan(W.query(model = "auto", cap = 300), W.snapshot(self = W.self(situation = W.selfSituation(engine = false)), peers = listOf(peer), now = now)).attempts.single().estimate!!.loadMs
        }
        val idle = W.limits.idleUnloadMs
        assertEquals(0L, load(idle), "used exactly idleUnloadMs ago: still loaded")
        assertTrue(load(idle + 1) > 0, "one millisecond later the model has been unloaded")
    }
}
