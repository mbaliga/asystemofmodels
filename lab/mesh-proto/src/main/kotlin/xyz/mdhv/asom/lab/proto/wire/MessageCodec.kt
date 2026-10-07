package xyz.mdhv.asom.lab.proto.wire

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

sealed interface Parsed {
    class Ok(val message: Message) : Parsed

    class Reject(val error: MeshError, val reason: String) : Parsed
}

/**
 * Typed builders and STRICT parsers for every frame of LAB_SPEC 7.2. Producers emit JCS with integers only (the value model has no float member, so a
 * float cannot be built). Receivers parse with the strict `:json` parser, do not require JCS, ignore unknown members and never store them.
 */
object MessageCodec {
    const val PROTO = "asom-mesh/1"

    fun frame(msg: Message, stream: Long): RawFrame = RawFrame(msg.type, stream, payload(msg))

    fun encode(msg: Message, stream: Long): ByteArray = FrameEncoder.encode(frame(msg, stream))

    fun payload(msg: Message): ByteArray = when (msg) {
        is InferBody -> msg.bytes
        is InferChunk -> msg.bytes
        is PairMsg -> msg.payload
        is ManifestMsg -> msg.container
        else -> Jcs.serialize(members(msg))
    }

    /** The members of a JSON message. With [renderUnknown], a stored `UNKNOWN` error code is shown as such (a diagnostic form; it is never put on the wire). */
    fun members(msg: Message, renderUnknown: Boolean = false): JObject = when (msg) {
        is Hello -> JObject(
            listOf(
                "endpoints" to endpoints(msg.endpoints), "features" to featureArray(msg.features), "keyTier" to JString(msg.keyTier.wire), "maxV" to JInt(msg.maxV.toLong()),
                "minV" to JInt(msg.minV.toLong()), "name" to JString(msg.name), "nodeId" to JString(msg.nodeId), "platform" to JString(msg.platform.wire),
                "proto" to JString(PROTO), "sessionNonce" to JString(msg.sessionNonce), "sw" to JString(msg.sw), "ts" to JInt(msg.ts), "v" to JInt(msg.v.toLong()),
            ),
        )
        is HelloAck -> JObject(
            listOfNotNull(
                "endpoints" to endpoints(msg.endpoints), "features" to featureArray(msg.features),
                "granted" to JArray(msg.granted.sortedBy { it.wire }.map { JString(it.wire) }),
                "limits" to JObject(
                    listOf(
                        "idleUnloadMs" to JInt(msg.limits.idleUnloadMs), "maxBodyBytes" to JInt(msg.limits.maxBodyBytes),
                        "maxConcurrent" to JInt(msg.limits.maxConcurrent), "maxTokens" to JInt(msg.limits.maxTokens), "rpm" to JInt(msg.limits.rpm),
                    ),
                ),
                "nodeId" to JString(msg.nodeId), stOf(msg.st), "ts" to JInt(msg.ts), "v" to JInt(msg.v.toLong()),
            ),
        )
        is GoAway -> JObject(listOf("reason" to JString(msg.reason.wire)))
        is PeerError -> {
            if (msg.code == MeshError.UNKNOWN && !renderUnknown) bad(Reason.UNKNOWN_NOT_EMITTABLE)
            JObject(
                listOfNotNull(
                    msg.attemptId?.let { "attemptId" to JString(it) }, "code" to JString(msg.code.name), msg.retryAfterMs?.let { "retryAfterMs" to JInt(it) },
                ),
            )
        }
        is InferOffer -> JObject(
            listOf(
                "attemptId" to JString(msg.attemptId), "deadlineMs" to JInt(msg.deadlineMs), "estTokensIn" to JInt(msg.estTokensIn), "maxTokens" to JInt(msg.maxTokens),
                "model" to JString(msg.model), "op" to JString(msg.op.wire), "promptBytes" to JInt(msg.promptBytes), "stream" to JBool(msg.stream),
            ),
        )
        is InferAccept -> JObject(
            listOfNotNull("attemptId" to JString(msg.attemptId), "fileSha256" to JString(msg.fileSha256), "servedModel" to JString(msg.servedModel), stOf(msg.st)),
        )
        is InferDecline -> JObject(
            listOfNotNull("attemptId" to JString(msg.attemptId), "code" to JString(msg.code.wire), "retryAfterMs" to JInt(msg.retryAfterMs), stOf(msg.st)),
        )
        is InferHead -> JObject(
            listOf("attemptId" to JString(msg.attemptId), "engine" to JString(msg.engine), "servedModel" to JString(msg.servedModel), "status" to JInt(msg.status.toLong())),
        )
        is InferEnd -> JObject(
            listOfNotNull("attemptId" to JString(msg.attemptId), stOf(msg.st), "status" to JInt(msg.status.toLong()), "terminal" to JString(msg.terminal.wire)),
        )
        is Cancel -> JObject(listOf("attemptId" to JString(msg.attemptId), "reason" to JString(msg.reason.wire)))
        is StateReq -> JObject(listOf("v" to JInt(1)))
        is StateMsg -> stateMembers(msg)
        is ManifestReq -> JObject(listOf("challenge" to JString(msg.challenge), "v" to JInt(1)))
        is RevokeNotice -> JObject(listOf("reason" to JString("user"), "v" to JInt(1)))
        is InferBody, is InferChunk, is ManifestMsg, is PairMsg -> throw IllegalArgumentException("${msg::class.simpleName} has no members")
    }

