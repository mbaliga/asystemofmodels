package xyz.mdhv.asom.lab.proto.wire

import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

class MessageCodecTest {
    private val rnd = SplittableRandom(99)
    private val server = PeerRole.TLS_SERVER
    private val client = PeerRole.TLS_CLIENT

    private val nodeId = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val attempt = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun parse(type: Int, text: String): Parsed = MessageCodec.parse(RawFrame(type, 1, text.toByteArray()))

    private fun reason(type: Int, text: String): String = (parse(type, text) as? Parsed.Reject)?.reason ?: "ACCEPTED"

    private val helloJson = """{"endpoints":[],"features":["state"],"keyTier":"file","maxV":1,"minV":1,"name":"n","nodeId":"$nodeId","platform":"linux","proto":"asom-mesh/1","sessionNonce":"$attempt","sw":"asom-desktop/1.0.0","ts":1,"v":1}"""

    private fun allowedMembers(type: Int): Set<String> = when (type) {
        FrameTypes.HELLO -> setOf("endpoints", "features", "keyTier", "maxV", "minV", "name", "nodeId", "platform", "proto", "sessionNonce", "sw", "ts", "v")
        FrameTypes.HELLO_ACK -> setOf("endpoints", "features", "granted", "limits", "nodeId", "st", "ts", "v")
        FrameTypes.GOAWAY -> setOf("reason")
        FrameTypes.ERROR -> setOf("attemptId", "code", "retryAfterMs")
        FrameTypes.INFER_OFFER -> setOf("attemptId", "deadlineMs", "estTokensIn", "maxTokens", "model", "op", "promptBytes", "stream")
        FrameTypes.INFER_ACCEPT -> setOf("attemptId", "fileSha256", "servedModel", "st")
        FrameTypes.INFER_DECLINE -> setOf("attemptId", "code", "retryAfterMs", "st")
        FrameTypes.INFER_HEAD -> setOf("attemptId", "engine", "servedModel", "status")
        FrameTypes.INFER_END -> setOf("attemptId", "st", "status", "terminal")
        FrameTypes.CANCEL -> setOf("attemptId", "reason")
        FrameTypes.STATE_REQ -> setOf("v")
        FrameTypes.MANIFEST_REQ -> setOf("challenge", "v")
        FrameTypes.REVOKE_NOTICE -> setOf("reason", "v")
        else -> error("no member list for $type")
    }

    private fun names(v: JValue, depth: Int = 0, out: MutableSet<String> = HashSet()): Set<String> {
        if (depth == 0 && v is JObject) v.members.forEach { out += it.first }
        return out
    }

    @Test
    fun everyTypeRoundTripsThroughTheCodecForRandomMessages() {
        var n = 0
        for (type in FrameTypes.specs.keys) repeat(150) {
            val msg = randomMessage(rnd, type)
            val spec = FrameTypes.specs.getValue(type)
            val stream = streamFor(rnd, spec.stream)
            val bytes = MessageCodec.encode(msg, stream)
            val receiver = if (spec.direction == Direction.SERVER_TO_CLIENT) client else server
            val mode = if (FrameTypes.isPair(type)) ConnMode.PAIRING else ConnMode.ESTABLISHED
            val frame = (decodeWhole(bytes, receiver, mode).events.single() as Inbound.Frame).frame
            assertEquals(type, frame.type)
            assertEquals(stream, frame.stream)
            assertEquals(msg, (MessageCodec.parse(frame) as Parsed.Ok).message, "type 0x%02x".format(type))
            n++
        }
        assertTrue(n >= FrameTypes.specs.size * 150)
    }

    @Test
    fun producersEmitJcsWithIntegersOnlyAndOnlyTheSpecifiedMembers() {
        for (type in FrameTypes.specs.keys) {
            if (type in setOf(FrameTypes.INFER_BODY, FrameTypes.INFER_CHUNK, FrameTypes.STATE, FrameTypes.MANIFEST) || FrameTypes.isPair(type)) continue
            repeat(100) {
                val msg = randomMessage(rnd, type)
                val payload = MessageCodec.payload(msg)
                val parsed = (StrictJson.parse(payload) as ParseResult.Ok).value
                assertEquals(String(payload), Jcs.serializeToString(parsed), "the producer emits JCS")
                assertTrue(allowedMembers(type).containsAll(names(parsed)), "type $type emitted ${names(parsed) - allowedMembers(type)}")
                assertFalse(Regex("""[0-9]\.[0-9]|[0-9][eE][0-9]""").containsMatchIn(String(payload).replace(Regex("\"[^\"]*\""), "\"\"")))
            }
        }
    }

