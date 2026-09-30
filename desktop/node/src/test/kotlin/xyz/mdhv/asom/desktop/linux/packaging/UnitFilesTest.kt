package xyz.mdhv.asom.desktop.linux.packaging

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.linux.LawCounter
import xyz.mdhv.asom.desktop.linux.Report

/**
 * The shipped systemd units, the sysusers.d file and the polkit rule (linux.md 3.2, 10.2). Evidence label: LAB. Real
 * systemd, real logind and a real polkit are `systemd-vm.sh` (CI-ONLY). `ASOM_REQUIRE_SYSTEMD_TOOLS=1` (CI) turns a
 * missing `systemd-analyze` / `systemd-sysusers` into a FAILURE instead of a skip; `ASOM_REQUIRE_NODE=1` does the same for
 * `node` (the polkit rule is evaluated with a mock `polkit` object).
 */
class UnitFilesTest {
    private fun sys() = Pkg.sections(Pkg.systemUnit)
    private fun usr() = Pkg.sections(Pkg.userUnit)
    private fun List<Pair<String, String>>.one(key: String): String {
        val v = filter { it.first == key }
        assertEquals(1, v.size, "$key must appear exactly once, found $v")
        return v.single().second
    }
    private fun List<Pair<String, String>>.has(key: String) = any { it.first == key }

    @Test
    fun `the system unit carries every normative directive of linux md 3_2`() {
        val u = sys()
        assertEquals(setOf("Unit", "Service", "Install"), u.keys)
        val s = u.getValue("Service")
        val expect = linkedMapOf(
            "Type" to "exec", "User" to "asom", "Group" to "asom",
            "ExecStart" to "/opt/asom/current/bin/asom-node --mode=system",
            "StateDirectory" to "asom", "StateDirectoryMode" to "0700", "RuntimeDirectory" to "asom", "RuntimeDirectoryMode" to "0750",
            "UMask" to "0077", "StandardOutput" to "null", "StandardError" to "null", "LimitCORE" to "0", "MemorySwapMax" to "0",
            "Nice" to "10", "CPUWeight" to "20", "IOSchedulingClass" to "idle",
            "NoNewPrivileges" to "yes", "ProtectSystem" to "strict", "ProtectHome" to "yes", "PrivateTmp" to "yes",
            "ProtectKernelTunables" to "yes", "ProtectKernelModules" to "yes", "ProtectControlGroups" to "yes", "ProtectClock" to "yes",
            "RestrictNamespaces" to "yes", "RestrictRealtime" to "yes", "LockPersonality" to "yes",
            "CapabilityBoundingSet" to "", "AmbientCapabilities" to "", "SystemCallArchitectures" to "native",
            "RestrictAddressFamilies" to "AF_UNIX AF_INET AF_INET6 AF_NETLINK",
            "DevicePolicy" to "closed", "DeviceAllow" to "char-drm rw", "SupplementaryGroups" to "render video",
            "TimeoutStopSec" to "35", "Restart" to "on-failure", "RestartSec" to "5",
        )
        for ((k, v) in expect) assertEquals(v, s.one(k), k)
        assertEquals(expect.keys, s.map { it.first }.toSet(), "no directive beyond the normative list")
        assertEquals("asom node (lends compute only when enabled; see `asom status`)", u.getValue("Unit").one("Description"))
        assertEquals("multi-user.target", u.getValue("Install").one("WantedBy"))
        assertTrue(!s.has("MemoryDenyWriteExecute"), "the JIT needs writable-executable memory")
        assertTrue(!s.has("Type") || s.one("Type") != "notify", "Type=exec, not notify (no sd_notify from the JDK)")
        assertTrue(!s.has("Environment") && !s.has("EnvironmentFile"), "no environment can smuggle a token or a JVM flag")
    }

