package xyz.mdhv.asom.lab.manifest

import java.security.MessageDigest
import xyz.mdhv.asom.lab.bench.Audience
import xyz.mdhv.asom.lab.bench.BenchArithmeticException
import xyz.mdhv.asom.lab.bench.Checked
import xyz.mdhv.asom.lab.bench.Derive
import xyz.mdhv.asom.lab.bench.Project
import xyz.mdhv.asom.lab.bench.SchemaViolation
import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.JsonRejectCode
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/** Every typed reject of the r3 verifier (LAB_SPEC 4.6). `KEY_CHANGED` and the TOFU states are gone in r3. */
enum class RejectCode {
    TOO_LARGE, MALFORMED_JSON, INVALID_UNICODE, NON_INTEGER_NUMBER, NUMBER_RANGE, DUPLICATE_KEY, TRAILING_DATA,
    CONTAINER_VERSION_UNKNOWN, CONTAINER_INVALID, SCHEMA_MAJOR_UNKNOWN, PAYLOAD_TYPE_UNSUPPORTED, SIGNATURE_COUNT, ENCODING,
    SIGNATURE_ENCODING, KEY_NOT_PINNED, ALG_UNSUPPORTED, TEST_ONLY_KEY, FINGERPRINT_MISMATCH, SIGNATURE_INVALID, NON_CANONICAL,
    SCHEMA_INVALID, SUBJECT_KEY_MISMATCH, NOT_YET_VALID, EXPIRED, TTL_INVALID, NONCE_MISMATCH, INCONSISTENT, DERIVATION_MISMATCH,
    AUDIENCE_MISMATCH, ROLLBACK, EQUIVOCATION, TIER_INSUFFICIENT,
}

enum class Mode { MESH, FILE }

enum class Tier { A0, A1, A2 }

class RollbackEntry(val seq: Long, val bodyDigest: String)

/** Keyed by (peer nodeId, audience). The verifier only reads it; committing after a successful verify is the caller's act (LAB_SPEC 4.6 step 19). */
interface RollbackStore {
    fun get(nodeId: String, audience: String): RollbackEntry?
}

class InMemoryRollbackStore(private val map: MutableMap<Pair<String, String>, RollbackEntry> = HashMap()) : RollbackStore {
    override fun get(nodeId: String, audience: String): RollbackEntry? = map[nodeId to audience]

    fun commit(update: RollbackUpdate) {
        val prev = map[update.nodeId to update.audience]
        if (prev == null || update.seq > prev.seq) map[update.nodeId to update.audience] = RollbackEntry(update.seq, update.bodyDigest)
    }
}

class RollbackUpdate(val nodeId: String, val audience: String, val seq: Long, val bodyDigest: String)

/** How the user compared the signer's fingerprint (FILE context). */
enum class CompareMethod(val wire: String) { QR("qr"), TYPED("typed") }

class VerifyContext(
    val mode: Mode,
    val pinnedSpki: ByteArray? = null,
    val expectedChallenge: ByteArray? = null,
    val comparedFingerprint: String? = null,
    val compareMethod: CompareMethod? = null,
    val rollback: RollbackStore? = null,
    val requiredTier: Tier = Tier.A0,
    /** The lowest `confVersion` a document may pin. No compiled-in default exists (ERRATA ERR-CLOSURE-5): the caller supplies it. */
    val confFloor: String,
    val knownBadConf: Set<String> = emptySet(),
    val productionKeys: Boolean = true,
    val nowMs: Long,
) {
    init {
        require(comparedFingerprint == null || compareMethod != null) { "a compared fingerprint needs a compare method" }
    }
}

/** How the signer was established: `PINNED` (mesh), `PINNED_BY_FINGERPRINT(typed|qr)` or `SIGNER_UNVERIFIED` (file). */
sealed class PinState(val wire: String) {
    data object Pinned : PinState("PINNED")
    class ByFingerprint(val method: CompareMethod) : PinState("PINNED_BY_FINGERPRINT(${method.wire})")
    data object SignerUnverified : PinState("SIGNER_UNVERIFIED")
}

sealed interface VerifyResult

class Verified(
    val payloadBytes: ByteArray,
    val obj: ManifestObj,
    val bodyDigest: String,
    val pin: PinState,
    val tier: Tier,
    val unknownFields: Int,
    val signerSpki: ByteArray,
    val rollbackUpdate: RollbackUpdate?,
) : VerifyResult

