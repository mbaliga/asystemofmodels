package xyz.mdhv.asom.desktop.win.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.PowerSource
import xyz.mdhv.asom.desktop.win.fakes.Report
import xyz.mdhv.asom.desktop.win.jna.JnaPowerRequests
import xyz.mdhv.asom.desktop.win.jna.JnaSystemPowerStatus
import xyz.mdhv.asom.desktop.win.jna.JnaSuspendNotifications
import xyz.mdhv.asom.desktop.win.power.PowerStatusParser
import xyz.mdhv.asom.desktop.win.power.WinPowerPort

/** CI-ONLY (hosted Windows runner; SKIPPED elsewhere). Sleep, lid, Modern Standby and battery behaviour stay NEEDS-DEVICE-VALIDATION. */
@EnabledOnOs(OS.WINDOWS)
class PowerRequestIT {
    private val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"

    /** `powercfg /requests` (elevated). It is not on the node's read-only allowlist, so the test runs it itself. */
    private fun requests(): String? {
        val p = ProcessBuilder("$systemRoot\\System32\\powercfg.exe", "/requests").redirectErrorStream(true).start()
        val text = p.inputStream.readAllBytes().toString(Charsets.ISO_8859_1)
        return if (p.waitFor() == 0) text else null
    }

    @Test
    fun `a hold shows up in powercfg requests with the spec's reason string and is gone after release`() {
        val before = requests()
        assumeTrue(before != null, "powercfg /requests needs an elevated token; hosted runners run as administrator [FW39]")
        val port = WinPowerPort(JnaSystemPowerStatus(), JnaPowerRequests(), JnaSuspendNotifications())
        val hold = port.hold(LockKind.BLOCK)
        try {
            assertEquals(1, port.activeHolds, "the request was created: ${port.lastHoldError}")
            val during = requests()!!
            Report.line("IT PowerRequestIT: powercfg /requests while holding contains the reason: ${"asom: lending compute" in during}")
            assertTrue("asom: lending compute to your paired devices" in during, "powercfg /requests output:\n$during")
        } finally {
            hold.release()
        }
        val after = requests()!!
        assertFalse("asom: lending compute" in after, "the request must be cleared after release:\n$after")
        assertEquals(0, port.activeHolds)
    }

    @Test
    fun `GetSystemPowerStatus is readable and parses to a reading`() {
        val raw = JnaSystemPowerStatus().read()
        assertNotNull(raw)
        val r = PowerStatusParser.parse(raw)
        Report.line("IT PowerRequestIT: raw=$raw source=${r.source} hasBattery=${r.hasBattery} saver=${r.saver} (a VM: not evidence about a device)")
        assertTrue(raw.acLineStatus in 0..255)
        assertTrue(r.source == PowerSource.AC || r.source == PowerSource.BATTERY || r.source == PowerSource.UNKNOWN)
    }

    @Test
    fun `registering and unregistering the suspend and display notifications works without a window`() {
        val handle = JnaSuspendNotifications().register { }
        handle.close()
        handle.close()
    }
}
