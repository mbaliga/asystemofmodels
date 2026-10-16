package xyz.mdhv.asom.desktop.win

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.ListenerGateDecision
import xyz.mdhv.asom.desktop.win.fakes.FakeInterfaces
import xyz.mdhv.asom.desktop.win.fakes.FakeRunner
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.nic
import xyz.mdhv.asom.desktop.win.net.Eligibility
import xyz.mdhv.asom.desktop.win.net.FirewallGate
import xyz.mdhv.asom.desktop.win.net.FirewallProbe
import xyz.mdhv.asom.desktop.win.net.FirewallRead
import xyz.mdhv.asom.desktop.win.net.FirewallRule
import xyz.mdhv.asom.desktop.win.net.FirewallTarget
import xyz.mdhv.asom.desktop.win.net.GateResult
import xyz.mdhv.asom.desktop.win.net.IneligibleReason
import xyz.mdhv.asom.desktop.win.net.InterfaceEligibility
import xyz.mdhv.asom.desktop.win.net.ListenDecision
import xyz.mdhv.asom.desktop.win.net.NetshFirewallProbe
import xyz.mdhv.asom.desktop.win.net.NetshRuleParser
import xyz.mdhv.asom.desktop.win.net.ParsedRules
import xyz.mdhv.asom.desktop.win.net.PeerPathKind
import xyz.mdhv.asom.desktop.win.net.SelectionResult
import xyz.mdhv.asom.desktop.win.net.WinListenerGate

