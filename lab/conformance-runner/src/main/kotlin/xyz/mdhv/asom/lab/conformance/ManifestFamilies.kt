package xyz.mdhv.asom.lab.conformance

import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.lab.bench.Audience
import xyz.mdhv.asom.lab.bench.BenchCodec
import xyz.mdhv.asom.lab.bench.BenchSets
import xyz.mdhv.asom.lab.bench.Derive
import xyz.mdhv.asom.lab.bench.RdContext
import xyz.mdhv.asom.lab.bench.RenderOptions
import xyz.mdhv.asom.lab.bench.SchemaViolation
import xyz.mdhv.asom.lab.bench.TextRender
import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.manifest.CompareMethod
import xyz.mdhv.asom.lab.manifest.CoarseDevices
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.manifest.FileProjection
import xyz.mdhv.asom.lab.manifest.ManifestDecoder
import xyz.mdhv.asom.lab.manifest.ManifestText
import xyz.mdhv.asom.lab.manifest.Mode
import xyz.mdhv.asom.lab.manifest.P256
import xyz.mdhv.asom.lab.manifest.PinState
import xyz.mdhv.asom.lab.manifest.PublicDerivative
import xyz.mdhv.asom.lab.manifest.Rejected
import xyz.mdhv.asom.lab.manifest.ReleaseAllowList
import xyz.mdhv.asom.lab.manifest.RejectCode
import xyz.mdhv.asom.lab.manifest.RollbackEntry
import xyz.mdhv.asom.lab.manifest.RollbackStore
import xyz.mdhv.asom.lab.manifest.SigCodec
import xyz.mdhv.asom.lab.manifest.Tier
import xyz.mdhv.asom.lab.manifest.Verified
import xyz.mdhv.asom.lab.manifest.VerifyContext
import xyz.mdhv.asom.lab.manifest.Verifier

/** Converts a vector's kotlinx element to the lab's strict value model (the vector file is not a signed document; the conversion is exact for integers). */
fun JsonElement.toJValue(): JValue = when (val r = StrictJson.parse(toString().toByteArray(Charsets.UTF_8))) {
    is ParseResult.Ok -> r.value
    is ParseResult.Reject -> throw LawViolation("vector value is not in the JSON profile: $r")
}

private fun sha256Hex(b: ByteArray): String = Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(b))

/** The verifier input of a vector (M02, M03, and the manifest kinds of M05 and M06): document bytes, context, clock. */
class VerifierInput(val doc: ByteArray, val ctx: VerifyContext, val mode: Mode) {
    companion object {
        fun of(input: JsonObject): VerifierInput {
            val doc: ByteArray = when {
                input["documentFill"] != null -> input.obj("documentFill").let { f -> ByteArray(f.long("count").toInt()) { f.long("byte").toByte() } }
                input["documentHex"] != null -> Hex.decode(input.str("documentHex"))
                else -> input.str("document").toByteArray(Charsets.UTF_8)
            }
            val c = input.obj("context")
            val mode = Mode.valueOf(c.str("mode"))
            val rollback = c.objOrNull("rollback")?.let { rb -> rb.entries.associate { (k, v) -> k to RollbackEntry((v as JsonObject).long("seq"), v.str("bodyDigest")) } }.orEmpty()
            val store = if (rollback.isEmpty()) null else object : RollbackStore {
                override fun get(nodeId: String, audience: String): RollbackEntry? = rollback["$nodeId|$audience"]
            }
            val challenge = c.strOrNull("expectedChallengeB64u")?.let { (Base64Strict.decodeUrlNoPad(it) as B64Result.Ok).bytes }
            val ctx = VerifyContext(
                mode = mode, pinnedSpki = c.strOrNull("pinnedSpkiB64")?.let { unb64(it) }, expectedChallenge = challenge,
                comparedFingerprint = c.strOrNull("comparedFingerprint"), compareMethod = c.strOrNull("compareMethod")?.let { m -> CompareMethod.entries.first { it.wire == m } },
                rollback = store, requiredTier = Tier.valueOf(c.str("requiredTier")), confFloor = c.str("confFloor"), knownBadConf = c.strList("knownBadConf").toSet(),
                productionKeys = c.bool("productionKeys"), nowMs = input.long("nowMs"),
            )
            return VerifierInput(doc, ctx, mode)
        }
    }
}

private fun isHighS(doc: ByteArray): Boolean {
    val root = (StrictJson.parse(doc) as? ParseResult.Ok)?.value as? JObject ?: return false
    val sig = ((root["dsse"] as? JObject)?.get("signatures") as? JArray)?.items?.firstOrNull() as? JObject ?: return false
    val text = (sig["sig"] as? JString)?.value ?: return false
    val bytes = (Base64Strict.decodeEither(text) as? B64Result.Ok)?.bytes ?: return false
    return bytes.size == 64 && BigInteger(1, bytes.copyOfRange(32, 64)) > P256.HALF_N
}

