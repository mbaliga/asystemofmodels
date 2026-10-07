package xyz.mdhv.asom.desktop.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mdhv.asom.desktop.json.StrictJson

/**
 * Runs the REAL installed launchers (`build/install/asom-node/bin/asom-node` and `.../asom`) in child processes, on the
 * real /proc and /sys of this machine. The node refuses to run as root, so when this test itself runs as root the
 * children are started through `setpriv` as uid 65534; if that is impossible the test FAILS (it never skips).
 * Evidence label: LAB (a container on x86_64 Linux); not a Deck, not a Dell, not a systemd unit.
 */
class LauncherProcessTest {
    private val installDir = Path.of(System.getProperty("asom.installDir") ?: error("asom.installDir not set"))
    private val secrets = listOf("asom-dev-token-0f1e2d3c4b5a6978", "sk-live-LAUNCHERSECRET5566", "ASOMPASS-hunter2-qwerty")
    private val secretEnv = mapOf("ASOM_DEV_TOKEN" to secrets[0], "ASOM_KEY_OPENROUTER" to secrets[1], "ASOM_PASSPHRASE" to secrets[2])

    private fun myUid(): Int =
        File("/proc/self/status").readLines().first { it.startsWith("Uid:") }.removePrefix("Uid:").trim().split(Regex("\\s+"))[0].toInt()

    private class Result(val exit: Int, val out: String, val err: String, val pid: Long)

    private fun command(script: String, args: List<String>): List<String> {
        val sh = installDir.resolve("bin/$script").toString()
        check(File(sh).canExecute()) { "$sh is not installed; run :node:installDist" }
        return if (myUid() == 0) {
            check(File("/usr/bin/setpriv").canExecute()) { "running as root and no setpriv to drop privileges: cannot exercise the launcher (FAIL, not skip)" }
            listOf("/usr/bin/setpriv", "--reuid=65534", "--regid=65534", "--clear-groups", sh) + args
        } else {
            listOf(sh) + args
        }
    }

    private fun processBuilder(script: String, args: List<String>, javaOpts: String? = null): ProcessBuilder {
        val pb = ProcessBuilder(command(script, args))
        val env = pb.environment()
        env.remove("JAVA_TOOL_OPTIONS") // the container's proxy settings are printed by the JVM itself and are not asom output
        env["HOME"] = "/tmp" // the node writes nothing under HOME in this wave; a directory every uid can traverse
        env.putAll(secretEnv)
        if (javaOpts != null) env["JAVA_OPTS"] = javaOpts
        return pb
    }