    @Test
    fun theRemovedMembersAreNeverEmitted() {
        val accept = MessageCodec.members(randomMessage(rnd, FrameTypes.INFER_ACCEPT))
        assertNull(accept["queuePos"])
        assertNull(accept["estStartMs"])
        val end = MessageCodec.members(randomMessage(rnd, FrameTypes.INFER_END))
        for (k in listOf("usage", "ttftMs", "totalMs")) assertNull(end[k])
        val offer = MessageCodec.members(randomMessage(rnd, FrameTypes.INFER_OFFER))
        for (k in listOf("dataClass", "retain")) assertNull(offer[k])
        val declined = InferDecline::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("message" in declined)
    }

    @Test
    fun receiversDoNotRequireJcsAndIgnoreUnknownMembers() {
        val ok = parse(FrameTypes.HELLO, helloJson)
        assertTrue(ok is Parsed.Ok)
        val reordered = """ { "v" : 1,
           "ts":1, "zzz":{"deep":[1,2,3]}, "sw":"asom-desktop/1.0.0","sessionNonce":"$attempt","proto":"asom-mesh/1","platform":"linux","nodeId":"$nodeId","name":"n","minV":1,"maxV":1,"keyTier":"file","features":["state","unknown-feature"],"endpoints":[] } """
        assertEquals((ok as Parsed.Ok).message, (parse(FrameTypes.HELLO, reordered) as Parsed.Ok).message)
        val hello = ok.message as Hello
        assertEquals(setOf(Feature.STATE), hello.features)
        assertNull(MessageCodec.members(hello)["zzz"])
    }

    @Test
    fun theStrictJsonProfileIsEnforcedOnEveryTypedFrame() {
        for ((text, want) in listOf(
            helloJson.replace("\"ts\":1", "\"ts\":1.5") to "NON_INTEGER_NUMBER",
            helloJson.replace("\"ts\":1", "\"ts\":1e3") to "NON_INTEGER_NUMBER",
            helloJson.replace("\"v\":1}", "\"v\":1,\"v\":1}") to "DUPLICATE_KEY",
            helloJson.replace("\"name\":\"n\"", "\"name\":\"\\ud800\"") to "INVALID_UNICODE",
            helloJson.replace("\"ts\":1", "\"ts\":9007199254740992") to "NUMBER_RANGE",
            "[$helloJson]" to Reason.NOT_AN_OBJECT,
            helloJson + " x" to "TRAILING_DATA",
        )) assertEquals(want, reason(FrameTypes.HELLO, text), text.take(60))
        val deep = "{\"a\":" + "[".repeat(16) + "]".repeat(16) + "}"
        assertEquals("MALFORMED_JSON", reason(FrameTypes.GOAWAY, deep))
        val bytes = helloJson.toByteArray().copyOf().also { it[it.indexOf('n'.code.toByte())] = 0xFF.toByte() }
        assertEquals("INVALID_UNICODE", (MessageCodec.parse(RawFrame(0x01, 0, bytes)) as Parsed.Reject).reason)
    }

    @Test
    fun identifiersAreValidatedExactly() {
        val badNodeIds = listOf(
            nodeId.dropLast(1), nodeId + "A", nodeId.dropLast(1) + "B", nodeId.replace('A', '+').take(43), "$nodeId=", " ${nodeId.drop(1)}", "", nodeId.replace('A', '*'),
            nodeId.dropLast(1) + "/",
        )
        for (id in badNodeIds) assertEquals("OUT_OF_RANGE", reason(FrameTypes.HELLO, helloJson.replace(nodeId, id)), "nodeId '$id'")
        for (id in listOf(attempt.dropLast(1), attempt + "A", attempt.dropLast(1) + "B", "$attempt=", "")) {
            assertEquals("OUT_OF_RANGE", reason(FrameTypes.CANCEL, """{"attemptId":"$id","reason":"deadline"}"""), "attemptId '$id'")
        }
        assertEquals("ACCEPTED", reason(FrameTypes.CANCEL, """{"attemptId":"$attempt","reason":"deadline"}"""))
        for (c in listOf(nodeId.dropLast(1), nodeId + "A", nodeId.dropLast(1) + "B")) assertEquals("OUT_OF_RANGE", reason(FrameTypes.MANIFEST_REQ, """{"challenge":"$c","v":1}"""))
        assertEquals("ACCEPTED", reason(FrameTypes.MANIFEST_REQ, """{"challenge":"$nodeId","v":1}"""))
        assertEquals("OUT_OF_RANGE", reason(FrameTypes.HELLO, helloJson.replace("\"sessionNonce\":\"$attempt\"", "\"sessionNonce\":\"${attempt.dropLast(1)}\"")))
    }