/**
 * M02 (verify: accept) and M03 (verify: reject): the r3 verifier of LAB_SPEC 4.6 over signed containers. Both families run the same code; a vector
 * pins the whole accepted summary (`pin`, `tier`, `seq`, `bodyDigest`, `unknownFields`) or the reject code AND the failing step. Evidence label: LAB, oracle: self.
 */
class ManifestChecker(family: String) : FamilyChecker(family) {
    override val requiredLaws: Set<String> = if (family == "M02") {
        setOf("accept-PINNED", "accept-SIGNER_UNVERIFIED", "accept-PINNED_BY_FINGERPRINT(typed)", "accept-PINNED_BY_FINGERPRINT(qr)", "accept-tier-A0", "accept-tier-A1", "accept-high-s", "accept-unknown-fields", "accept-rollback-store", "accept-file")
    } else {
        RejectCode.entries.map { "reject-${it.name}" }.toSet()
    }

    override val requiredIds: Set<String> = if (family == "M02") {
        (listOf(101) + (104..114)).map { "M02-%03d".format(it) }.toSet()
    } else {
        ((101..121) + (123..126) + 128 + 127 + (129..143)).map { "M03-%03d".format(it) }.toSet()
    }

    override fun observe(v: Vector): Observed {
        val vi = VerifierInput.of(v.input)
        return when (val r = Verifier.verify(vi.doc, vi.ctx)) {
            is Verified -> {
                bump("accept-${r.pin.wire}")
                bump("accept-tier-${r.tier.name}")
                if (isHighS(vi.doc)) bump("accept-high-s")
                if (r.unknownFields > 0) bump("accept-unknown-fields")
                if (vi.ctx.rollback != null) bump("accept-rollback-store")
                if (vi.mode == Mode.FILE) bump("accept-file")
                Observed.Ok(
                    buildJsonObject {
                        put("pin", r.pin.wire)
                        put("tier", r.tier.name)
                        put("seq", r.obj.body.seq?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("bodyDigest", r.bodyDigest)
                        put("unknownFields", r.unknownFields)
                    },
                )
            }
            is Rejected -> {
                bump("reject-${r.code.name}")
                Observed.Reject(r.code.name, buildJsonObject { put("step", r.step) })
            }
        }
    }
}

/** M01-201..207: the DER <-> raw signature codec and the low-S normalisation (LAB_SPEC 4.9). */
object SigCodecCases {
    val REQUIRED_LAWS: Set<String> = setOf("codec-ok", "codec-reject", "lowS-changed", "lowS-unchanged")
    val REQUIRED_IDS: Set<String> = (201..206).map { "M01-%03d".format(it) }.toSet()

    fun observe(v: Vector, bump: (String) -> Unit): Observed {
        val kind = v.input.str("kind")
        val res: SigCodec.Result = when (kind) {
            "derToRaw" -> SigCodec.derToRaw(Hex.decode(v.input.str("derHex")))
            "rawToDer" -> SigCodec.rawToDer(Hex.decode(v.input.str("rawHex")))
            "normaliseLowS" -> SigCodec.normaliseRaw(Hex.decode(v.input.str("rawHex")))
            else -> throw LawViolation("unknown codec kind $kind")
        }
        return when (res) {
            is SigCodec.Result.Reject -> {
                bump("codec-reject")
                Observed.Reject(res.code)
            }
            is SigCodec.Result.Ok -> {
                bump("codec-ok")
                if (kind == "normaliseLowS") {
                    val before = Hex.decode(v.input.str("rawHex"))
                    bump(if (before.contentEquals(res.bytes)) "lowS-unchanged" else "lowS-changed")
                }
                Observed.Ok(buildJsonObject { put(if (kind == "rawToDer") "derHex" else "rawHex", Hex.encode(res.bytes)) })
            }
        }
    }
}

/**
 * M05: byte-exact plain text. `asom.manifest-text/1` (`manifest`), the exported text (`export`, never a verification block), the `asom.text/1`
 * body of a bench document (`body`), and the MLPerf wording rule (`mlperf`, LM-9). Every rendering is checked for ASCII-only and for the forbidden label.
 */
class M05Checker : FamilyChecker("M05") {
    override val requiredLaws: Set<String> = setOf(
        "text-manifest", "text-body", "text-export-no-verification", "text-rejected-display", "mlperf-flag-off-l1", "mlperf-flag-on-q1", "ascii-only",
        "no-forbidden-label", "verification-block-computed-by-viewer", "mesh-role-rendered", "text-body-reject",
    )
    override val requiredIds: Set<String> = setOf("M05-101", "M05-102", "M05-103", "M05-104", "M05-105", "M05-MLP")

