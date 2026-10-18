package xyz.mdhv.asom.desktop.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.LawCounter
import xyz.mdhv.asom.desktop.Report

/**
 * A-LA-3, B-LA-3: the real [DefaultTtyOpener], in a child JVM with no controlling terminal (`setsid`) and a piped stdin that
 * answers "yes". The JVM console is the way stdin could answer: with `-Djdk.console=jdk.internal.le` (the default provider on
 * JDK 22 and later) `System.console()` is non-null over a pipe and reads file descriptor 0. A fake opener never exercises this.
 * Evidence label: LAB (this machine's JDK; a Windows console is not exercised here).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultTtyOpenerTest {
    private val laws = LawCounter(listOf("no-tty-piped-stdin", "no-tty-console-provider-forced", "console-fallback-rule", "tty-positive"))

    @AfterAll
    fun allLawsExercised() = laws.assertAllExercised("DefaultTtyOpener")

    private fun runChild(vararg jvmArgs: String): String {
        val os = System.getProperty("os.name").orEmpty()
        check(os.startsWith("Linux")) { "this test needs setsid (Linux); node-core tests run on Linux in CI. os=$os" }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val cp = System.getProperty("asom.testClasspath") ?: error("asom.testClasspath is not set (run through Gradle)")
        val home = Files.createTempDirectory("asom-tty-home-")
        val pb = ProcessBuilder(listOf("setsid", java) + jvmArgs + listOf("-cp", cp, "xyz.mdhv.asom.desktop.cli.TtyChildMain"))
        pb.environment().keys.removeIf { it == "JAVA_TOOL_OPTIONS" || it == "JDK_JAVA_OPTIONS" || it.startsWith("XDG_") }
        pb.environment()["HOME"] = home.toString()
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        val p = pb.start()
        p.outputStream.use { it.write("yes\nyes\n".toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "child did not finish" }
        return out.lineSequence().firstOrNull { it.startsWith("RESULT ") } ?: error("child printed no RESULT line: '$out' (exit ${p.exitValue()})")
    }

    @Test
    fun `without a controlling terminal a piped stdin never answers`() {
        assertEquals("RESULT NoTty", runChild())
        laws.hit("no-tty-piped-stdin")
    }

    @Test
    fun `forcing the JVM console provider that reads file descriptor 0 does not make a pipe answer either`() {
        assertEquals("RESULT NoTty", runChild("-Djdk.console=jdk.internal.le"))
        laws.hit("no-tty-console-provider-forced")
    }

    private val ptyDriver = """
        import os, pty, select, sys, time
        pid, fd = pty.fork()
        if pid == 0:
            os.execv(sys.argv[1], sys.argv[1:])
        out = b""
        sent = False
        deadline = time.time() + 60
        while time.time() < deadline:
            ready, _, _ = select.select([fd], [], [], 1)
            if ready:
                try:
                    data = os.read(fd, 4096)
                except OSError:
                    break
                if not data:
                    break
                out += data
                if not sent and b"to confirm" in out:
                    os.write(fd, b"yes\n")
                    sent = True
        else:
            os.kill(pid, 9)
        os.waitpid(pid, 0)
        sys.stdout.buffer.write(out)
    """.trimIndent()

    @Test
    fun `a real controlling terminal does answer, so the tty branch is exercised and not only its absence`() {
        check(System.getProperty("os.name").orEmpty().startsWith("Linux")) { "this test needs a pty (Linux)" }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val cp = System.getProperty("asom.testClasspath") ?: error("asom.testClasspath is not set (run through Gradle)")
        val dir = Files.createTempDirectory("asom-tty-pty-")
        val driver = dir.resolve("pty_driver.py")
        Files.writeString(driver, ptyDriver)
        val pb = ProcessBuilder("python3", "-I", driver.toString(), java, "-cp", cp, "xyz.mdhv.asom.desktop.cli.TtyChildMain")
        pb.environment().keys.removeIf { it == "JAVA_TOOL_OPTIONS" || it == "JDK_JAVA_OPTIONS" || it.startsWith("XDG_") }
        pb.environment()["HOME"] = dir.toString()
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(90, TimeUnit.SECONDS)) { "pty driver did not finish" }
        assertTrue("RESULT Confirmed" in out, "no confirmation came back through the pty: '$out'")
        laws.hit("tty-positive")
    }

    @Test
    fun `the console fallback is decided by a pure rule that only a Windows console passes`() {
        val os = listOf("Linux", "Mac OS X", "Windows 11")
        for (o in os) {
            val allowed = ConsoleFallback.allowed(o, consoleProvider = null, consolePresent = true, isTerminal = null)
            assertEquals(o.startsWith("Windows"), allowed, o)
        }
        assertTrue(!ConsoleFallback.allowed("Windows 11", consoleProvider = "jdk.internal.le", consolePresent = true, isTerminal = null), "a provider that reads fd 0 needs isTerminal")
        assertTrue(ConsoleFallback.allowed("Windows 11", consoleProvider = "jdk.internal.le", consolePresent = true, isTerminal = true))
        assertTrue(!ConsoleFallback.allowed("Windows 11", consoleProvider = "jdk.internal.le", consolePresent = true, isTerminal = false))
        assertTrue(!ConsoleFallback.allowed("Windows 11", consoleProvider = null, consolePresent = false, isTerminal = null))
        assertTrue(!ConsoleFallback.allowed("Windows 11", consoleProvider = null, consolePresent = true, isTerminal = false))
        laws.hit("console-fallback-rule")
        Report.line("DefaultTtyOpener: piped stdin and a forced jdk.internal.le console answer NoTty in a setsid child JVM (LAB)")
    }
}