    @Test
    fun enumerationsAreClosed() {
        for ((k, v) in listOf("platform" to "beos", "keyTier" to "hsm", "proto" to "asom-mesh/2")) {
            val replaced = helloJson.replace(Regex("\"$k\":\"[^\"]*\""), "\"$k\":\"$v\"")
            assertEquals("UNKNOWN_ENUM", reason(FrameTypes.HELLO, replaced), k)
        }
        for (p in Platform.entries) assertEquals("ACCEPTED", reason(FrameTypes.HELLO, helloJson.replace("\"linux\"", "\"${p.wire}\"")))
        for (t in KeyTier.entries) assertEquals("ACCEPTED", reason(FrameTypes.HELLO, helloJson.replace("\"file\"", "\"${t.wire}\"")))
        assertEquals("UNKNOWN_ENUM", reason(FrameTypes.GOAWAY, """{"reason":"sleep"}"""))
        assertEquals("UNKNOWN_ENUM", reason(FrameTypes.INFER_DECLINE, """{"attemptId":"$attempt","code":"PEER_THERMAL","retryAfterMs":5000}"""))
        assertEquals("UNKNOWN_ENUM", reason(FrameTypes.INFER_END, """{"attemptId":"$attempt","status":200,"terminal":"thermal"}"""))
        assertEquals("UNKNOWN_ENUM", reason(FrameTypes.CANCEL, """{"attemptId":"$attempt","reason":"x"}"""))
        assertEquals("UNKNOWN_ENUM", reason(FrameTypes.INFER_OFFER, """{"attemptId":"$attempt","deadlineMs":1,"estTokensIn":1,"maxTokens":1,"model":"m","op":"edit","promptBytes":1,"stream":true}"""))
    }

    @Test
    fun endpointsAreAtMostFourIpLiterals() {
        fun hello(vararg eps: String) = helloJson.replace("\"endpoints\":[]", "\"endpoints\":[${eps.joinToString(",")}]")
        fun ep(addr: String, port: Int = 11436, via: String = "lan") = """{"addr":"$addr","port":$port,"via":"$via"}"""
        assertEquals("ACCEPTED", reason(FrameTypes.HELLO, hello(ep("10.0.0.1"), ep("10.0.0.2"), ep("fd00::1"), ep("::ffff:1.2.3.4"))))
        assertEquals("OUT_OF_RANGE", reason(FrameTypes.HELLO, hello(*Array(5) { ep("10.0.0.$it") })))
        for (bad in listOf("example.com", "localhost", "[::1]", "fe80::1%eth0", "10.0.0.256", "010.0.0.1", "10.0.0", "10.0.0.1.5", "::1::2", "1:2:3:4:5:6:7:8:9", ":1", "1:", "g::1", "", "::1.2.3.4.5", "1.2.3.4::", "http://10.0.0.1")) {
            assertEquals("OUT_OF_RANGE", reason(FrameTypes.HELLO, hello(ep(bad))), "address '$bad'")
        }
        for (ok in listOf("::", "::1", "1::", "1:2:3:4:5:6:7::", "::2:3:4:5:6:7:8", "1:2:3:4:5:6:1.2.3.4", "::ffff:255.255.255.255", "2001:DB8::FF00:42:8329", "0.1.2.3")) {
            assertEquals("ACCEPTED", reason(FrameTypes.HELLO, hello(ep(ok))), "address '$ok'")
        }
        assertEquals("OUT_OF_RANGE", reason(FrameTypes.HELLO, hello(ep("10.0.0.1", 0))))
        assertEquals("OUT_OF_RANGE", reason(FrameTypes.HELLO, hello(ep("10.0.0.1", 65536))))
        assertEquals("UNKNOWN_ENUM", reason(FrameTypes.HELLO, hello(ep("10.0.0.1", 1, "wan"))))
        assertEquals("WRONG_TYPE", reason(FrameTypes.HELLO, hello("\"10.0.0.1\"")))
    }

    @Test
    fun nameLengthCountsCodePoints() {
        fun hello(name: String) = helloJson.replace("\"name\":\"n\"", "\"name\":\"$name\"")
        assertEquals("ACCEPTED", reason(FrameTypes.HELLO, hello("x".repeat(32))))
        assertEquals("ACCEPTED", reason(FrameTypes.HELLO, hello("\uD83D\uDDA5".repeat(32))))
        assertEquals("OUT_OF_RANGE", reason(FrameTypes.HELLO, hello("\uD83D\uDDA5".repeat(33))))
        assertEquals("OUT_OF_RANGE", reason(FrameTypes.HELLO, hello("")))
        assertEquals("OUT_OF_RANGE", reason(FrameTypes.HELLO, hello("a\\u0007b")))
    }

