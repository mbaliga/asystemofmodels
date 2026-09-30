package xyz.mdhv.asom.lab.manifest

import java.io.File
import java.math.BigInteger
import kotlin.test.Test
import xyz.mdhv.asom.lab.bench.JsonText
import xyz.mdhv.asom.lab.bench.RenderOptions
import xyz.mdhv.asom.lab.bench.Scenarios
import xyz.mdhv.asom.lab.bench.ja
import xyz.mdhv.asom.lab.bench.jb
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.bench.jo
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.bench.jsList
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs

/**
 * Regenerates the M01 (signature codec), M02, M03, M05 and M06 vector files of `lab/conformance/manifest` from this implementation (LAB_SPEC 4.9).
 * It runs only through `./gradlew -p lab :manifest:genManifestVectors` (system property `asom.lab.regen`). Every expectation is written by hand in
 * [ManifestCases] (or below); the generator REFUSES to write a file when the implementation disagrees with one, so a recording can never bless a bug.
 * Every vector is `oracle: self`.
 */
class RegenerateManifestVectors {
    private fun write(rel: String, v: JValue) {
        val f = File(Repo.conformance, rel)
        f.parentFile.mkdirs()
        f.writeText(JsonText.pretty(v), Charsets.UTF_8)
        println("wrote $rel (${f.length()} bytes)")
    }

    private fun docOf(c: Case): ByteArray = if (c.fill != null) ByteArray(c.fill.second) { c.fill.first.toByte() } else c.doc

    private fun sha(text: ByteArray) = sha256Hex(text)

    // ------------------------------------------------------------------------------------------ M02 / M03

    private fun verifierVectors(): Pair<List<Case>, List<Case>> {
        val accept = ManifestCases.accept()
        val reject = ManifestCases.reject()
        val problems = mutableListOf<String>()
        for (c in accept + reject) {
            val actual = Verifier.verify(docOf(c), c.ctx.toVerifyContext(c.nowMs))
            if (c.reject == null && actual !is Verified) problems += "${c.id}: expected accept, got ${(actual as Rejected).code} step ${actual.step} ${actual.detail}"
            if (c.reject != null) {
                if (actual !is Rejected) problems += "${c.id}: expected ${c.reject}, but the verifier accepted"
                else if (actual.code != c.reject || (c.step != null && actual.step != c.step)) problems += "${c.id}: expected ${c.reject} at step ${c.step}, got ${actual.code} at step ${actual.step} (${actual.detail})"
            }
        }
        check(problems.isEmpty()) { "generator refuses to write:\n" + problems.joinToString("\n") }
        return accept to reject
    }

    // ------------------------------------------------------------------------------------------ M05 (manifest text)

    private fun renderJson(c: Case, kind: String, mesh: Boolean, displayOnReject: Boolean = false): Pair<JValue, String?> {
        val input = mutableListOf<Pair<String, JValue>>("kind" to js(kind))
        val text = VectorJson.utf8OrNull(c.doc)!!
        input += "document" to js(text)
        input += "context" to c.ctx.toJson()
        input += "nowMs" to ji(c.nowMs)
        input += "render" to jo("meshAvailable" to jb(mesh), "mlperfNoteEnabled" to jb(false))
        if (displayOnReject) input += "displayOnReject" to jb(true)
        val r = Verifier.verify(c.doc, c.ctx.toVerifyContext(c.nowMs))
        val opts = RenderOptions(meshAvailable = mesh)
        val out: String? = when {
            r is Verified && kind == "export" -> ManifestText.renderExport(r.obj, opts)
            r is Verified -> ManifestText.render(r.obj, ManifestText.viewerFor(r, c.ctx.mode), opts)
            r is Rejected && displayOnReject -> {
                val pin = if (c.ctx.mode == Mode.MESH) PinState.Pinned else PinState.SignerUnverified
                ManifestText.render(r.obj!!, ManifestText.viewerFor(r, c.ctx.mode, pin)!!, opts)
            }
            else -> null
        }
        return jo(input) to out
    }