/** [step] is the verifier step that failed (`"8"`, `"15a"`, ...). [obj] is present only when the payload had been decoded (steps 12 onward). */
class Rejected(val code: RejectCode, val step: String, val detail: String = "", val obj: ManifestObj? = null, val signerSpki: ByteArray? = null) : VerifyResult {
    /** A human viewer may display a report rejected only at steps 13-16 (LAB_SPEC 4.6 display rule). */
    val displayable: Boolean get() = step.takeWhile { it.isDigit() }.toIntOrNull() in 13..16
}

/**
 * The r3 verifier, in the normative step order of LAB_SPEC 4.6. It fails closed with the FIRST failing step's code, never trusts an
 * unauthenticated field (`keyid` and `signer.spki` are hints), reads `dsse.payload` only until step 8 and only the verified bytes after
 * it, and commits nothing on a reject.
 */
object Verifier {
    private val manifestTypeRx = Regex("application/vnd\\.asom\\.manifest\\.v([0-9]+)\\+json")
    private val schemaRx = Regex("asom\\.manifest/([0-9]+)")
    private val HW_STORAGE = setOf("strongbox", "tee", "secure-enclave", "tpm")
    private const val FUTURE_SKEW_MS = 300_000L
    private const val OWN_MAX_TTL_MS = 600_000L
    private const val EVIDENCE_ITEM_MAX_BYTES = 32_768

    private fun reject(code: RejectCode, step: String, detail: String = "", obj: ManifestObj? = null, spki: ByteArray? = null) = Rejected(code, step, detail, obj, spki)

    private fun parseCode(c: JsonRejectCode): RejectCode = RejectCode.valueOf(c.name)

