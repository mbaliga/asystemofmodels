package xyz.mdhv.asom.desktop.mac

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.mac.helper.Event
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperEnvironment
import xyz.mdhv.asom.desktop.mac.helper.HelperLauncher
import xyz.mdhv.asom.desktop.mac.helper.HelperLostException
import xyz.mdhv.asom.desktop.mac.helper.HelperProcess
import xyz.mdhv.asom.desktop.mac.helper.HelperState
import xyz.mdhv.asom.desktop.mac.helper.ProcessHelperLauncher

/**
 * The node's side of the pipe against a REAL child process (a JVM running `FakeHelperMain`, which speaks the wire protocol from
 * the fixture machine). LAB evidence: the pipe, the framing, the id correlation, the loss and restart rules are the real code;
 * what answers is a fake, not the Swift helper (that is `mac/HelperIT`, macOS only).
 */
class HelperProcessTest {
    private val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
    private val classpath = System.getProperty("asom.testClasspath") ?: error("asom.testClasspath not set")
    private val childMain = "xyz.mdhv.asom.desktop.mac.fakes.FakeHelperMainKt"

    private fun launcher(vararg opts: String, env: Map<String, String> = emptyMap()) =
        ProcessHelperLauncher(listOf(javaBin, "-cp", classpath, childMain) + opts, env)

    private fun newProcess(vararg opts: String, clock: FakeClock = FakeClock(), callTimeoutMs: Long = 20_000, env: Map<String, String> = emptyMap()) =
        HelperProcess(launcher(*opts, env = env), clock, callTimeoutMs = callTimeoutMs, minRestartIntervalMs = 60_000, closeWaitMs = 10_000)

    private fun tmp(): File = Files.createTempDirectory("asom-helper-").toFile().also { it.deleteOnExit() }

    @Test
    fun `the first call starts the helper and says hello, and typed calls round-trip through real pipes`() {
        newProcess().use { p ->
            assertEquals(HelperState.NOT_STARTED, p.state)
            val c = HelperClient(p)
            assertEquals(HelperClient.PowerInfo("ac", true, 870, false), c.power())
            assertEquals(HelperState.RUNNING, p.state)
            assertEquals("0.1.0", p.helloInfo!!.helper)
            assertEquals(1, p.startCount)
            assertEquals("nominal", c.thermal())
            assertEquals(137, c.gpuUtilPermille())
            val key = c.seCreate()
            val sig = c.seSign(key.blob, byteArrayOf(1, 2, 3))
            assertTrue(xyz.mdhv.asom.desktop.mac.keys.Es256.verify(key.spki, byteArrayOf(1, 2, 3), sig), "a real ES256 signature came back over the real pipe")
        }
    }