    private fun stateMembers(msg: StateMsg): JObject {
        val obj = try {
            StateParser.normalForm(msg.doc)
        } catch (e: IllegalArgumentException) {
            bad(Reason.OUT_OF_RANGE)
        }
        if (ProducerStrict.check(Jcs.serialize(obj)) != null) bad(Reason.UNKNOWN_ENUM)
        return obj
    }

    private fun stOf(st: St?): Pair<String, JValue>? = st?.let {
        "st" to JObject(listOf("fsm" to JString(it.fsm.name), "gov" to JString(it.gov.name), "qb" to JInt(it.qb.toLong()), "seq" to JInt(it.seq), "tb" to JInt(it.tb.toLong())))
    }

    private fun endpoints(list: List<Endpoint>): JValue =
        JArray(list.map { JObject(listOf("addr" to JString(it.addr), "port" to JInt(it.port.toLong()), "via" to JString(it.via.wire))) })

    private fun featureArray(f: Set<Feature>): JValue = JArray(f.sortedBy { it.wire }.map { JString(it.wire) })

    // ---------------------------------------------------------------------------------------------------------------- parsing

    /** The strict parse of one frame. Never throws for peer input: a refusal is a [Parsed.Reject] with a typed code and a reason that carries no peer text. */
    fun parse(frame: RawFrame, pairHook: PairPayloadHook? = null): Parsed = try {
        Parsed.Ok(parseOrThrow(frame, pairHook))
    } catch (e: WireRefusal) {
        Parsed.Reject(e.error, e.reason)
    }

    private fun parseOrThrow(frame: RawFrame, pairHook: PairPayloadHook?): Message {
        val spec = FrameTypes.specs[frame.type] ?: bad(Reason.UNKNOWN_TYPE)
        when (frame.type) {
            FrameTypes.INFER_BODY -> return InferBody(frame.payload)
            FrameTypes.INFER_CHUNK -> return InferChunk(frame.payload)
        }
        val root = when (val r = StrictJson.parse(frame.payload)) {
            is ParseResult.Reject -> bad(r.code.name)
            is ParseResult.Ok -> r.value as? JObject ?: bad(Reason.NOT_AN_OBJECT)
        }
        if (FrameTypes.isPair(spec.type)) {
            pairHook?.check(spec.type, frame.payload)?.let { bad(it) }
            return PairMsg(spec.type, frame.payload)
        }
        if (spec.type == FrameTypes.STATE) return parseState(frame.payload)
        if (spec.type == FrameTypes.MANIFEST) return ManifestMsg(frame.payload)
        return fromMembers(spec.type, root)
    }

