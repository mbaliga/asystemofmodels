package xyz.mdhv.asom.lab.router

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.policy.Freshness

/** LAB_SPEC 6.8, laws RL1..RL13, RL18, RL20 and RL-H over generated worlds. Oracles are written from the world, not from the router's own key functions. */
class RouterLawsTest {
    private fun GenWorld.with(f: (MeshSnapshot) -> MeshSnapshot) = GenWorld(q, f(s))
    private fun GenWorld.withQ(f: (MeshQuery) -> MeshQuery) = GenWorld(f(q), s)
    private fun GenWorld.noEngine() = with { it.copy(self = it.self.copy(self = it.self.self!!.copy(hasEngine = false))) }
    private fun GenWorld.engine() = with { it.copy(self = it.self.copy(self = it.self.self!!.copy(hasEngine = true))) }
    private fun GenWorld.v1Only(): Boolean = !q.app.cloudBanned && !q.app.deviceOnly

    private fun ids(p: MeshPlan) = p.attempts.map { it.id() }

    @Test
    fun rl1_conservativeExtension() {
        Laws.run("RL1a") { gen, w0 ->
            if (!w0.v1Only()) return@run Outcome.NotApplicable
            var w = w0.noEngine()
            w = if (gen.chance(50)) w.with { it.copy(meshGlobalOn = false) } else w.with { s -> s.copy(peers = s.peers.map { it.copy(peer = it.peer!!.copy(paired = false)) }) }
            val mesh = tryPlan(w.q, w.s)
            val ref = v1(w.q, w.s)
            when {
                mesh is PlanRes.Ok && ref is PlanRes.Ok -> Laws.check(ids(mesh.plan) == ids(ref.plan).take(w.s.config.maxAttempts)) { "mesh ${ids(mesh.plan)} vs v1 ${ids(ref.plan)}" }
                mesh is PlanRes.Err && ref is PlanRes.Err -> Laws.check(mesh.code == ref.code) { "mesh error ${mesh.code} vs v1 ${ref.code}" }
                else -> Outcome.Violated("mesh ${mesh::class.simpleName} vs v1 ${ref::class.simpleName}")
            }
        }
        Laws.run("RL1b") { gen, w0 ->
            if (!w0.v1Only() || w0.q.v1.fallback.isNotEmpty()) return@run Outcome.NotApplicable
            val w = w0.engine().let { e -> if (gen.chance(50)) e.with { s -> s.copy(meshGlobalOn = false) } else e.withQ { q -> q.copy(app = q.app.copy(meshAllowed = false)) } }
            if (Policy.fromWire(w.q.v1.model) == Policy.LOCAL_ONLY || w.q.resolvedPolicy(w.s.config) == Policy.LOCAL_ONLY) return@run Outcome.NotApplicable
            val mesh = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            if (mesh.attempts.any { it.tier == Tier.PEER }) return@run Outcome.Violated("a peer attempt with the mesh off")
            val selfAtt = mesh.attempts.filter { it.tier == Tier.SELF }
            val bad = mesh.excluded.filter { it.tier == Tier.SELF && it.code !in setOf("F4_MODEL", "F5_CONTEXT", "F6_MEMORY", "F8_CLAIM", "F10_THERMAL", "F16_EMBED_IDENTITY") }
            if (bad.isNotEmpty()) return@run Outcome.Violated("SELF removed by ${bad.first().code}")
            val ref = v1(w.q, w.s) as? PlanRes.Ok
            val cloudIds = mesh.attempts.filter { it.tier == Tier.CLOUD }.map { it.id() }
            val want = ref?.plan?.attempts?.map { it.id() }.orEmpty().take(w.s.config.maxAttempts - selfAtt.size)
            if (cloudIds != want) return@run Outcome.Violated("cloud tail $cloudIds vs v1 $want")
            if (w.q.resolvedPolicy(w.s.config) == Policy.AUTO && selfAtt.isNotEmpty() && mesh.attempts.first().tier != Tier.SELF) return@run Outcome.Violated("SELF is not first under auto")
            if (selfAtt.isEmpty()) Outcome.NotApplicable else Outcome.Held
        }
    }

