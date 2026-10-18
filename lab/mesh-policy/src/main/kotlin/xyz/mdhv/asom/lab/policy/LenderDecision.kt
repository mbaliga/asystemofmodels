package xyz.mdhv.asom.lab.policy

import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString

enum class DeclineCode { PEER_BUSY, PEER_UNAVAILABLE, MODEL_NOT_OFFERED, SCOPE_DENIED, DUPLICATE_ATTEMPT }

/** Connection or attempt errors the decision table can produce (`ERROR` frame). */
enum class MeshErrorCode { PEER_NOT_PAIRED, FRAME_TOO_LARGE }

data class OfferView(val attemptId: String, val model: String, val op: String, val promptBytes: Long, val maxTokens: Long, val deadlineMs: Long, val stream: Boolean)

data class LenderLimits(val maxBodyBytes: Long = 8_388_608, val maxTokens: Long = 4_096, val maxConcurrent: Int = 1, val rpm: Int = 30)

/**
 * Everything the lender's decision reads. `presenceActive` is a PRESENCE input (LP-0): it may change the accept/decline decision and its code, and nothing else on the
 * wire (LP-1); its `retryAfterMs` is the constant of a condition so that the decline never counts down to the user's last input (ERRATA ERR-FX4-LD-2).
 * `presenceHoldRemainingMs` is how long the LP-2 hold-down still has to run; the decision no longer reads it.
 */
data class LenderSituation(
    val registryStatus: PeerStatus,
    val inferScopeGranted: Boolean,
    val attemptSeenWithin24h: Boolean,
    val modelAllowedAndLoadable: Boolean,
    val limits: LenderLimits,
    val servingConditionsOk: Boolean,
    val presenceActive: Boolean,
    val presenceHoldRemainingMs: Long,
    val predictedThermalHold: Boolean,
    val estStartMs: Long,
    val inflight: Int,
    val requestsThisMinute: Int,
    /** The lender's own availability (design 3.2 rule 1): only `SERVING` takes offers. The default exists for callers that already fold it into [servingConditionsOk]. */
    val fsm: Fsm = Fsm.SERVING,
    /** False once this session is past its 30-minute maximum age: it takes no new attempt, the requester dials a fresh session (ERRATA ERR-FX4-LD-3). */
    val sessionAcceptsNew: Boolean = true,
)

sealed interface LenderReply {
    /** `retain` is always `none` (row 8): the requester's app, its data class and `P` are never sent, and neither is any request for retention. */
    data class Accept(val retain: String = "none") : LenderReply

    data class Decline(val code: DeclineCode, val retryAfterMs: Long) : LenderReply

    data class Error(val code: MeshErrorCode, val closeConnection: Boolean) : LenderReply
}

class LenderDecision(val reply: LenderReply, val row: String)

/**
 * The lender's decision table (LAB_SPEC 7.2: `trust.md` 7.3 plus rows 6a, 6b and 8), evaluated in order; the first failing row decides.
 *  1 not PAIRED -> `ERROR PEER_NOT_PAIRED` and close;  2 no `infer` scope -> `SCOPE_DENIED`;  3 duplicate `attemptId` (24 h) -> `DUPLICATE_ATTEMPT`;
 *  4 model not allowed or not loadable -> `MODEL_NOT_OFFERED`;  5 offer above the limits -> `ERROR FRAME_TOO_LARGE` (the decline enum has no code for it, ERRATA ERR-LP-6);
 *  6 availability other than `SERVING`, serving conditions, or any presence cause -> `PEER_UNAVAILABLE` (one constant `retryAfterMs`, ERRATA ERR-FX4-LD-2);
 *  6c the session is past its maximum age -> `PEER_UNAVAILABLE` (re-dial);  6a a predicted thermal hold -> `PEER_UNAVAILABLE`;  6b `estStartMs > deadlineMs` -> `PEER_BUSY`;
 *  7 concurrency or requests per minute -> `PEER_BUSY`;  8 `retain` is always none.  Then `INFER_ACCEPT`.
 */
