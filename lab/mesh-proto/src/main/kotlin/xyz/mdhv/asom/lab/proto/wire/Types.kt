package xyz.mdhv.asom.lab.proto.wire

/** The mesh error codes (LAB_SPEC 7.2). [UNKNOWN] exists only so a received code outside the closed set can be stored; it is never emitted. */
enum class MeshError {
    PEER_NOT_PAIRED,
    SCOPE_DENIED,
    PROTOCOL_ERROR,
    VERSION_UNSUPPORTED,
    FRAME_TOO_LARGE,
    DUPLICATE_ATTEMPT,
    CLOCK_SKEW,
    MODEL_NOT_OFFERED,
    PEER_BUSY,
    PEER_UNAVAILABLE,
    MANIFEST_UNAVAILABLE,
    PAIRING_WINDOW_CLOSED,
    PAIRING_PROOF_INVALID,
    PAIRING_REFUSED,
    UNKNOWN,
    ;

    /** An unknown code is handled as `PROTOCOL_ERROR` (LAB_SPEC 7.2). */
    val handledAs: MeshError get() = if (this == UNKNOWN) PROTOCOL_ERROR else this

    companion object {
        fun fromWire(code: String): MeshError = entries.firstOrNull { it != UNKNOWN && it.name == code } ?: UNKNOWN
    }
}

/** The TLS roles: the client dials, the server listens. A decoder is told the role of the node that RECEIVES the bytes. */
enum class PeerRole { TLS_CLIENT, TLS_SERVER }

/** A pairing-mode connection accepts only `PAIR_*` frames (and `ERROR`, which carries a refusal); any other type is a protocol error. */
enum class ConnMode { ESTABLISHED, PAIRING }

enum class Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT, BOTH }

enum class StreamRule { ZERO, ODD, ANY, PAIRING_ONE }

enum class PayloadClass { JSON, RAW_BODY, RAW_CHUNK }

class FrameSpec(val type: Int, val name: String, val direction: Direction, val stream: StreamRule, val payload: PayloadClass)

object WireLimits {
    const val HEADER_BYTES: Int = 9
    const val MIN_LENGTH: Long = 5L
    const val MAX_LENGTH: Long = 16_777_221L
    const val MAX_PAYLOAD: Long = MAX_LENGTH - MIN_LENGTH
    const val JSON_PAYLOAD_MAX: Long = 1_048_576L
    const val INFER_BODY_MAX: Long = 8_388_608L
    const val MAX_STREAM: Long = 0xFFFF_FFFFL

    fun payloadLimit(spec: FrameSpec): Long = when (spec.payload) {
        PayloadClass.JSON -> JSON_PAYLOAD_MAX
        PayloadClass.RAW_BODY -> INFER_BODY_MAX
        PayloadClass.RAW_CHUNK -> MAX_PAYLOAD
    }
}

object FrameTypes {
    const val HELLO = 0x01
    const val HELLO_ACK = 0x02
    const val GOAWAY = 0x05
    const val ERROR = 0x06
    const val INFER_OFFER = 0x10
    const val INFER_ACCEPT = 0x11
    const val INFER_DECLINE = 0x12
    const val INFER_BODY = 0x13
    const val INFER_HEAD = 0x14
    const val INFER_CHUNK = 0x15
    const val INFER_END = 0x16
    const val CANCEL = 0x17
    const val STATE_REQ = 0x20
    const val STATE = 0x21
    const val MANIFEST_REQ = 0x22
    const val MANIFEST = 0x23
    const val PAIR_HELLO = 0x30
    const val PAIR_CHALLENGE = 0x31
    const val PAIR_DECISION = 0x32
    const val PAIR_COMMIT = 0x33
    const val PAIR_COMMIT_ACK = 0x34
    const val REVOKE_NOTICE = 0x40

    const val EXTENSION_FIRST = 0x80

    private val c2s = Direction.CLIENT_TO_SERVER
    private val s2c = Direction.SERVER_TO_CLIENT
    private val json = PayloadClass.JSON