    @Test
    fun rl1c_aLongPromptOnAPhoneKeepsSelfFirst() {
        Laws.run("RL1c") { _, w0 ->
            val w = w0.engine().withQ { it.copy(promptTokens = 30_000, promptBytes = 120_000, v1 = it.v1.copy(model = "auto", fallback = emptyList()), app = it.app.copy(meshAllowed = false, cloudBanned = false, deviceOnly = false)) }
            val plan = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            if (plan.attempts.none { it.tier == Tier.SELF }) return@run Outcome.NotApplicable
            Laws.check(plan.attempts.first().tier == Tier.SELF) { "SELF is not first: ${ids(plan)}" }
        }
    }

    @Test
    fun rl2_localOnlyAndDeviceOnlyAreSelfOnly() {
        Laws.run("RL2") { gen, w0 ->
            val w = when (gen.int(0, 2)) {
                0 -> w0.withQ { it.copy(v1 = it.v1.copy(model = "local-only", fallback = emptyList())) }
                1 -> w0.withQ { it.copy(v1 = it.v1.copy(policyHeader = Policy.LOCAL_ONLY, model = "llama-3.3-70b", fallback = emptyList())) }
                else -> w0.withQ { it.copy(app = it.app.copy(deviceOnly = true), v1 = it.v1.copy(fallback = emptyList())) }
            }.engine()
            val plan = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            Laws.check(plan.attempts.isNotEmpty() && plan.attempts.all { it.tier == Tier.SELF }) { "non-SELF attempt: ${ids(plan)}" }
        }
    }

    @Test
    fun rl3_fallbackIsCloudOnlyAndEqualsV1() {
        Laws.run("RL3") { gen, w0 ->
            val fb = gen.pick(listOf("openrouter", "groq", "anthropic", "trainy-ai"))
            val w = w0.withQ { it.copy(v1 = it.v1.copy(fallback = listOf(fb, "groq"), model = if (Policy.fromWire(it.v1.model) == Policy.LOCAL_ONLY) "llama-3.3-70b" else it.v1.model, policyHeader = null), app = it.app.copy(cloudBanned = false)) }
            if (w.q.resolvedPolicy(w.s.config) == Policy.LOCAL_ONLY) return@run Outcome.NotApplicable
            val mesh = tryPlan(w.q, w.s)
            val ref = v1(w.q, w.s)
            when {
                w.q.app.deviceOnly -> if (mesh is PlanRes.Err) Outcome.Held else Outcome.Violated("device-only with a fallback header served")
                mesh is PlanRes.Ok && ref is PlanRes.Ok ->
                    Laws.check(mesh.plan.attempts.all { it.tier == Tier.CLOUD } && ids(mesh.plan) == ids(ref.plan).take(6)) { "mesh ${ids(mesh.plan)} vs v1 ${ids(ref.plan)}" }
                mesh is PlanRes.Err && ref is PlanRes.Err -> Laws.check(mesh.code == ref.code) { "codes ${mesh.code} vs ${ref.code}" }
                else -> Outcome.Violated("mesh ${mesh::class.simpleName} vs v1 ${ref::class.simpleName}")
            }
        }
    }

    @Test
    fun rl4_peerAttemptsAreEligible() {
        Laws.run("RL4") { _, w ->
            val plan = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val peers = plan.attempts.filter { it.tier == Tier.PEER }
            if (peers.isEmpty()) return@run Outcome.NotApplicable
            val allowed = OracleFilters.peerAllowed(w)
            val bad = peers.firstOrNull { a -> val row = w.s.peers.first { it.nodeId == a.nodeId }.peer!!; !(allowed && row.paired && row.routeEnabled && row.inferGrantedToMe) }
            Laws.check(bad == null) { "peer ${bad?.nodeId} is not eligible" }
        }
    }

    @Test
    fun rl5_noAttemptViolatesAHardFilter() {
        Laws.run("RL5") { _, w ->
            val plan = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val sov = plan.attempts.filter { it.tier != Tier.CLOUD }
            if (sov.isEmpty()) return@run Outcome.NotApplicable
            val bad = sov.firstNotNullOfOrNull { a -> OracleFilters.violation(w, a)?.let { "${a.id()}: $it" } }
            Laws.check(bad == null) { bad!! }
        }
    }

    private fun <T> shufflePerm(gen: WorldGen, xs: List<T>): List<T> = gen.shuffled(xs.size).map { xs[it] }

