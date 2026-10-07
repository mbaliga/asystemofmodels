package xyz.mdhv.asom.desktop.mac.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

data class InterfaceSnapshot(val name: String, val isUp: Boolean, val addresses: List<InetAddress>)

fun interface InterfaceLister {
    fun list(): List<InterfaceSnapshot>
}

/** Reads the JDK's view of the interfaces. Nothing is bound and nothing is opened. */
object JdkInterfaceLister : InterfaceLister {
    override fun list(): List<InterfaceSnapshot> {
        val all = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        return all.toList().map { ni ->
            InterfaceSnapshot(ni.name, runCatching { ni.isUp }.getOrDefault(false), ni.inetAddresses.toList())
        }
    }
}

enum class InterfaceKind {
    /** `utunN`: the kernel TUN device an overlay (Tailscale, Headscale) uses. */
    OVERLAY_CANDIDATE,

    /** `enN`: Wi-Fi and Ethernet, the only interfaces on which the Local Network privilege applies. */
    LAN_CANDIDATE,
    LOOPBACK,

    /** Bridges, AWDL, low-latency WLAN, cellular (`pdp_ip`), IPsec, tunnels, personal-hotspot links, and anything unknown. */
    OTHER,
}

/** The vocabulary of the design's `DIAL` outcome (LAB_SPEC 7.6); this classifier produces only the three it can tell apart. */
enum class DialOutcome(val wire: String) {
    CONNECTED("connected"),
    REFUSED("refused"),
    TIMEOUT("timeout"),
    PIN_MISMATCH("pin-mismatch"),
    NOT_TLS("not-tls"),
    LOCAL_NETWORK_DENIED("local-network-denied"),
    FIREWALL_BLOCKED("firewall-blocked"),
}

/** A dial outcome derived from a JVM exception. [inferred] is true when it rests on an assumption (AM09). */
class ClassifiedDial(val outcome: DialOutcome, val inferred: Boolean, val explanation: String)

/** A listener binding: the interface, the one address on it, and what kind of network it is. */
data class ListenBinding(val kind: InterfaceKind, val interfaceName: String, val address: InetAddress)

sealed interface Eligibility {
    class Bind(val binding: ListenBinding) : Eligibility
    class Refuse(val reason: String) : Eligibility
}

sealed interface Reresolution {
    /** The same interface still carries the same address: nothing changed. */
    data object Same : Reresolution

    /** The overlay address moved to another `utun` (numbering is not stable across reboots): the node must drain and re-bind. */
    class Moved(val binding: ListenBinding) : Reresolution

    /** The selected interface is gone, down, lost its address, or is no longer eligible: DRAINING, and no re-bind. */
    class Lost(val reason: String) : Reresolution
}

/**
 * Which interfaces and addresses the peer listener may ever bind (macos.md 4.1). Only addresses on the interface the user
 * selected: the overlay's `utun` (a Tailscale 100.64.0.0/10 or fd7a:115c:a1e0::/48 address) and/or a Wi-Fi or Ethernet `en*` on a
 * network the user confirmed, at private or link-local addresses. NEVER a wildcard, loopback or public address, whatever the
 * caller asks for. This module only decides; it binds nothing.
 *
 * `utun` numbering is not stable, so an overlay selection is recorded as (kind = overlay, the selected ADDRESS) and is re-resolved
 * on every wake ([reresolve]) only to the `utun` that carries that exact address: a prefix cannot tell Tailscale from another
 * VPN that uses the same carrier-grade NAT range, so an address that is gone is `Lost` and the user selects again. If no kernel `utun` carries an overlay address (for example `tailscaled` in userspace-networking mode),
 * the answer is "overlay mode unsupported: use a kernel TUN", and loopback is never used as a substitute.
 */
object InterfaceEligibility {
    private val UTUN = Regex("^utun[0-9]+$")
    private val EN = Regex("^en[0-9]+$")

    fun kindOf(name: String): InterfaceKind = when {
        UTUN.matches(name) -> InterfaceKind.OVERLAY_CANDIDATE
        EN.matches(name) -> InterfaceKind.LAN_CANDIDATE
        name == "lo0" -> InterfaceKind.LOOPBACK
        else -> InterfaceKind.OTHER
    }

