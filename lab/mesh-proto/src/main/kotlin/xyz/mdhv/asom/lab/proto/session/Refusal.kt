package xyz.mdhv.asom.lab.proto.session

import java.util.EnumMap
import xyz.mdhv.asom.lab.proto.wire.MeshError
import xyz.mdhv.asom.lab.proto.wire.Reason

/**
 * Every refusal the session layer can make, typed and counted. [error] is the `MeshError` that goes on the wire; [closes] says whether the session ends.
 * The set is closed: the hostile-node suite (W08) fails when one of them was never exercised.
 */
enum class Refusal(val error: MeshError, val closes: Boolean) {
    CODEC_FRAME_TOO_LARGE(MeshError.FRAME_TOO_LARGE, true),
    UNKNOWN_TYPE(MeshError.PROTOCOL_ERROR, true),
    BAD_PAYLOAD(MeshError.PROTOCOL_ERROR, true),
    STREAM_RULE(MeshError.PROTOCOL_ERROR, true),
    WRONG_DIRECTION(MeshError.PROTOCOL_ERROR, true),
    MODE_REJECTS_TYPE(MeshError.PROTOCOL_ERROR, true),
    TRUNCATED(MeshError.PROTOCOL_ERROR, true),
    FRAME_BEFORE_HELLO(MeshError.PROTOCOL_ERROR, true),
    HELLO_NODE_MISMATCH(MeshError.PROTOCOL_ERROR, true),
    HELLO_BAD_VERSION(MeshError.VERSION_UNSUPPORTED, true),
    CLOCK_SKEW(MeshError.CLOCK_SKEW, true),
    SECOND_HELLO(MeshError.PROTOCOL_ERROR, true),
    STREAM_REUSE(MeshError.PROTOCOL_ERROR, true),
    BODY_WITHOUT_OFFER(MeshError.PROTOCOL_ERROR, true),
    CANCEL_UNKNOWN_ATTEMPT(MeshError.PROTOCOL_ERROR, true),
    UNSOLICITED_REPLY(MeshError.PROTOCOL_ERROR, true),
    PEER_NOT_PAIRED(MeshError.PEER_NOT_PAIRED, true),
    SCOPE_DENIED(MeshError.SCOPE_DENIED, false),
    DUPLICATE_ATTEMPT(MeshError.DUPLICATE_ATTEMPT, false),
    MODEL_NOT_OFFERED(MeshError.MODEL_NOT_OFFERED, false),
    OFFER_TOO_LARGE(MeshError.FRAME_TOO_LARGE, false),
    PEER_UNAVAILABLE(MeshError.PEER_UNAVAILABLE, false),
    PEER_BUSY(MeshError.PEER_BUSY, false),
    MANIFEST_UNAVAILABLE(MeshError.MANIFEST_UNAVAILABLE, false),
    MANIFEST_REJECTED(MeshError.PROTOCOL_ERROR, false),
    ;

    companion object {
        /** The refusal for a failure the frame decoder reported (`Inbound.Failure.reason`). */
        fun ofDecoder(reason: String): Refusal = when (reason) {
            Reason.LENGTH_BELOW_MIN, Reason.LENGTH_ABOVE_MAX, Reason.PAYLOAD_LIMIT -> CODEC_FRAME_TOO_LARGE
            Reason.UNKNOWN_TYPE -> UNKNOWN_TYPE
            Reason.MODE_REJECTS_TYPE -> MODE_REJECTS_TYPE
            Reason.WRONG_DIRECTION -> WRONG_DIRECTION
            Reason.STREAM_RULE -> STREAM_RULE
            Reason.TRUNCATED -> TRUNCATED
            else -> BAD_PAYLOAD
        }
    }
}

/** Counts refusals per kind, per node. Thread-safe. */
class RefusalCounters {
    private val counts = EnumMap<Refusal, Int>(Refusal::class.java)

    @Synchronized
    fun count(r: Refusal) {
        counts[r] = (counts[r] ?: 0) + 1
    }

    @Synchronized
    fun of(r: Refusal): Int = counts[r] ?: 0

    @Synchronized
    fun snapshot(): Map<Refusal, Int> = counts.toMap()

    @Synchronized
    fun total(): Int = counts.values.sum()
}
