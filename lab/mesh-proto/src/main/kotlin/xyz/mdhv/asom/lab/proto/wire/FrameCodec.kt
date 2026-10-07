package xyz.mdhv.asom.lab.proto.wire

import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/** One frame without its length prefix: `type:u8 stream:u32be payload`. [appBytes] is what ledger rows count (LAB_SPEC 7.1). */
class RawFrame(val type: Int, val stream: Long, val payload: ByteArray) {
    init {
        require(type in 0..0xFF) { "type is one octet" }
        require(stream in 0..WireLimits.MAX_STREAM) { "stream is a u32" }
    }

    val appBytes: Long get() = WireLimits.HEADER_BYTES.toLong() + payload.size

    override fun equals(other: Any?): Boolean = other is RawFrame && type == other.type && stream == other.stream && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (type * 31 + stream.hashCode()) * 31 + payload.contentHashCode()

    override fun toString(): String = "RawFrame(${FrameTypes.nameOf(type)}, stream=$stream, ${payload.size} payload bytes)"
}

/** The producer side: a mesh-1 sender never emits an unknown type or an extension, and never a frame its own decoder would refuse. */
object FrameEncoder {
    fun encode(frame: RawFrame): ByteArray {
        val spec = specFor(frame.type)
        checkStream(spec, frame.stream)
        if (frame.payload.size.toLong() > WireLimits.payloadLimit(spec)) throw WireRefusal(MeshError.FRAME_TOO_LARGE, Reason.PAYLOAD_LIMIT)
        if (spec.payload == PayloadClass.JSON) {
            when (val r = StrictJson.parse(frame.payload)) {
                is ParseResult.Reject -> throw WireRefusal(MeshError.PROTOCOL_ERROR, r.code.name)
                is ParseResult.Ok -> if (r.value !is JObject) throw WireRefusal(MeshError.PROTOCOL_ERROR, Reason.NOT_AN_OBJECT)
            }
        }
        val length = WireLimits.MIN_LENGTH + frame.payload.size
        val out = ByteArray(4 + length.toInt())
        putU32(out, 0, length)
        out[4] = frame.type.toByte()
        putU32(out, 5, frame.stream)
        frame.payload.copyInto(out, WireLimits.HEADER_BYTES)
        return out
    }

    private fun specFor(type: Int): FrameSpec {
        if (FrameTypes.isExtension(type)) throw WireRefusal(MeshError.PROTOCOL_ERROR, Reason.EXTENSION_NOT_SENT)
        return FrameTypes.specs[type] ?: throw WireRefusal(MeshError.PROTOCOL_ERROR, Reason.UNKNOWN_TYPE)
    }

    private fun checkStream(spec: FrameSpec, stream: Long) {
        if (!streamAllowed(spec.stream, stream)) throw WireRefusal(MeshError.PROTOCOL_ERROR, Reason.STREAM_RULE)
    }

    private fun putU32(out: ByteArray, at: Int, v: Long) {
        out[at] = (v ushr 24).toByte()
        out[at + 1] = (v ushr 16).toByte()
        out[at + 2] = (v ushr 8).toByte()
        out[at + 3] = v.toByte()
    }
}

internal fun streamAllowed(rule: StreamRule, stream: Long): Boolean = when (rule) {
    StreamRule.ZERO -> stream == 0L
    StreamRule.ODD -> stream and 1L == 1L
    StreamRule.ANY -> true
    StreamRule.PAIRING_ONE -> stream == 1L
}

sealed interface Inbound {
    /** A complete, structurally valid frame. For a JSON-class type the payload is already known to be one strict JSON object. */
    class Frame(val frame: RawFrame) : Inbound {
        val appBytes: Long get() = frame.appBytes
    }

    /** A frame of type 0x80..0xFF, skipped without being stored. The session layer writes the CONTROL row `EXT_IGNORED` for it. */
    class ExtIgnored(val type: Int, val stream: Long, val appBytes: Long) : Inbound

    /** The first and only failure of a decoder: send `ERROR [error]` (when the connection can still carry it) and close. [consumed] is how many bytes of the failing frame had been read. */
    class Failure(val error: MeshError, val reason: String, val consumed: Long) : Inbound
}