/**
 * C16: the listener never starts before a consented allow rule exists, and every failure is CLOSED. The matrix is exhaustive
 * over the fields the gate reads; the mutation checks named in PROGRESS.md (probe failure read as open; a block rule
 * ignored) fail these tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirewallGateTest {
    private val laws = LawCounter(
        listOf(
            "gate-open", "gate-missing-when-not-ours", "gate-block-wins", "gate-foreign-block-ignored", "gate-probe-failure-closed",
            "gate-unjudgeable-block", "gate-service-mode", "gate-decision-closed-unless-open", "parse-fixture", "parse-crlf", "parse-localised-fails-closed",
            "iface-select", "iface-refuse", "iface-check", "iface-cgnat-boundaries", "listen-order", "netsh-probe",
        ),
    )

    private val exe = "C:\\Program Files\\asom\\asom.exe"
    private val target = FirewallTarget(exe, null, 11436)

    private fun rule(
        name: String = "asom peer listener (overlay)", enabled: Boolean? = true, inbound: Boolean? = true, action: String? = "Allow",
        protocol: String? = "TCP", port: String? = "11436", program: String? = exe, service: String? = null, profiles: String? = "Private",
        remote: String? = "100.64.0.0/10,fd7a:115c:a1e0::/48",
    ) = FirewallRule(name, enabled, inbound, action, protocol, port, program, service, profiles, remote)

    private class Probe(val f: () -> FirewallRead) : FirewallProbe { override fun read(): FirewallRead = f() }

    private fun gate(vararg rules: FirewallRule, t: FirewallTarget = target) =
        FirewallGate(Probe { FirewallRead.Rules(ParsedRules(rules.toList())) }) { t }

    // ---- allow rules ------------------------------------------------------------------------------------------------

    @Test
    fun `asom's own printed rule opens the gate`() {
        val r = gate(rule()).evaluate()
        assertTrue(r is GateResult.Open && r.allowRule == "asom peer listener (overlay)", r.stateName)
        assertEquals(ListenerGateDecision.OPEN, gate(rule()).decision())
        laws.hit("gate-open")
        // the LAN variant is ours too
        assertTrue(gate(rule(name = "asom peer listener (LAN)", remote = "LocalSubnet")).evaluate() is GateResult.Open)
    }

    @Test
    fun `an allow rule that is not exactly asom's does not open the gate`() {
        val bad = mapOf(
            "no rules" to emptyList(),
            "disabled" to listOf(rule(enabled = false)),
            "outbound" to listOf(rule(inbound = false)),
            "block action" to listOf(rule(action = "Bypass")),
            "udp" to listOf(rule(protocol = "UDP")),
            "any protocol" to listOf(rule(protocol = "Any")),
            "wrong port" to listOf(rule(port = "11437")),
            "any port" to listOf(rule(port = "Any")),
            "port list without ours" to listOf(rule(port = "80,443,5000-5010")),
            "other program" to listOf(rule(program = "C:\\Program Files\\other\\asom.exe")),
            "no program or service" to listOf(rule(program = null)),
            "foreign name" to listOf(rule(name = "Allow anything for asom.exe")),
            "remote any" to listOf(rule(remote = "Any")),
            "remote missing" to listOf(rule(remote = null)),
            "public only" to listOf(rule(profiles = "Public")),
            "domain only" to listOf(rule(profiles = "Domain")),
            "profiles unknown" to listOf(rule(profiles = null)),
        )
        for ((name, rules) in bad) {
            val r = gate(*rules.toTypedArray()).evaluate()
            assertEquals(GateResult.RuleMissing, r, "$name -> ${r.stateName}")
            assertEquals(ListenerGateDecision.CLOSED_UNTIL_CONSENT, gate(*rules.toTypedArray()).decision(), name)
            laws.hit("gate-missing-when-not-ours")
        }
        // controls: the ports the spec allows still pass
        assertTrue(gate(rule(port = "11400-11500")).evaluate() is GateResult.Open)
        assertTrue(gate(rule(port = "80, 11436")).evaluate() is GateResult.Open)
        assertTrue(gate(rule(profiles = "Domain,Private,Public")).evaluate() is GateResult.Open)
        assertTrue(gate(rule(program = exe.uppercase().replace('\\', '/'))).evaluate() is GateResult.Open, "path compare ignores case and slash direction")
    }

    // ---- block rules ------------------------------------------------------------------------------------------------

    @Test
    fun `a block rule that concerns asom wins over any allow rule`() {
        val allow = rule()
        val cancel = rule(name = "asom.exe", action = "Block", remote = "Any", port = "Any", profiles = "Public")
        val r = gate(allow, cancel).evaluate()
        assertTrue(r is GateResult.BlockRulePresent && r.ruleNames == listOf("asom.exe"), r.stateName)
        assertEquals("FIREWALL_BLOCK_RULE_PRESENT", r.stateName)
        assertEquals(ListenerGateDecision.CLOSED_UNTIL_CONSENT, gate(allow, cancel).decision())
        laws.hit("gate-block-wins")
        val concerning = mapOf(
            "our program" to rule(name = "x", action = "Block", program = exe, port = "Any", remote = "Any"),
            "our program, path case" to rule(name = "x", action = "Block", program = exe.lowercase(), port = "1", remote = "Any"),
            "our display name" to rule(name = "asom peer listener (old)", action = "Block", program = "C:\\other.exe"),
            "our service" to rule(name = "x", action = "Block", program = null, service = "asom"),
            "everyone, our port" to rule(name = "Block port", action = "Block", program = "Any", port = "11000-12000", remote = "Any"),
            "everyone, any port" to rule(name = "Block all in", action = "Block", program = "Any", port = "Any", remote = "Any", protocol = "Any"),
        )
        for ((n, b) in concerning) {
            assertTrue(gate(allow, b, t = FirewallTarget(exe, "asom", 11436)).evaluate() is GateResult.BlockRulePresent, n)
            laws.hit("gate-block-wins")
        }
    }

    @Test
    fun `block rules that do not concern asom are ignored`() {
        val allow = rule()
        val ignored = mapOf(
            "other program" to rule(name = "x", action = "Block", program = "C:\\Tools\\telemetry.exe"),
            "other port, everyone" to rule(name = "x", action = "Block", program = "Any", port = "445", remote = "Any"),
            "everyone but scoped remote" to rule(name = "x", action = "Block", program = "Any", port = "Any", remote = "10.1.2.3"),
            "disabled" to rule(name = "asom.exe", action = "Block", enabled = false, program = exe),
            "outbound" to rule(name = "asom.exe", action = "Block", inbound = false, program = exe),
            "udp only" to rule(name = "asom.exe", action = "Block", protocol = "UDP", program = exe),
            "unjudgeable, unrelated" to rule(name = "some vendor", enabled = null, action = null, program = "C:\\v.exe"),
        )
        for ((n, b) in ignored) {
            assertTrue(gate(allow, b).evaluate() is GateResult.Open, n)
            laws.hit("gate-foreign-block-ignored")
        }
    }

    @Test
    fun `a rule that names asom but cannot be judged counts as a block`() {
        val r = gate(rule(), rule(name = "asom.exe", enabled = true, inbound = true, action = null, program = exe)).evaluate()
        assertTrue(r is GateResult.BlockRulePresent, r.stateName)
        laws.hit("gate-unjudgeable-block")
    }

    @Test
    fun `every probe failure is closed, never open`() {
        val failures = listOf<() -> FirewallRead>(
            { FirewallRead.Failed("netsh timed out") },
            { error("boom") },
            { throw java.io.IOException("cannot start") },
        )
        for (f in failures) {
            val g = FirewallGate(Probe(f)) { target }
            val r = g.evaluate()
            assertTrue(r is GateResult.ProbeFailed, r.stateName)
            assertEquals("FIREWALL_PROBE_FAILED", r.stateName)
            assertEquals(ListenerGateDecision.CLOSED_UNTIL_CONSENT, g.decision())
            laws.hit("gate-probe-failure-closed")
        }
        assertTrue(FirewallGate(Probe { FirewallRead.Rules(ParsedRules(listOf(rule()))) }) { error("no target") }.evaluate() is GateResult.ProbeFailed)
    }

    @Test
    fun `service mode names the service, not a program`() {
        val svcTarget = FirewallTarget(null, "asom", 11436)
        assertTrue(gate(rule(program = null, service = "asom"), t = svcTarget).evaluate() is GateResult.Open)
        assertEquals(GateResult.RuleMissing, gate(rule(program = exe, service = null), t = svcTarget).evaluate(), "a program rule does not serve the service")
        assertEquals(GateResult.RuleMissing, gate(rule(program = null, service = "asom"), t = target).evaluate(), "a service rule does not serve user mode")
        assertFailsWith<IllegalArgumentException> { FirewallTarget(null, null, 11436) }
        laws.hit("gate-service-mode")
    }

    @Test
    fun `decision is CLOSED for every result except Open`() {
        val results = listOf(
            FirewallRead.Rules(ParsedRules(emptyList())),
            FirewallRead.Failed("x"),
            FirewallRead.Rules(ParsedRules(listOf(rule(), rule(name = "asom.exe", action = "Block", program = exe)))),
        )
        for (r in results) assertEquals(ListenerGateDecision.CLOSED_UNTIL_CONSENT, FirewallGate(Probe { r }) { target }.decision())
        laws.hit("gate-decision-closed-unless-open")
    }

    // ---- netsh parsing ------------------------------------------------------------------------------------------------

    private fun fixture(name: String): String = FirewallGateTest::class.java.getResourceAsStream("/fixtures/netsh/$name")!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun `the SYNTHETIC netsh fixtures parse into the rules they describe`() {
        val p = NetshRuleParser.parse(fixture("allow-overlay.txt"))
        assertEquals(3, p.rules.size)
        assertEquals(3, p.judgeableCount)
        val ours = p.rules.single { it.displayName.startsWith("asom peer listener") }
        assertEquals(true, ours.enabled); assertEquals(true, ours.inbound); assertEquals("Allow", ours.action); assertEquals("TCP", ours.protocol)
        assertEquals("11436", ours.localPort); assertEquals(exe, ours.program); assertEquals("Private", ours.profiles)
        assertEquals("100.64.0.0/10,fd7a:115c:a1e0::/48", ours.remoteIp)
        assertTrue(FirewallGate(Probe { FirewallRead.Rules(p) }) { target }.evaluate() is GateResult.Open)
        val blocked = NetshRuleParser.parse(fixture("block-after-prompt.txt"))
        assertEquals(3, blocked.rules.size)
        val r = FirewallGate(Probe { FirewallRead.Rules(blocked) }) { target }.evaluate()
        assertTrue(r is GateResult.BlockRulePresent && r.ruleNames == listOf("asom.exe"), "the auto-created block rules win over our allow rule: ${r.stateName}")
        laws.hit("parse-fixture")
    }

    @Test
    fun `CRLF output parses identically to LF output`() {
        for (name in listOf("allow-overlay.txt", "block-after-prompt.txt")) {
            val lf = NetshRuleParser.parse(fixture(name))
            val crlf = NetshRuleParser.parse(fixture(name).replace("\n", "\r\n"))
            assertEquals(lf.rules, crlf.rules, name)
            laws.hit("parse-crlf")
        }
    }

    @Test
    fun `a non-English listing is not recognised and the probe fails closed`() {
        val text = fixture("localized-de.txt")
        assertEquals(0, NetshRuleParser.parse(text).judgeableCount)
        val runner = FakeRunner(output = text, exitCode = 0)
        val read = NetshFirewallProbe(runner, "C:\\Windows").read()
        assertTrue(read is FirewallRead.Failed && "not recognised" in read.reason, read.toString())
        assertEquals(ListenerGateDecision.CLOSED_UNTIL_CONSENT, FirewallGate(NetshFirewallProbe(runner, "C:\\Windows")) { target }.decision())
        laws.hit("parse-localised-fails-closed")
    }

    @Test
    fun `the netsh probe runs exactly one read-only command by absolute path and maps failures`() {
        val runner = FakeRunner(output = fixture("allow-overlay.txt"))
        val read = NetshFirewallProbe(runner, "C:\\Windows").read()
        assertTrue(read is FirewallRead.Rules)
        assertEquals(listOf("C:\\Windows\\System32\\netsh.exe" to listOf("advfirewall", "firewall", "show", "rule", "name=all", "dir=in", "verbose")), runner.calls)
        assertTrue(NetshFirewallProbe(FakeRunner(timedOut = true), "C:\\Windows").read() is FirewallRead.Failed)
        assertTrue(NetshFirewallProbe(FakeRunner(throws = true), "C:\\Windows").read() is FirewallRead.Failed)
        assertTrue(NetshFirewallProbe(FakeRunner(output = "", exitCode = 1), "C:\\Windows").read() is FirewallRead.Failed, "no rules recognised is a failure, not 'open'")
        laws.hit("netsh-probe")
    }

    // ---- interface eligibility ------------------------------------------------------------------------------------------

    @Test
    fun `selection accepts only overlay or confirmed-LAN addresses and refuses the rest`() {
        val nics = listOf(
            nic("Tailscale", 12, "100.101.102.103", "fd7a:115c:a1e0:ab12:4843:cd96:6265:6667"),
            nic("Ethernet", 5, "192.168.1.20", "203.0.113.9"),
            nic("Wi-Fi", 7, "10.4.5.6"),
            nic("Down", 9, "192.168.9.9", up = false),
            nic("Loop", 1, "127.0.0.1"),
            nic("Weird", 2, "169.254.10.10", "224.0.0.1"),
        )
        fun sel(alias: String, kind: PeerPathKind, confirmed: Set<String> = emptySet()) = InterfaceEligibility.select(alias, kind, confirmed, nics)
        val ok = sel("tailscale", PeerPathKind.OVERLAY)
        assertTrue(ok is SelectionResult.Selected && ok.selection.bindAddress.hostAddress == "100.101.102.103" && ok.selection.index == 12, "alias match ignores case")
        laws.hit("iface-select")
        assertTrue(sel("Ethernet", PeerPathKind.LAN, setOf("Ethernet")) is SelectionResult.Selected)
        assertTrue(sel("Weird", PeerPathKind.LAN, setOf("Weird")) is SelectionResult.Selected, "link-local IPv4 is a LAN address (the multicast one is skipped)")
        val refusals = mapOf(
            "overlay on a LAN address" to (sel("Ethernet", PeerPathKind.OVERLAY) to IneligibleReason.NO_ELIGIBLE_ADDRESS),
            "LAN not confirmed" to (sel("Ethernet", PeerPathKind.LAN) to IneligibleReason.LAN_NOT_CONFIRMED),
            "overlay address as LAN" to (sel("Tailscale", PeerPathKind.LAN, setOf("Tailscale")) to IneligibleReason.NO_ELIGIBLE_ADDRESS),
            "public LAN address only" to (InterfaceEligibility.select("Pub", PeerPathKind.LAN, setOf("Pub"), listOf(nic("Pub", 3, "203.0.113.9"))) to IneligibleReason.NO_ELIGIBLE_ADDRESS),
            "interface down" to (sel("Down", PeerPathKind.LAN, setOf("Down")) to IneligibleReason.INTERFACE_DOWN),
            "loopback" to (sel("Loop", PeerPathKind.LAN, setOf("Loop")) to IneligibleReason.NO_ELIGIBLE_ADDRESS),
            "missing" to (sel("Nope", PeerPathKind.OVERLAY) to IneligibleReason.INTERFACE_MISSING),
        )
        for ((n, pair) in refusals) {
            val (r, reason) = pair
            assertTrue(r is SelectionResult.Refused && r.reason == reason, "$n: ${(r as? SelectionResult.Refused)?.reason}")
            laws.hit("iface-refuse")
        }
        // address-level rules: wildcard, loopback, multicast, public, scope
        for ((a, k, reason) in listOf(
            Triple("127.0.0.1", PeerPathKind.LAN, IneligibleReason.LOOPBACK),
            Triple("::1", PeerPathKind.LAN, IneligibleReason.LOOPBACK),
            Triple("224.0.0.251", PeerPathKind.LAN, IneligibleReason.MULTICAST),
            Triple("203.0.113.9", PeerPathKind.LAN, IneligibleReason.PUBLIC_ADDRESS),
            Triple("2001:db8::1", PeerPathKind.LAN, IneligibleReason.PUBLIC_ADDRESS),
            Triple("192.168.1.1", PeerPathKind.OVERLAY, IneligibleReason.OUT_OF_OVERLAY_RANGE),
            Triple("fe80::1", PeerPathKind.LAN, IneligibleReason.SCOPE_MISSING),
            Triple("fd7a:115c:a1e0::1", PeerPathKind.LAN, IneligibleReason.NOT_PRIVATE_LAN),
            Triple("100.64.0.1", PeerPathKind.LAN, IneligibleReason.NOT_PRIVATE_LAN),
        )) {
            assertEquals(reason, InterfaceEligibility.addressProblem(xyz.mdhv.asom.desktop.win.fakes.addr(a), k)?.first, a)
            laws.hit("iface-refuse")
        }
        val any = java.net.InetAddress.getByAddress(ByteArray(4))
        assertEquals(IneligibleReason.WILDCARD, InterfaceEligibility.addressProblem(any, PeerPathKind.LAN)?.first)
        assertEquals(IneligibleReason.WILDCARD, InterfaceEligibility.addressProblem(java.net.InetAddress.getByAddress(ByteArray(16)), PeerPathKind.OVERLAY)?.first)
        // a scoped link-local IPv6 address is fine
        val scoped = java.net.Inet6Address.getByAddress(null, xyz.mdhv.asom.desktop.win.fakes.addr("fe80::1").address, 12)
        assertEquals(null, InterfaceEligibility.addressProblem(scoped, PeerPathKind.LAN))
    }

    @Test
    fun `the CGNAT overlay range has the right edges`() {
        for ((a, inside) in listOf("100.64.0.0" to true, "100.127.255.255" to true, "100.63.255.255" to false, "100.128.0.0" to false, "10.0.0.1" to false)) {
            val ok = InterfaceEligibility.addressProblem(xyz.mdhv.asom.desktop.win.fakes.addr(a), PeerPathKind.OVERLAY) == null
            assertEquals(inside, ok, a)
            laws.hit("iface-cgnat-boundaries")
        }
        for ((a, inside) in listOf("fd7a:115c:a1e0::1" to true, "fd7a:115c:a1e0:ffff::1" to true, "fd7a:115c:a1e1::1" to false, "fd7b:115c:a1e0::1" to false)) {
            assertEquals(inside, InterfaceEligibility.addressProblem(xyz.mdhv.asom.desktop.win.fakes.addr(a), PeerPathKind.OVERLAY) == null, a)
            laws.hit("iface-cgnat-boundaries")
        }
        for ((a, inside) in listOf("172.15.255.255" to false, "172.16.0.0" to true, "172.31.255.255" to true, "172.32.0.0" to false, "192.169.0.1" to false, "fc00::1" to true, "fdff::1" to true, "fe00::1" to false)) {
            assertEquals(inside, InterfaceEligibility.addressProblem(xyz.mdhv.asom.desktop.win.fakes.addr(a), PeerPathKind.LAN) == null, a)
            laws.hit("iface-cgnat-boundaries")
        }
    }

    @Test
    fun `every bind re-checks, so a changed index, a lost address, a down interface or a withdrawn confirmation drains and extra addresses do not`() {
        val base = listOf(nic("Tailscale", 12, "100.101.102.103"))
        val sel = (InterfaceEligibility.select("Tailscale", PeerPathKind.OVERLAY, emptySet(), base) as SelectionResult.Selected).selection
        assertTrue(InterfaceEligibility.check(sel, base, emptySet()) is Eligibility.Eligible)
        assertEquals(IneligibleReason.NO_SELECTION, (InterfaceEligibility.check(null, base, emptySet()) as Eligibility.Ineligible).reason)
        assertEquals(IneligibleReason.INDEX_CHANGED, (InterfaceEligibility.check(sel, listOf(nic("Tailscale", 13, "100.101.102.103")), emptySet()) as Eligibility.Ineligible).reason)
        assertEquals(IneligibleReason.ADDRESS_GONE, (InterfaceEligibility.check(sel, listOf(nic("Tailscale", 12, "100.101.102.104")), emptySet()) as Eligibility.Ineligible).reason)
        assertEquals(IneligibleReason.INTERFACE_DOWN, (InterfaceEligibility.check(sel, listOf(nic("Tailscale", 12, "100.101.102.103", up = false)), emptySet()) as Eligibility.Ineligible).reason)
        assertEquals(IneligibleReason.INTERFACE_MISSING, (InterfaceEligibility.check(sel, emptyList(), emptySet()) as Eligibility.Ineligible).reason)
        assertTrue(InterfaceEligibility.check(sel, listOf(nic("Tailscale", 12, "100.101.102.103", "fd7a:115c:a1e0::9")), emptySet()) is Eligibility.Eligible, "an extra address is not a change")
        val lan = (InterfaceEligibility.select("Eth", PeerPathKind.LAN, setOf("Eth"), listOf(nic("Eth", 4, "192.168.1.2"))) as SelectionResult.Selected).selection
        assertTrue(InterfaceEligibility.check(lan, listOf(nic("Eth", 4, "192.168.1.2")), setOf("Eth")) is Eligibility.Eligible)
        assertEquals(IneligibleReason.LAN_NOT_CONFIRMED, (InterfaceEligibility.check(lan, listOf(nic("Eth", 4, "192.168.1.2")), emptySet()) as Eligibility.Ineligible).reason)
        assertEquals(PeerPathKind.OVERLAY, InterfaceEligibility.peerPath(sel.bindAddress, sel))
        assertEquals(null, InterfaceEligibility.peerPath(xyz.mdhv.asom.desktop.win.fakes.addr("100.101.102.104"), sel), "peerPath is never guessed")
        laws.hit("iface-check")
    }

    // ---- the composed gate --------------------------------------------------------------------------------------------

    @Test
    fun `the composed gate checks the interface first and reads the firewall only when an interface is eligible`() {
        val nics = FakeInterfaces(listOf(nic("Tailscale", 12, "100.101.102.103")))
        var firewallReads = 0
        val fw = FirewallGate(Probe { firewallReads++; FirewallRead.Rules(ParsedRules(listOf(rule()))) }) { target }
        val sel = (InterfaceEligibility.select("Tailscale", PeerPathKind.OVERLAY, emptySet(), nics.nics) as SelectionResult.Selected).selection
        // nothing selected: closed, the firewall is not even read
        var selection: xyz.mdhv.asom.desktop.win.net.SelectedInterface? = null
        val gate = WinListenerGate({ selection }, { emptySet() }, nics, fw)
        val closed = gate.evaluate()
        assertTrue(closed is ListenDecision.Closed && closed.state == "NO_SELECTION")
        assertEquals(0, firewallReads)
        assertEquals(ListenerGateDecision.CLOSED_UNTIL_CONSENT, gate.decision())
        // selected and consented: may bind exactly the selected address
        selection = sel
        val open = gate.evaluate()
        assertTrue(open is ListenDecision.MayBind && open.address.hostAddress == "100.101.102.103", open.toString())
        assertEquals(ListenerGateDecision.OPEN, gate.decision())
        assertTrue(firewallReads >= 1, "the firewall is read once an interface is eligible")
        // the interface changes underneath: closed again
        nics.nics = listOf(nic("Tailscale", 99, "100.101.102.103"))
        assertTrue((gate.evaluate() as ListenDecision.Closed).state == "INDEX_CHANGED")
        nics.nics = listOf(nic("Tailscale", 12, "100.101.102.103"))
        // no allow rule: closed with the state name
        val noRule = WinListenerGate({ sel }, { emptySet() }, nics, FirewallGate(Probe { FirewallRead.Rules(ParsedRules(emptyList())) }) { target })
        assertEquals("FIREWALL_RULE_MISSING", (noRule.evaluate() as ListenDecision.Closed).state)
        // unreadable interfaces: closed
        val broken = WinListenerGate({ sel }, { emptySet() }, FakeInterfaces(throws = true), fw)
        assertEquals("INTERFACES_UNREADABLE", (broken.evaluate() as ListenDecision.Closed).state)
        assertFalse(broken.decision() == ListenerGateDecision.OPEN)
        laws.hit("listen-order")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("firewall-and-interfaces")
}