    /** `verifyManifest(doc, ctx)`. Any thrown [BenchArithmeticException] is an `INCONSISTENT` (checked arithmetic, LAB_SPEC 4.6). */
    fun verify(doc: ByteArray, ctx: VerifyContext): VerifyResult {
        // 1
        if (doc.size > Dsse.MAX_CONTAINER_BYTES) return reject(RejectCode.TOO_LARGE, "1")
        // 2
        val c = when (val p = StrictJson.parse(doc)) {
            is ParseResult.Ok -> p.value
            is ParseResult.Reject -> return reject(parseCode(p.code), "2", p.detail)
        }
        // 3
        val cont = c as? JObject
        if (cont == null || (cont["asomCapabilityManifest"] as? JInt)?.value != 1L) return reject(RejectCode.CONTAINER_VERSION_UNKNOWN, "3")
        val dsse = cont["dsse"] as? JObject
        val payloadType = (dsse?.get("payloadType") as? JString)?.value
        val payloadB64 = (dsse?.get("payload") as? JString)?.value
        val sigs = (dsse?.get("signatures") as? JArray)?.items
        if (dsse == null || payloadType == null || payloadB64 == null || sigs == null) return reject(RejectCode.CONTAINER_INVALID, "3")
        // 4
        if (payloadType != Dsse.PT_MANIFEST_V1) {
            val m = manifestTypeRx.matchEntire(payloadType)
            val major = m?.groupValues?.get(1)?.toBigInteger()
            return if (major != null && major > java.math.BigInteger.ONE) reject(RejectCode.SCHEMA_MAJOR_UNKNOWN, "4") else reject(RejectCode.PAYLOAD_TYPE_UNSUPPORTED, "4")
        }
        // 5
        if (sigs.size != 1) return reject(RejectCode.SIGNATURE_COUNT, "5")
        val sig0 = sigs[0] as? JObject
        val sigB64 = (sig0?.get("sig") as? JString)?.value ?: return reject(RejectCode.CONTAINER_INVALID, "5")
        val keyidMember = sig0["keyid"]
        if (keyidMember != null && keyidMember !is JString) return reject(RejectCode.CONTAINER_INVALID, "5")
        val keyid = (keyidMember as? JString)?.value
        // 6
        val payload = when (val r = Base64Strict.decodeEither(payloadB64)) {
            is B64Result.Ok -> r.bytes
            is B64Result.Reject -> return reject(RejectCode.ENCODING, "6", "payload: ${r.reason}")
        }
        val sig = when (val r = Base64Strict.decodeEither(sigB64)) {
            is B64Result.Ok -> r.bytes
            is B64Result.Reject -> return reject(RejectCode.ENCODING, "6", "sig: ${r.reason}")
        }
        if (sig.size != 64) return reject(RejectCode.SIGNATURE_ENCODING, "6")
        if (payload.size > Dsse.MAX_PAYLOAD_BYTES) return reject(RejectCode.TOO_LARGE, "6", "decoded payload over 256 KiB")
        // 7
        val spki: ByteArray
        when (ctx.mode) {
            Mode.MESH -> {
                spki = ctx.pinnedSpki ?: return reject(RejectCode.KEY_NOT_PINNED, "7", "no pinned key")
                if (keyid != null && keyid != Spki.nodeId(spki)) return reject(RejectCode.KEY_NOT_PINNED, "7", "keyid is not the pinned node")
            }
            Mode.FILE -> {
                val signer = cont["signer"] as? JObject
                val spkiB64 = (signer?.get("spki") as? JString)?.value ?: return reject(RejectCode.KEY_NOT_PINNED, "7", "no signer.spki")
                spki = when (val r = Base64Strict.decodeEither(spkiB64)) {
                    is B64Result.Ok -> r.bytes
                    is B64Result.Reject -> return reject(RejectCode.ENCODING, "7", "signer.spki: ${r.reason}")
                }
                if (keyid != null && keyid != Spki.nodeId(spki)) return reject(RejectCode.KEY_NOT_PINNED, "7", "keyid is not the signer key")
            }
        }
        val key = Spki.strict(spki) ?: return reject(RejectCode.ALG_UNSUPPORTED, "7")
        val nodeId = Spki.nodeId(spki)
        if (ctx.productionKeys && TestOnlyKeys.isTestOnly(nodeId)) return reject(RejectCode.TEST_ONLY_KEY, "7")
        // 7b
        val pin: PinState
        if (ctx.mode == Mode.FILE) {
            val compared = ctx.comparedFingerprint
            if (compared != null) {
                val want = Spki.exportFingerprintPlain(spki).toByteArray(Charsets.US_ASCII)
                val got = Spki.normaliseFingerprint(compared).toByteArray(Charsets.UTF_8)
                if (!MessageDigest.isEqual(want, got)) return reject(RejectCode.FINGERPRINT_MISMATCH, "7b")
                pin = PinState.ByFingerprint(ctx.compareMethod!!)
            } else {
                pin = PinState.SignerUnverified
            }
        } else {
            pin = PinState.Pinned
        }
        // 8
        if (!Es256.rangeOk(sig) || !Es256.verify(key, Dsse.pae(Dsse.PT_MANIFEST_V1, payload), sig)) return reject(RejectCode.SIGNATURE_INVALID, "8")
        // ---- from here on only `payload` (the verified bytes) is read ----
        // 9
        val o = when (val p = StrictJson.parse(payload)) {
            is ParseResult.Ok -> p.value
            is ParseResult.Reject -> return reject(parseCode(p.code), "9", p.detail)
        }
        // 10
        if (!Jcs.serialize(o).contentEquals(payload)) return reject(RejectCode.NON_CANONICAL, "10")
        // 11
        val schema = ((o as? JObject)?.get("schema") as? JString)?.value ?: return reject(RejectCode.SCHEMA_INVALID, "11", "no schema")
        if (schema != "asom.manifest/1") {
            val major = schemaRx.matchEntire(schema)?.groupValues?.get(1)?.toBigInteger()
            return if (major != null && major > java.math.BigInteger.ONE) reject(RejectCode.SCHEMA_MAJOR_UNKNOWN, "11") else reject(RejectCode.SCHEMA_INVALID, "11", "schema $schema")
        }
        val obj = try {
            ManifestDecoder.decode(o)
        } catch (e: SchemaViolation) {
            return reject(RejectCode.SCHEMA_INVALID, "11", e.message ?: "")
        }
        try {
            return afterDecode(ctx, cont, obj, payload, pin, spki, nodeId)
        } catch (e: BenchArithmeticException) {
            return reject(RejectCode.INCONSISTENT, "15", "checked arithmetic: ${e.message}", obj, spki)
        }
    }

