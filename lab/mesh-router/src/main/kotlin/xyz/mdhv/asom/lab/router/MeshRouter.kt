package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.policy.DestinationSets
import xyz.mdhv.asom.lab.policy.Dest
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.PolicyInputs
import xyz.mdhv.asom.routing.Candidate

/**
 * `plan(query, snapshot) -> plan` (LAB_SPEC 6.1): pure. It reads no clock (the snapshot carries `nowMonoMs` and `wallNowMs`), performs no I/O, and iterates only
 * sorted collections in decision paths. Everything a peer said reached the snapshot through the reducers ([LiveStateCache], [ClaimTracker]).
 */
class MeshRouter {
    fun plan(q: MeshQuery, s: MeshSnapshot): MeshPlan {
        val cfg = s.config
        val policy = Policy.fromWire(q.v1.model) ?: q.v1.policyHeader ?: cfg.defaultPolicy
        val fallback = q.v1.fallback
        val p = DestinationSets.compute(
            PolicyInputs(
                meshGlobalOn = s.meshGlobalOn, appMeshAllowed = q.app.meshAllowed, appCloudBanned = q.app.cloudBanned, appDeviceOnly = q.app.deviceOnly,
                policyLocalOnly = policy == Policy.LOCAL_ONLY, fallbackProviders = fallback.takeIf { it.isNotEmpty() }, noTrain = q.v1.noTrain,
            ),
        ).dests
        if (p.isEmpty()) {
            throw MeshPlanException(if (policy == Policy.LOCAL_ONLY) "LOCAL_ENGINE_ABSENT" else "NO_PROVIDER_KEY", "no permitted destination for this request")
        }
        val cloud = if (Dest.C in p) CloudAdapter.plan(s.cloud, q.v1, s.wallNowMs, cfg.defaultPolicy) else null

        if (fallback.isNotEmpty()) {
            return when (cloud) {
                is CloudTier.Failed -> throw MeshPlanException(cloud.code, "X-Asom-Fallback: the v1 cloud tier failed")
                is CloudTier.Plan -> MeshPlan(cloud.candidates.take(cfg.maxAttempts).mapIndexed { i, c -> cloudAttempt(c, if (i == 0) "v1:policy" else "cloud:failover") }, emptyList(), CapDelta())
                null -> throw MeshPlanException("NO_PROVIDER_KEY", "X-Asom-Fallback with the cloud not permitted")
            }
        }

        val outTokens = Estimator.outTokens(q.maxTokensCap, s.appEwmaOut[q.app.pkg], cfg)
        val virtual = Policy.fromWire(q.v1.model) != null
        val selfHasEngine = s.self.self?.hasEngine == true
        val nodes = buildList {
            if (Dest.T in p && selfHasEngine) add(s.self)
            if (Dest.O in p) addAll(s.peers.distinctBy { it.nodeId })
        }.sortedBy { it.nodeId }

        val excluded = ArrayList<Exclusion>()
        class Survivor(val node: NodeView, val file: FileKey, val fast: FastView?, val ok: PriorResolution.Ok)
        val survivors = ArrayList<Survivor>()
        var universeSize = 0
        val universe = ArrayList<Pair<NodeView, FileKey>>()
        for (n in nodes) {
            val fast = if (n.tier == Tier.PEER) fastViewOf(n, s.nowMonoMs) else null
            val files = n.files.filter { serves(it, q, virtual, cfg) }.distinctBy { it.fileSha256 }.sortedBy { it.fileSha256 }
            for (f in files) {
                universeSize++
                universe += n to f
                val prior = Priors.resolve(n, f, fast, q.promptTokens.toLong(), s)
                val fail = HardFilters.firstFailing(n, f, fast, prior, p, q, s, outTokens)
                if (fail != null) {
                    excluded += Exclusion(n.nodeId, n.tier, f.modelId, f.fileSha256, fail.code, fail.detail)
                } else {
                    survivors += Survivor(n, f, fast, prior as PriorResolution.Ok)
                }
            }
        }

        val bestRank = survivors.mapNotNull { it.file.catalogueRank }.minOrNull()
        val sov = survivors.map { sv ->
            val est = Scoring.estimate(sv.node, sv.file, sv.fast, sv.ok, q, s, outTokens)
            val sc = Scoring.score(sv.node, sv.file, sv.fast, est, q, s, virtual, bestRank)
            val fr = sv.fast?.freshness ?: Freshness.FRESH
            Scored(
                tier = sv.node.tier, nodeId = sv.node.nodeId, file = sv.file, estimate = est, score = sc, usable = Scoring.usable(sv.node, est, q, cfg),
                probeOnly = fr == Freshness.EXPIRED || sv.node.breaker.halfOpen, claimState = sv.ok.state, freshness = fr,
                key = if (sv.node.tier == Tier.PEER) sv.ok.key else null,
            )
        }

        val cloudEntries: List<CloudEntry> = (cloud as? CloudTier.Plan)?.candidates?.map { c -> cloudEntry(c, policy, q, s, outTokens) }.orEmpty()
        val merged = Merge.order(policy, sov, cloudEntries, q.app.neverCloudWhenDevicesCanAnswer, s.caps)
        val attempts = truncate(merged.items, cfg)

        if (attempts.isEmpty()) throw errorFor(policy, p, selfHasEngine, universe, excluded, (cloud as? CloudTier.Failed)?.code, virtual)

        val planned = attempts.mapIndexed { i, item -> planned(i, item, attempts, merged, policy, sov.isNotEmpty(), cloudEntries.isNotEmpty()) }
        return MeshPlan(planned, excluded.sortedWith(compareBy({ it.nodeId }, { it.fileSha256 })), merged.capDelta, detail(planned, excluded))
    }