    private fun parseState(bytes: ByteArray): Message = when (val r = StateParser.parse(bytes)) {
        is StateParse.Ok -> StateMsg(r.doc)
        is StateParse.Reject -> bad(r.code)
    }

    /** The typed message for [type] from already-parsed members. Raw-payload types and `PAIR_*` are not member-shaped and are refused here. */
    fun fromMembers(type: Int, root: JObject): Message {
        val r = Rd(root)
        return when (type) {
            FrameTypes.HELLO -> {
                if (r.str("proto") != PROTO) bad(Reason.UNKNOWN_ENUM)
                Hello(
                    endpoints = r.endpoints("endpoints"), features = r.known("features", Feature.entries), keyTier = r.enum("keyTier", KeyTier.entries),
                    minV = r.int("minV").toIntOrBad(), maxV = r.int("maxV").toIntOrBad(), name = r.str("name"), nodeId = r.str("nodeId"),
                    platform = r.enum("platform", Platform.entries), sessionNonce = r.str("sessionNonce"), sw = r.str("sw"), ts = r.int("ts"), v = r.int("v").toIntOrBad(),
                )
            }
            FrameTypes.HELLO_ACK -> HelloAck(
                endpoints = r.endpoints("endpoints"), features = r.known("features", Feature.entries), granted = r.known("granted", Scope.entries),
                limits = r.obj("limits").let { l ->
                    Limits(l.int("idleUnloadMs"), l.int("maxBodyBytes"), l.int("maxConcurrent"), l.int("maxTokens"), l.int("rpm"))
                },
                nodeId = r.str("nodeId"), st = r.st(), ts = r.int("ts"), v = r.int("v").toIntOrBad(),
            )
            FrameTypes.GOAWAY -> GoAway(r.enum("reason", GoAwayReason.entries))
            FrameTypes.ERROR -> {
                val code = MeshError.fromWire(r.str("code"))
                PeerError(code, r.strOrNull("attemptId"), r.intOrNull("retryAfterMs"))
            }
            FrameTypes.INFER_OFFER -> InferOffer(
                attemptId = r.str("attemptId"), deadlineMs = r.int("deadlineMs"), estTokensIn = r.int("estTokensIn"), maxTokens = r.int("maxTokens"), model = r.str("model"),
                op = r.enum("op", InferOp.entries), promptBytes = r.int("promptBytes"), stream = r.bool("stream"),
            )
            FrameTypes.INFER_ACCEPT -> InferAccept(r.str("attemptId"), r.str("fileSha256"), r.str("servedModel"), r.st())
            FrameTypes.INFER_DECLINE -> InferDecline(r.str("attemptId"), r.enum("code", DeclineWire.entries), r.int("retryAfterMs"), r.st())
            FrameTypes.INFER_HEAD -> {
                if (r.str("engine") != "local") bad(Reason.UNKNOWN_ENUM)
                InferHead(r.str("attemptId"), r.str("servedModel"), r.int("status").toIntOrBad())
            }
            FrameTypes.INFER_END -> InferEnd(r.str("attemptId"), r.int("status").toIntOrBad(), r.enum("terminal", Terminal.entries), r.st())
            FrameTypes.CANCEL -> Cancel(r.str("attemptId"), r.enum("reason", CancelReason.entries))
            FrameTypes.STATE_REQ -> {
                r.version()
                StateReq
            }
            FrameTypes.STATE -> parseState(Jcs.serialize(root))
            FrameTypes.MANIFEST -> ManifestMsg(Jcs.serialize(root))
            FrameTypes.MANIFEST_REQ -> {
                val challenge = r.str("challenge")
                r.version()
                ManifestReq(challenge)
            }
            FrameTypes.REVOKE_NOTICE -> {
                if (r.str("reason") != "user") bad(Reason.UNKNOWN_ENUM)
                r.version()
                RevokeNotice
            }
            else -> bad(Reason.UNKNOWN_TYPE)
        }
    }

