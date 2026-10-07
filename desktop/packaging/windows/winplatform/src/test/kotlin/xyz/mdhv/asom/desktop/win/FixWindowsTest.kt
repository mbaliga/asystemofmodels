package xyz.mdhv.asom.desktop.win

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.DesktopPlatform
import xyz.mdhv.asom.desktop.ExitCodes
import xyz.mdhv.asom.desktop.HostLookup
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodeMain
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.win.acl.Ace
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.AclRight
import xyz.mdhv.asom.desktop.win.acl.AclRights
import xyz.mdhv.asom.desktop.win.acl.AclSnapshot
import xyz.mdhv.asom.desktop.win.acl.AclVerifier
import xyz.mdhv.asom.desktop.win.acl.ServiceSid
import xyz.mdhv.asom.desktop.win.acl.WellKnownSid
import xyz.mdhv.asom.desktop.win.acl.WinAcl
import xyz.mdhv.asom.desktop.win.api.CngProvider
import xyz.mdhv.asom.desktop.win.api.KeyScope
import xyz.mdhv.asom.desktop.win.api.WinApiException
import xyz.mdhv.asom.desktop.win.ctl.WinControlSocket
import xyz.mdhv.asom.desktop.win.exec.SystemProcessRunner
import xyz.mdhv.asom.desktop.win.fakes.Captured
import xyz.mdhv.asom.desktop.win.fakes.FakeAcl
import xyz.mdhv.asom.desktop.win.fakes.FakeCng
import xyz.mdhv.asom.desktop.win.fakes.FakeMutex
import xyz.mdhv.asom.desktop.win.fakes.FakeNative
import xyz.mdhv.asom.desktop.win.fakes.FakeRunner
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.OWNER_SID
import xyz.mdhv.asom.desktop.win.fakes.fakeEnv
import xyz.mdhv.asom.desktop.win.jna.JnaCng
import xyz.mdhv.asom.desktop.win.jna.JnaSessions
import xyz.mdhv.asom.desktop.win.jna.NCryptApi
import xyz.mdhv.asom.desktop.win.keys.KeyTierRequest
import xyz.mdhv.asom.desktop.win.keys.NikTier
import xyz.mdhv.asom.desktop.win.keys.WinNikStore
import xyz.mdhv.asom.desktop.win.net.FirewallGate
import xyz.mdhv.asom.desktop.win.net.FirewallRead
import xyz.mdhv.asom.desktop.win.net.FirewallTarget
import xyz.mdhv.asom.desktop.win.net.GateResult
import xyz.mdhv.asom.desktop.win.net.NetshFirewallProbe
import xyz.mdhv.asom.desktop.win.presence.WinPresenceReader
import xyz.mdhv.asom.desktop.win.service.ServiceHost
import xyz.mdhv.asom.desktop.win.thermal.ThermalZoneProbe

