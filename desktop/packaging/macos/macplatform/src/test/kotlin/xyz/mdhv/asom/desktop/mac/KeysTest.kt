package xyz.mdhv.asom.desktop.mac

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.mac.fakes.FakeEnclave
import xyz.mdhv.asom.desktop.mac.fakes.FakeHelper
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperLostException
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.keys.Es256
import xyz.mdhv.asom.desktop.mac.keys.FileNik
import xyz.mdhv.asom.desktop.mac.keys.KeyTierRequest
import xyz.mdhv.asom.desktop.mac.keys.MacNikStore
import xyz.mdhv.asom.desktop.mac.keys.NikKey
import xyz.mdhv.asom.desktop.mac.keys.NikSelectionFailure
import xyz.mdhv.asom.desktop.mac.keys.NikTier
import xyz.mdhv.asom.desktop.mac.keys.NikTierSelector
import xyz.mdhv.asom.desktop.mac.keys.NikUnavailableException
import xyz.mdhv.asom.desktop.mac.keys.SecureEnclaveNik

/**
 * The node identity tiers: T2 through the (fake) enclave, T0 as a file, the selection rule, the store's refusals. Every signature is
 * REAL ES256 (JCA keys behind the fake enclave) and is verified independently of the code under test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeysTest {
    private val laws = LawCounter(
        listOf(
            "t2-sign-verifies", "t0-sign-verifies", "select-se-available", "select-no-se", "select-create-failed", "select-selftest-failed",
            "select-helper-lost-no-key", "select-file-explicit", "select-both-fail", "existing-t2-never-downgrades", "identity-never-replaced",
            "files-are-0600", "dir-must-be-private",
        ),
    )
    private val random = SecureRandom()

    @AfterAll
    fun report() = laws.assertAllExercised("keys")

    private val me: Int? = runCatching { Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int }.getOrNull()

    private fun identityDir(): Path {
        val root = Files.createTempDirectory("asom-keys-")
        val dir = root.resolve("node")
        MacPaths.createPrivateDir(dir)
        return dir
    }

    private fun paths(dir: Path) = NodePaths(HostMode.USER, dir.parent, dir.parent, dir.parent.resolve("ledger"), dir, dir.parent.resolve("run"), dir.parent.resolve("run/ctl.sock"))

    private fun verifies(key: NikKey): Boolean {
        val msg = ByteArray(200).also(random::nextBytes)
        val sig = key.sign(msg)
        return sig.size == 64 && Es256.verify(key.spki(), msg, sig) && !Es256.verify(key.spki(), msg + 1.toByte(), sig)
    }

    @Test
    fun `T2 through the helper makes a real key whose signatures verify, and its blob file is 0600`() {
        val dir = identityDir()
        val h = FakeHelper()
        val key = SecureEnclaveNik.create(dir, HelperClient(h), me)
        assertEquals(NikTier.T2_SECURE_ENCLAVE, key.tier)
        assertEquals(KeyStorage.SECURE_ENCLAVE, key.tier.keyStorage)
        assertEquals("secure-enclave", key.tier.keyStorage.wire)
        assertTrue(verifies(key)); laws.hit("t2-sign-verifies")
        assertEquals(91, key.spki().size)
        assertTrue(key.selfTest())
        val file = dir.resolve(SecureEnclaveNik.FILE_NAME)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file)); laws.hit("files-are-0600")
        // reopen from the file: the same public key, signatures still verify
        val again = SecureEnclaveNik.open(dir, HelperClient(h), me)!!
        assertContentEquals(key.spki(), again.spki())
        assertTrue(verifies(again))
        // a second create never overwrites
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { SecureEnclaveNik.create(dir, HelperClient(h), me) }
    }

    @Test
    fun `the T2 blob file is refused when it is not private, corrupt, or not ours`() {
        val dir = identityDir()
        val h = FakeHelper()
        SecureEnclaveNik.create(dir, HelperClient(h), me)
        val f = dir.resolve(SecureEnclaveNik.FILE_NAME)
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r-----"))
        assertFailsWith<NikUnavailableException> { SecureEnclaveNik.open(dir, HelperClient(h), me) }
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"))
        assertFailsWith<NikUnavailableException> { SecureEnclaveNik.open(dir, HelperClient(h), (me ?: 0) + 1) } // a different owner uid
        val good = Files.readAllBytes(f)
        for (bad in listOf(ByteArray(0), "not a key".toByteArray(), good.copyOf(good.size - 1), good.copyOf(20), good + 0.toByte())) {
            Files.write(f, bad)
            assertFailsWith<NikUnavailableException>("corrupt file of ${bad.size} bytes") { SecureEnclaveNik.open(dir, HelperClient(h), me) }
        }
        Files.delete(f)
        assertNull(SecureEnclaveNik.open(dir, HelperClient(h), me))
    }

    @Test
    fun `T2 refuses a public key that is not P-256 and a directory that is not private`() {
        val dir = identityDir()
        val h = FakeHelper(handler = { r ->
            if (r.op == "se.create") Reply.Success("se.create", r.id, xyz.mdhv.asom.desktop.mac.helper.Fields.of(
                "blob" to xyz.mdhv.asom.desktop.mac.helper.HValue.Bytes(byteArrayOf(1)), "spki" to xyz.mdhv.asom.desktop.mac.helper.HValue.Bytes(ByteArray(91)),
            )) else null
        })
        assertFailsWith<IllegalStateException> { SecureEnclaveNik.create(dir, HelperClient(h), me) }
        assertFalse(Files.exists(dir.resolve(SecureEnclaveNik.FILE_NAME)), "nothing is persisted for a bad key")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertFailsWith<IllegalArgumentException> { SecureEnclaveNik.create(dir, HelperClient(FakeHelper()), me) }
        assertFailsWith<IllegalArgumentException> { FileNik.create(dir, me) }
        laws.hit("dir-must-be-private", 2)
    }

    @Test
    fun `T0 makes a real key in a 0600 file and reopens it`() {
        val dir = identityDir()
        val key = FileNik.create(dir, me)
        assertEquals(NikTier.T0_FILE, key.tier)
        assertEquals(KeyStorage.FILE, key.tier.keyStorage)
        assertTrue(verifies(key)); laws.hit("t0-sign-verifies")
        val f = dir.resolve(FileNik.FILE_NAME)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f)); laws.hit("files-are-0600")
        val again = FileNik.open(dir, me)!!
        assertContentEquals(key.spki(), again.spki())
        assertTrue(verifies(again))
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { FileNik.create(dir, me) }
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-rw----"))
        assertFailsWith<NikUnavailableException> { FileNik.open(dir, me) }
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"))
        val bytes = Files.readAllBytes(f)
        Files.write(f, bytes.copyOf(30))
        assertFailsWith<NikUnavailableException> { FileNik.open(dir, me) }
        Files.write(f, bytes)
        again.delete()
        assertFalse(Files.exists(f))
    }

    private fun select(request: KeyTierRequest, dir: Path, h: FakeHelper, ownUid: Int? = me) = NikTierSelector.select(
        request, seAvailable = { HelperClient(h).hello().se },
        t2 = { SecureEnclaveNik.create(dir, HelperClient(h), ownUid) },
        t0 = { FileNik.create(dir, ownUid) },
    )

    @Test
    fun `AUTO picks T2 where the enclave works, and says so`() {
        val dir = identityDir()
        val s = select(KeyTierRequest.AUTO, dir, FakeHelper())
        assertEquals(NikTier.T2_SECURE_ENCLAVE, s.tier)
        assertEquals(listOf(true), s.attempts.map { it.ok })
        assertTrue(Files.exists(dir.resolve("nik.se")) && !Files.exists(dir.resolve("nik.p8")))
        laws.hit("select-se-available")
    }

    @Test
    fun `hello se false means T0 and the attempt log says why`() {
        val dir = identityDir()
        val s = select(KeyTierRequest.AUTO, dir, FakeHelper(FakeEnclave(available = false)))
        assertEquals(NikTier.T0_FILE, s.tier)
        assertTrue(s.attempts.first().detail.contains("no Secure Enclave"))
        assertTrue(Files.exists(dir.resolve("nik.p8")) && !Files.exists(dir.resolve("nik.se")))
        laws.hit("select-no-se")
    }

    @Test
    fun `a failed create or a failed self-test falls back to T0 on first creation, and the bad T2 blob is removed`() {
        val a = identityDir()
        val s1 = select(KeyTierRequest.AUTO, a, FakeHelper(FakeEnclave(failCreate = true)))
        assertEquals(NikTier.T0_FILE, s1.tier)
        assertTrue(s1.attempts[0].detail.startsWith("create failed"))
        laws.hit("select-create-failed")

        val b = identityDir()
        val bad = FakeEnclave(badSignatures = true)
        val s2 = select(KeyTierRequest.AUTO, b, FakeHelper(bad))
        assertEquals(NikTier.T0_FILE, s2.tier)
        assertTrue(s2.attempts[0].detail.startsWith("self-test failed"), s2.attempts.toString())
        assertFalse(Files.exists(b.resolve("nik.se")), "the half-working T2 blob does not stay behind")
        assertEquals(1, bad.creates.get())
        laws.hit("select-selftest-failed")
    }

    @Test
    fun `a helper that cannot be reached is NOT read as no Enclave, so no key of either tier is made`() {
        val dir = identityDir()
        val h = FakeHelper()
        h.lost = "the helper is gone"
        assertFailsWith<HelperLostException> { select(KeyTierRequest.AUTO, dir, h) }
        assertEquals(0, Files.list(dir).use { it.count() }, "nothing was written")
        // a helper that dies between hello and se.create is the same
        val h2 = FakeHelper(handler = { r -> if (r.op == "se.create") throw HelperLostException("died") else null })
        assertFailsWith<HelperLostException> { select(KeyTierRequest.AUTO, dir, h2) }
        assertEquals(0, Files.list(dir).use { it.count() })
        laws.hit("select-helper-lost-no-key", 2)
    }

    @Test
    fun `--key-tier file goes straight to T0 and never touches the enclave`() {
        val dir = identityDir()
        val enclave = FakeEnclave()
        val s = select(KeyTierRequest.FILE, dir, FakeHelper(enclave))
        assertEquals(NikTier.T0_FILE, s.tier)
        assertEquals(0, enclave.creates.get())
        assertTrue(s.attempts.single().detail.contains("--key-tier file"))
        laws.hit("select-file-explicit")
    }

    @Test
    fun `when every allowed tier fails the selection fails, it does not invent a key`() {
        val dir = identityDir()
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x")) // T0 refuses a non-private directory
        val e = assertFailsWith<NikSelectionFailure> { select(KeyTierRequest.AUTO, dir, FakeHelper(FakeEnclave(available = false))) }
        assertEquals(listOf(false, false), e.attempts.map { it.ok })
        laws.hit("select-both-fail")
    }

    @Test
    fun `an existing T2 identity that cannot be opened or fails its self-test is NIK_UNAVAILABLE, never a T0 downgrade`() {
        val dir = identityDir()
        val h = FakeHelper()
        val store = MacNikStore(paths(dir), HelperClient(h), null) { me }
        val made = store.createAtFirstMeshEnable(KeyTierRequest.AUTO)
        assertEquals(NikTier.T2_SECURE_ENCLAVE, made.tier)
        assertEquals(KeyStorage.SECURE_ENCLAVE, store.keyStorage)
        // 1. the enclave stops accepting the blob (an erase, another Mac)
        h.enclave.badSignatures = true
        assertFailsWith<NikUnavailableException> { store.existing() }
        h.enclave.badSignatures = false
        // 2. the helper reports it cannot sign
        h.failures["se.selftest"] = Reply.Failure(null, "FAILED", "key blob not usable")
        assertFailsWith<NikUnavailableException> { store.existing() }
        h.failures.clear()
        // 3. the blob file is gone but the binding-less store still sees a T0 file appear: both files is ambiguous
        FileNik.create(dir, me)
        assertFailsWith<NikUnavailableException> { store.existing() }
        assertTrue(Files.exists(dir.resolve("nik.se")), "the T2 blob is still there: nothing was downgraded or replaced")
        laws.hit("existing-t2-never-downgrades", 3)
        // and a healthy identity opens
        Files.delete(dir.resolve("nik.p8"))
        assertEquals(NikTier.T2_SECURE_ENCLAVE, store.existing()!!.tier)
    }

    @Test
    fun `an identity is never silently replaced, and reading the tier creates nothing`() {
        val dir = identityDir()
        val h = FakeHelper()
        val store = MacNikStore(paths(dir), HelperClient(h), null) { me }
        assertEquals(KeyStorage.UNKNOWN, store.keyStorage)
        assertNull(store.existing())
        assertTrue(h.calls.isEmpty(), "reading the tier and looking for a key call nothing")
        assertEquals(0, Files.list(dir).use { it.count() }, "and create nothing")
        store.createAtFirstMeshEnable(KeyTierRequest.AUTO)
        val e = assertFailsWith<IllegalStateException> { store.createAtFirstMeshEnable(KeyTierRequest.AUTO) }
        assertTrue("already exists" in e.message!!)
        assertEquals(1, h.enclave.creates.get())
        laws.hit("identity-never-replaced")
    }

    @Test
    fun `the tier disclosures name what each tier does NOT guarantee, and the login keychain is rejected in words`() {
        assertEquals(2, NikTier.entries.size, "there is no T1")
        val t2 = NikTier.T2_SECURE_ENCLAVE.doesNotGuarantee.joinToString(" ")
        for (needle in listOf("blob is not bound", "root", "Full Disk Access", "attestation: none", "erase")) assertTrue(needle in t2, "T2 disclosure lacks: $needle")
        val t0 = NikTier.T0_FILE.doesNotGuarantee.joinToString(" ")
        for (needle in listOf("EXTRACTION", "copied file is the node", "Migration Assistant")) assertTrue(needle in t0, "T0 disclosure lacks: $needle")
        assertEquals("hardware-backed (self-reported)", NikTier.T2_SECURE_ENCLAVE.label)
        assertTrue("rejected" in NikTier.REJECTED_LOGIN_KEYCHAIN && "/usr/bin/security" in NikTier.REJECTED_LOGIN_KEYCHAIN)
        assertNotEquals(NikTier.T2_SECURE_ENCLAVE.keyStorage, NikTier.T0_FILE.keyStorage)
    }

    @Test
    fun `Es256 rejects wrong sizes, wrong keys and garbage`() {
        val dir = identityDir()
        val key = FileNik.create(dir, me)
        val msg = "m".toByteArray()
        val sig = key.sign(msg)
        assertTrue(Es256.verify(key.spki(), msg, sig))
        assertFalse(Es256.verify(key.spki(), msg, sig.copyOf(63)))
        assertFalse(Es256.verify(key.spki(), msg, sig + 0.toByte()))
        assertFalse(Es256.verify(ByteArray(91), msg, sig))
        assertFalse(Es256.isP256Spki(ByteArray(91)))
        assertTrue(Es256.isP256Spki(key.spki()))
        assertIs<ByteArray>(Es256.sha256(msg))
    }
}