    @Test
    fun `concurrent callers are correlated by id, never mixed up`() {
        newProcess().use { p ->
            val c = HelperClient(p)
            val failures = AtomicInteger()
            val done = CountDownLatch(8)
            repeat(8) { t ->
                Thread {
                    try {
                        repeat(25) { i ->
                            if ((t + i) % 2 == 0) {
                                if (c.thermal() != "nominal") failures.incrementAndGet()
                            } else if (c.gpuUtilPermille() != 137) failures.incrementAndGet()
                        }
                    } catch (_: Exception) {
                        failures.incrementAndGet()
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            assertTrue(done.await(60, TimeUnit.SECONDS))
            assertEquals(0, failures.get())
        }
    }

    @Test
    fun `events interleave with replies and a listener may call back into the client without deadlock`() {
        newProcess("--events").use { p ->
            val c = HelperClient(p)
            val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
            val acked = CountDownLatch(1)
            c.subscribe { e: Event ->
                seen += e.name
                if (e.name == "sleep.will") {
                    c.sleepAck((e.fields["token"] as xyz.mdhv.asom.desktop.mac.helper.HValue.I).v)
                    acked.countDown()
                }
            }
            c.thermal()
            assertTrue(acked.await(20, TimeUnit.SECONDS), "the sleep.will event reached the listener and its ack was answered")
            assertTrue("power" in seen && "sleep.will" in seen, seen.toString())
        }
    }

    @Test
    fun `a helper that crashes is lost, restarts are refused for a minute and then allowed once`() {
        val dir = tmp()
        val starts = File(dir, "starts")
        val clock = FakeClock(10_000_000)
        newProcess("--die-after=2", "--start-count=${starts.absolutePath}", clock = clock).use { p ->
            val c = HelperClient(p)
            assertEquals("nominal", c.thermal()) // hello was answer 1, this is answer 2: the child exits now
            val e = assertFailsWith<HelperLostException> {
                repeat(50) { c.thermal(); Thread.sleep(50) }
            }
            assertTrue(e.message!!.startsWith("PROBE_LOST"), e.message)
            assertEquals(HelperState.LOST, p.state)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (p.lostCount < 1 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, p.lostCount)
            val startsSoFar = p.startCount
            val e2 = assertFailsWith<HelperLostException> { c.thermal() }
            assertTrue("at most once per 60 s" in e2.message!!, e2.message)
            assertEquals(startsSoFar, p.startCount, "a refused restart must not launch anything")
            clock.now += 59_999
            assertFailsWith<HelperLostException> { c.thermal() }
            assertEquals(startsSoFar, p.startCount)
            clock.now += 1
            assertEquals("nominal", c.thermal(), "one minute after the last start the helper is restarted")
            assertEquals(startsSoFar + 1, p.startCount)
            assertEquals(2, starts.readLines().size, "two real processes were started in total")
        }
    }

    @Test
    fun `a helper that hangs is killed after the call timeout and reported lost`() {
        newProcess("--hang-on=power.get", callTimeoutMs = 700).use { p ->
            val c = HelperClient(p)
            assertEquals("nominal", c.thermal())
            val t0 = System.nanoTime()
            val e = assertFailsWith<HelperLostException> { c.power() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms in 500..15_000, "returned after about the timeout, not never: $ms ms")
            assertTrue("no reply to power.get" in e.message!!)
            assertEquals(HelperState.LOST, p.state)
            assertNotNull(p.lastLossReason)
        }
    }

    @Test
    fun `a helper that breaks the protocol is dropped, whatever it says`() {
        for (mode in listOf("--garbage", "--overlong", "--wrong-hello")) {
            newProcess(mode).use { p ->
                assertFailsWith<HelperLostException>("mode $mode") { HelperClient(p).thermal() }
                assertEquals(HelperState.LOST, p.state, mode)
            }
        }
    }

    @Test
    fun `closing the client closes the helper's stdin, and its EOF is what makes it exit`() {
        val marker = File(tmp(), "marker")
        val p = newProcess("--marker=${marker.absolutePath}")
        HelperClient(p).thermal()
        p.close()
        assertEquals(HelperState.CLOSED, p.state)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(marker.exists() && marker.readText() == "eof", "the child saw EOF on stdin and exited cleanly")
        assertFailsWith<HelperLostException> { HelperClient(p).thermal() }
    }

    @Test
    fun `stderr is discarded and can neither block the helper nor reach the node`() {
        newProcess("--noise").use { p ->
            val c = HelperClient(p)
            repeat(20) { assertEquals("nominal", c.thermal()) }
        }
    }

    @Test
    fun `the helper's environment holds only TMPDIR, never a secret, DYLD or a Java option`() {
        val out = File(tmp(), "env.txt")
        val parent = mapOf(
            "TMPDIR" to "/var/folders/zz/abc/T/", "ASOM_DEV_TOKEN" to "asom-dev-token-7f3c9a1e", "OPENAI_API_KEY" to "sk-live-XYZ",
            "DYLD_INSERT_LIBRARIES" to "/tmp/evil.dylib", "JAVA_TOOL_OPTIONS" to "-javaagent:/tmp/evil.jar", "PATH" to "/tmp/evil",
        )
        newProcess("--env-out=${out.absolutePath}", env = parent).use { p -> HelperClient(p).thermal() }
        val env = out.readText()
        assertTrue("TMPDIR=/var/folders/zz/abc/T/" in env)
        for (bad in listOf("ASOM_DEV_TOKEN", "OPENAI_API_KEY", "DYLD_", "evil", "javaagent", "sk-live")) assertFalse(bad in env, "leaked into the helper's environment: $bad")
        // the pure rule
        assertEquals(mapOf("TMPDIR" to "/x/"), HelperEnvironment.scrub(mapOf("TMPDIR" to "/x/", "HOME" to "/h")))
        assertEquals(emptyMap(), HelperEnvironment.scrub(mapOf("TMPDIR" to "relative")))
        assertEquals(emptyMap(), HelperEnvironment.scrub(mapOf("TMPDIR" to "/a\u0000b")))
        assertEquals(emptyMap(), HelperEnvironment.scrub(emptyMap()))
    }

    @Test
    fun `a helper that cannot be started is a lost probe with the reason, and a failed start counts against the restart limit`() {
        val clock = FakeClock(5_000_000)
        val launches = AtomicInteger()
        val bad = HelperLauncher {
            launches.incrementAndGet()
            ProcessHelperLauncher(listOf("/nonexistent/asom-mac-helper", "serve"), emptyMap()).launch()
        }
        HelperProcess(bad, clock, callTimeoutMs = 2_000, minRestartIntervalMs = 60_000).use { p ->
            val e = assertFailsWith<HelperLostException> { HelperClient(p).thermal() }
            assertTrue("cannot start the helper" in e.message!!, e.message)
            assertFailsWith<HelperLostException> { HelperClient(p).thermal() }
            assertEquals(1, launches.get(), "no second launch inside the minute")
            clock.now += 60_000
            assertFailsWith<HelperLostException> { HelperClient(p).thermal() }
            assertEquals(2, launches.get())
        }
    }

    @Test
    fun `loss listeners are told once per loss`() {
        newProcess("--die-after=1").use { p ->
            val reasons = java.util.concurrent.CopyOnWriteArrayList<String>()
            p.onLost { reasons += it }
            assertFailsWith<HelperLostException> { repeat(50) { HelperClient(p).thermal(); Thread.sleep(50) } }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (reasons.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            Thread.sleep(300)
            assertEquals(1, reasons.size, reasons.toString())
        }
    }
}
