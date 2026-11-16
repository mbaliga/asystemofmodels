package xyz.mdhv.asom.lab.conformance

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.policy.ProducerStrict
import xyz.mdhv.asom.lab.policy.StateParse
import xyz.mdhv.asom.lab.policy.StateParser
import xyz.mdhv.asom.lab.policy.StateSchema
import xyz.mdhv.asom.lab.proto.wire.AckDecision
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.Direction
import xyz.mdhv.asom.lab.proto.wire.Feature
import xyz.mdhv.asom.lab.proto.wire.FrameDecoder
import xyz.mdhv.asom.lab.proto.wire.FrameEncoder
import xyz.mdhv.asom.lab.proto.wire.FrameTypes
import xyz.mdhv.asom.lab.proto.wire.Handshake
import xyz.mdhv.asom.lab.proto.wire.HelloAck
import xyz.mdhv.asom.lab.proto.wire.HelloDecision
import xyz.mdhv.asom.lab.proto.wire.Hello
import xyz.mdhv.asom.lab.proto.wire.Inbound
import xyz.mdhv.asom.lab.proto.wire.InferBody
import xyz.mdhv.asom.lab.proto.wire.InferChunk
import xyz.mdhv.asom.lab.proto.wire.ManifestMsg
import xyz.mdhv.asom.lab.proto.wire.MessageCodec
import xyz.mdhv.asom.lab.proto.wire.Message
import xyz.mdhv.asom.lab.proto.wire.PairMsg
import xyz.mdhv.asom.lab.proto.wire.Parsed
import xyz.mdhv.asom.lab.proto.wire.PayloadClass
import xyz.mdhv.asom.lab.proto.wire.PeerError
import xyz.mdhv.asom.lab.proto.wire.PeerRole
import xyz.mdhv.asom.lab.proto.wire.RawFrame
import xyz.mdhv.asom.lab.proto.wire.Reason
import xyz.mdhv.asom.lab.proto.wire.StateMsg
import xyz.mdhv.asom.lab.proto.wire.StoredTextProbe
import xyz.mdhv.asom.lab.proto.wire.VersionRange
import xyz.mdhv.asom.lab.proto.wire.VersionRule
import xyz.mdhv.asom.lab.proto.wire.WireLimits
import xyz.mdhv.asom.lab.proto.wire.WireRefusal

/** The JSON a vector carries, as the hand-written `:json` value model (a vector may hold integers only where a frame is concerned). */
internal fun toJValue(e: JsonElement): JValue = when (e) {
    is JsonNull -> JNull
    is JsonObject -> JObject(e.entries.map { it.key to toJValue(it.value) })
    is JsonArray -> JArray(e.map { toJValue(it) })
    is JsonPrimitive -> when {
        e.isString -> JString(e.content)
        e.content == "true" || e.content == "false" -> JBool(e.content == "true")
        else -> JInt(e.content.toLongOrNull() ?: throw LawViolation("a vector's frame members hold integers only, got ${e.content}"))
    }
}

private fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

private fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