    private fun serves(f: FileKey, q: MeshQuery, virtual: Boolean, cfg: MeshConfig): Boolean =
        if (!virtual) {
            f.modelId == q.v1.model
        } else {
            val kindOk = if (q.op == "embeddings") f.kind == FileKind.EMBED else f.kind == FileKind.CHAT
            val floor = cfg.autoRankFloor
            kindOk && (floor == null || (f.catalogueRank != null && f.catalogueRank <= floor))
        }

    private fun cloudAttempt(c: Candidate, reason: String) = PlannedAttempt(Tier.CLOUD, null, null, c, null, null, true, false, reason)

    private fun cloudEntry(c: Candidate, policy: Policy, q: MeshQuery, s: MeshSnapshot, outTokens: Long): CloudEntry {
        val rank = s.cloud.catalogue.model(c.modelId)?.rank
        var s1: Long? = null
        if (policy == Policy.FASTEST) {
            val ewma = s.cloud.ewmaMs[c.provider.id]
            if (ewma != null) {
                val ttft = Math.ceil(ewma).toLong().coerceIn(0, Sat.MAX)
                val rate = s.cloud.cloudRateMilliTokPerSec[c.provider.id] ?: s.config.cloudDecodePriorMilliTokPerSec
                val total = Sat.add(ttft, Sat.ceilDiv(Sat.mul(outTokens - 1, 1_000_000), rate))
                s1 = Sat.add(total, if (q.stream) ttft else 0L)
            }
        }
        return CloudEntry(c, rank, s1)
    }

    /** `maxAttempts` (6) with at most `maxPeerAttempts` (3) peer attempts; a peer beyond the third is dropped, never moved. */
    private fun truncate(items: List<MergeItem>, cfg: MeshConfig): List<MergeItem> {
        val out = ArrayList<MergeItem>()
        var peers = 0
        for (it in items) {
            if (out.size >= cfg.maxAttempts) break
            if (it is MergeItem.Sov && it.c.tier == Tier.PEER) {
                if (peers >= cfg.maxPeerAttempts) continue
                peers++
            }
            out += it
        }
        return out
    }