/**
 * The incremental frame decoder (LAB_SPEC 7.1). Feed arbitrary chunks, down to one byte at a time: the events and the failure are the same for every
 * chunking. Every check runs as soon as the prefix that decides it has arrived, in this order: length bounds, unknown type, connection mode, direction,
 * payload limit (all after 5 bytes), the stream rule (after 9 bytes), then the payload's JSON profile once it is complete.
 *
 * Byte accounting is exact: `totalFed == appBytesDelivered + pendingBytes + discardedAfterClose`, where a delivered frame or skipped extension counts
 * `9 + |payload|`.
 */
class FrameDecoder(val receiver: PeerRole, val mode: ConnMode = ConnMode.ESTABLISHED) {
    private enum class Phase { HEADER, PAYLOAD, SKIP, CLOSED }

    private val head = ByteArray(WireLimits.HEADER_BYTES)
    private var headFill = 0
    private var lengthTypeDone = false
    private var phase = Phase.HEADER
    private var length = 0L
    private var type = 0
    private var stream = 0L
    private var spec: FrameSpec? = null
    private var payload = ByteArray(0)
    private var payloadFill = 0
    private var payloadLen = 0L
    private var skipped = 0L
    private var consumed = 0L

    var totalFed: Long = 0
        private set
    var appBytesDelivered: Long = 0
        private set
    var discardedAfterClose: Long = 0
        private set

    val closed: Boolean get() = phase == Phase.CLOSED

    /** Bytes of the frame in progress that have been read (for a failed decoder: of the frame that failed). */
    val pendingBytes: Long get() = consumed

    fun feed(chunk: ByteArray, off: Int = 0, len: Int = chunk.size - off): List<Inbound> {
        require(off >= 0 && len >= 0 && off + len <= chunk.size)
        totalFed += len
        if (phase == Phase.CLOSED) {
            discardedAfterClose += len
            return emptyList()
        }
        val out = ArrayList<Inbound>()
        var i = off
        val end = off + len
        while (i < end) {
            when (phase) {
                Phase.HEADER -> {
                    val target = if (lengthTypeDone) WireLimits.HEADER_BYTES else 5
                    val take = minOf(end - i, target - headFill)
                    chunk.copyInto(head, headFill, i, i + take)
                    headFill += take
                    consumed += take
                    i += take
                    if (headFill == target) {
                        val failure = if (!lengthTypeDone) evaluateLengthAndType() else evaluateStream(out)
                        if (failure != null) return close(out, failure, end - i)
                    }
                }
                Phase.PAYLOAD -> {
                    val take = minOf((end - i).toLong(), payloadLen - payloadFill).toInt()
                    grow(payloadFill + take)
                    chunk.copyInto(payload, payloadFill, i, i + take)
                    payloadFill += take
                    consumed += take
                    i += take
                    if (payloadFill.toLong() == payloadLen) {
                        val failure = completePayload(out)
                        if (failure != null) return close(out, failure, end - i)
                    }
                }
                Phase.SKIP -> {
                    val take = minOf((end - i).toLong(), payloadLen - skipped).toInt()
                    skipped += take
                    consumed += take
                    i += take
                    if (skipped == payloadLen) completeExtension(out)
                }
                Phase.CLOSED -> error("unreachable")
            }
        }
        return out
    }

    /** Signals the end of the byte stream. A partly received frame is a failure; nothing is ever delivered truncated. */
    fun finish(): Inbound.Failure? {
        if (phase == Phase.CLOSED || consumed == 0L) return null
        phase = Phase.CLOSED
        return Inbound.Failure(MeshError.PROTOCOL_ERROR, Reason.TRUNCATED, consumed)
    }

    private fun close(out: MutableList<Inbound>, failure: Inbound.Failure, remainder: Int): List<Inbound> {
        phase = Phase.CLOSED
        discardedAfterClose += remainder
        out += failure
        return out
    }

    private fun fail(error: MeshError, reason: String) = Inbound.Failure(error, reason, consumed)

