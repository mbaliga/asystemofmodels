package xyz.mdhv.asom.lab.manifest

import java.io.File
import java.math.BigInteger
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.bench.jsList
import xyz.mdhv.asom.lab.json.JValue

private fun ownDoc(path: List<Any>, value: JValue): ByteArray = F.container(DocSpec(F.payloadOf(JEdit.set(F.ownObj(), path, value))))

private fun assertReject(r: VerifyResult, code: RejectCode, step: String, what: String) {
    assertTrue(r is Rejected, "$what: expected $code at step $step, the verifier accepted")
    r as Rejected
    assertEquals(code, r.code, "$what: ${r.step} ${r.detail}")
    assertEquals(step, r.step, what)
}

/** CV-1: the signed body carries second copies of facts that body.bench already states; none may disagree with bench. */
class OneRecordTiesTest {
    @Test
    fun theUneditedDocumentVerifiesAndAShorterCommitThatIsAPrefixIsTheSameCommit() {
        assertTrue(F.verify(ownDoc(listOf("body", "producer", "app"), js("asom-android")), F.ctxMesh()) is Verified)
        val full = F.bench.harness.engine.commit
        assertTrue(F.verify(ownDoc(listOf("body", "producer", "engine", "commit"), js(full.take(7))), F.ctxMesh()) is Verified, "an abbreviation of the bench commit is the same commit")
        assertTrue(F.verify(ownDoc(listOf("body", "producer", "engine", "commit"), js(full)), F.ctxMesh()) is Verified, "the full commit is the same commit")
    }

    @Test
    fun everySecondCopyOfABenchFactMustAgreeWithBench() {
        val c = Counter("one-record ties")
        val cases = listOf(
            "producer confVersion below the floor" to (listOf("body", "producer", "harness", "confVersion") to js("0.0.1")),
            "producer confVersion above bench" to (listOf("body", "producer", "harness", "confVersion") to js("0.2.1")),
            "producer engine commit" to (listOf("body", "producer", "engine", "commit") to js("ffffffff")),
            "producer engine commit, a longer one that bench is not a prefix of" to (listOf("body", "producer", "engine", "commit") to js("0".repeat(40))),
            "producer engine name" to (listOf("body", "producer", "engine", "name") to js("other-engine")),
            "producer engine buildFlags" to (listOf("body", "producer", "engine", "buildFlags") to jsList(listOf("X=1"))),
            "device memory total" to (listOf("body", "device", "memory", "totalBytes") to ji(64_000_000_000L)),
            "device os family" to (listOf("body", "device", "os", "family") to js("ios")),
            "device os version" to (listOf("body", "device", "os", "version") to js("99")),
            "device vendor" to (listOf("body", "device", "vendor") to js("Other")),
            "device model" to (listOf("body", "device", "model") to js("Other Phone")),
            "device soc name" to (listOf("body", "device", "soc", "name") to js("Other SoC")),
        )
        for ((what, edit) in cases) {
            assertReject(F.verify(ownDoc(edit.first, edit.second), F.ctxMesh()), RejectCode.INCONSISTENT, "15", what)
            c.hit()
        }
        c.requireNonVacuous(cases.size)
    }

    @Test
    fun theSameTiesHoldForAFileExport() {
        val c = Counter("one-record ties (file)")
        val base = F.fileObj()
        for ((path, v) in listOf(
            listOf<Any>("body", "device", "memory", "totalBytes") to ji(64_000_000_000L),
            listOf<Any>("body", "producer", "harness", "confVersion") to js("0.0.1"),
            listOf<Any>("body", "device", "os", "family") to js("windows"),
        )) {
            val doc = F.container(DocSpec(F.payloadOf(JEdit.set(base, path, v)), signKey = F.key3))
            assertReject(F.verify(doc, F.ctxFile()), RejectCode.INCONSISTENT, "15", path.joinToString("."))
            c.hit()
        }
        c.requireNonVacuous(3)
    }
}

