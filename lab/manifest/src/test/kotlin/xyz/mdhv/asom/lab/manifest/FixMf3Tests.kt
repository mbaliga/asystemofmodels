package xyz.mdhv.asom.lab.manifest

import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.security.KeyPair
import java.security.PrivateKey
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun tempDir(prefix: String): File = java.nio.file.Files.createTempDirectory(prefix).toFile()

/** R4MS-1: the signer's lock follows the durable store, not the object, so two instances of one file can never sign two bodies under one seq. */
class SeqLockIdentityTest {
    private class GatedStore(private val inner: SeqStore, private val gate: CyclicBarrier) : SeqStore {
        override val lockKey: Any get() = inner.lockKey

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
    fun twoFileStoreInstancesOnOnePathNeverSignTwoBodiesUnderOneSeq() {
        val c = Counter("two instances of one seq file")
        repeat(3) {
            val dir = tempDir("asom-seq-two")
            try {
                val gate = CyclicBarrier(2)
                val storeA = GatedStore(FileSeqStore(File(dir, "seq")), gate)
                val storeB = GatedStore(FileSeqStore(File(dir, "seq")), gate)
                val signer = ManifestSigner(F.CONF, productionKeys = false)
                val key = F.key1.keyPair()
                val a = F.inputs(F.benchFrom("""{"preset":"phone","plan":"standard"}"""))
                val b = F.inputs(F.benchFrom("""{"preset":"desktop","plan":"standard"}"""))
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val fa = pool.submit<Signed> { signer.signOwn(a, key, "strongbox", F.challenge1, F.ISSUED, storeA) }
                    val fb = pool.submit<Signed> { signer.signOwn(b, key, "strongbox", F.challenge1, F.ISSUED, storeB) }
                    val ra = fa.get(30, TimeUnit.SECONDS).verified
                    val rb = fb.get(30, TimeUnit.SECONDS).verified
                    assertNotEquals(ra.bodyDigest, rb.bodyDigest)
                    assertNotEquals(ra.obj.body.seq, rb.obj.body.seq, "two different bodies were signed under one seq by two instances of one file")
                    c.hit()
                } finally {
                    pool.shutdownNow()
                }
            } finally {
                dir.deleteRecursively()
            }
        }
        c.requireNonVacuous(3)
    }

    @Test
    fun everySpellingOfOneFileSharesOneLockAndDifferentFilesDoNot() {
        val c = Counter("lock keys")
        val dir = tempDir("asom-seq-key")
        try {
            File(dir, "sub").mkdirs()
            val base = FileSeqStore(File(dir, "seq")).lockKey
            val spellings = listOf(File(dir, "seq"), File(dir, "sub" + File.separator + ".." + File.separator + "seq"), File(File(dir, "sub"), "..").resolve("seq"), File(dir.path + File.separator + File.separator + "seq"))
            for (f in spellings) {
                assertSame(base, FileSeqStore(f).lockKey, "a spelling of one file got its own lock: $f")
                c.hit()
            }
            assertTrue(FileSeqStore(File(dir, "other")).lockKey !== base, "two different files must not share a lock")
            c.hit()
            assertSame(base, FileSeqStore(File(dir, "SEQ")).lockKey, "a case variant shares the lock (case-insensitive file systems; sharing a lock is only contention)")
            c.hit()
        } finally {
            dir.deleteRecursively()
        }
        c.requireNonVacuous(6)
    }

    @Test
    fun anInMemoryStoreIsItsOwnLock() {
        val a = InMemorySeqStore()
        val b = InMemorySeqStore()
        assertSame(a, a.lockKey)
        assertTrue(a.lockKey !== b.lockKey)
    }
}

/** R4MS-2: a store file that exists and cannot be read fails closed; it is never mistaken for "no store". */
class SeqStoreFailsClosedTest {
    private val signer = ManifestSigner(F.CONF, productionKeys = false)
    private val key = F.key1.keyPair()
    private val inputs = F.inputs()

    private val malformed = mapOf(
        "crlf line endings" to "1790676000\r\nabcDEF\r\n",
        "empty file" to "",
        "seq line only" to "1790676000\n",
        "empty digest" to "1790676000\n\n",
        "no trailing newline" to "1790676000\nabcDEF",
        "non-numeric seq" to "seq\nabcDEF\n",
        "negative seq" to "-5\nabcDEF\n",
        "zero seq" to "0\nabcDEF\n",
        "seq above 2^53 - 1" to "9007199254740992\nabcDEF\n",
        "extra line" to "1790676000\nabcDEF\nmore\n",
        "binary junk" to "\u0000\u0000\u0000\u0000",
    )

