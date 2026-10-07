package xyz.mdhv.asom.lab.manifest

import xyz.mdhv.asom.lab.bench.BenchCodec
import xyz.mdhv.asom.lab.bench.Scenarios
import xyz.mdhv.asom.lab.bench.ja
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.bench.jo
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.bench.jsList
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import java.math.BigInteger
import java.security.MessageDigest

/** The M02 and M03 cases of LAB_SPEC 4.9, regenerated for r3. Every expectation below is written by hand from the spec text; the generator refuses to write a file when the implementation disagrees. */
object ManifestCases {
    private val key1 = F.key1
    private val key2 = F.key2
    private val key3 = F.key3
    private val key4 = F.key4

    private fun mesh(
        pinned: TestOnlyKeys.Entry? = key1, challenge: ByteArray? = F.challenge1, rollback: Map<String, RollbackEntry> = emptyMap(), tier: Tier = Tier.A0,
        production: Boolean = false, knownBad: Set<String> = emptySet(),
    ) = VCtx(Mode.MESH, pinned?.spki, challenge, rollback = rollback, requiredTier = tier, production = production, knownBad = knownBad)

    private fun file(compared: String? = null, method: CompareMethod? = null, production: Boolean = false, tier: Tier = Tier.A0) =
        VCtx(Mode.FILE, compared = compared, method = method, production = production, requiredTier = tier)

    private fun pay(o: JValue) = F.payloadOf(o)

    private fun own(o: JValue = F.ownObj(), key: TestOnlyKeys.Entry = key1, spec: (DocSpec) -> DocSpec = { it }): ByteArray = F.container(spec(DocSpec(pay(o), signKey = key)))

    private fun fileDoc(o: JValue = F.fileObj(), key: TestOnlyKeys.Entry = key3, spec: (DocSpec) -> DocSpec = { it }): ByteArray = F.container(spec(DocSpec(pay(o), signKey = key)))

    private fun copy(s: DocSpec, payload: ByteArray = s.payload, signKey: TestOnlyKeys.Entry = s.signKey, type: String = s.type, keyid: String? = s.keyid, signerSpki: ByteArray? = s.signerSpki,
                     sig: ByteArray? = s.sig, extraSigs: Int = s.extraSigs, encoder: (ByteArray) -> String = s.encoder, highS: Boolean = s.highS, evidence: List<JValue>? = s.evidence,
                     signOver: ByteArray? = s.signOver, sigText: String? = s.sigText, payloadText: String? = s.payloadText) =
        DocSpec(payload, signKey, type, keyid, signerSpki, sig, extraSigs, encoder, highS, evidence, signOver, sigText, payloadText)

    private fun astral(n: Int): String = "😀".repeat(n)

    private fun long(o: JValue, path: List<Any>): Long = (JEdit.get(o, path) as JInt).value

    private val ownObj get() = F.ownObj()

    /** Compact JSON with the members in their given order (not JCS): for the non-canonical vector. */
    fun compact(v: JValue): String = when (v) {
        is JObject -> v.members.joinToString(",", "{", "}") { (k, x) -> "\"$k\":${compact(x)}" }
        is JArray -> v.items.joinToString(",", "[", "]") { compact(it) }
        is JString -> "\"" + v.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is JInt -> v.value.toString()
        is JNull -> "null"
        is xyz.mdhv.asom.lab.json.JBool -> v.value.toString()
    }

    private fun addOne(o: JValue, path: List<Any>, delta: Long): JValue = JEdit.set(o, path, ji(long(o, path) + delta))

