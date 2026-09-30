package xyz.mdhv.asom.desktop.win

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.net.FirewallCommand
import xyz.mdhv.asom.desktop.win.net.FirewallCommandRefused
import xyz.mdhv.asom.desktop.win.net.FirewallRequest
import xyz.mdhv.asom.desktop.win.net.PeerPathKind

/** The node PRINTS firewall commands and never applies them (windows.md 4.2; assignment). The text is pinned verbatim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirewallCommandTest {
    private val laws = LawCounter(listOf("golden-overlay", "golden-lan", "golden-service", "golden-removal", "hostile-refused", "lan-needs-confirmation", "print-never-applies", "validation"))

    private val date = "2026-09-30"

    @Test
    fun `the overlay rule is exactly the text of windows dot md 4 dot 2`() {
        val expected = """
            New-NetFirewallRule -Name 'asom-peer-overlay-in' -DisplayName 'asom peer listener (overlay)' `
              -Direction Inbound -Action Allow -Protocol TCP -LocalPort 11436 `
              -Program 'C:\Program Files\asom\asom.exe' `
              -InterfaceAlias 'Tailscale' -RemoteAddress 100.64.0.0/10,fd7a:115c:a1e0::/48 `
              -Profile Private -EdgeTraversalPolicy Block `
              -Description 'asom: paired devices only; created by asom mesh firewall enable on 2026-09-30'
        """.trimIndent() + "\n"
        assertEquals(expected, FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, dateIso = date)).text)
        laws.hit("golden-overlay")
    }

    @Test
    fun `the LAN rule is scoped to a confirmed interface and the local subnet`() {
        val expected = """
            New-NetFirewallRule -Name 'asom-peer-lan-in' -DisplayName 'asom peer listener (LAN)' `
              -Direction Inbound -Action Allow -Protocol TCP -LocalPort 11436 `
              -Program 'C:\Program Files\asom\asom.exe' `
              -InterfaceAlias 'Ethernet 2' -RemoteAddress LocalSubnet `
              -Profile Private -EdgeTraversalPolicy Block `
              -Description 'asom: paired devices only; created by asom mesh firewall enable on 2026-09-30'
        """.trimIndent() + "\n"
        val req = FirewallRequest(PeerPathKind.LAN, lanAlias = "Ethernet 2", confirmedLans = setOf("ethernet 2"), dateIso = date)
        assertEquals(expected, FirewallCommand.render(req).text)
        laws.hit("golden-lan")
        assertFailsWith<FirewallCommandRefused> { FirewallCommand.render(req.copy(confirmedLans = emptySet())) }
        assertFailsWith<FirewallCommandRefused> { FirewallCommand.render(req.copy(lanAlias = null)) }
        assertFailsWith<FirewallCommandRefused> { FirewallCommand.render(req.copy(lanAlias = "Wi-Fi")) }
        laws.hit("lan-needs-confirmation")
    }

    @Test
    fun `service mode swaps the program for the service`() {
        val text = FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, programPath = null, serviceName = "asom", dateIso = date)).text
        assertTrue("  -Service 'asom' `\n" in text && "-Program" !in text, text)
        assertFailsWith<FirewallCommandRefused> { FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, programPath = null, serviceName = null, dateIso = date)) }
        assertFailsWith<FirewallCommandRefused> { FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, serviceName = "asom", dateIso = date)) }
        laws.hit("golden-service")
    }

    @Test
    fun `removal is one command naming both rules`() {
        assertEquals("Remove-NetFirewallRule -Name 'asom-peer-overlay-in','asom-peer-lan-in' -ErrorAction SilentlyContinue\n", FirewallCommand.renderRemoval().text)
        laws.hit("golden-removal")
    }

    @Test
    fun `a hostile interface alias, path, service or date can never end the quoted string`() {
        val hostile = listOf(
            "x'; Remove-Item C:\\ -Recurse; '", "Tailscale' -Force; calc #", "a`nb", "a\nb", "a\rb", "a`\$(calc)", "\$(calc)", "\${env:USERNAME}",
            "Tailscale\u2019; calc; \u2018", "a\u201ab", "Tailscale\u0000", "Local Area Connection* 3", "Wi-Fi?", "[Eth]", "", " Tailscale", "-Tailscale",
            "a;b", "a|b", "a&b", "a>b", "a<b", "a\"b", "a%b%", "a,b", "a".repeat(65),
        )
        for (alias in hostile) {
            assertFailsWith<FirewallCommandRefused>("alias <$alias>") { FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, overlayAlias = alias, dateIso = date)) }
            laws.hit("hostile-refused")
        }
        val badPaths = listOf(
            "C:\\Program Files\\asom\\asom.exe'; calc; '", "C:\\a\\..\\b\\asom.exe", "C:\\Program Files\\asom\\asom.dll", "relative\\asom.exe", "\\\\server\\share\\asom.exe",
            "C:\\a\\.\\asom.exe", "C:\\Program Files\\asom\\asom.exe\n", "C:\\a`b\\asom.exe",
        )
        for (p in badPaths) {
            assertFailsWith<FirewallCommandRefused>("path <$p>") { FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, programPath = p, dateIso = date)) }
            laws.hit("hostile-refused")
        }
        for (s in listOf("asom'; calc", "1asom", "as om", "")) {
            assertFailsWith<FirewallCommandRefused>("service <$s>") { FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, programPath = null, serviceName = s, dateIso = date)) }
            laws.hit("hostile-refused")
        }
        for (d in listOf("2026-9-30", "2026-09-30'; calc", "today", "", "2026-09-30\n")) {
            assertFailsWith<FirewallCommandRefused>("date <$d>") { FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, dateIso = d)) }
            laws.hit("hostile-refused")
        }
        // controls: ordinary aliases pass, including localised letters and the punctuation Windows uses
        for (alias in listOf("Tailscale", "Ethernet 2", "Wi-Fi", "vEthernet (Default Switch)", "Ethernet-Verbindung", "Bluetooth Network Connection #2", "Sieć lokalna")) {
            FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, overlayAlias = alias, dateIso = date))
            laws.hit("validation")
        }
    }

    @Test
    fun `ports are bounded`() {
        for (p in listOf(0, 80, 1023, 65536, -1)) assertFailsWith<FirewallCommandRefused> { FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, port = p, dateIso = date)) }
        assertTrue("-LocalPort 1024 " in FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, port = 1024, dateIso = date)).text)
        laws.hit("validation")
    }

    @Test
    fun `print writes the commands and the limits to the given stream and applies nothing`() {
        val buf = ByteArrayOutputStream()
        FirewallCommand.print(PrintStream(buf, true, Charsets.UTF_8), FirewallCommand.render(FirewallRequest(PeerPathKind.OVERLAY, dateIso = date)))
        val out = buf.toString(Charsets.UTF_8)
        assertTrue(out.startsWith("# asom PRINTS these commands and never runs them."), out)
        assertTrue("New-NetFirewallRule -Name 'asom-peer-overlay-in'" in out)
        assertTrue("does NOT do" in out && "authorisation boundary" in out && "Group Policy" in out && "Public network blocks" in out)
        for (forbidden in listOf("Start-Process", "RunAs", "Invoke-Expression", "iex ", "Set-ExecutionPolicy", "netsh advfirewall firewall add")) {
            assertFalse(forbidden.lowercase() in out.lowercase(), "printed text must not contain $forbidden")
        }
        laws.hit("print-never-applies")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("firewall-command")
}
