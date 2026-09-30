package xyz.mdhv.asom.desktop.mac

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.PartiallyImplemented
import xyz.mdhv.asom.desktop.mac.ctl.MacControlSocket
import xyz.mdhv.asom.desktop.mac.ctl.PeerPrincipal
import xyz.mdhv.asom.desktop.mac.ctl.SoPeerCred
import xyz.mdhv.asom.desktop.mac.net.ClassifiedDial
import xyz.mdhv.asom.desktop.mac.net.DialOutcome
import xyz.mdhv.asom.desktop.mac.net.Eligibility
import xyz.mdhv.asom.desktop.mac.net.InterfaceEligibility
import xyz.mdhv.asom.desktop.mac.net.InterfaceKind
import xyz.mdhv.asom.desktop.mac.net.InterfaceSnapshot
import xyz.mdhv.asom.desktop.mac.net.ListenBinding
import xyz.mdhv.asom.desktop.mac.net.Reresolution

/** Interface eligibility, the Local Network classification, and the control socket's identity rules. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NetAndControlTest {
    private val laws = LawCounter(
        listOf(
            "kinds", "bindable-table", "overlay-resolve", "overlay-unsupported", "lan-resolve", "lan-refuses", "reresolve", "dial-classify",
            "peer-both-directions", "peer-real-socket", "socket-not-bound", "socket-dir",
        ),
    )

    @AfterAll
    fun report() = laws.assertAllExercised("net-control")

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    private fun ll6(bytes: String, scope: Int): Inet6Address =
        Inet6Address.getByAddress(null, InetAddress.getByName(bytes).address, scope)

    @Test
    fun `interface names are classified by kind`() {
        val table = mapOf(
            "utun0" to InterfaceKind.OVERLAY_CANDIDATE, "utun12" to InterfaceKind.OVERLAY_CANDIDATE,
            "en0" to InterfaceKind.LAN_CANDIDATE, "en12" to InterfaceKind.LAN_CANDIDATE, "lo0" to InterfaceKind.LOOPBACK,
            "bridge0" to InterfaceKind.OTHER, "awdl0" to InterfaceKind.OTHER, "llw0" to InterfaceKind.OTHER, "pdp_ip0" to InterfaceKind.OTHER,
            "ipsec0" to InterfaceKind.OTHER, "gif0" to InterfaceKind.OTHER, "stf0" to InterfaceKind.OTHER, "ap1" to InterfaceKind.OTHER,
            "utun" to InterfaceKind.OTHER, "en" to InterfaceKind.OTHER, "en0.1" to InterfaceKind.OTHER, "xutun0" to InterfaceKind.OTHER, "" to InterfaceKind.OTHER,
        )
        for ((n, k) in table) assertEquals(k, InterfaceEligibility.kindOf(n), n)
        laws.hit("kinds", table.size.toLong())
    }

    @Test
    fun `no wildcard, loopback, multicast or public address is ever bindable, whatever the caller composes`() {
        val never = listOf(
            "0.0.0.0", "127.0.0.1", "127.1.2.3", "224.0.0.251", "255.255.255.255", "8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0",
            "192.167.1.1", "192.169.1.1", "11.0.0.1", "169.253.1.1", "::", "::1", "ff02::fb", "2001:4860:4860::8888", "fe7f::1", "fec0::1", "fbff::1", "fe00::1",
        )
        for (a in never) assertFalse(InterfaceEligibility.isBindable(ip(a)), a)
        val always = listOf("100.64.0.1", "100.127.255.254", "10.0.0.5", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.1.5", "169.254.10.10", "fd00::1", "fc00::1", "fd7a:115c:a1e0::1")
        for (a in always) assertTrue(InterfaceEligibility.isBindable(ip(a)), a)
        assertTrue(InterfaceEligibility.isBindable(ll6("fe80::1", 4)), "link-local with a scope")
        assertFalse(InterfaceEligibility.isBindable(ip("fe80::1")), "link-local with NO scope names no interface")
        // the overlay block is not a LAN address, and a LAN address is not an overlay address
        assertFalse(InterfaceEligibility.isLanAddress(ip("100.64.0.1")))
        assertFalse(InterfaceEligibility.isLanAddress(ip("fd7a:115c:a1e0::1")))
        assertTrue(InterfaceEligibility.isOverlayAddress(ip("fd7a:115c:a1e0:ffff::1")))
        assertFalse(InterfaceEligibility.isOverlayAddress(ip("fd7a:115c:a1e1::1")))
        assertFalse(InterfaceEligibility.isOverlayAddress(ip("192.168.1.1")))
        laws.hit("bindable-table", (never.size + always.size + 2).toLong())
    }

    private fun snap(name: String, vararg addrs: String, up: Boolean = true) = InterfaceSnapshot(name, up, addrs.map(::ip))

    @Test
    fun `the overlay resolves to the utun that carries an overlay address, deterministically`() {
        val ifs = listOf(
            snap("lo0", "127.0.0.1"), snap("en0", "192.168.1.5"), snap("utun3", "fe80::1"), snap("utun5", "100.101.102.103", "fd7a:115c:a1e0::5"),
            snap("utun4", "100.90.0.1"),
        )
        val e = InterfaceEligibility.resolveOverlay(ifs)
        assertIs<Eligibility.Bind>(e)
        assertEquals("utun4", e.binding.interfaceName, "lowest interface name among the IPv4 candidates")
        assertEquals(ip("100.90.0.1"), e.binding.address)
        assertEquals(InterfaceKind.OVERLAY_CANDIDATE, e.binding.kind)
        // IPv6-only overlay
        val v6 = InterfaceEligibility.resolveOverlay(listOf(snap("utun2", "fd7a:115c:a1e0::9"))) as Eligibility.Bind
        assertEquals("utun2", v6.binding.interfaceName)
        laws.hit("overlay-resolve", 2)
    }

    @Test
    fun `no kernel TUN with an overlay address is overlay mode unsupported, and loopback is never a substitute`() {
        val userspace = listOf(snap("lo0", "127.0.0.1"), snap("en0", "192.168.1.5"), snap("utun0", "fe80::1"))
        val e = InterfaceEligibility.resolveOverlay(userspace)
        assertIs<Eligibility.Refuse>(e)
        assertTrue("overlay mode unsupported: use a kernel TUN" in e.reason)
        assertIs<Eligibility.Refuse>(InterfaceEligibility.resolveOverlay(listOf(snap("utun1", "100.64.0.1", up = false))))
        assertIs<Eligibility.Refuse>(InterfaceEligibility.resolveOverlay(listOf(snap("en0", "100.64.0.1"))), "an overlay address on en0 is not an overlay interface")
        assertIs<Eligibility.Refuse>(InterfaceEligibility.resolveOverlay(emptyList()))
        laws.hit("overlay-unsupported", 4)
    }

    @Test
    fun `a LAN interface must be confirmed, en-star, up, and carry a private or link-local address`() {
        val ifs = listOf(snap("en0", "203.0.113.9", "192.168.1.5"), snap("en1", "8.8.8.8"), snap("en2", "10.0.0.2", up = false), snap("bridge0", "192.168.9.9"), snap("utun1", "100.64.0.1"))
        val ok = InterfaceEligibility.resolveLan(ifs, "en0", true)
        assertIs<Eligibility.Bind>(ok)
        assertEquals(ip("192.168.1.5"), ok.binding.address, "the public address on the same interface is skipped")
        assertEquals(InterfaceKind.LAN_CANDIDATE, ok.binding.kind)
        val refusals = mapOf(
            "unconfirmed" to InterfaceEligibility.resolveLan(ifs, "en0", false),
            "public only" to InterfaceEligibility.resolveLan(ifs, "en1", true),
            "down" to InterfaceEligibility.resolveLan(ifs, "en2", true),
            "not en*" to InterfaceEligibility.resolveLan(ifs, "bridge0", true),
            "overlay interface is not a LAN" to InterfaceEligibility.resolveLan(ifs, "utun1", true),
            "missing" to InterfaceEligibility.resolveLan(ifs, "en9", true),
        )
        for ((why, r) in refusals) assertIs<Eligibility.Refuse>(r, why)
        assertTrue(refusals.getValue("unconfirmed").let { (it as Eligibility.Refuse).reason.contains("not confirmed") })
        val scoped = InterfaceEligibility.resolveLan(listOf(InterfaceSnapshot("en0", true, listOf(ll6("fe80::1", 4)))), "en0", true)
        assertIs<Eligibility.Bind>(scoped)
        laws.hit("lan-resolve")
        laws.hit("lan-refuses", refusals.size.toLong())
    }

    @Test
    fun `re-resolving after a wake, same, moved (utun renumbered), or lost`() {
        val overlay = ListenBinding(InterfaceKind.OVERLAY_CANDIDATE, "utun4", ip("100.90.0.1"))
        assertIs<Reresolution.Same>(InterfaceEligibility.reresolve(overlay, listOf(snap("utun4", "100.90.0.1")), false))
        val moved = InterfaceEligibility.reresolve(overlay, listOf(snap("utun7", "100.90.0.1")), false)
        assertIs<Reresolution.Moved>(moved)
        assertEquals("utun7", moved.binding.interfaceName)
        assertIs<Reresolution.Moved>(InterfaceEligibility.reresolve(overlay, listOf(snap("utun4", "100.90.0.77")), false), "a new overlay address is a change")
        assertIs<Reresolution.Lost>(InterfaceEligibility.reresolve(overlay, listOf(snap("utun4", "fe80::1")), false))
        val lan = ListenBinding(InterfaceKind.LAN_CANDIDATE, "en0", ip("192.168.1.5"))
        assertIs<Reresolution.Same>(InterfaceEligibility.reresolve(lan, listOf(snap("en0", "192.168.1.5", "192.168.1.6")), true), "an extra address does not flap")
        assertIs<Reresolution.Lost>(InterfaceEligibility.reresolve(lan, listOf(snap("en0", "192.168.1.6")), true))
        assertIs<Reresolution.Lost>(InterfaceEligibility.reresolve(lan, listOf(snap("en0", "192.168.1.5", up = false)), true))
        assertIs<Reresolution.Lost>(InterfaceEligibility.reresolve(lan, emptyList(), true))
        assertIs<Reresolution.Lost>(InterfaceEligibility.reresolve(lan, listOf(snap("en0", "192.168.1.5")), false), "the confirmation was withdrawn")
        assertIs<Reresolution.Lost>(InterfaceEligibility.reresolve(ListenBinding(InterfaceKind.LOOPBACK, "lo0", ip("127.0.0.1")), listOf(snap("lo0", "127.0.0.1")), true))
        laws.hit("reresolve", 10)
    }

    @Test
    fun `a failed dial is classified, and a LAN No-route-to-host is an inference that says so`() {
        fun c(e: Throwable, dest: String): ClassifiedDial? = InterfaceEligibility.classifyDial(e, ip(dest))
        assertEquals(DialOutcome.TIMEOUT, c(SocketTimeoutException("connect timed out"), "192.168.1.9")!!.outcome)
        assertEquals(DialOutcome.REFUSED, c(java.net.ConnectException("Connection refused"), "192.168.1.9")!!.outcome)
        val denied = c(NoRouteToHostException("No route to host"), "192.168.1.9")!!
        assertEquals(DialOutcome.LOCAL_NETWORK_DENIED, denied.outcome)
        assertEquals("local-network-denied", denied.outcome.wire)
        assertTrue(denied.inferred, "AM09 is unverified: this is an inference")
        assertTrue("host is really unreachable" in denied.explanation)
        assertEquals(DialOutcome.LOCAL_NETWORK_DENIED, c(java.io.IOException("No route to host (Host unreachable)"), "169.254.3.3")!!.outcome)
        assertNull(c(NoRouteToHostException("No route to host"), "100.101.102.103"), "an overlay destination is never Local Network privacy")
        assertNull(c(NoRouteToHostException("No route to host"), "8.8.8.8"), "a public destination is not local network either")
        assertNull(c(IllegalStateException("something else"), "192.168.1.9"))
        val wire = DialOutcome.entries.map { it.wire }
        assertEquals(listOf("connected", "refused", "timeout", "pin-mismatch", "not-tls", "local-network-denied", "firewall-blocked"), wire)
        laws.hit("dial-classify", 9)
    }

    // ---- control socket -----------------------------------------------------------------------------------------------------

    @Test
    fun `the caller is identified by user name and both directions use the same rule`() {
        val me = PeerPrincipal("alice", "staff")
        assertEquals("local-uid:alice", MacControlSocket.callerIdentity(me).callerPkg)
        assertTrue(MacControlSocket.authorizeClient(me, "alice"))
        assertFalse(MacControlSocket.authorizeClient(PeerPrincipal("bob", "staff"), "alice"))
        assertFalse(MacControlSocket.authorizeClient(PeerPrincipal("501", "20"), "alice"), "an unresolvable uid arrives as digits and is denied")
        assertFalse(MacControlSocket.authorizeClient(null, "alice"), "an unreadable peer is denied")
        assertFalse(MacControlSocket.authorizeClient(me, ""), "an empty own name matches nobody")
        assertTrue(MacControlSocket.verifyServer(me, "alice"))
        assertFalse(MacControlSocket.verifyServer(PeerPrincipal("_www", "_www"), "alice"), "the CLI does not trust a server socket owned by someone else")
        assertFalse(MacControlSocket.verifyServer(null, "alice"))
        laws.hit("peer-both-directions", 8)
    }

    @Test
    fun `the socket path limit is 103 bytes, and the socket directory must be a private real directory`() {
        assertNull(MacControlSocket.pathProblem("/" + "a".repeat(102)))
        assertNotNull(MacControlSocket.pathProblem("/" + "a".repeat(103)))
        assertNotNull(MacControlSocket.pathProblem("/" + "é".repeat(52)))
        val me: Int? = runCatching { Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int }.getOrNull()
        val dir = Files.createTempDirectory("asom-ctl-")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        assertEquals(emptyList(), MacControlSocket.dirProblems(dir, me))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx--x---"))
        assertTrue(MacControlSocket.dirProblems(dir, me).isNotEmpty())
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        assertTrue(MacControlSocket.dirProblems(dir, (me ?: 0) + 1).isNotEmpty(), "owned by someone else")
        laws.hit("socket-dir", 4)
    }

    @Test
    fun `the control socket does not bind, and declares that it does not`() {
        val s = MacControlSocket(Path.of("/tmp/x/ctl.sock"))
        val e = assertFailsWith<NotYetImplementedException> { s.start { _, _ -> error("unreachable") } }
        assertTrue("control-socket bind" in e.message!!)
        assertIs<PartiallyImplemented>(s)
        assertEquals(1, s.notYetImplemented.size)
        assertFailsWith<NotYetImplementedException> { s.notYetImplemented.single().probe() }
        assertFalse(Files.exists(Path.of("/tmp/x/ctl.sock")))
        laws.hit("socket-not-bound")
    }

    /**
     * A REAL AF_UNIX pair, bound here in the test (the main sources never bind). On Linux this exercises the JDK's SO_PEERCRED; on macOS
     * the same call is getpeereid, and `mac/ControlSocketIT` asserts that. Either way both directions see this user's own name.
     */
    @Test
    fun `real peer credentials over a real AF_UNIX pair name this user in both directions`() {
        assertTrue(SoPeerCred.isSupported(), "this JDK exposes peer credentials for AF_UNIX")
        val dir = Files.createTempDirectory("asom-s-")
        val path = dir.resolve("c.sock")
        val me = System.getProperty("user.name")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(path))
            SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                client.connect(UnixDomainSocketAddress.of(path))
                server.accept().use { accepted ->
                    val seenByServer = SoPeerCred.read(accepted)
                    val seenByClient = SoPeerCred.read(client)
                    assertNotNull(seenByServer); assertNotNull(seenByClient)
                    assertEquals(me, seenByServer.user)
                    assertEquals(me, seenByClient.user)
                    assertTrue(MacControlSocket.authorizeClient(seenByServer, me))
                    assertTrue(MacControlSocket.verifyServer(seenByClient, me))
                    assertFalse(MacControlSocket.authorizeClient(seenByServer, "someone-else"))
                }
            }
        }
        laws.hit("peer-real-socket")
    }
}
