package xyz.mdhv.asom.lab.proto.session

import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.manifest.RejectCode
import xyz.mdhv.asom.lab.manifest.Verified
import xyz.mdhv.asom.lab.policy.StateDoc
import xyz.mdhv.asom.lab.proto.wire.InferAccept
import xyz.mdhv.asom.lab.proto.wire.InferDecline
import xyz.mdhv.asom.lab.proto.wire.InferHead
import xyz.mdhv.asom.lab.proto.wire.InferOp
import xyz.mdhv.asom.lab.proto.wire.MeshError
import xyz.mdhv.asom.lab.proto.wire.Terminal

/** What the requester's router knows about one attempt (the `INFER_OFFER` carries metadata only; the rows carry the app's package and the request id, the frame never does). */
class OfferSpec(
    val callerPkg: String,
    val requestId: String?,
    val attemptIndex: Int,
    val model: String,
    val op: InferOp,
    val deadlineMs: Long,
    val estTokensIn: Long,
    val maxTokens: Long,
    val stream: Boolean,
)

/**
 * The end of an attempt. [row] is the durable outcome row, or null when it could not be made durable (the intent row then stays the truthful "outcome
 * unknown"). [status] and [meshCode] are what the row says; at most one of [terminal], [decline] and [error] explains it.
 */
class AttemptOutcome(
    val attemptId: String,
    val status: Int,
    val meshCode: String?,
    val terminal: Terminal?,
    val decline: InferDecline?,
    val error: MeshError?,
    val row: LabRouteRecord?,
)

interface AttemptListener {
    /** Return false to stop the attempt before the body leaves (a `CANCEL policy-changed` is sent instead). */
    fun onAccepted(accept: InferAccept): Boolean = true

    fun onHead(head: InferHead) {}

    fun onChunk(bytes: ByteArray) {}

    /** The attempt is over and its outcome row (if durable) has been appended. */
    fun onFinished(outcome: AttemptOutcome)
}

sealed interface StateResult {
    class Ok(val doc: StateDoc) : StateResult

    class Failed(val code: MeshError) : StateResult

    data object Lost : StateResult
}

sealed interface ManifestResult {
    /** The manifest verified against the pinned peer key and the challenge. */
    class Accepted(val verified: Verified) : ManifestResult

    /** The verifier's typed verdict; it is also the `meshCode` of the `MANIFEST_RECEIVED` row. */
    class Rejected(val code: RejectCode, val step: String) : ManifestResult

    class Failed(val code: MeshError) : ManifestResult

    data object Lost : ManifestResult
}
