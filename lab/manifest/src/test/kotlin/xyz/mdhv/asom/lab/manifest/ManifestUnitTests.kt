package xyz.mdhv.asom.lab.manifest

import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.bench.Audience
import xyz.mdhv.asom.lab.bench.BenchCodec
import xyz.mdhv.asom.lab.bench.RenderOptions
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.bench.jo
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs

/** Every case a test runs is counted; a suite that ran nothing fails (LAB_SPEC R10). */
class Counter(private val name: String) {
    var n = 0
        private set

    fun hit() {
        n++
    }

    fun requireNonVacuous(min: Int = 1) {
        assertTrue(n >= min, "$name exercised $n cases, expected at least $min")
        println("non-vacuity: $name exercised $n cases")
    }
}

/** The keys file, the compiled table and the derived identifiers must agree (LAB_SPEC 4.5). */
class TestOnlyKeysTest {
    @Test
    fun theFileAndTheTableAreTheSameAndEveryDerivedFieldRecomputes() {
        val c = Counter("keys")
        val file = parseB(File(Repo.conformance, "keys/TEST-ONLY-keys.json").readBytes()) as JObject
        assertEquals("true", (file["TEST_ONLY"] as xyz.mdhv.asom.lab.json.JBool).value.toString())
        for (e in TestOnlyKeys.entries) {
            val k = file[e.name] as JObject
            fun s(n: String) = (k[n] as JString).value
            assertEquals(e.dHex, s("d_hex"))
            assertEquals(e.spkiB64, s("spki_b64"))
            assertEquals(e.nodeId, s("nodeId"))
            assertEquals(e.exportFingerprint.replace("-", ""), Spki.exportFingerprintPlain(e.spki))
            assertEquals(e.exportFingerprint, Spki.exportFingerprint(e.spki))
            assertEquals(s("exportFingerprint"), Spki.exportFingerprint(e.spki))
            assertEquals(s("fingerprint"), Spki.displayFingerprint(e.spki))
            assertEquals(s("nodeTag"), Spki.nodeTag(e.spki))
            assertEquals(Spki.nodeId(e.spki), e.nodeId)
            // the published scalar really is the private key of the published SPKI
            assertContentEquals(e.spki, P256Math.spkiOf(BigInteger(e.dHex, 16)), "${e.name}: d_hex does not give spki_b64")
            assertNotNull(Spki.strict(e.spki))
            assertTrue(TestOnlyKeys.isTestOnly(e.nodeId))
            c.hit()
        }
        assertEquals(4, TestOnlyKeys.entries.size)
        assertFalse(TestOnlyKeys.isTestOnly(Es256.generate().nodeId))
        c.requireNonVacuous(4)
    }
}

class Es256AndSpkiTest {
    private val n = P256.N

    @Test
    fun rfc6979AppendixA25SampleGivesTheDocumentedNonceAndSignature() {
        val d = "C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721"
        val msg = "sample".toByteArray()
        assertEquals("A6E3C57DD01ABE90086538398355DD4C3B17AA873382B0F24D6129493D8AAD60", Rfc6979.k(BigInteger(d, 16), msg).toString(16).uppercase())
        val raw = Rfc6979.sign(d, msg, lowS = false)
        assertEquals("EFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716", BigInteger(1, raw.copyOfRange(0, 32)).toString(16).uppercase())
        assertEquals("F7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8", BigInteger(1, raw.copyOfRange(32, 64)).toString(16).uppercase())
        val low = Rfc6979.sign(d, msg, lowS = true)
        assertEquals(n.subtract(BigInteger(1, raw.copyOfRange(32, 64))), BigInteger(1, low.copyOfRange(32, 64)))
        val spki = P256Math.spkiOf(BigInteger(d, 16))
        val pub = Spki.strict(spki)!!
        assertTrue(Es256.verify(pub, msg, raw), "the high-S form verifies (a consumer never demands low-S)")
        assertTrue(Es256.verify(pub, msg, low), "the low-S form verifies")
    }

    @Test
    fun theProducerAlwaysEmitsLowSAndTheVerifierAcceptsBothHalves() {
        val c = Counter("sign/verify")
        repeat(60) { i ->
            val kp = Es256.generate()
            val msg = "message $i".toByteArray()
            val sig = Es256.sign(kp.private, msg)
            assertEquals(64, sig.size)
            assertTrue(BigInteger(1, sig.copyOfRange(32, 64)) <= P256.HALF_N, "low-S")
            val pub = Spki.strict(kp.spki)!!
            assertTrue(Es256.verify(pub, msg, sig))
            val high = sig.copyOfRange(0, 32) + Es256.fixed32(n.subtract(BigInteger(1, sig.copyOfRange(32, 64))))
            assertTrue(Es256.verify(pub, msg, high), "high-S twin verifies")
            assertContentEquals(sig, Es256.normaliseLowS(high))
            assertContentEquals(sig, Es256.normaliseLowS(sig))
            assertFalse(Es256.verify(pub, msg + 1, sig), "another message")
            val flipped = sig.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
            assertFalse(Es256.verify(pub, msg, flipped), "a flipped bit")
            c.hit()
        }
        c.requireNonVacuous(60)
    }

