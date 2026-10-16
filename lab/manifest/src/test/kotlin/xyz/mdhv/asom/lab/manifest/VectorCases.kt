package xyz.mdhv.asom.lab.manifest

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import xyz.mdhv.asom.lab.bench.ja
import xyz.mdhv.asom.lab.bench.jb
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.bench.jo
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.bench.jsList
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/** A verifier context, in both forms: the live [VerifyContext] and its JSON for a vector file. */
class VCtx(
    val mode: Mode,
    val pinned: ByteArray? = null,
    val challenge: ByteArray? = null,
    val compared: String? = null,
    val method: CompareMethod? = null,
    val rollback: Map<String, RollbackEntry> = emptyMap(),
    val requiredTier: Tier = Tier.A0,
    val confFloor: String = F.CONF,
    val knownBad: Set<String> = emptySet(),
    val production: Boolean = false,
) {
    fun toVerifyContext(nowMs: Long): VerifyContext {
        val store = object : RollbackStore {
            override fun get(nodeId: String, audience: String): RollbackEntry? = rollback["$nodeId|$audience"]
        }
        return VerifyContext(mode, pinned, challenge, compared, method, if (rollback.isEmpty()) null else store, requiredTier, confFloor, knownBad, production, nowMs)
    }

    fun toJson(): JValue {
        val m = mutableListOf<Pair<String, JValue>>("mode" to js(mode.name))
        pinned?.let { m += "pinnedSpkiB64" to js(Dsse.b64(it)) }
        challenge?.let { m += "expectedChallengeB64u" to js(Base64Strict.encodeUrlNoPad(it)) }
        compared?.let { m += "comparedFingerprint" to js(it) }
        method?.let { m += "compareMethod" to js(it.wire) }
        if (rollback.isNotEmpty()) m += "rollback" to jo(rollback.entries.sortedBy { it.key }.map { (k, v) -> k to jo("seq" to ji(v.seq), "bodyDigest" to js(v.bodyDigest)) })
        m += "requiredTier" to js(requiredTier.name)
        m += "confFloor" to js(confFloor)
        m += "knownBadConf" to jsList(knownBad.sorted())
        m += "productionKeys" to jb(production)
        return jo(m)
    }
}

/** One verifier vector: the document bytes (or a fill), the context, the clock, and the hand-written expectation. */
class Case(
    val id: String,
    val description: String,
    val doc: ByteArray,
    val ctx: VCtx,
    val nowMs: Long,
    /** Null means the verifier must accept. */
    val reject: RejectCode? = null,
    val step: String? = null,
    val fill: Pair<Int, Int>? = null,
)

object VectorJson {
    fun envelope(family: String, specRefs: List<String>, vectors: List<JValue>): JValue = jo(
        "family" to js(family), "confVersion" to js(F.CONF), "specRefs" to jsList(specRefs), "vectors" to ja(vectors),
    )

    fun vector(id: String, description: String, input: JValue, expect: JValue, extra: List<Pair<String, JValue>> = emptyList()): JValue = jo(
        listOf("id" to js(id), "origin" to js("generated"), "status" to js("normative"), "oracle" to js("self"), "description" to js(description), "input" to input) +
            extra + listOf("expect" to expect),
    )

    fun utf8OrNull(b: ByteArray): String? {
        val dec = Charsets.UTF_8.newDecoder()
        return try {
            dec.decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    /**
     * The signature layer, computed by a path that shares no code with the verifier: lenient JDK base64, the DSSE PAE built by hand, and the
     * JDK's P1363 verify under the key the verifier would have used. Null when the document does not reach a checkable signature.
     */
    fun sigLayer(c: Case): JValue? {
        val text = utf8OrNull(c.doc) ?: return null
        val root = (StrictJson.parse(c.doc) as? ParseResult.Ok)?.value as? JObject ?: return null
        val dsse = root["dsse"] as? JObject ?: return null
        val type = (dsse["payloadType"] as? JString)?.value ?: return null
        val payloadB64 = (dsse["payload"] as? JString)?.value ?: return null
        val sig0 = ((dsse["signatures"] as? JArray)?.items?.singleOrNull() as? JObject) ?: return null
        val sigB64 = (sig0["sig"] as? JString)?.value ?: return null
        fun lenient(s: String): ByteArray? = runCatching {
            val t = s.replace('-', '+').replace('_', '/').trimEnd('=')
            Base64.getDecoder().decode(t + "=".repeat((4 - t.length % 4) % 4))
        }.getOrNull()
        val payload = lenient(payloadB64) ?: return null
        val sig = lenient(sigB64) ?: return null
        val spki: ByteArray = if (c.ctx.mode == Mode.MESH) c.ctx.pinned ?: return null else {
            val s = ((root["signer"] as? JObject)?.get("spki") as? JString)?.value ?: return null
            lenient(s) ?: return null
        }
        if (sig.size != 64) return null
        val t = type.toByteArray(Charsets.UTF_8)
        val pae = "DSSEv1 ${t.size} ".toByteArray(Charsets.US_ASCII) + t + " ${payload.size} ".toByteArray(Charsets.US_ASCII) + payload
        val ok = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(key); update(pae); verify(sig) }
        } catch (e: Exception) {
            return null
        }
        check(text.isNotEmpty())
        return jo("spkiB64" to js(Dsse.b64(spki)), "verifies" to jb(ok))
    }

    fun caseJson(c: Case, actual: VerifyResult): JValue {
        val input = mutableListOf<Pair<String, JValue>>()
        if (c.fill != null) {
            input += "documentFill" to jo("byte" to ji(c.fill.first), "count" to ji(c.fill.second))
        } else {
            val text = utf8OrNull(c.doc)
            if (text != null) input += "document" to js(text) else input += "documentHex" to js(Hex.encode(c.doc))
        }
        input += "context" to c.ctx.toJson()
        input += "nowMs" to ji(c.nowMs)
        val expect: JValue = when (actual) {
            is Verified -> jo(
                "ok" to jo(
                    "pin" to js(actual.pin.wire), "tier" to js(actual.tier.name), "seq" to (actual.obj.body.seq?.let { ji(it) } ?: JNull),
                    "bodyDigest" to js(actual.bodyDigest), "unknownFields" to ji(actual.unknownFields),
                ),
            )
            is Rejected -> jo("reject" to js(actual.code.name))
        }
        val extra = mutableListOf<Pair<String, JValue>>()
        if (actual is Rejected) extra += "expectDetail" to jo("step" to js(actual.step))
        if (c.fill == null) sigLayer(c)?.let { extra += "sigLayer" to it }
        return vector(c.id, c.description, jo(input), expect, extra)
    }
}

fun sha256Hex(b: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(b))