private fun unhex(s: String): ByteArray {
    val t = s.replace(" ", "")
    require(t.length % 2 == 0) { "odd hex length" }
    return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** A byte source as vectors write it: a list of `{hex}` and `{repeat, count}` parts. */
internal fun expandBytes(parts: JsonArray): ByteArray {
    val chunks = parts.map { p ->
        val o = p as JsonObject
        if ("hex" in o) unhex(o.str("hex"))
        else {
            val unit = unhex(o.str("repeat"))
            val n = o.long("count").toInt()
            ByteArray(unit.size * n) { unit[it % unit.size] }
        }
    }
    val out = ByteArray(chunks.sumOf { it.size })
    var at = 0
    for (c in chunks) {
        c.copyInto(out, at)
        at += c.size
    }
    return out
}

private fun receiverOf(i: JsonObject): PeerRole = when (val r = i.str("receiver")) {
    "server" -> PeerRole.TLS_SERVER
    "client" -> PeerRole.TLS_CLIENT
    else -> throw LawViolation("unknown receiver '$r'")
}

private fun modeOf(i: JsonObject): ConnMode = when (val m = i.strOrNull("mode") ?: "established") {
    "established" -> ConnMode.ESTABLISHED
    "pairing" -> ConnMode.PAIRING
    else -> throw LawViolation("unknown mode '$m'")
}

private fun refusal(e: WireRefusal, extra: Map<String, Long> = emptyMap()): Observed.Reject =
    Observed.Reject(
        e.error.name,
        buildJsonObject {
            put("reason", e.reason)
            extra.forEach { (k, v) -> put(k, v) }
        },
    )

/** What a decoder did with a byte string, end of stream included. */
private class Decoded(val events: List<Inbound>, val bytesFed: Long, val appBytes: Long, val pending: Long, val discarded: Long)

private fun decodeAll(bytes: ByteArray, receiver: PeerRole, mode: ConnMode, chunks: List<Int>?): Decoded {
    val d = FrameDecoder(receiver, mode)
    val out = ArrayList<Inbound>()
    if (chunks == null) out += d.feed(bytes)
    else {
        var at = 0
        for (n in chunks) {
            out += d.feed(bytes, at, n)
            at += n
        }
        check(at == bytes.size)
    }
    d.finish()?.let { out += it }
    return Decoded(out, d.totalFed, d.appBytesDelivered, d.pendingBytes, d.discardedAfterClose)
}

private fun sameEvents(a: List<Inbound>, b: List<Inbound>): Boolean {
    if (a.size != b.size) return false
    for (k in a.indices) {
        val x = a[k]
        val y = b[k]
        val same = when {
            x is Inbound.Frame && y is Inbound.Frame -> x.frame == y.frame
            x is Inbound.ExtIgnored && y is Inbound.ExtIgnored -> x.type == y.type && x.stream == y.stream && x.appBytes == y.appBytes
            x is Inbound.Failure && y is Inbound.Failure -> x.error == y.error && x.reason == y.reason && x.consumed == y.consumed
            else -> false
        }
        if (!same) return false
    }
    return true
}

/** The chunkings a byte string is decoded under, besides one whole feed: every split point and one byte at a time when it is small; a schedule when it is large. */
private fun chunkings(n: Int): List<List<Int>> {
    if (n == 0) return listOf(emptyList())
    if (n <= 4096) {
        val out = ArrayList<List<Int>>()
        for (k in 0..n) out += listOf(k, n - k)
        out += List(n) { 1 }
        return out
    }
    val head = List(64) { 1 }
    var left = n - 64
    val body = ArrayList<Int>()
    while (left > 0) {
        val c = minOf(left, 4093)
        body += c
        left -= c
    }
    return listOf(head + body)
}

private val JSON_REASONS = setOf("MALFORMED_JSON", "INVALID_UNICODE", "NON_INTEGER_NUMBER", "NUMBER_RANGE", "DUPLICATE_KEY", "TRAILING_DATA", Reason.NOT_AN_OBJECT)

private fun walkNames(v: JValue, out: MutableSet<String>) {
    when (v) {
        is JObject -> v.members.forEach { out += it.first; walkNames(it.second, out) }
        is JArray -> v.items.forEach { walkNames(it, out) }
        else -> Unit
    }
}

/**
 * W06: the `asom-mesh/1` frame codec and message layer (LAB_SPEC 7.1, 7.2): the four worked encodings, a golden encoding of every frame, the incremental decoder
 * under every split, the stream and direction rules, extensions and unknown types, the strict JSON profile, the size limits, `ERROR` without a message member, and
 * the version rule. Evidence label: LAB, oracle: self (the expectations were computed by an independent Python model, same session).
 */
class W06Checker : FamilyChecker("W06") {
    override val requiredLaws = setOf(
        "frame-length-bounds", "stream-parity", "unknown-type", "extension-skip", "strict-json", "size-limits", "no-message-member", "version-rule", "incremental-split",
        "encode-decode-roundtrip", "byte-accounting", "typed-parse-reject",
    )

    override val requiredIds = setOf("W06-001", "W06-002", "W06-003", "W06-004")

    override fun observe(v: Vector): Observed {
        val i = v.input
        return when (val kind = i.str("kind")) {
            "frameEncode" -> frameEncode(i)
            "messageEncode" -> messageEncode(i)
            "frameDecode" -> decodeKind(i, typed = false)
            "messageDecode" -> decodeKind(i, typed = true)
            "negotiate" -> negotiate(i)
            "helloDecision" -> helloDecision(i)
            "ackDecision" -> ackDecision(i)
            else -> throw LawViolation("unknown W06 kind '$kind'")
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------ encoding

    private fun roundTrip(frameBytes: ByteArray, frame: RawFrame, msg: Message?) {
        val spec = FrameTypes.specs.getValue(frame.type)
        val receiver = if (spec.direction == Direction.SERVER_TO_CLIENT) PeerRole.TLS_CLIENT else PeerRole.TLS_SERVER
        val mode = if (FrameTypes.isPair(frame.type)) ConnMode.PAIRING else ConnMode.ESTABLISHED
        val back = decodeAll(frameBytes, receiver, mode, null)
        val only = back.events.singleOrNull() as? Inbound.Frame ?: throw LawViolation("an encoded frame did not decode to exactly one frame: ${back.events.map { it::class.simpleName }}")
        if (only.frame != frame) throw LawViolation("an encoded frame did not decode to itself")
        if (msg != null) {
            val parsed = MessageCodec.parse(only.frame)
            val ok = parsed as? Parsed.Ok ?: throw LawViolation("an encoded message did not parse back: ${(parsed as Parsed.Reject).reason}")
            if (!messageEquals(ok.message, msg)) throw LawViolation("an encoded message did not parse back to itself")
        }
        bump("encode-decode-roundtrip")
    }

    private fun messageEquals(a: Message, b: Message): Boolean = a == b

    private fun encodeObservation(frameBytes: ByteArray, payloadJson: String?) = Observed.Ok(
        buildJsonObject {
            put("appBytes", frameBytes.size.toLong())
            put("frameSha256", sha256Hex(frameBytes))
            if (frameBytes.size <= 1024) put("hex", hexOf(frameBytes))
            if (payloadJson != null) put("payload", payloadJson)
        },
    )

    private fun frameEncode(i: JsonObject): Observed {
        val type = FrameTypes.parse(i.str("type"))
        val frame = RawFrame(type, i.long("stream"), expandBytes(i.arr("payload")))
        val bytes = try {
            FrameEncoder.encode(frame)
        } catch (e: WireRefusal) {
            countRefusal(e)
            return refusal(e)
        }
        bump("frame-length-bounds")
        bump("stream-parity")
        if (frame.payload.size.toLong() == WireLimits.payloadLimit(FrameTypes.specs.getValue(type))) bump("size-limits")
        if (FrameTypes.specs.getValue(type).payload == PayloadClass.JSON) bump("strict-json")
        roundTrip(bytes, frame, null)
        return encodeObservation(bytes, null)
    }

    private fun countRefusal(e: WireRefusal) {
        when (e.reason) {
            Reason.PAYLOAD_LIMIT -> bump("size-limits")
            Reason.STREAM_RULE -> bump("stream-parity")
            Reason.UNKNOWN_TYPE, Reason.EXTENSION_NOT_SENT -> bump("unknown-type")
            else -> if (e.reason in JSON_REASONS) bump("strict-json")
        }
    }

    private fun messageEncode(i: JsonObject): Observed {
        val type = FrameTypes.parse(i.str("type"))
        val stream = i.long("stream")
        try {
            val msg: Message
            if ("members" in i) {
                msg = MessageCodec.fromMembers(type, toJValue(i.obj("members")) as JObject)
            } else {
                val payload = expandBytes(i.arr("payload"))
                msg = when {
                    type == FrameTypes.INFER_BODY -> InferBody(payload)
                    type == FrameTypes.INFER_CHUNK -> InferChunk(payload)
                    FrameTypes.isPair(type) -> PairMsg(type, payload)
                    else -> throw LawViolation("a payload-only messageEncode vector must be a raw or PAIR type")
                }
            }
            val frame = MessageCodec.frame(msg, stream)
            val bytes = FrameEncoder.encode(frame)
            bump("frame-length-bounds")
            bump("stream-parity")
            roundTrip(bytes, frame, msg)
            val json = if (msg is InferBody || msg is InferChunk || msg is PairMsg) null else String(frame.payload, Charsets.UTF_8)
            if (json != null) {
                val parsed = StrictJson.parse(frame.payload)
                if (parsed !is ParseResult.Ok) throw LawViolation("the producer emitted a payload that its own strict parser refuses")
                if (Jcs.serializeToString(parsed.value) != json) throw LawViolation("the producer did not emit JCS")
                bump("strict-json")
            }
            return encodeObservation(bytes, json)
        } catch (e: WireRefusal) {
            countRefusal(e)
            if (e.reason != Reason.UNKNOWN_NOT_EMITTABLE) bump("typed-parse-reject")
            return refusal(e)
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------ decoding

    private fun decodeKind(i: JsonObject, typed: Boolean): Observed {
        val bytes = expandBytes(i.arr("bytes"))
        val receiver = receiverOf(i)
        val mode = modeOf(i)
        return observeDecode(bytes, receiver, mode, typed)
    }

    internal fun observeDecode(bytes: ByteArray, receiver: PeerRole, mode: ConnMode, typed: Boolean): Observed {
        val whole = decodeAll(bytes, receiver, mode, null)
        if (whole.bytesFed != whole.appBytes + whole.pending + whole.discarded) {
            throw LawViolation("byte accounting broke: fed ${whole.bytesFed} != delivered ${whole.appBytes} + pending ${whole.pending} + discarded ${whole.discarded}")
        }
        bump("byte-accounting")
        for (c in chunkings(bytes.size)) {
            val split = decodeAll(bytes, receiver, mode, c)
            if (!sameEvents(whole.events, split.events)) throw LawViolation("the decoder's events depend on the chunking (${c.take(4)}...)")
            if (split.appBytes != whole.appBytes || split.pending != whole.pending || split.discarded != whole.discarded) {
                throw LawViolation("the decoder's byte accounting depends on the chunking")
            }
        }
        bump("incremental-split", chunkings(bytes.size).size)

        val events = ArrayList<JsonObject>()
        for (ev in whole.events) {
            when (ev) {
                is Inbound.ExtIgnored -> {
                    bump("extension-skip")
                    if (ev.type !in 0x80..0xFF) throw LawViolation("an extension event for type ${ev.type}")
                    if (ev.appBytes - 4 in listOf(WireLimits.MIN_LENGTH, WireLimits.MAX_LENGTH)) bump("frame-length-bounds")
                    events += buildJsonObject {
                        put("kind", "EXT_IGNORED")
                        put("type", ev.type.toLong())
                        put("stream", ev.stream)
                        put("appBytes", ev.appBytes)
                    }
                }
                is Inbound.Frame -> {
                    countAccepted(ev.frame)
                    if (!typed) events += frameEvent(ev.frame)
                    else {
                        when (val r = MessageCodec.parse(ev.frame)) {
                            is Parsed.Reject -> {
                                bump("typed-parse-reject")
                                return Observed.Reject(
                                    r.error.name,
                                    buildJsonObject {
                                        put("reason", r.reason)
                                        put("framesBefore", events.size.toLong())
                                        put("consumed", ev.appBytes)
                                    },
                                )
                            }
                            is Parsed.Ok -> events += messageEvent(ev.frame, r.message)
                        }
                    }
                }
                is Inbound.Failure -> {
                    countFailure(ev)
                    return Observed.Reject(
                        ev.error.name,
                        buildJsonObject {
                            put("reason", ev.reason)
                            put("framesBefore", events.size.toLong())
                            put("consumed", ev.consumed)
                        },
                    )
                }
            }
        }
        return Observed.Ok(buildJsonObject { put("events", JsonArray(events)) })
    }

    private fun countAccepted(f: RawFrame) {
        val spec = FrameTypes.specs.getValue(f.type)
        bump("stream-parity")
        val length = f.appBytes - 4
        if (length in listOf(WireLimits.MIN_LENGTH, WireLimits.MAX_LENGTH)) bump("frame-length-bounds")
        if (f.payload.size.toLong() == WireLimits.payloadLimit(spec) && spec.payload != PayloadClass.RAW_CHUNK) bump("size-limits")
        if (spec.payload == PayloadClass.JSON) bump("strict-json")
    }

    private fun countFailure(f: Inbound.Failure) {
        when (f.reason) {
            Reason.LENGTH_BELOW_MIN, Reason.LENGTH_ABOVE_MAX -> bump("frame-length-bounds")
            Reason.UNKNOWN_TYPE -> bump("unknown-type")
            Reason.STREAM_RULE, Reason.WRONG_DIRECTION, Reason.MODE_REJECTS_TYPE -> bump("stream-parity")
            Reason.PAYLOAD_LIMIT -> bump("size-limits")
            Reason.TRUNCATED -> bump("frame-length-bounds")
            else -> if (f.reason in JSON_REASONS) bump("strict-json") else throw LawViolation("an unexpected decoder failure reason ${f.reason}")
        }
    }

    private fun frameEvent(f: RawFrame): JsonObject = buildJsonObject {
        put("kind", "FRAME")
        put("type", FrameTypes.nameOf(f.type))
        put("stream", f.stream)
        put("appBytes", f.appBytes)
        put("payloadLen", f.payload.size.toLong())
        put("payloadSha256", sha256Hex(f.payload))
    }

    private fun messageEvent(f: RawFrame, msg: Message): JsonObject {
        if (msg is PeerError) probeNoMessage(f, msg)
        return buildJsonObject {
            put("kind", "MESSAGE")
            put("type", FrameTypes.nameOf(f.type))
            put("stream", f.stream)
            put("appBytes", f.appBytes)
            when (msg) {
                is InferBody, is InferChunk, is PairMsg -> {
                    put("payloadLen", f.payload.size.toLong())
                    put("payloadSha256", sha256Hex(f.payload))
                }
                is ManifestMsg -> put("normal", Jcs.serializeToString((StrictJson.parse(msg.container) as ParseResult.Ok).value))
                else -> put("normal", Jcs.serializeToString(MessageCodec.members(msg, renderUnknown = true)))
            }
        }
    }

    /** A received `ERROR` that carries a `message` member must not hold that text anywhere in what the parser kept (design T15). */
    private fun probeNoMessage(f: RawFrame, msg: PeerError) {
        val root = (StrictJson.parse(f.payload) as ParseResult.Ok).value as JObject
        val text = (root["message"] as? JString)?.value ?: return
        if (StoredTextProbe.holds(msg, text)) throw LawViolation("a received ERROR's message text was stored in the parsed message")
        if (Jcs.serializeToString(MessageCodec.members(msg, renderUnknown = true)).contains(text)) throw LawViolation("a received ERROR's message text reached the normal form")
        bump("no-message-member")
    }

    // ------------------------------------------------------------------------------------------------------------------------------ negotiation

    private fun range(o: JsonObject) = VersionRange(o.long("minV").toInt(), o.long("maxV").toInt())

    private fun features(i: JsonObject, key: String): Set<Feature> = i.strList(key).map { s -> Feature.entries.first { it.wire == s } }.toSet()

    private fun negotiate(i: JsonObject): Observed {
        bump("version-rule")
        val v = VersionRule.negotiate(range(i.obj("local")), range(i.obj("peer")))
            ?: return Observed.Reject("VERSION_UNSUPPORTED")
        return Observed.Ok(buildJsonObject { put("v", v.toLong()) })
    }

    private fun helloDecision(i: JsonObject): Observed {
        bump("version-rule")
        val hello = try {
            MessageCodec.fromMembers(FrameTypes.HELLO, toJValue(i.obj("hello")) as JObject) as Hello
        } catch (e: WireRefusal) {
            return Observed.Reject(e.error.name)
        }
        return when (val d = Handshake.onHello(range(i.obj("local")), features(i, "localFeatures"), hello, i.str("tlsNodeId"))) {
            is HelloDecision.Refuse -> Observed.Reject(d.error.code.name)
            is HelloDecision.Established -> Observed.Ok(
                buildJsonObject {
                    put("v", d.v.toLong())
                    put("features", jsonStrings(d.features.map { it.wire }.sorted()))
                },
            )
        }
    }

    private fun ackDecision(i: JsonObject): Observed {
        bump("version-rule")
        val ack = try {
            MessageCodec.fromMembers(FrameTypes.HELLO_ACK, toJValue(i.obj("ack")) as JObject) as HelloAck
        } catch (e: WireRefusal) {
            return Observed.Reject(e.error.name)
        }
        return when (val d = Handshake.onAck(range(i.obj("local")), features(i, "localFeatures"), ack, i.str("tlsNodeId"))) {
            is AckDecision.Refuse -> Observed.Reject(d.error.code.name)
            is AckDecision.Established -> Observed.Ok(
                buildJsonObject {
                    put("v", d.v.toLong())
                    put("granted", jsonStrings(d.granted.map { it.wire }.sorted()))
                    put(
                        "limits",
                        buildJsonObject {
                            put("idleUnloadMs", d.limits.idleUnloadMs)
                            put("maxBodyBytes", d.limits.maxBodyBytes)
                            put("maxConcurrent", d.limits.maxConcurrent)
                            put("maxTokens", d.limits.maxTokens)
                            put("rpm", d.limits.rpm)
                        },
                    )
                    put("features", jsonStrings(d.features.map { it.wire }.sorted()))
                },
            )
        }
    }
}

/**
 * W07 is two vector files: `policy/W07-live-state.json` (the producer-strict builder, parser, staleness; kinds `stateBuild`, `stateParse`, `producerCheck`,
 * `staleness`, `skew`, run by [W07Checker]) and `wire/W07-state-frames.json` (the same document as a frame: kinds `wireStateBuild`, `wireStateDecode`,
 * `wireStateProducer`). One checker serves both: a kind that starts with `wire` is run here, any other is delegated (ERRATA ERR-PW-9).
 */
class W07WireChecker : FamilyChecker("W07") {
    private val inner = W07Checker()
    private val frames = W06Checker()

    override val requiredLaws: Set<String> = inner.requiredLaws + setOf("wire-state-build", "wire-state-decode-ok", "wire-state-decode-reject", "state-producer-strict", "wire-presence-ignored")

    override fun observe(v: Vector): Observed {
        val kind = v.input.str("kind")
        if (!kind.startsWith("wire")) {
            val before = LinkedHashMap(inner.laws)
            val o = inner.observe(v)
            for ((k, n) in inner.laws) {
                val d = n - (before[k] ?: 0)
                if (d > 0) bump(k, d)
            }
            return o
        }
        val i = v.input
        return when (kind) {
            "wireStateBuild" -> stateBuild(i)
            "wireStateDecode" -> stateDecode(i)
            "wireStateProducer" -> stateProducer(i)
            else -> throw LawViolation("unknown W07 frame kind '$kind'")
        }
    }

    private fun stateBuild(i: JsonObject): Observed {
        val state = toJValue(i.obj("state")) as JObject
        val doc = when (val r = StateParser.parse(Jcs.serialize(state))) {
            is StateParse.Reject -> return Observed.Reject("PROTOCOL_ERROR", buildJsonObject { put("reason", r.code) })
            is StateParse.Ok -> r.doc
        }
        val frame: RawFrame
        val bytes: ByteArray
        try {
            frame = MessageCodec.frame(StateMsg(doc), i.long("stream"))
            bytes = FrameEncoder.encode(frame)
        } catch (e: WireRefusal) {
            return Observed.Reject(e.error.name, buildJsonObject { put("reason", e.reason) })
        }
        val text = String(frame.payload, Charsets.UTF_8)
        if (ProducerStrict.check(frame.payload) != null) throw LawViolation("the STATE builder emitted a member that producer-strict refuses: ${ProducerStrict.check(frame.payload)}")
        val names = HashSet<String>()
        walkNames((StrictJson.parse(frame.payload) as ParseResult.Ok).value, names)
        val allowed = StateSchema.MEMBERS.values.flatten().toSet()
        if (!allowed.containsAll(names)) throw LawViolation("the STATE builder emitted members outside asom.state/1: ${names - allowed}")
        if (names.any { it in StateSchema.PRESENCE_NAMES }) throw LawViolation("the STATE builder emitted a presence field")
        if (Regex("""[0-9]\.[0-9]|[0-9][eE][+-]?[0-9]""").containsMatchIn(text.replace(Regex("\"[^\"]*\""), "\"\""))) throw LawViolation("the STATE builder emitted a float")
        bump("wire-state-build")
        bump("state-producer-strict")
        val back = decodeFrame(bytes)
        val parsedBack = (MessageCodec.parse(back) as? Parsed.Ok)?.message as? StateMsg ?: throw LawViolation("the STATE frame did not parse back")
        if (Jcs.serializeToString(MessageCodec.members(parsedBack)) != text) throw LawViolation("the STATE frame did not parse back to the document it was built from")
        return Observed.Ok(
            buildJsonObject {
                put("appBytes", bytes.size.toLong())
                put("frameSha256", sha256Hex(bytes))
                if (bytes.size <= 1024) put("hex", hexOf(bytes))
                put("payload", text)
            },
        )
    }

    private fun decodeFrame(bytes: ByteArray): RawFrame {
        val d = FrameDecoder(PeerRole.TLS_CLIENT).feed(bytes)
        return (d.single() as Inbound.Frame).frame
    }

    private fun stateDecode(i: JsonObject): Observed {
        val bytes = expandBytes(i.arr("bytes"))
        val o = frames.observeDecode(bytes, receiverOf(i), modeOf(i), typed = true)
        if (o is Observed.Ok) {
            bump("wire-state-decode-ok")
            val d = FrameDecoder(receiverOf(i)).feed(bytes)
            val f = (d.firstOrNull() as? Inbound.Frame)?.frame
            if (f != null && f.type == FrameTypes.STATE) {
                val root = (StrictJson.parse(f.payload) as ParseResult.Ok).value
                val received = HashSet<String>()
                walkNames(root, received)
                val kept = HashSet<String>()
                val normal = (o.value as JsonObject).arr("events").map { (it as JsonObject).str("normal") }.firstOrNull()
                if (normal != null) walkNames((StrictJson.parse(normal.toByteArray()) as ParseResult.Ok).value, kept)
                if (received.any { it in StateSchema.PRESENCE_NAMES } && kept.none { it in StateSchema.PRESENCE_NAMES }) bump("wire-presence-ignored")
                if (kept.any { it in StateSchema.PRESENCE_NAMES }) throw LawViolation("a received presence field was kept in the parsed STATE")
            }
        } else {
            bump("wire-state-decode-reject")
        }
        return o
    }

    private fun stateProducer(i: JsonObject): Observed {
        val bytes = expandBytes(i.arr("bytes"))
        val d = FrameDecoder(PeerRole.TLS_CLIENT)
        val events = d.feed(bytes) + listOfNotNull(d.finish())
        val failure = events.filterIsInstance<Inbound.Failure>().firstOrNull()
        if (failure != null) {
            return Observed.Reject(
                failure.error.name,
                buildJsonObject {
                    put("reason", failure.reason)
                    put("framesBefore", events.indexOf(failure).toLong())
                    put("consumed", failure.consumed)
                },
            )
        }
        val frame = (events.first() as Inbound.Frame).frame
        bump("state-producer-strict")
        val code = ProducerStrict.check(frame.payload) ?: return Observed.Ok(buildJsonObject { put("conforms", true) })
        return Observed.Reject(code)
    }
}