    fun accept(): List<Case> {
        val c = mutableListOf<Case>()
        val baseObj = ownObj
        val baseDoc = own(baseObj)
        val baseDigest = Verifier.bodyDigest(F.ownBody())
        c += Case("M02-101", "MESH, key1 pinned, challenge1, own audience: accepted, PINNED, tier A1 (StrongBox claimed, no evidence).", baseDoc, mesh(), F.NOW)
        c += Case("M02-104", "Boundary: nowMs == expiresAtMs - 1 accepts.", baseDoc, mesh(), F.ISSUED + 600_000L - 1)
        c += Case("M02-105", "High-S twin of M02-101 (s replaced by n - s): accepted, with the same bodyDigest (signature bytes are never an identifier).", own(baseObj) .let { F.container(DocSpec(pay(baseObj), highS = true)) }, mesh(), F.NOW)
        c += Case("M02-106", "URL-safe unpadded base64 for payload and signature: DSSE verifiers accept either alphabet.", F.container(DocSpec(pay(baseObj), encoder = { Base64Strict.encodeUrlNoPad(it) })), mesh(), F.NOW)
        c += Case(
            "M02-107", "schemaMinor 1 with one unknown additive member (body.device.npuOffloadPermille): accepted, unknownFields 1.",
            own(JEdit.set(JEdit.set(baseObj, listOf("schemaMinor"), ji(1)), listOf("body", "device", "npuOffloadPermille"), ji(0))), mesh(), F.NOW,
        )
        c += Case("M02-108", "The rollback store holds seq 16 for this node and audience: seq 17 accepts.", baseDoc, mesh(rollback = mapOf("${key1.nodeId}|own" to RollbackEntry(F.SEQ - 1, "x".repeat(43)))), F.NOW)
        c += Case("M02-109", "The rollback store holds seq 17 with the SAME body digest (a re-presentation with a new challenge): accepted.", baseDoc, mesh(rollback = mapOf("${key1.nodeId}|own" to RollbackEntry(F.SEQ, baseDigest))), F.NOW)
        c += Case("M02-110", "FILE, per-export key3, fingerprint not compared: accepted as SIGNER_UNVERIFIED, tier A0.", fileDoc(), file(), F.NOW)
        c += Case("M02-111", "FILE, the export fingerprint typed exactly: PINNED_BY_FINGERPRINT(typed).", fileDoc(), file(key3.exportFingerprint, CompareMethod.TYPED), F.NOW)
        c += Case("M02-112", "FILE, the fingerprint scanned, lowercase with spaces: PINNED_BY_FINGERPRINT(qr).", fileDoc(), file(key3.exportFingerprint.lowercase().replace("-", " "), CompareMethod.QR), F.NOW)
        c += Case(
            "M02-113", "device.model of exactly 96 astral characters (192 UTF-16 code units), in body.device and in its bench twin: accepted, because lengths are counted in code points.",
            own(JEdit.set(JEdit.set(baseObj, listOf("body", "device", "model"), js(astral(96))), listOf("body", "bench", "device", "model"), js(astral(96)))), mesh(), F.NOW,
        )
        c += Case("M02-114", "The maxima: every rate 10^9, every byte field 2^50: accepted.", own(F.ownObj(F.ownBody(inputs = F.inputs(maxima())))), mesh(), F.NOW)
        c += Case("M02-115", "FILE, per-export key4, the export fingerprint typed with its hyphens: PINNED_BY_FINGERPRINT(typed).", fileDoc(F.fileObj(F.fileBody(key4)), key4), file(key4.exportFingerprint, CompareMethod.TYPED), F.NOW)
        c += Case("M02-116", "A partial run (aborted at a charger removal): the projected manifest still verifies.", own(F.ownObj(F.ownBody(inputs = F.inputs(partial())))), mesh(), F.NOW)
        c += Case("M02-117", "keyStorage os-keystore: accepted at tier A0 (self-reported, treated like A0).", own(F.ownObj(F.ownBody(storage = "os-keystore"))), mesh(), F.NOW)
        c += Case("M02-118", "keyStorage secure-enclave (a self-reported hardware claim) at requiredTier A0: accepted, labelled tier A1; ERR-FX-CV3 retag of the r3 vector that let the claim satisfy requiredTier A1 (see M03-191).", own(F.ownObj(F.ownBody(storage = "secure-enclave"))), mesh(), F.NOW)
        c += Case("M02-119", "Boundary: issuedAtMs == nowMs + 300,000 (exactly the allowed skew) accepts.", baseDoc, mesh(), F.ISSUED - 300_000L)
        c += Case("M02-120", "keyStorage tee (a self-reported hardware claim) at requiredTier A0: accepted, labelled tier A1; ERR-FX-CV3 retag (see M03-191).", own(F.ownObj(F.ownBody(storage = "tee"))), mesh(), F.NOW)
        c += Case("M02-122", "device.class toaster (an open enum value that is not a bench form): accepted, shown by a viewer as other (toaster); body.device.class is not tied to bench.device.form.", own(JEdit.set(baseObj, listOf("body", "device", "class"), js("toaster"))), mesh(), F.NOW)
        val fileMinor1 = JEdit.set(F.fileObj(), listOf("schemaMinor"), ji(1))
        c += Case("M02-121", "FILE, schemaMinor 1 and no extra presentation member: accepted as SIGNER_UNVERIFIED (the sibling of M03-193).", fileDoc(fileMinor1), file(), F.NOW)
        return c
    }