    @Test
    fun aReceivedErrorKeepsOnlyItsCodeAndNeverItsMessage() {
        val needle = "SECRET-PEER-TEXT-4711"
        val msg = (parse(FrameTypes.ERROR, """{"code":"PEER_BUSY","message":"$needle","retryAfterMs":7000}""") as Parsed.Ok).message as PeerError
        assertEquals(PeerError(MeshError.PEER_BUSY, null, 7000), msg)
        assertFalse(StoredTextProbe.holds(msg, needle))
        assertFalse(PeerError::class.java.declaredFields.any { it.name == "message" })
        val unknown = (parse(FrameTypes.ERROR, """{"code":"PEER_ON_FIRE","message":"$needle"}""") as Parsed.Ok).message as PeerError
        assertEquals(MeshError.UNKNOWN, unknown.code)
        assertEquals(MeshError.PROTOCOL_ERROR, unknown.effective)
        assertFalse(StoredTextProbe.holds(unknown, needle))
        assertEquals(Reason.UNKNOWN_NOT_EMITTABLE, WireLaws.assertFailsRefusal { MessageCodec.encode(unknown, 0) }.reason)
        for (e in MeshError.entries.filter { it != MeshError.UNKNOWN }) {
            val back = (parse(FrameTypes.ERROR, """{"code":"${e.name}"}""") as Parsed.Ok).message as PeerError
            assertEquals(e, back.code)
        }
    }

    @Test
    fun fieldRangesAreEnforcedOnTheProducerSide() {
        assertEquals("OUT_OF_RANGE", WireLaws.assertFailsRefusal { InferDecline(attempt, DeclineWire.PEER_BUSY, 4999) }.reason)
        assertEquals("OUT_OF_RANGE", WireLaws.assertFailsRefusal { InferDecline(attempt, DeclineWire.PEER_BUSY, 600_001) }.reason)
        InferDecline(attempt, DeclineWire.PEER_BUSY, 5000)
        InferDecline(attempt, DeclineWire.PEER_BUSY, 600_000)
        assertEquals("OUT_OF_RANGE", WireLaws.assertFailsRefusal { St(xyz.mdhv.asom.lab.policy.Fsm.OFF, xyz.mdhv.asom.lab.policy.Governor.RUN, 3, 1, 0) }.reason)
        assertEquals("OUT_OF_RANGE", WireLaws.assertFailsRefusal { Endpoint("example.com", 1, Via.LAN) }.reason)
        assertEquals("OUT_OF_RANGE", WireLaws.assertFailsRefusal { Cancel("short", CancelReason.DEADLINE) }.reason)
        assertEquals("BAD_VERSION", WireLaws.assertFailsRefusal {
            Hello(emptyList(), emptySet(), KeyTier.FILE, 1, 1, "n", nodeId, Platform.LINUX, attempt, "a/1", 1, 2)
        }.reason)
    }

    @Test
    fun pairFramesAreOpaqueWithACodecHook() {
        val frame = RawFrame(FrameTypes.PAIR_HELLO, 1, """{"v":1,"nonceS":"x"}""".toByteArray())
        val opaque = (MessageCodec.parse(frame) as Parsed.Ok).message as PairMsg
        assertEquals(FrameTypes.PAIR_HELLO, opaque.type)
        assertEquals(String(frame.payload), String(opaque.payload))
        val refused = MessageCodec.parse(frame) { _, _ -> "NONCE_BAD" } as Parsed.Reject
        assertEquals("NONCE_BAD", refused.reason)
        assertEquals(MeshError.PROTOCOL_ERROR, refused.error)
        val seen = ArrayList<Int>()
        MessageCodec.parse(frame) { t, _ -> seen += t; null }
        assertEquals(listOf(FrameTypes.PAIR_HELLO), seen)
        assertEquals(setOf(0x30, 0x31, 0x32, 0x33, 0x34), FrameTypes.specs.keys.filter { FrameTypes.isPair(it) }.toSet())
    }

    @Test
    fun manifestPayloadIsAStrictJsonContainer() {
        val ok = RawFrame(FrameTypes.MANIFEST, 1, """{"payload":"e30","signatures":[]}""".toByteArray())
        assertTrue(MessageCodec.parse(ok) is Parsed.Ok)
        assertEquals(Reason.NOT_AN_OBJECT, (MessageCodec.parse(RawFrame(FrameTypes.MANIFEST, 1, "[]".toByteArray())) as Parsed.Reject).reason)
        assertEquals("NON_INTEGER_NUMBER", (MessageCodec.parse(RawFrame(FrameTypes.MANIFEST, 1, """{"a":1.0}""".toByteArray())) as Parsed.Reject).reason)
    }

    @Test
    fun anUnknownTypeNeverParses() {
        for (t in listOf(0x03, 0x04, 0x41, 0x42, 0x24, 0x25, 0x80, 0xFF)) {
            assertEquals(Reason.UNKNOWN_TYPE, (MessageCodec.parse(RawFrame(t, 0, "{}".toByteArray())) as Parsed.Reject).reason)
        }
    }

    @Suppress("unused")
    private fun silence(a: JArray) = a
}