    private fun textVectors(accept: Map<String, Case>, reject: Map<String, Case>): List<JValue> {
        class R(val id: String, val desc: String, val c: Case, val kind: String = "manifest", val mesh: Boolean = false, val display: Boolean = false)
        val list = listOf(
            R("M05-101", "Text of the verified payload of M02-101 (MESH, PINNED, A1, challenge matched), rendered by the viewer.", accept.getValue("M02-101")),
            R("M05-102", "Rendering of M02-107: identical to M05-101 except the trailing newer-items line.", accept.getValue("M02-107")),
            R("M05-103", "FILE, per-export key, fingerprint not compared (M02-110): the signer is unverified, freshness not applicable, header shows the export day only.", accept.getValue("M02-110")),
            R("M05-104", "FILE, fingerprint typed (M02-111): 'matches the fingerprint you compared (typed)'.", accept.getValue("M02-111")),
            R("M05-105", "A file export's plain text (M02-110): only the asom.text/1 body, no verification block, 'not measured' wording where the passive lane would be.", accept.getValue("M02-110"), kind = "export"),
            R("M05-106", "As M05-101 with the mesh available: question 5 names the role.", accept.getValue("M02-101"), mesh = true),
            R("M05-107", "A report rejected at step 14 (NONCE_MISMATCH) is displayed with the reject in the verification block (steps 13-16 only).", reject.getValue("M03-109"), display = true),
            R("M05-108", "FILE, fingerprint scanned (M02-112): 'matches the fingerprint you compared (scanned)'.", accept.getValue("M02-112")),
            R("M05-109", "device.model of 96 astral characters (M02-113): each becomes one '?' in the ASCII text.", accept.getValue("M02-113")),
            R("M05-110", "A partial run (M02-116): the finished tests, and a note that the run stopped early.", accept.getValue("M02-116")),
            R("M05-111", "keyStorage os-keystore (M02-117): the key storage line says software, self-reported.", accept.getValue("M02-117")),
            R("M05-112", "A report rejected at step 13 (EXPIRED) is displayed with the reject shown.", reject.getValue("M03-107"), display = true),
        )
        return list.map { r ->
            val (input, text) = renderJson(r.c, r.kind, r.mesh, r.display)
            check(text != null) { "${r.id}: nothing rendered" }
            check(text.all { it == '\n' || it.code in 0x20..0x7E }) { "${r.id}: non-ASCII" }
            check(!text.contains("MLPerf-comparable")) { "${r.id}: forbidden label" }
            if (r.kind == "export") check(!text.contains("VERIFICATION")) { "${r.id}: a verification block in an export" }
            VectorJson.vector(r.id, r.desc, input, jo("ok" to jo("text" to js(text), "sha256" to js(sha(text.toByteArray(Charsets.US_ASCII))))))
        }
    }

    // ------------------------------------------------------------------------------------------ M06

    private fun publicVector(id: String, desc: String, c: Case, catalogue: List<String>, allow: Pair<List<String>, List<String>>, coarse: Pair<List<String>, List<String>>, selected: List<Int>? = null): JValue {
        val r = Verifier.verify(c.doc, c.ctx.toVerifyContext(c.nowMs)) as Verified
        val out = PublicDerivative.from(r.obj, catalogue.toSet(), ReleaseAllowList(allow.first.toSet(), allow.second.toSet()), CoarseDevices(coarse.first.toSet(), coarse.second.toSet()), selected?.toSet())
        val input = mutableListOf<Pair<String, JValue>>(
            "kind" to js("public"), "document" to js(VectorJson.utf8OrNull(c.doc)!!), "context" to c.ctx.toJson(), "nowMs" to ji(c.nowMs), "catalogue" to jsList(catalogue),
            "allowList" to jo("engineCommits" to jsList(allow.first), "harnessVersions" to jsList(allow.second)), "coarse" to jo("vendors" to jsList(coarse.first), "models" to jsList(coarse.second)),
        )
        selected?.let { input += "selected" to ja(it.map { i -> ji(i) }) }
        val expect: JValue = if (out == null) jo("ok" to jo("none" to jb(true))) else {
            val bytes = Jcs.serialize(out)
            jo("ok" to jo("jcsUtf8" to js(String(bytes, Charsets.UTF_8)), "sha256" to js(sha(bytes))))
        }
        return VectorJson.vector(id, desc, jo(input), expect)
    }