    @Test
    fun `the user unit is the same block minus what a user manager cannot apply, and is not a sandbox`() {
        val u = usr()
        val s = u.getValue("Service")
        val sysKeys = sys().getValue("Service").map { it.first }.toSet()
        for (k in listOf("User", "Group", "StateDirectory", "StateDirectoryMode", "RuntimeDirectory", "RuntimeDirectoryMode", "SupplementaryGroups", "DevicePolicy", "DeviceAllow",
            "ProtectSystem", "ProtectHome", "ProtectKernelTunables", "ProtectKernelModules", "ProtectControlGroups", "ProtectClock", "PrivateTmp", "CapabilityBoundingSet", "AmbientCapabilities")) {
            assertTrue(!s.has(k), "the user unit must not set $k")
        }
        assertTrue(s.map { it.first }.all { it in sysKeys }, "the user unit sets nothing the system unit does not: ${s.map { it.first }.filter { it !in sysKeys }}")
        assertEquals("%h/.local/opt/asom/current/bin/asom-node --mode=user", s.one("ExecStart"))
        for ((k, v) in mapOf("StandardOutput" to "null", "StandardError" to "null", "LimitCORE" to "0", "MemorySwapMax" to "0", "NoNewPrivileges" to "yes", "UMask" to "0077", "Type" to "exec", "TimeoutStopSec" to "35")) {
            assertEquals(v, s.one(k), k)
        }
        assertEquals("default.target", u.getValue("Install").one("WantedBy"))
    }

    @Test
    fun `shipped disabled, the packaging tree enables and starts nothing`() {
        val links = Files.walk(Pkg.linux).use { s -> s.filter { Files.isSymbolicLink(it) }.map { Pkg.linux.relativize(it).toString() }.toList() }
        assertEquals(emptyList(), links, "the packaging tree ships no symlink (a symlink under *.wants would enable a unit)")
        val wants = Files.walk(Pkg.linux).use { s -> s.filter { it.fileName.toString().endsWith(".wants") || it.fileName.toString().endsWith(".preset") || it.toString().contains("/preset") }.toList() }
        assertEquals(emptyList(), wants)
        val installers = Files.walk(Pkg.linux).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sh") && !it.toString().contains("/test/") }.toList() }
        for (f in installers) {
            val t = Files.readString(f)
            assertTrue(!Regex("systemctl\\s+(--user\\s+)?(enable|start|restart|reload-or-restart)|--now").containsMatchIn(t), "$f enables or starts a unit")
        }
    }