/** CV-3: a self-reported key storage is a display label; it must never satisfy a required tier. */
class SelfReportedTierTest {
    @Test
    fun aHardwareClaimIsALabelAndNeverSatisfiesARequiredTierAboveA0() {
        val c = Counter("self-reported tiers")
        for (storage in listOf("strongbox", "tee", "secure-enclave", "tpm", "os-keystore", "file")) {
            val doc = F.container(DocSpec(F.payloadOf(F.ownObj(F.ownBody(storage = storage)))))
            val v = F.verify(doc, F.ctxMesh()) as Verified
            assertEquals(if (storage in setOf("strongbox", "tee", "secure-enclave", "tpm")) Tier.A1 else Tier.A0, v.tier, "the label for $storage")
            for (required in listOf(Tier.A1, Tier.A2)) {
                assertReject(F.verify(doc, F.ctxMesh(requiredTier = required)), RejectCode.TIER_INSUFFICIENT, "18", "$storage required $required")
                c.hit()
            }
        }
        c.requireNonVacuous(12)
    }
}

/** CV-6: only ASCII letters are upper-cased; a non-ASCII letter that Unicode would fold onto a base32 letter must not match. */
class FingerprintNormalisationTest {
    @Test
    fun nonAsciiLettersThatUnicodeUppercasesToBase32LettersAreNotFolded() {
        val c = Counter("fingerprint folding")
        assertEquals("ſ", Spki.normaliseFingerprint("ſ"))
        assertEquals("ı", Spki.normaliseFingerprint("ı"))
        assertEquals("ß", Spki.normaliseFingerprint("ß"))
        assertEquals("ABC2", Spki.normaliseFingerprint("a-b c2"))
        for (k in listOf(F.key2, F.key4)) {
            val plain = Spki.exportFingerprintPlain(k.spki)
            assertTrue(plain.contains('S') || plain.contains('I'), "${k.nodeId} carries an S or an I")
            val folded = plain.lowercase().replace('s', 'ſ').replace('i', 'ı')
            assertNotEquals(plain.lowercase(), folded)
            val body = if (k === F.key2) F.fileBody(k) else F.fileBody(k)
            val doc = F.container(DocSpec(F.payloadOf(F.fileObj(body)), signKey = k))
            assertReject(F.verify(doc, F.ctxFile(compared = folded, method = CompareMethod.TYPED)), RejectCode.FINGERPRINT_MISMATCH, "7b", "folded fingerprint of ${k.nodeId}")
            assertTrue(F.verify(doc, F.ctxFile(compared = plain.lowercase(), method = CompareMethod.TYPED)) is Verified, "the ASCII lowercase form still matches")
            c.hit()
        }
        c.requireNonVacuous(2)
    }
}

/** CV-8: container-shape and lexer readings that the two lanes must share. */
class LaneAgreementReadingsTest {
    private fun raw(text: String) = F.verify(text.toByteArray(Charsets.UTF_8), F.ctxMesh())

    private val head = "{\"asomCapabilityManifest\":1,\"dsse\":{\"payload\":\"\",\"payloadType\":\"application/vnd.asom.manifest.v1+json\","

    @Test
    fun aContainerThatIsNotAnObjectIsContainerInvalid() {
        val c = Counter("non-object containers")
        for (t in listOf("[]", "1", "\"x\"", "null", "true")) {
            assertReject(raw(t), RejectCode.CONTAINER_INVALID, "3", t)
            c.hit()
        }
        c.requireNonVacuous(5)
    }