    @Test
    fun rl6_determinismAndPermutationInvariance() {
        Laws.run("RL6") { gen, w ->
            val a = tryPlan(w.q, w.s)
            val b = tryPlan(w.q, w.s)
            val cat = w.s.cloud.catalogue
            val perm = cat.copy(providers = shufflePerm(gen, cat.providers).map { it.copy(models = shufflePerm(gen, it.models)) }, models = shufflePerm(gen, cat.models))
            val w2 = w.with { s ->
                s.copy(
                    peers = shufflePerm(gen, s.peers).map { it.copy(files = shufflePerm(gen, it.files)) }, self = s.self.copy(files = shufflePerm(gen, s.self.files)),
                    cloud = s.cloud.copy(catalogue = perm),
                )
            }
            val c = tryPlan(w2.q, w2.s)
            fun proj(p: MeshPlan) = Triple(p.attempts.map { listOf(it.id(), it.estimate, it.score, it.usable, it.probeOnly, it.reason, it.freshness, it.claimState) }, p.excluded, p.capDelta)
            fun same(x: PlanRes, y: PlanRes) = when {
                x is PlanRes.Ok && y is PlanRes.Ok -> proj(x.plan) == proj(y.plan)
                x is PlanRes.Err && y is PlanRes.Err -> x.code == y.code
                else -> false
            }
            Laws.check(same(a, b) && same(a, c)) { "plans differ under repetition or permutation: $a / $c" }
        }
    }

    @Test
    fun rl7_noTwoAttemptsAreEqual() {
        Laws.run("RL7") { _, w ->
            val plan = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val idl = ids(plan)
            Laws.check(idl.toSet().size == idl.size) { "duplicate attempts $idl" }
        }
    }

    private fun position(plan: PlanRes, id: String): Int = ((plan as? PlanRes.Ok)?.plan?.attempts?.map { it.id() }?.indexOf(id) ?: -1).let { if (it < 0) Int.MAX_VALUE else it }

    private fun mutatePeer(w: GenWorld, id: String, f: (NodeView) -> NodeView) = w.with { s -> s.copy(peers = s.peers.map { if (it.nodeId == id) f(it) else it }) }

    private fun scale(p: PerfPrior, num: Long, den: Long) = p.copy(
        decodeAt = p.decodeAt.map { it.first to maxOf(1L, it.second * num / den) }, prefillMilliTokPerSec = maxOf(1L, p.prefillMilliTokPerSec * num / den),
        steadyMilliTokPerSec = maxOf(1L, p.steadyMilliTokPerSec * num / den),
    )

    /** File-level moves change one file of the node; node-level moves change every file of it, so those are measured against other nodes' attempts only. */
    private class Move(val name: String, val nodeLevel: Boolean, val improve: (NodeView, String, Long) -> NodeView?, val worsen: (NodeView, String, Long) -> NodeView?)

    private fun onlyFile(n: NodeView, sha: String, f: (PerfPrior) -> PerfPrior) = n.copy(priors = n.priors.mapValues { (k, v) -> if (k.fileSha256 == sha) f(v) else v })

