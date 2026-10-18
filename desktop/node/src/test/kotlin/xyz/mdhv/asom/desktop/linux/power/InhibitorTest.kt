package xyz.mdhv.asom.desktop.linux.power

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.linux.Report

/**
 * The keep-awake locks. A FAKE `systemd-inhibit` (a shell script in a temp dir) records how it was called and can refuse
 * a block lock the way polkit does; one case runs the REAL `systemd-inhibit` if it exists. None of this touches logind
 * (evidence label: LAB). What real logind and polkit do is `systemd-vm.sh` (CI-ONLY) and NEEDS-DEVICE-VALIDATION.
 */
class InhibitorTest {
    private class Fake(val dir: Path) {
        val script: Path = dir.resolve("fake-systemd-inhibit")
        val log: Path = dir.resolve("calls.log")
        val denyBlock: Path = dir.resolve("deny-block")

        init {
            Files.writeString(
                script,
                """#!/bin/sh
mode=
what=
who=
echo "${'$'}*" >> "$log"
for a in "${'$'}@"; do
  case "${'$'}a" in
    --what=*) what=${'$'}{a#--what=} ;;
    --mode=*) mode=${'$'}{a#--mode=} ;;
    --who=*) who=${'$'}{a#--who=} ;;
  esac
done
while [ "${'$'}#" -gt 0 ]; do
  case "${'$'}1" in
    --*) shift ;;
    *) break ;;
  esac
done
if [ "${'$'}mode" = block ] && [ -e "$denyBlock" ]; then
  echo "Failed to inhibit: Access denied" >&2
  exit 1
fi
exec "${'$'}@"
""",
            )
            script.toFile().setExecutable(true)
        }

        fun calls(): List<String> = if (Files.exists(log)) Files.readAllLines(log) else emptyList()
        fun inhibitor(blockAllowed: Boolean = true, readyTimeoutMs: Long = 5_000) =
            Inhibitor(blockAllowed, listOf(script.toString()), readyTimeoutMs = readyTimeoutMs)
    }

