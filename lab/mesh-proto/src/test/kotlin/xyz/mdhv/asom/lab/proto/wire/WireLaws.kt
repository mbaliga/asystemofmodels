package xyz.mdhv.asom.lab.proto.wire

import java.util.SplittableRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.policy.ProducerStrict
import xyz.mdhv.asom.lab.policy.StateSchema

/**
 * The frame rules written a second time, as a table, so that the codec is checked against something it was not derived from. A letter pair is
 * (who sends it: C client, S server, B both) and (its stream: Z zero, O odd, A any, P pairing stream 1).
 */
object Oracle {
    private val table: Map<Int, Pair<Char, Char>> = mapOf(
        0x01 to ('C' to 'Z'), 0x02 to ('S' to 'Z'), 0x05 to ('B' to 'Z'), 0x06 to ('B' to 'A'),
        0x10 to ('C' to 'O'), 0x11 to ('S' to 'O'), 0x12 to ('S' to 'O'), 0x13 to ('C' to 'O'), 0x14 to ('S' to 'O'), 0x15 to ('S' to 'O'), 0x16 to ('S' to 'O'), 0x17 to ('C' to 'O'),
        0x20 to ('C' to 'O'), 0x21 to ('S' to 'O'), 0x22 to ('C' to 'O'), 0x23 to ('S' to 'O'),
        0x30 to ('B' to 'P'), 0x31 to ('B' to 'P'), 0x32 to ('B' to 'P'), 0x33 to ('B' to 'P'), 0x34 to ('B' to 'P'),
        0x40 to ('B' to 'Z'),
    )

    val knownTypes: Set<Int> = table.keys

    fun accepts(type: Int, stream: Long, receiver: PeerRole, mode: ConnMode): Boolean {
        val (sender, rule) = table[type] ?: return false
        val isPair = type in 0x30..0x34
        if (mode == ConnMode.ESTABLISHED && isPair) return false
        if (mode == ConnMode.PAIRING && !isPair && type != 0x06) return false
        val senderOk = sender == 'B' || (sender == 'C' && receiver == PeerRole.TLS_SERVER) || (sender == 'S' && receiver == PeerRole.TLS_CLIENT)
        if (!senderOk) return false
        return when (rule) {
            'Z' -> stream == 0L
            'O' -> stream % 2 == 1L
            'A' -> true
            else -> stream == 1L
        }
    }

    fun payloadFor(type: Int): ByteArray = if (type == 0x13 || type == 0x15) ByteArray(0) else "{}".toByteArray()
}

fun header(length: Long, type: Int, stream: Long): ByteArray {
    val b = ByteArray(9)
    for (k in 0..3) b[k] = (length ushr (24 - 8 * k)).toByte()
    b[4] = type.toByte()
    for (k in 0..3) b[5 + k] = (stream ushr (24 - 8 * k)).toByte()
    return b
}

fun failureOf(f: Fed): Inbound.Failure = f.events.filterIsInstance<Inbound.Failure>().singleOrNull() ?: fail("expected one failure, got ${describe(f.events)}")

/** Each function exercises one rule over many cases, asserts, and returns the number of cases (LAB_SPEC R10: a zero count fails the suite). */
object WireLaws {
    private val server = PeerRole.TLS_SERVER
    private val client = PeerRole.TLS_CLIENT
    private val streams = listOf(0L, 1L, 2L, 3L, 4L, 5L, 255L, 256L, 0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FFFEL, 0xFFFF_FFFFL)