    val specs: Map<Int, FrameSpec> = listOf(
        FrameSpec(HELLO, "HELLO", c2s, StreamRule.ZERO, json),
        FrameSpec(HELLO_ACK, "HELLO_ACK", s2c, StreamRule.ZERO, json),
        FrameSpec(GOAWAY, "GOAWAY", Direction.BOTH, StreamRule.ZERO, json),
        FrameSpec(ERROR, "ERROR", Direction.BOTH, StreamRule.ANY, json),
        FrameSpec(INFER_OFFER, "INFER_OFFER", c2s, StreamRule.ODD, json),
        FrameSpec(INFER_ACCEPT, "INFER_ACCEPT", s2c, StreamRule.ODD, json),
        FrameSpec(INFER_DECLINE, "INFER_DECLINE", s2c, StreamRule.ODD, json),
        FrameSpec(INFER_BODY, "INFER_BODY", c2s, StreamRule.ODD, PayloadClass.RAW_BODY),
        FrameSpec(INFER_HEAD, "INFER_HEAD", s2c, StreamRule.ODD, json),
        FrameSpec(INFER_CHUNK, "INFER_CHUNK", s2c, StreamRule.ODD, PayloadClass.RAW_CHUNK),
        FrameSpec(INFER_END, "INFER_END", s2c, StreamRule.ODD, json),
        FrameSpec(CANCEL, "CANCEL", c2s, StreamRule.ODD, json),
        FrameSpec(STATE_REQ, "STATE_REQ", c2s, StreamRule.ODD, json),
        FrameSpec(STATE, "STATE", s2c, StreamRule.ODD, json),
        FrameSpec(MANIFEST_REQ, "MANIFEST_REQ", c2s, StreamRule.ODD, json),
        FrameSpec(MANIFEST, "MANIFEST", s2c, StreamRule.ODD, json),
        FrameSpec(PAIR_HELLO, "PAIR_HELLO", Direction.BOTH, StreamRule.PAIRING_ONE, json),
        FrameSpec(PAIR_CHALLENGE, "PAIR_CHALLENGE", Direction.BOTH, StreamRule.PAIRING_ONE, json),
        FrameSpec(PAIR_DECISION, "PAIR_DECISION", Direction.BOTH, StreamRule.PAIRING_ONE, json),
        FrameSpec(PAIR_COMMIT, "PAIR_COMMIT", Direction.BOTH, StreamRule.PAIRING_ONE, json),
        FrameSpec(PAIR_COMMIT_ACK, "PAIR_COMMIT_ACK", Direction.BOTH, StreamRule.PAIRING_ONE, json),
        FrameSpec(REVOKE_NOTICE, "REVOKE_NOTICE", Direction.BOTH, StreamRule.ZERO, json),
    ).associateBy { it.type }

    fun isExtension(type: Int): Boolean = type in EXTENSION_FIRST..0xFF

    fun isPair(type: Int): Boolean = type in PAIR_HELLO..PAIR_COMMIT_ACK

    fun nameOf(type: Int): String = specs[type]?.name ?: "0x%02x".format(type)

    /** A type as vectors write it: a name (`HELLO`), a hex number (`0x80`) or a decimal number. */
    fun parse(text: String): Int =
        specs.values.firstOrNull { it.name == text }?.type
            ?: if (text.startsWith("0x")) text.substring(2).toInt(16) else text.toInt()
}

/** A refusal with a typed code and a stable reason string; the reason is a diagnostic and never carries peer-authored text. */
class WireRefusal(val error: MeshError, val reason: String) : RuntimeException("$error: $reason", null, false, false)

object Reason {
    const val LENGTH_BELOW_MIN = "LENGTH_BELOW_MIN"
    const val LENGTH_ABOVE_MAX = "LENGTH_ABOVE_MAX"
    const val UNKNOWN_TYPE = "UNKNOWN_TYPE"
    const val MODE_REJECTS_TYPE = "MODE_REJECTS_TYPE"
    const val WRONG_DIRECTION = "WRONG_DIRECTION"
    const val PAYLOAD_LIMIT = "PAYLOAD_LIMIT"
    const val STREAM_RULE = "STREAM_RULE"
    const val TRUNCATED = "TRUNCATED"
    const val NOT_AN_OBJECT = "NOT_AN_OBJECT"
    const val EXTENSION_NOT_SENT = "EXTENSION_NOT_SENT"
    const val UNKNOWN_NOT_EMITTABLE = "UNKNOWN_NOT_EMITTABLE"
    const val MISSING_MEMBER = "MISSING_MEMBER"
    const val WRONG_TYPE = "WRONG_TYPE"
    const val UNKNOWN_ENUM = "UNKNOWN_ENUM"
    const val OUT_OF_RANGE = "OUT_OF_RANGE"
    const val BAD_VERSION = "BAD_VERSION"
    const val NODE_ID_MISMATCH = "NODE_ID_MISMATCH"
    const val NO_COMMON_VERSION = "NO_COMMON_VERSION"
    const val PAIR_HOOK_REFUSED = "PAIR_HOOK_REFUSED"
}