    @Test
    fun `sysusers d defines exactly the asom system user with no shell`() {
        val lines = Files.readAllLines(Pkg.sysusers).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(listOf("u asom - \"asom node\" /var/lib/asom"), lines)
        val tool = Pkg.toolOrSkip("systemd-sysusers", "ASOM_REQUIRE_SYSTEMD_TOOLS")
        val root = Files.createTempDirectory("asom-sysusers-")
        Files.createDirectories(root.resolve("etc"))
        val r = Pkg.run(listOf(tool, "--root=$root", Pkg.sysusers.toString()))
        assertEquals(0, r.exit, r.out)
        val passwd = Files.readAllLines(root.resolve("etc/passwd")).single { it.startsWith("asom:") }.split(':')
        val group = Files.readAllLines(root.resolve("etc/group")).single { it.startsWith("asom:") }.split(':')
        assertEquals("/var/lib/asom", passwd[5])
        assertTrue(passwd[6].endsWith("/nologin"), "login shell must be nologin, was ${passwd[6]}")
        assertTrue(passwd[2].toInt() in 1..999, "system uid range, was ${passwd[2]}")
        assertEquals(passwd[3], group[2], "primary group asom has the user's gid")
        Report.line("sysusers.d: `systemd-sysusers --root=<tmp>` created ${passwd.joinToString(":")} (LAB, synthetic root)")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // systemd-analyze verify
    // ---------------------------------------------------------------------------------------------------------------

    /** A synthetic root holding the host's own units (so After=network-online.target resolves), a stub node and passwd/group. */
    private fun syntheticRoot(unitText: String, withBinary: Boolean = true): Path {
        val root = Files.createTempDirectory("asom-sdroot-")
        val unitDir = Files.createDirectories(root.resolve("usr/lib/systemd/system"))
        val cp = Pkg.run(listOf("cp", "-a", "/usr/lib/systemd/system/.", unitDir.toString()))
        assertEquals(0, cp.exit, cp.out)
        Files.writeString(unitDir.resolve("asom.service"), unitText)
        if (withBinary) {
            val bin = Files.createDirectories(root.resolve("opt/asom/current/bin")).resolve("asom-node")
            Files.writeString(bin, "#!/bin/sh\nexit 0\n")
            bin.toFile().setExecutable(true)
        }
        Files.createDirectories(root.resolve("etc"))
        Files.writeString(root.resolve("etc/passwd"), "root:x:0:0:root:/root:/bin/sh\nasom:x:990:990:asom:/var/lib/asom:/usr/sbin/nologin\n")
        Files.writeString(root.resolve("etc/group"), "root:x:0:\nasom:x:990:\nrender:x:105:\nvideo:x:44:\n")
        return root
    }

    private fun verify(root: Path) = Pkg.run(listOf(Pkg.toolOrSkip("systemd-analyze", "ASOM_REQUIRE_SYSTEMD_TOOLS"), "--root=$root", "verify", "/usr/lib/systemd/system/asom.service"))

    @Test
    fun `systemd-analyze verify prints nothing for the system unit, and does print for broken ones`() {
        val laws = LawCounter(listOf("shipped-unit-verifies-clean", "broken-unit-is-caught"))
        val good = Files.readString(Pkg.systemUnit)
        val r = verify(syntheticRoot(good))
        assertEquals("", r.out, "systemd-analyze verify must print nothing")
        assertEquals(0, r.exit)
        laws.hit("shipped-unit-verifies-clean")
        Report.line("systemd-analyze verify asom.service (synthetic root with the host's own units, stub binary): no output, exit 0 (LAB: NOT the CI-ONLY systemd-vm check)")

        // mutation checks: each must be caught, or the "prints nothing" result above would prove nothing
        val broken = mapOf(
            "a value systemd cannot parse" to good.replace("Nice=10", "Nice=banana"),
            "an ExecStart that does not exist" to good.replace("/opt/asom/current/bin/asom-node", "/opt/asom/current/bin/asom-nodex"),
            "an unknown directive" to good.replace("LimitCORE=0", "LimitCORE=0\nStandardOutptu=null"),
            "an unknown section" to good.replace("[Install]", "[Instal]"),
        )
        for ((why, text) in broken) {
            val b = verify(syntheticRoot(text))
            assertTrue(b.out.isNotEmpty() || b.exit != 0, "systemd-analyze verify did NOT catch $why")
            laws.hit("broken-unit-is-caught")
        }
        laws.assertAllExercised("UnitVerify")
    }

    @Test
    fun `systemd-analyze --user verify accepts the user unit apart from the no-system-bus notice of a container`() {
        val tool = Pkg.toolOrSkip("systemd-analyze", "ASOM_REQUIRE_SYSTEMD_TOOLS")
        val home = Files.createTempDirectory("asom-home-")
        val bin = Files.createDirectories(home.resolve(".local/opt/asom/current/bin")).resolve("asom-node")
        Files.writeString(bin, "#!/bin/sh\nexit 0\n"); bin.toFile().setExecutable(true)
        val xdg = Files.createTempDirectory("asom-xdg-")
        Files.setPosixFilePermissions(xdg, PosixFilePermissions.fromString("rwx------"))
        val env = mapOf("HOME" to home.toString(), "XDG_RUNTIME_DIR" to xdg.toString())
        fun run(unit: Path) = Pkg.run(listOf(tool, "--user", "verify", unit.toString()), env)
        fun significant(out: String) = out.lines().filter { it.isNotBlank() && !it.startsWith("Failed to connect to system bus") }
        val ok = run(Pkg.userUnit)
        assertEquals(emptyList(), significant(ok.out), "systemd-analyze --user verify printed: ${ok.out}")
        assertEquals(0, ok.exit)
        val broken = Files.createTempFile("asom-user-broken-", ".service")
        Files.writeString(broken, Files.readString(Pkg.userUnit).replace("Nice=10", "Nice=banana"))
        val renamed = broken.resolveSibling("asom-user.service").also { Files.move(broken, it, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        assertTrue(significant(run(renamed).out).isNotEmpty() || run(renamed).exit != 0, "the mutation was not caught")
        Report.line("systemd-analyze --user verify asom-user.service: nothing beyond the container's 'Failed to connect to system bus' notice, exit 0; a broken copy is caught (LAB)")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The polkit rule
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `the polkit rule is the verbatim LD-6 text - one action, one user, nothing else`() {
        val t = Files.readString(Pkg.polkitRule)
        val actions = Regex("org\\.freedesktop\\.[A-Za-z0-9_.\\-]+").findAll(t).map { it.value }.toSet()
        assertEquals(setOf("org.freedesktop.login1.inhibit-block-sleep"), actions)
        assertEquals(1, Regex("polkit\\.addRule").findAll(t).count())
        assertEquals(1, Regex("polkit\\.Result\\.").findAll(t).count())
        assertTrue("polkit.Result.YES" in t && !Regex("Result\\.(AUTH|NO|NOT_HANDLED)").containsMatchIn(t))
        assertTrue("subject.user == \"asom\"" in t)
        assertTrue(!Regex("subject\\.isInGroup|subject\\.local|subject\\.active|\\|\\|").containsMatchIn(t), "no group, session or alternation widening")
        val code = t.lines().filter { !it.trimStart().startsWith("//") && it.isNotBlank() }.joinToString("\n")
        assertEquals(
            """polkit.addRule(function (action, subject) {
    if (action.id == "org.freedesktop.login1.inhibit-block-sleep" && subject.user == "asom") {
        return polkit.Result.YES;
    }
});""",
            code,
        )
    }

    private fun evaluate(ruleFile: Path, cases: List<Pair<String, String?>>): List<String> {
        val node = Pkg.toolOrSkip("node", "ASOM_REQUIRE_NODE")
        val js = """
            const fs = require('fs');
            const rules = [];
            const polkit = { addRule: (f) => rules.push(f), Result: { YES: 'YES', NO: 'NO', AUTH_SELF: 'AUTH_SELF', NOT_HANDLED: null } };
            new Function('polkit', fs.readFileSync(process.argv[1], 'utf8'))(polkit);
            const cases = JSON.parse(process.argv[2]);
            for (const [action, user] of cases) {
              let r = null;
              for (const f of rules) { const v = f({ id: action }, user === null ? {} : { user }); if (v !== undefined && v !== null) { r = v; break; } }
              console.log(String(r));
            }
        """.trimIndent()
        val casesJson = cases.joinToString(",", "[", "]") { (a, u) -> "[\"$a\",${if (u == null) "null" else "\"$u\""}]" }
        val r = Pkg.run(listOf(node, "-e", js, ruleFile.toString(), casesJson))
        assertEquals(0, r.exit, r.out)
        return r.out.trim().lines()
    }

    @Test
    fun `evaluated with a mock polkit, the rule grants block-sleep to user asom and to nobody and nothing else`() {
        val block = "org.freedesktop.login1.inhibit-block-sleep"
        val cases = listOf(
            block to "asom", block to "alice", block to "root", block to "asom2", block to "ASOM", block to " asom", block to "", block to null,
            "org.freedesktop.login1.inhibit-delay-sleep" to "asom", "org.freedesktop.login1.inhibit-block-shutdown" to "asom",
            "org.freedesktop.login1.inhibit-block-idle" to "asom", "org.freedesktop.login1.suspend" to "asom",
            "org.freedesktop.login1.power-off" to "asom", "org.freedesktop.login1.inhibit-block-sleep-x" to "asom", "org.freedesktop.systemd1.manage-units" to "asom",
        )
        val out = evaluate(Pkg.polkitRule, cases)
        assertEquals(cases.size, out.size)
        val expected = cases.map { (a, u) -> if (a == block && u == "asom") "YES" else "null" }
        assertEquals(expected, out, "decision per (action, user)")
        assertEquals(1, out.count { it == "YES" }, "non-vacuity: exactly one granting case, and it was exercised")
        // mutation check: a widened rule (any user) MUST make the matrix above fail
        val widened = Files.createTempFile("asom-widened-", ".rules")
        Files.writeString(widened, Files.readString(Pkg.polkitRule).replace(" && subject.user == \"asom\"", ""))
        val wide = evaluate(widened, cases)
        assertNotEquals(expected, wide, "the matrix did not notice a rule that grants block-sleep to every user")
        val other = Files.createTempFile("asom-other-", ".rules")
        Files.writeString(other, Files.readString(Pkg.polkitRule).replace("inhibit-block-sleep", "inhibit-block-shutdown"))
        assertNotEquals(expected, evaluate(other, cases), "the matrix did not notice a rule for a different action")
        Report.line("polkit rule evaluated with a mock polkit object under node: ${cases.size} cases, exactly 1 grant (asom, block-sleep); widened and re-targeted mutants caught (LAB; the real polkitd is systemd-vm.sh, CI-ONLY)")
    }

    @Test
    fun `the packaging files are not executable when they should not be, and the scripts are`() {
        for (f in listOf(Pkg.systemUnit, Pkg.userUnit, Pkg.sysusers, Pkg.polkitRule)) assertTrue(!f.toFile().canExecute(), "$f must not be executable")
        for (f in listOf(Pkg.journalHygiene, Pkg.systemdVm)) assertTrue(File(f.toString()).canExecute(), "$f must be executable")
    }
}