    fun frameLengthBounds(): Int {
        var cases = 0
        for (length in listOf(0L, 1L, 2L, 3L, 4L)) {
            for (type in listOf(0x01, 0x15, 0x80, 0xFF, 0x03, 0x7F)) {
                val f = decodeWhole(header(length, type, 1), client)
                val x = failureOf(f)
                assertEquals(MeshError.FRAME_TOO_LARGE, x.error, "length $length type $type")
                assertEquals(Reason.LENGTH_BELOW_MIN, x.reason)
                assertEquals(5L, x.consumed, "the failure is raised as soon as length and type have arrived")
                cases++
            }
        }
        val above = listOf(WireLimits.MAX_LENGTH + 1, WireLimits.MAX_LENGTH + 2, 0x0200_0000L, 0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FFFEL, 0xFFFF_FFFFL)
        for (length in above) {
            for (type in listOf(0x01, 0x15, 0x13, 0x80, 0xFF, 0x03)) {
                val x = failureOf(decodeWhole(header(length, type, 1), client))
                assertEquals(MeshError.FRAME_TOO_LARGE, x.error, "length $length type $type")
                assertEquals(Reason.LENGTH_ABOVE_MAX, x.reason)
                assertEquals(5L, x.consumed)
                cases++
            }
        }
        val five = decodeWhole(header(5, FrameTypes.INFER_CHUNK, 1), client)
        assertEquals(listOf("F 21 1 "), describe(five.events), "length 5 on a raw type is an empty chunk")
        assertEquals(9L, five.decoder.appBytesDelivered)
        cases++
        val max = ByteArray(9 + WireLimits.MAX_PAYLOAD.toInt())
        header(WireLimits.MAX_LENGTH, FrameTypes.INFER_CHUNK, 1).copyInto(max)
        val atMax = decodeWhole(max, client)
        val frame = (atMax.events.single() as Inbound.Frame).frame
        assertEquals(16_777_216, frame.payload.size)
        assertEquals(16_777_225L, frame.appBytes, "application bytes are 9 + the payload")
        cases++
        val six = decodeWhole(header(6, FrameTypes.INFER_CHUNK, 1) + byteArrayOf(0x7b), client)
        assertEquals(listOf("F 21 1 7b"), describe(six.events))
        cases++
        val jsonAtMax = failureOf(decodeWhole(header(WireLimits.MAX_LENGTH, FrameTypes.HELLO, 0), server))
        assertEquals(Reason.PAYLOAD_LIMIT, jsonAtMax.reason, "the maximum length is a frame bound, not a JSON bound")
        cases++
        val jsonFive = failureOf(decodeWhole(header(5, FrameTypes.HELLO, 0), server))
        assertEquals("MALFORMED_JSON", jsonFive.reason, "an empty JSON payload is not JSON")
        cases++
        val truncatedPrefix = failureOf(decodeWhole(header(WireLimits.MAX_LENGTH, FrameTypes.INFER_CHUNK, 1).copyOf(7), client))
        assertEquals(Reason.TRUNCATED, truncatedPrefix.reason)
        cases++
        return cases
    }

    fun streamParity(): Int {
        var cases = 0
        for (receiver in PeerRole.entries) for (mode in ConnMode.entries) for (type in Oracle.knownTypes) for (stream in streams) {
            val bytes = FrameEncoderBypass.frame(type, stream, Oracle.payloadFor(type))
            val f = decodeWhole(bytes, receiver, mode)
            val want = Oracle.accepts(type, stream, receiver, mode)
            val got = f.events.singleOrNull() is Inbound.Frame
            assertEquals(want, got, "type 0x%02x stream %d receiver %s mode %s: %s".format(type, stream, receiver, mode, describe(f.events)))
            if (!want) {
                val x = failureOf(f)
                assertEquals(MeshError.PROTOCOL_ERROR, x.error)
                assertTrue(x.reason in setOf(Reason.STREAM_RULE, Reason.WRONG_DIRECTION, Reason.MODE_REJECTS_TYPE), x.reason)
            }
            cases++
        }
        return cases
    }

    fun unknownType(): Int {
        var cases = 0
        val retired = listOf(0x03, 0x04, 0x41, 0x42, 0x24, 0x25)
        for (type in 0..0x7F) {
            if (type in Oracle.knownTypes) continue
            for (receiver in PeerRole.entries) for (mode in ConnMode.entries) {
                val x = failureOf(decodeWhole(FrameEncoderBypass.frame(type, 0, "{}".toByteArray()), receiver, mode))
                assertEquals(MeshError.PROTOCOL_ERROR, x.error, "type $type")
                assertEquals(Reason.UNKNOWN_TYPE, x.reason)
                assertEquals(5L, x.consumed)
                cases++
            }
        }
        for (t in retired) assertFalse(t in Oracle.knownTypes, "a retired type must not be known")
        assertTrue(retired.all { t -> FrameTypes.specs[t] == null })
        return cases
    }

