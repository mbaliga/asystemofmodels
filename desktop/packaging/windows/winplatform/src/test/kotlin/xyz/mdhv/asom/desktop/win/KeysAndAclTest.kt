package xyz.mdhv.asom.desktop.win

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.win.acl.Ace
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.AclRight
import xyz.mdhv.asom.desktop.win.acl.AclRights
import xyz.mdhv.asom.desktop.win.acl.AclSnapshot
import xyz.mdhv.asom.desktop.win.acl.AclVerifier
import xyz.mdhv.asom.desktop.win.acl.ServiceSid
import xyz.mdhv.asom.desktop.win.acl.WellKnownSid
import xyz.mdhv.asom.desktop.win.api.CngProvider
import xyz.mdhv.asom.desktop.win.api.KeyScope
import xyz.mdhv.asom.desktop.win.fakes.FakeAcl
import xyz.mdhv.asom.desktop.win.fakes.FakeCng
import xyz.mdhv.asom.desktop.win.fakes.FakeDpapi
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.OWNER_SID
import xyz.mdhv.asom.desktop.win.keys.Es256
import xyz.mdhv.asom.desktop.win.keys.FileNik
import xyz.mdhv.asom.desktop.win.keys.KeyTierRequest
import xyz.mdhv.asom.desktop.win.keys.NikTier
import xyz.mdhv.asom.desktop.win.keys.WinNikBackends
import xyz.mdhv.asom.desktop.win.keys.WinNikStore

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeysAndAclTest {
    private val laws = LawCounter(
        listOf(
            "service-sid-known-vector", "acl-rejects-broad-group", "acl-rejects-inherited-users", "acl-rejects-missing-required",
            "acl-rejects-wrong-owner", "acl-rejects-overgrant", "acl-accepts-plan", "acl-rights-mapping", "file-nik-refuses-open-dir",
            "file-nik-dpapi-wrap", "file-nik-create-new", "store-creates-nothing-before-enable", "store-refuses-second-identity",
            "store-service-scope-and-sddl",
        ),
    )

    private val svc = ServiceSid.of("asom")
    private val full = setOf(AclRight.FULL)

    // ---- ServiceSid ------------------------------------------------------------------------------------------------

    @Test
    fun `the per-service SID matches the value Windows publishes for TrustedInstaller`() {
        assertEquals("S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464", ServiceSid.of("TrustedInstaller"))
        assertEquals(ServiceSid.of("TrustedInstaller"), ServiceSid.of("TRUSTEDINSTALLER"), "the name is case-insensitive")
        assertTrue(svc.startsWith("S-1-5-80-") && svc.split('-').size == 9)
        assertFailsWith<IllegalArgumentException> { ServiceSid.of("") }
        assertFailsWith<IllegalArgumentException> { ServiceSid.of("NT SERVICE\\asom") }
        laws.hit("service-sid-known-vector")
    }

    // ---- AclVerifier -----------------------------------------------------------------------------------------------

    private fun snap(owner: String?, vararg aces: Ace) = AclSnapshot(owner, aces.toList())

    @Test
    fun `a DACL that grants Everyone, Users or Authenticated Users fails, whether set or inherited`() {
        val plan = AclPlan.userState(OWNER_SID)
        val ok = plan.required
        assertEquals(emptyList(), AclVerifier.violations(plan, snap(OWNER_SID, *ok.toTypedArray())))
        laws.hit("acl-accepts-plan")
        for (sid in listOf(WellKnownSid.EVERYONE, WellKnownSid.USERS, WellKnownSid.AUTHENTICATED_USERS)) {
            val v = AclVerifier.violations(plan, snap(OWNER_SID, *ok.toTypedArray(), Ace(sid, true, setOf(AclRight.READ))))
            assertTrue(v.any { sid in it && "not in the plan" in it }, "$sid: $v")
            laws.hit("acl-rejects-broad-group")
        }
        // %ProgramData% hands Users read/execute to a new directory: an inherited ACE looks exactly like this.
        val inherited = AclVerifier.violations(AclPlan.serviceState(svc), snap(WellKnownSid.ADMINISTRATORS,
            Ace(svc, true, full), Ace(WellKnownSid.SYSTEM, true, full), Ace(WellKnownSid.ADMINISTRATORS, true, full), Ace(WellKnownSid.USERS, true, setOf(AclRight.READ))))
        assertEquals(1, inherited.size, inherited.toString())
        laws.hit("acl-rejects-inherited-users")
        // A deny ACE only narrows and is not a violation.
        assertEquals(emptyList(), AclVerifier.violations(plan, snap(OWNER_SID, *ok.toTypedArray(), Ace(WellKnownSid.EVERYONE, false, full))))
    }

    @Test
    fun `a missing required ACE, a wrong owner and an over-grant each fail`() {
        val plan = AclPlan.userState(OWNER_SID)
        val noSystem = plan.required.filter { it.sid != WellKnownSid.SYSTEM }
        assertTrue(AclVerifier.violations(plan, snap(OWNER_SID, *noSystem.toTypedArray())).any { WellKnownSid.SYSTEM in it && "missing" in it })
        laws.hit("acl-rejects-missing-required")
        assertTrue(AclVerifier.violations(plan, snap("S-1-5-21-9-9-9-500", *plan.required.toTypedArray())).any { "owner is" in it })
        assertTrue(AclVerifier.violations(plan, snap(null, *plan.required.toTypedArray())).any { "owner unreadable" in it })
        laws.hit("acl-rejects-wrong-owner")
        // Service run dir: the owner may write and read, never FULL (which could change the ACL).
        val run = AclPlan.serviceRunDir(OWNER_SID, svc)
        val good = run.required
        assertEquals(emptyList(), AclVerifier.violations(run, snap(WellKnownSid.ADMINISTRATORS, *good.toTypedArray())))
        val over = good.filter { it.sid != OWNER_SID } + Ace(OWNER_SID, true, full)
        assertTrue(AclVerifier.violations(run, snap(WellKnownSid.ADMINISTRATORS, *over.toTypedArray())).any { "more than the plan" in it })
        laws.hit("acl-rejects-overgrant")
        assertFailsWith<IllegalArgumentException> { AclPlan("bad", listOf(Ace(WellKnownSid.EVERYONE, true, full)), setOf(OWNER_SID)) }
    }

    @Test
    fun `NTFS permission sets map to the three node rights and back`() {
        val all = java.nio.file.attribute.AclEntryPermission.entries.toSet()
        assertTrue(AclRight.FULL in AclRights.rightsOf(all))
        val write = AclRights.permissionsFor(setOf(AclRight.WRITE))
        assertEquals(setOf(AclRight.WRITE), AclRights.rightsOf(write) - AclRight.READ, "write-only grants write (READ_ATTRIBUTES aside)")
        assertFalse(AclRight.FULL in AclRights.rightsOf(write))
        val read = AclRights.permissionsFor(setOf(AclRight.READ))
        assertEquals(setOf(AclRight.READ), AclRights.rightsOf(read))
        assertEquals(emptySet(), AclRights.rightsOf(emptySet()))
        assertEquals(all, AclRights.permissionsFor(full))
        laws.hit("acl-rights-mapping")
    }

    // ---- FileNik ---------------------------------------------------------------------------------------------------

    private fun identityDir(acl: FakeAcl, plan: AclPlan): Path {
        val dir = Files.createTempDirectory("asom-fnik-")
        acl.replace(dir, plan.required)
        acl.store[dir.resolve(FileNik.FILE_NAME)] = AclSnapshot(OWNER_SID, plan.required)
        return dir
    }

    @Test
    fun `the T0 file is refused in a directory that other users can read`() {
        val plan = AclPlan.userState(OWNER_SID)
        val acl = FakeAcl(OWNER_SID)
        val dir = Files.createTempDirectory("asom-fnik-open-")
        acl.store[dir] = AclSnapshot(OWNER_SID, plan.required + Ace(WellKnownSid.USERS, true, setOf(AclRight.READ)))
        val e = assertFailsWith<IllegalArgumentException> { FileNik.create(dir, null, acl, plan) }
        assertTrue("does not honour the plan" in e.message!!)
        assertFalse(Files.exists(dir.resolve(FileNik.FILE_NAME)), "nothing may be written before the check")
        laws.hit("file-nik-refuses-open-dir")
        // The file's own DACL is checked after the write; a bad one removes the key again.
        val acl2 = FakeAcl(OWNER_SID)
        val dir2 = Files.createTempDirectory("asom-fnik-open2-")
        acl2.replace(dir2, plan.required)
        acl2.store[dir2.resolve(FileNik.FILE_NAME)] = AclSnapshot(OWNER_SID, plan.required + Ace(WellKnownSid.EVERYONE, true, full))
        assertFailsWith<IllegalStateException> { FileNik.create(dir2, null, acl2, plan) }
        assertFalse(Files.exists(dir2.resolve(FileNik.FILE_NAME)))
    }

    @Test
    fun `a DPAPI-wrapped T0 key never holds the plain key material and reopens to the same key`() {
        val plan = AclPlan.userState(OWNER_SID)
        val acl = FakeAcl(OWNER_SID)
        val dir = identityDir(acl, plan)
        val dpapi = FakeDpapi()
        val key = FileNik.create(dir, dpapi, acl, plan)
        assertEquals(1, dpapi.protectCalls)
        val onDisk = Files.readAllBytes(dir.resolve(FileNik.FILE_NAME))
        assertTrue(onDisk.copyOfRange(0, 8).contentEquals("ASOMNIK1".toByteArray()))
        assertEquals(1, onDisk[8].toInt() and 1, "the DPAPI flag is recorded")
        // The SPKI is inside the wrapped payload, so its bytes must not appear on disk in the clear.
        val spki = key.spki()
        assertFalse(indexOf(onDisk, spki) >= 0, "the wrapped file must not contain the plain SPKI")
        val msg = "hello".toByteArray()
        val back = FileNik.open(dir, dpapi)!!
        assertContentEquals(spki, back.spki())
        assertTrue(Es256.verify(spki, msg, back.sign(msg)))
        assertFailsWith<IllegalArgumentException> { FileNik.open(dir, null) }
        laws.hit("file-nik-dpapi-wrap")
        // Unwrapped: the SPKI IS on disk (the control for the assertion above).
        val acl2 = FakeAcl(OWNER_SID)
        val dir2 = identityDir(acl2, plan)
        val plain = FileNik.create(dir2, null, acl2, plan)
        assertTrue(indexOf(Files.readAllBytes(dir2.resolve(FileNik.FILE_NAME)), plain.spki()) >= 0)
    }

    @Test
    fun `an existing T0 key is never overwritten and delete removes the file`() {
        val plan = AclPlan.userState(OWNER_SID)
        val acl = FakeAcl(OWNER_SID)
        val dir = identityDir(acl, plan)
        val key = FileNik.create(dir, null, acl, plan)
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { FileNik.create(dir, null, acl, plan) }
        laws.hit("file-nik-create-new")
        key.delete()
        assertFalse(Files.exists(dir.resolve(FileNik.FILE_NAME)))
        assertNull(FileNik.open(dir, null))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ---- WinNikStore -----------------------------------------------------------------------------------------------

    private fun paths(mode: HostMode): NodePaths {
        val base = Files.createTempDirectory("asom-store-")
        return NodePaths(mode, base, base, base.resolve("ledger"), base.resolve("node").also { Files.createDirectories(it) }, base.resolve("run"), base.resolve("run/ctl.sock"))
    }

    @Test
    fun `constructing the store and reading its tier creates nothing, and the key is created once at first mesh enable`() {
        val cng = FakeCng()
        val acl = FakeAcl(OWNER_SID)
        val store = WinNikStore(paths(HostMode.USER), WinNikBackends(cng, FakeDpapi(), acl), { OWNER_SID })
        assertEquals(KeyStorage.UNKNOWN, store.keyStorage)
        assertEquals(KeyStorage.UNKNOWN, store.keyStorage)
        assertTrue(cng.created.isEmpty(), "no key may exist before the first mesh enable")
        laws.hit("store-creates-nothing-before-enable")

        val sel = store.createAtFirstMeshEnable(KeyTierRequest.AUTO)
        assertEquals(NikTier.T2_TPM, sel.tier)
        assertEquals(listOf("PLATFORM/asom-nik-v1/USER"), cng.created)
        assertEquals(KeyStorage.TPM, store.keyStorage)
        val e = assertFailsWith<IllegalStateException> { store.createAtFirstMeshEnable(KeyTierRequest.AUTO) }
        assertTrue("already exists" in e.message!!)
        assertEquals(1, cng.created.size, "the identity is never replaced")
        laws.hit("store-refuses-second-identity")
        // A fresh store object (another process) sees the same tier without creating anything.
        assertEquals(KeyStorage.TPM, WinNikStore(paths(HostMode.USER), WinNikBackends(cng, FakeDpapi(), acl), { OWNER_SID }).keyStorage)
        assertEquals(1, cng.created.size)
    }

    @Test
    fun `service mode uses a machine-scope key whose DACL names SYSTEM and the service SID only`() {
        val cng = FakeCng()
        val store = WinNikStore(paths(HostMode.SYSTEM), WinNikBackends(cng, FakeDpapi(), FakeAcl()), { null })
        val sel = store.createAtFirstMeshEnable(KeyTierRequest.AUTO)
        assertEquals(listOf("PLATFORM/asom-nik-v1-svc/MACHINE"), cng.created)
        val sddl = cng.keys.getValue(CngProvider.PLATFORM to "asom-nik-v1-svc").sddl!!
        assertEquals("D:P(A;;GA;;;SY)(A;;GA;;;$svc)", sddl)
        assertFalse("WD" in sddl || "BU" in sddl || "AU" in sddl || "BA" in sddl, "no Everyone, Users, Authenticated Users or Administrators in the key DACL")
        assertEquals(NikTier.T2_TPM, sel.tier)
        assertEquals(KeyScope.MACHINE, cng.keys.getValue(CngProvider.PLATFORM to "asom-nik-v1-svc").scope)
        laws.hit("store-service-scope-and-sddl")
    }

    @Test
    fun `the file tier needs the owner SID and is created through the file request only`() {
        val p = paths(HostMode.USER)
        val acl = FakeAcl(OWNER_SID)
        acl.replace(p.identityDir, AclPlan.userState(OWNER_SID).required)
        acl.store[p.identityDir.resolve(FileNik.FILE_NAME)] = AclSnapshot(OWNER_SID, AclPlan.userState(OWNER_SID).required)
        val store = WinNikStore(p, WinNikBackends(FakeCng(), null, acl), { OWNER_SID })
        val sel = store.createAtFirstMeshEnable(KeyTierRequest.FILE)
        assertEquals(NikTier.T0_FILE, sel.tier)
        assertEquals(KeyStorage.FILE, WinNikStore(p, WinNikBackends(FakeCng(), null, acl), { OWNER_SID }).keyStorage)
        // no owner SID: refused, and nothing is left behind
        val p2 = paths(HostMode.USER)
        val failing = WinNikStore(p2, WinNikBackends(FakeCng(), null, FakeAcl()), { null })
        assertFailsWith<xyz.mdhv.asom.desktop.win.keys.NikSelectionFailure> { failing.createAtFirstMeshEnable(KeyTierRequest.FILE) }
        assertFalse(Files.exists(p2.identityDir.resolve(FileNik.FILE_NAME)))
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("keys-and-acl")
}