    @Test
    fun rsOutsideOneToNMinusOneIsRefusedByTheRangeCheckAndByVerify() {
        val kp = Es256.generate()
        val pub = Spki.strict(kp.spki)!!
        val msg = "m".toByteArray()
        val good = Es256.sign(kp.private, msg)
        val r = good.copyOfRange(0, 32)
        val s = good.copyOfRange(32, 64)
        val zero = ByteArray(32)
        val nBytes = Es256.fixed32(n)
        for ((name, sig) in listOf("r=0" to zero + s, "s=0" to r + zero, "r=n" to nBytes + s, "s=n" to r + nBytes, "r=max" to ByteArray(32) { -1 } + s)) {
            assertFalse(Es256.rangeOk(sig), name)
            assertFalse(Es256.verify(pub, msg, sig), name)
        }
        assertTrue(Es256.rangeOk(good))
        assertFalse(Es256.rangeOk(good.copyOf(63)))
        assertFalse(Es256.verify(pub, msg, good.copyOf(63)))
        assertFalse(Es256.verify(pub, msg, good + byteArrayOf(0)))
    }

    @Test
    fun strictSpkiAcceptsOnlyThe91ByteUncompressedP256Form() {
        val c = Counter("spki")
        val ok = F.key1.spki
        assertNotNull(Spki.strict(ok))
        val bad = mutableListOf<Pair<String, ByteArray>>()
        bad += "empty" to ByteArray(0)
        bad += "one byte short" to ok.copyOf(90)
        bad += "one byte long" to ok + byteArrayOf(0)
        bad += "prefix byte flipped" to ok.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        bad += "compressed marker" to ok.copyOf().also { it[26] = 3 }
        bad += "hybrid marker" to ok.copyOf().also { it[26] = 6 }
        bad += "y flipped: off the curve" to ok.copyOf().also { it[90] = (it[90].toInt() xor 1).toByte() }
        bad += "x flipped: off the curve" to ok.copyOf().also { it[30] = (it[30].toInt() xor 2).toByte() }
        bad += "point at infinity encoding" to ok.copyOf().also { for (i in 27 until 91) it[i] = 0 }
        bad += "secp256k1 OID" to ok.copyOf().also { it[22] = (it[22].toInt() xor 0x01).toByte() }
        bad += "x = p" to ok.copyOf().also { System.arraycopy(Es256.fixed32(P256.P), 0, it, 27, 32) }
        for ((name, spki) in bad) {
            assertNull(Spki.strict(spki), name)
            c.hit()
        }
        c.requireNonVacuous(11)
    }

    @Test
    fun identifiersAndFingerprintsHaveTheDocumentedWorkedValues() {
        val k = F.key1
        assertEquals("vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4", Spki.nodeId(k.spki))
        assertEquals("xwwd3xqw7teb", Spki.nodeTag(k.spki).take(12))
        assertEquals("XWWD-3XQW-7TEB-MU27", Spki.displayFingerprint(k.spki))
        assertEquals("XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY", Spki.exportFingerprint(k.spki))
        assertEquals("XWWD3XQW7TEBMU27MLPG3CBTVY", Spki.exportFingerprintPlain(k.spki))
        assertEquals("XWWD3XQW7TEBMU27MLPG3CBTVY", Spki.normaliseFingerprint("xwwd3 xqw7t-ebmu 27ml pg3c-btvy"))
        assertEquals(43, Spki.nodeId(k.spki).length)
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(k.spki), Spki.pin(k.spki))
    }

    @Test
    fun derCodecRoundTripsAndRejectsNonMinimalForms() {
        val c = Counter("der codec")
        val r = SplittableRandom(9L)
        repeat(300) {
            val raw = ByteArray(64).also { b -> for (i in b.indices) b[i] = r.nextInt(256).toByte(); if (it % 3 == 0) b[0] = 0; if (it % 5 == 0) b[32] = 0 }
            val der = (SigCodec.rawToDer(raw) as SigCodec.Result.Ok).bytes
            assertContentEquals(raw, (SigCodec.derToRaw(der) as SigCodec.Result.Ok).bytes)
            c.hit()
        }
        val raw = ByteArray(64) { (it + 1).toByte() }
        val der = (SigCodec.rawToDer(raw) as SigCodec.Result.Ok).bytes
        val longForm = byteArrayOf(0x30, 0x81.toByte(), der[1]) + der.copyOfRange(2, der.size)
        assertTrue(SigCodec.derToRaw(longForm) is SigCodec.Result.Reject)
        assertTrue(SigCodec.derToRaw(der + byteArrayOf(0)) is SigCodec.Result.Reject)
        assertTrue(SigCodec.derToRaw(der.copyOf(der.size - 1)) is SigCodec.Result.Reject)
        assertTrue(SigCodec.derToRaw(ByteArray(0)) is SigCodec.Result.Reject)
        assertTrue(SigCodec.rawToDer(ByteArray(63)) is SigCodec.Result.Reject)
        assertTrue(SigCodec.normaliseRaw(ByteArray(65)) is SigCodec.Result.Reject)
        c.requireNonVacuous(300)
    }
}

class DsseTest {
    @Test
    fun paeMatchesTheSpecificationExampleAndTheAsomType() {
        val pae = Dsse.pae("http://example.com/HelloWorld", "hello world".toByteArray())
        assertEquals("DSSEv1 29 http://example.com/HelloWorld 11 hello world", String(pae, Charsets.UTF_8))
        assertEquals(54, pae.size)
        val body = "{}".toByteArray()
        assertEquals("DSSEv1 37 application/vnd.asom.manifest.v1+json 2 {}", String(Dsse.pae(Dsse.PT_MANIFEST_V1, body), Charsets.UTF_8))
        // lengths are byte counts, not character counts
        assertEquals("DSSEv1 3 aé 1 x".length, "DSSEv1 3 aé 1 x".length)
        assertEquals("DSSEv1 3 aé 1 x", String(Dsse.pae("aé", "x".toByteArray()), Charsets.UTF_8))
    }

