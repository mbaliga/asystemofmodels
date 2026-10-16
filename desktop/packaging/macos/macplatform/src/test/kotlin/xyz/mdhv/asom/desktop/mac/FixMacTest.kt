package xyz.mdhv.asom.desktop.mac

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.mac.fakes.FakeHelper
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperLauncher
import xyz.mdhv.asom.desktop.mac.helper.HelperLostException
import xyz.mdhv.asom.desktop.mac.helper.HelperProcess
import xyz.mdhv.asom.desktop.mac.helper.ProcessHelperLauncher
import xyz.mdhv.asom.desktop.mac.keys.KeyTierRequest
import xyz.mdhv.asom.desktop.mac.keys.MacNikStore
import xyz.mdhv.asom.desktop.mac.keys.NikUnavailableException
import xyz.mdhv.asom.desktop.mac.net.Eligibility
import xyz.mdhv.asom.desktop.mac.net.InterfaceEligibility
import xyz.mdhv.asom.desktop.mac.net.InterfaceKind
import xyz.mdhv.asom.desktop.mac.net.InterfaceSnapshot
import xyz.mdhv.asom.desktop.mac.net.ListenBinding
import xyz.mdhv.asom.desktop.mac.net.Reresolution
import xyz.mdhv.asom.desktop.mac.power.MacPowerPort