    private fun planned(i: Int, item: MergeItem, attempts: List<MergeItem>, merged: MergeResult, policy: Policy, sovereignPresent: Boolean, cloudPresent: Boolean): PlannedAttempt {
        when (item) {
            is MergeItem.Cloud -> {
                val reason = if (i > 0) {
                    "cloud:failover"
                } else if (!sovereignPresent) {
                    "v1:policy"
                } else {
                    when (policy) {
                        Policy.FASTEST -> "cloud:policy-fastest"
                        Policy.BEST_REASONING -> "cloud:policy-rank"
                        else -> "cloud:no-usable-sovereign"
                    }
                }
                return cloudAttempt(item.e.candidate, reason)
            }
            is MergeItem.Sov -> {
                val c = item.c
                val tier = if (c.tier == Tier.SELF) "self" else "peer"
                val reason = if (i > 0) {
                    "$tier:failover"
                } else {
                    val code = when {
                        attempts.size == 1 -> "only-eligible"
                        !cloudPresent || policy == Policy.AUTO || policy == Policy.LOCAL_ONLY -> "best-score"
                        policy == Policy.CHEAPEST -> "policy-cheapest"
                        policy == Policy.FASTEST -> "policy-fastest"
                        else -> "policy-rank"
                    }
                    val runnerUp = attempts.drop(1).firstOrNull { it is MergeItem.Sov && (policy != Policy.AUTO || it.c.usable == c.usable) } as? MergeItem.Sov
                    val term = if (code == "only-eligible" || runnerUp == null) null else RouteReasons.dominantTerm(c.score, runnerUp.c.score)
                    val flags = buildList {
                        if (merged.cappedSwap) add("cap:unverified")
                        if (c.probeOnly) add("probe")
                        if (c.freshness == Freshness.STALE) add("stale")
                    }
                    RouteReasons.compose(tier, code, term, flags)
                }
                return PlannedAttempt(c.tier, c.nodeId, c.file, null, c.estimate, c.score, c.usable, c.probeOnly, reason, c.freshness, c.claimState)
            }
        }
    }

    private fun detail(planned: List<PlannedAttempt>, excluded: List<Exclusion>): String = buildString {
        fun brk(a: PlannedAttempt) = a.score?.let { "S1=${it.s1Time} S2=${it.s2Battery} S3=${it.s3Heat} S4=${it.s4Locality} S5=${it.s5Quality} S6=${it.s6Uncertainty} S=${it.total}" } ?: "cloud"
        val sovereign = planned.filter { it.tier != Tier.CLOUD }
        sovereign.getOrNull(0)?.let { append("winner ${it.nodeId}/${it.file!!.modelId} ${brk(it)}") }
        sovereign.getOrNull(1)?.let { append("; runner-up ${it.nodeId}/${it.file!!.modelId} ${brk(it)}") }
        if (excluded.isNotEmpty()) {
            if (isNotEmpty()) append("; ")
            append("excluded ").append(excluded.sortedWith(compareBy({ it.nodeId }, { it.fileSha256 })).joinToString(",") { "${it.nodeId}/${it.fileSha256.take(8)}:${it.code}" })
        }
    }

    /**
     * The most specific true cause among the existing codes, computed over the destination set `P` (design 7.6; ERRATA for the order):
     * a v2 code when SELF was excluded for that reason, then `ALL_PROVIDERS_COOLING` when every sovereign candidate is cooling or backing off,
     * then the cloud tier's own v1 code, then the v1 code for the app's permitted universe. `NO_ELIGIBLE_NODE` does not exist.
     */
    private fun errorFor(
        policy: Policy,
        p: Set<Dest>,
        selfHasEngine: Boolean,
        universe: List<Pair<NodeView, FileKey>>,
        excluded: List<Exclusion>,
        cloudErr: String?,
        virtual: Boolean,
    ): MeshPlanException {
        fun err(code: String, message: String) = MeshPlanException(code, message, excluded.sortedWith(compareBy({ it.nodeId }, { it.fileSha256 })))
        val selfPairs = universe.count { it.first.tier == Tier.SELF }
        val selfEx = excluded.filter { it.tier == Tier.SELF }
        if (selfPairs > 0 && selfEx.size == selfPairs) {
            val codes = selfEx.map { it.code }.toSet()
            when {
                "F10_THERMAL" in codes -> return err("THERMAL_HOLD", "this device is too hot to serve the request")
                "F6_MEMORY" in codes -> return err("MODEL_OOM", "not enough free memory for the model")
                "F5_CONTEXT" in codes -> return err("CONTEXT_OVERFLOW", "the request does not fit the model's context")
            }
        }
        if (universe.isNotEmpty() && excluded.size == universe.size && excluded.all { it.code == "F14_BREAKER" || it.code == "F15_DECLINE_BACKOFF" }) {
            return err("ALL_PROVIDERS_COOLING", "all candidates, including your paired devices, are unavailable")
        }
        if (Dest.C in p && cloudErr != null) return err(cloudErr, "the cloud tier has no usable provider")
        if (policy == Policy.LOCAL_ONLY || (Dest.T in p && !selfHasEngine && Dest.O !in p)) return err("LOCAL_ENGINE_ABSENT", "no local engine can serve the request")
        if (universe.isEmpty() && !virtual) return err("MODEL_UNKNOWN", "no device this app may use holds the model")
        return err("NO_PROVIDER_KEY", "no usable provider for this app")
    }
}
