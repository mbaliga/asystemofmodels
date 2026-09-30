package xyz.mdhv.asom.desktop.mac.macos

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.mac.Report
import xyz.mdhv.asom.desktop.mac.exec.SystemProcessRunner
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperProcess
import xyz.mdhv.asom.desktop.mac.helper.ProcessHelperLauncher
import xyz.mdhv.asom.desktop.mac.helper.ProtocolSpec

/**
 * The keep-awake assertion is REAL: `pmset -g assertions` shows it while it is held, and does NOT show it after the holder is killed
 * with SIGKILL (assumption AM11: IOKit releases an assertion when its owner exits; here the owner is the helper, which also sees
 * EOF on its stdin when the node dies). CI (hosted VM) evidence; whether a real Mac then idles to sleep is NDV.
 */
@EnabledOnOs(OS.MAC)
class PowerAssertionIT {
    private val helper = MacIT.helperPath()
    private val runner = SystemProcessRunner()

    private fun held(): Boolean = runner.run(SystemProcessRunner.PMSET, listOf("-g", "assertions"), 10_000).text.contains(ProtocolSpec.HOLD_REASON)

    private fun waitUntil(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(200)
        }
        assertTrue(cond(), "timed out waiting for: $what")
    }

    @Test
    fun `pmset shows the assertion while it is held and not after an explicit release`() {
        assertFalse(held(), "nothing holds asom's assertion before the test")
        HelperProcess(ProcessHelperLauncher(helper)).use { p ->
            val c = HelperClient(p)
            c.assertHold()
            waitUntil("the assertion to appear in pmset", 10_000) { held() }
            Report.line("IT PowerAssertionIT: held, pmset shows \"${ProtocolSpec.HOLD_REASON}\"")
            c.assertHold() // a second hold creates no second assertion
            val count = runner.run(SystemProcessRunner.PMSET, listOf("-g", "assertions"), 10_000).text.split(ProtocolSpec.HOLD_REASON).size - 1
            assertEquals(1, count, "exactly one assertion however many holds")
            c.assertRelease()
            waitUntil("the assertion to leave pmset", 10_000) { !held() }
            Report.line("IT PowerAssertionIT: released, pmset no longer shows it")
        }
    }

    @Test
    fun `the assertion is gone after the node is killed with SIGKILL (AM11)`() {
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val cp = System.getProperty("asom.testClasspath") ?: error("asom.testClasspath not set")
        val node = ProcessBuilder(javaBin, "-cp", cp, "xyz.mdhv.asom.desktop.mac.macos.HoldChildMainKt", helper.toString())
            .redirectErrorStream(true).start()
        try {
            assertEquals("held", node.inputStream.bufferedReader().readLine(), "the child node took the assertion")
            waitUntil("the assertion to appear in pmset", 10_000) { held() }
            node.destroyForcibly() // SIGKILL: no shutdown hook, no chance to release anything
            assertTrue(node.waitFor(20, TimeUnit.SECONDS))
            waitUntil("the assertion to disappear after SIGKILL of the node", 20_000) { !held() }
            Report.line("IT PowerAssertionIT: after kill -9 of the node, pmset no longer shows the assertion (AM11)")
        } finally {
            node.destroyForcibly()
        }
    }
}