    private fun Long.toIntOrBad(): Int = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else bad(Reason.OUT_OF_RANGE)

    private class Rd(private val o: JObject) {
        fun str(k: String): String = when (val v = o[k]) {
            null -> bad(Reason.MISSING_MEMBER)
            is JString -> v.value
            else -> bad(Reason.WRONG_TYPE)
        }

        fun strOrNull(k: String): String? = when (val v = o[k]) {
            null -> null
            is JString -> v.value
            else -> bad(Reason.WRONG_TYPE)
        }

        fun int(k: String): Long = when (val v = o[k]) {
            null -> bad(Reason.MISSING_MEMBER)
            is JInt -> v.value
            else -> bad(Reason.WRONG_TYPE)
        }

        fun intOrNull(k: String): Long? = when (val v = o[k]) {
            null -> null
            is JInt -> v.value
            else -> bad(Reason.WRONG_TYPE)
        }

        fun bool(k: String): Boolean = when (val v = o[k]) {
            null -> bad(Reason.MISSING_MEMBER)
            is JBool -> v.value
            else -> bad(Reason.WRONG_TYPE)
        }

        fun obj(k: String): Rd = when (val v = o[k]) {
            null -> bad(Reason.MISSING_MEMBER)
            is JObject -> Rd(v)
            else -> bad(Reason.WRONG_TYPE)
        }

        fun <E> enum(k: String, all: Iterable<E>): E where E : Enum<E>, E : WireEnum = all.byWire(str(k)) ?: bad(Reason.UNKNOWN_ENUM)

        private fun array(k: String): List<JValue> = when (val v = o[k]) {
            null -> bad(Reason.MISSING_MEMBER)
            is JArray -> v.items
            else -> bad(Reason.WRONG_TYPE)
        }

        /** A set of enumerated strings; an element this node does not know is dropped (a forward-compatible peer), a non-string element is refused. */
        fun <E> known(k: String, all: Iterable<E>): Set<E> where E : Enum<E>, E : WireEnum =
            array(k).mapNotNull { e -> all.byWire((e as? JString)?.value ?: bad(Reason.WRONG_TYPE)) }.toSet()

        fun endpoints(k: String): List<Endpoint> {
            val items = array(k)
            if (items.size > Rules.MAX_ENDPOINTS) bad(Reason.OUT_OF_RANGE)
            return items.map { e ->
                val r = Rd(e as? JObject ?: bad(Reason.WRONG_TYPE))
                val addr = r.str("addr")
                val port = r.int("port")
                val via = r.enum("via", Via.entries)
                if (port !in 1..65535) bad(Reason.OUT_OF_RANGE)
                Endpoint(addr, port.toInt(), via)
            }
        }

        fun st(): St? = when (val v = o["st"]) {
            null -> null
            is JObject -> {
                val r = Rd(v)
                val fsm = r.str("fsm").let { n -> xyz.mdhv.asom.lab.policy.Fsm.entries.firstOrNull { it.name == n } ?: bad(Reason.UNKNOWN_ENUM) }
                val gov = r.str("gov").let { n -> xyz.mdhv.asom.lab.policy.Governor.entries.firstOrNull { it.name == n } ?: bad(Reason.UNKNOWN_ENUM) }
                val qb = r.int("qb")
                val seq = r.int("seq")
                val tb = r.int("tb")
                if (qb !in 0..2 || tb !in 0..2) bad(Reason.OUT_OF_RANGE)
                St(fsm, gov, qb.toInt(), seq, tb.toInt())
            }
            JNull -> bad(Reason.WRONG_TYPE)
            else -> bad(Reason.WRONG_TYPE)
        }

        fun version() {
            if (int("v") != 1L) bad(Reason.BAD_VERSION)
        }
    }
}
