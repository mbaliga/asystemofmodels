package xyz.mdhv.asom.desktop.win

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.win.api.InterfaceKind
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.nic
import xyz.mdhv.asom.desktop.win.jna.InterfaceKinds
import xyz.mdhv.asom.desktop.win.net.Eligibility
import xyz.mdhv.asom.desktop.win.net.IneligibleReason
import xyz.mdhv.asom.desktop.win.net.InterfaceEligibility
import xyz.mdhv.asom.desktop.win.net.PeerPathKind
import xyz.mdhv.asom.desktop.win.net.SelectionResult

/**
 * B-LA-2, A-LA-7: trust.md 6.6 makes eligibility depend on the adapter type, not only the alias and the address. A cellular
 * adapter is never bound, and the same 100.64.0.0/10 block on a non-tunnel adapter is carrier-grade NAT, not the overlay.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InterfaceKindTest {
    private val laws = LawCounter(listOf("kind-classify", "kind-select-refuse", "kind-select-accept", "kind-check"))

    @AfterAll
    fun allLawsExercised() = laws.assertAllExercised("InterfaceKind")

    private fun reason(r: SelectionResult) = (r as? SelectionResult.Refused)?.reason

    @Test
    fun `IfType and the physical medium map to a kind, and anything unnamed is OTHER`() {
        val table = listOf(
            Triple(6, 0, InterfaceKind.ETHERNET), Triple(71, 0, InterfaceKind.WIFI), Triple(71, 9, InterfaceKind.WIFI),
            Triple(243, 0, InterfaceKind.CELLULAR), Triple(244, 0, InterfaceKind.CELLULAR), Triple(23, 0, InterfaceKind.CELLULAR),
            Triple(6, 8, InterfaceKind.CELLULAR), Triple(71, 8, InterfaceKind.CELLULAR),
            Triple(131, 0, InterfaceKind.TUNNEL), Triple(53, 0, InterfaceKind.TUNNEL), Triple(1, 0, InterfaceKind.TUNNEL),
            Triple(24, 0, InterfaceKind.OTHER), Triple(9, 0, InterfaceKind.OTHER), Triple(0, 0, InterfaceKind.OTHER), Triple(144, 0, InterfaceKind.OTHER),
        )
        for ((t, m, k) in table) {
            assertEquals(k, InterfaceKinds.fromIfType(t, m), "IfType=$t medium=$m")
            laws.hit("kind-classify")
        }
    }

    @Test
    fun `a cellular adapter is refused for LAN and for overlay, whatever its address and whatever was confirmed`() {
        for ((addr, kind) in listOf(
            "10.20.30.40" to PeerPathKind.LAN, "172.20.10.2" to PeerPathKind.LAN, "192.168.43.7" to PeerPathKind.LAN, "100.70.1.2" to PeerPathKind.OVERLAY,
            "100.64.0.1" to PeerPathKind.OVERLAY, "fd7a:115c:a1e0::5" to PeerPathKind.OVERLAY, "203.0.113.9" to PeerPathKind.LAN,
        )) {
            val nics = listOf(nic("Cellular", 3, addr, kind = InterfaceKind.CELLULAR))
            val r = InterfaceEligibility.select("Cellular", kind, setOf("Cellular"), nics)
            assertEquals(IneligibleReason.INTERFACE_KIND, reason(r), "$kind $addr")
            laws.hit("kind-select-refuse")
        }
    }

    @Test
    fun `an adapter that could not be classified is never bound`() {
        val nics = listOf(nic("Mystery", 3, "192.168.1.2", kind = InterfaceKind.UNKNOWN), nic("Mystery2", 4, "100.101.1.1", kind = InterfaceKind.UNKNOWN))
        assertEquals(IneligibleReason.INTERFACE_KIND, reason(InterfaceEligibility.select("Mystery", PeerPathKind.LAN, setOf("Mystery"), nics)))
        assertEquals(IneligibleReason.INTERFACE_KIND, reason(InterfaceEligibility.select("Mystery2", PeerPathKind.OVERLAY, emptySet(), nics)))
        laws.hit("kind-select-refuse")
        laws.hit("kind-select-refuse")
    }

    @Test
    fun `CGNAT on a Wi-Fi or Ethernet adapter is not the overlay, and a tunnel adapter is not a LAN`() {
        val nics = listOf(
            nic("Wi-Fi", 7, "100.70.1.2", kind = InterfaceKind.WIFI),
            nic("Ethernet", 5, "100.80.1.2", kind = InterfaceKind.ETHERNET),
            nic("Tailscale", 12, "100.101.102.103", kind = InterfaceKind.TUNNEL),
            nic("Other", 13, "192.168.5.5", kind = InterfaceKind.OTHER),
        )
        assertEquals(IneligibleReason.INTERFACE_KIND, reason(InterfaceEligibility.select("Wi-Fi", PeerPathKind.OVERLAY, emptySet(), nics)))
        assertEquals(IneligibleReason.INTERFACE_KIND, reason(InterfaceEligibility.select("Ethernet", PeerPathKind.OVERLAY, emptySet(), nics)))
        assertEquals(IneligibleReason.INTERFACE_KIND, reason(InterfaceEligibility.select("Other", PeerPathKind.LAN, setOf("Other"), nics)))
        val lanOnTunnel = listOf(nic("Tailscale", 12, "192.168.7.7", kind = InterfaceKind.TUNNEL))
        assertEquals(IneligibleReason.INTERFACE_KIND, reason(InterfaceEligibility.select("Tailscale", PeerPathKind.LAN, setOf("Tailscale"), lanOnTunnel)))
        repeat(4) { laws.hit("kind-select-refuse") }
        assertTrue(InterfaceEligibility.select("Tailscale", PeerPathKind.OVERLAY, emptySet(), nics) is SelectionResult.Selected)
        assertTrue(InterfaceEligibility.select("Wi-Fi", PeerPathKind.LAN, setOf("Wi-Fi"), listOf(nic("Wi-Fi", 7, "10.4.5.6", kind = InterfaceKind.WIFI))) is SelectionResult.Selected)
        assertTrue(InterfaceEligibility.select("Ethernet", PeerPathKind.LAN, setOf("Ethernet"), listOf(nic("Ethernet", 5, "192.168.1.9", kind = InterfaceKind.ETHERNET))) is SelectionResult.Selected)
        repeat(3) { laws.hit("kind-select-accept") }
    }

    @Test
    fun `every bind re-checks the adapter type, so an adapter that turns out to be cellular drains the listener`() {
        val ok = listOf(nic("Ethernet", 4, "192.168.1.2", kind = InterfaceKind.ETHERNET))
        val sel = (InterfaceEligibility.select("Ethernet", PeerPathKind.LAN, setOf("Ethernet"), ok) as SelectionResult.Selected).selection
        assertTrue(InterfaceEligibility.check(sel, ok, setOf("Ethernet")) is Eligibility.Eligible)
        for (k in listOf(InterfaceKind.CELLULAR, InterfaceKind.UNKNOWN, InterfaceKind.TUNNEL, InterfaceKind.OTHER)) {
            val now = listOf(nic("Ethernet", 4, "192.168.1.2", kind = k))
            val e = InterfaceEligibility.check(sel, now, setOf("Ethernet"))
            assertTrue(e is Eligibility.Ineligible && e.reason == IneligibleReason.INTERFACE_KIND, "$k: ${(e as? Eligibility.Ineligible)?.reason}")
            laws.hit("kind-check")
        }
        val tun = listOf(nic("Tailscale", 12, "100.101.102.103", kind = InterfaceKind.TUNNEL))
        val osel = (InterfaceEligibility.select("Tailscale", PeerPathKind.OVERLAY, emptySet(), tun) as SelectionResult.Selected).selection
        val wifi = listOf(nic("Tailscale", 12, "100.101.102.103", kind = InterfaceKind.WIFI))
        val e = InterfaceEligibility.check(osel, wifi, emptySet())
        assertTrue(e is Eligibility.Ineligible && e.reason == IneligibleReason.INTERFACE_KIND)
        laws.hit("kind-check")
    }
}