    @Test
    fun theShapeOfTheFirstSignatureEntryIsDecidedAtStepThreeBeforeThePayloadTypeAndTheCount() {
        val c = Counter("signature entry shapes")
        val cases = listOf(
            "signatures [1,2]" to head + "\"signatures\":[1,2]}}",
            "signatures [1]" to head + "\"signatures\":[1]}}",
            "payloadType x with signatures [1]" to head.replace("application/vnd.asom.manifest.v1+json", "x") + "\"signatures\":[1]}}",
            "an entry with no sig" to head + "\"signatures\":[{\"keyid\":\"a\"}]}}",
            "a keyid that is not a string" to head + "\"signatures\":[{\"keyid\":5,\"sig\":\"AA\"}]}}",
        )
        for ((what, doc) in cases) {
            assertReject(raw(doc), RejectCode.CONTAINER_INVALID, "3", what)
            c.hit()
        }
        assertReject(raw(head + "\"signatures\":[]}}"), RejectCode.SIGNATURE_COUNT, "5", "an empty signatures array is a count problem")
        assertReject(raw(head.replace("application/vnd.asom.manifest.v1+json", "x") + "\"signatures\":[]}}"), RejectCode.PAYLOAD_TYPE_UNSUPPORTED, "4", "type before count")
        c.hit()
        c.hit()
        c.requireNonVacuous(7)
    }

    @Test
    fun aFileSignerSpkiThatIsNotAStringIsContainerInvalid() {
        val doc = String(F.container(DocSpec(F.payloadOf(F.fileObj()), signKey = F.key3)), Charsets.UTF_8)
        val bad = doc.replace(Regex("\"signer\":\\{\"spki\":\"[^\"]*\"\\}"), "\"signer\":{\"spki\":5}")
        assertNotEquals(doc, bad)
        assertReject(F.verify(bad.toByteArray(), F.ctxFile()), RejectCode.CONTAINER_INVALID, "7", "signer.spki = 5")
    }

    @Test
    fun aPayloadTypeOrSchemaMajorMustBeACanonicalDecimal() {
        val c = Counter("major spellings")
        val base = F.container(DocSpec(F.payloadOf(F.ownObj())))
        val pt = "application/vnd.asom.manifest.v1+json"
        for ((spelled, code) in listOf("v02" to RejectCode.PAYLOAD_TYPE_UNSUPPORTED, "v002" to RejectCode.PAYLOAD_TYPE_UNSUPPORTED, "v0" to RejectCode.PAYLOAD_TYPE_UNSUPPORTED, "v2" to RejectCode.SCHEMA_MAJOR_UNKNOWN, "v10" to RejectCode.SCHEMA_MAJOR_UNKNOWN)) {
            val doc = String(base, Charsets.UTF_8).replace(pt, "application/vnd.asom.manifest.$spelled+json")
            assertReject(F.verify(doc.toByteArray(), F.ctxMesh()), code, "4", "payloadType $spelled")
            c.hit()
        }
        for ((spelled, code) in listOf("02" to RejectCode.SCHEMA_INVALID, "2" to RejectCode.SCHEMA_MAJOR_UNKNOWN, "0" to RejectCode.SCHEMA_INVALID)) {
            val doc = F.container(DocSpec(F.payloadOf(JEdit.set(F.ownObj(), listOf("schema"), js("asom.manifest/$spelled")))))
            assertReject(F.verify(doc, F.ctxMesh()), code, "11", "schema $spelled")
            c.hit()
        }
        c.requireNonVacuous(8)
    }

    @Test
    fun anEvidenceItemOverTheItemLimitIsContainerInvalid() {
        val big = xyz.mdhv.asom.lab.bench.jo("x" to js("a".repeat(40_000)))
        val doc = F.container(DocSpec(F.payloadOf(F.ownObj()), evidence = listOf(big)))
        assertReject(F.verify(doc, F.ctxMesh()), RejectCode.CONTAINER_INVALID, "15c", "an evidence item over 32,768 bytes")
        val small = xyz.mdhv.asom.lab.bench.jo("x" to js("a".repeat(20_000)))
        assertTrue(F.verify(F.container(DocSpec(F.payloadOf(F.ownObj()), evidence = listOf(small))), F.ctxMesh()) is Verified)
    }