    private fun check(text: String) {
        if (!text.all { it == '\n' || it.code in 0x20..0x7E }) throw LawViolation("text holds a byte outside 0x20-0x7E and LF")
        bump("ascii-only")
        if (text.contains(TextRender.FORBIDDEN_LABEL)) throw LawViolation("text contains the forbidden label")
        bump("no-forbidden-label")
    }

    private fun options(input: JsonObject): RenderOptions {
        val r = input.objOrNull("render")
        return RenderOptions(meshAvailable = r?.bool("meshAvailable") ?: false, mlperfNoteEnabled = r?.bool("mlperfNoteEnabled") ?: false)
    }

    private fun result(text: String): Observed {
        check(text)
        return Observed.Ok(buildJsonObject { put("text", text); put("sha256", sha256Hex(text.toByteArray(Charsets.US_ASCII))) })
    }

    private fun decodeBench(input: JsonObject) = BenchCodec.decode(input.obj("benchDoc").toJValue(), RdContext(false))

    override fun observe(v: Vector): Observed = when (val kind = v.input.str("kind")) {
        "body" -> {
            val bench = try {
                decodeBench(v.input)
            } catch (e: SchemaViolation) {
                bump("text-body-reject")
                return Observed.Reject("SCHEMA_INVALID")
            }
            val text = TextRender.render(Derive.derive(bench), options(v.input))
            bump("text-body")
            if (options(v.input).meshAvailable) bump("mesh-role-rendered")
            result(text)
        }
        "manifest", "export" -> {
            val vi = VerifierInput.of(v.input)
            when (val r = Verifier.verify(vi.doc, vi.ctx)) {
                is Verified -> {
                    val text = if (kind == "export") ManifestText.renderExport(r.obj, options(v.input)) else ManifestText.render(r.obj, ManifestText.viewerFor(r, vi.mode), options(v.input))
                    if (kind == "export") {
                        if (text.contains("VERIFICATION")) throw LawViolation("an exported text contains a verification block")
                        bump("text-export-no-verification")
                    } else {
                        bump("text-manifest")
                        if (!text.contains("VERIFICATION (checked by this viewer, not stated by the device)")) throw LawViolation("no viewer-computed verification block")
                        bump("verification-block-computed-by-viewer")
                    }
                    if (options(v.input).meshAvailable) bump("mesh-role-rendered")
                    result(text)
                }
                is Rejected -> {
                    val pin = if (vi.mode == Mode.MESH) PinState.Pinned else PinState.SignerUnverified
                    val vr = ManifestText.viewerFor(r, vi.mode, pin)
                    val rejectedObj = r.obj
                    if (kind == "manifest" && v.input.bool("displayOnReject") && vr != null && rejectedObj != null) {
                        bump("text-rejected-display")
                        result(ManifestText.render(rejectedObj, vr, options(v.input)))
                    } else {
                        Observed.Reject(r.code.name)
                    }
                }
            }
        }
        "mlperf" -> mlperf(v.input)
        else -> throw LawViolation("unknown M05 kind '$kind'")
    }

    private fun mlperf(input: JsonObject): Observed {
        val base = decodeBench(input)
        val out = buildJsonArray {
            for (c in input.arr("cases")) {
                val o = c as JsonObject
                val derived = Derive.derive(base)
                val flag = o.bool("mlperfNoteEnabled")
                val text = TextRender.render(derived.copy(doc = derived.doc.copy(benchSet = o.str("benchSet"))), RenderOptions(meshAvailable = false, mlperfNoteEnabled = flag))
                check(text)
                if (text.contains("MLPerf") || text.contains(TextRender.FORBIDDEN_LABEL)) throw LawViolation("the MLPerf wording rule is broken: the text names MLPerf")
                if (o.str("benchSet") == BenchSets.L1_ID && !flag) bump("mlperf-flag-off-l1")
                if (o.str("benchSet") == BenchSets.Q1_ID && flag) bump("mlperf-flag-on-q1")
                add(buildJsonObject { put("benchSet", o.str("benchSet")); put("mlperfNoteEnabled", flag); put("noteRendered", false); put("sha256", sha256Hex(text.toByteArray(Charsets.US_ASCII))) })
            }
        }
        return Observed.Ok(buildJsonObject { put("results", out) })
    }
}

/** M06: the public anonymised derivative, the q2 table, and the FILE projection of an own body (LAB_SPEC 4.7, 4.9). */
class M06Checker : FamilyChecker("M06") {
    override val requiredLaws: Set<String> = setOf("public-ok", "public-none", "q2-idempotent", "file-projection", "public-forbidden-absent", "file-no-stable-identifier", "public-catalogue-drop", "public-custom")
    override val requiredIds: Set<String> = setOf("M06-101", "M06-102", "M06-103", "M06-104", "M06-201", "M06-202", "M06-203", "M06-204")