/**
 * Regression tests for the verified review findings HWM-1 to HWM-6, HWM-12 and HWM-13. Every test here runs on any
 * operating system: the Windows APIs are replaced by fakes behind the same interfaces, so these tests prove the LOGIC and
 * say nothing about a real TPM, a real DACL or a real console session (those remain NEEDS-DEVICE-VALIDATION).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FixWindowsTest {
    private val laws = LawCounter(
        listOf(
            "hwm1-provider-status", "hwm1-no-second-identity", "hwm2-mutex-oracle", "hwm3-state-acl", "hwm4-escalation-rights",
            "hwm5-socket-file", "hwm6-console-read", "hwm12-kelvin", "hwm13-truncation",
        ),
    )

    // ---- HWM-1: an unopenable provider is not "no such key" ------------------------------------------------------------------

    private class FakeNCrypt(var providerStatus: Int, var keyStatus: Int = JnaCng.NTE_BAD_KEYSET) : NCryptApi {
        var providerOpens = 0

        override fun NCryptOpenStorageProvider(provider: PointerByReference, providerName: String?, flags: Int): Int {
            providerOpens++
            if (providerStatus == 0) provider.value = Pointer(1)
            return providerStatus
        }

        override fun NCryptOpenKey(provider: Pointer, key: PointerByReference, keyName: String, legacyKeySpec: Int, flags: Int): Int = keyStatus
        override fun NCryptFreeObject(obj: Pointer): Int = 0
        override fun NCryptCreatePersistedKey(provider: Pointer, key: PointerByReference, algorithm: String, keyName: String?, legacyKeySpec: Int, flags: Int): Int = error("unused")
        override fun NCryptSetProperty(obj: Pointer, property: String, input: ByteArray, inputSize: Int, flags: Int): Int = error("unused")
        override fun NCryptFinalizeKey(key: Pointer, flags: Int): Int = error("unused")
        override fun NCryptSignHash(key: Pointer, paddingInfo: Pointer?, hash: ByteArray, hashSize: Int, signature: ByteArray?, signatureSize: Int, resultSize: IntByReference, flags: Int): Int = error("unused")
        override fun NCryptExportKey(key: Pointer, exportKey: Pointer?, blobType: String, parameters: Pointer?, output: ByteArray?, outputSize: Int, resultSize: IntByReference, flags: Int): Int = error("unused")
        override fun NCryptDeleteKey(key: Pointer, flags: Int): Int = error("unused")
    }

    @Test
    fun `HWM-1 a provider that cannot be opened for a reason other than absence is an error, never no key`() {
        val unavailable = mapOf(
            "NTE_DEVICE_NOT_READY (TPM still starting)" to 0x80090030.toInt(),
            "NTE_FAIL" to 0x80090020.toInt(),
            "NTE_INTERNAL_ERROR" to 0x8009002D.toInt(),
            "E_ACCESSDENIED" to 0x80070005.toInt(),
            "RPC_S_SERVER_UNAVAILABLE (TBS stopped)" to 0x800706BA.toInt(),
            "TBS_E_SERVICE_NOT_RUNNING" to 0x80284008.toInt(),
        )
        for ((name, status) in unavailable) {
            val api = FakeNCrypt(status)
            val e = assertFailsWith<WinApiException>(name) { JnaCng { api }.open(CngProvider.PLATFORM, "asom-nik-v1", KeyScope.USER) }
            assertEquals(status, e.status, name)
            laws.hit("hwm1-provider-status")
        }
    }

    @Test
    fun `HWM-1 a provider that is not installed or has no device is still no key, so a machine without a TPM falls through to T1`() {
        val absent = listOf(0x80090017.toInt(), 0x8009001E.toInt(), 0x80090011.toInt(), 0x80090029.toInt(), 0x80090035.toInt())
        for (status in absent) {
            val api = FakeNCrypt(status)
            assertNull(JnaCng { api }.open(CngProvider.PLATFORM, "asom-nik-v1", KeyScope.USER), "0x${Integer.toHexString(status)}")
            laws.hit("hwm1-provider-status")
        }
        val keyless = FakeNCrypt(0, keyStatus = 0x80090016.toInt())
        assertNull(JnaCng { keyless }.open(CngProvider.SOFTWARE, "asom-nik-v1", KeyScope.USER), "a reachable provider without the key is no key")
        val denied = FakeNCrypt(0, keyStatus = 0x80070005.toInt())
        assertFailsWith<WinApiException> { JnaCng { denied }.open(CngProvider.SOFTWARE, "asom-nik-v1", KeyScope.USER) }
    }

    private fun store(native: FakeNative = FakeNative()): Pair<FakeNative, WinNikStore> {
        val p = WinPlatform(fakeEnv(), native.bundle(), WinOptions())
        return native to (p.nikStore(p.paths(HostMode.USER)) as WinNikStore)
    }

    @Test
    fun `HWM-1 a T2 identity hidden by an unavailable provider is not replaced by a second, weaker identity`() {
        val (native, store) = store()
        assertEquals(NikTier.T2_TPM, store.createAtFirstMeshEnable(KeyTierRequest.AUTO).tier)
        native.cng.providerUnavailable = setOf(CngProvider.PLATFORM)
        val before = native.cng.created.toList()
        val e = assertFailsWith<IllegalStateException> { store.createAtFirstMeshEnable(KeyTierRequest.AUTO) }
        assertContains(e.message.orEmpty(), "cannot tell whether a node key already exists")
        assertEquals(before, native.cng.created, "no key of any tier was created while the question was open")
        assertEquals(1, native.cng.keys.size)
        // the store does not claim a tier it cannot see
        assertEquals(xyz.mdhv.asom.desktop.KeyStorage.UNKNOWN, WinPlatform(fakeEnv(), native.bundle(), WinOptions()).let { it.nikStore(it.paths(HostMode.USER)) }.keyStorage)
        // control: with the provider back, the existing key is found and still refuses replacement
        native.cng.providerUnavailable = emptySet()
        assertFailsWith<IllegalStateException> { store.createAtFirstMeshEnable(KeyTierRequest.AUTO) }.also { assertContains(it.message.orEmpty(), "already exists") }
        laws.hit("hwm1-no-second-identity")
    }

    @Test
    fun `HWM-1 control a machine whose Platform Crypto Provider is absent still gets a T1 key`() {
        val native = FakeNative().also { it.cng.providerMissing = setOf(CngProvider.PLATFORM) }
        val (_, store) = store(native)
        assertEquals(NikTier.T1_OS_KEYSTORE, store.createAtFirstMeshEnable(KeyTierRequest.AUTO).tier)
        laws.hit("hwm1-no-second-identity")
    }

    // ---- HWM-2: the single-identity mutex follows the mode NodeMain will actually run ---------------------------------------

    private fun platform(native: FakeNative = FakeNative()) = WinPlatform(fakeEnv(), native.bundle(), WinOptions())

    @Test
    fun `HWM-2 whenever NodeMain would start a node, WinNodeMain takes the mutex first, whatever order the arguments come in`() {
        val argLists = listOf(
            listOf("--mode=user"), listOf("--mode=system"), listOf("--mode=foreground"), listOf("--foreground"),
            listOf("--self-test", "--mode=user"), listOf("--self-test", "--mode=system"), listOf("--self-test", "--foreground"),
            listOf("--mode=selftest", "--mode=user"), listOf("--mode=selftest", "--foreground"), listOf("--self-test", "--mode=foreground"),
            listOf("--mode=user", "--self-test"), listOf("--foreground", "--mode=selftest"),
            listOf("--mode=selftest"), listOf("--self-test"), listOf("--version"), listOf("--help"),
            listOf("--mode=user", "--version"), listOf("--self-test", "--help"), listOf("--bogus"), listOf("--mode=nonsense"), emptyList(),
        )
        var started = 0
        var ranBesideLiveNode = 0
        for (args in argLists) {
            var direct = false
            val directCode = NodeMain.run(args, Captured().env(), HostLookup.Found(platform())) { direct = true }
            val held = FakeMutex().also { it.held.add("Global\\asom-node") }
            var viaWin = false
            val cap = Captured()
            val code = WinNodeMain.run(args, cap.env(), platform(), NodeMutex(held)) { viaWin = true }
            if (direct) {
                assertFalse(viaWin, "$args: a node must not run while another one holds the identity")
                assertEquals(ExitCodes.REFUSED, code, "$args: ${cap.errText}")
                started++
            } else if (directCode == ExitCodes.OK) {
                assertEquals(ExitCodes.OK, code, "$args: self-test, version and help still run beside a live node")
                ranBesideLiveNode++
            }
            assertEquals(setOf("Global\\asom-node"), held.held, "$args: nobody released the other node's mutex")
        }
        assertTrue(started >= 6, "non-vacuity: only $started start-a-node argument lists were exercised")
        assertTrue(ranBesideLiveNode >= 6, "non-vacuity: only $ranBesideLiveNode run-beside-a-node lists were exercised")
        laws.hit("hwm2-mutex-oracle")
    }

    // ---- HWM-3: the service state directory is verified before config.json is read -------------------------------------------

    private class ShiftedPlatform(private val inner: WinPlatform, private val paths: NodePaths) : DesktopPlatform by inner {
        override fun paths(mode: HostMode): NodePaths = paths
    }

    private fun serviceTree(): NodePaths {
        val dir = Files.createTempDirectory("asom-svc-")
        return NodePaths(HostMode.SYSTEM, dir, dir, dir.resolve("ledger"), dir.resolve("node"), dir.resolve("run"), dir.resolve("run").resolve("ctl.sock"))
    }

    private class Started(val failure: Throwable?, val ranRuntime: Boolean)

    private fun startHost(host: ServiceHost): Started {
        var failure: Throwable? = null
        val done = CountDownLatch(1)
        val t = Thread { try { host.start() } catch (e: Throwable) { failure = e } finally { done.countDown() } }
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (host.runtime == null && failure == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val ran = host.runtime != null
        if (ran) host.stop()
        assertTrue(done.await(10, TimeUnit.SECONDS), "start must return after stop or failure")
        return Started(failure, ran)
    }

    @Test
    fun `HWM-3 a service state directory or config file owned by an ordinary user is refused before the config is read`() {
        val svc = ServiceSid.of("asom")
        val good = AclPlan.serviceState(svc).required
        val userSid = "S-1-5-21-5-5-5-1234"
        fun run(dirSnap: AclSnapshot?, fileSnap: AclSnapshot?, config: Boolean = true): Started {
            val paths = serviceTree()
            if (config) Files.writeString(paths.stateDir.resolve("config.json"), "{}", Charsets.UTF_8)
            val acl = FakeAcl()
            dirSnap?.let { acl.store[paths.stateDir] = it }
            fileSnap?.let { acl.store[paths.stateDir.resolve("config.json")] = it }
            val mutex = FakeMutex()
            val host = ServiceHost(ShiftedPlatform(platform(), paths), NodeMutex(mutex), acl = acl, serviceSid = svc)
            return startHost(host).also { assertTrue(mutex.held.isEmpty(), "a refused start releases the identity") }
        }
        // squatted directory
        val squatted = run(AclSnapshot(userSid, good), AclSnapshot(WellKnownSid.ADMINISTRATORS, good))
        assertTrue(squatted.failure is HostRefusedException && !squatted.ranRuntime, "directory owned by a user: ${squatted.failure}")
        assertContains(squatted.failure!!.message.orEmpty(), userSid)
        // a config file planted into a directory that is otherwise right
        val planted = run(AclSnapshot(WellKnownSid.ADMINISTRATORS, good), AclSnapshot(userSid, good))
        assertTrue(planted.failure is HostRefusedException && !planted.ranRuntime, "file owned by a user: ${planted.failure}")
        // a directory that carries an ACE for the user
        val leaked = run(AclSnapshot(WellKnownSid.ADMINISTRATORS, good + Ace(userSid, true, setOf(AclRight.WRITE))), AclSnapshot(WellKnownSid.ADMINISTRATORS, good))
        assertTrue(leaked.failure is HostRefusedException && !leaked.ranRuntime, "directory with a user ACE: ${leaked.failure}")
        // controls: a directory the installer owns starts, and so does a tree with no config at all
        val fine = run(AclSnapshot(WellKnownSid.ADMINISTRATORS, good), AclSnapshot(WellKnownSid.SYSTEM, good))
        assertTrue(fine.failure == null && fine.ranRuntime, "verified tree: ${fine.failure}")
        val bare = run(null, null, config = false)
        assertTrue(bare.failure == null && bare.ranRuntime, "no config file, nothing to read: ${bare.failure}")
        laws.hit("hwm3-state-acl")
    }

    // ---- HWM-4: DACL-changing rights are not "write" ------------------------------------------------------------------------

    @Test
    fun `HWM-4 an owner ACE that can rewrite the DACL, take ownership or delete is not the plan's write-only`() {
        val svc = ServiceSid.of("asom")
        val run = AclPlan.serviceRunDir(OWNER_SID, svc)
        val others = run.required.filter { it.sid != OWNER_SID }
        fun violations(perms: Set<AclEntryPermission>) =
            AclVerifier.violations(run, AclSnapshot(WellKnownSid.ADMINISTRATORS, others + Ace(OWNER_SID, true, AclRights.rightsOf(perms))))
        val ordinary = setOf(AclEntryPermission.WRITE_DATA, AclEntryPermission.READ_DATA)
        assertEquals(emptyList(), violations(ordinary + AclEntryPermission.READ_ACL), "the plan's own write and read pass")
        assertEquals(emptyList(), violations(AclRights.permissionsFor(setOf(AclRight.WRITE, AclRight.READ))), "what the plan writes reads back as the plan")
        val escalating = mapOf(
            "the review's case" to setOf(AclEntryPermission.WRITE_DATA, AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER, AclEntryPermission.DELETE_CHILD),
            "WRITE_ACL" to ordinary + AclEntryPermission.WRITE_ACL,
            "WRITE_OWNER" to ordinary + AclEntryPermission.WRITE_OWNER,
            "DELETE" to ordinary + AclEntryPermission.DELETE,
            "DELETE_CHILD" to ordinary + AclEntryPermission.DELETE_CHILD,
        )
        for ((name, perms) in escalating) {
            assertTrue(violations(perms).isNotEmpty(), "$name must violate the owner-write-only plan")
            laws.hit("hwm4-escalation-rights")
        }
        // the principals that may hold everything still may, and a required FULL is not met by a lone WRITE_ACL
        val user = AclPlan.userState(OWNER_SID)
        assertEquals(emptyList(), AclVerifier.violations(user, AclSnapshot(OWNER_SID, user.required.map { it.copy(rights = AclRights.rightsOf(AclEntryPermission.entries.toSet())) })))
        val weak = user.required.filter { it.sid != OWNER_SID } + Ace(OWNER_SID, true, AclRights.rightsOf(setOf(AclEntryPermission.WRITE_ACL)))
        assertTrue(AclVerifier.violations(user, AclSnapshot(OWNER_SID, weak)).any { "required ACE for $OWNER_SID" in it })
        assertEquals(setOf(AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER), AclRights.permissionsFor(setOf(AclRight.CONTROL)))
    }

    // ---- HWM-5: the socket FILE is checked, not only the directory ------------------------------------------------------------

    @Test
    fun `HWM-5 the precheck reads the socket file's owner and DACL as well as the directory's`() {
        val svc = ServiceSid.of("asom")
        val runDir = Path.of("run")
        val sock = Path.of("run", "ctl.sock")
        val plan = AclPlan.serviceRunDir(OWNER_SID, svc)
        fun acl(dirOwner: String, fileOwner: String?, fileAces: List<Ace> = plan.required): FakeAcl = FakeAcl().also {
            it.store[runDir] = AclSnapshot(dirOwner, plan.required)
            if (fileOwner != null) it.store[sock] = AclSnapshot(fileOwner, fileAces)
        }
        // the gap: a socket planted by the owner passes the directory check
        assertEquals(emptyList(), WinControlSocket.precheck(acl(WellKnownSid.ADMINISTRATORS, OWNER_SID), runDir, plan))
        // service mode: the file must belong to the service
        val planted = WinControlSocket.precheckConnect(acl(WellKnownSid.ADMINISTRATORS, OWNER_SID), HostMode.SYSTEM, OWNER_SID, runDir, sock)
        assertTrue(planted.any { "owner is $OWNER_SID" in it }, planted.toString())
        assertEquals(emptyList(), WinControlSocket.precheckConnect(acl(WellKnownSid.ADMINISTRATORS, svc), HostMode.SYSTEM, OWNER_SID, runDir, sock))
        val stray = WinControlSocket.precheckConnect(acl(WellKnownSid.ADMINISTRATORS, svc, plan.required + Ace(WellKnownSid.USERS, true, setOf(AclRight.WRITE))), HostMode.SYSTEM, OWNER_SID, runDir, sock)
        assertTrue(stray.any { WellKnownSid.USERS in it }, stray.toString())
        // user mode: the file must belong to the owner
        val userPlan = AclPlan.userRunDir(OWNER_SID)
        fun userAcl(fileOwner: String) = FakeAcl().also {
            it.store[runDir] = AclSnapshot(OWNER_SID, userPlan.required)
            it.store[sock] = AclSnapshot(fileOwner, userPlan.required)
        }
        assertEquals(emptyList(), WinControlSocket.precheckConnect(userAcl(OWNER_SID), HostMode.USER, OWNER_SID, runDir, sock))
        assertTrue(WinControlSocket.precheckConnect(userAcl("S-1-5-21-9-9-9-1500"), HostMode.USER, OWNER_SID, runDir, sock).any { "owner is" in it })
        // a directory that fails still fails, and a socket file that is not there (or cannot be read) fails closed
        assertTrue(WinControlSocket.precheckConnect(acl(OWNER_SID, svc), HostMode.SYSTEM, OWNER_SID, runDir, sock).any { "owner is" in it })
        val missing = WinControlSocket.precheckConnect(acl(WellKnownSid.ADMINISTRATORS, null).also { it.store.remove(sock) }, HostMode.SYSTEM, OWNER_SID, runDir, sock)
        assertTrue(missing.isNotEmpty(), "an unreadable or absent socket file must not pass")
        val thrower = object : WinAcl {
            override fun snapshot(path: Path): AclSnapshot = if (path == sock) error("access denied") else AclSnapshot(WellKnownSid.ADMINISTRATORS, plan.required)
            override fun replace(path: Path, aces: List<Ace>) = error("no")
        }
        assertTrue(WinControlSocket.precheckConnect(thrower, HostMode.SYSTEM, OWNER_SID, runDir, sock).single().startsWith("cannot read the socket file"))
        laws.hit("hwm5-socket-file")
    }

    // ---- HWM-6: an unreadable console session is a present user --------------------------------------------------------------

    private fun wtsInfo(level: Int = 1, user: String = "bob", size: Int = 208): ByteArray {
        val b = ByteArray(size)
        fun put32(o: Int, v: Int) { for (i in 0..3) if (o + i < size) b[o + i] = (v shr (8 * i)).toByte() }
        put32(0, level)
        put32(8, 2)
        put32(16, 1)
        user.forEachIndexed { i, c -> b[86 + 2 * i] = c.code.toByte() }
        return b
    }

    @Test
    fun `HWM-6 a console session that cannot be read throws, so presence treats it as a present user even when logged-out serving is on`() {
        val none = JnaSessions(consoleId = { -1 }, rawQuery = { error("must not query when there is no console session") })
        assertNull(none.consoleSession(), "no console session at all is null")
        val unreadable = mapOf(
            "the call failed" to JnaSessions(consoleId = { 2 }, rawQuery = { null }),
            "the buffer is short" to JnaSessions(consoleId = { 2 }, rawQuery = { wtsInfo(size = 100) }),
            "the level is not 1" to JnaSessions(consoleId = { 2 }, rawQuery = { wtsInfo(level = 7) }),
        )
        for ((name, sessions) in unreadable) {
            assertFailsWith<WinApiException>(name) { sessions.consoleSession() }
            val verdict = WinPresenceReader(true, xyz.mdhv.asom.desktop.win.fakes.FakeUserInput(), sessions, WinOptions(serveWhileLoggedOut = true)).verdict()
            assertTrue(verdict.present, "$name: ${verdict.blocks}")
            laws.hit("hwm6-console-read")
        }
        // controls: a readable session with a user parses, and a readable logon screen (blank user) is genuinely logged out
        val bob = JnaSessions(consoleId = { 2 }, rawQuery = { wtsInfo() }).consoleSession()
        assertEquals("bob", bob?.userName)
        val logon = JnaSessions(consoleId = { 2 }, rawQuery = { wtsInfo(user = "") })
        assertFalse(WinPresenceReader(true, xyz.mdhv.asom.desktop.win.fakes.FakeUserInput(), logon, WinOptions(serveWhileLoggedOut = true)).verdict().present)
        assertTrue(WinPresenceReader(true, xyz.mdhv.asom.desktop.win.fakes.FakeUserInput(), none, WinOptions(serveWhileLoggedOut = false)).verdict().present)
        assertFalse(WinPresenceReader(true, xyz.mdhv.asom.desktop.win.fakes.FakeUserInput(), none, WinOptions(serveWhileLoggedOut = true)).verdict().present)
        laws.hit("hwm6-console-read")
    }

    // ---- HWM-12: integer narrowing in the thermal conversion ------------------------------------------------------------------

    @Test
    fun `HWM-12 an absurd or non-finite Kelvin reading is rejected before it is narrowed, never wrapped into the plausible band`() {
        for (k in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 4295260.446, 4295260.446 + 4294967.296, 1e300, 1e18, 0.0, -5.0)) {
            assertNull(ThermalZoneProbe.kelvinToMilliC(k), "kelvin $k")
            laws.hit("hwm12-kelvin")
        }
        assertEquals(26_850, ThermalZoneProbe.kelvinToMilliC(300.0))
        assertEquals(-100_000, ThermalZoneProbe.kelvinToMilliC(173.15))
        assertEquals(250_000, ThermalZoneProbe.kelvinToMilliC(523.15))
        assertNull(ThermalZoneProbe.kelvinToMilliC(523.2))
        assertNull(ThermalZoneProbe.kelvinToMilliC(173.1))
    }

    // ---- HWM-13: a truncated or failed netsh listing is a probe failure ---------------------------------------------------------

    private class CannedProcess(bytes: ByteArray, private val exit: Int = 0) : Process() {
        private val input = ByteArrayInputStream(bytes)
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = exit
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = exit
        override fun destroy() { destroyed = true }
    }

    private fun fixture(name: String): String = FixWindowsTest::class.java.getResourceAsStream("/fixtures/netsh/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val target = FirewallTarget("C:\\Program Files\\asom\\asom.exe", null, 11436)

    @Test
    fun `HWM-13 a netsh listing cut at the output cap is a failed probe, not a shorter rule list`() {
        val text = fixture("block-after-prompt.txt")
        val cut = text.indexOf("Rule Name:                            asom.exe")
        assertTrue(cut > 0, "the fixture has the block rule after the allow rule")
        // the full listing: the later block rule wins
        val full = FirewallGate(NetshFirewallProbe(SystemProcessRunner("C:\\Windows", start = { CannedProcess(text.toByteArray(Charsets.ISO_8859_1)) }), "C:\\Windows")) { target }.evaluate()
        assertTrue(full is GateResult.BlockRulePresent, full.stateName)
        // the same listing cut just before the block rule: the cap must not turn it into OPEN
        val proc = CannedProcess(text.toByteArray(Charsets.ISO_8859_1))
        val capped = SystemProcessRunner("C:\\Windows", maxOutput = cut, start = { proc })
        val probe = NetshFirewallProbe(capped, "C:\\Windows")
        val read = probe.read()
        assertTrue(read is FirewallRead.Failed, "a capped listing must fail the probe: $read")
        assertContains((read as FirewallRead.Failed).reason, "truncated")
        assertTrue(FirewallGate(probe) { target }.evaluate() is GateResult.ProbeFailed)
        // control: a cap that is exactly the length of the listing loses nothing
        val exact = FirewallGate(NetshFirewallProbe(SystemProcessRunner("C:\\Windows", maxOutput = text.length, start = { CannedProcess(text.toByteArray(Charsets.ISO_8859_1)) }), "C:\\Windows")) { target }.evaluate()
        assertTrue(exact is GateResult.BlockRulePresent, exact.stateName)
        laws.hit("hwm13-truncation")
    }

    @Test
    fun `HWM-13 a netsh run that exits non-zero is a failed probe even when some rules parsed`() {
        val listing = fixture("allow-overlay.txt")
        val ok = NetshFirewallProbe(FakeRunner(output = listing, exitCode = 0), "C:\\Windows").read()
        assertTrue(ok is FirewallRead.Rules, "control: exit 0 parses")
        val bad = NetshFirewallProbe(FakeRunner(output = listing, exitCode = 1), "C:\\Windows").read()
        assertTrue(bad is FirewallRead.Failed, "exit 1 with a parsable listing must fail: $bad")
        assertContains((bad as FirewallRead.Failed).reason, "exit")
        laws.hit("hwm13-truncation")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("fix-windows")
}
