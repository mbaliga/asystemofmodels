package xyz.mdhv.asom.desktop.win.jna

import java.net.NetworkInterface
import xyz.mdhv.asom.desktop.win.api.InterfaceKind
import xyz.mdhv.asom.desktop.win.api.InterfaceLister
import xyz.mdhv.asom.desktop.win.api.InterfaceSnapshot

/**
 * `IfType` and `PhysicalMediumType` from `MIB_IF_ROW2` to a kind (trust.md 6.6). Anything not named here is OTHER and is
 * refused; a mobile-broadband adapter is CELLULAR whatever `IfType` it reports (`NdisPhysicalMediumWirelessWan`), and PPP is
 * treated as cellular because dial-up and mobile modems present as PPP. TUNNEL covers `IF_TYPE_TUNNEL` (131),
 * `IF_TYPE_PROP_VIRTUAL` (53) and `IF_TYPE_OTHER` (1); which of them a given Wintun or WireGuard adapter reports is
 * NEEDS-DEVICE-VALIDATION.
 */
object InterfaceKinds {
    const val IF_TYPE_OTHER = 1
    const val IF_TYPE_ETHERNET_CSMACD = 6
    const val IF_TYPE_PPP = 23
    const val IF_TYPE_PROP_VIRTUAL = 53
    const val IF_TYPE_IEEE80211 = 71
    const val IF_TYPE_TUNNEL = 131
    const val IF_TYPE_WWANPP = 243
    const val IF_TYPE_WWANPP2 = 244
    const val PHYSICAL_MEDIUM_WIRELESS_WAN = 8

    fun fromIfType(ifType: Int, physicalMediumType: Int): InterfaceKind = when {
        physicalMediumType == PHYSICAL_MEDIUM_WIRELESS_WAN -> InterfaceKind.CELLULAR
        ifType == IF_TYPE_WWANPP || ifType == IF_TYPE_WWANPP2 || ifType == IF_TYPE_PPP -> InterfaceKind.CELLULAR
        ifType == IF_TYPE_ETHERNET_CSMACD -> InterfaceKind.ETHERNET
        ifType == IF_TYPE_IEEE80211 -> InterfaceKind.WIFI
        ifType == IF_TYPE_TUNNEL || ifType == IF_TYPE_PROP_VIRTUAL || ifType == IF_TYPE_OTHER -> InterfaceKind.TUNNEL
        else -> InterfaceKind.OTHER
    }
}

/**
 * The JDK's `NetworkInterface` names Windows adapters `eth0` and `wireless_32768`; the alias the owner knows ("Tailscale",
 * "Wi-Fi") is the friendly name, which the JDK does not expose. It is read from `GetIfEntry2` for the same interface index
 * (jna-platform's `IPHlpAPI.MIB_IF_ROW2.Alias`), together with the adapter type ([InterfaceKinds]). When that lookup fails the
 * JDK display name stands in with kind UNKNOWN, and an owner-picked alias will then simply not match, or be refused as an
 * unclassified adapter (the listener stays closed).
 */
class JdkInterfaceLister : InterfaceLister {
    override fun list(): List<InterfaceSnapshot> {
        val out = ArrayList<InterfaceSnapshot>()
        val nics = NetworkInterface.getNetworkInterfaces() ?: return out
        for (ni in nics) {
            val row = entry(ni.index)
            out += InterfaceSnapshot(
                alias = row?.first ?: ni.displayName ?: ni.name,
                index = ni.index,
                isUp = ni.isUp,
                addresses = ni.inetAddresses.toList(),
                kind = row?.second ?: InterfaceKind.UNKNOWN,
            )
        }
        return out
    }

    private fun entry(index: Int): Pair<String?, InterfaceKind>? = try {
        val row = com.sun.jna.platform.win32.IPHlpAPI.MIB_IF_ROW2()
        row.InterfaceIndex = index
        if (com.sun.jna.platform.win32.IPHlpAPI.INSTANCE.GetIfEntry2(row) == 0) {
            String(row.Alias).substringBefore('\u0000').ifBlank { null } to InterfaceKinds.fromIfType(row.Type, row.PhysicalMediumType)
        } else null
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}