    private fun fake() = Fake(Files.createTempDirectory("asom-inhibit-"))

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun waitDead(pid: Long, ms: Long = 5_000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (!alive(pid)) return true
            Thread.sleep(25)
        }
        return !alive(pid)
    }

    @Test
    fun `a delay lock is held with exactly sleep and delay, released on request, and the holder process ends`() {
        val f = fake()
        val hold = f.inhibitor().hold(LockKind.DELAY)
        assertEquals(HoldState.HELD, hold.state, hold.detail)
        assertEquals("delay-lock held", hold.statusText)
        val pid = assertNotNull(hold.pid)
        assertTrue(alive(pid))
        val call = f.calls().single()
        assertTrue("--what=sleep" in call && "--mode=delay" in call && "--who=asom" in call, call)
        assertFalse("idle" in call, "an idle lock is never taken: $call")
        hold.release()
        assertEquals(HoldState.RELEASED, hold.state)
        assertTrue(waitDead(pid), "the process that held the lock is still alive after release")
        hold.release()
    }

    @Test
    fun `a block lock that polkit refuses is a recorded state, tried once, never an exception or a retry loop`() {
        val f = fake()
        Files.createFile(f.denyBlock)
        val hold = f.inhibitor().hold(LockKind.BLOCK)
        assertEquals(HoldState.REFUSED, hold.state)
        assertTrue("Access denied" in hold.detail, hold.detail)
        assertEquals("keep-awake: unavailable (polkit)", hold.statusText)
        Thread.sleep(600)
        assertEquals(1, f.calls().size, "a refusal must not be retried by this class: ${f.calls()}")
        hold.release()
        val delay = f.inhibitor().hold(LockKind.DELAY)
        assertEquals(HoldState.HELD, delay.state, "a refused block lock does not stop the delay lock")
        delay.release()
    }

    @Test
    fun `a block lock is held when polkit allows it`() {
        val f = fake()
        val hold = f.inhibitor().hold(LockKind.BLOCK)
        assertEquals(HoldState.HELD, hold.state, hold.detail)
        assertEquals("block-lock held", hold.statusText)
        assertTrue("--mode=block" in f.calls().single())
        hold.release()
    }

    @Test
    fun `never a block lock where the host forbids it (SteamOS) - nothing is even spawned, the delay lock still works`() {
        val f = fake()
        val spawned = AtomicInteger()
        val inhibitor = Inhibitor(blockLockAllowed = false, inhibitCommand = listOf(f.script.toString()), launcher = { spawned.incrementAndGet(); ProcessBuilder(it).start() })
        val block = inhibitor.hold(LockKind.BLOCK)
        assertEquals(HoldState.REFUSED, block.state)
        assertTrue("never taken on this host" in block.detail, block.detail)
        assertTrue(block.statusText.startsWith("keep-awake: unavailable (policy"), block.statusText)
        assertEquals(0, spawned.get(), "no process may be started for a forbidden block lock")
        assertEquals(emptyList(), f.calls())
        val delay = inhibitor.hold(LockKind.DELAY)
        assertEquals(HoldState.HELD, delay.state, delay.detail)
        assertEquals(1, spawned.get())
        assertTrue("--mode=delay" in f.calls().single())
        delay.release()
    }

    @Test
    fun `a missing systemd-inhibit binary is a refused lock, not an exception`() {
        val hold = Inhibitor(true, listOf("/nonexistent/systemd-inhibit")).hold(LockKind.DELAY)
        assertEquals(HoldState.REFUSED, hold.state)
        assertTrue("cannot run" in hold.detail, hold.detail)
        hold.release()
    }

    @Test
    fun `a holder that never says ready is killed after the timeout and reported`() {
        val dir = Files.createTempDirectory("asom-inhibit-")
        val slow = dir.resolve("slow")
        Files.writeString(slow, "#!/bin/sh\nsleep 30\n")
        slow.toFile().setExecutable(true)
        var pid = -1L
        val hold = Inhibitor(true, listOf(slow.toString()), launcher = { ProcessBuilder(it).start().also { p -> pid = p.pid() } }, readyTimeoutMs = 300).hold(LockKind.DELAY)
        assertEquals(HoldState.REFUSED, hold.state)
        assertTrue("timed out" in hold.detail, hold.detail)
        assertTrue(waitDead(pid), "the stuck child was not killed")
    }

    @Test
    fun `a lock whose holder dies underneath us becomes LOST, and release afterwards is safe`() {
        val f = fake()
        val hold = f.inhibitor().hold(LockKind.DELAY)
        val pid = assertNotNull(hold.pid)
        ProcessHandle.of(pid).get().destroyForcibly()
        val end = System.currentTimeMillis() + 5_000
        while (hold.state == HoldState.HELD && System.currentTimeMillis() < end) Thread.sleep(25)
        assertEquals(HoldState.LOST, hold.state)
        assertTrue(hold.statusText.contains("lost"), hold.statusText)
        hold.release()
    }

    /** ERR-DL2-6: the lock must not outlive the JVM, even when the JVM is SIGKILLed (no shutdown hook runs). */
    @Test
    fun `the lock is released when the JVM that took it is killed with SIGKILL`() {
        val f = fake()
        val cp = System.getProperty("asom.testClasspath") ?: error("asom.testClasspath is not set (run through Gradle)")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-cp", cp, "xyz.mdhv.asom.desktop.linux.power.InhibitorChildMainKt", f.script.toString())
            .redirectErrorStream(true).start()
        val reader = p.inputStream.bufferedReader()
        var first: String?
        do { first = reader.readLine() } while (first != null && !first.startsWith("pid=") && !first.startsWith("Exception"))
        assertTrue(first != null && first.startsWith("pid="), "child JVM said: $first")
        val lockPid = first!!.removePrefix("pid=").toLong()
        assertTrue(alive(lockPid), "the lock holder should be alive while the JVM lives")
        p.destroyForcibly()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(137, p.exitValue(), "the JVM must have died by SIGKILL")
        assertTrue(waitDead(lockPid, 8_000), "LEAK: the lock holder $lockPid outlived its SIGKILLed JVM")
        Report.line("Inhibitor: lock holder $lockPid ended after its JVM was SIGKILLed (exit 137): OK (LAB, fake systemd-inhibit)")
    }

    @Test
    fun `the real systemd-inhibit binary, if present, is driven with valid arguments and never throws`() {
        val real = listOf("/usr/bin/systemd-inhibit", "/bin/systemd-inhibit").firstOrNull { File(it).canExecute() }
        if (real == null) {
            Report.line("Inhibitor: real systemd-inhibit not present on this machine; case not run (LAB)")
            return
        }
        val hold = Inhibitor(true).hold(LockKind.DELAY)
        try {
            assertTrue(hold.state == HoldState.HELD || hold.state == HoldState.REFUSED, hold.state.toString())
            if (hold.state == HoldState.REFUSED) {
                assertFalse("option" in hold.detail.lowercase() || "usage" in hold.detail.lowercase(), "systemd-inhibit rejected our arguments: ${hold.detail}")
            }
            Report.line("Inhibitor: real systemd-inhibit ${hold.state} (${hold.detail}) in this environment (LAB; a refusal here is expected without logind)")
        } finally {
            hold.release()
        }
    }
}