    private fun fileProjectionVector(id: String, desc: String, ownObj: JValue): JValue {
        val own = ManifestDecoder.decode(ownObj)
        val body = FileProjection.fromOwn(own.body, F.key3.nodeId)
        val bytes = Jcs.serialize(body)
        check(!String(bytes, Charsets.UTF_8).contains(own.body.subject.nodeId)) { "$id: the NIK node id survived the projection" }
        return VectorJson.vector(id, desc, jo("kind" to js("fileProjection"), "ownObj" to ownObj, "exportNodeId" to js(F.key3.nodeId)), jo("ok" to jo("jcsUtf8" to js(String(bytes, Charsets.UTF_8)), "sha256" to js(sha(bytes)))))
    }

    private fun derivativeVectors(accept: Map<String, Case>): List<JValue> {
        val base = accept.getValue("M02-101")
        val verified = Verifier.verify(base.doc, base.ctx.toVerifyContext(base.nowMs)) as Verified
        val shas = verified.obj.body.results.map { it.fileSha256 }
        val commit = verified.obj.body.producer.engine.commit
        val inputs = F.inputs()
        val out = mutableListOf<JValue>()
        out += publicVector("M06-101", "Public derivative of the verified M02-101 payload: allow-listed fields only, 2-significant-digit quantisation, month-granular date, RAM class; commit and versions on the release allow-list, vendor and model on the coarse list. Only the row with a heat test can be carried.", base, shas, listOf(commit) to listOf("1.0.0", F.CONF), listOf(inputs.device.vendor) to listOf(inputs.device.model))
        val values = listOf(0L, 7, 99, 100, 149, 150, 994, 995, 11_234, 18_400, 212_000, 2_480_000)
        out += VectorJson.vector("M06-102", "q2 quantisation table (law: q2(q2(x)) == q2(x); values below 100 unchanged).", jo("kind" to js("q2"), "values" to ja(values.map { ji(it) })), jo("ok" to jo("q2" to ja(values.map { ji(PublicDerivative.q2(it)) }))))
        out += publicVector("M06-103", "Results whose fileSha256 is not in the catalogue the device holds are dropped: nothing is left, so nothing is sent.", base, shas.filter { it != verified.obj.body.results[1].fileSha256 }, listOf(commit) to listOf("1.0.0"), emptyList<String>() to emptyList())
        out += publicVector("M06-104", "An engine commit and harness versions that are NOT on the release allow-list are emitted as `custom`.", base, shas, listOf("ffffffff") to listOf("9.9.9"), listOf(inputs.device.vendor) to listOf(inputs.device.model))
        out += publicVector("M06-105", "Vendor and model that are not on the catalogue-backed coarse list map to `other`.", base, shas, listOf(commit) to listOf("1.0.0", F.CONF), emptyList<String>() to emptyList())
        val fileCase = accept.getValue("M02-110")
        out += publicVector("M06-106", "The public derivative of a FILE manifest (M02-110): the same allow-list, nothing that the file form dropped.", fileCase, shas, listOf(commit) to listOf("1.0.0", F.CONF), listOf(inputs.device.vendor) to listOf(inputs.device.model))
        out += fileProjectionVector("M06-201", "own body -> file body (LAB_SPEC 4.7): a new subject (the per-export key, ephemeral), no platformIds, no securityPatch, no seq, day-granular times, no battery level or screen state, no OS build or GPU driver; results recomputed from the file-form bench.", F.ownObj())
        val midnight = java.time.Instant.parse("2026-09-29T23:55:00Z").toEpochMilli()
        val overMidnight = F.benchFrom("""{"preset":"phone","plan":"standard","epochStartMs":$midnight}""")
        check(overMidnight.run.endedAtMs / xyz.mdhv.asom.lab.bench.DAY_MS != overMidnight.run.startedAtMs / xyz.mdhv.asom.lab.bench.DAY_MS) { "the run must cross midnight" }
        out += fileProjectionVector("M06-202", "A run that crosses midnight: the start and the end truncate to their own days.", F.ownObj(F.ownBody(inputs = F.inputs(overMidnight))))
        out += fileProjectionVector("M06-203", "A desktop (no battery): the projection is the same and carries no battery level.", F.ownObj(F.ownBody(inputs = F.inputs(F.benchFrom("""{"preset":"desktop","plan":"standard"}""")))))
        out += fileProjectionVector("M06-204", "A partial run (charger removed): its finished tests project like a full one.", F.ownObj(F.ownBody(inputs = F.inputs(ManifestCases.partial()))))
        return out
    }

