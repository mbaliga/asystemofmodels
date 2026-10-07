package xyz.mdhv.asom.lab.proto.pairing

import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.StatusLookup

/** The typed refusals of the pairing ceremony. They travel as `ERROR {"code": ...}`; none carries text. */
enum class Refusal { PAIRING_WINDOW_CLOSED, PAIRING_PROOF_INVALID, PAIRING_REFUSED, PROTOCOL_ERROR }

enum class AbortReason { DECLINED_LOCAL, DECLINED_REMOTE, TIMEOUT, CONNECTION_LOST, CANCELLED, PROTOCOL, WRITE_FAILED, REVOKED_PEER, PEER_EXISTS, REGISTRY_UNREADABLE, WINDOW_EXPIRED, TRIES_EXHAUSTED, TRANSCRIPT_MISMATCH }

/** What the host must do. The state machines perform no I/O: effects are returned, in order. */
sealed interface PairEffect {
    data object WindowOpened : PairEffect
    data class Refuse(val code: Refusal) : PairEffect
    data object SendChallenge : PairEffect
    data class SendHello(val nonceS: ByteArray, val proof: ByteArray) : PairEffect
    data class Dial(val endpoint: QrEndpoint) : PairEffect
    data class SendDecision(val approve: Boolean) : PairEffect
    data class ShowConsent(val sas: String) : PairEffect
    data class ShowConnectConfirm(val name: String) : PairEffect
    data class ScanRejected(val code: QrReject) : PairEffect

    /** Durably write the PAIRED row. The commit or the acknowledgement is sent only after the host reports the row durable. */
    data object WritePairedRow : PairEffect
    data class SendCommit(val transcript: ByteArray) : PairEffect
    data class SendAck(val transcript: ByteArray) : PairEffect
    data class Warn(val kind: String) : PairEffect
    data class MarkUnconfirmed(val reason: String) : PairEffect

    /** The ceremony is over, no row was written by it. [refusal] is the error to send if the connection is still alive. */
    data class Abort(val reason: AbortReason, val refusal: Refusal?) : PairEffect
    data class Closed(val reason: String) : PairEffect
    data object Done : PairEffect
}

val PairEffect.kind: String get() = this::class.simpleName ?: "?"

private fun hex8(b: ByteArray) = xyz.mdhv.asom.lab.json.Hex.encode(b.copyOf(4))

/** One stable line per effect for the vectors: the name, and the few arguments that tie it to the proof, SAS or transcript functions. */
fun PairEffect.describe(): String = when (this) {
    PairEffect.WindowOpened -> "WindowOpened"
    is PairEffect.Refuse -> "Refuse($code)"
    PairEffect.SendChallenge -> "SendChallenge"
    is PairEffect.SendHello -> "SendHello(${hex8(proof)})"
    is PairEffect.Dial -> "Dial($endpoint)"
    is PairEffect.SendDecision -> "SendDecision($approve)"
    is PairEffect.ShowConsent -> "ShowConsent($sas)"
    is PairEffect.ShowConnectConfirm -> "ShowConnectConfirm($name)"
    is PairEffect.ScanRejected -> "ScanRejected($code)"
    PairEffect.WritePairedRow -> "WritePairedRow"
    is PairEffect.SendCommit -> "SendCommit(${hex8(transcript)})"
    is PairEffect.SendAck -> "SendAck(${hex8(transcript)})"
    is PairEffect.Warn -> "Warn($kind)"
    is PairEffect.MarkUnconfirmed -> "MarkUnconfirmed($reason)"
    is PairEffect.Abort -> "Abort($reason,${refusal ?: "-"})"
    is PairEffect.Closed -> "Closed($reason)"
    PairEffect.Done -> "Done"
}

class PairFsmConfig(
    val ownPin: Pin,
    val windowMs: Long = 120_000,
    val maxTries: Int = 3,
    val decisionTimeoutMs: Long = 120_000,
    val ackTimeoutMs: Long = 10_000,
)

// ------------------------------------------------------------------------------------------------------------------------------ D (displayer)