    fun reject(): List<Case> {
        val c = mutableListOf<Case>()
        val baseObj = ownObj
        val basePayload = pay(baseObj)
        val baseDoc = own(baseObj)
        val fileObj = F.fileObj()
        fun r(id: String, desc: String, doc: ByteArray, ctx: VCtx, code: RejectCode, step: String, now: Long = F.NOW) { c += Case(id, desc, doc, ctx, now, code, step) }
        fun rs(id: String, desc: String, spec: DocSpec, ctx: VCtx, code: RejectCode, step: String, now: Long = F.NOW) = r(id, desc, F.container(spec), ctx, code, step, now)

        // ---- signature layer
        val tampered = String(basePayload, Charsets.UTF_8).replaceFirst("\"genTokens\":128", "\"genTokens\":129").toByteArray()
        check(!tampered.contentEquals(basePayload))
        rs("M03-101", "One integer in the payload changed after signing (genTokens 128 -> 129).", DocSpec(tampered, signOver = Dsse.pae(Dsse.PT_MANIFEST_V1, basePayload)), mesh(), RejectCode.SIGNATURE_INVALID, "8")
        val goodSig = F.rawSig(key1, Dsse.pae(Dsse.PT_MANIFEST_V1, basePayload))
        val der = (SigCodec.rawToDer(goodSig) as SigCodec.Result.Ok).bytes
        rs("M03-102", "DER-encoded signature where the 64-octet raw r||s is required.", DocSpec(basePayload, sig = der), mesh(), RejectCode.SIGNATURE_ENCODING, "6")
        rs("M03-103", "MESH pinned to key1; a valid document signed by key2 (keyid = key2).", DocSpec(pay(JEdit.set(baseObj, listOf("body", "subject", "nodeId"), js(key2.nodeId))), signKey = key2), mesh(), RejectCode.KEY_NOT_PINNED, "7")
        val pretty = JsonTextNonCanonical.pretty(baseObj)
        rs("M03-104", "A validly signed payload that is NOT in canonical (JCS) form (member order and whitespace).", DocSpec(pretty), mesh(), RejectCode.NON_CANONICAL, "10")
        val dup = String(basePayload, Charsets.UTF_8).replaceFirst("{\"audience\":\"own\",\"bench\"", "{\"audience\":\"own\",\"audience\":\"file\",\"bench\"")
        rs("M03-105", "A validly signed payload with a duplicate member name (audience).", DocSpec(dup.toByteArray()), mesh(), RejectCode.DUPLICATE_KEY, "9")
        val flt = String(basePayload, Charsets.UTF_8).let { txt ->
            val m = Regex("\"steadyMilliTokPerSec\":([0-9]+)").find(txt)!!
            txt.replaceRange(m.range, "\"steadyMilliTokPerSec\":${m.groupValues[1]}.5")
        }
        rs("M03-106", "A validly signed payload holding a non-integer number.", DocSpec(flt.toByteArray()), mesh(), RejectCode.NON_INTEGER_NUMBER, "9")
        r("M03-107", "nowMs == expiresAtMs: expired.", baseDoc, mesh(), RejectCode.EXPIRED, "13", F.ISSUED + 600_000L)
        r("M03-108", "issuedAtMs more than 5 minutes ahead of the verifier clock.", baseDoc, mesh(), RejectCode.NOT_YET_VALID, "13", F.ISSUED - 300_001L)
        r("M03-109", "The challenge differs from the one this requester sent (a replayed presentation).", baseDoc, mesh(challenge = MessageDigest.getInstance("SHA-256").digest("another challenge".toByteArray())), RejectCode.NONCE_MISMATCH, "14")
        r("M03-110", "The requester expected a challenge but the own-audience presentation carries none.", own(JEdit.set(baseObj, listOf("presentation"), F.ownPresentation(challenge = null))), mesh(), RejectCode.NONCE_MISMATCH, "14")
        r("M03-111", "The rollback store holds seq 18: seq 17 is a rollback.", baseDoc, mesh(rollback = mapOf("${key1.nodeId}|own" to RollbackEntry(F.SEQ + 1, "x".repeat(43)))), RejectCode.ROLLBACK, "16")
        r("M03-112", "The rollback store holds seq 17 with a DIFFERENT body digest: two bodies under one seq.", baseDoc, mesh(rollback = mapOf("${key1.nodeId}|own" to RollbackEntry(F.SEQ, Base64Strict.encodeUrlNoPad(ByteArray(32))))), RejectCode.EQUIVOCATION, "16")
        rs("M03-113", "The envelope claims another asom payload type (key-rollover); the type is refused before any signature check.", DocSpec(basePayload, type = Dsse.PT_KEY_ROLLOVER_V1, signOver = Dsse.pae(Dsse.PT_MANIFEST_V1, basePayload)), mesh(), RejectCode.PAYLOAD_TYPE_UNSUPPORTED, "4")
        rs("M03-114", "A different payload type, correctly signed by the pinned key, cannot be replayed as a manifest.", DocSpec("{\"type\":\"x\"}".toByteArray(), type = Dsse.PT_KEY_ROLLOVER_V1), mesh(), RejectCode.PAYLOAD_TYPE_UNSUPPORTED, "4")
        r("M03-115", "The payload names another node as its subject but is signed by key1.", own(JEdit.set(baseObj, listOf("body", "subject", "nodeId"), js(key2.nodeId))), mesh(), RejectCode.SUBJECT_KEY_MISMATCH, "12")
        r("M03-116", "A challenge-bound presentation with a TTL over 10 minutes.", own(JEdit.set(baseObj, listOf("presentation"), F.ownPresentation(expires = F.ISSUED + 600_001L))), mesh(), RejectCode.TTL_INVALID, "13")
        rs("M03-117", "An envelope with two signature entries (the asom profile requires exactly one).", DocSpec(basePayload, extraSigs = 1), mesh(), RejectCode.SIGNATURE_COUNT, "5")
        rs("M03-118", "An unknown major version in the payload type.", DocSpec(basePayload, type = "application/vnd.asom.manifest.v2+json", signOver = Dsse.pae(Dsse.PT_MANIFEST_V1, basePayload)), mesh(), RejectCode.SCHEMA_MAJOR_UNKNOWN, "4")
        r("M03-119", "An unknown major version inside the signed payload (asom.manifest/2).", own(JEdit.set(baseObj, listOf("schema"), js("asom.manifest/2"))), mesh(), RejectCode.SCHEMA_MAJOR_UNKNOWN, "11")
        r("M03-120", "Internally inconsistent claim: the steady-state rate above every point of its own curve.", own(JEdit.set(baseObj, listOf("body", "results", 1, "sustained", "steadyMilliTokPerSec"), ji(99_999_999))), mesh(), RejectCode.INCONSISTENT, "15")
        val ttft = jo("p10" to ji(100_000), "p50" to ji(100_000), "p90" to ji(100_000))
        r("M03-121", "Internally inconsistent claim: time to first token shorter than the prompt time implied by its own prefill rate.", own(JEdit.set(baseObj, listOf("body", "results", 0, "prefill", 0, "ttftMicros"), ttft)), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-123", "Trailing data after the container JSON.", baseDoc + " {}".toByteArray(), mesh(), RejectCode.TRAILING_DATA, "2")
        r("M03-124", "requiredTier A2 but no platform evidence exists: tier A1 is insufficient.", baseDoc, mesh(tier = Tier.A2), RejectCode.TIER_INSUFFICIENT, "18")
        val stdSig = Dsse.b64(goodSig)
        rs("M03-125", "A signature whose base64 final character carries non-zero unused bits; rejected before any signature check.", DocSpec(basePayload, sigText = stdSig.dropLast(3) + "B=="), mesh(), RejectCode.ENCODING, "6")
        r("M03-126", "A bidi override character (U+202E) in a display string.", own(JEdit.set(baseObj, listOf("body", "device", "model"), js("Example Phone 1‮1 enohP"))), mesh(), RejectCode.SCHEMA_INVALID, "11")
        rs("M03-127", "FILE mode, the container has no signer.spki (relabelled from r0's TOFU case).", DocSpec(pay(fileObj), signKey = key3, signerSpki = null), file(), RejectCode.KEY_NOT_PINNED, "7")
        val flags = Regex("\"flags\":\\[[^\\]]*\\]").find(String(basePayload, Charsets.UTF_8))!!.value
        val deep = String(basePayload, Charsets.UTF_8).replaceFirst(flags, "\"flags\":" + "[".repeat(14) + "]".repeat(14))
        rs("M03-128", "A validly signed payload nested deeper than 16 levels (rejected by the strict parser before the schema).", DocSpec(deep.toByteArray()), mesh(), RejectCode.MALFORMED_JSON, "9")

        // ---- r3 additions
        rs("M03-129", "FILE: the fingerprint of key4 is compared against a file signed by key3.", DocSpec(pay(fileObj), signKey = key3), file(key4.exportFingerprint, CompareMethod.TYPED), RejectCode.FINGERPRINT_MISMATCH, "7b")
        val d0 = listOf<Any>("body", "results", 0, "decode", 0, "milliTokPerSec")
        val bumped = addOne(addOne(baseObj, d0 + "p50", 1), d0 + "p90", 1)
        r("M03-130", "results edited to disagree with derive(bench), consistent in itself, re-signed.", own(bumped), mesh(), RejectCode.DERIVATION_MISMATCH, "15a")
        r("M03-131", "The bench pins a confVersion (0.1.0) below the verifier's floor (0.2.0); the producer block says the same, so the one-record rule (M03-179) does not decide first.", own(JEdit.set(JEdit.set(baseObj, listOf("body", "bench", "harness", "confVersion"), js("0.1.0")), listOf("body", "producer", "harness", "confVersion"), js("0.1.0"))), mesh(), RejectCode.DERIVATION_MISMATCH, "15a")
        r("M03-132", "An own-audience document verified in FILE mode.", baseDoc, file(), RejectCode.AUDIENCE_MISMATCH, "15b")
        rs("M03-133", "Three evidence items.", DocSpec(basePayload, evidence = listOf(jo(), jo(), jo())), mesh(), RejectCode.CONTAINER_INVALID, "15c")
        r("M03-134", "A file-audience document carrying seq.", fileDoc(JEdit.set(fileObj, listOf("body", "seq"), ji(1))), file(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-135", "A file-audience document whose issuedAtMs is not day-truncated.", fileDoc(JEdit.set(fileObj, listOf("presentation"), F.filePresentation(F.FILE_DAY + 1000))), file(), RejectCode.SCHEMA_INVALID, "11")
        val compressed = Spki.PREFIX.copyOfRange(0, 3).let { byteArrayOf(0x30, 0x39) } + hex("301306072a8648ce3d020106082a8648ce3d030107032200") + byteArrayOf(if (key3.spki[90].toInt() and 1 == 0) 2 else 3) + key3.spki.copyOfRange(27, 59)
        check(compressed.size == 59)
        rs("M03-136", "A compressed-point SPKI (33-byte point) as the FILE signer key.", DocSpec(pay(fileObj), signKey = key3, signerSpki = compressed, keyid = Spki.nodeId(compressed)), file(), RejectCode.ALG_UNSUPPORTED, "7")
        val trailing = key3.spki + byteArrayOf(0)
        rs("M03-137", "A valid SPKI plus one trailing byte.", DocSpec(pay(fileObj), signKey = key3, signerSpki = trailing, keyid = Spki.nodeId(trailing)), file(), RejectCode.ALG_UNSUPPORTED, "7")
        r("M03-138", "device.model of 97 astral characters (over the 96 code point limit).", own(JEdit.set(baseObj, listOf("body", "device", "model"), js(astral(97)))), mesh(), RejectCode.SCHEMA_INVALID, "11")
        val zero = JEdit.set(baseObj, d0 + "p10", ji(0))
        r("M03-139", "A rate of 0.", own(zero), mesh(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-140", "A rate of 10^9 + 1.", own(JEdit.set(baseObj, d0 + "p90", ji(1_000_000_001))), mesh(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-141", "Production mode: key1 is a TEST-ONLY key.", baseDoc, mesh(production = true), RejectCode.TEST_ONLY_KEY, "7")
        r("M03-142", "A `derived` member inside body.bench.", own(JEdit.set(baseObj, listOf("body", "bench", "derived"), jo())), mesh(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-143", "A file-audience document presented in MESH mode (step 14 runs before 15b).", fileDoc(), VCtx(Mode.MESH, key3.spki, F.challenge1), RejectCode.NONCE_MISMATCH, "14")

        // ---- container, parser and envelope layers
        c += Case("M03-144", "A document of 524,289 bytes.", ByteArray(0), mesh(), F.NOW, RejectCode.TOO_LARGE, "1", fill = 32 to 524_289)
        r("M03-150", "Not one JSON value.", "[".toByteArray(), mesh(), RejectCode.MALFORMED_JSON, "2")
        r("M03-151", "Invalid UTF-8 inside a string of the container.", "{\"asomCapabilityManifest\":1,\"x\":\"".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\"}".toByteArray(), mesh(), RejectCode.INVALID_UNICODE, "2")
        r("M03-152", "An unknown container version.", "{\"asomCapabilityManifest\":2}".toByteArray(), mesh(), RejectCode.CONTAINER_VERSION_UNKNOWN, "3")
        r("M03-153", "A container whose dsse has no payload.", "{\"asomCapabilityManifest\":1,\"dsse\":{\"payloadType\":\"application/vnd.asom.manifest.v1+json\",\"signatures\":[]}}".toByteArray(), mesh(), RejectCode.CONTAINER_INVALID, "3")
        val big = String(basePayload, Charsets.UTF_8).replaceFirst("\"seq\":17", "\"seq\":9007199254740992")
        rs("M03-154", "A validly signed payload with an integer beyond 2^53 - 1.", DocSpec(big.toByteArray()), mesh(), RejectCode.NUMBER_RANGE, "9")
        rs("M03-155", "A signature of 64 zero bytes (r = 0 and s = 0 are out of range).", DocSpec(basePayload, sig = ByteArray(64)), mesh(), RejectCode.SIGNATURE_INVALID, "8")
        val sEqN = goodSig.copyOfRange(0, 32) + Es256.fixed32(P256.N)
        rs("M03-156", "A signature with s = n (out of range).", DocSpec(basePayload, sig = sEqN), mesh(), RejectCode.SIGNATURE_INVALID, "8")
        rs("M03-157", "FILE: the keyid is not the node id of signer.spki (a lie is a reject, never a hint).", DocSpec(pay(fileObj), signKey = key3, keyid = key4.nodeId), file(), RejectCode.KEY_NOT_PINNED, "7")
        r("M03-158", "An unknown member at schemaMinor 0 (unknown members are tolerated only above the known minor).", own(JEdit.set(baseObj, listOf("body", "device", "npuOffloadPermille"), ji(0))), mesh(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-159", "A forbidden member name (`field`) at schemaMinor 1, where unknown members are otherwise tolerated.", own(JEdit.set(JEdit.set(baseObj, listOf("schemaMinor"), ji(1)), listOf("body", "device", "field"), ji(0))), mesh(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-160", "FILE: subject.keyStorage strongbox (a hardware attestation field of the node key inside a file).", fileDoc(JEdit.set(fileObj, listOf("body", "subject", "keyStorage"), js("strongbox"))), file(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-161", "FILE: device.platformIds present.", fileDoc(JEdit.set(fileObj, listOf("body", "device", "platformIds"), jo("brand" to js("example")))), file(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-162", "FILE: os.securityPatch present.", fileDoc(JEdit.set(fileObj, listOf("body", "device", "os", "securityPatch"), js("2026-09-01"))), file(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-163", "FILE: a result's measuredAtMs that is not a multiple of 86,400,000.", fileDoc(addOne(fileObj, listOf("body", "results", 0, "measuredAtMs"), 1)), file(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-164", "FILE: bench.device.osBuild present (a stable identifier).", fileDoc(JEdit.set(fileObj, listOf("body", "bench", "device", "osBuild"), js("EXAMPLE.260901.001"))), file(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-165", "FILE: conditions.screenOn present.", fileDoc(JEdit.set(fileObj, listOf("body", "results", 0, "conditions", "screenOn"), jsonTrue())), file(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-166", "FILE: results that disagree with project(derive(bench)) although every rule of the file form holds.", fileDoc(addOne(fileObj, listOf("body", "results", 0, "measuredAtMs"), -86_400_000L)), file(), RejectCode.DERIVATION_MISMATCH, "15a")
        r("M03-167", "FILE in production mode: key3 is a TEST-ONLY key.", fileDoc(), file(production = true), RejectCode.TEST_ONLY_KEY, "7")
        val pfx = listOf<Any>("body", "results", 0, "prefill", 0)
        val big3 = JEdit.set(JEdit.set(baseObj, pfx + "ttftMicros", jo("p10" to ji(3_600_000_000L), "p50" to ji(3_600_000_000L), "p90" to ji(3_600_000_000L))), pfx + "milliTokPerSec", jo("p10" to ji(1_000_000_000L), "p50" to ji(1_000_000_000L), "p90" to ji(1_000_000_000L)))
        r("M03-169", "Checked arithmetic: a TTFT of 3.6e9 microseconds at 1e9 milli-tokens per second overflows the consistency product.", own(big3), mesh(), RejectCode.INCONSISTENT, "15")
        val reordered = compact(baseObj).toByteArray()
        rs("M03-170", "A validly signed payload whose members are not sorted (same values, other order).", DocSpec(reordered), mesh(), RejectCode.NON_CANONICAL, "10")
        rs("M03-171", "A payload whose base64 holds a character outside both alphabets.", DocSpec(basePayload, payloadText = "!!!!"), mesh(), RejectCode.ENCODING, "6")
        val offCurve = key1.spki.copyOf().also { it[90] = (it[90].toInt() xor 1).toByte() }
        rs("M03-172", "The pinned key has the right shape but its point is off the curve.", DocSpec(basePayload, keyid = null), VCtx(Mode.MESH, offCurve, F.challenge1), RejectCode.ALG_UNSUPPORTED, "7")
        r("M03-173", "bench.benchProtocol 2 (this verifier derives protocol 1 only).", own(JEdit.set(baseObj, listOf("body", "bench", "benchProtocol"), ji(2))), mesh(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-174", "bench.energy is not null (energy methods are deferred).", own(JEdit.set(baseObj, listOf("body", "bench", "energy"), jo())), mesh(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-175", "A bench tier whose sha256 differs from the compiled-in pin.", own(JEdit.set(baseObj, listOf("body", "bench", "tiers", 0, "sha256"), js("0".repeat(64)))), mesh(), RejectCode.SCHEMA_INVALID, "11")
        val slower = (JEdit.get(baseObj, listOf("body", "bench", "tiers", 1, "tests", 1, "samples")) as JArray).items.map { ji((it as JInt).value * 2) }
        r("M03-176", "Flattering summaries of unflattering samples: every tg128@d0 sample of tier T2 doubled after the results were computed.", own(JEdit.set(baseObj, listOf("body", "bench", "tiers", 1, "tests", 1, "samples"), ja(slower))), mesh(), RejectCode.DERIVATION_MISMATCH, "15a")
        r("M03-177", "The bench pins a confVersion that is on the known-bad list.", baseDoc, mesh(knownBad = setOf(F.CONF)), RejectCode.DERIVATION_MISMATCH, "15a")
        val wrapT = jo("p10" to ji(1_900_000_000L), "p50" to ji(1_900_000_000L), "p90" to ji(1_900_000_000L))
        val wrapR = jo("p10" to ji(1_000_000_000L), "p50" to ji(1_000_000_000L), "p90" to ji(1_000_000_000L))
        val wrap = JEdit.set(JEdit.set(baseObj, pfx + "ttftMicros", wrapT), pfx + "milliTokPerSec", wrapR)
        r("M03-178", "Checked arithmetic: 1.9e9 microseconds at 1e9 milli-tokens per second wraps a 64-bit product to a POSITIVE number that would pass the consistency test; it is INCONSISTENT, not accepted.", own(wrap), mesh(), RejectCode.INCONSISTENT, "15")

        return c
    }

    /**
     * The review-fix vectors (ERR-FX-CV1 .. CV10), M03-179 and up. They live in their own file (`M03-verify-reject-fx.json`, family M03) so that the
     * count of the original reject file, which the W08 session test pins, does not move.
     */
    fun rejectFx(): List<Case> {
        val c = mutableListOf<Case>()
        val baseObj = ownObj
        val basePayload = pay(baseObj)
        val baseDoc = own(baseObj)
        val fileObj = F.fileObj()
        fun r(id: String, desc: String, doc: ByteArray, ctx: VCtx, code: RejectCode, step: String, now: Long = F.NOW) { c += Case(id, desc, doc, ctx, now, code, step) }
        fun rs(id: String, desc: String, spec: DocSpec, ctx: VCtx, code: RejectCode, step: String, now: Long = F.NOW) = r(id, desc, F.container(spec), ctx, code, step, now)

        // ---- review fixes (ERR-FX-CV1 .. CV10)
        fun ownEdit(path: List<Any>, v: JValue) = own(JEdit.set(baseObj, path, v))
        r("M03-179", "One record: body.producer.harness.confVersion says 0.0.1 (below the floor) while body.bench pins 0.2.0; re-signed.", ownEdit(listOf("body", "producer", "harness", "confVersion"), js("0.0.1")), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-180", "One record: body.producer.engine.commit is not a prefix of bench.harness.engine.commit.", ownEdit(listOf("body", "producer", "engine", "commit"), js("ffffffff")), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-181", "One record: body.producer.engine.name differs from bench.harness.engine.name.", ownEdit(listOf("body", "producer", "engine", "name"), js("other-engine")), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-182", "One record: body.producer.engine.buildFlags differs from bench.harness.engine.buildFlags.", ownEdit(listOf("body", "producer", "engine", "buildFlags"), jsList(listOf("X=1"))), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-183", "One record: body.device.memory.totalBytes is 64e9 while bench.device.memTotalBytes is 16e9.", ownEdit(listOf("body", "device", "memory", "totalBytes"), ji(64_000_000_000L)), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-184", "One record: body.device.os.family is ios while bench.device.platform is android.", ownEdit(listOf("body", "device", "os", "family"), js("ios")), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-185", "One record: body.device.os.version differs from bench.device.osVersion.", ownEdit(listOf("body", "device", "os", "version"), js("99")), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-186", "One record: body.producer.engine.commit is a full 40-hex commit that the bench commit is not a prefix of (neither is an abbreviation of the other).", ownEdit(listOf("body", "producer", "engine", "commit"), js("0".repeat(40))), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-187", "One record: body.device.vendor differs from bench.device.maker.", ownEdit(listOf("body", "device", "vendor"), js("Other")), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-188", "One record: body.device.model differs from bench.device.model.", ownEdit(listOf("body", "device", "model"), js("Other Phone")), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-189", "One record: body.device.soc.name differs from bench.device.soc.", ownEdit(listOf("body", "device", "soc", "name"), js("Other SoC")), mesh(), RejectCode.INCONSISTENT, "15")
        r("M03-190", "One record, FILE form: body.device.memory.totalBytes is 64e9 while bench.device.memTotalBytes is 16e9.", fileDoc(JEdit.set(fileObj, listOf("body", "device", "memory", "totalBytes"), ji(64_000_000_000L))), file(), RejectCode.INCONSISTENT, "15")
        r("M03-191", "A self-reported keyStorage tee satisfies no required tier: requiredTier A1 is TIER_INSUFFICIENT (ERR-FX-CV3; manifest.md 9.1: A1 is treated exactly as A0 for every decision).", own(F.ownObj(F.ownBody(storage = "tee"))), mesh(tier = Tier.A1), RejectCode.TIER_INSUFFICIENT, "18")
        r("M03-192", "keyStorage strongbox at requiredTier A1: TIER_INSUFFICIENT (the claim is a display label only).", baseDoc, mesh(tier = Tier.A1), RejectCode.TIER_INSUFFICIENT, "18")
        r("M03-193", "FILE, schemaMinor 1 and one extra member in the presentation: SCHEMA_INVALID, because a FILE presentation is exactly { issuedAtMs } whatever the minor (P3).", fileDoc(JEdit.set(JEdit.set(fileObj, listOf("schemaMinor"), ji(1)), listOf("presentation", "exportedAtMs"), ji(1_790_676_061_234L))), file(), RejectCode.SCHEMA_INVALID, "11")
        r("M03-194", "FILE: the compared fingerprint is the right 26 characters followed by 256 more (a length that differs by a multiple of 256): FINGERPRINT_MISMATCH.", fileDoc(), file(key3.exportFingerprint + "A".repeat(256), CompareMethod.TYPED), RejectCode.FINGERPRINT_MISMATCH, "7b")
        r("M03-195", "FILE: the compared fingerprint of key4 with U+017F (long s) in place of every S: FINGERPRINT_MISMATCH (only ASCII letters are upper-cased).", fileDoc(F.fileObj(F.fileBody(key4)), key4), file(key4.exportFingerprint.lowercase().replace('s', 'ſ'), CompareMethod.TYPED), RejectCode.FINGERPRINT_MISMATCH, "7b")
        r("M03-196", "FILE: the compared fingerprint of key2 with U+0131 (dotless i) in place of every I: FINGERPRINT_MISMATCH.", fileDoc(F.fileObj(F.fileBody(key2)), key2), file(key2.exportFingerprint.lowercase().replace('i', 'ı'), CompareMethod.TYPED), RejectCode.FINGERPRINT_MISMATCH, "7b")
        val deepOpen = "[".repeat(17)
        val deepClose = "]".repeat(17)
        r("M03-197", "A fraction inside a region nested deeper than 16: row 4 outranks row 7, so NON_INTEGER_NUMBER.", (deepOpen + "1.5" + deepClose).toByteArray(), mesh(), RejectCode.NON_INTEGER_NUMBER, "2")
        r("M03-198", "A duplicate member name AFTER a region nested deeper than 16: row 6 outranks row 7, so DUPLICATE_KEY.", ("[" + "[".repeat(16) + "]".repeat(16) + ",{\"a\":1,\"a\":2}]").toByteArray(), mesh(), RejectCode.DUPLICATE_KEY, "2")
        r("M03-199", "A lone surrogate escape inside a region nested deeper than 16: row 3 outranks row 7, so INVALID_UNICODE.", (deepOpen + "\"\\ud800\"" + deepClose).toByteArray(), mesh(), RejectCode.INVALID_UNICODE, "2")
        r("M03-200", "An integer beyond 2^53 - 1 inside a region nested deeper than 16: row 5 outranks row 7, so NUMBER_RANGE.", (deepOpen + "9007199254740992" + deepClose).toByteArray(), mesh(), RejectCode.NUMBER_RANGE, "2")
        r("M03-201", "Container shape: a top-level array is not an object (CONTAINER_INVALID, not a version problem).", "[]".toByteArray(), mesh(), RejectCode.CONTAINER_INVALID, "3")
        r("M03-202", "Container shape: a top-level number.", "1".toByteArray(), mesh(), RejectCode.CONTAINER_INVALID, "3")
        val dsseHead = "{\"asomCapabilityManifest\":1,\"dsse\":{\"payload\":\"\",\"payloadType\":\"application/vnd.asom.manifest.v1+json\","
        r("M03-203", "Container shape: signatures [1,2]; the shape of signatures[0] is decided at step 3, before the count at step 5.", (dsseHead + "\"signatures\":[1,2]}}").toByteArray(), mesh(), RejectCode.CONTAINER_INVALID, "3")
        r("M03-204", "Container shape: payloadType x with signatures [1]; signatures[0] is checked before the payload type.", (dsseHead.replace("application/vnd.asom.manifest.v1+json", "x") + "\"signatures\":[1]}}").toByteArray(), mesh(), RejectCode.CONTAINER_INVALID, "3")
        val spkiNotString = String(fileDoc(fileObj), Charsets.UTF_8).replace(Regex("\"signer\":\\{\"spki\":\"[^\"]*\"\\}"), "\"signer\":{\"spki\":5}")
        check("\"spki\":5" in spkiNotString)
        r("M03-205", "Container shape, FILE: signer.spki is the number 5 (not a string).", spkiNotString.toByteArray(), file(), RejectCode.CONTAINER_INVALID, "7")
        val asDoc = String(baseDoc, Charsets.UTF_8)
        r("M03-206", "payloadType application/vnd.asom.manifest.v02+json: not a canonical decimal major, so PAYLOAD_TYPE_UNSUPPORTED (v2 is SCHEMA_MAJOR_UNKNOWN, M03-118).", asDoc.replace("application/vnd.asom.manifest.v1+json", "application/vnd.asom.manifest.v02+json").toByteArray(), mesh(), RejectCode.PAYLOAD_TYPE_UNSUPPORTED, "4")
        r("M03-207", "A signed payload whose schema is asom.manifest/02: not a canonical decimal major, so SCHEMA_INVALID.", ownEdit(listOf("schema"), js("asom.manifest/02")), mesh(), RejectCode.SCHEMA_INVALID, "11")
        rs("M03-208", "An evidence item whose JCS form exceeds 32,768 bytes (a DER of at most 16 KiB cannot need more): CONTAINER_INVALID.", DocSpec(basePayload, evidence = listOf(jo("x" to js("a".repeat(33_000))))), mesh(), RejectCode.CONTAINER_INVALID, "15c")
        r("M03-209", "Lexer: truex is one letter run, not the value true followed by data: MALFORMED_JSON.", "truex".toByteArray(), mesh(), RejectCode.MALFORMED_JSON, "2")
        r("M03-210", "Lexer: nullnull is one letter run: MALFORMED_JSON.", "nullnull".toByteArray(), mesh(), RejectCode.MALFORMED_JSON, "2")
        r("M03-211", "Lexer: Infinityx is one letter run, not Infinity followed by data: MALFORMED_JSON.", "Infinityx".toByteArray(), mesh(), RejectCode.MALFORMED_JSON, "2")
        r("M03-212", "Lexer: [1-2] is one number lexeme (the maximal run of digits and signs): NON_INTEGER_NUMBER.", "[1-2]".toByteArray(), mesh(), RejectCode.NON_INTEGER_NUMBER, "2")
        r("M03-213", "Lexer: a lone minus sign is a number lexeme outside the profile: NON_INTEGER_NUMBER.", "-".toByteArray(), mesh(), RejectCode.NON_INTEGER_NUMBER, "2")
        r("M03-214", "Lexer: [-NaN] is minus followed by the word NaN, a number outside the profile: NON_INTEGER_NUMBER.", "[-NaN]".toByteArray(), mesh(), RejectCode.NON_INTEGER_NUMBER, "2")
        val small = NonReducedKeys.smallPoint()
        val nonReduced = NonReducedKeys.encode(small.first.add(P256.P), small.second)
        check(Spki.strict(NonReducedKeys.encode(small.first, small.second)) != null)
        rs("M03-215", "The pinned key is a small-x point encoded with x + p (a non-reduced coordinate: the same point, a second encoding, another node id): ALG_UNSUPPORTED.", DocSpec(basePayload, keyid = null), VCtx(Mode.MESH, nonReduced, F.challenge1), RejectCode.ALG_UNSUPPORTED, "7")
        return c
    }

    private fun hex(s: String) = Hex.decode(s)

    private fun jsonTrue(): JValue = xyz.mdhv.asom.lab.json.JBool(true)

    /** The maxima of LAB_SPEC 4.9 M02-114: every rate exactly 10^9 milli-tokens per second, every byte field 2^50. */
    fun maxima(): xyz.mdhv.asom.lab.bench.BenchDoc {
        val b = F.bench
        val tiers = b.tiers.map { t ->
            t.copy(
                tests = t.tests.map { x -> x.copy(samples = x.samples.map { x.spec.tokens }, wholeSamples = x.wholeSamples?.map { x.spec.tokens }) },
                kvBytesPerToken = (1L shl 50) / t.nCtx, availBeforeLoadBytes = 1L shl 50, peakFootprintBytes = 1L shl 50,
            )
        }
        val sustain = b.sustain?.let { su -> su.copy(windows = su.windows.map { it.copy(tokens = 1, micros = 1) }) }
        return b.copy(
            device = b.device.copy(memTotalBytes = 1L shl 50), memory = b.memory.copy(availAtStartBytes = 1L shl 50), tiers = tiers, sustain = sustain,
        )
    }

    /** A run stopped by a charger removal during the second tier: only the finished tests are kept. */
    fun partial(): xyz.mdhv.asom.lab.bench.BenchDoc {
        val r = Scenarios.run(
            xyz.mdhv.asom.lab.json.StrictJson.parse("""{"preset":"phone","plan":"standard","injections":[{"atMs":150000,"kind":"CHARGER_REMOVED"}]}""".toByteArray()).let { (it as xyz.mdhv.asom.lab.json.ParseResult.Ok).value },
        )
        check(r.outcome.startsWith("aborted")) { r.outcome }
        return r.doc!!
    }
}

/** A JSON writer for the "not canonical" vectors: members in their given order with a space after each colon. */
object JsonTextNonCanonical {
    fun pretty(v: JValue): ByteArray = (ManifestCases.compact(v).replace("\":", "\": ")).toByteArray(Charsets.UTF_8)
}