    // ------------------------------------------------------------------------------------------ M01-2xx (DER <-> raw)

    private fun derInt(v: BigInteger): ByteArray {
        var b = v.toByteArray()
        if (b.size > 1 && b[0] == 0.toByte() && b[1].toInt() and 0x80 == 0) b = b.copyOfRange(1, b.size)
        return byteArrayOf(0x02, b.size.toByte()) + b
    }

    private fun derSeq(inner: ByteArray): ByteArray = (if (inner.size < 0x80) byteArrayOf(0x30, inner.size.toByte()) else byteArrayOf(0x30, 0x81.toByte(), inner.size.toByte())) + inner

    private fun raw(r: BigInteger, s: BigInteger): ByteArray = Es256.fixed32(r) + Es256.fixed32(s)

    private fun codecVectors(): List<JValue> {
        val n = P256.N
        fun v32(first: Int, second: Int): BigInteger = BigInteger(1, ByteArray(32) { i -> when (i) { 0 -> first.toByte(); 1 -> second.toByte(); else -> (i * 7 + 3).toByte() } })
        val rNormal = v32(0x5a, 0x3f)
        val sNormal = v32(0x3b, 0x2a)
        val rZero = v32(0x00, 0x11)
        val sZero = v32(0x00, 0x7f)
        val hi = n.subtract(BigInteger("12345678", 16))
        val out = mutableListOf<JValue>()
        fun ok(id: String, desc: String, kind: String, inKey: String, inHex: ByteArray, outKey: String, outHex: ByteArray) {
            out += VectorJson.vector(id, desc, jo("kind" to js(kind), inKey to js(Hex.encode(inHex))), jo("ok" to jo(outKey to js(Hex.encode(outHex)))))
        }
        fun bad(id: String, desc: String, kind: String, inKey: String, inHex: ByteArray) {
            out += VectorJson.vector(id, desc, jo("kind" to js(kind), inKey to js(Hex.encode(inHex))), jo("reject" to js("SIGNATURE_ENCODING")))
        }
        ok("M01-201", "DER to raw: r has a leading zero byte, so its DER integer is 31 bytes; the raw form is padded back to 32.", "derToRaw", "derHex", derSeq(derInt(rZero) + derInt(sNormal)), "rawHex", raw(rZero, sNormal))
        ok("M01-202", "DER to raw: s has a leading zero byte (a 31-byte DER integer).", "derToRaw", "derHex", derSeq(derInt(rNormal) + derInt(sZero)), "rawHex", raw(rNormal, sZero))
        val rHigh = v32(0xff, 0x00)
        val sHigh = v32(0xe0, 0x10)
        val derHigh = derSeq(derInt(rHigh) + derInt(sHigh))
        check(derHigh.size == 2 + 35 + 35) { "unexpected DER size ${derHigh.size}" }
        ok("M01-203", "DER to raw: both integers have the high bit set, so each DER integer is 33 bytes with a 0x00 sign byte.", "derToRaw", "derHex", derHigh, "rawHex", raw(rHigh, sHigh))
        ok("M01-204", "Low-S normalisation: (r, s) with s > n/2 becomes (r, n - s); r is unchanged.", "normaliseLowS", "rawHex", raw(rNormal, hi), "rawHex", raw(rNormal, n.subtract(hi)))
        val nonMinimal = byteArrayOf(0x30, 0x81.toByte()) + (derInt(rNormal) + derInt(sNormal)).let { byteArrayOf(it.size.toByte()) + it }
        bad("M01-205", "DER with a long-form length (0x81) where the short form suffices: not minimal, rejected.", "derToRaw", "derHex", nonMinimal)
        bad("M01-206", "A raw signature of 63 bytes is rejected.", "normaliseLowS", "rawHex", raw(rNormal, sNormal).copyOf(63))
        bad("M01-207", "A raw signature of 65 bytes is rejected.", "rawToDer", "rawHex", raw(rNormal, sNormal) + byteArrayOf(1))
        bad("M01-208", "A DER integer that is negative (high bit set, no 0x00 sign byte).", "derToRaw", "derHex", derSeq(byteArrayOf(0x02, 0x01, 0x80.toByte()) + derInt(sNormal)))
        bad("M01-209", "A DER integer with a redundant leading 0x00.", "derToRaw", "derHex", derSeq(byteArrayOf(0x02, 0x02, 0x00, 0x01) + derInt(sNormal)))
        bad("M01-210", "Trailing bytes after the DER sequence.", "derToRaw", "derHex", derSeq(derInt(rNormal) + derInt(sNormal)) + byteArrayOf(0))
        ok("M01-211", "Raw to DER: the minimal DER of a signature with a leading-zero r and a high s.", "rawToDer", "rawHex", raw(rZero, sHigh), "derHex", derSeq(derInt(rZero) + derInt(sHigh)))
        ok("M01-212", "Low-S normalisation leaves a signature that is already low-S unchanged.", "normaliseLowS", "rawHex", raw(rNormal, sNormal), "rawHex", raw(rNormal, sNormal))
        return out
    }