sealed class DState(val kind: String) {
    data object Closed : DState("CLOSED")
    class Open(val secret: ByteArray, val expiresAtMs: Long, val tries: Int) : DState("OPEN")
    class Consumed(val pinS: Pin, val nonceS: ByteArray, val nonceD: ByteArray, val sinceMs: Long) : DState("CONSUMED")
    class AwaitDecisions(val pinS: Pin, val nonceS: ByteArray, val nonceD: ByteArray, val deadlineMs: Long, val local: Boolean, val remote: Boolean) :
        DState(if (local) "AWAIT_LOCAL" else if (remote) "AWAIT_REMOTE" else "AWAIT_NONE")
    class Committing(val pinS: Pin, val nonceS: ByteArray, val nonceD: ByteArray) : DState("COMMITTING")
    class AwaitAck(val expectedTranscript: ByteArray, val deadlineMs: Long) : DState("AWAIT_ACK")
}

sealed class DEvent(val kind: String) {
    class UserOpenWindow(val secret: ByteArray, val nowMs: Long) : DEvent("UserOpenWindow")

    /** `pinS` is the pin of the chain TLS presented. [freshNonceD] is a new 32-byte CSPRNG draw, used only if the hello is accepted. */
    class HelloReceived(val pinS: Pin, val nonceS: ByteArray, val proof: ByteArray, val pinSStatus: StatusLookup, val freshNonceD: ByteArray, val nowMs: Long) : DEvent("HelloReceived")
    data object ChallengeSent : DEvent("ChallengeSent")
    class LocalDecision(val approve: Boolean) : DEvent("LocalDecision")
    class RemoteDecision(val approve: Boolean) : DEvent("RemoteDecision")
    class Tick(val nowMs: Long) : DEvent("Tick")
    data object UserCancel : DEvent("UserCancel")
    data object ConnectionLost : DEvent("ConnectionLost")
    class RowDurable(val nowMs: Long) : DEvent("RowDurable")
    data object RowWriteFailed : DEvent("RowWriteFailed")
    class AckReceived(val transcript: ByteArray) : DEvent("AckReceived")
    data object SecondUnknownConnection : DEvent("SecondUnknownConnection")
}

class DStep(val state: DState, val effects: List<PairEffect>) {
    val signature: String get() = state.kind + ":" + effects.joinToString(",") { it.describe() }
}

/**
 * The pairing window and ceremony on D (trust.md 4.6). A pure function of (state, event): no clock, no randomness, no I/O. The registry
 * row is written by the host on [PairEffect.WritePairedRow], and only a local approval AND the peer's approval reach it (law L1).
 */
class DFsm(private val cfg: PairFsmConfig) {
    private fun same(s: DState, vararg e: PairEffect) = DStep(s, e.toList())

    private fun abort(reason: AbortReason, refusal: Refusal?, vararg before: PairEffect) = DStep(DState.Closed, before.toList() + PairEffect.Abort(reason, refusal))

    fun step(s: DState, e: DEvent): DStep = when (s) {
        DState.Closed -> closed(e)
        is DState.Open -> open(s, e)
        is DState.Consumed -> consumed(s, e)
        is DState.AwaitDecisions -> awaiting(s, e)
        is DState.Committing -> committing(s, e)
        is DState.AwaitAck -> awaitAck(s, e)
    }

    private fun closed(e: DEvent): DStep = when (e) {
        is DEvent.UserOpenWindow -> DStep(DState.Open(e.secret.copyOf(), e.nowMs + cfg.windowMs, 0), listOf(PairEffect.WindowOpened))
        is DEvent.HelloReceived -> same(DState.Closed, PairEffect.Refuse(Refusal.PAIRING_WINDOW_CLOSED))
        else -> same(DState.Closed)
    }

    private fun open(s: DState.Open, e: DEvent): DStep = when (e) {
        is DEvent.UserOpenWindow -> same(s)
        is DEvent.HelloReceived -> hello(s, e)
        is DEvent.Tick -> if (e.nowMs >= s.expiresAtMs) DStep(DState.Closed, listOf(PairEffect.Closed("window-expired"))) else same(s)
        DEvent.UserCancel -> DStep(DState.Closed, listOf(PairEffect.Closed("window-cancelled")))
        is DEvent.RemoteDecision, is DEvent.AckReceived -> same(s, PairEffect.Refuse(Refusal.PROTOCOL_ERROR))
        DEvent.ChallengeSent, is DEvent.LocalDecision, DEvent.ConnectionLost, is DEvent.RowDurable, DEvent.RowWriteFailed, DEvent.SecondUnknownConnection -> same(s)
    }

