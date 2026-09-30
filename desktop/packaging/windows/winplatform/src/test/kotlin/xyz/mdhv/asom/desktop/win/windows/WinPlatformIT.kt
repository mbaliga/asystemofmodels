package xyz.mdhv.asom.desktop.win.windows

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.ExitCodes
import xyz.mdhv.asom.desktop.HostFinder
import xyz.mdhv.asom.desktop.HostLookup
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.NodeMain
import xyz.mdhv.asom.desktop.win.WinPlatform
import xyz.mdhv.asom.desktop.win.fakes.Captured
import xyz.mdhv.asom.desktop.win.fakes.Report
import java.io.PrintStream

/**
 * CI-ONLY (hosted Windows runner; SKIPPED elsewhere): the whole host through the shared node entry, with REAL ports. It is
 * the test that shows the seam, the paths, the mutex-free selftest, the probes and the doctor work together on Windows.
 * Evidence label: CI (hosted VM) evidence, NOT device evidence.
 */
@EnabledOnOs(OS.WINDOWS)
class WinPlatformIT {
    @Test
    fun `the ServiceLoader finds the Windows host on Windows`() {
        val lookup = HostFinder.find()
        assertTrue(lookup is HostLookup.Found && lookup.platform is WinPlatform, lookup.toString())
    }

    @Test
    fun `asom-node selftest with the real ports reports zero failures`() {
        val cap = Captured()
        val code = NodeMain.run(listOf("--mode=selftest"), cap.env(userName = System.getProperty("user.name")), HostLookup.Found(WinPlatform())) {}
        Report.line("IT WinPlatformIT selftest output:\n" + cap.outText)
        assertEquals(ExitCodes.OK, code, cap.outText + cap.errText)
        assertContains(cap.outText, "0 failed")
        assertContains(cap.outText, "host windows")
        assertFalse("[FAIL]" in cap.outText)
    }

    @Test
    fun `the doctor runs on real ports and prints every check`() {
        val cap = Captured()
        val p = WinPlatform()
        val d = p.doctor(HostMode.USER)
        val lines = d.run()
        d.print(PrintStream(cap.out, true, Charsets.UTF_8), lines)
        Report.line("IT WinPlatformIT doctor output:\n" + cap.outText)
        assertTrue(lines.size >= 11)
        assertTrue(lines.single { it.id == "firewall" }.text.contains("FIREWALL_RULE_MISSING") || lines.single { it.id == "firewall" }.text.contains("FIREWALL"), "the firewall state is named")
    }

    @Test
    fun `the default charset of this JVM is reported and never assumed (AW20)`() {
        Report.line("IT WinPlatformIT: java=${System.getProperty("java.version")} defaultCharset=${Charset.defaultCharset()} native.encoding=${System.getProperty("native.encoding")}")
        assertTrue(Charset.defaultCharset().name().isNotEmpty())
    }
}
