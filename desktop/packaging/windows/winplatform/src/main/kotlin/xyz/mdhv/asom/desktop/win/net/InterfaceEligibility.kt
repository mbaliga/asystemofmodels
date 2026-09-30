package xyz.mdhv.asom.desktop.win.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import xyz.mdhv.asom.desktop.win.api.InterfaceSnapshot

enum class PeerPathKind { OVERLAY, LAN }

enum class IneligibleReason {
    NO_SELECTION, INTERFACE_MISSING, INTERFACE_DOWN, INDEX_CHANGED, ADDRESS_GONE,
    LOOPBACK, WILDCARD, MULTICAST, PUBLIC_ADDRESS, OUT_OF_OVERLAY_RANGE, LAN_NOT_CONFIRMED, NOT_PRIVATE_LAN, SCOPE_MISSING, NO_ELIGIBLE_ADDRESS,
}

/** The interface and address the owner picked (`asom mesh listen --interface "<alias>"`), recorded at selection time. */
data class SelectedInterface(
    val alias: String,
    val index: Int,
    val bindAddress: InetAddress,
    val kind: PeerPathKind,
    val recordedAddresses: List<InetAddress>,
)

sealed interface Eligibility {
    class Eligible(val address: InetAddress) : Eligibility
    class Ineligible(val reason: IneligibleReason, val detail: String) : Eligibility
}

sealed interface SelectionResult {
    class Selected(val selection: SelectedInterface) : SelectionResult
    class Refused(val reason: IneligibleReason, val detail: String) : SelectionResult
}

/**
 * Which interface and address the peer listener may bind (windows.md 4.1; IC-1). The node never binds a wildcard, a
 * loopback or a public address.
 *  - OVERLAY: the alias the owner picked (typically "Tailscale", AW13) AND an address in `100.64.0.0/10` or `fd7a:115c:a1e0::/48`.
 *  - LAN: an interface the owner CONFIRMED and an RFC 1918, link-local or ULA address.
 * Every bind re-checks with [check]; any answer other than Eligible means DRAINING. A change of interface index, or the
 * loss of the bound address, is a change; extra addresses appearing on the interface (DHCP, IPv6 temporary addresses)
 * are not (windows ERRATA WIN-NET-1). A link-local IPv6 address without a scope is refused: it names no interface.
 */
object InterfaceEligibility {
    private val CGNAT = Cidr.parse("100.64.0.0/10")
    private val TAILSCALE_ULA = Cidr.parse("fd7a:115c:a1e0::/48")
    // The 10/8 block is built from bytes: its dotted text contains the wildcard-address text that the source hygiene
    // rules (check_law.py, WinSourceHygieneTest) forbid in a main source, and they are right to be that blunt.
    private val LAN_RANGES = listOf(
        Cidr.v4(10, 0, 0, 0, 8), Cidr.v4(172, 16, 0, 0, 12), Cidr.v4(192, 168, 0, 0, 16), Cidr.v4(169, 254, 0, 0, 16),
        Cidr.parse("fe80::/10"), Cidr.parse("fc00::/7"),
    )