    fun extensionSkip(): Int {
        var cases = 0
        val rnd = SplittableRandom(7)
        for (type in 0x80..0xFF) for (receiver in PeerRole.entries) for (mode in ConnMode.entries) {
            val payload = ByteArray(rnd.nextInt(30)) { rnd.nextInt(256).toByte() }
            val stream = rnd.nextLong(1L shl 32)
            val f = decodeWhole(FrameEncoderBypass.frame(type, stream, payload), receiver, mode)
            val ev = f.events.single() as? Inbound.ExtIgnored ?: fail("type $type was not surfaced as EXT_IGNORED: ${describe(f.events)}")
            assertEquals(type, ev.type)
            assertEquals(stream, ev.stream)
            assertEquals(9L + payload.size, ev.appBytes)
            assertEquals(ev.appBytes, f.decoder.appBytesDelivered)
            cases++
        }
        val between = FrameEncoderBypass.frame(0x01, 0, """{"v":1}""".toByteArray()) + FrameEncoderBypass.frame(0x99, 5, byteArrayOf(1, 2, 3)) + FrameEncoderBypass.frame(0x20, 3, """{"v":1}""".toByteArray())
        assertEquals(listOf("F 1 0 7b2276223a317d", "X 153 5 12", "F 32 3 7b2276223a317d"), describe(decodeWhole(between, server).events))
        cases++
        return cases
    }