    @Test
    fun theSizeLimitsAreTheLabSpecOnes() {
        assertEquals(524_288, Dsse.MAX_CONTAINER_BYTES)
        assertEquals(262_144, Dsse.MAX_PAYLOAD_BYTES)
        val doc = F.container(DocSpec(F.payloadOf(F.ownObj())))
        assertTrue(Dsse.MAX_CONTAINER_BYTES > doc.size)
        val big = doc + ByteArray(Dsse.MAX_CONTAINER_BYTES - doc.size + 1) { ' '.code.toByte() }
        assertEquals(RejectCode.TOO_LARGE, F.rejectOf(F.verify(big, F.ctxMesh())))
        assertEquals("1", (F.verify(big, F.ctxMesh()) as Rejected).step)
        // exactly at the limit passes step 1 (and then fails later, because the padding is trailing whitespace: JSON accepts it)
        val exact = doc + ByteArray(Dsse.MAX_CONTAINER_BYTES - doc.size) { ' '.code.toByte() }
        assertEquals(Dsse.MAX_CONTAINER_BYTES, exact.size)
        assertTrue(F.verify(exact, F.ctxMesh()) is Verified)
    }

    @Test
    fun aDecodedPayloadOverTheLimitIsTooLargeAtStepSix() {
        // 262,145 bytes of payload: signed correctly, the container itself is under 524,288 bytes
        val pad = "x".repeat(Dsse.MAX_PAYLOAD_BYTES + 1 - F.payloadOf(F.ownObj()).size - 30)
        val big = F.payloadOf(JEdit.set(F.ownObj(), listOf("pad"), js(pad)))
        val payload = big + ByteArray(Dsse.MAX_PAYLOAD_BYTES + 1 - big.size) { ' '.code.toByte() }
        assertEquals(Dsse.MAX_PAYLOAD_BYTES + 1, payload.size)
        val doc = F.container(DocSpec(payload))
        assertTrue(doc.size < Dsse.MAX_CONTAINER_BYTES, "the container is ${doc.size} bytes")
        val r = F.verify(doc, F.ctxMesh()) as Rejected
        assertEquals(RejectCode.TOO_LARGE, r.code)
        assertEquals("6", r.step)
    }
}

/** A composable defect: it edits a build description; each defect fails at exactly one step of LAB_SPEC 4.6. */
private class Build {
    var obj: JValue = F.ownObj()
    var spec: (DocSpec) -> DocSpec = { it }
    var containerEdits = mutableListOf<(JValue) -> JValue>()
    var byteEdits = mutableListOf<(ByteArray) -> ByteArray>()
    var nonCanonical = false
    var challenge: ByteArray? = F.challenge1
    var now = F.NOW
    var tier = Tier.A0
    var production = false
    var confFloor = F.CONF
    var rollback: Map<String, RollbackEntry> = emptyMap()

    fun result(): VerifyResult {
        var payload = if (nonCanonical) ManifestCases.compact(JObject((obj as JObject).members.reversed())).toByteArray() else F.payloadOf(obj)
        var ds = spec(DocSpec(payload))
        var doc = F.container(ds)
        if (containerEdits.isNotEmpty()) {
            var c = parseB(doc)
            for (e in containerEdits) c = e(c)
            doc = Jcs.serialize(c)
        }
        for (e in byteEdits) doc = e(doc)
        val ctx = VCtx(Mode.MESH, F.key1.spki, challenge, rollback = rollback, requiredTier = tier, confFloor = confFloor, production = production).toVerifyContext(now)
        return Verifier.verify(doc, ctx)
    }
}

private class Defect(val name: String, val code: RejectCode, val step: String, val apply: (Build) -> Unit)

class StepOrderMatrixTest {
    private val other = F.key2
    private val defects: List<Defect> = listOf(
        Defect("size", RejectCode.TOO_LARGE, "1") { b -> b.byteEdits += { it + ByteArray(Dsse.MAX_CONTAINER_BYTES) { ' '.code.toByte() } } },
        Defect("trailing", RejectCode.TRAILING_DATA, "2") { b -> b.byteEdits += { it + "x".toByteArray() } },
        Defect("version", RejectCode.CONTAINER_VERSION_UNKNOWN, "3") { b -> b.containerEdits += { JEdit.set(it, listOf("asomCapabilityManifest"), ji(2)) } },
        Defect("type", RejectCode.PAYLOAD_TYPE_UNSUPPORTED, "4") { b -> val prev = b.spec; b.spec = { s -> DocSpec(prev(s).payload, type = "application/vnd.asom.other.v1+json") } },
        Defect("sigcount", RejectCode.SIGNATURE_COUNT, "5") { b -> val prev = b.spec; b.spec = { s -> prev(s).let { p -> DocSpec(p.payload, type = p.type, extraSigs = 1) } } },
        Defect("sigenc", RejectCode.SIGNATURE_ENCODING, "6") { b -> val prev = b.spec; b.spec = { s -> prev(s).let { p -> DocSpec(p.payload, type = p.type, extraSigs = p.extraSigs, sig = ByteArray(63)) } } },
        Defect("keyid", RejectCode.KEY_NOT_PINNED, "7") { b -> val prev = b.spec; b.spec = { s -> prev(s).let { p -> DocSpec(p.payload, type = p.type, extraSigs = p.extraSigs, sig = p.sig, keyid = other.nodeId) } } },
        Defect("testonly", RejectCode.TEST_ONLY_KEY, "7") { b -> b.production = true },
        Defect("badsig", RejectCode.SIGNATURE_INVALID, "8") { b -> val prev = b.spec; b.spec = { s -> prev(s).let { p -> DocSpec(p.payload, type = p.type, extraSigs = p.extraSigs, sig = p.sig, keyid = p.keyid, signOver = "other".toByteArray()) } } },
        Defect("noncanonical", RejectCode.NON_CANONICAL, "10") { b -> b.nonCanonical = true },
        Defect("schema", RejectCode.SCHEMA_INVALID, "11") { b -> b.obj = JEdit.set(b.obj, listOf("zz"), ji(1)) },
        Defect("subject", RejectCode.SUBJECT_KEY_MISMATCH, "12") { b -> b.obj = JEdit.set(b.obj, listOf("body", "subject", "nodeId"), js(other.nodeId)) },
        Defect("expired", RejectCode.EXPIRED, "13") { b -> b.now = F.ISSUED + 600_000L },
        Defect("nonce", RejectCode.NONCE_MISMATCH, "14") { b -> b.challenge = MessageDigest.getInstance("SHA-256").digest("other".toByteArray()) },
        Defect("inconsistent", RejectCode.INCONSISTENT, "15") { b -> b.obj = JEdit.set(b.obj, listOf("body", "results", 1, "sustained", "steadyMilliTokPerSec"), ji(99_999_999)) },
        Defect("floor", RejectCode.DERIVATION_MISMATCH, "15a") { b -> b.confFloor = "0.3.0" },
        Defect("rollback", RejectCode.ROLLBACK, "16") { b -> b.rollback = mapOf("${F.key1.nodeId}|own" to RollbackEntry(F.SEQ + 1, "x".repeat(43))) },
        Defect("tier", RejectCode.TIER_INSUFFICIENT, "18") { b -> b.tier = Tier.A2 },
    )