/**
 * Regression tests for the verified review findings HWM-7, HWM-8, HWM-9, HWM-11, HA-01 and HA-08. They run on any operating
 * system over fakes: they prove the LOGIC, not the Secure Enclave, a real helper process or a real network (those stay
 * NEEDS-DEVICE-VALIDATION).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FixMacTest {
    private val laws = LawCounter(
        listOf(
            "hwm7-open-verifies-stored-key", "hwm8-overlay-selection", "hwm9-assertion-follows-helper", "hwm11-unreadable-binding",
            "ha01-checklist-limit", "ha08-socket-probe",
        ),
    )

    @AfterAll
    fun report() = laws.assertAllExercised("fix-mac")

    private val me: Int? = runCatching { Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int }.getOrNull()

    private fun identityDir(): Path {
        val root = Files.createTempDirectory("asom-fixmac-")
        val dir = root.resolve("node")
        MacPaths.createPrivateDir(dir)
        return dir
    }

    private fun paths(dir: Path) = NodePaths(HostMode.USER, dir.parent, dir.parent, dir.parent.resolve("ledger"), dir, dir.parent.resolve("run"), dir.parent.resolve("run/ctl.sock"))

    // ---- HWM-7: opening a T2 identity checks the STORED public key against the blob ---------------------------------------------------

    @Test
    fun `HWM-7 a T2 identity whose stored public key is not the blob's key is refused at open`() {
        val dir = identityDir()
        val h = FakeHelper()
        val store = MacNikStore(paths(dir), HelperClient(h), null) { me }
        store.createAtFirstMeshEnable(KeyTierRequest.AUTO)
        assertNotNull(store.existing(), "control: a consistent identity opens")
        // replace the stored SubjectPublicKeyInfo (the last 91 bytes of nik.se) with another real P-256 key's: the blob is untouched,
        // so the helper's own self-test (blob against blob's own public key) still says verified
        val file = dir.resolve("nik.se")
        val bytes = Files.readAllBytes(file)
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded
        assertEquals(91, other.size)
        System.arraycopy(other, 0, bytes, bytes.size - 91, 91)
        Files.write(file, bytes)
        val e = assertFailsWith<NikUnavailableException> { MacNikStore(paths(dir), HelperClient(h), null) { me }.existing() }
        assertContains(e.message.orEmpty(), "public key")
        laws.hit("hwm7-open-verifies-stored-key")
    }

    // ---- HWM-8: the overlay is the one the user selected, never one chosen silently among several ------------------------------------

    private fun snap(name: String, vararg addrs: String, up: Boolean = true) = InterfaceSnapshot(name, up, addrs.map { InetAddress.getByName(it) })

    @Test
    fun `HWM-8 another CGNAT VPN on a utun is never chosen over the selected overlay, and a lost overlay is lost, not moved`() {
        val warp = snap("utun3", "100.96.0.2")
        val tailscale = snap("utun5", "100.101.2.3", "fd7a:115c:a1e0::5")
        val both = listOf(snap("lo0", "127.0.0.1"), snap("en0", "192.168.1.5"), warp, tailscale)
        val tsAddr = InetAddress.getByName("100.101.2.3")
        // two candidates and no selection: the node does not pick
        val ambiguous = InterfaceEligibility.resolveOverlay(both)
        assertIs<Eligibility.Refuse>(ambiguous)
        assertContains(ambiguous.reason, "utun3")
        assertContains(ambiguous.reason, "utun5")
        // an explicit selection binds that address on its own interface
        val chosen = InterfaceEligibility.resolveOverlay(both, tsAddr)
        assertIs<Eligibility.Bind>(chosen)
        assertEquals("utun5", chosen.binding.interfaceName)
        assertEquals(tsAddr, chosen.binding.address)
        assertIs<Eligibility.Refuse>(InterfaceEligibility.resolveOverlay(both, InetAddress.getByName("100.101.9.9")), "an address no utun carries")
        // one candidate and no selection still resolves (the common single-VPN Mac)
        assertIs<Eligibility.Bind>(InterfaceEligibility.resolveOverlay(listOf(tailscale)))
        val recorded = ListenBinding(InterfaceKind.OVERLAY_CANDIDATE, "utun5", tsAddr)
        assertIs<Reresolution.Same>(InterfaceEligibility.reresolve(recorded, both, false))
        // utun renumbered across a reboot: the same ADDRESS on another interface is a move
        val renumbered = listOf(snap("utun3", "100.101.2.3"), snap("utun5", "100.96.0.2"))
        val moved = InterfaceEligibility.reresolve(recorded, renumbered, false)
        assertIs<Reresolution.Moved>(moved)
        assertEquals("utun3", moved.binding.interfaceName)
        // Tailscale stopped: only the other VPN is left, and the listener must not move onto it
        val lost = InterfaceEligibility.reresolve(recorded, listOf(snap("lo0", "127.0.0.1"), warp), false)
        assertIs<Reresolution.Lost>(lost, "a different VPN's address is not the overlay the user selected")
        // the recorded interface now carries a different overlay address (someone else's): lost
        assertIs<Reresolution.Lost>(InterfaceEligibility.reresolve(recorded, listOf(snap("utun5", "100.96.0.9")), false))
        laws.hit("hwm8-overlay-selection")
    }

    // ---- HWM-9: the keep-awake assertion is the helper PROCESS's, and dies with it -----------------------------------------------------

    @Test
    fun `HWM-9 a hold taken after the helper was restarted asks the new helper for the assertion`() {
        val h = FakeHelper()
        val port = MacPowerPort(HelperClient(h))
        val a = port.hold(LockKind.DELAY)
        assertTrue(h.assertionHeld)
        h.die()
        assertFalse(h.assertionHeld, "the assertion died with the helper")
        h.restart()
        val b = port.hold(LockKind.BLOCK)
        assertEquals(2, h.opCount("assert.hold"), "the second hold must not piggyback on an assertion that no longer exists")
        assertTrue(h.assertionHeld)
        assertEquals(2, port.activeHolds)
        // one assertion still serves both holds on the same helper
        val c = port.hold(LockKind.DELAY)
        assertEquals(2, h.opCount("assert.hold"))
        a.release(); b.release()
        assertTrue(h.assertionHeld, "one hold is still active")
        c.release()
        assertFalse(h.assertionHeld)
        laws.hit("hwm9-assertion-follows-helper")
    }

    @Test
    fun `HWM-9 the next power read puts the assertion back for holds that are still active`() {
        val h = FakeHelper()
        val port = MacPowerPort(HelperClient(h))
        val a = port.hold(LockKind.DELAY)
        h.die()
        h.restart()
        assertFalse(h.assertionHeld)
        port.read()
        assertTrue(h.assertionHeld, "a held lock and a helper with no assertion is the state the doctor and pmset would disagree about")
        assertEquals(2, h.opCount("assert.hold"))
        port.read()
        assertEquals(2, h.opCount("assert.hold"), "and it is not re-sent on every read")
        a.release()
        assertFalse(h.assertionHeld)
        laws.hit("hwm9-assertion-follows-helper")
    }

    @Test
    fun `HWM-9 a hold while the helper is gone is refused honestly, not counted as held`() {
        val h = FakeHelper()
        val port = MacPowerPort(HelperClient(h))
        val a = port.hold(LockKind.DELAY)
        h.die()
        val b = port.hold(LockKind.DELAY)
        assertNotNull(port.lastHoldError, "the helper is lost: the hold could not be placed")
        assertEquals(1, port.activeHolds, "a hold that holds nothing is not counted")
        h.restart()
        val c = port.hold(LockKind.DELAY)
        assertTrue(h.assertionHeld)
        assertNull(port.lastHoldError)
        b.release(); a.release(); c.release()
        assertFalse(h.assertionHeld)
        laws.hit("hwm9-assertion-follows-helper")
    }

    @Test
    fun `HWM-9 the real helper process changes its epoch on every restart`() {
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("asom.testClasspath") ?: error("asom.testClasspath not set")
        val launcher: HelperLauncher = ProcessHelperLauncher(listOf(javaBin, "-cp", classpath, "xyz.mdhv.asom.desktop.mac.fakes.FakeHelperMainKt", "--die-after=2"), emptyMap())
        val clock = FakeClock(10_000_000)
        HelperProcess(launcher, clock, callTimeoutMs = 20_000, minRestartIntervalMs = 60_000, closeWaitMs = 10_000).use { p ->
            val c = HelperClient(p)
            assertEquals(0L, p.epoch, "no helper yet")
            c.thermal()
            val first = p.epoch
            assertEquals(1L, first)
            assertFailsWith<HelperLostException> { repeat(50) { c.thermal(); Thread.sleep(50) } }
            assertEquals(first, p.epoch, "a lost helper is not a new one until it is restarted")
            clock.now += 61_000
            c.thermal()
            assertEquals(first + 1, p.epoch, "the restarted helper is a different process")
        }
        laws.hit("hwm9-assertion-follows-helper")
    }

    // ---- HWM-11: an unreadable binding record is not a mismatch ---------------------------------------------------------------------

    private class Registry : PairedRegistry {
        var unpaired = 0
        override fun unpairAll(): Int { unpaired++; return 3 }
    }

    @Test
    fun `HWM-11 a binding record that cannot be read does not unpair any peer`() {
        val dir = Files.createTempDirectory("asom-guard-fix-")
        val digest = ByteArray(32) { 1 }
        val g = MigrationGuard(dir.resolve("binding.json"), { digest })
        g.bind()
        val registry = Registry()
        assertEquals(MigrationGuard.Verdict.Bound, g.enforce(true, registry).first, "control")
        // a directory where the file should be: reading it fails with an I/O error, which says nothing about this Mac
        Files.delete(dir.resolve("binding.json"))
        Files.createDirectory(dir.resolve("binding.json"))
        val v = g.check(true)
        assertIs<MigrationGuard.Verdict.Unverifiable>(v)
        assertContains(v.reason, "cannot be read")
        val (verdict, unpaired) = g.enforce(true, registry)
        assertIs<MigrationGuard.Verdict.Unverifiable>(verdict)
        assertEquals(0, unpaired)
        assertEquals(0, registry.unpaired, "nothing destructive on a transient read failure")
        // the store refuses to present the identity, as it does for any unverifiable binding
        val nik = identityDir()
        val h = FakeHelper()
        val store = MacNikStore(paths(nik), HelperClient(h), MigrationGuard(nik.resolve("binding.json"), { digest }), { me })
        store.createAtFirstMeshEnable(KeyTierRequest.AUTO)
        Files.delete(nik.resolve("binding.json"))
        Files.createDirectory(nik.resolve("binding.json"))
        assertFailsWith<NikUnavailableException> { store.existing() }
        // controls: a readable record that does not match, and bytes that are not UTF-8, are still migrated or corrupt
        Files.delete(dir.resolve("binding.json"))
        g.bind()
        assertIs<MigrationGuard.Verdict.Migrated>(MigrationGuard(dir.resolve("binding.json"), { ByteArray(32) { 2 } }).check(true))
        Files.write(dir.resolve("binding.json"), byteArrayOf(0xC3.toByte(), 0x28))
        assertIs<MigrationGuard.Verdict.Migrated>(g.check(true))
        laws.hit("hwm11-unreadable-binding")
    }

    // ---- HA-01: the checklist states the limit the code has ----------------------------------------------------------------------------

    @Test
    fun `HA-01 the device checklist expects the node's own socket-path limit, not a stale 103`() {
        val line = File(repoRoot(), "desktop/packaging/macos/docs/DEVICE_CHECKLIST_MACOS.md").readLines().single { it.startsWith("| S-M7a ") }
        assertFalse("at least 103" in line, "a Mac that behaves like the hosted runner binds 102 and would be marked FAIL: $line")
        assertContains(line, "MAX_SOCKET_PATH_BYTES")
        assertContains(line, MacPaths.MAX_SOCKET_PATH_BYTES.toString())
        val errata = File(repoRoot(), "desktop/packaging/macos/ERRATA.md").readText(Charsets.UTF_8)
        assertContains(errata, "ERR-FX-HA01")
        laws.hit("ha01-checklist-limit")
    }

    // ---- HA-08: the socket-limit probe cannot pass by finding nothing ----------------------------------------------------------------------

    @Test
    fun `HA-08 the socket-limit probe fails when nothing binds, when nothing was tried, and when the node's limit is too high`() {
        val base = Path.of("/tmp/asom-len-0123456789")
        val lengths = 90..110
        fun host(limit: Int) = SocketLimitProbe.probe(base, lengths) { it.toString().toByteArray().size <= limit }
        val limit = MacPaths.MAX_SOCKET_PATH_BYTES
        // the hosted macOS runner measured 102: the node's limit must be bindable there
        assertNull(SocketLimitProbe.problem(host(102), lengths, limit), "a host that binds 102 bytes satisfies the node's limit of $limit")
        assertNull(SocketLimitProbe.problem(host(104), lengths, limit))
        // a host whose bind limit is 99, the review's surviving mutation: the old check passed it, this one does not
        val tight = host(99)
        assertEquals(99, tight.longest)
        assertNotNull(SocketLimitProbe.problem(tight, lengths, limit))
        // the old probe range 100..110 found nothing on that host (longest 0), which its `longest == 0 ||` escape read as a pass
        val oldRange = SocketLimitProbe.probe(base, 100..110) { it.toString().toByteArray().size <= 99 }
        assertEquals(0, oldRange.longest)
        assertNotNull(SocketLimitProbe.problem(oldRange, 100..110, limit))
        // a host that binds none of the probed lengths (limit below 90) must fail, not pass with zero
        val none = host(80)
        assertEquals(0, none.longest)
        assertNotNull(SocketLimitProbe.problem(none, lengths, limit))
        // a base directory so long that no probed length can be built: nothing was tried, so nothing is shown
        val longBase = Path.of("/tmp/" + "x".repeat(120))
        val untried = SocketLimitProbe.probe(longBase, lengths) { true }
        assertTrue(untried.tried.isEmpty())
        assertNotNull(SocketLimitProbe.problem(untried, lengths, limit))
        // a base so long that only SOME lengths can be built, on a host that binds them all: the probe still shows too little
        val partial = SocketLimitProbe.probe(Path.of("/tmp/" + "y".repeat(92)), lengths) { true }
        assertTrue(partial.tried.isNotEmpty() && partial.tried.size < lengths.count() && partial.longest >= limit)
        assertNotNull(SocketLimitProbe.problem(partial, lengths, limit), "an incomplete probe must not pass on the strength of the lengths it did try")
        // and every length is tried from a short base
        assertEquals(lengths.toList(), host(102).tried)
        laws.hit("ha08-socket-probe")
    }
}
