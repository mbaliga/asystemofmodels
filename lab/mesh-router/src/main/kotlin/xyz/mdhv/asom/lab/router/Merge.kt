package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.routing.Candidate

/** One scored sovereign candidate (this device or an own peer). */
data class Scored(
    val tier: Tier,
    val nodeId: String,
    val file: FileKey,
    val estimate: Estimate,
    val score: ScoreBreakdown,
    val usable: Boolean,
    val probeOnly: Boolean,
    val claimState: ClaimState,
    val freshness: Freshness,
    val key: ClaimKey?,
) {
    val total: Long get() = score.total
}

/** One entry of the v1 cloud plan, in v1's order. [s1Ms] is the cloud estimate the `fastest` policy uses; null means unmeasured (v1 sorts it last). */
data class CloudEntry(val candidate: Candidate, val rank: Int?, val s1Ms: Long?)

sealed interface MergeItem {
    data class Sov(val c: Scored) : MergeItem

    data class Cloud(val e: CloudEntry) : MergeItem
}

data class MergeResult(val items: List<MergeItem>, val capDelta: CapDelta, val cappedSwap: Boolean)

/**
 * The merge per policy (LAB_SPEC 6.7, R03) and the cap on UNVERIFIED claims (router.md 5.5). Total-order tie-break for every sovereign sort:
 * `(primary key, S, tier ordinal SELF < PEER, nodeId, modelId, fileSha256)`; cloud entries keep v1's order.
 *
 * Probe-only partition: within a sovereign block a probe-only entry is placed after every non-probe entry with the same primary key. For `auto` the block
 * is the primary key; for the other policies the policy key (S2 + S3, S1, catalogue rank) comes first, so that RL10 (the policy laws) holds and RL13 is
 * measured within equal primary keys (ERRATA).
 */
object Merge {
    private val unranked = Int.MAX_VALUE

    private fun tie(): Comparator<Scored> = compareBy<Scored> { it.total }.thenBy { it.tier.ordinal }.thenBy { it.nodeId }.thenBy { it.file.modelId }.thenBy { it.file.fileSha256 }

    private fun byPrimary(primary: (Scored) -> Long): Comparator<Scored> = compareBy<Scored> { primary(it) }.thenBy { it.probeOnly }.then(tie())

    fun order(policy: Policy, sov: List<Scored>, cloud: List<CloudEntry>, neverCloudWhenDevicesCanAnswer: Boolean, caps: Map<ClaimKey, CapCounter>): MergeResult {
        val cloudItems = cloud.map { MergeItem.Cloud(it) }
        val merged: List<MergeItem> = when (policy) {
            Policy.LOCAL_ONLY -> sov.sortedWith(byPrimary { 0L }).map { MergeItem.Sov(it) }
            Policy.AUTO -> {
                val block = byPrimary { 0L }
                val usable = sov.filter { it.usable }.sortedWith(block).map { MergeItem.Sov(it) }
                val unusable = sov.filter { !it.usable }.sortedWith(block).map { MergeItem.Sov(it) }
                if (neverCloudWhenDevicesCanAnswer) usable + unusable + cloudItems else usable + cloudItems + unusable
            }
            Policy.CHEAPEST -> sov.sortedWith(byPrimary { Sat.add(it.score.s2Battery, it.score.s3Heat) }).map { MergeItem.Sov(it) } + cloudItems
            Policy.FASTEST -> {
                val sv = sov.sortedWith(byPrimary { it.score.s1Time })
                interleave(sv, cloudItems) { s, c -> c.e.s1Ms == null || s.score.s1Time < c.e.s1Ms }
            }
            Policy.BEST_REASONING -> {
                val sv = sov.sortedWith(byPrimary { (it.file.catalogueRank ?: unranked).toLong() })
                interleave(sv, cloudItems) { s, c -> (s.file.catalogueRank ?: unranked) <= (c.e.rank ?: unranked) }
            }
        }
        return applyCap(merged, caps)
    }

    /** Each sovereign entry goes before the first cloud entry it beats, keeping both relative orders (cloud order never changes). */
    private fun interleave(sv: List<Scored>, cloud: List<MergeItem.Cloud>, beats: (Scored, MergeItem.Cloud) -> Boolean): List<MergeItem> {
        val out = ArrayList<MergeItem>()
        var ci = 0
        for (s in sv) {
            while (ci < cloud.size && !beats(s, cloud[ci])) {
                out += cloud[ci]
                ci++
            }
            out += MergeItem.Sov(s)
        }
        while (ci < cloud.size) out += cloud[ci++]
        return out
    }

    /**
     * The top entry, when it is a sovereign UNVERIFIED candidate and another USABLE sovereign candidate exists, wins at most `ceilDiv(wouldWin, 4)` of the
     * placements it would win; otherwise it swaps places with the best other usable sovereign candidate. The cap never moves work to the cloud and never
     * changes eligibility.
     */
    private fun applyCap(items: List<MergeItem>, caps: Map<ClaimKey, CapCounter>): MergeResult {
        val top = (items.firstOrNull() as? MergeItem.Sov)?.c
        if (top == null || top.claimState != ClaimState.UNVERIFIED || top.key == null) return MergeResult(items, CapDelta(), false)
        val di = items.indexOfFirst { it is MergeItem.Sov && it.c !== top && it.c.usable }
        if (di < 0) return MergeResult(items, CapDelta(), false)
        val counter = ClaimTracker.capAt(caps, top.key)
        val wouldWin = counter.wouldWin + 1
        return if (counter.won >= Sat.ceilDiv(wouldWin.toLong(), 4)) {
            val out = items.toMutableList()
            out[0] = items[di]
            out[di] = items[0]
            MergeResult(out, CapDelta(listOf(CapDelta.Entry(top.key, 1, 0, true))), true)
        } else {
            MergeResult(items, CapDelta(listOf(CapDelta.Entry(top.key, 1, 1, false))), false)
        }
    }
}