    private fun evaluateLengthAndType(): Inbound.Failure? {
        lengthTypeDone = true
        length = readU32(0)
        type = head[4].toInt() and 0xFF
        if (length < WireLimits.MIN_LENGTH) return fail(MeshError.FRAME_TOO_LARGE, Reason.LENGTH_BELOW_MIN)
        if (length > WireLimits.MAX_LENGTH) return fail(MeshError.FRAME_TOO_LARGE, Reason.LENGTH_ABOVE_MAX)
        payloadLen = length - WireLimits.MIN_LENGTH
        if (FrameTypes.isExtension(type)) {
            spec = null
            return null
        }
        val s = FrameTypes.specs[type] ?: return fail(MeshError.PROTOCOL_ERROR, Reason.UNKNOWN_TYPE)
        val modeOk = when (mode) {
            ConnMode.ESTABLISHED -> !FrameTypes.isPair(type)
            ConnMode.PAIRING -> FrameTypes.isPair(type) || type == FrameTypes.ERROR
        }
        if (!modeOk) return fail(MeshError.PROTOCOL_ERROR, Reason.MODE_REJECTS_TYPE)
        val directionOk = when (s.direction) {
            Direction.BOTH -> true
            Direction.CLIENT_TO_SERVER -> receiver == PeerRole.TLS_SERVER
            Direction.SERVER_TO_CLIENT -> receiver == PeerRole.TLS_CLIENT
        }
        if (!directionOk) return fail(MeshError.PROTOCOL_ERROR, Reason.WRONG_DIRECTION)
        if (payloadLen > WireLimits.payloadLimit(s)) return fail(MeshError.FRAME_TOO_LARGE, Reason.PAYLOAD_LIMIT)
        spec = s
        return null
    }

    private fun evaluateStream(out: MutableList<Inbound>): Inbound.Failure? {
        stream = readU32(5)
        val s = spec
        if (s == null) {
            phase = Phase.SKIP
            skipped = 0
            if (payloadLen == 0L) completeExtension(out)
            return null
        }
        if (!streamAllowed(s.stream, stream)) return fail(MeshError.PROTOCOL_ERROR, Reason.STREAM_RULE)
        phase = Phase.PAYLOAD
        payloadFill = 0
        payload = ByteArray(minOf(payloadLen, INITIAL_BUFFER.toLong()).toInt())
        return if (payloadLen == 0L) completePayload(out) else null
    }

    private fun completePayload(out: MutableList<Inbound>): Inbound.Failure? {
        val s = spec!!
        val bytes = if (payload.size == payloadFill) payload else payload.copyOf(payloadFill)
        if (s.payload == PayloadClass.JSON) {
            when (val r = StrictJson.parse(bytes)) {
                is ParseResult.Reject -> return fail(MeshError.PROTOCOL_ERROR, r.code.name)
                is ParseResult.Ok -> if (r.value !is JObject) return fail(MeshError.PROTOCOL_ERROR, Reason.NOT_AN_OBJECT)
            }
        }
        val frame = RawFrame(type, stream, bytes)
        out += Inbound.Frame(frame)
        appBytesDelivered += frame.appBytes
        nextFrame()
        return null
    }

    private fun completeExtension(out: MutableList<Inbound>) {
        val app = WireLimits.HEADER_BYTES + payloadLen
        out += Inbound.ExtIgnored(type, stream, app)
        appBytesDelivered += app
        nextFrame()
    }

    private fun nextFrame() {
        headFill = 0
        lengthTypeDone = false
        phase = Phase.HEADER
        payload = ByteArray(0)
        payloadFill = 0
        skipped = 0
        consumed = 0
        spec = null
    }

    private fun grow(needed: Int) {
        if (needed <= payload.size) return
        payload = payload.copyOf(minOf(maxOf(needed, payload.size * 2).toLong(), payloadLen).toInt())
    }

    private fun readU32(at: Int): Long =
        ((head[at].toLong() and 0xFF) shl 24) or ((head[at + 1].toLong() and 0xFF) shl 16) or ((head[at + 2].toLong() and 0xFF) shl 8) or (head[at + 3].toLong() and 0xFF)

    private companion object {
        const val INITIAL_BUFFER = 8192
    }
}