    private fun hello(s: DState.Open, e: DEvent.HelloReceived): DStep {
        if (e.nowMs >= s.expiresAtMs) return DStep(DState.Closed, listOf(PairEffect.Refuse(Refusal.PAIRING_WINDOW_CLOSED), PairEffect.Closed("window-expired")))
        when (val st = e.pinSStatus) {
            is StatusLookup.Known -> if (st.status == PeerStatus.REVOKED) {
                return abort(AbortReason.REVOKED_PEER, Refusal.PAIRING_REFUSED, PairEffect.Warn("revoked-device-tried-to-pair"))
            } else return abort(AbortReason.PEER_EXISTS, Refusal.PAIRING_REFUSED)
            is StatusLookup.Corrupt, StatusLookup.Unreadable -> return abort(AbortReason.REGISTRY_UNREADABLE, Refusal.PAIRING_REFUSED)
            StatusLookup.Absent -> Unit
        }
        if (!PairCrypto.proofValid(s.secret, cfg.ownPin, e.pinS, e.nonceS, e.proof)) {
            val tries = s.tries + 1
            return if (tries >= cfg.maxTries) abort(AbortReason.TRIES_EXHAUSTED, Refusal.PAIRING_PROOF_INVALID)
            else DStep(DState.Open(s.secret, s.expiresAtMs, tries), listOf(PairEffect.Refuse(Refusal.PAIRING_PROOF_INVALID)))
        }
        return DStep(DState.Consumed(e.pinS, e.nonceS.copyOf(), e.freshNonceD.copyOf(), e.nowMs), listOf(PairEffect.SendChallenge))
    }

    private fun consumed(s: DState.Consumed, e: DEvent): DStep = when (e) {
        DEvent.ChallengeSent -> DStep(
            DState.AwaitDecisions(s.pinS, s.nonceS, s.nonceD, s.sinceMs + cfg.decisionTimeoutMs, local = false, remote = false),
            listOf(PairEffect.ShowConsent(PairCrypto.sas(cfg.ownPin, s.pinS, s.nonceS, s.nonceD))),
        )
        is DEvent.HelloReceived -> same(s, PairEffect.Refuse(Refusal.PAIRING_WINDOW_CLOSED))
        is DEvent.RemoteDecision, is DEvent.AckReceived -> abort(AbortReason.PROTOCOL, Refusal.PROTOCOL_ERROR)
        is DEvent.Tick -> if (e.nowMs >= s.sinceMs + cfg.decisionTimeoutMs) abort(AbortReason.TIMEOUT, Refusal.PAIRING_REFUSED) else same(s)
        DEvent.UserCancel -> abort(AbortReason.CANCELLED, Refusal.PAIRING_REFUSED)
        DEvent.ConnectionLost -> abort(AbortReason.CONNECTION_LOST, null)
        DEvent.SecondUnknownConnection -> same(s, PairEffect.Warn("second-connection"))
        is DEvent.UserOpenWindow, is DEvent.LocalDecision, is DEvent.RowDurable, DEvent.RowWriteFailed -> same(s)
    }

    private fun awaiting(s: DState.AwaitDecisions, e: DEvent): DStep = when (e) {
        is DEvent.LocalDecision ->
            if (!e.approve) abort(AbortReason.DECLINED_LOCAL, Refusal.PAIRING_REFUSED, PairEffect.SendDecision(false))
            else if (s.local) same(s)
            else if (s.remote) DStep(DState.Committing(s.pinS, s.nonceS, s.nonceD), listOf(PairEffect.SendDecision(true), PairEffect.WritePairedRow))
            else DStep(DState.AwaitDecisions(s.pinS, s.nonceS, s.nonceD, s.deadlineMs, local = true, remote = false), listOf(PairEffect.SendDecision(true)))
        is DEvent.RemoteDecision ->
            if (!e.approve) abort(AbortReason.DECLINED_REMOTE, Refusal.PAIRING_REFUSED)
            else if (s.remote) same(s)
            else if (s.local) DStep(DState.Committing(s.pinS, s.nonceS, s.nonceD), listOf(PairEffect.WritePairedRow))
            else DStep(DState.AwaitDecisions(s.pinS, s.nonceS, s.nonceD, s.deadlineMs, local = false, remote = true), emptyList())
        is DEvent.HelloReceived -> same(s, PairEffect.Refuse(Refusal.PAIRING_WINDOW_CLOSED))
        is DEvent.Tick -> if (e.nowMs >= s.deadlineMs) abort(AbortReason.TIMEOUT, Refusal.PAIRING_REFUSED) else same(s)
        DEvent.UserCancel -> abort(AbortReason.DECLINED_LOCAL, Refusal.PAIRING_REFUSED, PairEffect.SendDecision(false))
        DEvent.ConnectionLost -> abort(AbortReason.CONNECTION_LOST, null)
        is DEvent.AckReceived -> abort(AbortReason.PROTOCOL, Refusal.PROTOCOL_ERROR)
        DEvent.SecondUnknownConnection -> same(s, PairEffect.Warn("second-connection"))
        is DEvent.UserOpenWindow, DEvent.ChallengeSent, is DEvent.RowDurable, DEvent.RowWriteFailed -> same(s)
    }

