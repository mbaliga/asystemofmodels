package xyz.mdhv.asom.lab.router

/** The requester's view of one peer attempt (router.md 8.1). */
enum class AttemptPhase { PLANNED, OFFERING, ACCEPTED, BODY_SENT, RECEIVING }

sealed interface AttemptEvent {
    data object DialFailed : AttemptEvent

    /** `code` is a decline code of `:mesh-policy` (`PEER_BUSY`, `PEER_UNAVAILABLE`, `MODEL_NOT_OFFERED`, `SCOPE_DENIED`, `DUPLICATE_ATTEMPT`). */
    data class Decline(val code: String, val retryAfterMs: Long) : AttemptEvent

    /** A connection-level `ERROR` such as `PEER_NOT_PAIRED`. */
    data class ErrorFrame(val code: String) : AttemptEvent

    data object OfferTimeout : AttemptEvent

    /** `INFER_ACCEPT` arrived and the re-evaluation is worse than the runner-up by more than `max(1,000 ms, 10%)`. */
    data object AcceptedWorse : AttemptEvent

    data object HeadTimeout : AttemptEvent

    /** `GOAWAY`, a reset or a lost connection. */
    data object ConnectionLost : AttemptEvent

    /** `INFER_END` with its `terminal` (`done`, `cancelled`, `interrupted`, `oom`, `error`). */
    data class Terminal(val terminal: String) : AttemptEvent

    data object ClientDisconnect : AttemptEvent

    data object DeadlinePassed : AttemptEvent

    data object SelfGovernorHold : AttemptEvent
}

data class AttemptContext(
    val stream: Boolean,
    /** For a stream: `INFER_HEAD` arrived, so the echo headers are committed and a switch of node would put two models under one header (router.md 8.2). */
    val headersCommitted: Boolean,
)

enum class Action { NEXT, RETURN, FAIL_IN_BAND, STOP, CLIENT_CANCELLED, V2_LOCAL }

enum class BreakerEffect {
    NONE,

    /** Unreachable, timeout or lost connection: the peer transport curve (30 s cap, then a half-open offer-only probe). */
    TRANSPORT_FAILURE,

    /** Any other failure: the v1 curve (30 s doubling to 15 min). */
    FAILURE,

    EXCLUDE_UNTIL_SESSION_REESTABLISHED,
}

enum class TrackerEffect { NONE, FAILED_OBSERVATION, MEMORY_DISCREPANT }

data class Decision(
    val action: Action,
    val cancelFrame: Boolean,
    val rowStatus: String,
    val breaker: BreakerEffect,
    val backoffMs: Long?,
    val tracker: TrackerEffect,
    val sseReason: String?,
    val newAttemptId: Boolean,
    val refreshRegistry: Boolean,
    val contentLeft: Boolean,
)

/**
 * The retry-or-fail decision table (router.md 8.2) with the r3 codes: `PEER_UNAVAILABLE` replaces `PEER_THERMAL`, `PEER_BATTERY` and `PEER_USER_ACTIVE`, and
 * `terminal = interrupted` replaces `thermal`. A stream whose `INFER_HEAD` arrived is never retried (its headers are committed); every other failure before
 * a byte reaches the client may retry. A pure function of (phase, context, event).
 */
object Failover {
    const val INTERRUPTED_BACKOFF_MS = 60_000L

    private fun d(
        action: Action,
        status: String,
        breaker: BreakerEffect = BreakerEffect.NONE,
        backoff: Long? = null,
        tracker: TrackerEffect = TrackerEffect.NONE,
        cancel: Boolean = false,
        sse: String? = null,
        newId: Boolean = false,
        refresh: Boolean = false,
        content: Boolean = false,
    ) = Decision(action, cancel, status, breaker, backoff, tracker, sse, newId, refresh, content)