    override fun observe(v: Vector): Observed = when (val kind = v.input.str("kind")) {
        "q2" -> {
            val values = v.input.arr("values").map { (it as JsonPrimitive).content.toLong() }
            val q = values.map { PublicDerivative.q2(it) }
            if (q.any { PublicDerivative.q2(it) != it }) throw LawViolation("q2 is not idempotent")
            bump("q2-idempotent", values.size)
            Observed.Ok(buildJsonObject { put("q2", buildJsonArray { q.forEach { add(JsonPrimitive(it)) } }) })
        }
        "public" -> public(v)
        "fileProjection" -> fileProjection(v)
        else -> throw LawViolation("unknown M06 kind '$kind'")
    }

    private fun names(v: JValue, out: MutableSet<String>) {
        when (v) {
            is JObject -> v.members.forEach { (k, x) -> out += k; names(x, out) }
            is JArray -> v.items.forEach { names(it, out) }
            else -> Unit
        }
    }

    private fun public(v: Vector): Observed {
        val vi = VerifierInput.of(v.input)
        val r = Verifier.verify(vi.doc, vi.ctx)
        if (r !is Verified) return Observed.Reject((r as Rejected).code.name)
        val allow = v.input.objOrNull("allowList")
        val coarse = v.input.objOrNull("coarse")
        val out = PublicDerivative.from(
            r.obj, v.input.strList("catalogue").toSet(),
            ReleaseAllowList(allow?.strList("engineCommits")?.toSet().orEmpty(), allow?.strList("harnessVersions")?.toSet().orEmpty()),
            CoarseDevices(coarse?.strList("vendors")?.toSet().orEmpty(), coarse?.strList("models")?.toSet().orEmpty()),
            v.input.arrOrNull("selected")?.map { (it as JsonPrimitive).content.toInt() }?.toSet(),
        )
        val catalogue = v.input.strList("catalogue").toSet()
        if (r.obj.body.results.any { it.sustained != null && it.fileSha256 !in catalogue }) bump("public-catalogue-drop")
        if (out == null) {
            bump("public-none")
            return Observed.Ok(buildJsonObject { put("none", true) })
        }
        val bytes = Jcs.serialize(out)
        val found = HashSet<String>().also { names(out, it) }
        val bad = found.intersect(PublicDerivative.FORBIDDEN_NAMES.toSet())
        if (bad.isNotEmpty()) throw LawViolation("the public derivative holds forbidden member(s) $bad (LM-2)")
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains(r.obj.body.subject.nodeId)) throw LawViolation("the public derivative holds the signer's node id (LM-2)")
        bump("public-forbidden-absent")
        bump("public-ok")
        if (text.contains("\"custom\"")) bump("public-custom")
        return Observed.Ok(buildJsonObject { put("jcsUtf8", text); put("sha256", sha256Hex(bytes)) })
    }

    private fun fileProjection(v: Vector): Observed {
        val ownObj = v.input.obj("ownObj").toJValue()
        val own = try {
            ManifestDecoder.decode(ownObj)
        } catch (e: SchemaViolation) {
            throw LawViolation("own object does not decode: ${e.message}")
        }
        if (own.body.audience != Audience.OWN) throw LawViolation("fileProjection needs an own-audience input")
        val exportNodeId = v.input.str("exportNodeId")
        val body = FileProjection.fromOwn(own.body, exportNodeId)
        val bytes = Jcs.serialize(body)
        val text = String(bytes, Charsets.UTF_8)
        // the FILE body carries no stable device identifier (ERRATA ERR-CLOSURE-1)
        val forbidden = listOf(own.body.subject.nodeId, "platformIds", "securityPatch", "\"seq\"", "osBuild\":\"", "gpuDriver\":\"", "\"screenOn\":true", "\"screenOn\":false", "batteryStartPermille\":[0-9]")
        for (f in forbidden) if (Regex(f).containsMatchIn(text)) throw LawViolation("the file projection still holds `$f`")
        bump("file-no-stable-identifier")
        bump("file-projection")
        return Observed.Ok(buildJsonObject { put("jcsUtf8", text); put("sha256", sha256Hex(bytes)) })
    }
}