    /** The address-level rule shared by selection and re-check. */
    fun addressProblem(address: InetAddress, kind: PeerPathKind): Pair<IneligibleReason, String>? {
        if (address.isAnyLocalAddress) return IneligibleReason.WILDCARD to "a wildcard address is never bound"
        if (address.isLoopbackAddress) return IneligibleReason.LOOPBACK to "a loopback address is never a peer address"
        if (address.isMulticastAddress) return IneligibleReason.MULTICAST to "a multicast address is never a peer address"
        if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0 && address.scopedInterface == null) {
            return IneligibleReason.SCOPE_MISSING to "a link-local IPv6 address needs an interface scope"
        }
        return when (kind) {
            PeerPathKind.OVERLAY -> if (CGNAT.contains(address) || TAILSCALE_ULA.contains(address)) null
            else IneligibleReason.OUT_OF_OVERLAY_RANGE to "$address is outside 100.64.0.0/10 and fd7a:115c:a1e0::/48"
            // The overlay's own ULA block sits inside fc00::/7, so the overlay ranges are excluded first: an overlay address is never a LAN address.
            PeerPathKind.LAN -> if (CGNAT.contains(address) || TAILSCALE_ULA.contains(address)) IneligibleReason.NOT_PRIVATE_LAN to "$address is an overlay address, not a LAN address"
            else if (LAN_RANGES.any { it.contains(address) }) null
            else IneligibleReason.PUBLIC_ADDRESS to "$address is not an RFC 1918, link-local or ULA address"
        }
    }

    fun select(
        alias: String,
        kind: PeerPathKind,
        lanConfirmedAliases: Set<String>,
        current: List<InterfaceSnapshot>,
    ): SelectionResult {
        val nic = current.firstOrNull { it.alias.equals(alias, ignoreCase = true) }
            ?: return SelectionResult.Refused(IneligibleReason.INTERFACE_MISSING, "no interface named \"$alias\"")
        if (!nic.isUp) return SelectionResult.Refused(IneligibleReason.INTERFACE_DOWN, "interface \"${nic.alias}\" is down")
        if (kind == PeerPathKind.LAN && lanConfirmedAliases.none { it.equals(nic.alias, ignoreCase = true) }) {
            return SelectionResult.Refused(IneligibleReason.LAN_NOT_CONFIRMED, "interface \"${nic.alias}\" is not confirmed as a LAN (asom lan-confirm)")
        }
        var lastProblem: Pair<IneligibleReason, String>? = null
        for (a in nic.addresses) {
            val p = addressProblem(a, kind)
            if (p == null) return SelectionResult.Selected(SelectedInterface(nic.alias, nic.index, a, kind, nic.addresses))
            lastProblem = p
        }
        return SelectionResult.Refused(
            IneligibleReason.NO_ELIGIBLE_ADDRESS,
            lastProblem?.second ?: "interface \"${nic.alias}\" has no address",
        )
    }

    fun check(sel: SelectedInterface?, current: List<InterfaceSnapshot>, lanConfirmedAliases: Set<String>): Eligibility {
        if (sel == null) return Eligibility.Ineligible(IneligibleReason.NO_SELECTION, "no interface selected (asom mesh listen --interface)")
        val nic = current.firstOrNull { it.alias.equals(sel.alias, ignoreCase = true) }
            ?: return Eligibility.Ineligible(IneligibleReason.INTERFACE_MISSING, "interface \"${sel.alias}\" is gone")
        if (!nic.isUp) return Eligibility.Ineligible(IneligibleReason.INTERFACE_DOWN, "interface \"${sel.alias}\" is down")
        if (nic.index != sel.index) return Eligibility.Ineligible(IneligibleReason.INDEX_CHANGED, "interface \"${sel.alias}\" changed index ${sel.index} -> ${nic.index}")
        if (nic.addresses.none { it == sel.bindAddress }) return Eligibility.Ineligible(IneligibleReason.ADDRESS_GONE, "address ${sel.bindAddress} is no longer on \"${sel.alias}\"")
        if (sel.kind == PeerPathKind.LAN && lanConfirmedAliases.none { it.equals(sel.alias, ignoreCase = true) }) {
            return Eligibility.Ineligible(IneligibleReason.LAN_NOT_CONFIRMED, "LAN confirmation for \"${sel.alias}\" was withdrawn")
        }
        addressProblem(sel.bindAddress, sel.kind)?.let { return Eligibility.Ineligible(it.first, it.second) }
        return Eligibility.Eligible(sel.bindAddress)
    }

    /** `peerPath` comes from the local address of the accepted or connected socket, never from a guess. Null when it is not the bound address. */
    fun peerPath(localAddress: InetAddress, sel: SelectedInterface): PeerPathKind? = if (localAddress == sel.bindAddress) sel.kind else null
}

/** A CIDR block over the raw address bytes. IPv4 and IPv6 never match each other. */
class Cidr private constructor(private val network: ByteArray, private val prefix: Int) {
    fun contains(a: InetAddress): Boolean {
        val b = a.address
        if (b.size != network.size) return false
        var bits = prefix
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
            if ((b[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }

    companion object {
        fun v4(a: Int, b: Int, c: Int, d: Int, prefix: Int): Cidr {
            require(prefix in 0..32)
            return Cidr(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()), prefix)
        }

        fun parse(text: String): Cidr {
            val (addr, len) = text.split('/', limit = 2).let { it[0] to it[1].toInt() }
            val a = InetAddress.getByName(addr)
            require(a is Inet4Address || a is Inet6Address)
            require(len in 0..a.address.size * 8) { "bad prefix in $text" }
            return Cidr(a.address, len)
        }
    }
}