object LenderDecisionTable {
    const val MIN_RETRY_MS = 5_000L
    const val MAX_RETRY_MS = 600_000L

    /** PROVISIONAL starting values (ERRATA ERR-LP-7): the spec fixes only the range 5,000..600,000. */
    const val CONDITION_RETRY_MS = 30_000L
    const val BUSY_RETRY_MS = 5_000L

    /** A session past its maximum age is not unavailable, only old: the requester may dial a fresh one at once. */
    const val REDIAL_RETRY_MS = MIN_RETRY_MS

    private fun clamp(ms: Long) = ms.coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)

    fun decide(o: OfferView, s: LenderSituation): LenderDecision = when {
        s.registryStatus != PeerStatus.PAIRED -> LenderDecision(LenderReply.Error(MeshErrorCode.PEER_NOT_PAIRED, closeConnection = true), "1")
        !s.inferScopeGranted -> LenderDecision(LenderReply.Decline(DeclineCode.SCOPE_DENIED, clamp(CONDITION_RETRY_MS)), "2")
        s.attemptSeenWithin24h -> LenderDecision(LenderReply.Decline(DeclineCode.DUPLICATE_ATTEMPT, clamp(BUSY_RETRY_MS)), "3")
        !s.modelAllowedAndLoadable -> LenderDecision(LenderReply.Decline(DeclineCode.MODEL_NOT_OFFERED, clamp(CONDITION_RETRY_MS)), "4")
        o.promptBytes > s.limits.maxBodyBytes || o.maxTokens > s.limits.maxTokens -> LenderDecision(LenderReply.Error(MeshErrorCode.FRAME_TOO_LARGE, closeConnection = false), "5")
        s.fsm != Fsm.SERVING || s.presenceActive || !s.servingConditionsOk -> LenderDecision(LenderReply.Decline(DeclineCode.PEER_UNAVAILABLE, clamp(CONDITION_RETRY_MS)), "6")
        !s.sessionAcceptsNew -> LenderDecision(LenderReply.Decline(DeclineCode.PEER_UNAVAILABLE, clamp(REDIAL_RETRY_MS)), "6c")
        s.predictedThermalHold -> LenderDecision(LenderReply.Decline(DeclineCode.PEER_UNAVAILABLE, clamp(CONDITION_RETRY_MS)), "6a")
        s.estStartMs > o.deadlineMs -> LenderDecision(LenderReply.Decline(DeclineCode.PEER_BUSY, clamp(BUSY_RETRY_MS)), "6b")
        s.inflight >= s.limits.maxConcurrent || s.requestsThisMinute >= s.limits.rpm -> LenderDecision(LenderReply.Decline(DeclineCode.PEER_BUSY, clamp(BUSY_RETRY_MS)), "7")
        else -> LenderDecision(LenderReply.Accept(), "8")
    }
}

/** What each decision puts on the wire. There is no `queuePos` and no `estStartMs` (LP-1): those values move with the lender's own local use. */
object LenderWire {
    fun accept(o: OfferView, fileSha256: String, servedModel: String): JObject =
        JObject(listOf("attemptId" to JString(o.attemptId), "fileSha256" to JString(fileSha256), "servedModel" to JString(servedModel)))

    fun decline(o: OfferView, d: LenderReply.Decline): JObject =
        JObject(listOf("attemptId" to JString(o.attemptId), "code" to JString(d.code.name), "retryAfterMs" to JInt(d.retryAfterMs)))

    fun error(o: OfferView, e: LenderReply.Error): JObject =
        JObject(listOf("attemptId" to JString(o.attemptId), "code" to JString(e.code.name)))

    fun of(o: OfferView, d: LenderDecision, fileSha256: String = "a".repeat(64)): JObject = when (val r = d.reply) {
        is LenderReply.Accept -> accept(o, fileSha256, o.model)
        is LenderReply.Decline -> decline(o, r)
        is LenderReply.Error -> error(o, r)
    }
}
