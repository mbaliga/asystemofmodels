package xyz.mdhv.asom.desktop.mac

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.ExitCodes
import xyz.mdhv.asom.desktop.HostFinder
import xyz.mdhv.asom.desktop.HostLookup
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.ListenerGateDecision
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeMain
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.SelfTest
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.mac.fakes.FakeHelper
import xyz.mdhv.asom.desktop.mac.helper.Fields
import xyz.mdhv.asom.desktop.mac.helper.HValue
import xyz.mdhv.asom.desktop.mac.helper.HelperProcess
import xyz.mdhv.asom.desktop.mac.helper.HelperState
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.keys.KeyTierRequest
import xyz.mdhv.asom.desktop.mac.keys.MacNikStore
import xyz.mdhv.asom.desktop.mac.presence.CpuLoadSource
import xyz.mdhv.asom.desktop.mac.presence.MacRules

/** The seam, the entry points and what happens when the helper is missing: LAB evidence, a fake helper, no Mac underneath. */
class PlatformTest {
    private val idleCpu = object : CpuLoadSource {
        override fun systemLoad() = 0.05
        override fun processLoad() = 0.01
    }

    private fun tempEnv(): MacEnv {
        val home = Files.createTempDirectory(shortTempBase(), "ah-")
        val tmp = Files.createDirectory(home.resolve("T"))
        return MacEnv("me", home.toString(), emptyMap(), { tmp.toString() }, { 501 })
    }

    private fun platform(h: FakeHelper = FakeHelper(), env: MacEnv = tempEnv(), teamId: String? = null) =
        MacPlatform(helperTransport = h, env = env, options = MacOptions(teamId), cpu = idleCpu)

    @Test
    fun `ServiceLoader finds the macOS host, and constructing it starts nothing`() {
        val lookup = HostFinder.find()
        assertIs<HostLookup.Found>(lookup)
        assertEquals("macos", lookup.platform.id)
        assertIs<MacPlatform>(lookup.platform)
        val h = FakeHelper()
        val p = platform(h)
        assertEquals("macos", p.id)
        assertTrue(h.calls.isEmpty(), "constructing spawned/called nothing")
        p.power(); p.presence(); p.thermal(); p.listenerGate(); p.hostRules(NodeConfig(), HostMode.USER)
        val paths = p.paths(HostMode.USER)
        p.nikStore(paths); p.controlSocket(paths)
        assertTrue(h.calls.isEmpty(), "obtaining the ports and paths calls nothing on the helper")
    }

    @Test
    fun `the listener gate is always open, the presence rules are the macOS ones, and the state is the dev directory when unsigned`() {
        val p = platform()
        assertEquals(ListenerGateDecision.OPEN, p.listenerGate().decision())
        assertIs<MacRules>(p.hostRules(NodeConfig(), HostMode.USER))
        assertTrue(p.devState)
        assertFalse(platform(teamId = "ABCDE12345").devState)
        val signed = platform(teamId = "ABCDE12345").paths(HostMode.USER)
        assertTrue(signed.identityDir.toString().contains("Group Containers/ABCDE12345.xyz.mdhv.asom/node"))
    }

    @Test
    fun `reading the tier and getting paths creates nothing on disk`() {
        val env = tempEnv()
        val p = platform(env = env)
        val paths = p.paths(HostMode.USER)
        val store = p.nikStore(paths)
        assertEquals(KeyStorage.UNKNOWN, store.keyStorage)
        assertFalse(Files.exists(paths.stateDir), "no directory was created")
        assertEquals(0, Files.list(Path.of(env.home)).use { it.filter { f -> f.fileName.toString() != "T" }.count() })
    }