    private fun afterDecode(ctx: VerifyContext, cont: JObject, obj: ManifestObj, payload: ByteArray, pin: PinState, spki: ByteArray, nodeId: String): VerifyResult {
        val body = obj.body
        val pres = obj.presentation
        // 12
        if (body.subject.nodeId != nodeId) return reject(RejectCode.SUBJECT_KEY_MISMATCH, "12", obj = obj, spki = spki)
        // 13
        if (pres.issuedAtMs > Checked.add(ctx.nowMs, FUTURE_SKEW_MS)) return reject(RejectCode.NOT_YET_VALID, "13", obj = obj, spki = spki)
        if (body.audience == Audience.OWN) {
            val exp = pres.expiresAtMs!!
            if (ctx.nowMs >= exp) return reject(RejectCode.EXPIRED, "13", obj = obj, spki = spki)
            val ttl = exp - pres.issuedAtMs
            if (ttl <= 0L || ttl > OWN_MAX_TTL_MS) return reject(RejectCode.TTL_INVALID, "13", obj = obj, spki = spki)
        }
        // 14
        if (ctx.mode == Mode.MESH) {
            val want = ctx.expectedChallenge
            val got = pres.challengeBytes
            if (want == null || got == null || !MessageDigest.isEqual(got, want)) return reject(RejectCode.NONCE_MISMATCH, "14", obj = obj, spki = spki)
        }
        // 15
        Consistency.check(obj)?.let { return reject(RejectCode.INCONSISTENT, "15", it, obj, spki) }
        // 15a
        val conf = body.bench.harness.confVersion
        if (Semver.compare(conf, ctx.confFloor) < 0 || conf in ctx.knownBadConf) return reject(RejectCode.DERIVATION_MISMATCH, "15a", "confVersion $conf", obj, spki)
        val expected = Project.results(Derive.derive(body.bench), body.audience)
        if (!Jcs.serialize(body.resultsJson).contentEquals(Jcs.serialize(xyz.mdhv.asom.lab.bench.ja(expected)))) {
            return reject(RejectCode.DERIVATION_MISMATCH, "15a", "results differ from project(derive(bench))", obj, spki)
        }
        // 15b
        val wantAudience = if (ctx.mode == Mode.MESH) Audience.OWN else Audience.FILE
        if (body.audience != wantAudience) return reject(RejectCode.AUDIENCE_MISMATCH, "15b", obj = obj, spki = spki)
        // 15c
        val evidence = cont["evidence"]
        if (evidence != null) {
            val items = (evidence as? JArray)?.items ?: return reject(RejectCode.CONTAINER_INVALID, "15c", "evidence is not an array", obj, spki)
            if (items.size > 2 || items.any { it !is JObject || Jcs.serialize(it).size > EVIDENCE_ITEM_MAX_BYTES }) return reject(RejectCode.CONTAINER_INVALID, "15c", "evidence", obj, spki)
        }
        // 16
        val digest = bodyDigest(body.bodyJson)
        var update: RollbackUpdate? = null
        if (ctx.mode == Mode.MESH) {
            val seq = body.seq!!
            val prev = ctx.rollback?.get(nodeId, "own")
            if (prev != null) {
                if (seq < prev.seq) return reject(RejectCode.ROLLBACK, "16", obj = obj, spki = spki)
                if (seq == prev.seq && digest != prev.bodyDigest) return reject(RejectCode.EQUIVOCATION, "16", obj = obj, spki = spki)
            }
            update = RollbackUpdate(nodeId, "own", maxOf(seq, prev?.seq ?: seq), if (prev != null && prev.seq > seq) prev.bodyDigest else digest)
        }
        // 17
        val tier = if (body.subject.keyStorage in HW_STORAGE) Tier.A1 else Tier.A0
        // 18
        if (tier.ordinal < ctx.requiredTier.ordinal) return reject(RejectCode.TIER_INSUFFICIENT, "18", obj = obj, spki = spki)
        // 19 (the caller commits `update`; the library default is display-only)
        return Verified(payload, obj, digest, pin, tier, obj.unknownFields, spki, update)
    }

    /** `D = b64url(SHA-256(JCS(body)))`. */
    fun bodyDigest(body: JValue): String = Base64Strict.encodeUrlNoPad(MessageDigest.getInstance("SHA-256").digest(Jcs.serialize(body)))
}

object Semver {
    /** Compares two `MAJOR.MINOR.PATCH` strings numerically. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toLong() }
        val y = b.split('.').map { it.toLong() }
        for (i in 0 until 3) {
            val d = x[i].compareTo(y[i])
            if (d != 0) return d
        }
        return 0
    }
}