    @Test
    fun regenerate() {
        if (System.getProperty("asom.lab.regen") != "true") return
        val (accept, reject) = verifierVectors()
        write("manifest/M02-verify-accept.json", VectorJson.envelope("M02", listOf("LAB_SPEC.md 4.6", "LAB_SPEC.md 4.9", "manifest.md 16.2"), accept.map { VectorJson.caseJson(it, Verifier.verify(docOf(it), it.ctx.toVerifyContext(it.nowMs))) }))
        write("manifest/M03-verify-reject.json", VectorJson.envelope("M03", listOf("LAB_SPEC.md 4.6", "LAB_SPEC.md 4.9", "manifest.md 16.2"), reject.map { VectorJson.caseJson(it, Verifier.verify(docOf(it), it.ctx.toVerifyContext(it.nowMs))) }))
        val acc = accept.associateBy { it.id }
        val rej = reject.associateBy { it.id }
        write("manifest/M05-render.json", VectorJson.envelope("M05", listOf("LAB_SPEC.md 4.8", "manifest.md 14"), textVectors(acc, rej)))
        write("manifest/M06-derivatives.json", VectorJson.envelope("M06", listOf("LAB_SPEC.md 4.7", "LAB_SPEC.md 4.9", "manifest.md 12"), derivativeVectors(acc)))
        write("manifest/M01-der-raw.json", VectorJson.envelope("M01", listOf("LAB_SPEC.md 4.4", "LAB_SPEC.md 4.9"), codecVectors()))
    }
}