    private fun unsigned(b: Byte) = b.toInt() and 0xff

    /** 100.64.0.0/10 or fd7a:115c:a1e0::/48. Built from bytes so no dotted text of a wildcard address appears in source. */
    fun isOverlayAddress(a: InetAddress): Boolean {
        val b = a.address
        return when (a) {
            is Inet4Address -> unsigned(b[0]) == 100 && (unsigned(b[1]) and 0xC0) == 64
            is Inet6Address -> b.size == 16 && unsigned(b[0]) == 0xfd && unsigned(b[1]) == 0x7a && unsigned(b[2]) == 0x11 && unsigned(b[3]) == 0x5c && unsigned(b[4]) == 0xa1 && unsigned(b[5]) == 0xe0
            else -> false
        }
    }

    /**
     * A private or link-local unicast address that is not the overlay's: IPv4 10/8, 172.16/12, 192.168/16 and 169.254/16; IPv6
     * fe80::/10 (only with a scope, since it names no interface otherwise) and fc00::/7 (ULA) minus the overlay block.
     * The 10/8 test is on the first byte so that no dotted wildcard text is written.
     */
    fun isLanAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isMulticastAddress || isOverlayAddress(a)) return false
        val b = a.address
        return when (a) {
            is Inet4Address -> unsigned(b[0]) == 10 ||
                (unsigned(b[0]) == 172 && (unsigned(b[1]) and 0xF0) == 16) ||
                (unsigned(b[0]) == 192 && unsigned(b[1]) == 168) ||
                (unsigned(b[0]) == 169 && unsigned(b[1]) == 254)
            is Inet6Address -> when {
                b.size != 16 -> false
                unsigned(b[0]) == 0xfe && (unsigned(b[1]) and 0xC0) == 0x80 -> a.scopedInterface != null || a.scopeId > 0
                (unsigned(b[0]) and 0xFE) == 0xFC -> true
                else -> false
            }
            else -> false
        }
    }

    /**
     * The `utun` the overlay listener may bind. 100.64.0.0/10 is shared carrier-grade NAT space that other VPNs also use
     * (Cloudflare WARP takes 100.96.0.0/12), so an address inside it does not say WHICH VPN it belongs to, and the node never
     * picks between interfaces on its own (mac ERRATA ERR-FX-HWM-8):
     *  - with a [selected] address (the one the user confirmed): only the `utun` that carries exactly that address, else refused;
     *  - with none: the one `utun` that carries an overlay address, when there is exactly one; two or more are refused and
     *    named, and the user selects. Within the one interface IPv4 is preferred over IPv6.
     */
    fun resolveOverlay(interfaces: List<InterfaceSnapshot>, selected: InetAddress? = null): Eligibility {
        val carriers = interfaces.filter { it.isUp && kindOf(it.name) == InterfaceKind.OVERLAY_CANDIDATE }
            .map { i -> i to i.addresses.filter(::isOverlayAddress) }
            .filter { it.second.isNotEmpty() }
        if (selected != null) {
            val owner = carriers.firstOrNull { (_, addrs) -> selected in addrs }
                ?: return Eligibility.Refuse("no up utun interface carries the selected overlay address ${selected.hostAddress}; select the overlay interface again")
            return Eligibility.Bind(ListenBinding(InterfaceKind.OVERLAY_CANDIDATE, owner.first.name, selected))
        }
        if (carriers.isEmpty()) {
            return Eligibility.Refuse("overlay mode unsupported: use a kernel TUN (no utun interface carries an overlay address; a userspace-networking tailscaled is not usable, and loopback is never used instead)")
        }
        if (carriers.size > 1) {
            return Eligibility.Refuse(
                "more than one utun interface carries a 100.64.0.0/10 or Tailscale address (${carriers.map { it.first.name }.sorted().joinToString(", ")}); " +
                    "another VPN may share that range, so select the overlay interface explicitly",
            )
        }
        val (iface, addrs) = carriers.single()
        val address = addrs.sortedBy { it !is Inet4Address }.first()
        return Eligibility.Bind(ListenBinding(InterfaceKind.OVERLAY_CANDIDATE, iface.name, address))
    }

    /** A Wi-Fi or Ethernet interface the user confirmed, at a private or link-local address. */
    fun resolveLan(interfaces: List<InterfaceSnapshot>, interfaceName: String, lanConfirmed: Boolean): Eligibility {
        if (!lanConfirmed) return Eligibility.Refuse("this network is not confirmed; confirm the interface, its addresses and the Wi-Fi network name first")
        if (kindOf(interfaceName) != InterfaceKind.LAN_CANDIDATE) return Eligibility.Refuse("$interfaceName is not a Wi-Fi or Ethernet interface (en*)")
        val i = interfaces.firstOrNull { it.name == interfaceName } ?: return Eligibility.Refuse("interface $interfaceName does not exist")
        if (!i.isUp) return Eligibility.Refuse("interface $interfaceName is down")
        val addr = i.addresses.filter(::isLanAddress).sortedBy { it !is Inet4Address }.firstOrNull()
            ?: return Eligibility.Refuse("interface $interfaceName has no private or link-local address")
        return Eligibility.Bind(ListenBinding(InterfaceKind.LAN_CANDIDATE, interfaceName, addr))
    }

    /** Re-resolves a recorded selection after a wake or a change of network. A change of anything the listener depends on is not "Same". */
    fun reresolve(previous: ListenBinding, interfaces: List<InterfaceSnapshot>, lanConfirmed: Boolean): Reresolution = when (previous.kind) {
        InterfaceKind.OVERLAY_CANDIDATE -> when (val e = resolveOverlay(interfaces, previous.address)) {
            is Eligibility.Refuse -> Reresolution.Lost(e.reason)
            is Eligibility.Bind -> if (e.binding == previous) Reresolution.Same else Reresolution.Moved(e.binding)
        }
        InterfaceKind.LAN_CANDIDATE -> {
            val i = interfaces.firstOrNull { it.name == previous.interfaceName }
            when {
                i == null || !i.isUp -> Reresolution.Lost("interface ${previous.interfaceName} is gone or down")
                !lanConfirmed -> Reresolution.Lost("the LAN confirmation was withdrawn")
                previous.address !in i.addresses -> Reresolution.Lost("interface ${previous.interfaceName} no longer carries ${previous.address.hostAddress}")
                !isLanAddress(previous.address) -> Reresolution.Lost("${previous.address.hostAddress} is no longer a private or link-local address")
                else -> Reresolution.Same
            }
        }
        else -> Reresolution.Lost("${previous.interfaceName} is not an interface the listener may bind")
    }

    /**
     * Whether an address may be bound at all, whatever the interface says. The last line of defence: a wildcard, loopback,
     * multicast or public address is refused even if a caller composed a binding by hand.
     */
    fun isBindable(a: InetAddress): Boolean = isOverlayAddress(a) || isLanAddress(a)

    /**
     * The `DIAL` outcome for a failed connect (macos.md 4.2, assumption AM09, spike S-M7: UNVERIFIED on a real Mac).
     * A refused connection and a timeout are what they say. "No route to host" (EHOSTUNREACH) to a LAN address is written as
     * `local-network-denied`, because that is how macOS 15 blocks an app without the Local Network privilege; it is an INFERENCE
     * (a really unreachable host gives the same error), so [ClassifiedDial.inferred] is true and the explanation says both.
     * Overlay traffic is never "local network" (a VPN interface is excluded), so an overlay destination is never classified as denied.
     */
    fun classifyDial(e: Throwable, destination: InetAddress): ClassifiedDial? {
        val message = e.message.orEmpty()
        return when {
            e is SocketTimeoutException || message.contains("timed out", ignoreCase = true) ->
                ClassifiedDial(DialOutcome.TIMEOUT, false, "the connect timed out")
            message.contains("Connection refused", ignoreCase = true) ->
                ClassifiedDial(DialOutcome.REFUSED, false, "the peer refused the connection")
            (e is NoRouteToHostException || message.contains("No route to host", ignoreCase = true)) && isLanAddress(destination) ->
                ClassifiedDial(
                    DialOutcome.LOCAL_NETWORK_DENIED, true,
                    "No route to host to a local-network address: macOS may have blocked this app (System Settings > Privacy & Security > Local Network), or the host is really unreachable (assumption AM09)",
                )
            else -> null
        }
    }
}