    private fun committing(s: DState.Committing, e: DEvent): DStep = when (e) {
        is DEvent.RowDurable -> {
            val t = PairCrypto.transcript(cfg.ownPin, s.pinS, s.nonceS, s.nonceD)
            DStep(DState.AwaitAck(t, e.nowMs + cfg.ackTimeoutMs), listOf(PairEffect.SendCommit(t)))
        }
        DEvent.RowWriteFailed -> abort(AbortReason.WRITE_FAILED, Refusal.PAIRING_REFUSED)
        is DEvent.HelloReceived -> same(s, PairEffect.Refuse(Refusal.PAIRING_WINDOW_CLOSED))
        DEvent.ConnectionLost -> abort(AbortReason.CONNECTION_LOST, null, PairEffect.MarkUnconfirmed("connection-lost-while-committing"))
        is DEvent.AckReceived -> abort(AbortReason.PROTOCOL, Refusal.PROTOCOL_ERROR, PairEffect.MarkUnconfirmed("acknowledgement-before-commit"))
        DEvent.SecondUnknownConnection -> same(s, PairEffect.Warn("second-connection"))
        is DEvent.UserOpenWindow, DEvent.ChallengeSent, is DEvent.LocalDecision, is DEvent.RemoteDecision, is DEvent.Tick, DEvent.UserCancel -> same(s)
    }

    private fun awaitAck(s: DState.AwaitAck, e: DEvent): DStep = when (e) {
        is DEvent.AckReceived ->
            if (PairCrypto.transcriptMatches(s.expectedTranscript, e.transcript)) DStep(DState.Closed, listOf(PairEffect.Done, PairEffect.Closed("done")))
            else DStep(DState.Closed, listOf(PairEffect.MarkUnconfirmed("transcript-mismatch"), PairEffect.Warn("transcript-mismatch"), PairEffect.Closed("transcript-mismatch")))
        is DEvent.Tick ->
            if (e.nowMs >= s.deadlineMs) DStep(DState.Closed, listOf(PairEffect.MarkUnconfirmed("ack-timeout"), PairEffect.Warn("may-not-have-finished"), PairEffect.Closed("ack-timeout"))) else same(s)
        DEvent.ConnectionLost -> DStep(DState.Closed, listOf(PairEffect.MarkUnconfirmed("connection-lost"), PairEffect.Warn("may-not-have-finished"), PairEffect.Closed("connection-lost")))
        is DEvent.HelloReceived -> same(s, PairEffect.Refuse(Refusal.PAIRING_WINDOW_CLOSED))
        DEvent.SecondUnknownConnection -> same(s, PairEffect.Warn("second-connection"))
        is DEvent.UserOpenWindow, DEvent.ChallengeSent, is DEvent.LocalDecision, is DEvent.RemoteDecision, DEvent.UserCancel, is DEvent.RowDurable, DEvent.RowWriteFailed -> same(s)
    }
}

// ------------------------------------------------------------------------------------------------------------------------------ S (scanner)

sealed class SState(val kind: String) {
    data object Idle : SState("IDLE")
    class ConfirmConnect(val payload: QrPayload) : SState("CONFIRM_CONNECT")
    class Dialing(val payload: QrPayload, val index: Int) : SState("DIALING")
    class SentHello(val payload: QrPayload, val nonceS: ByteArray) : SState("SENT_HELLO")
    class AwaitDecisions(val payload: QrPayload, val nonceS: ByteArray, val nonceD: ByteArray, val deadlineMs: Long, val local: Boolean, val remote: Boolean) :
        SState(if (local) "AWAIT_LOCAL" else if (remote) "AWAIT_REMOTE" else "AWAIT_NONE")
    class AwaitCommit(val payload: QrPayload, val nonceS: ByteArray, val nonceD: ByteArray, val deadlineMs: Long) : SState("AWAIT_COMMIT")
    class Writing(val transcript: ByteArray) : SState("WRITING")
    data object Done : SState("DONE")
    class Failed(val reason: AbortReason, val code: String?) : SState("FAILED")
}