    @Test
    fun theLexerTakesTheMaximalLetterRunAndTheMaximalNumberRunAsOneToken() {
        val c = Counter("lexer readings")
        for ((text, code) in listOf(
            "truex" to RejectCode.MALFORMED_JSON, "nullnull" to RejectCode.MALFORMED_JSON, "Infinityx" to RejectCode.MALFORMED_JSON, "[truefalse]" to RejectCode.MALFORMED_JSON,
            "[1-2]" to RejectCode.NON_INTEGER_NUMBER, "-" to RejectCode.NON_INTEGER_NUMBER, "[-NaN]" to RejectCode.NON_INTEGER_NUMBER, "[1+]" to RejectCode.NON_INTEGER_NUMBER,
        )) {
            assertReject(raw(text), code, "2", text)
            c.hit()
        }
        c.requireNonVacuous(8)
    }

    @Test
    fun aDepthFaultDoesNotHideAnEarlierRowThatSitsAfterTheDeepRegion() {
        val c = Counter("depth versus rows 2-6")
        val deep = "[".repeat(17)
        val close = "]".repeat(17)
        assertReject(raw(deep + "1.5" + close), RejectCode.NON_INTEGER_NUMBER, "2", "fraction inside a deep region")
        assertReject(raw("[" + "[".repeat(16) + "]".repeat(16) + ",{\"a\":1,\"a\":2}]"), RejectCode.DUPLICATE_KEY, "2", "duplicate after a deep region")
        assertReject(raw(deep + "\"\\ud800\"" + close), RejectCode.INVALID_UNICODE, "2", "lone surrogate inside a deep region")
        assertReject(raw(deep + "9007199254740992" + close), RejectCode.NUMBER_RANGE, "2", "range inside a deep region")
        assertReject(raw(deep + close), RejectCode.MALFORMED_JSON, "2", "the deep region alone")
        repeat(5) { c.hit() }
        c.requireNonVacuous(5)
    }
}

/** CV-9: two bodies must never share a seq, whatever the interleaving. */
class SeqRaceTest {
    private class GatedStore(private val inner: SeqStore, private val gate: CyclicBarrier) : SeqStore {
        override fun load(): StoredSeq? {
            val v = inner.load()
            try {
                gate.await(700, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // the signer serialises its callers: the second thread cannot reach the gate while the first holds the lock
            } catch (e: java.util.concurrent.BrokenBarrierException) {
            }
            return v
        }

        override fun persist(stored: StoredSeq) = inner.persist(stored)
    }