    private fun run(script: String, args: List<String>, javaOpts: String? = null): Result {
        val pb = processBuilder(script, args, javaOpts)
        val out = Files.createTempFile("asom-out-", ".txt")
        val err = Files.createTempFile("asom-err-", ".txt")
        pb.redirectOutput(out.toFile()).redirectError(err.toFile()).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val p = pb.start()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "launcher did not finish" }
        return Result(p.exitValue(), Files.readString(out), Files.readString(err), p.pid())
    }

    private fun noSecrets(vararg texts: String) {
        val all = texts.joinToString("\n")
        for (s in secrets) assertFalse(s in all, "secret leaked: $s")
        assertFalse("[ledger]" in all)
        assertFalse(Regex("(?i)bearer\\s+\\S").containsMatchIn(all))
    }

    @Test
    fun `the real launcher refuses root in every mode, as an exit code and a sentence`() {
        for (m in listOf("--mode=system", "--mode=user", "--foreground", "--mode=selftest")) {
            val r = run("asom-node", listOf(m), javaOpts = "-Duser.name=root")
            assertEquals(78, r.exit, m)
            assertTrue("refusing to run as root" in r.err, r.err)
            assertEquals("", r.out)
        }
        val cli = run("asom", listOf("status", "--json"), javaOpts = "-Duser.name=root")
        assertEquals(78, cli.exit)
        assertEquals("", cli.out)
    }

    @Test
    fun `asom status --json through the real launcher prints the plan's shape`() {
        val r = run("asom", listOf("status", "--json"))
        assertEquals(0, r.exit, r.err)
        val line = r.out.trim()
        assertTrue(line.startsWith("""{"host":"foreground","fsm":"OFF","listeners":[],"locks":[],"""), line)
        val o = StrictJson.parse(line) as JsonObject
        assertEquals(listOf("host", "fsm", "listeners", "locks"), o.keys.take(4))
        assertEquals(JsonArray(emptyList()), o["listeners"])
        assertEquals("local-snapshot", (o["source"] as JsonPrimitive).content)
        noSecrets(r.out, r.err)
        Report.line("asom status --json (real launcher, this container, LAB): $line")
    }

    @Test
    fun `selftest through the real launcher exits 0 and names the not-yet-implemented members`() {
        val r = run("asom-node", listOf("--mode=selftest"))
        assertEquals(0, r.exit, r.out + r.err)
        assertTrue(r.out.lines().any { it.startsWith("  [not-yet-implemented] control-socket") }, r.out)
        assertTrue(r.out.trim().endsWith("0 failed"), r.out)
        noSecrets(r.out, r.err)
        Report.line("asom-node --mode=selftest (real launcher, LAB):\n" + r.out.trim().lines().joinToString("\n") { "    $it" })
    }

    @Test
    fun `a foreground node starts OFF, holds no listening or bound socket, prints nothing on stdout, and exits 143 on SIGTERM`() {
        val pb = processBuilder("asom-node", listOf("--foreground"))
        val out = Files.createTempFile("asom-out-", ".txt")
        val err = Files.createTempFile("asom-err-", ".txt")
        pb.redirectOutput(out.toFile()).redirectError(err.toFile()).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val p = pb.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (!Files.readString(err).contains("listeners=none")) {
                check(p.isAlive) { "node exited early: " + Files.readString(err) }
                check(System.nanoTime() < deadline) { "no banner within 60 s" }
                Thread.sleep(100)
            }
            // The launcher execs, so p.pid() is the JVM. It must own no TCP or UDP socket, no bound or listening Unix socket.
            // A JDK 17 runtime holds one unconnected, unbound, non-listening AF_UNIX descriptor of its own even in an idle
            // program that does nothing (observed with a bare Sleep.java); that is classified, not hidden.
            val held = File("/proc/${p.pid()}/fd").listFiles().orEmpty()
                .mapNotNull { runCatching { Files.readSymbolicLink(it.toPath()).toString() }.getOrNull() }
                .filter { it.startsWith("socket:[") }.map { it.removePrefix("socket:[").removeSuffix("]") }
            val netTables = listOf("tcp", "tcp6", "udp", "udp6").flatMap { runCatching { File("/proc/net/$it").readLines().drop(1) }.getOrDefault(emptyList()) }
            val unixTable = runCatching { File("/proc/net/unix").readLines().drop(1) }.getOrDefault(emptyList())
            val offenders = ArrayList<String>()
            for (ino in held) {
                if (netTables.any { row -> row.trim().split(Regex("\\s+")).getOrNull(9) == ino }) offenders += "inet socket $ino"
                for (row in unixTable) {
                    val f = row.trim().split(Regex("\\s+"))
                    if (f.getOrNull(6) != ino) continue
                    val listening = (f[3].toLong(16) and 0x10000L) != 0L
                    if (listening || f.size > 7) offenders += "unix socket $ino listening=$listening path=${f.getOrNull(7)}"
                }
            }
            assertTrue(offenders.isEmpty(), "the node holds a listening, bound or inet socket: $offenders")
            val status = run("asom", listOf("status", "--json"))
            assertEquals(0, status.exit)
            Report.line("foreground node pid ${p.pid()}: ${held.size} socket descriptor(s) held, 0 inet, 0 listening, 0 bound unix (LAB, this container)")
        } finally {
            p.destroy()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), "node did not stop on SIGTERM")
        }
        assertEquals(143, p.exitValue())
        assertEquals("", Files.readString(out), "stdout stays empty")
        val errText = Files.readString(err)
        assertTrue("UNSIGNED" in errText && "control-socket=NOT_YET_IMPLEMENTED(DL2)" in errText, errText)
        noSecrets(Files.readString(out), errText)
    }
}