    private val moves = listOf(
        Move("rtt", true, { n, _, _ -> n.link?.takeIf { it.rttMs > 1 }?.let { l -> n.copy(link = l.copy(rttMs = l.rttMs / 2)) } }, { n, _, _ -> n.link?.let { l -> n.copy(link = l.copy(rttMs = l.rttMs * 2 + 5)) } }),
        Move("kbps", true, { n, _, _ -> n.link?.takeIf { it.kbps > 0 }?.let { l -> n.copy(link = l.copy(kbps = l.kbps * 2)) } }, { n, _, _ -> n.link?.takeIf { it.kbps > 1_000 }?.let { l -> n.copy(link = l.copy(kbps = l.kbps / 2)) } }),
        Move("rates", false, { n, sha, _ -> onlyFile(n, sha) { scale(it, 2, 1) } }, { n, sha, _ -> onlyFile(n, sha) { scale(it, 1, 2) } }),
        Move("queue", true, { n, _, _ -> n.state?.takeIf { it.queueBucket > 0 }?.let { d -> n.copy(state = d.copy(queueBucket = d.queueBucket - 1)) } }, { n, _, _ -> n.state?.takeIf { it.queueBucket < 2 }?.let { d -> n.copy(state = d.copy(queueBucket = d.queueBucket + 1)) } }),
        Move("reservations", true, { n, _, _ -> n.takeIf { it.ownReservationsMs > 0 }?.copy(ownReservationsMs = 0) }, { n, _, _ -> n.copy(ownReservationsMs = n.ownReservationsMs + 5_000) }),
        Move("thermal", true, { n, _, _ -> n.state?.takeIf { it.thermalBand > 0 }?.let { d -> n.copy(state = d.copy(thermalBand = 0)) } }, { n, _, _ -> n.state?.takeIf { it.thermalBand == 0 }?.let { d -> n.copy(state = d.copy(thermalBand = 1)) } }),
        Move("charging", true, { n, _, _ -> n.state?.takeIf { !it.charging }?.let { d -> n.copy(state = d.copy(charging = true)) } }, { n, _, _ -> n.state?.takeIf { it.charging && it.powerSource == "battery" }?.let { d -> n.copy(state = d.copy(charging = false)) } }),
        Move("warm model", false, { n, sha, now -> n.copy(lastSameFileMonoMs = n.lastSameFileMonoMs + (sha to now)) }, { n, sha, _ -> n.takeIf { sha in it.lastSameFileMonoMs }?.copy(lastSameFileMonoMs = n.lastSameFileMonoMs - sha) }),
    )

    private fun unlimited(w: GenWorld) = w.with { it.copy(config = it.config.copy(maxAttempts = 100, maxPeerAttempts = 100)) }

    /** The number of attempts before [a] in the plan, counting only attempts of other nodes when [nodeLevel]; MAX when absent. */
    private fun rank(plan: PlanRes, a: PlannedAttempt, nodeLevel: Boolean): Int {
        val list = (plan as? PlanRes.Ok)?.plan?.attempts ?: return Int.MAX_VALUE
        val i = list.indexOfFirst { it.id() == a.id() }
        if (i < 0) return Int.MAX_VALUE
        return if (nodeLevel) list.take(i).count { it.nodeId != a.nodeId } else i
    }