    @Test
    fun theUnbrokenBaselineVerifies() {
        val r = Build().result()
        assertTrue(r is Verified, "baseline: $r")
    }

    @Test
    fun eachDefectAloneGivesItsOwnCodeAndStep() {
        val c = Counter("single defects")
        for (d in defects) {
            val b = Build()
            d.apply(b)
            val r = b.result()
            assertTrue(r is Rejected, "${d.name} must be refused, got $r")
            r as Rejected
            assertEquals(d.code, r.code, d.name)
            assertEquals(d.step, r.step, d.name)
            c.hit()
        }
        c.requireNonVacuous(defects.size)
    }

    @Test
    fun whenTwoDefectsMeetTheEarlierStepWinsForEveryPair() {
        val c = Counter("defect pairs")
        for (i in defects.indices) for (j in i + 1 until defects.size) {
            val b = Build()
            // later defects first, so that an earlier defect's transform is applied last where it matters
            defects[j].apply(b)
            defects[i].apply(b)
            val r = b.result()
            assertTrue(r is Rejected, "${defects[i].name}+${defects[j].name} must be refused")
            r as Rejected
            assertEquals(defects[i].code, r.code, "${defects[i].name}+${defects[j].name}: the first failing step wins")
            assertEquals(defects[i].step, r.step, "${defects[i].name}+${defects[j].name}")
            c.hit()
        }
        assertEquals(defects.size * (defects.size - 1) / 2, c.n)
        c.requireNonVacuous(153)
    }

    @Test
    fun onlyStepsThirteenToSixteenMayBeDisplayedAsContent() {
        val c = Counter("display rule")
        for (d in defects) {
            val b = Build()
            d.apply(b)
            val r = b.result() as Rejected
            val n = r.step.takeWhile { it.isDigit() }.toInt()
            assertEquals(n in 13..16, r.displayable, "${d.name} (step ${r.step})")
            c.hit()
        }
        c.requireNonVacuous(defects.size)
    }
}

/** Single-bit flips over a signed container: nothing may verify unless the flipped byte is one the verifier never reads. */
class BitFlipFuzzTest {
    private fun ignoredRanges(doc: ByteArray, mode: Mode): List<IntRange> {
        val text = String(doc, Charsets.UTF_8)
        val out = mutableListOf<IntRange>()
        // the name of an optional member the verifier does not require (evidence), and unknown-member names
        text.indexOf("\"evidence\"").takeIf { it >= 0 }?.let { out += it..it + 9 }
        // keyid is an optional hint: a flip inside its NAME makes it an unknown member (its VALUE is checked and a flip there rejects)
        text.indexOf("\"keyid\"").takeIf { it >= 0 }?.let { out += it..it + 6 }
        // in MESH the signer.spki hint is never read
        if (mode == Mode.MESH) {
            val s = text.indexOf("\"signer\":")
            if (s >= 0) {
                var depth = 0
                var i = text.indexOf('{', s)
                val start = s
                while (i < text.length) {
                    if (text[i] == '{') depth++
                    if (text[i] == '}') { depth--; if (depth == 0) break }
                    i++
                }
                out += start..i
            }
        }
        return out
    }

    private fun fuzz(name: String, doc: ByteArray, ctx: VerifyContext, mode: Mode, c: Counter) {
        val base = Verifier.verify(doc, ctx) as Verified
        val ignore = ignoredRanges(doc, mode)
        var accepted = 0
        for (pos in doc.indices) {
            for (bit in listOf(pos % 8, (pos * 3 + 1) % 8).distinct()) {
                val d = doc.copyOf()
                d[pos] = (d[pos].toInt() xor (1 shl bit)).toByte()
                val r = Verifier.verify(d, ctx)
                if (r is Verified) {
                    assertTrue(ignore.any { pos in it }, "$name: flipping bit $bit of byte $pos (${String(doc, pos, 1)}) still verifies outside the ignored members")
                    assertEquals(base.bodyDigest, r.bodyDigest, "$name: a flip changed the verified body")
                    accepted++
                }
                c.hit()
            }
        }
        assertTrue(accepted < doc.size / 4, "$name: too many accepted flips ($accepted)")
    }