    private val jsonTable: List<Pair<String, String?>> = listOf(
        """{"v":1}""" to null, """  {"z":1,"a":2}  """ to null, """{"v":9007199254740991}""" to null, """{"a":{"b":[1,2,{"c":null}]}}""" to null,
        """{"v":1.5}""" to "NON_INTEGER_NUMBER", """{"v":1e2}""" to "NON_INTEGER_NUMBER", """{"v":-0}""" to "NON_INTEGER_NUMBER", """{"v":NaN}""" to "NON_INTEGER_NUMBER",
        """{"v":Infinity}""" to "NON_INTEGER_NUMBER", """{"v":9007199254740992}""" to "NUMBER_RANGE", """{"v":1,"v":1}""" to "DUPLICATE_KEY", """{"a":{"x":1,"x":2}}""" to "DUPLICATE_KEY",
        """not json""" to "MALFORMED_JSON", """{"v":""" to "MALFORMED_JSON", "" to "MALFORMED_JSON", """{"v":1,}""" to "MALFORMED_JSON", """{'v':1}""" to "MALFORMED_JSON",
        """{"v":1}x""" to "TRAILING_DATA", """{"v":"\ud800"}""" to "INVALID_UNICODE", """{"v":"\udc00"}""" to "INVALID_UNICODE", """[]""" to Reason.NOT_AN_OBJECT, """5""" to Reason.NOT_AN_OBJECT,
        """"x"""" to Reason.NOT_AN_OBJECT, """null""" to Reason.NOT_AN_OBJECT,
        """{"a":""" + "[".repeat(16) + "1" + "]".repeat(16) + "}" to "MALFORMED_JSON", """{"a":""" + "[".repeat(15) + "1" + "]".repeat(15) + "}" to null,
    )

    fun strictJson(): Int {
        var cases = 0
        for ((text, want) in jsonTable) {
            val f = decodeWhole(FrameEncoderBypass.frame(0x20, 1, text.toByteArray()), server)
            if (want == null) assertTrue(f.events.single() is Inbound.Frame, "should be accepted: $text")
            else {
                val x = failureOf(f)
                assertEquals(want, x.reason, text)
                assertEquals(MeshError.PROTOCOL_ERROR, x.error)
            }
            cases++
        }
        val bytesCases = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + """{"v":1}""".toByteArray() to "MALFORMED_JSON",
            """{"v":"""".toByteArray() + byteArrayOf(0xFF.toByte()) + """"}""".toByteArray() to "INVALID_UNICODE",
            """{"v":"""".toByteArray() + byteArrayOf(0xC0.toByte(), 0x80.toByte()) + """"}""".toByteArray() to "INVALID_UNICODE",
            """{"v":"""".toByteArray() + byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + """"}""".toByteArray() to "INVALID_UNICODE",
        )
        for ((bytes, want) in bytesCases) {
            assertEquals(want, failureOf(decodeWhole(FrameEncoderBypass.frame(0x20, 1, bytes), server)).reason)
            cases++
        }
        val rawOk = decodeWhole(FrameEncoderBypass.frame(0x15, 1, byteArrayOf(0xFF.toByte(), 0, 1)), client)
        assertTrue(rawOk.events.single() is Inbound.Frame, "a raw type carries any bytes")
        cases++
        for (t in Oracle.knownTypes.filter { it !in setOf(0x13, 0x15) }) {
            val spec = FrameTypes.specs.getValue(t)
            val receiver = if (spec.direction == Direction.SERVER_TO_CLIENT) client else server
            val mode = if (FrameTypes.isPair(t)) ConnMode.PAIRING else ConnMode.ESTABLISHED
            val stream = when (spec.stream) { StreamRule.ZERO -> 0L; StreamRule.PAIRING_ONE -> 1L; else -> 1L }
            assertEquals("NON_INTEGER_NUMBER", failureOf(decodeWhole(FrameEncoderBypass.frame(t, stream, """{"v":1.5}""".toByteArray()), receiver, mode)).reason, "type $t")
            cases++
        }
        return cases
    }

    fun sizeLimits(): Int {
        var cases = 0
        fun padded(n: Int): ByteArray = """{"v":1}""".toByteArray() + ByteArray(n - 7) { ' '.code.toByte() }
        val okJson = decodeWhole(FrameEncoderBypass.frame(0x20, 1, padded(1_048_576)), server)
        assertEquals(1_048_576, (okJson.events.single() as Inbound.Frame).frame.payload.size)
        cases++
        val over = decodeWhole(FrameEncoderBypass.frame(0x20, 1, padded(1_048_577)), server)
        assertTrue(over.events.none { it is Inbound.Frame }, "an over-limit payload is never delivered, not even truncated")
        val x = failureOf(over)
        assertEquals(MeshError.FRAME_TOO_LARGE, x.error)
        assertEquals(Reason.PAYLOAD_LIMIT, x.reason)
        assertEquals(5L, x.consumed, "refused as soon as length and type arrive, before any payload is buffered")
        cases++
        val okBody = decodeWhole(FrameEncoderBypass.frame(0x13, 1, ByteArray(8_388_608) { 0x61 }), server)
        assertEquals(8_388_608, (okBody.events.single() as Inbound.Frame).frame.payload.size)
        assertEquals(8_388_617L, okBody.decoder.appBytesDelivered)
        cases++
        val overBody = decodeWhole(FrameEncoderBypass.frame(0x13, 1, ByteArray(8_388_609) { 0x61 }), server)
        assertEquals(Reason.PAYLOAD_LIMIT, failureOf(overBody).reason)
        assertTrue(overBody.events.none { it is Inbound.Frame })
        cases++
        val bigChunk = decodeWhole(FrameEncoderBypass.frame(0x15, 1, ByteArray(8_388_609) { 0x62 }), client)
        assertEquals(8_388_609, (bigChunk.events.single() as Inbound.Frame).frame.payload.size, "a chunk is bounded by the frame maximum only")
        cases++
        for (n in listOf(1_048_577, 2_000_000, 16_777_216)) {
            assertEquals(MeshError.FRAME_TOO_LARGE, assertFailsRefusal { FrameEncoder.encode(RawFrame(0x20, 1, padded(n))) }.error)
            cases++
        }
        assertEquals(MeshError.FRAME_TOO_LARGE, assertFailsRefusal { FrameEncoder.encode(RawFrame(0x13, 1, ByteArray(8_388_609))) }.error)
        cases++
        FrameEncoder.encode(RawFrame(0x20, 1, padded(1_048_576)))
        FrameEncoder.encode(RawFrame(0x13, 1, ByteArray(8_388_608)))
        cases += 2
        return cases
    }

    fun assertFailsRefusal(block: () -> Unit): WireRefusal {
        try {
            block()
        } catch (e: WireRefusal) {
            return e
        }
        fail("expected a WireRefusal")
    }

    fun noMessageMember(): Int {
        var cases = 0
        val rnd = SplittableRandom(11)
        val needle = "SECRET-PEER-TEXT-4711"
        val codes = MeshError.entries.filter { it != MeshError.UNKNOWN }.map { it.name } + listOf("PEER_ON_FIRE", "peer_busy", "")
        repeat(120) {
            val code = rnd.pick(codes)
            val withAttempt = if (rnd.nextBoolean()) ""","attemptId":"${id(rnd, 16)}"""" else ""
            val text = """{"code":"$code","message":"$needle ${rnd.nextInt(1000)} \"quoted\" é"$withAttempt}"""
            val parsed = MessageCodec.parse(RawFrame(0x06, rnd.nextLong(1L shl 32), text.toByteArray()))
            val msg = (parsed as Parsed.Ok).message as PeerError
            assertFalse(StoredTextProbe.holds(msg, needle), "a received ERROR message was stored")
            assertFalse(Jcs.serializeToString(MessageCodec.members(msg, renderUnknown = true)).contains(needle))
            assertEquals(if (code in MeshError.entries.map { it.name } && code != "UNKNOWN") MeshError.valueOf(code) else MeshError.UNKNOWN, msg.code)
            assertEquals(if (msg.code == MeshError.UNKNOWN) MeshError.PROTOCOL_ERROR else msg.code, msg.effective)
            cases++
        }
        assertTrue(StoredTextProbe.holds(listOf("x", needle), needle), "the probe itself must see text it is given")
        assertTrue(StoredTextProbe.holds(mapOf("k" to needle.toByteArray()), needle))
        cases++
        val names = PeerError::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("message" in names, "PeerError has no message field")
        for (t in MeshError.entries.filter { it != MeshError.UNKNOWN }) {
            val members = MessageCodec.members(PeerError(t))
            assertNull(members["message"])
            cases++
        }
        assertEquals(Reason.UNKNOWN_NOT_EMITTABLE, assertFailsRefusal { MessageCodec.members(PeerError(MeshError.UNKNOWN)) }.reason)
        cases++
        return cases
    }

    fun stateProducerStrict(): Int {
        var cases = 0
        val rnd = SplittableRandom(13)
        val allowed = StateSchema.MEMBERS.values.flatten().toSet()
        val expectedFields = setOf(
            "seq", "sampledAgeMs", "fsm", "powerSource", "charging", "batteryBand", "thermalBand", "governor", "backend", "commit", "confVersion", "held", "queueBucket",
            "manifestSeq", "manifestDigest",
        )
        assertEquals(expectedFields, xyz.mdhv.asom.lab.policy.StateDoc::class.java.declaredFields.map { it.name }.toSet(), "StateDoc has no field that could carry a presence signal")
        cases++
        repeat(400) {
            val doc = randomState(rnd)
            val frame = MessageCodec.frame(StateMsg(doc), 1L + 2 * rnd.nextInt(1000))
            val bytes = FrameEncoder.encode(frame)
            assertNull(ProducerStrict.check(frame.payload), "the STATE builder emitted a member producer-strict refuses")
            val names = HashSet<String>()
            collectNames((StrictJson.parse(frame.payload) as ParseResult.Ok).value, names)
            assertTrue(allowed.containsAll(names), "members outside asom.state/1: ${names - allowed}")
            assertTrue(names.none { it in StateSchema.PRESENCE_NAMES })
            assertFalse(Regex("""[0-9]\.[0-9]|[0-9][eE][+-]?[0-9]""").containsMatchIn(String(frame.payload).replace(Regex("\"[^\"]*\""), "\"\"")), "a float in STATE")
            val back = (decodeWhole(bytes, client).events.single() as Inbound.Frame).frame
            val msg = (MessageCodec.parse(back) as Parsed.Ok).message as StateMsg
            assertEquals(doc.copy(held = doc.held.sorted()), msg.doc)
            cases++
        }
        val loaded = """{"user":{"active":true},"inflight":3,"busyForMs":10,"loaded":["x"],"estStartS":4,"reason":"game","availability":{"fsm":"SERVING"},"engine":{"backend":"vulkan","commit":"4f1c2ab","confVersion":"1.0.0","held":[],"loaded":["y"]},"manifest":null,"power":{"batteryBand":null,"charging":false,"source":"ac"},"queue":{"bucket":0,"queuePos":3},"sampledAgeMs":1,"seq":1,"thermal":{"band":0,"governor":"RUN"},"v":1}"""
        val parsed = (MessageCodec.parse(RawFrame(0x21, 1, loaded.toByteArray())) as Parsed.Ok).message as StateMsg
        val rebuilt = MessageCodec.payload(parsed)
        assertNull(ProducerStrict.check(rebuilt), "what a node relays after receiving presence fields is clean")
        assertEquals("PRESENCE_FIELD", ProducerStrict.check(loaded.toByteArray()))
        cases++
        assertEquals("NON_INTEGER_NUMBER", (MessageCodec.parse(RawFrame(0x21, 1, loaded.replace("\"seq\":1", "\"seq\":1.5").toByteArray())) as Parsed.Reject).reason)
        cases++
        return cases
    }

    private fun collectNames(v: JValue, out: MutableSet<String>) {
        when (v) {
            is JObject -> v.members.forEach { out += it.first; collectNames(it.second, out) }
            is xyz.mdhv.asom.lab.json.JArray -> v.items.forEach { collectNames(it, out) }
            else -> Unit
        }
    }

    fun versionRule(): Int {
        var cases = 0
        for (a in 1..6) for (b in a..6) for (c in 1..6) for (d in c..6) {
            val got = VersionRule.negotiate(VersionRange(a, b), VersionRange(c, d))
            var want: Int? = null
            for (v in 6 downTo 1) if (v in a..b && v in c..d) { want = v; break }
            assertEquals(want, got, "[$a,$b] and [$c,$d]")
            assertEquals(got, VersionRule.negotiate(VersionRange(c, d), VersionRange(a, b)), "the rule is symmetric")
            cases++
        }
        assertEquals(255, VersionRule.negotiate(VersionRange(1, 255), VersionRange(255, 255)))
        assertNull(VersionRule.negotiate(VersionRange(2, 3), VersionRange(1, 1)), "no downgrade below minV")
        cases += 2
        return cases
    }

    fun incrementalSplit(): Int {
        var cases = 0
        val rnd = SplittableRandom(17)
        for (receiver in PeerRole.entries) for (mode in ConnMode.entries) {
            repeat(40) {
                val seq = randomSequence(rnd, receiver, mode)
                val whole = decodeWhole(seq.bytes, receiver, mode)
                assertEquals(seq.expected, describe(whole.events), "whole feed")
                assertEquals(seq.bytes.size.toLong(), whole.decoder.appBytesDelivered, "every byte accounted for as application bytes")
                assertEquals(0L, whole.decoder.pendingBytes)
                for (k in 0..seq.bytes.size) {
                    val f = decodeChunks(seq.bytes, receiver, mode, listOf(k, seq.bytes.size - k).filter { it >= 0 })
                    assertEquals(seq.expected, describe(f.events), "split at $k of ${seq.bytes.size}")
                    cases++
                }
                val one = decodeChunks(seq.bytes, receiver, mode, List(seq.bytes.size) { 1 })
                assertEquals(seq.expected, describe(one.events), "one byte at a time")
                cases++
                repeat(5) {
                    val sizes = ArrayList<Int>()
                    var left = seq.bytes.size
                    while (left > 0) {
                        val n = minOf(left, 1 + rnd.nextInt(40))
                        sizes += n
                        left -= n
                    }
                    assertEquals(seq.expected, describe(decodeChunks(seq.bytes, receiver, mode, sizes).events), "random chunks $sizes")
                    cases++
                }
            }
        }
        return cases
    }
}

/** Builds raw frame bytes WITHOUT the producer's checks, so the decoder can be shown frames that no mesh-1 sender would emit. */
object FrameEncoderBypass {
    fun frame(type: Int, stream: Long, payload: ByteArray): ByteArray = header(5L + payload.size, type, stream) + payload
}