    @Test
    fun rl8_monotonicity() {
        Laws.run("RL8", calm = true) { gen, w0 ->
            val w = unlimited(w0)
            val base = tryPlan(w.q, w.s)
            val plan = (base as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val peers = plan.attempts.filter { it.tier == Tier.PEER }
            if (peers.isEmpty()) return@run Outcome.NotApplicable
            val target = gen.pick(peers)
            val node = w.s.peers.first { it.nodeId == target.nodeId }
            val sha = target.file!!.fileSha256
            val move = gen.pick(moves)
            val before = rank(base, target, move.nodeLevel)
            fun show(r: PlanRes) = (r as? PlanRes.Ok)?.plan?.attempts?.joinToString { it.id().take(24) + " S=" + it.score?.total + (if (it.usable) "" else " unusable") + (if (it.probeOnly) " probe" else "") }
            val improved = move.improve(node, sha, w.s.nowMonoMs)
            if (improved != null && target.freshness != Freshness.EXPIRED) {
                val res = tryPlan(w.q, mutatePeer(w, node.nodeId) { improved }.s)
                val after = rank(res, target, move.nodeLevel)
                if (after > before) return@run Outcome.Violated("improving ${move.name} moved ${target.id()} from $before to $after: ${show(base)} => ${show(res)}")
            }
            val worse = move.worsen(node, sha, w.s.nowMonoMs)
            if (worse != null) {
                val res = tryPlan(w.q, mutatePeer(w, node.nodeId) { worse }.s)
                val after = rank(res, target, move.nodeLevel)
                if (after < before) return@run Outcome.Violated("worsening ${move.name} moved ${target.id()} from $before to $after: ${show(base)} => ${show(res)}")
            }
            Outcome.Held
        }
    }

    private fun dominates(a: ScoreBreakdown, b: ScoreBreakdown): Boolean = (1..6).all { a.term(it) <= b.term(it) } && (1..6).any { a.term(it) < b.term(it) }

    @Test
    fun rl9_dominance() {
        Laws.run("RL9", calm = true) { _, w0 ->
            val w = unlimited(w0)
            val plan = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val sov = plan.attempts.filter { it.tier != Tier.CLOUD }
            var pairs = 0
            val policy = w.q.resolvedPolicy(w.s.config)
            for (i in sov.indices) for (j in sov.indices) {
                if (i == j) continue
                val a = sov[i]
                val b = sov[j]
                if (a.probeOnly != b.probeOnly || (policy == Policy.AUTO && a.usable != b.usable)) continue
                if (policy == Policy.BEST_REASONING && a.file!!.catalogueRank != b.file!!.catalogueRank) continue
                if (dominates(a.score!!, b.score!!)) {
                    pairs++
                    if (i > j) return@run Outcome.Violated("${a.id()} dominates ${b.id()} but comes after it")
                }
            }
            if (pairs == 0) Outcome.NotApplicable else Outcome.Held
        }
    }

    private fun rankOf(a: PlannedAttempt, w: GenWorld): Int = (if (a.tier == Tier.CLOUD) w.s.cloud.catalogue.model(a.cloud!!.modelId)?.rank else a.file!!.catalogueRank) ?: Int.MAX_VALUE

    @Test
    fun rl10_policyLaws() {
        // The cap may swap the top UNVERIFIED candidate with another usable one, which is its bounded departure from the policy key (ERRATA): the four
        // policy laws hold exactly in calm worlds, and the two that survive a swap (cheapest before cloud, the auto blocks) also hold with the cap active.
        Laws.run("RL10", calm = true) { _, w -> policyLaw(w, true) }
        Laws.run("RL10b") { _, w -> policyLaw(w, false) }
    }

    private fun policyLaw(w: GenWorld, strict: Boolean): Outcome {
        run {
            val plan = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return Outcome.NotApplicable
            if (w.q.v1.fallback.isNotEmpty()) return Outcome.NotApplicable
            val a = plan.attempts
            return when (w.q.resolvedPolicy(w.s.config)) {
                Policy.CHEAPEST -> {
                    val firstCloud = a.indexOfFirst { it.tier == Tier.CLOUD }
                    val lastSov = a.indexOfLast { it.tier != Tier.CLOUD }
                    if (firstCloud < 0 || lastSov < 0) return Outcome.NotApplicable
                    Laws.check(lastSov < firstCloud) { "a cloud attempt precedes a sovereign one: ${ids(plan)}" }
                }
                Policy.BEST_REASONING -> {
                    val ranks = a.map { rankOf(it, w) }
                    if (!strict || a.size < 2) Outcome.NotApplicable else Laws.check(ranks == ranks.sorted()) { "ranks $ranks are not non-decreasing" }
                }
                Policy.FASTEST -> {
                    val s1 = a.filter { it.tier != Tier.CLOUD }.map { it.score!!.s1Time }
                    val cloud = a.filter { it.tier == Tier.CLOUD }.map { it.id() }
                    val ref = (v1(w.q, w.s) as? PlanRes.Ok)?.plan?.attempts?.map { it.id() }.orEmpty()
                    if (!strict || s1.size + cloud.size < 2) return Outcome.NotApplicable
                    Laws.check(s1 == s1.sorted() && cloud == ref.take(cloud.size)) { "S1 $s1 or cloud order $cloud vs v1 $ref" }
                }
                Policy.AUTO -> {
                    val never = w.q.app.neverCloudWhenDevicesCanAnswer
                    fun cls(x: PlannedAttempt) = if (x.tier == Tier.CLOUD) 1 else if (x.usable) 0 else 2
                    val order = if (never) mapOf(0 to 0, 2 to 1, 1 to 2) else mapOf(0 to 0, 1 to 1, 2 to 2)
                    val seq = a.map { order.getValue(cls(it)) }
                    if (a.size < 2) Outcome.NotApplicable else Laws.check(seq == seq.sorted()) { "auto blocks out of order: ${a.map { cls(it) }}" }
                }
                Policy.LOCAL_ONLY -> Outcome.NotApplicable
            }
        }
    }

    @Test
    fun rl11_theCapBound() {
        Laws.run("RL11", perSeed = 8, floor = 20) { gen, w0 ->
            val now = w0.s.nowMonoMs
            val a = W.peer("cap-a", files = listOf(W.file()), prior = W.prior(prefill = 400_000, decode = 40_000 + gen.long(0, 20_000)), lastSame = mapOf(W.SHA_A to now))
            val b = W.peer("cap-b", files = listOf(W.file()), prior = W.prior(prefill = 300_000, decode = 30_000), lastSame = mapOf(W.SHA_A to now))
            val keyA = ClaimKey("cap-a", W.SHA_A, "metal")
            val keyB = ClaimKey("cap-b", W.SHA_A, "metal")
            val calm = TrackerState(claimSeq = 1, ratios = List(6) { 1000L })
            var snap = W.snapshot(self = W.self(files = emptyList()), peers = listOf(a, b), now = now, tracker = mapOf(keyB to calm), cloud = W.cloud(keys = setOf("openrouter")))
            val q = W.query(model = "auto", cap = 300)
            var wouldWin = 0
            var wins = 0
            val k = gen.int(12, 40)
            repeat(k) {
                val plan = MeshRouter().plan(q, snap)
                val best = plan.attempts.filter { it.tier == Tier.PEER && it.usable }.minByOrNull { it.score!!.total }!!
                if (best.nodeId == "cap-a") wouldWin++
                if (plan.attempts.first().nodeId == "cap-a") wins++
                if (plan.attempts.first().tier == Tier.CLOUD) return@run Outcome.Violated("the cap moved work to the cloud")
                snap = snap.copy(caps = CapReducer.commit(snap.caps, plan.capDelta))
            }
            if (wouldWin == 0) return@run Outcome.NotApplicable
            Laws.check(wins <= (wouldWin + 3) / 4) { "won $wins of $wouldWin would-win plans (bound ${(wouldWin + 3) / 4}); keys $keyA $keyB" }
        }
    }

    private fun peerSet(plan: MeshPlan) = plan.attempts.filter { it.tier == Tier.PEER }.map { it.nodeId!! }.toSet()

    @Test
    fun rl12_claimsNeverGrantEligibility() {
        Laws.run("RL12") { gen, w ->
            val base = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val allowed = OracleFilters.peerAllowed(w)
            val eligible = w.s.peers.filter { p -> allowed && p.peer!!.paired && p.peer.routeEnabled && p.peer.inferGrantedToMe }.map { it.nodeId }.toSet()
            if (!peerSet(base).all { it in eligible }) return@run Outcome.Violated("a peer outside the eligible set was planned")
            // more favourable claims: no candidate is removed, and nothing F1..F3 removed comes back
            val fav = w.with { s ->
                s.copy(
                    peers = s.peers.map { n ->
                        n.copy(
                            priors = n.priors.mapValues { scale(it.value, 2, 1).copy(kvBytesPerToken = it.value.kvBytesPerToken / 2) },
                            state = n.state?.copy(fsm = xyz.mdhv.asom.lab.policy.Fsm.SERVING, thermalBand = 0, governor = xyz.mdhv.asom.lab.policy.Governor.RUN, held = (n.state.held + n.files.map { it.fileSha256 }).distinct()),
                        )
                    },
                )
            }
            val after = (tryPlan(fav.q, fav.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.Violated("more favourable claims turned a plan into an error")
            val pairsBefore = base.excluded.map { it.nodeId to it.fileSha256 }.toSet()
            val gained = after.excluded.filter { (it.nodeId to it.fileSha256) !in pairsBefore }
            if (gained.isNotEmpty()) return@run Outcome.Violated("a more favourable claim removed ${gained.first().nodeId}/${gained.first().code}")
            val registry = setOf("F1_ELIGIBILITY", "F2_NOT_PAIRED", "F3_NO_SCOPE")
            val restored = base.excluded.filter { it.code in registry }.filter { b -> after.excluded.none { it.nodeId == b.nodeId && it.fileSha256 == b.fileSha256 && it.code == b.code } }
            if (restored.isNotEmpty()) return@run Outcome.Violated("a claim restored ${restored.first().nodeId}, removed by ${restored.first().code}")
            Laws.check(peerSet(after).all { it in eligible }) { "a claim made a peer eligible that the registry and policy do not allow" }
        }
    }

    @Test
    fun rl13_stalenessAndExpiry() {
        Laws.run("RL13a", perSeed = 300, calm = true) { gen, w0 ->
            val w = unlimited(w0)
            val base = tryPlan(w.q, w.s)
            val plan = (base as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val cand = plan.attempts.filter { it.tier == Tier.PEER && (it.freshness == Freshness.FRESH || it.freshness == Freshness.WARM) }.ifEmpty { return@run Outcome.NotApplicable }
            val target = gen.pick(cand)
            val stale = mutatePeer(w, target.nodeId!!) { n -> n.copy(stateRxMonoMs = w.s.nowMonoMs - 100_000, sessionOpen = true, goawaySeen = false, stateRegressed = false, state = n.state!!.copy(sampledAgeMs = 0)) }
            val after = rank(tryPlan(w.q, stale.s), target, true)
            Laws.check(after >= rank(base, target, true)) { "STALE ranked ${target.id()} better than the same state fresh" }
        }
        Laws.run("RL13b", calm = true) { _, w ->
            val w2 = w.withQ { it.copy(v1 = it.v1.copy(model = "auto", policyHeader = null, fallback = emptyList()), app = it.app.copy(meshAllowed = true, deviceOnly = false)) }.with { it.copy(meshGlobalOn = true) }
            val plan = (tryPlan(w2.q, w2.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val sov = plan.attempts.filter { it.tier != Tier.CLOUD }
            if (sov.none { it.freshness == Freshness.EXPIRED }) return@run Outcome.NotApplicable
            if (sov.any { (it.freshness == Freshness.EXPIRED) != it.probeOnly && !w2.s.peers.first { p -> p.nodeId == it.nodeId }.breaker.halfOpen }) return@run Outcome.Violated("EXPIRED and probe-only disagree")
            for (i in sov.indices) for (j in i + 1 until sov.size) {
                if (sov[i].probeOnly && !sov[j].probeOnly && sov[i].usable == sov[j].usable) return@run Outcome.Violated("${sov[i].id()} (probe) ahead of ${sov[j].id()}")
            }
            Outcome.Held
        }
    }

    @Test
    fun rl18_aFailedPeerReturnsAfterItsCooldown() {
        Laws.run("RL18", calm = true) { gen, w ->
            val plan = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val peers = plan.attempts.filter { it.tier == Tier.PEER }
            if (peers.isEmpty()) return@run Outcome.NotApplicable
            val target = gen.pick(peers)
            val now = w.s.nowMonoMs
            val cooling = tryPlan(w.q, mutatePeer(w, target.nodeId!!) { it.copy(breaker = BreakerView(now + 1, null)) }.s)
            val ended = tryPlan(w.q, mutatePeer(w, target.nodeId) { it.copy(breaker = BreakerView(now, null)) }.s)
            val present = { r: PlanRes -> position(r, target.id()) != Int.MAX_VALUE }
            Laws.check(!present(cooling) && present(ended)) { "cooling present=${present(cooling)}, after the cooldown present=${present(ended)}" }
        }
    }

    @Test
    fun rlH_noAppFacingProjectionLeaksPeerSituation() {
        Laws.run("RL-H") { gen, w ->
            val plan = (tryPlan(w.q, w.s) as? PlanRes.Ok)?.plan ?: return@run Outcome.NotApplicable
            val statuses = RouteReasons.PEER_STATUSES.toList() + listOf("PEER_THERMAL", "PEER_BATTERY", "PEER_USER_ACTIVE", "429", "503")
            var n = 0
            for (a in plan.attempts) {
                val prev = gen.pick(statuses)
                for (reason in listOf(a.reason, RouteReasons.withPrev(a.reason, prev), RouteReasons.withPrev(a.reason + ";stale", prev))) {
                    val app = RouteReasons.appFacing(reason)
                    n++
                    if ("PEER_" in app || "/battery" in app || "/heat" in app || "/uncertainty" in app || "stale" in app || "cap:" in app || "probe" in app) return@run Outcome.Violated("leaks: $reason -> $app")
                }
            }
            if (n == 0) Outcome.NotApplicable else Outcome.Held
        }
    }

    @Test
    fun theWorkedExampleReasonKeepsItsHeatTermOnlyInTheLedger() {
        val reason = "peer:best-score/heat"
        assertEquals("peer:best-score", RouteReasons.appFacing(reason))
        assertEquals("peer:failover;prev:peer-unavailable", RouteReasons.appFacing("peer:failover;prev:PEER_BUSY"))
    }
}