    @Test
    fun twoDifferentBodiesSignedInTheSameSecondNeverShareASeq() {
        val dir = java.nio.file.Files.createTempDirectory("asom-seq-race").toFile()
        try {
            val file = FileSeqStore(File(dir, "seq"))
            val gate = CyclicBarrier(2)
            val store = GatedStore(file, gate)
            val signer = ManifestSigner(F.CONF, productionKeys = false)
            val key = F.key1.keyPair()
            val a = F.inputs(F.benchFrom("""{"preset":"phone","plan":"standard"}"""))
            val b = F.inputs(F.benchFrom("""{"preset":"desktop","plan":"standard"}"""))
            val pool = Executors.newFixedThreadPool(2)
            try {
                val fa = pool.submit<Signed> { signer.signOwn(a, key, "strongbox", F.challenge1, F.ISSUED, store) }
                val fb = pool.submit<Signed> { signer.signOwn(b, key, "strongbox", F.challenge1, F.ISSUED, store) }
                val ra = fa.get(30, TimeUnit.SECONDS).verified
                val rb = fb.get(30, TimeUnit.SECONDS).verified
                assertNotEquals(ra.bodyDigest, rb.bodyDigest)
                assertNotEquals(ra.obj.body.seq, rb.obj.body.seq, "two different bodies were signed under one seq")
            } finally {
                pool.shutdownNow()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aForeignFileAtTheOldFixedTemporaryNameNeverBlocksOrIsTouchedByAWriter() {
        val dir = java.nio.file.Files.createTempDirectory("asom-seq-stale").toFile()
        try {
            val squatter = File(dir, "seq.tmp")
            assertTrue(squatter.mkdir(), "a directory sits where a fixed temporary name would be written")
            File(squatter, "keep").writeText("x")
            val store = FileSeqStore(File(dir, "seq"))
            store.persist(StoredSeq(7L, "digest"))
            assertEquals(7L, store.load()!!.seq)
            assertTrue(File(squatter, "keep").isFile, "the foreign entry is untouched")
            assertEquals(setOf("seq", "seq.tmp"), dir.list()!!.toSet(), "no temporary file of the writer is left")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrentWritersOfTheFileStoreNeitherFailNorLeaveTemporaryFiles() {
        val dir = java.nio.file.Files.createTempDirectory("asom-seq-file").toFile()
        try {
            val store = FileSeqStore(File(dir, "seq"))
            val n = 8
            val gate = CyclicBarrier(n)
            val pool = Executors.newFixedThreadPool(n)
            try {
                val futures = (0 until n).map { t ->
                    pool.submit<Throwable?> {
                        try {
                            gate.await(30, TimeUnit.SECONDS)
                            for (i in 0 until 150) store.persist(StoredSeq((t * 1000 + i).toLong(), "d$t-$i"))
                            null
                        } catch (e: Throwable) {
                            e
                        }
                    }
                }
                val failures = futures.mapNotNull { it.get(60, TimeUnit.SECONDS) }
                assertTrue(failures.isEmpty(), "persist failed under contention: ${failures.firstOrNull()}")
            } finally {
                pool.shutdownNow()
            }
            assertNotNull(store.load())
            assertEquals(listOf("seq"), dir.list()!!.toList(), "no temporary file is left behind")
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** A point with a small x, and its encodings: the coordinate not reduced modulo p is a second encoding of the same key (CV-10). */
object NonReducedKeys {
    /** The smallest x whose curve equation has a root, with that root: a point whose x + p still fits in 256 bits. */
    fun smallPoint(): Pair<BigInteger, BigInteger> {
        val p = P256.P
        var x = BigInteger.valueOf(2)
        while (true) {
            val rhs = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(P256.B).mod(p)
            val y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
            if (y.multiply(y).mod(p) == rhs) return x to y
            x = x.add(BigInteger.ONE)
        }
    }

    fun encode(x: BigInteger, y: BigInteger): ByteArray = Spki.PREFIX + byteArrayOf(4) + Es256.fixed32(x) + Es256.fixed32(y)
}

/** CV-10: a coordinate that is not reduced modulo p is a second encoding of the same key and must be refused. */
class NonReducedCoordinateTest {
    @Test
    fun theCanonicalEncodingIsAcceptedAndTheNonReducedOneIsNot() {
        val (x, y) = NonReducedKeys.smallPoint()
        assertTrue(P256.onCurve(x, y))
        val canonical = NonReducedKeys.encode(x, y)
        val nonReduced = NonReducedKeys.encode(x.add(P256.P), y)
        assertNotNull(Spki.strict(canonical), "the reduced encoding of a small-x point is a key")
        assertNull(Spki.strict(nonReduced), "x + p is the same point, a second encoding, with a different node id")
        assertNotEquals(Spki.nodeId(canonical), Spki.nodeId(nonReduced))
        val doc = F.container(DocSpec(F.payloadOf(F.ownObj()), keyid = null))
        val ctx = VerifyContext(Mode.MESH, pinnedSpki = nonReduced, expectedChallenge = F.challenge1, confFloor = F.CONF, productionKeys = false, nowMs = F.NOW)
        assertReject(Verifier.verify(doc, ctx), RejectCode.ALG_UNSUPPORTED, "7", "a pinned key with a non-reduced coordinate")
    }

    @Test
    fun aNonReducedYIsRefusedToo() {
        val (x, y) = NonReducedKeys.smallPoint()
        val cap = BigInteger.ONE.shiftLeft(256).subtract(P256.P)
        // y + p fits in 256 bits only for y < 2^256 - p; when it does not, the encoding cannot exist and there is nothing to refuse
        if (y < cap) assertNull(Spki.strict(NonReducedKeys.encode(x, y.add(P256.P))))
        val (x2, y2) = P256Math.mul(BigInteger.valueOf(5))!!
        assertNotNull(Spki.strict(NonReducedKeys.encode(x2, y2)))
    }
}
