package xyz.mdhv.asom.desktop.win.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.ListenerGateDecision
import xyz.mdhv.asom.desktop.win.doctor.LidAction
import xyz.mdhv.asom.desktop.win.exec.SystemProcessRunner
import xyz.mdhv.asom.desktop.win.fakes.Report
import xyz.mdhv.asom.desktop.win.net.FirewallGate
import xyz.mdhv.asom.desktop.win.net.FirewallRead
import xyz.mdhv.asom.desktop.win.net.FirewallTarget
import xyz.mdhv.asom.desktop.win.net.GateResult
import xyz.mdhv.asom.desktop.win.net.NetshFirewallProbe

/**
 * CI-ONLY (hosted Windows runner; SKIPPED elsewhere). This is the only place the netsh parser meets REAL netsh output (the
 * fixtures in the pure tests are hand-written and labelled SYNTHETIC). The runner's language is English. The state of real
 * firewall profiles on real home networks stays NEEDS-DEVICE-VALIDATION.
 */
@EnabledOnOs(OS.WINDOWS)
class FirewallProbeIT {
    private val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"

    @Test
    fun `real netsh output parses into many judgeable rules and asom's gate is closed because no asom rule exists`() {
        val probe = NetshFirewallProbe(SystemProcessRunner(systemRoot), systemRoot)
        val read = probe.read()
        assertTrue(read is FirewallRead.Rules, "the parser must recognise real netsh output on an English runner: $read")
        val parsed = (read as FirewallRead.Rules).parsed
        Report.line("IT FirewallProbeIT: real netsh listed ${parsed.rules.size} inbound rules, ${parsed.judgeableCount} judgeable")
        assertTrue(parsed.judgeableCount >= 5, "judgeable rules: ${parsed.judgeableCount}")
        val gate = FirewallGate(probe) { FirewallTarget("C:\\Program Files\\asom\\asom.exe", null, 11436) }
        assertEquals(GateResult.RuleMissing, gate.evaluate(), "C16: FIREWALL_RULE_MISSING before any consented rule exists")
        assertEquals(ListenerGateDecision.CLOSED_UNTIL_CONSENT, gate.decision())
    }

    @Test
    fun `the lid action reads without throwing (a VM may have no lid setting)`() {
        val s = LidAction.read(SystemProcessRunner(systemRoot), systemRoot)
        Report.line("IT FirewallProbeIT: lid action = $s")
        assertNotNull(LidAction.name(s?.ac ?: 0))
    }
}