    @Test
    fun anAbsentFileIsNoStoreAndAWellFormedOneRoundTrips() {
        val dir = tempDir("asom-seq-ok")
        try {
            val f = File(dir, "seq")
            assertNull(FileSeqStore(f).load())
            FileSeqStore(f).persist(StoredSeq(1790676000L, "abcDEF"))
            val back = FileSeqStore(f).load()!!
            assertEquals(1790676000L, back.seq)
            assertEquals("abcDEF", back.contentDigest)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aMalformedStoreFileIsAnErrorNotAnAbsentStore() {
        val c = Counter("malformed store files")
        val dir = tempDir("asom-seq-bad")
        try {
            for ((what, text) in malformed) {
                val f = File(dir, "seq")
                f.writeText(text, Charsets.UTF_8)
                assertFailsWith<IOException>("$what: load() treated an unreadable store as an absent one") { FileSeqStore(f).load() }
                c.hit()
            }
        } finally {
            dir.deleteRecursively()
        }
        c.requireNonVacuous(malformed.size)
    }

    @Test
    fun signOwnSignsNothingAndWritesNothingWhenTheStoreFileIsMalformed() {
        val c = Counter("signOwn over a malformed store")
        val dir = tempDir("asom-seq-bad-sign")
        try {
            for ((what, text) in malformed) {
                val f = File(dir, "seq")
                f.writeText(text, Charsets.UTF_8)
                val ex = assertFailsWith<ManifestUnavailable>("$what: signOwn went on with an unreadable store") { signer.signOwn(inputs, key, "strongbox", F.challenge1, F.ISSUED, FileSeqStore(f)) }
                assertNull(ex.code, what)
                assertEquals(text, f.readText(Charsets.UTF_8), "$what: the unreadable file was overwritten")
                assertEquals(listOf("seq"), dir.list()!!.toList(), "$what: a temporary file was left")
                c.hit()
            }
        } finally {
            dir.deleteRecursively()
        }
        c.requireNonVacuous(malformed.size)
    }

    @Test
    fun theCrlfRewriteThatReusedAnIssuedSeqNoLongerDoes() {
        val dir = tempDir("asom-seq-crlf")
        try {
            val f = File(dir, "seq")
            val a = inputs
            val b = F.inputs(F.benchFrom("""{"preset":"desktop","plan":"standard"}"""))
            val first = signer.signOwn(a, key, "strongbox", F.challenge1, F.ISSUED, FileSeqStore(f)).verified.obj.body.seq!!
            val second = signer.signOwn(b, key, "strongbox", F.challenge1, F.ISSUED, FileSeqStore(f)).verified.obj.body.seq!!
            assertEquals(first + 1, second)
            f.writeText(f.readText(Charsets.UTF_8).replace("\n", "\r\n"), Charsets.UTF_8)
            assertFailsWith<ManifestUnavailable> { signer.signOwn(a, key, "strongbox", F.challenge1, F.ISSUED, FileSeqStore(f)) }
            assertNotNull(f.readText(Charsets.UTF_8))
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** R4MS-3: the nextSeq rule of R4-L-13 is applied before anything is written. */
class NextSeqBoundsTest {
    private val signer = ManifestSigner(F.CONF, productionKeys = false)
    private val key = F.key1.keyPair()
    private val inputs = F.inputs()
    private val maxSeq = 9_007_199_254_740_991L

    @Test
    fun aClockOutsideTheRangeIsRefusedBeforeAnythingIsPersisted() {
        val c = Counter("refused clocks")
        val dir = tempDir("asom-seq-bounds")
        val clocks = listOf(-1L, -5_000L, Long.MIN_VALUE, 9_100_000_000_000_000_000L, Long.MAX_VALUE, maxSeq * 1000L + 1_000L, maxSeq + 1L, maxSeq - 599_999L)
        try {
            for (nowMs in clocks) {
                val mem = InMemorySeqStore()
                assertFailsWith<ManifestUnavailable>("memory, clock $nowMs") { signer.signOwn(inputs, key, "strongbox", F.challenge1, nowMs, mem) }
                assertNull(mem.load(), "memory, clock $nowMs: a seq was persisted before the refusal")
                c.hit()
                val f = File(dir, "seq")
                assertFailsWith<ManifestUnavailable>("file, clock $nowMs") { signer.signOwn(inputs, key, "strongbox", F.challenge1, nowMs, FileSeqStore(f)) }
                assertTrue(!f.exists(), "file, clock $nowMs: a seq was persisted before the refusal")
                c.hit()
            }
        } finally {
            dir.deleteRecursively()
        }
        c.requireNonVacuous(clocks.size * 2)
    }

    @Test
    fun aRefusedClockDoesNotPoisonTheStoreForTheNextSaneCall() {
        val dir = tempDir("asom-seq-poison")
        try {
            val f = File(dir, "seq")
            val other = F.inputs(F.benchFrom("""{"preset":"desktop","plan":"standard"}"""))
            assertFailsWith<ManifestUnavailable> { signer.signOwn(inputs, key, "strongbox", F.challenge1, 9_100_000_000_000_000_000L, FileSeqStore(f)) }
            val ok = signer.signOwn(other, key, "strongbox", F.challenge1, F.ISSUED, FileSeqStore(f))
            assertEquals(F.ISSUED / 1000L, ok.verified.obj.body.seq)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aStoredSeqAtTheMaximumRefusesTheNextBodyAndLeavesTheStoreUnchanged() {
        val c = Counter("seq at the maximum")
        for (stored in listOf(maxSeq, Long.MAX_VALUE)) {
            val store = InMemorySeqStore()
            store.persist(StoredSeq(stored, "not-this-body"))
            assertFailsWith<ManifestUnavailable>("stored $stored") { signer.signOwn(inputs, key, "strongbox", F.challenge1, F.ISSUED, store) }
            assertEquals(stored, store.load()!!.seq)
            assertEquals("not-this-body", store.load()!!.contentDigest)
            c.hit()
        }
        c.requireNonVacuous(2)
    }

    @Test
    fun theLastSeqAtTheMaximumIsStillSigned() {
        val store = InMemorySeqStore()
        store.persist(StoredSeq(maxSeq - 1L, "not-this-body"))
        val s = signer.signOwn(inputs, key, "strongbox", F.challenge1, F.ISSUED, store)
        assertEquals(maxSeq, s.verified.obj.body.seq)
        assertEquals(maxSeq, store.load()!!.seq)
    }
}

/** R4MS-5: the injected-key seam is pinned by the whole public surface of the signer and of the module, not by one return-type filter. */
class SignerSurfaceTest {
    private fun isPublicApi(m: java.lang.reflect.Method) = Modifier.isPublic(m.modifiers) && !m.isSynthetic && !m.isBridge && !m.name.contains('$')

    private val keyTypes = setOf<Class<*>>(EcKeyPair::class.java, PrivateKey::class.java, KeyPair::class.java)

    private fun sig(m: java.lang.reflect.Method) = m.name + m.parameterTypes.joinToString(",", "(", ")") { it.simpleName }

    private fun moduleClasses(): List<Class<*>> {
        val root = File(ManifestSigner::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(root.isDirectory, "the main classes are a directory: $root")
        val out = root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
            .map { Class.forName(it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.'), false, ManifestSigner::class.java.classLoader) }.toList()
        assertTrue(out.size >= 10, "the module's main classes were found: ${out.size}")
        return out
    }

    @Test
    fun theSigningMethodsOfManifestSignerAreExactlyTheTwoProductionEntries() {
        val found = ManifestSigner::class.java.declaredMethods.filter { isPublicApi(it) && it.name.startsWith("sign") }.map { sig(it) }.toSet()
        assertEquals(setOf("signOwn(ManifestInputs,EcKeyPair,String,byte[],long,SeqStore)", "signFile(ManifestInputs,long)"), found, "the public signing surface changed: a new entry must be reviewed against R4-L-13")
    }

    @Test
    fun noPublicMethodOfManifestSignerOtherThanSignOwnTakesOrReturnsAKey() {
        val c = Counter("ManifestSigner public methods")
        for (m in ManifestSigner::class.java.declaredMethods.filter { isPublicApi(it) }) {
            c.hit()
            assertTrue(m.name == "signOwn" || m.parameterTypes.none { it in keyTypes }, "${sig(m)} lets a caller hand the signer a key")
            assertTrue(m.returnType !in keyTypes, "${sig(m)} hands out a key")
        }
        c.requireNonVacuous(2)
    }

    @Test
    fun noTopLevelOrExtensionFunctionOfTheModuleSignsForAnotherKey() {
        val c = Counter("module methods that touch the signer")
        for (cls in moduleClasses().filter { it != ManifestSigner::class.java }) {
            for (m in cls.declaredMethods.filter { isPublicApi(it) }) {
                val touchesSigner = m.parameterTypes.any { it == ManifestSigner::class.java } || SignedFile::class.java.isAssignableFrom(m.returnType) || Signed::class.java.isAssignableFrom(m.returnType)
                if (touchesSigner) {
                    c.hit()
                    assertTrue(m.parameterTypes.none { it in keyTypes }, "${cls.simpleName}.${sig(m)} signs with a caller's key outside ManifestSigner")
                }
            }
        }
        val names = moduleClasses().map { it.simpleName }
        assertTrue("ManifestSigner" in names && "Verifier" in names, "the scan covered the signer and the verifier: $names")
        c.hit()
        c.requireNonVacuous(1)
    }
}