    @Test
    fun ownContainerInMeshMode() {
        val c = Counter("bit flips (own)")
        fuzz("own", F.container(DocSpec(F.payloadOf(F.ownObj()))), F.ctxMesh(), Mode.MESH, c)
        c.requireNonVacuous(10_000)
    }

    @Test
    fun fileContainerInFileMode() {
        val c = Counter("bit flips (file)")
        val doc = F.container(DocSpec(F.payloadOf(F.fileObj()), signKey = F.key3))
        fuzz("file", doc, F.ctxFile(F.key3.exportFingerprint, CompareMethod.TYPED), Mode.FILE, c)
        c.requireNonVacuous(8_000)
    }
}

class SignerTest {
    private val inputs = F.inputs()

    @Test
    fun theSeqRuleHoldsAndIsDurableBeforeTheFirstSignature() {
        val dir = java.nio.file.Files.createTempDirectory("asom-seq").toFile()
        try {
            val store = FileSeqStore(File(dir, "seq"))
            val signer = ManifestSigner(F.CONF, productionKeys = false)
            val key = F.key1.keyPair()
            val t0 = F.ISSUED
            val a = signer.signOwn(inputs, key, "strongbox", F.challenge1, t0, store)
            assertEquals(t0 / 1000L, a.verified.obj.body.seq, "the first seq is max(stored + 1, now / 1000)")
            val again = signer.signOwn(inputs, key, "strongbox", F.challenge1, t0 + 5_000L, store)
            assertEquals(a.verified.obj.body.seq, again.verified.obj.body.seq, "the same body keeps its seq")
            assertEquals(a.verified.bodyDigest, again.verified.bodyDigest)
            // a changed body takes max(stored + 1, now / 1000), even when the clock went BACKWARDS
            val changed = F.inputs(F.benchFrom("""{"preset":"desktop","plan":"standard"}"""))
            val b = signer.signOwn(changed, key, "strongbox", F.challenge1, t0 - 3_600_000L, FileSeqStore(File(dir, "seq")))
            assertEquals(a.verified.obj.body.seq!! + 1, b.verified.obj.body.seq, "a backwards clock never lowers seq")
            assertNotEquals(a.verified.bodyDigest, b.verified.bodyDigest)
            // the value is on disk (a second store instance reads it back)
            val stored = FileSeqStore(File(dir, "seq")).load()!!
            assertEquals(b.verified.obj.body.seq, stored.seq)
            assertTrue(File(dir, "seq").isFile && !File(dir, "seq.tmp").exists(), "atomic move left no temp file")
            // durability precedes the signature: a signer whose own verifier refuses (confFloor above the document's) has already persisted the new seq
            val strict = ManifestSigner("9.9.9", productionKeys = false)
            val store2 = InMemorySeqStore()
            assertFailsWith<ManifestUnavailable> { strict.signOwn(inputs, key, "strongbox", F.challenge1, t0, store2) }
            assertNotNull(store2.load(), "seq was persisted before the signature was attempted")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun seqIsMonotonicAcrossManyChangesAndNeverRepeatsForDifferentContent() {
        val c = Counter("seq changes")
        val store = InMemorySeqStore()
        val signer = ManifestSigner(F.CONF, productionKeys = false)
        val key = F.key1.keyPair()
        val seen = mutableMapOf<Long, String>()
        var last = 0L
        val scenarios = listOf("""{"preset":"phone","plan":"standard"}""", """{"preset":"desktop","plan":"standard"}""", """{"preset":"phone","plan":"quick"}""", """{"preset":"desktop","plan":"ci"}""")
        for (round in 0 until 8) {
            val inp = F.inputs(F.benchFrom(scenarios[round % scenarios.size]))
            val s = signer.signOwn(inp, key, "strongbox", F.challenge1, F.ISSUED + round * 1000L, store)
            val seq = s.verified.obj.body.seq!!
            assertTrue(seq >= last, "seq never decreases")
            val prev = seen.put(seq, s.verified.bodyDigest)
            assertTrue(prev == null || prev == s.verified.bodyDigest, "one seq, one body")
            last = seq
            c.hit()
        }
        c.requireNonVacuous(8)
    }

    @Test
    fun productionModeRefusesTestOnlyKeysAndAChallengeMustBe32Bytes() {
        val strict = ManifestSigner(F.CONF, productionKeys = true)
        val ex = assertFailsWith<ManifestUnavailable> { strict.signOwn(inputs, F.key1.keyPair(), "strongbox", F.challenge1, F.ISSUED, InMemorySeqStore()) }
        assertEquals(RejectCode.TEST_ONLY_KEY, ex.code)
        assertFailsWith<IllegalArgumentException> { ManifestSigner(F.CONF, productionKeys = false).signOwn(inputs, F.key1.keyPair(), "strongbox", ByteArray(31), F.ISSUED, InMemorySeqStore()) }
        // a fresh random key is fine in production mode
        strict.signOwn(inputs, Es256.generate(), "strongbox", F.challenge1, F.ISSUED, InMemorySeqStore())
    }

    @Test
    fun theFileExportUsesAFreshKeyAndCarriesNoStableDeviceIdentifier() {
        val c = Counter("file exports")
        val signer = ManifestSigner(F.CONF, productionKeys = false)
        val seenIds = mutableSetOf<String>()
        repeat(4) {
            val e = signer.signFile(inputs, F.ISSUED)
            val obj = e.verified.obj
            assertNull(obj.body.seq)
            assertEquals(Audience.FILE, obj.body.audience)
            assertEquals("ephemeral", obj.body.subject.keyStorage)
            assertNotEquals(F.key1.nodeId, obj.body.subject.nodeId)
            assertTrue(seenIds.add(obj.body.subject.nodeId), "every export has its own key")
            assertNull(obj.body.device.platformIds)
            assertNull(obj.body.device.os.securityPatch)
            assertEquals(0L, obj.presentation.issuedAtMs % xyz.mdhv.asom.lab.bench.DAY_MS)
            val text = String(e.container, Charsets.UTF_8)
            assertFalse(text.contains(F.key1.nodeId))
            assertEquals(PinState.ByFingerprint(CompareMethod.TYPED).wire, e.verified.pin.wire)
            c.hit()
        }
        c.requireNonVacuous(4)
    }

    @Test
    fun theFileProjectionOfAnOwnBodyEqualsTheDirectFileBody() {
        val ownVerified = F.verify(F.container(DocSpec(F.payloadOf(F.ownObj()))), F.ctxMesh()) as Verified
        val via = FileProjection.fromOwn(ownVerified.obj.body, F.key3.nodeId)
        val direct = F.fileBody(F.key3, inputs)
        assertContentEquals(Jcs.serialize(direct), Jcs.serialize(via), "projectFile(bodyOwn) is one function")
        val json = Jcs.serialize(via).toString(Charsets.UTF_8)
        for (banned in listOf("platformIds", "securityPatch", "\"seq\"", "batteryStartPermille\":7", "screenOn\":t", F.key1.nodeId)) assertFalse(json.contains(banned), "FILE body must not contain $banned")
    }
}

/** The public derivative, quantisation and the renderers: the LM laws of LAB_SPEC 4.8. */
class DerivativeAndTextLawsTest {
    private val verified = F.verify(F.container(DocSpec(F.payloadOf(F.ownObj()))), F.ctxMesh()) as Verified
    private val catalogue = verified.obj.body.results.map { it.fileSha256 }.toSet()

    private fun names(v: JValue, out: MutableSet<String> = mutableSetOf()): Set<String> {
        when (v) {
            is JObject -> v.members.forEach { (k, x) -> out += k; names(x, out) }
            is JArray -> v.items.forEach { names(it, out) }
            else -> {}
        }
        return out
    }

    @Test
    fun q2IsIdempotentMonotoneAndWithinHalfAPercentOfTwoDigits() {
        val c = Counter("q2")
        val r = SplittableRandom(4L)
        var prev = 0L
        for (x in 0L..3000L) {
            val q = PublicDerivative.q2(x)
            assertEquals(q, PublicDerivative.q2(q))
            assertTrue(q >= prev, "q2 is monotone")
            prev = q
            if (x >= 100L) assertTrue(kotlin.math.abs(q - x) * 100L <= x * 5L / 1L, "at most 5% off for two significant digits")
            c.hit()
        }
        repeat(2000) {
            val x = r.nextLong(1L, 4_000_000_000_000L)
            assertEquals(PublicDerivative.q2(x), PublicDerivative.q2(PublicDerivative.q2(x)))
            c.hit()
        }
        assertEquals(0L, PublicDerivative.q2(0L))
        assertEquals(99L, PublicDerivative.q2(99L))
        assertEquals(150L, PublicDerivative.q2(149L + 1L))
        assertEquals(1000L, PublicDerivative.q2(995L))
        assertEquals(990L, PublicDerivative.q2(994L))
        c.requireNonVacuous(5000)
    }

    @Test
    fun ramClassesAreTheSmallestClassThatHoldsTheMemory() {
        assertEquals(16L, PublicDerivative.ramClassGib(16_000_000_000L))
        assertEquals(1L, PublicDerivative.ramClassGib(1L))
        assertEquals(24L, PublicDerivative.ramClassGib(17L * (1L shl 30)))
        assertEquals(2048L, PublicDerivative.ramClassGib(Long.MAX_VALUE))
        for (i in 1 until PublicDerivative.RAM_CLASSES_GIB.size) assertTrue(PublicDerivative.RAM_CLASSES_GIB[i] > PublicDerivative.RAM_CLASSES_GIB[i - 1])
    }

    @Test
    fun theDerivativeCarriesNoForbiddenNameAndEmitsOnlyAllowListedVersions() {
        val c = Counter("public derivative")
        for ((allow, coarse) in listOf(
            ReleaseAllowList(emptySet(), emptySet()) to CoarseDevices(),
            ReleaseAllowList(setOf("01234567"), setOf("1.0.0", "0.2.0")) to CoarseDevices(setOf("Example"), setOf("Phone X1 (synthetic)")),
        )) {
            val out = PublicDerivative.from(verified.obj, catalogue, allow, coarse)!!
            val n = names(out)
            for (banned in PublicDerivative.FORBIDDEN_NAMES) assertFalse(banned in n, "the public derivative must not carry $banned")
            val text = Jcs.serialize(out).toString(Charsets.UTF_8)
            assertFalse(text.contains(F.key1.nodeId))
            assertFalse(text.contains("EXAMPLE.260901.001"), "OS build")
            assertFalse(text.contains("example-512.0"), "GPU driver")
            val engine = (out["engine"] as JObject)
            assertEquals(if ("01234567" in allow.engineCommits) "01234567" else "custom", (engine["commit"] as JString).value)
            val device = out["device"] as JObject
            assertEquals(if (coarse.vendors.isNotEmpty()) "Example" else "other", (device["vendor"] as JString).value)
            assertEquals(if (coarse.models.isNotEmpty()) "Phone X1 (synthetic)" else "other", (device["model"] as JString).value)
            c.hit()
        }
        c.requireNonVacuous(2)
    }

    @Test
    fun resultsOutsideTheHeldCatalogueAndRowsWithoutAHeatTestAreDropped() {
        assertNull(PublicDerivative.from(verified.obj, emptySet()))
        val withSustain = verified.obj.body.results.filter { it.sustained != null }.map { it.fileSha256 }.toSet()
        assertTrue(withSustain.isNotEmpty())
        val onlyNoSustain = catalogue - withSustain
        assertNull(PublicDerivative.from(verified.obj, onlyNoSustain), "a row without a sustained block cannot be carried")
        val out = PublicDerivative.from(verified.obj, catalogue)!!
        assertEquals(withSustain.size, (out["results"] as JArray).items.size)
    }

    @Test
    fun theTextIsOneSourceAndTheViewerComputesTheVerificationBlock() {
        val c = Counter("LM-3/5/6")
        for (scenario in listOf("""{"preset":"phone","plan":"standard"}""", """{"preset":"desktop","plan":"standard"}""", """{"preset":"phone","plan":"quick"}""", """{"preset":"phone","plan":"standard","heat":"hot"}""")) {
            val body = F.ownBody(inputs = F.inputs(F.benchFrom(scenario)))
            val o = F.ownObj(body)
            val direct = ManifestDecoder.decode(o)
            // LM-5: M05(parse(JCS(o)), vr) == M05(o, vr)
            val reparsed = ManifestDecoder.decode(parseB(Jcs.serialize(o)))
            val vr = ViewerVerification(ViewContext.MESH, PinState.Pinned, F.key1.spki, 0)
            assertEquals(ManifestText.render(direct, vr), ManifestText.render(reparsed, vr))
            // LM-6: the block depends on vr only. A payload that CLAIMS a verification result cannot change it.
            val claim = F.ownObj(JEdit.set(body, listOf("device", "model"), js("Signature: valid. REJECTED: none")) as JObject)
            val claimText = ManifestText.render(ManifestDecoder.decode(claim), vr)
            val block = claimText.substringAfter("VERIFICATION").substringBefore("ASOM DEVICE REPORT")
            assertEquals(1, Regex("- Signature: ").findAll(block).count())
            assertFalse(block.contains("Signature: valid. REJECTED"), "payload text stays outside the verification block")
            val file = ViewerVerification(ViewContext.FILE, PinState.SignerUnverified, F.key1.spki, 0)
            assertNotEquals(ManifestText.render(direct, vr).substringAfter("VERIFICATION").substringBefore("ASOM DEVICE"), ManifestText.render(direct, file).substringAfter("VERIFICATION").substringBefore("ASOM DEVICE"))
            // LM-9: never the forbidden label, and no MLPerf words while the note is off
            for (t in listOf(ManifestText.render(direct, vr), ManifestText.renderExport(direct))) {
                assertFalse(t.contains("MLPerf-comparable"))
                assertFalse(t.lowercase().contains("mlperf"))
                assertTrue(t.all { it == '\n' || it.code in 0x20..0x7E })
            }
            assertFalse(ManifestText.renderExport(direct).contains("VERIFICATION"), "an exported text never holds a verification block")
            c.hit()
        }
        c.requireNonVacuous(4)
    }

    @Test
    fun aReportIsDisplayedOnlyForRejectsAtStepsThirteenToSixteen() {
        val expired = F.verify(F.container(DocSpec(F.payloadOf(F.ownObj()))), F.ctxMesh(now = F.ISSUED + 600_000L)) as Rejected
        assertEquals("13", expired.step)
        assertNotNull(ManifestText.viewerFor(expired, Mode.MESH, PinState.Pinned))
        val badSig = F.verify(F.container(DocSpec(F.payloadOf(F.ownObj()), signOver = "x".toByteArray())), F.ctxMesh()) as Rejected
        assertEquals("8", badSig.step)
        assertNull(ManifestText.viewerFor(badSig, Mode.MESH, PinState.Pinned))
        val tier = F.verify(F.container(DocSpec(F.payloadOf(F.ownObj()))), F.ctxMesh(requiredTier = Tier.A2)) as Rejected
        assertEquals("18", tier.step)
        assertNull(ManifestText.viewerFor(tier, Mode.MESH, PinState.Pinned), "step 18 is not displayable")
    }
}

/** Checked arithmetic in `consistency()` and the verifier: an overflow is INCONSISTENT, never a wrapped number. */
class VerifierArithmeticTest {
    @Test
    fun anOverflowingProductInTheBodyIsInconsistentNotACrash() {
        val big = JEdit.set(F.ownObj(), listOf("body", "bench", "tiers", 0, "kvBytesPerToken"), ji(9_007_199_254_740_991L))
        val r = F.verify(F.container(DocSpec(F.payloadOf(big))), F.ctxMesh())
        assertTrue(r is Rejected, "$r")
        assertTrue((r as Rejected).code == RejectCode.INCONSISTENT || r.code == RejectCode.SCHEMA_INVALID, "got ${r.code}")
    }

    @Test
    fun aRollbackTableThatHoldsALowerSeqIsUpdatedAndAHigherOneRefuses() {
        val store = InMemoryRollbackStore()
        val doc = F.container(DocSpec(F.payloadOf(F.ownObj())))
        val v1 = F.verify(doc, F.ctxMesh(rollback = store)) as Verified
        store.commit(v1.rollbackUpdate!!)
        assertTrue(F.verify(doc, F.ctxMesh(rollback = store)) is Verified, "the same body again is fine")
        val newer = F.container(DocSpec(F.payloadOf(F.ownObj(F.ownBody(seq = F.SEQ + 5)))))
        val v2 = F.verify(newer, F.ctxMesh(rollback = store)) as Verified
        store.commit(v2.rollbackUpdate!!)
        assertEquals(RejectCode.ROLLBACK, F.rejectOf(F.verify(doc, F.ctxMesh(rollback = store))))
        // a reject commits nothing
        val before = store.get(F.key1.nodeId, "own")!!.seq
        F.verify(doc, F.ctxMesh(rollback = store))
        assertEquals(before, store.get(F.key1.nodeId, "own")!!.seq)
    }

    @Test
    fun theHexAndBase64HelpersUsedByTheVectorsRoundTrip() {
        val b = ByteArray(40) { it.toByte() }
        assertContentEquals(b, Hex.decode(Hex.encode(b)))
        assertEquals(43, Base64Strict.encodeUrlNoPad(ByteArray(32)).length)
    }
}

/** Boundaries that a mutation run showed no other test pinned. */
class BoundaryTest {
    private val doc get() = F.container(DocSpec(F.payloadOf(F.ownObj())))

    @Test
    fun theFutureSkewIsInclusiveAtFiveMinutes() {
        assertTrue(F.verify(doc, F.ctxMesh(now = F.ISSUED - 300_000L)) is Verified, "issuedAt == now + 300,000 accepts")
        val r = F.verify(doc, F.ctxMesh(now = F.ISSUED - 300_001L)) as Rejected
        assertEquals(RejectCode.NOT_YET_VALID, r.code)
        assertEquals("13", r.step)
    }

    @Test
    fun expiryAndTtlBoundaries() {
        assertTrue(F.verify(doc, F.ctxMesh(now = F.ISSUED + 600_000L - 1)) is Verified)
        assertEquals(RejectCode.EXPIRED, F.rejectOf(F.verify(doc, F.ctxMesh(now = F.ISSUED + 600_000L))))
        val ttl600 = F.container(DocSpec(F.payloadOf(JEdit.set(F.ownObj(), listOf("presentation"), F.ownPresentation(expires = F.ISSUED + 600_000L)))))
        assertTrue(F.verify(ttl600, F.ctxMesh()) is Verified, "a TTL of exactly 10 minutes is valid")
        val ttl601 = F.container(DocSpec(F.payloadOf(JEdit.set(F.ownObj(), listOf("presentation"), F.ownPresentation(expires = F.ISSUED + 600_001L)))))
        assertEquals(RejectCode.TTL_INVALID, F.rejectOf(F.verify(ttl601, F.ctxMesh())))
    }

    @Test
    fun onlyHardwareBackedStorageIsTierA1() {
        val c = Counter("key storage tiers")
        for (storage in listOf("strongbox", "tee", "secure-enclave", "tpm")) {
            val d = F.container(DocSpec(F.payloadOf(F.ownObj(F.ownBody(storage = storage)))))
            val v = F.verify(d, F.ctxMesh(requiredTier = Tier.A1)) as Verified
            assertEquals(Tier.A1, v.tier, storage)
            c.hit()
        }
        for (storage in listOf("os-keystore", "file", "unknown")) {
            val d = F.container(DocSpec(F.payloadOf(F.ownObj(F.ownBody(storage = storage)))))
            assertEquals(Tier.A0, (F.verify(d, F.ctxMesh()) as Verified).tier, storage)
            assertEquals(RejectCode.TIER_INSUFFICIENT, F.rejectOf(F.verify(d, F.ctxMesh(requiredTier = Tier.A1))), storage)
            c.hit()
        }
        c.requireNonVacuous(7)
    }

    @Test
    fun aProductThatWrapsToAPositiveNumberIsInconsistentNotAccepted() {
        val pfx = listOf<Any>("body", "results", 0, "prefill", 0)
        fun trio(v: Long) = jo("p10" to ji(v), "p50" to ji(v), "p90" to ji(v))
        val o = JEdit.set(JEdit.set(F.ownObj(), pfx + "ttftMicros", trio(1_900_000_000L)), pfx + "milliTokPerSec", trio(1_000_000_000L))
        // the wrapped 64-bit product is positive and larger than the right-hand side, so an unchecked implementation would pass step 15
        assertTrue(1_900_000_000L * 1_000_000_000L * 10L > 4_600_000_000_000L, "the wrapped product would pass the consistency test")
        val r = F.verify(F.container(DocSpec(F.payloadOf(o))), F.ctxMesh()) as Rejected
        assertEquals(RejectCode.INCONSISTENT, r.code)
        assertEquals("15", r.step)
    }

    @Test
    fun theStrictSpkiFormIsTheOnlyPathToAKey() {
        // a valid key with one trailing byte and one with a flipped prefix byte are both ALG_UNSUPPORTED at step 7
        val c = Counter("spki at step 7")
        for (spki in listOf(F.key1.spki + byteArrayOf(0), F.key1.spki.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }, F.key1.spki.copyOf(90))) {
            val d = F.container(DocSpec(F.payloadOf(F.ownObj()), keyid = null))
            val ctx = VerifyContext(Mode.MESH, pinnedSpki = spki, expectedChallenge = F.challenge1, confFloor = F.CONF, productionKeys = false, nowMs = F.NOW)
            assertEquals(RejectCode.ALG_UNSUPPORTED, F.rejectOf(Verifier.verify(d, ctx)))
            c.hit()
        }
        c.requireNonVacuous(3)
    }
}
