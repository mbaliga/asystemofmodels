package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.StateDoc
import xyz.mdhv.asom.routing.Candidate
import xyz.mdhv.asom.routing.RouteQuery

/** `Fsm`, `Governor`, `BatteryBand`, `Freshness` and the parsed state live in `:mesh-policy` (ERRATA ERR-LP-8); this alias keeps the LAB_SPEC 6.1 name. */
typealias LiveState = StateDoc

/** The ordinal is part of the total order (LAB_SPEC 6.1). */
enum class Tier { SELF, PEER, CLOUD }

enum class DeviceClass { PHONE, TABLET, HANDHELD, LAPTOP, DESKTOP, SBC }

enum class ClaimState { LOCAL_MEASURED, UNVERIFIED, CORROBORATED, WEAK, DISCREPANT }

/** R3-CLOSURE-5 (6): a virtual selector matches files by kind; the spec's `FileKey` has no such field. */
enum class FileKind { CHAT, EMBED }

data class FileKey(
    val modelId: String,
    val fileSha256: String,
    val quant: String?,
    val fileBytes: Long,
    val catalogueRank: Int?,
    val kind: FileKind = FileKind.CHAT,
    val contextTokens: Long? = null,
)

data class ClaimKey(val nodeId: String, val fileSha256: String, val backend: String)

data class PerfPrior(
    val decodeAt: List<Pair<Int, Long>>,
    val prefillMilliTokPerSec: Long,
    val ttft0Ms: Long,
    val steadyMilliTokPerSec: Long,
    val throttleOnsetMs: Long?,
    val powerMilliW: Long?,
    val kvBytesPerToken: Long,
    val peakProcessBytes: Long,
    val flags: Set<String>,
)

/** LOCAL ONLY; never serialised to a peer. Additive fields (ERRATA): [hasEngine], [backend], [localQueueMs] (R3-CLOSURE-5 (4)). */
data class SelfSituation(
    val batteryPermille: Int?,
    val charging: Boolean,
    val onBattery: Boolean,
    val saver: Boolean,
    val batteryDesignMilliWh: Long?,
    val thermalCode: Int,
    val governor: Governor,
    val availBytes: Long?,
    val loaded: Set<String>,
    val userActive: Boolean,
    val busyForMs: Long,
    val hasEngine: Boolean = true,
    val backend: String = "cpu",
    val localQueueMs: Long = 0,
)

data class LinkStats(
    val rttMs: Long,
    val kbps: Long,
    val path: PeerPath,
    val metered: Boolean,
    val sessionWarm: Boolean,
    val samples: Int,
)

data class BreakerView(val coolingUntilMonoMs: Long?, val declineBackoffUntilMonoMs: Long?, val halfOpen: Boolean = false)

data class PeerLimits(val maxConcurrent: Int, val maxBodyBytes: Long, val maxTokens: Int, val rpm: Int, val idleUnloadMs: Long)

data class PeerRowView(
    val paired: Boolean,
    val routeEnabled: Boolean,
    val inferGrantedToMe: Boolean,
    val requireCharging: Boolean,
    val limits: PeerLimits,
)

/**
 * Additive fields (ERRATA): [maxContextTokens] and [batteryDesignMilliWh] (R3-CLOSURE-5 (3), (5)), [stateRegressed] (the requester's
 * [LiveStateCache] saw the stored state's `seq` fall below the highest `seq` of the session).
 */
data class NodeView(
    val nodeId: String,
    val nodeTag: String,
    val tier: Tier,
    val deviceClass: DeviceClass,
    val peer: PeerRowView?,
    val files: List<FileKey>,
    val priors: Map<ClaimKey, PerfPrior>,
    val self: SelfSituation?,
    val state: LiveState?,
    val stateRxMonoMs: Long?,
    val sessionOpen: Boolean,
    val goawaySeen: Boolean,
    val link: LinkStats?,
    val breaker: BreakerView,
    val lastSameFileMonoMs: Map<String, Long>,
    val ownReservationsMs: Long,
    val maxContextTokens: Long? = null,
    val batteryDesignMilliWh: Long? = null,
    val stateRegressed: Boolean = false,
)

data class AppPolicy(
    val pkg: String,
    val meshAllowed: Boolean,
    val cloudBanned: Boolean,
    val deviceOnly: Boolean,
    val allowMeshOnMetered: Boolean,
    val neverCloudWhenDevicesCanAnswer: Boolean,
)

/** [embeddingIdentity] (additive): the file the request's embeddings must come from (F16, R3-CLOSURE-5 (5)). */
data class MeshQuery(
    val v1: RouteQuery,
    val op: String,
    val stream: Boolean,
    val promptTokens: Int,
    val promptBytes: Long,
    val maxTokensCap: Int?,
    val app: AppPolicy,
    val deadlineMs: Long,
    val embeddingIdentity: String? = null,
)

/** The cloud tier's inputs as the requester froze them at snapshot time (LAB_SPEC 6.7). Doubles appear only where v1 uses them (R6). */
data class FrozenCloudView(
    val catalogue: Catalogue,
    val keysPresent: Set<String>,
    val coolingUntilWallMs: Map<String, Long>,
    val ewmaMs: Map<String, Double>,
    val cloudRateMilliTokPerSec: Map<String, Long> = emptyMap(),
)

