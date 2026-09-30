package xyz.mdhv.asom.desktop.win

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
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
import xyz.mdhv.asom.desktop.ExitCodes
import xyz.mdhv.asom.desktop.HostFinder
import xyz.mdhv.asom.desktop.HostLookup
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.ListenerGateDecision
import xyz.mdhv.asom.desktop.NodeEnv
import xyz.mdhv.asom.desktop.NodeMain
import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.PartiallyImplemented
import xyz.mdhv.asom.desktop.control.CallerIdentity
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.AclRight
import xyz.mdhv.asom.desktop.win.acl.AclSnapshot
import xyz.mdhv.asom.desktop.win.acl.Ace
import xyz.mdhv.asom.desktop.win.acl.ServiceSid
import xyz.mdhv.asom.desktop.win.acl.WellKnownSid
import xyz.mdhv.asom.desktop.win.api.MutexResult
import xyz.mdhv.asom.desktop.win.api.WinApiUnavailableException
import xyz.mdhv.asom.desktop.win.ctl.WinControlSocket
import xyz.mdhv.asom.desktop.win.doctor.DoctorStatus
import xyz.mdhv.asom.desktop.win.doctor.LidAction
import xyz.mdhv.asom.desktop.win.fakes.Captured
import xyz.mdhv.asom.desktop.win.fakes.FakeAcl
import xyz.mdhv.asom.desktop.win.fakes.FakeClock
import xyz.mdhv.asom.desktop.win.fakes.FakeFiles
import xyz.mdhv.asom.desktop.win.fakes.FakeMutex
import xyz.mdhv.asom.desktop.win.fakes.FakeNative
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.OWNER_SID
import xyz.mdhv.asom.desktop.win.fakes.fakeEnv
import xyz.mdhv.asom.desktop.win.jna.JnaSystemPowerStatus
import xyz.mdhv.asom.desktop.win.keys.KeyTierRequest
import xyz.mdhv.asom.desktop.win.keys.WinNikStore
import xyz.mdhv.asom.desktop.win.net.GateResult
import xyz.mdhv.asom.desktop.win.service.ServiceHost