    /** Null when the event cannot happen in that phase. */
    fun decide(phase: AttemptPhase, ctx: AttemptContext, e: AttemptEvent): Decision? {
        val contentLeft = phase == AttemptPhase.BODY_SENT || phase == AttemptPhase.RECEIVING
        return when (e) {
            AttemptEvent.DeadlinePassed -> d(Action.STOP, "DEADLINE", content = contentLeft)
            AttemptEvent.ClientDisconnect -> if (phase == AttemptPhase.PLANNED) null else d(Action.CLIENT_CANCELLED, "CANCELLED", cancel = true, content = contentLeft)
            AttemptEvent.SelfGovernorHold -> d(Action.V2_LOCAL, "V2")
            AttemptEvent.DialFailed -> if (phase != AttemptPhase.PLANNED) null else d(Action.NEXT, "PEER_UNREACHABLE", BreakerEffect.TRANSPORT_FAILURE)
            is AttemptEvent.Decline -> if (phase != AttemptPhase.OFFERING) null else decline(e)
            is AttemptEvent.ErrorFrame -> if (phase != AttemptPhase.OFFERING) null else d(Action.NEXT, e.code, BreakerEffect.EXCLUDE_UNTIL_SESSION_REESTABLISHED, refresh = true)
            AttemptEvent.OfferTimeout -> if (phase != AttemptPhase.OFFERING) null else d(Action.NEXT, "OFFER_TIMEOUT", BreakerEffect.TRANSPORT_FAILURE, cancel = true)
            AttemptEvent.AcceptedWorse -> if (phase != AttemptPhase.ACCEPTED) null else d(Action.NEXT, "CANCELLED_BEFORE_BODY", cancel = true)
            AttemptEvent.HeadTimeout -> if (phase != AttemptPhase.BODY_SENT) null else d(Action.NEXT, "PEER_LOST_PRE_HEAD", BreakerEffect.TRANSPORT_FAILURE, cancel = true, content = true)
            AttemptEvent.ConnectionLost -> when (phase) {
                AttemptPhase.PLANNED -> null
                AttemptPhase.OFFERING, AttemptPhase.ACCEPTED -> d(Action.NEXT, "PEER_UNREACHABLE", BreakerEffect.TRANSPORT_FAILURE)
                AttemptPhase.BODY_SENT -> d(Action.NEXT, "PEER_LOST_PRE_HEAD", BreakerEffect.TRANSPORT_FAILURE, cancel = true, content = true)
                AttemptPhase.RECEIVING ->
                    if (ctx.stream && ctx.headersCommitted) d(Action.FAIL_IN_BAND, "PEER_LOST_MID_STREAM", BreakerEffect.TRANSPORT_FAILURE, sse = "peer-lost", content = true)
                    else d(Action.NEXT, "PEER_LOST", BreakerEffect.TRANSPORT_FAILURE, content = true)
            }
            is AttemptEvent.Terminal -> when {
                phase != AttemptPhase.BODY_SENT && phase != AttemptPhase.RECEIVING -> null
                e.terminal == "done" -> d(Action.RETURN, "ok", content = true)
                else -> terminal(e.terminal, ctx.stream && ctx.headersCommitted)
            }
        }
    }

    private fun decline(e: AttemptEvent.Decline): Decision {
        val backoff = DeclineBackoff.until(0, e.retryAfterMs)
        return when (e.code) {
            "MODEL_NOT_OFFERED" -> d(Action.NEXT, e.code, backoff = backoff, tracker = TrackerEffect.FAILED_OBSERVATION)
            "SCOPE_DENIED" -> d(Action.NEXT, e.code, BreakerEffect.EXCLUDE_UNTIL_SESSION_REESTABLISHED, refresh = true)
            "DUPLICATE_ATTEMPT" -> d(Action.NEXT, e.code, newId = true)
            else -> d(Action.NEXT, e.code, backoff = backoff)
        }
    }

    private fun terminal(t: String, committed: Boolean): Decision {
        val status = "PEER_${t.uppercase()}" + (if (committed) "_MID_STREAM" else "")
        val action = if (committed) Action.FAIL_IN_BAND else Action.NEXT
        val sse = if (committed) "peer-lost" else null
        return when (t) {
            "interrupted" -> d(action, status, backoff = INTERRUPTED_BACKOFF_MS, sse = sse, content = true)
            "oom" -> d(action, status, tracker = TrackerEffect.MEMORY_DISCREPANT, sse = sse, content = true)
            else -> d(action, status, BreakerEffect.FAILURE, sse = sse, content = true)
        }
    }

    /** Accept-time re-evaluation (router.md 8.4): worse than the next candidate by more than `max(1,000 ms, 10%)` of the runner-up's score. */
    fun acceptedIsWorse(acceptedScore: Long, runnerUpScore: Long): Boolean = acceptedScore > Sat.add(runnerUpScore, maxOf(1_000L, Sat.floorDiv(runnerUpScore, 10)))
}
