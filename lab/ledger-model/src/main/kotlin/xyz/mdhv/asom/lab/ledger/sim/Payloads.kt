package xyz.mdhv.asom.lab.ledger.sim

import java.util.Base64
import java.util.SplittableRandom
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.ledger.FrameKind
import xyz.mdhv.asom.lab.ledger.FrameSpec
import xyz.mdhv.asom.lab.ledger.ServedRecord

/** A frame plus its JSON payload text (null for raw-byte frames and for frames a vector states only by length). */
class Built(val frame: FrameSpec, val text: String?)

/**
 * Builds frames with the JSON shapes of LAB_SPEC 7.2, canonical form, so that payload lengths are real. There is no requestId member in any
 * shape (law L-L7); the HELLO carries the device display name, which a row must never hold (law L-L8).
 */
class PayloadFactory(private val rng: SplittableRandom, private val displayName: String = "Deck of Doom") {
    fun b64u(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { b -> for (i in b.indices) b[i] = rng.nextInt(256).toByte() })

    fun attemptId(): String = b64u(16)

    private fun json(kind: FrameKind, members: List<Pair<String, JValue>>, stream: Int, attemptId: String? = null, code: String? = null, joinId: String? = null): Built {
        val text = Jcs.serializeToString(JObject(members))
        return Built(FrameSpec(kind, text.toByteArray(Charsets.UTF_8).size, stream, attemptId, code, joinId), text)
    }

    fun raw(kind: FrameKind, bytes: Int, stream: Int, attemptId: String?): Built = Built(FrameSpec(kind, bytes, stream, attemptId), null)

    fun hello(sessionNonce: String): Built = json(
        FrameKind.HELLO,
        listOf(
            "endpoints" to JArray(emptyList()), "features" to JArray(listOf("infer.offer", "manifest", "state").map { JString(it) }), "keyTier" to JString("os-keystore"),
            "maxV" to JInt(1), "minV" to JInt(1), "name" to JString(displayName), "nodeId" to JString(b64u(32)), "platform" to JString("linux"),
            "proto" to JString("asom-mesh/1"), "sessionNonce" to JString(sessionNonce), "sw" to JString("asom/0.1"), "ts" to JInt(1_790_000_000_000L), "v" to JInt(1),
        ),
        0, joinId = sessionNonce,
    )

    fun helloAck(): Built = json(
        FrameKind.HELLO_ACK,
        listOf(
            "endpoints" to JArray(emptyList()), "features" to JArray(emptyList()), "granted" to JArray(listOf("infer", "manifest", "state").map { JString(it) }),
            "limits" to JObject(listOf("idleUnloadMs" to JInt(300_000), "maxBodyBytes" to JInt(8_388_608), "maxConcurrent" to JInt(1), "maxTokens" to JInt(4096), "rpm" to JInt(30))),
            "nodeId" to JString(b64u(32)), "ts" to JInt(1_790_000_000_001L), "v" to JInt(1),
        ),
        0,
    )

    fun stateReq(stream: Int): Built = json(FrameKind.STATE_REQ, listOf("v" to JInt(1)), stream)

    fun state(stream: Int): Built = json(
        FrameKind.STATE,
        listOf(
            "availability" to JObject(listOf("fsm" to JString("SERVING"))), "power" to JObject(listOf("batteryBand" to xyz.mdhv.asom.lab.json.JNull, "charging" to JBool(false), "source" to JString("ac"))),
            "queue" to JObject(listOf("bucket" to JInt(0))), "sampledAgeMs" to JInt(800), "seq" to JInt(4711), "thermal" to JObject(listOf("band" to JInt(0), "governor" to JString("RUN"))), "v" to JInt(1),
        ),
        stream,
    )

    fun manifestReq(stream: Int): Built = json(FrameKind.MANIFEST_REQ, listOf("challenge" to JString(b64u(32)), "v" to JInt(1)), stream)

    fun manifest(stream: Int, extra: Int): Built = json(FrameKind.MANIFEST, listOf("container" to JString("x".repeat(extra)), "v" to JInt(1)), stream, code = "verified")

    fun goaway(reason: String): Built = json(FrameKind.GOAWAY, listOf("reason" to JString(reason)), 0, code = reason)

    fun error(code: String, stream: Int, attemptId: String? = null): Built =
        json(FrameKind.ERROR, listOfNotNull(attemptId?.let { "attemptId" to JString(it) }, "code" to JString(code)), stream, attemptId, code)

    fun revoke(): Built = json(FrameKind.REVOKE_NOTICE, listOf("reason" to JString("user"), "v" to JInt(1)), 0)

    fun pair(kind: FrameKind, joinId: String? = null): Built = json(kind, listOf("v" to JInt(1), "n" to JString(b64u(16))), 1, joinId = joinId)

    fun extension(bytes: Int): Built = raw(FrameKind.EXTENSION, bytes, 0, null)

    fun offer(attemptId: String, stream: Int, model: String, promptBytes: Int): Built = json(
        FrameKind.INFER_OFFER,
        listOf(
            "attemptId" to JString(attemptId), "deadlineMs" to JInt(120_000), "estTokensIn" to JInt(promptBytes / 4L), "maxTokens" to JInt(512), "model" to JString(model),
            "op" to JString("chat"), "promptBytes" to JInt(promptBytes.toLong()), "stream" to JBool(true),
        ),
        stream, attemptId,
    )

    fun accept(attemptId: String, stream: Int, model: String): Built = json(
        FrameKind.INFER_ACCEPT, listOf("attemptId" to JString(attemptId), "fileSha256" to JString("a".repeat(64)), "servedModel" to JString(model)), stream, attemptId,
    )

    fun decline(attemptId: String, stream: Int, code: String): Built = json(
        FrameKind.INFER_DECLINE, listOf("attemptId" to JString(attemptId), "code" to JString(code), "retryAfterMs" to JInt(30_000)), stream, attemptId, code,
    )

    fun head(attemptId: String, stream: Int, served: ServedRecord): Built {
        val text = Jcs.serializeToString(served.headPayload(attemptId))
        return Built(FrameSpec(FrameKind.INFER_HEAD, text.toByteArray(Charsets.UTF_8).size, stream, attemptId), text)
    }

    fun end(attemptId: String, stream: Int, served: ServedRecord): Built {
        val text = Jcs.serializeToString(served.endPayload(attemptId))
        return Built(FrameSpec(FrameKind.INFER_END, text.toByteArray(Charsets.UTF_8).size, stream, attemptId), text)
    }

    fun cancel(attemptId: String, stream: Int, reason: String): Built =
        json(FrameKind.CANCEL, listOf("attemptId" to JString(attemptId), "reason" to JString(reason)), stream, attemptId)
}