/** The Windows host as a whole: the seam, the control socket's ACL policy, the service entry, the doctor, and stdout hygiene (H3). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostTest {
    private val laws = LawCounter(
        listOf(
            "seam-paths", "seam-refuses-system-account", "seam-gate-closed-by-default", "seam-no-listener", "selftest-no-failures",
            "ctl-identity", "ctl-precheck", "ctl-start-not-yet-implemented", "service-lifecycle", "service-single-identity",
            "main-mutex", "doctor-lines", "doctor-tailscale", "doctor-lid", "stdout-no-secrets", "unavailable-off-windows",
        ),
    )

    private fun platform(native: FakeNative = FakeNative(), user: String = "alice", clock: FakeClock = FakeClock()) =
        WinPlatform(fakeEnv(userName = user), native.bundle(), WinOptions(), clock)

    // ---- the seam ---------------------------------------------------------------------------------------------------

    @Test
    fun `the ServiceLoader finds exactly the Windows host on this classpath`() {
        val lookup = HostFinder.find()
        assertTrue(lookup is HostLookup.Found && lookup.platform is WinPlatform && lookup.platform.id == "windows", lookup.toString())
    }

    @Test
    fun `paths follow the mode and the node refuses to run as SYSTEM or a machine account`() {
        val p = platform()
        assertEquals("C:\\Users\\alice\\AppData\\Local\\asom\\run\\ctl.sock", p.paths(HostMode.USER).controlSocket.toString().let { if ('/' in it) it.replace('/', '\\') else it })
        assertEquals(HostMode.SYSTEM, p.paths(HostMode.SYSTEM).mode)
        assertEquals(HostMode.FOREGROUND, p.paths(HostMode.FOREGROUND).mode)
        for (u in listOf("SYSTEM", "system", "WORKSTATION$")) {
            for (m in HostMode.entries) assertFailsWith<HostRefusedException>("$u/$m") { platform(user = u).paths(m) }
            laws.hit("seam-refuses-system-account")
        }
        laws.hit("seam-paths")
        // an elevated Administrator is not refused (the hosted CI runner is one)
        assertEquals(HostMode.USER, platform(user = "runneradmin").paths(HostMode.USER).mode)
    }

    @Test
    fun `the listener gate is closed on a fresh node and nothing listens`() {
        val native = FakeNative()
        val p = platform(native)
        assertEquals(ListenerGateDecision.CLOSED_UNTIL_CONSENT, p.listenerGate().decision())
        assertEquals(0, native.runner.calls.size, "with no interface selected the firewall is not even read")
        laws.hit("seam-gate-closed-by-default")
        // the control socket does not bind
        val socket = p.controlSocket(p.paths(HostMode.USER))
        assertFailsWith<NotYetImplementedException> { socket.start { _, _ -> error("unreachable") } }
        laws.hit("seam-no-listener")
    }

    @Test
    fun `constructing the host on a machine without Windows touches no Windows API, and the first real call says so`() {
        val p = WinPlatform() // real ports, no Windows here
        assertEquals("windows", p.id)
        val e = assertFailsWith<WinApiUnavailableException> { JnaSystemPowerStatus().read() }
        assertContains(e.message.orEmpty(), "kernel32")
        // and the seam degrades: an unreadable power status is UNKNOWN, not a crash
        assertEquals(xyz.mdhv.asom.desktop.PowerSource.UNKNOWN, p.power().read().source)
        laws.hit("unavailable-off-windows")
    }

    @Test
    fun `asom-node selftest through the shared NodeMain runs every port of the host over fakes with no failure`() {
        val cap = Captured()
        val code = NodeMain.run(listOf("--mode=selftest"), cap.env(), HostLookup.Found(platform())) {}
        assertEquals(ExitCodes.OK, code, cap.outText + cap.errText)
        assertContains(cap.outText, "host windows")
        assertContains(cap.outText, "0 failed")
        assertContains(cap.outText, "control-socket bind (AF_UNIX): W5 / D-v2 (D25, D23)")
        assertTrue("[FAIL]" !in cap.outText, cap.outText)
        laws.hit("selftest-no-failures")
    }

    // ---- control socket ----------------------------------------------------------------------------------------------

    @Test
    fun `the control socket carries the SID identity, verifies the directory ACL, and does not bind`() {
        assertEquals(CallerIdentity("local-sid:$OWNER_SID(acl)"), WinControlSocket.callerIdentity(OWNER_SID))
        laws.hit("ctl-identity")
        val svc = ServiceSid.of("asom")
        val user = WinControlSocket.runDirPlan(HostMode.USER, OWNER_SID)
        assertEquals(emptyList(), WinControlSocket.precheck(user, AclSnapshot(OWNER_SID, user.required)))
        val leak = AclSnapshot(OWNER_SID, user.required + Ace(WellKnownSid.AUTHENTICATED_USERS, true, setOf(AclRight.WRITE)))
        assertTrue(WinControlSocket.precheck(user, leak).isNotEmpty(), "write for Authenticated Users lets anyone connect")
        val service = WinControlSocket.runDirPlan(HostMode.SYSTEM, OWNER_SID)
        assertEquals(emptyList(), WinControlSocket.precheck(service, AclSnapshot(WellKnownSid.ADMINISTRATORS, service.required)))
        assertTrue(service.required.any { it.sid == svc }, "the service SID is on the service run directory")
        assertEquals(setOf(AclRight.WRITE, AclRight.READ), service.required.single { it.sid == OWNER_SID }.rights, "the owner may write, never full control")
        // an unreadable directory fails the check, it does not pass it
        val acl = FakeAcl().also { it.store.clear() }
        val thrower = object : xyz.mdhv.asom.desktop.win.acl.WinAcl {
            override fun snapshot(path: java.nio.file.Path): AclSnapshot = error("access denied")
            override fun replace(path: java.nio.file.Path, aces: List<Ace>) = error("no")
        }
        assertTrue(WinControlSocket.precheck(thrower, Files.createTempDirectory("x"), user).single().startsWith("cannot read"))
        assertNotNull(acl)
        assertNull(WinControlSocket.pathProblem("C:\\a\\run\\ctl.sock"))
        assertNotNull(WinControlSocket.pathProblem("C:\\" + "a".repeat(120)))
        laws.hit("ctl-precheck")
    }

    @Test
    fun `starting the control socket is declared not yet implemented and really throws`() {
        val s = WinControlSocket(java.nio.file.Path.of("x"))
        val nyi = (s as PartiallyImplemented).notYetImplemented
        assertEquals(1, nyi.size)
        assertFailsWith<NotYetImplementedException> { nyi.single().probe() }
        assertEquals("W5 / D-v2 (D25, D23)", nyi.single().track)
        laws.hit("ctl-start-not-yet-implemented")
    }

    // ---- service host ------------------------------------------------------------------------------------------------

    @Test
    fun `the service host starts OFF, holds the identity while it runs, and releases it on stop`() {
        val mutexPort = FakeMutex()
        val host = ServiceHost(platform(), NodeMutex(mutexPort))
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        val t = Thread { try { host.start() } catch (e: Throwable) { failure = e } finally { done.countDown() } }
        t.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (host.runtime == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertNotNull(host.runtime, "start must build the runtime (failure: $failure)")
        assertEquals(LenderState.OFF, host.runtime!!.fsm.state, "a service starts OFF and inert")
        assertTrue("Global\\asom-node" in mutexPort.held)
        // a second node on the machine (user mode) is refused while the service holds the identity
        assertFailsWith<HostRefusedException> { NodeMutex(mutexPort).acquire() }
        laws.hit("service-single-identity")
        assertEquals(1L, done.count, "start blocks until stop")
        host.stop()
        assertTrue(done.await(10, TimeUnit.SECONDS), "start returns after stop")
        assertNull(failure)
        assertEquals(true, host.stopDrainedInTime)
        assertTrue(mutexPort.held.isEmpty(), "stop releases the identity")
        host.stop() // idempotent
        laws.hit("service-lifecycle")
    }

    @Test
    fun `a second service host refuses to start and leaves the first one's identity alone`() {
        val mutexPort = FakeMutex()
        val first = ServiceHost(platform(), NodeMutex(mutexPort))
        val t = Thread { first.start() }
        t.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (first.runtime == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFailsWith<HostRefusedException> { ServiceHost(platform(), NodeMutex(mutexPort)).start() }
        assertTrue("Global\\asom-node" in mutexPort.held)
        first.stop()
        t.join(10_000)
        // paths refused (SYSTEM account) also releases the mutex it took
        val refused = ServiceHost(platform(user = "SYSTEM"), NodeMutex(mutexPort))
        assertFailsWith<HostRefusedException> { refused.start() }
        assertTrue(mutexPort.held.isEmpty(), "a failed start must not keep the identity")
    }

    @Test
    fun `the service entry writes nothing to stdout or stderr`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            val host = ServiceHost(platform(), NodeMutex(FakeMutex()))
            val t = Thread { host.start() }
            t.start()
            val deadline = System.currentTimeMillis() + 10_000
            while (host.runtime == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
            host.stop()
            t.join(10_000)
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        assertEquals(0, out.size())
        assertEquals(0, err.size())
    }

    // ---- WinNodeMain (the user-mode entry) ----------------------------------------------------------------------------

    @Test
    fun `user mode takes the mutex, a second node is refused with a reason and selftest does not take it`() {
        val mutexPort = FakeMutex()
        val cap = Captured()
        var inside = false
        val code = WinNodeMain.run(listOf("--mode=user"), cap.env(), platform(), NodeMutex(mutexPort)) { inside = "Global\\asom-node" in mutexPort.held }
        assertEquals(ExitCodes.OK, code, cap.errText)
        assertTrue(inside, "the mutex is held while the node runs")
        assertTrue(mutexPort.held.isEmpty(), "and released after")
        laws.hit("main-mutex")
        // held by another: refused before the node starts
        mutexPort.held.add("Global\\asom-node")
        val cap2 = Captured()
        var started = false
        val code2 = WinNodeMain.run(listOf("--mode=user"), cap2.env(), platform(), NodeMutex(mutexPort)) { started = true }
        assertEquals(ExitCodes.REFUSED, code2)
        assertFalse(started)
        assertContains(cap2.errText, "another asom node already runs")
        // selftest, version and help never take the mutex
        for (a in listOf("--mode=selftest", "--self-test", "--version", "--help")) {
            val c = Captured()
            assertEquals(ExitCodes.OK, WinNodeMain.run(listOf(a), c.env(), platform(), NodeMutex(mutexPort)) {}, a)
        }
        // a mutex that cannot be created refuses
        val cap3 = Captured()
        assertEquals(ExitCodes.REFUSED, WinNodeMain.run(listOf("--mode=user"), cap3.env(), platform(), NodeMutex(FakeMutex().also { it.result = MutexResult.Failed(5) })) {})
    }

    // ---- doctor -----------------------------------------------------------------------------------------------------

    @Test
    fun `the doctor reports every check with a label as well as a status tag, and states the limits`() {
        val native = FakeNative()
        native.files.files["C:\\ProgramData\\Tailscale\\tailscaled-env.txt"] = "FOO=bar\r\nTS_NO_LOGS_NO_SUPPORT=true\r\n"
        native.runner.output = "Power Setting GUID: 5ca83367-6e45-459f-a27b-476b1d01c936  (Lid close action)\n  Current AC Power Setting Index: 0x00000000\n  Current DC Power Setting Index: 0x00000001\n"
        val cap = Captured()
        val p = platform(native)
        val lines = p.doctor(HostMode.USER).run()
        p.doctor(HostMode.USER).print(PrintStream(cap.out, true, Charsets.UTF_8), lines)
        val text = cap.outText
        val ids = lines.map { it.id }
        for (id in listOf("key-tier", "firewall", "network-profile", "lid-action", "tailscale-log-optout", "tailscale-unattended", "thermal", "gpu-contention", "presence", "keep-awake", "native-loading", "sleep")) {
            assertTrue(id in ids, "doctor must report $id: $ids")
        }
        assertTrue(lines.all { it.status.tag.startsWith("[") && it.status.tag.endsWith("]") && it.text.isNotBlank() }, "every line has a text tag, never colour alone")
        assertTrue(text.lines().filter { it.isNotBlank() }.all { l -> DoctorStatus.entries.any { l.startsWith(it.tag) } })
        laws.hit("doctor-lines")
        assertEquals(DoctorStatus.OK, lines.single { it.id == "tailscale-log-optout" }.status)
        assertEquals(DoctorStatus.OK, lines.single { it.id == "lid-action" }.status, "lid action Do nothing on AC")
        assertContains(lines.single { it.id == "lid-action" }.text, "powercfg /setacvalueindex SCHEME_CURRENT")
        assertContains(text, "no node key yet")
        assertContains(lines.single { it.id == "sleep" }.text, "cannot prevent user-initiated sleep")
        assertEquals(DoctorStatus.WARN, lines.single { it.id == "thermal" }.status)
        assertContains(lines.single { it.id == "thermal" }.text, "NO_THERMAL_SIGNAL")
        assertEquals(DoctorStatus.WARN, lines.single { it.id == "gpu-contention" }.status)
        assertEquals(DoctorStatus.WARN, lines.single { it.id == "firewall" }.status)
        assertContains(lines.single { it.id == "presence" }.text, "gamepad")
    }

    @Test
    fun `without the Tailscale opt-out the doctor prints the disclosure sentence exactly`() {
        val expected = "Tailscale on this PC uploads its own logs to Tailscale Inc. unless you add TS_NO_LOGS_NO_SUPPORT=true to C:\\ProgramData\\Tailscale\\tailscaled-env.txt and restart the Tailscale service. asom cannot see or ledger that traffic."
        for (content in listOf(null, "", "TS_NO_LOGS_NO_SUPPORT=false\n", "# TS_NO_LOGS_NO_SUPPORT=true\n", "XTS_NO_LOGS_NO_SUPPORT=true")) {
            val native = FakeNative()
            if (content != null) native.files.files["C:\\ProgramData\\Tailscale\\tailscaled-env.txt"] = content
            val l = platform(native).doctor(HostMode.USER).run().single { it.id == "tailscale-log-optout" }
            assertEquals(DoctorStatus.WARN, l.status, "content <$content>")
            assertEquals(expected, l.text)
            laws.hit("doctor-tailscale")
        }
        val unattended = FakeNative().let { platform(it).doctor(HostMode.SYSTEM).run().single { l -> l.id == "tailscale-unattended" } }
        assertEquals(DoctorStatus.NOT_READ, unattended.status)
        assertContains(unattended.text, "Run unattended")
    }

    @Test
    fun `the lid action is parsed from hex values whatever the console language`() {
        val en = "  Power Setting GUID: 5ca83367-6e45-459f-a27b-476b1d01c936  (Lid close action)\n    Possible Setting Index: 000\n    Current AC Power Setting Index: 0x00000001\n    Current DC Power Setting Index: 0x00000003\n"
        val de = "  GUID der Energieeinstellung: 5ca83367-6e45-459f-a27b-476b1d01c936  (Aktion beim Schliessen)\n    Aktueller Netzbetrieb-Einstellungsindex: 0x00000000\n    Aktueller Akkubetrieb-Einstellungsindex: 0x00000002\n"
        assertEquals(LidAction.Setting(1, 3), LidAction.parse(en))
        assertEquals(LidAction.Setting(0, 2), LidAction.parse(de))
        assertNull(LidAction.parse("no numbers here"))
        assertNull(LidAction.parse("0x00000001"))
        assertEquals("Do nothing", LidAction.name(0))
        assertEquals("Shut down", LidAction.name(3))
        assertEquals("unknown (9)", LidAction.name(9))
        laws.hit("doctor-lid")
    }

    @Test
    fun `the doctor names each key tier with what it does not guarantee`() {
        val native = FakeNative()
        val p = platform(native)
        val paths = p.paths(HostMode.USER)
        val store = p.nikStore(paths) as WinNikStore
        assertEquals(KeyStorage.UNKNOWN, store.keyStorage)
        store.createAtFirstMeshEnable(KeyTierRequest.AUTO)
        val line = p.doctor(HostMode.USER).run().single { it.id == "key-tier" }
        assertEquals(DoctorStatus.OK, line.status)
        assertContains(line.text, "hardware-backed (self-reported)")
        assertContains(line.text, "Does NOT guarantee")
        assertContains(line.text, "attestation: none")
    }

    // ---- H3: no token or row on any stream ------------------------------------------------------------------------------

    @Test
    fun `no secret appears on any stream while the host runs, and nothing bypasses the injected streams`() {
        val secrets = listOf("asom-dev-token-7f3c9a1e5b2d4086", "sk-live-SECRETSECRETSECRET1234", "hunter2-passphrase-xyzzy")
        val vars = mapOf("ASOM_DEV_TOKEN" to secrets[0], "OPENAI_API_KEY" to secrets[1], "ASOM_PASSPHRASE" to secrets[2])
        val realOut = ByteArrayOutputStream()
        val realErr = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(realOut, true, Charsets.UTF_8))
        System.setErr(PrintStream(realErr, true, Charsets.UTF_8))
        val cap = Captured()
        try {
            val env = NodeEnv("alice", vars, PrintStream(cap.out, true, Charsets.UTF_8), PrintStream(cap.err, true, Charsets.UTF_8))
            val native = FakeNative()
            val p = platform(native)
            NodeMain.run(listOf("--mode=selftest"), env, HostLookup.Found(p)) {}
            WinNodeMain.run(listOf("--mode=user"), env, p, NodeMutex(FakeMutex())) { it.enableLending(); it.tick(); it.shutdown() }
            p.doctor(HostMode.USER).print(env.out, p.doctor(HostMode.USER).run())
            p.doctor(HostMode.SYSTEM).print(env.out, p.doctor(HostMode.SYSTEM).run())
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        val all = cap.outText + cap.errText + realOut.toString(Charsets.UTF_8) + realErr.toString(Charsets.UTF_8)
        assertTrue(all.isNotBlank(), "non-vacuity: the entry points did print")
        for (s in secrets) assertFalse(s in all, "secret leaked: $s")
        assertFalse("RouteRecord(" in all)
        assertFalse(Regex("(?i)bearer\\s+\\S").containsMatchIn(all))
        assertEquals(0, realOut.size() + realErr.size(), "nothing may bypass the injected streams")
        laws.hit("stdout-no-secrets")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("host")
}