sealed class SEvent(val kind: String) {
    class Scanned(val parse: QrParse) : SEvent("Scanned")
    class UserConfirmConnect(val yes: Boolean, val nowMs: Long) : SEvent("UserConfirmConnect")

    /** The dial finished: [presented] is the pin of the chain TLS presented, or null when the connection failed. [freshNonceS] is a new CSPRNG draw. */
    class DialResult(val presented: Pin?, val freshNonceS: ByteArray, val nowMs: Long) : SEvent("DialResult")
    class ChallengeReceived(val nonceD: ByteArray, val nowMs: Long) : SEvent("ChallengeReceived")
    class ErrorReceived(val code: String) : SEvent("ErrorReceived")
    class LocalDecision(val approve: Boolean) : SEvent("LocalDecision")
    class RemoteDecision(val approve: Boolean) : SEvent("RemoteDecision")
    class CommitReceived(val transcript: ByteArray) : SEvent("CommitReceived")
    data object RowDurable : SEvent("RowDurable")
    data object RowWriteFailed : SEvent("RowWriteFailed")
    class Tick(val nowMs: Long) : SEvent("Tick")
    data object UserCancel : SEvent("UserCancel")
    data object ConnectionLost : SEvent("ConnectionLost")
}

class SStep(val state: SState, val effects: List<PairEffect>) {
    val signature: String get() = state.kind + ":" + effects.joinToString(",") { it.describe() }
}

/**
 * Pairing on S (trust.md 4.6). S dials outbound only. A pairing writes a row only after both people approved AND `PAIR_COMMIT` carried
 * the transcript S computed itself from the pins TLS presented and the two nonces (the transcript is never taken from a message).
 */
class SFsm(private val cfg: PairFsmConfig) {
    private fun same(s: SState, vararg e: PairEffect) = SStep(s, e.toList())

    private fun fail(reason: AbortReason, code: String?, vararg before: PairEffect) = SStep(SState.Failed(reason, code), before.toList() + PairEffect.Abort(reason, null))

    fun step(s: SState, e: SEvent): SStep = when (s) {
        SState.Idle -> idle(e)
        is SState.ConfirmConnect -> confirm(s, e)
        is SState.Dialing -> dialing(s, e)
        is SState.SentHello -> sentHello(s, e)
        is SState.AwaitDecisions -> awaiting(s, e)
        is SState.AwaitCommit -> awaitCommit(s, e)
        is SState.Writing -> writing(s, e)
        SState.Done, is SState.Failed -> same(s)
    }

    private fun idle(e: SEvent): SStep = when (e) {
        is SEvent.Scanned -> when (val p = e.parse) {
            is QrParse.Ok -> SStep(SState.ConfirmConnect(p.payload), listOf(PairEffect.ShowConnectConfirm(p.payload.name)))
            is QrParse.Reject -> same(SState.Idle, PairEffect.ScanRejected(p.code))
        }
        else -> same(SState.Idle)
    }

    private fun confirm(s: SState.ConfirmConnect, e: SEvent): SStep = when (e) {
        is SEvent.UserConfirmConnect -> if (e.yes) SStep(SState.Dialing(s.payload, 0), listOf(PairEffect.Dial(s.payload.endpoints[0]))) else same(SState.Idle)
        SEvent.UserCancel -> same(SState.Idle)
        else -> same(s)
    }

    private fun dialing(s: SState.Dialing, e: SEvent): SStep = when (e) {
        is SEvent.DialResult -> {
            val ok = e.presented != null && e.presented.equalsConstantTime(s.payload.pinD)
            if (ok) {
                val proof = PairCrypto.proof(s.payload.secret, s.payload.pinD, cfg.ownPin, e.freshNonceS)
                SStep(SState.SentHello(s.payload, e.freshNonceS.copyOf()), listOf(PairEffect.SendHello(e.freshNonceS.copyOf(), proof)))
            } else if (s.index + 1 < s.payload.endpoints.size) {
                SStep(SState.Dialing(s.payload, s.index + 1), listOf(PairEffect.Dial(s.payload.endpoints[s.index + 1])))
            } else fail(AbortReason.CONNECTION_LOST, null)
        }
        SEvent.UserCancel -> fail(AbortReason.CANCELLED, null)
        else -> same(s)
    }

