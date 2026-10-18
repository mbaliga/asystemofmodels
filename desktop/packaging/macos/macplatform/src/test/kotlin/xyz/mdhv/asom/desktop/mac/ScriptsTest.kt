package xyz.mdhv.asom.desktop.mac

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * The shell and Python scripts of the track. The AM14 demo is exercised on Linux against a SYNTHETIC fake of `security`
 * (`src/test/resources/fakes/fake-security.sh`), which shows the demo's control flow and that it can say both yes and no; it says
 * NOTHING about how the real tool behaves, which only the macOS CI run can show.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScriptsTest {
    private val laws = LawCounter(listOf("demo-yes", "demo-no", "demo-cannot-run", "demo-timeout", "demo-touches-only-its-keychain", "stubs-fail", "it-checker", "lanes-selftest") +
        if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) emptyList() else listOf("demo-not-macos"))

    @AfterAll
    fun report() = laws.assertAllExercised("scripts")

    private val macos = File(repoRoot(), "desktop/packaging/macos")
    private val scripts = File(macos, "scripts")
    private val fake = File(macos, "macplatform/src/test/resources/fakes/fake-security.sh")

    private class Out(val code: Int, val text: String)

    private fun run(cmd: List<String>, env: Map<String, String> = emptyMap(), timeoutSec: Long = 120, dir: File = repoRoot()): Out {
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val text = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertTrue(p.waitFor(timeoutSec, TimeUnit.SECONDS), "timed out: $cmd")
        return Out(p.exitValue(), text)
    }

    private fun demo(mode: String, state: File = Files.createTempDirectory("asom-fakesec-").toFile(), extra: Map<String, String> = emptyMap()): Pair<Out, File> {
        val env = mapOf(
            "FAKE_SECURITY_STATE" to state.absolutePath, "FAKE_SECURITY_MODE" to mode, "ASOM_DEMO_SECURITY" to fake.absolutePath,
            "ASOM_DEMO_ALLOW_NON_DARWIN" to "1", "ASOM_DEMO_TIMEOUT" to "2", "ASOM_DEMO_EXTRA_ENV" to "FAKE_SECURITY_STATE=${state.absolutePath} FAKE_SECURITY_MODE=$mode",
        ) + extra
        return run(listOf("bash", File(scripts, "demo-keychain-cli-weakness.sh").absolutePath), env) to state
    }

    @Test
    fun `the AM14 demo prints yes and exits 0 when the ordinary item is readable without a prompt, and shows its control`() {
        val (o, state) = demo("trusting")
        assertEquals(0, o.code, o.text)
        assertTrue("read without prompt: yes" in o.text, o.text)
        assertTrue("control (item stored with -T \"\", trusts no application): read without prompt: no (exit 36)" in o.text, o.text)
        assertTrue("the secret crossed a command line" in o.text)
        assertEquals(0, state.list()!!.size, "the temporary keychain was deleted")
        assertFalse(Regex("asom-am14-demo-value").containsMatchIn(o.text), "the demo value is never printed")
        laws.hit("demo-yes")
        laws.hit("demo-touches-only-its-keychain")
    }

    @Test
    fun `the demo says no and exits 1 when the read needs a prompt, so the assumption can fail loudly`() {
        val (o, _) = demo("prompting")
        assertEquals(1, o.code, o.text)
        assertTrue("read without prompt: no (exit 36)" in o.text, o.text)
        assertTrue("must be revisited" in o.text)
        laws.hit("demo-no")
    }

    @Test
    fun `the demo cannot run, and says so with exit 2, when the keychain cannot be made`() {
        val (o, _) = demo("broken")
        assertEquals(2, o.code, o.text)
        assertTrue("CANNOT RUN" in o.text)
        val (o2, _) = demo("trusting", extra = mapOf("ASOM_DEMO_SECURITY" to "/nonexistent/security"))
        assertEquals(2, o2.code, o2.text)
        laws.hit("demo-cannot-run", 2)
    }

    @Test
    fun `a read that hangs is cut off by the watchdog and counts as no`() {
        // the watchdog is 20 s; this test waits for it once
        val (o, _) = demo("hang")
        assertEquals(1, o.code, o.text)
        assertTrue("read without prompt: no (timed out)" in o.text || "read without prompt: no" in o.text, o.text)
        laws.hit("demo-timeout")
    }

    @Test
    fun `off macOS the demo refuses with exit 3 instead of pretending`() {
        if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) return
        val o = run(listOf("bash", File(scripts, "demo-keychain-cli-weakness.sh").absolutePath))
        assertEquals(3, o.code, o.text)
        assertTrue(o.text.startsWith("NOT RUN"))
        laws.hit("demo-not-macos")
    }

    @Test
    fun `the demo names its temporary keychain in every call and never touches the login keychain or the search list`() {
        val t = File(scripts, "demo-keychain-cli-weakness.sh").readText()
        for (banned in listOf("list-keychains", "default-keychain", "login.keychain", "-D ", "delete-generic-password", "import ", "add-trusted-cert", "sudo ")) {
            assertFalse(banned in t.lines().filter { !it.trimStart().startsWith("#") }.joinToString("\n"), "the demo must not use: $banned")
        }
        val calls = t.lines().filter { !it.trimStart().startsWith("#") && Regex("\"\\\$security\" (add-generic-password|find-generic-password|create-keychain|set-keychain-settings|unlock-keychain|delete-keychain)").containsMatchIn(it) }
        assertTrue(calls.size >= 6, "found only ${calls.size} security calls")
        for (c in calls) assertTrue("\$kc" in c, "a security call does not name the temporary keychain: $c")
        laws.hit("demo-touches-only-its-keychain")
    }

    @Test
    fun `every not-yet-implemented script fails on purpose with exit 3 and says what is missing`() {
        val stubs = scripts.listFiles { f -> f.extension == "sh" }!!.filter { "NOT-YET-IMPLEMENTED" in it.readText() }
        assertTrue(stubs.size >= 12, "expected the twelve stubs, found ${stubs.map { it.name }}")
        for (s in stubs) {
            val o = run(listOf("bash", s.absolutePath))
            assertEquals(3, o.code, "${s.name} must fail on purpose: ${o.text}")
            assertTrue("NOT-YET-IMPLEMENTED: ${s.name}" in o.text, o.text)
            assertTrue(s.readText().lines().first().startsWith("#!"), s.name)
        }
        val real = scripts.listFiles { f -> f.extension == "sh" }!!.filter { "NOT-YET-IMPLEMENTED" !in it.readText() }.map { it.name }.sorted()
        assertEquals(listOf("check-protocol-lanes.sh", "demo-keychain-cli-weakness.sh"), real)
        laws.hit("stubs-fail", stubs.size.toLong())
    }

    @Test
    fun `the integration-result checker demands skipped off macOS and passed on macOS, and refuses a vacuous run`() {
        val dir = Files.createTempDirectory("asom-it-").toFile()
        fun write(name: String, cls: String, body: String) = File(dir, "TEST-$name.xml").writeText("<testsuite>$body</testsuite>".replace("CLS", "xyz.mdhv.asom.desktop.mac.macos.$cls"))
        fun tc(cls: String, n: Int, state: String) = "<testcase name=\"t$n\" classname=\"xyz.mdhv.asom.desktop.mac.macos.$cls\">${when (state) { "skipped" -> "<skipped/>"; "failed" -> "<failure message=\"x\"/>"; else -> "" }}</testcase>"
        val checker = File(scripts, "check_it_results.py").absolutePath
        fun check(mode: String) = run(listOf("python3", checker, "--expect", mode, dir.absolutePath))
        // vacuous
        assertEquals(1, check("skipped").code)
        val classes = listOf("HelperIT", "PowerAssertionIT", "ControlSocketIT", "SecureEnclaveIT")
        write("a", "x", classes.mapIndexed { i, c -> (1..3).joinToString("") { tc(c, i * 10 + it, "skipped") } }.joinToString(""))
        assertEquals(0, check("skipped").code, check("skipped").text)
        assertEquals(1, check("mac").code, "all skipped on macOS is not a pass")
        assertTrue("SKIPPED on macOS (not allowed)" in check("mac").text)
        // macOS: everything ran except the Enclave test, which may skip
        write("a", "x", classes.mapIndexed { i, c -> (1..3).joinToString("") { tc(c, i * 10 + it, if (c == "SecureEnclaveIT") "skipped" else "passed") } }.joinToString(""))
        assertEquals(0, check("mac").code, check("mac").text)
        assertEquals(1, check("skipped").code, "a passed IT off macOS means the OS guard failed")
        // a failure is never acceptable
        write("a", "x", classes.mapIndexed { i, c -> (1..3).joinToString("") { tc(c, i * 10 + it, if (i == 1 && it == 1) "failed" else "passed") } }.joinToString(""))
        assertEquals(1, check("mac").code)
        // too few tests
        write("a", "x", tc("HelperIT", 1, "passed"))
        assertEquals(1, check("mac").code)
        assertEquals(2, run(listOf("python3", checker)).code)
        laws.hit("it-checker", 8)
    }

    @Test
    fun `the cross-lane script proves its own diff can fail`() {
        val o = run(listOf("bash", File(scripts, "check-protocol-lanes.sh").absolutePath, "--selftest"))
        assertEquals(0, o.code, o.text)
        assertTrue("selftest OK" in o.text)
        // and a real disagreement is refused
        val a = File.createTempFile("lane-a", ".lines"); val b = File.createTempFile("lane-b", ".lines")
        val n = File(macos, "helper-protocol/vectors").listFiles { f -> f.extension == "jsonl" }!!.sumOf { f -> f.readLines().count { it.isNotEmpty() } }
        a.writeText((1..n).joinToString("\n") { "HP\taccept\t-\t$it" } + "\n")
        b.writeText((1..n).joinToString("\n") { "HP\taccept\t-\t${if (it == 7) "x" else it.toString()}" } + "\n")
        val bad = run(listOf("bash", File(scripts, "check-protocol-lanes.sh").absolutePath, "--no-run", a.absolutePath, b.absolutePath))
        assertEquals(1, bad.code, bad.text)
        assertTrue("DISAGREE" in bad.text)
        laws.hit("lanes-selftest", 2)
    }
}
