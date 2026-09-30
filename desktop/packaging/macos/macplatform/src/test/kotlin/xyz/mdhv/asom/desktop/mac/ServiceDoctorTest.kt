package xyz.mdhv.asom.desktop.mac

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.mac.doctor.DoctorLevel
import xyz.mdhv.asom.desktop.mac.doctor.DoctorParsers
import xyz.mdhv.asom.desktop.mac.doctor.MacDoctor
import xyz.mdhv.asom.desktop.mac.doctor.MacDoctorInputs
import xyz.mdhv.asom.desktop.mac.exec.ProcessRunner
import xyz.mdhv.asom.desktop.mac.exec.RunResult
import xyz.mdhv.asom.desktop.mac.exec.SystemProcessRunner
import xyz.mdhv.asom.desktop.mac.fakes.FakeHelper
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.svc.ServiceKind
import xyz.mdhv.asom.desktop.mac.svc.ServiceRegistration
import xyz.mdhv.asom.desktop.mac.svc.ServiceStatus

/**
 * SYNTHETIC tool output: the strings below are hand-written listings in the shape the tools are documented to print, NOT captures
 * from a Mac. They are UNVERIFIED against a real macOS until the owner-device run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceDoctorTest {
    private val laws = LawCounter(
        listOf(
            "svc-status-guidance", "svc-daemon-refused", "svc-enable-explicit", "parse-firewall", "parse-power", "parse-lid", "parse-tailscale",
            "runner-allowlist", "doctor-lines", "doctor-fail-closed", "doctor-no-colour-words", "doctor-read-only",
        ),
    )

    @AfterAll
    fun report() = laws.assertAllExercised("service-doctor")

    // ---- service registration ---------------------------------------------------------------------------------------------------

    @Test
    fun `every status has guidance in words, and requiresApproval names the Login Items pane`() {
        for (s in ServiceStatus.entries) {
            val r = ServiceRegistration.report(s)
            assertEquals(s, r.status)
            assertTrue(r.guidance.isNotEmpty() && r.guidance.all { it.isNotBlank() })
        }
        val need = ServiceRegistration.report(ServiceStatus.REQUIRES_APPROVAL).guidance.joinToString(" ")
        assertTrue("Login Items" in need && "allow ASOM" in need)
        assertTrue("Lending stays OFF" in ServiceRegistration.report(ServiceStatus.ENABLED).guidance.joinToString(" "))
        assertTrue("development build" in ServiceRegistration.report(ServiceStatus.NOT_FOUND).guidance.joinToString(" "))
        laws.hit("svc-status-guidance", ServiceStatus.entries.size.toLong())
    }

    @Test
    fun `registration happens only when asked, through the helper, for the agent only`() {
        val h = FakeHelper()
        val svc = ServiceRegistration(HelperClient(h))
        assertTrue(h.calls.isEmpty(), "constructing the registration calls nothing")
        assertEquals(ServiceStatus.NOT_REGISTERED, svc.status().status)
        assertEquals(listOf("svc.status"), h.calls.toList(), "reading the status registers nothing")
        assertEquals(ServiceStatus.REQUIRES_APPROVAL, svc.enable().status)
        assertEquals(1, h.opCount("svc.register"))
        assertEquals(ServiceStatus.NOT_REGISTERED, svc.disable().status)
        laws.hit("svc-enable-explicit")
        h.calls.clear()
        val e = assertFailsWith<NotYetImplementedException> { svc.enable(ServiceKind.DAEMON) }
        assertTrue("MC9" in e.message!!)
        assertFailsWith<NotYetImplementedException> { svc.status(ServiceKind.DAEMON) }
        assertFailsWith<NotYetImplementedException> { svc.disable(ServiceKind.DAEMON) }
        assertTrue(h.calls.isEmpty(), "the daemon is refused before anything is asked of the helper")
        laws.hit("svc-daemon-refused", 3)
    }

    // ---- parsers ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the tool output parsers recognise what they know and say unknown otherwise`() {
        assertEquals(DoctorParsers.Firewall.ENABLED, DoctorParsers.firewallGlobalState("Firewall is enabled. (State = 1)"))
        assertEquals(DoctorParsers.Firewall.DISABLED, DoctorParsers.firewallGlobalState("Firewall is disabled. (State = 0)\n"))
        assertEquals(DoctorParsers.Firewall.UNKNOWN, DoctorParsers.firewallGlobalState("Le pare-feu est activé"))
        assertEquals(DoctorParsers.Firewall.UNKNOWN, DoctorParsers.firewallGlobalState(""))
        laws.hit("parse-firewall", 4)
        assertEquals("AC Power", DoctorParsers.powerSource("Now drawing from 'AC Power'\n -InternalBattery-0 (id=123)\t100%; charged"))
        assertEquals("Battery Power", DoctorParsers.powerSource("Now drawing from 'Battery Power'"))
        assertNull(DoctorParsers.powerSource("nothing here"))
        assertTrue(DoctorParsers.asomAssertionHeld("   pid 123(asom-mac-helper): [0x0001] 00:01:00 PreventUserIdleSystemSleep named: \"asom: lending compute to your paired devices\""))
        assertFalse(DoctorParsers.asomAssertionHeld("Assertion status system-wide:\n   PreventUserIdleSystemSleep     0"))
        laws.hit("parse-power", 5)
        assertEquals(true, DoctorParsers.lidClosed("    | |   \"AppleClamshellState\" = Yes"))
        assertEquals(false, DoctorParsers.lidClosed("    | |   \"AppleClamshellState\" = No"))
        assertNull(DoctorParsers.lidClosed("(no such key)"))
        laws.hit("parse-lid", 3)
        val ps = DoctorParsers.tailscaledProcesses(
            "412 /usr/local/bin/tailscaled --state=/var/lib/tailscale/tailscaled.state --no-logs-no-support\n" +
                "977 /opt/homebrew/bin/tailscaled --socket=/tmp/ts.sock\n" +
                "1500 vim tailscaled.conf\n" + "\n",
        )
        assertEquals(listOf(true, false), ps.map { it.noLogs })
        assertEquals(2, ps.size, "a text editor with tailscaled in an argument is not a tailscaled")
        laws.hit("parse-tailscale", 3)
    }

    // ---- the runner ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `only four read-only tools with their read-only arguments may run`() {
        val r = SystemProcessRunner()
        val allowed = listOf(
            SystemProcessRunner.PMSET to listOf("-g", "assertions"), SystemProcessRunner.PMSET to listOf("-g", "batt"),
            SystemProcessRunner.SOCKETFILTERFW to listOf("--getglobalstate"), SystemProcessRunner.SOCKETFILTERFW to listOf("--getappblocked", "/Applications/ASOM.app"),
            SystemProcessRunner.IOREG to listOf("-r", "-k", "AppleClamshellState", "-d", "4"), SystemProcessRunner.PGREP to listOf("-fl", "tailscaled"),
        )
        for ((exe, args) in allowed) assertNull(r.refusal(exe, args), "$exe $args")
        val refused = listOf(
            "pmset" to ("pmset" to listOf("-g", "assertions")), "relative" to ("./pmset" to listOf("-g", "assertions")),
            "pmset set" to (SystemProcessRunner.PMSET to listOf("-a", "sleep", "0")), "pmset disablesleep" to (SystemProcessRunner.PMSET to listOf("disablesleep", "1")),
            "pmset extra arg" to (SystemProcessRunner.PMSET to listOf("-g", "assertions", "-a")),
            "socketfilterfw set" to (SystemProcessRunner.SOCKETFILTERFW to listOf("--setglobalstate", "off")),
            "socketfilterfw add" to (SystemProcessRunner.SOCKETFILTERFW to listOf("--add", "/x")),
            "getappblocked relative" to (SystemProcessRunner.SOCKETFILTERFW to listOf("--getappblocked", "x")),
            "getappblocked control char" to (SystemProcessRunner.SOCKETFILTERFW to listOf("--getappblocked", "/x\n")),
            "getappblocked no arg" to (SystemProcessRunner.SOCKETFILTERFW to listOf("--getappblocked")),
            "ioreg other key" to (SystemProcessRunner.IOREG to listOf("-l")), "pgrep other pattern" to (SystemProcessRunner.PGREP to listOf("-fl", "sshd")),
            "sh" to ("/bin/sh" to listOf("-c", "id")), "security" to ("/usr/bin/security" to listOf("find-generic-password", "-w")),
            "launchctl" to ("/bin/launchctl" to listOf("list")), "ps" to ("/bin/ps" to listOf("-axo", "command")), "open" to ("/usr/bin/open" to listOf("x")),
        )
        for ((why, c) in refused) assertTrue(r.refusal(c.first, c.second) != null, "must be refused: $why")
        assertFailsWith<IllegalArgumentException> { r.run("/bin/sh", listOf("-c", "id"), 1_000) }
        laws.hit("runner-allowlist", (allowed.size + refused.size).toLong())
    }

    // ---- the doctor ---------------------------------------------------------------------------------------------------------------

    private class Script(val answers: Map<Pair<String, List<String>>, String>) : ProcessRunner {
        val ran = ArrayList<Pair<String, List<String>>>()
        override fun run(executable: String, args: List<String>, timeoutMs: Long): RunResult {
            ran += executable to args
            val text = answers[executable to args] ?: throw java.io.IOException("no such tool in this test: $executable")
            return RunResult(0, text.toByteArray())
        }
    }

    private val goodAnswers = mapOf(
        (SystemProcessRunner.SOCKETFILTERFW to listOf("--getglobalstate")) to "Firewall is enabled. (State = 1)",
        (SystemProcessRunner.PMSET to listOf("-g", "assertions")) to "   pid 1(x): PreventUserIdleSystemSleep named: \"asom: lending compute to your paired devices\"",
        (SystemProcessRunner.PMSET to listOf("-g", "batt")) to "Now drawing from 'AC Power'",
        (SystemProcessRunner.IOREG to listOf("-r", "-k", "AppleClamshellState", "-d", "4")) to "\"AppleClamshellState\" = No",
        (SystemProcessRunner.PGREP to listOf("-fl", "tailscaled")) to "412 /usr/local/bin/tailscaled --no-logs-no-support",
    )

    private fun inputs(
        runner: ProcessRunner = Script(goodAnswers), signed: Boolean = false, helper: HelperClient? = HelperClient(FakeHelper()),
        exists: (String) -> Boolean = { false }, ks: KeyStorage = KeyStorage.UNKNOWN, installed: String? = "0.0.0-scaffold", service: String? = "notRegistered",
        gpu: Boolean? = true, lock: Boolean? = false,
    ) = MacDoctorInputs(
        client = helper, helperLossReason = { null }, keyStorage = { ks },
        layout = MacPaths.layout(if (signed) MacMode.AGENT else MacMode.DEV, MacEnv("me", "/Users/me", emptyMap(), { "/tmp" }, { 501 }), if (signed) "ABCDE12345" else null),
        teamId = if (signed) "ABCDE12345" else null, runner = runner, gpuCounterExists = { gpu }, appPath = null, exists = exists,
        runningVersion = "0.0.0-scaffold", installedVersion = { installed }, serviceStatus = { service }, lockHeldByOther = { lock },
    )

    private fun render(d: MacDoctor): String {
        val b = ByteArrayOutputStream()
        d.render(PrintStream(b, true, Charsets.UTF_8))
        return b.toString(Charsets.UTF_8)
    }

    @Test
    fun `the doctor reports every check in words and never invents a value`() {
        val lines = MacDoctor(inputs()).run()
        val checks = lines.map { it.check }.toSet()
        for (c in listOf("helper", "key-tier", "container", "local-network", "firewall", "pmset-assertion", "power-source", "lid", "tailscale", "gpu-contention", "login-item", "node-lock", "version", "homebrew", "tray", "daemon-mode")) {
            assertTrue(c in checks, "missing check: $c")
        }
        val text = lines.joinToString("\n") { "${it.check}: ${it.detail}" }
        assertTrue("UNSIGNED BUILD: container protection absent" in text)
        assertTrue("asom holds its keep-awake assertion" in text)
        assertTrue("--no-logs-no-support is on the tailscaled command line" in text)
        assertTrue("Secure Enclave available" in text)
        assertTrue("local-network-denied" in text && "Privacy & Security" in text)
        assertTrue("HOMEBREW_NO_ANALYTICS" in text)
        assertEquals(2, lines.count { it.level == DoctorLevel.NOT_YET })
        laws.hit("doctor-lines")
    }

    @Test
    fun `key tier lines carry the tier's limits, or say there is no identity yet`() {
        val none = MacDoctor(inputs(ks = KeyStorage.UNKNOWN)).run().filter { it.check == "key-tier" }.joinToString("\n") { it.detail }
        assertTrue("no node identity yet" in none && "rejected" in none)
        val t2 = MacDoctor(inputs(ks = KeyStorage.SECURE_ENCLAVE)).run().filter { it.check == "key-tier" }.joinToString("\n") { it.detail }
        assertTrue("T2 secure-enclave (hardware-backed (self-reported))" in t2 && "does NOT guarantee" in t2 && "blob is not bound" in t2)
        val t0 = MacDoctor(inputs(ks = KeyStorage.FILE)).run().filter { it.check == "key-tier" }.joinToString("\n") { it.detail }
        assertTrue("T0 file" in t0 && "copied file is the node" in t0)
    }

    @Test
    fun `a Tailscale GUI app without the opt-out is a warning, an unrecognised tool output is unknown, a broken tool is unknown`() {
        val gui = MacDoctor(inputs(runner = Script(goodAnswers + mapOf((SystemProcessRunner.PGREP to listOf("-fl", "tailscaled")) to "")), exists = { it == "/Applications/Tailscale.app" })).run()
        assertTrue(gui.any { it.check == "tailscale" && it.level == DoctorLevel.WARN && "no documented opt-out" in it.detail && "Standalone" in it.detail })
        val mas = MacDoctor(inputs(runner = Script(goodAnswers + mapOf((SystemProcessRunner.PGREP to listOf("-fl", "tailscaled")) to "")), exists = { it == "/Applications/Tailscale.app" || it.endsWith("_MASReceipt/receipt") })).run()
        assertTrue(mas.any { it.check == "tailscale" && "Mac App Store" in it.detail })
        val plainTs = MacDoctor(inputs(runner = Script(goodAnswers + mapOf((SystemProcessRunner.PGREP to listOf("-fl", "tailscaled")) to "412 /usr/local/bin/tailscaled")))).run()
        assertTrue(plainTs.any { it.check == "tailscale" && it.level == DoctorLevel.WARN && "NOT on the tailscaled command line" in it.detail })
        val none = MacDoctor(inputs(runner = Script(goodAnswers + mapOf((SystemProcessRunner.PGREP to listOf("-fl", "tailscaled")) to "")))).run()
        assertTrue(none.any { it.check == "tailscale" && "no Tailscale client found" in it.detail })
        val foreign = MacDoctor(inputs(runner = Script(goodAnswers + mapOf((SystemProcessRunner.SOCKETFILTERFW to listOf("--getglobalstate")) to "Le pare-feu est activé")))).run()
        assertTrue(foreign.any { it.check == "firewall" && it.level == DoctorLevel.UNKNOWN })
        val broken = MacDoctor(inputs(runner = Script(emptyMap()))).run()
        for (c in listOf("firewall", "pmset-assertion", "power-source", "lid", "tailscale")) assertTrue(broken.any { it.check == c && it.level == DoctorLevel.UNKNOWN }, c)
        laws.hit("doctor-fail-closed", 3)
    }

    @Test
    fun `a signed build shows the container, an unreachable helper and a version skew are warnings`() {
        val signed = MacDoctor(inputs(signed = true)).run()
        assertTrue(signed.any { it.check == "container" && "Team-ID group container" in it.detail && "AM08" in it.detail })
        assertFalse(signed.any { "UNSIGNED BUILD" in it.detail })
        val lost = FakeHelper().also { it.lost = "gone" }
        val d = MacDoctor(inputs(helper = HelperClient(lost), installed = "0.0.1")).run()
        assertTrue(d.any { it.check == "helper" && it.level == DoctorLevel.WARN && "not reachable" in it.detail })
        assertTrue(d.any { it.check == "version" && it.level == DoctorLevel.WARN && "asom node restart" in it.detail })
        val ok = MacDoctor(inputs(gpu = false, lock = true, service = "requiresApproval")).run()
        assertTrue(ok.any { it.check == "gpu-contention" && "OFF" in it.detail })
        assertTrue(ok.any { it.check == "node-lock" && "another-user-node" in it.detail })
        assertTrue(ok.any { it.check == "login-item" && it.level == DoctorLevel.WARN })
    }

    @Test
    fun `the rendering uses words for severity, no colour, and nothing that looks like a secret`() {
        val out = render(MacDoctor(inputs(ks = KeyStorage.SECURE_ENCLAVE)))
        assertTrue(out.startsWith("asom doctor (macOS)"))
        assertFalse(out.contains('\u001b'), "no ANSI colour")
        for (tag in DoctorLevel.entries.map { "[" + it.tag + "]" }) if (tag in listOf("[ok]", "[info]", "[warn]", "[not-yet-implemented]")) assertTrue(tag in out, tag)
        assertTrue(Regex("(?m)^doctor: \\d+ ok, \\d+ info, \\d+ warn, \\d+ unknown, \\d+ not-yet-implemented$").containsMatchIn(out), out.takeLast(200))
        assertFalse(Regex("(?i)(sk-[a-z0-9]{8,}|bearer\\s+\\S|BEGIN (EC )?PRIVATE KEY)").containsMatchIn(out))
        laws.hit("doctor-no-colour-words")
    }

    @Test
    fun `the doctor only ever runs allowlisted read-only commands`() {
        val script = Script(goodAnswers)
        MacDoctor(inputs(runner = script)).run()
        assertTrue(script.ran.isNotEmpty())
        val runner = SystemProcessRunner()
        for ((exe, args) in script.ran) assertNull(runner.refusal(exe, args), "the doctor ran $exe $args")
        laws.hit("doctor-read-only", script.ran.size.toLong())
    }
}