enum class DiscardReason { INCOMPLETE, SHORT, OVERLONG, CONCURRENT, SETTINGS }

/** One observation of one attempt, kept or discarded. [ratio] is computed for every observation (the discard budget clamps to the lowest one). */
data class ObsRecord(val kept: Boolean, val ratio: Long, val outBytes: Long, val discard: DiscardReason?)

/**
 * The tracker's state for one (peer, file, backend). [ratios] holds the kept ratios of the window in arrival order. [strikes] survive a new claim
 * (ERRATA: they are inherited from the sibling keys of the same peer and file when a key is created).
 */
data class TrackerState(
    val claimSeq: Long? = null,
    val acceptedAtWallMs: Long? = null,
    val ratios: List<Long> = emptyList(),
    val recent: List<ObsRecord> = emptyList(),
    val strikes: Int = 0,
    val inheritedDiscrepant: Boolean = false,
    val memoryDiscrepant: Boolean = false,
)

/** Peer-wide penalty (LAB_SPEC 6.6 `disc`): while [untilWallMs] is in the future, every key of the peer is discounted to 400 permille. */
data class PeerPenalty(val untilWallMs: Long, val repeats: Int)

data class CapCounter(val wouldWin: Int = 0, val won: Int = 0)

data class CapDelta(val entries: List<Entry> = emptyList()) {
    data class Entry(val key: ClaimKey, val wouldWinInc: Int, val wonInc: Int, val swapped: Boolean)
}

data class MeshSnapshot(
    val nowMonoMs: Long,
    val wallNowMs: Long,
    val meshGlobalOn: Boolean,
    val self: NodeView,
    val peers: List<NodeView>,
    val cloud: FrozenCloudView,
    val tracker: Map<ClaimKey, TrackerState>,
    val caps: Map<ClaimKey, CapCounter>,
    val appEwmaOut: Map<String, Int>,
    val config: MeshConfig,
    val peerPenalty: Map<String, PeerPenalty> = emptyMap(),
)

/** E0..E12. [decEff] and [preEff] are the tracked rates the estimate used, in milliTok/s. */
data class Estimate(
    val outTokens: Long,
    val netMs: Long,
    val loadMs: Long,
    val queueMs: Long,
    val preEff: Long,
    val prefillMs: Long,
    val decEff: Long,
    val decodeMs: Long,
    val ttftMs: Long,
    val totalMs: Long,
    val energyMilliJ: Long,
    val batteryUsedPermille: Long,
) {
    companion object {
        val ZERO = Estimate(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

data class ScoreBreakdown(
    val s1Time: Long,
    val s2Battery: Long,
    val s3Heat: Long,
    val s4Locality: Long,
    val s5Quality: Long,
    val s6Uncertainty: Long,
) {
    val total: Long get() = Sat.add(s1Time, s2Battery, s3Heat, s4Locality, s5Quality, s6Uncertainty)

    fun term(i: Int): Long = when (i) {
        1 -> s1Time
        2 -> s2Battery
        3 -> s3Heat
        4 -> s4Locality
        5 -> s5Quality
        6 -> s6Uncertainty
        else -> throw IllegalArgumentException("terms are S1..S6")
    }

    companion object {
        val TERM_NAMES = listOf("time", "battery", "heat", "locality", "quality", "uncertainty")
    }
}

data class Exclusion(val nodeId: String, val tier: Tier, val modelId: String, val fileSha256: String, val code: String, val detail: String? = null)

data class PlannedAttempt(
    val tier: Tier,
    val nodeId: String?,
    val file: FileKey?,
    val cloud: Candidate?,
    val estimate: Estimate?,
    val score: ScoreBreakdown?,
    val usable: Boolean,
    val probeOnly: Boolean,
    val reason: String,
    val freshness: Freshness? = null,
    val claimState: ClaimState? = null,
)

data class MeshPlan(val attempts: List<PlannedAttempt>, val excluded: List<Exclusion>, val capDelta: CapDelta, val detail: String = "")

/** No new code: the four v1 names come from `AsomErrorCode`; the three v2 names (roadmap v2 contract delta) are not in the frozen enum, and a test says so. */
class MeshPlanException(val code: String, message: String, val excluded: List<Exclusion> = emptyList()) : Exception(message)

object MeshErrorCodes {
    val V1: Set<String> = setOf("MODEL_UNKNOWN", "NO_PROVIDER_KEY", "ALL_PROVIDERS_COOLING", "LOCAL_ENGINE_ABSENT")
    val V2: Set<String> = setOf("THERMAL_HOLD", "MODEL_OOM", "CONTEXT_OVERFLOW")
}

/** What a [NodeView] says about this node's fast fields after staleness handling. */
internal data class FastView(
    val freshness: Freshness,
    val fsm: Fsm,
    val governor: Governor,
    val thermalBand: Int,
    val queueBucket: Int,
    val powerSource: String,
    val charging: Boolean,
    val batteryBand: BatteryBand?,
    val backend: String?,
    val held: Set<String>?,
)