    @Test
    fun `selftest passes, and reports the two declared later-step features by name`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = xyz.mdhv.asom.desktop.NodeEnv("me", emptyMap(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        val code = SelfTest(platform(), env).run()
        val text = out.toString(Charsets.UTF_8)
        assertEquals(ExitCodes.OK, code, text)
        assertTrue("[not-yet-implemented] control-socket bind (AF_UNIX): MC4 / D-v2 (D25, D23)" in text, text)
        assertTrue("[not-yet-implemented] daemon registration (mode B): MC9 / M2, after S-M5" in text, text)
        assertTrue(Regex("selftest: \\d+ ok, 2 not-yet-implemented, 0 failed").containsMatchIn(text), text)
        assertTrue("host macos" in text)
    }

    @Test
    fun `the node entry refuses to start as root and does not create a listener`() {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val env = xyz.mdhv.asom.desktop.NodeEnv("root", emptyMap(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        val code = NodeMain.run(listOf("--mode=user"), env, HostLookup.Found(platform())) { error("must not start") }
        assertEquals(ExitCodes.REFUSED, code)
        val rootUid = platform(env = MacEnv("me", Files.createTempDirectory("h").toString(), emptyMap(), { "/tmp" }, { 0 }))
        assertFailsWith<xyz.mdhv.asom.desktop.HostRefusedException> { rootUid.paths(HostMode.USER) }
    }

    @Test
    fun `control socket start is not implemented and the selftest can prove the declaration is true`() {
        val p = platform()
        val s = p.controlSocket(p.paths(HostMode.USER))
        assertFailsWith<NotYetImplementedException> { s.start { _, _ -> error("unreachable") } }
        for (f in p.notYetImplemented) assertFailsWith<NotYetImplementedException>(f.feature) { f.probe() }
        assertEquals(1, p.notYetImplemented.size)
    }

    // ---- the helper is missing or dies -----------------------------------------------------------------------------------------

    @Test
    fun `with no helper configured every probe is lost and every port answers conservatively`() {
        val p = MacPlatform(helperTransport = null, env = tempEnv(), options = MacOptions(null), cpu = idleCpu)
        assertEquals(xyz.mdhv.asom.desktop.PowerSource.UNKNOWN, p.power().read().source)
        assertEquals(2, p.thermal().read().band)
        assertTrue(p.presenceVerdict().present)
        assertNotNull(p.gpuContention(), "a helper that cannot be reached does not decide that the GPU counter is absent")
        assertNull(p.gpuContention()!!.sample())
        val e = assertFailsWith<xyz.mdhv.asom.desktop.mac.helper.HelperLostException> { p.client.hello() }
        assertTrue("asom.mac.helper" in e.message!! && "no PATH lookup" in e.message!!)
        // the runtime never serves on top of it
        val clock = FakeClock(1_000_000)
        val rt = NodeRuntime(p, HostMode.USER, NodeConfig(), clock)
        rt.enableLending()
        repeat(700) { clock.now += 2_000; rt.tick() }
        assertEquals(LenderState.ARMED, rt.fsm.state, "23 minutes with a lost helper: never SERVING")
    }

    @Test
    fun `a serving node whose helper dies leaves SERVING at the next evaluation`() {
        val h = FakeHelper(handler = { r ->
            if (r.op == "presence.get") Reply.Success("presence.get", r.id, Fields.of("hidIdleMs" to HValue.I(9_000_000), "screenLocked" to HValue.B(false), "consoleUserIsSelf" to HValue.B(true))) else null
        })
        val p = platform(h)
        val clock = FakeClock(2_000_000)
        val rt = NodeRuntime(p, HostMode.USER, NodeConfig(), clock)
        rt.enableLending()
        clock.now += 2_000
        rt.tick()
        assertEquals(LenderState.SERVING, rt.fsm.state)
        h.lost = "the helper died"
        clock.now += 2_000
        rt.tick()
        assertEquals(LenderState.DRAINING, rt.fsm.state)
    }

    @Test
    fun `the helper comes only from the system property, absolute, never from the environment or PATH`() {
        val key = MacPlatform.HELPER_PROPERTY
        val old = System.getProperty(key)
        try {
            System.clearProperty(key)
            assertNull(MacPlatform.defaultHelperTransport())
            System.setProperty(key, "asom-mac-helper")
            assertNull(MacPlatform.defaultHelperTransport(), "a bare name would be a PATH lookup")
            System.setProperty(key, "./helper")
            assertNull(MacPlatform.defaultHelperTransport())
            System.setProperty(key, "/nonexistent/asom-mac-helper")
            val t = MacPlatform.defaultHelperTransport()
            assertIs<HelperProcess>(t)
            assertEquals(HelperState.NOT_STARTED, t.state, "configuring the helper does not start it")
            t.close()
        } finally {
            if (old == null) System.clearProperty(key) else System.setProperty(key, old)
        }
    }

    // ---- H3 ------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `no secret, key material or path reaches any output stream from the macOS entry points`() {
        val secrets = listOf("asom-dev-token-7f3c9a1e5b2d4086", "sk-live-SECRETSECRETSECRET1234", "or-key-ZZZZ-9876-ZZZZ", "hunter2-passphrase-xyzzy")
        val vars = mapOf("ASOM_DEV_TOKEN" to secrets[0], "ASOM_KEY_OPENROUTER" to secrets[1], "OPENAI_API_KEY" to secrets[2], "ASOM_PASSPHRASE" to secrets[3])
        val realOut = ByteArrayOutputStream(); val realErr = ByteArrayOutputStream()
        val oldOut = System.out; val oldErr = System.err
        System.setOut(PrintStream(realOut, true, Charsets.UTF_8)); System.setErr(PrintStream(realErr, true, Charsets.UTF_8))
        val cap = Captured()
        val blobs = ArrayList<String>()
        try {
            val env = cap.env("alice", vars)
            val h = FakeHelper()
            val p = platform(h)
            val lookup = HostLookup.Found(p)
            NodeMain.run(listOf("--foreground"), env, lookup) { it.enableLending(); it.tick(); it.shutdown() }
            NodeMain.run(listOf("--mode=selftest"), env, lookup) {}
            p.doctor().render(env.out)
            // make an identity: its blob and public key must not be printed anywhere either
            val paths = p.paths(HostMode.FOREGROUND)
            Files.createDirectories(paths.identityDir)
            java.nio.file.Files.setPosixFilePermissions(paths.identityDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            val me: Int? = runCatching { Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int }.getOrNull()
            val sel = MacNikStore(paths, p.client, null) { me }.createAtFirstMeshEnable(KeyTierRequest.AUTO)
            assertEquals(xyz.mdhv.asom.desktop.mac.keys.NikTier.T2_SECURE_ENCLAVE, sel.tier)
            val blob = Files.readAllBytes(paths.identityDir.resolve("nik.se"))
            blobs += java.util.Base64.getEncoder().encodeToString(blob.copyOfRange(20, 60))
            blobs += java.util.Base64.getEncoder().encodeToString(sel.key.spki())
        } finally {
            System.setOut(oldOut); System.setErr(oldErr)
        }
        val all = cap.outText + cap.errText + realOut.toString(Charsets.UTF_8) + realErr.toString(Charsets.UTF_8)
        assertTrue(all.isNotBlank(), "non-vacuity: the entry points did print")
        for (s in secrets) assertFalse(s in all, "secret leaked to an output stream: $s")
        assertEquals(2, blobs.size)
        for (b in blobs) assertFalse(b.take(24) in all, "key material leaked to an output stream")
        assertFalse(Regex("(?i)bearer\\s+\\S").containsMatchIn(all))
        assertFalse("PRIVATE KEY" in all)
        assertEquals(0, realOut.size() + realErr.size(), "nothing may bypass the injected streams and write to System.out or System.err")
    }
}