    private fun sentHello(s: SState.SentHello, e: SEvent): SStep = when (e) {
        is SEvent.ChallengeReceived -> SStep(
            SState.AwaitDecisions(s.payload, s.nonceS, e.nonceD.copyOf(), e.nowMs + cfg.decisionTimeoutMs, local = false, remote = false),
            listOf(PairEffect.ShowConsent(PairCrypto.sas(s.payload.pinD, cfg.ownPin, s.nonceS, e.nonceD))),
        )
        is SEvent.ErrorReceived -> SStep(SState.Failed(AbortReason.DECLINED_REMOTE, e.code), listOf(PairEffect.Abort(AbortReason.DECLINED_REMOTE, null)))
        SEvent.ConnectionLost -> fail(AbortReason.CONNECTION_LOST, null)
        SEvent.UserCancel -> fail(AbortReason.CANCELLED, null)
        is SEvent.RemoteDecision, is SEvent.CommitReceived -> fail(AbortReason.PROTOCOL, "PROTOCOL_ERROR")
        else -> same(s)
    }

    private fun awaiting(s: SState.AwaitDecisions, e: SEvent): SStep = when (e) {
        is SEvent.LocalDecision ->
            if (!e.approve) fail(AbortReason.DECLINED_LOCAL, null, PairEffect.SendDecision(false))
            else if (s.local) same(s)
            else {
                val next = if (s.remote) SState.AwaitCommit(s.payload, s.nonceS, s.nonceD, s.deadlineMs)
                else SState.AwaitDecisions(s.payload, s.nonceS, s.nonceD, s.deadlineMs, local = true, remote = false)
                SStep(next, listOf(PairEffect.SendDecision(true)))
            }
        is SEvent.RemoteDecision ->
            if (!e.approve) fail(AbortReason.DECLINED_REMOTE, "PAIRING_REFUSED")
            else if (s.remote) same(s)
            else {
                val next = if (s.local) SState.AwaitCommit(s.payload, s.nonceS, s.nonceD, s.deadlineMs)
                else SState.AwaitDecisions(s.payload, s.nonceS, s.nonceD, s.deadlineMs, local = false, remote = true)
                SStep(next, emptyList())
            }
        is SEvent.CommitReceived -> fail(AbortReason.PROTOCOL, "PROTOCOL_ERROR")
        is SEvent.ErrorReceived -> SStep(SState.Failed(AbortReason.DECLINED_REMOTE, e.code), listOf(PairEffect.Abort(AbortReason.DECLINED_REMOTE, null)))
        is SEvent.Tick -> if (e.nowMs >= s.deadlineMs) fail(AbortReason.TIMEOUT, null) else same(s)
        SEvent.UserCancel -> fail(AbortReason.DECLINED_LOCAL, null, PairEffect.SendDecision(false))
        SEvent.ConnectionLost -> fail(AbortReason.CONNECTION_LOST, null)
        else -> same(s)
    }

    private fun awaitCommit(s: SState.AwaitCommit, e: SEvent): SStep = when (e) {
        is SEvent.CommitReceived -> {
            val mine = PairCrypto.transcript(s.payload.pinD, cfg.ownPin, s.nonceS, s.nonceD)
            if (PairCrypto.transcriptMatches(mine, e.transcript)) SStep(SState.Writing(mine), listOf(PairEffect.WritePairedRow))
            else fail(AbortReason.TRANSCRIPT_MISMATCH, null)
        }
        is SEvent.ErrorReceived -> SStep(SState.Failed(AbortReason.DECLINED_REMOTE, e.code), listOf(PairEffect.Abort(AbortReason.DECLINED_REMOTE, null)))
        is SEvent.Tick -> if (e.nowMs >= s.deadlineMs) fail(AbortReason.TIMEOUT, null) else same(s)
        SEvent.UserCancel -> fail(AbortReason.DECLINED_LOCAL, null, PairEffect.SendDecision(false))
        SEvent.ConnectionLost -> fail(AbortReason.CONNECTION_LOST, null)
        is SEvent.RemoteDecision -> if (e.approve) same(s) else fail(AbortReason.DECLINED_REMOTE, "PAIRING_REFUSED")
        is SEvent.LocalDecision -> if (e.approve) same(s) else fail(AbortReason.DECLINED_LOCAL, null, PairEffect.SendDecision(false))
        else -> same(s)
    }

    private fun writing(s: SState.Writing, e: SEvent): SStep = when (e) {
        SEvent.RowDurable -> SStep(SState.Done, listOf(PairEffect.SendAck(s.transcript), PairEffect.Done))
        SEvent.RowWriteFailed -> fail(AbortReason.WRITE_FAILED, null)
        else -> same(s)
    }
}
