package xyz.mdhv.asom.desktop.win.jna

import java.net.NetworkInterface
import xyz.mdhv.asom.desktop.win.api.InterfaceLister
import xyz.mdhv.asom.desktop.win.api.InterfaceSnapshot

/**
 * The JDK's `NetworkInterface` names Windows adapters `eth0` and `wireless_32768`; the alias the owner knows ("Tailscale",
 * "Wi-Fi") is the friendly name, which the JDK does not expose. It is read from `GetIfEntry2` for the same interface index
 * (jna-platform's `IPHlpAPI.MIB_IF_ROW2.Alias`). When that lookup fails the JDK display name stands in, and an owner-picked
 * alias will then simply not match (the listener stays closed).
 */
class JdkInterfaceLister : InterfaceLister {
    override fun list(): List<InterfaceSnapshot> {
        val out = ArrayList<InterfaceSnapshot>()
        val nics = NetworkInterface.getNetworkInterfaces() ?: return out
        for (ni in nics) {
            out += InterfaceSnapshot(
                alias = friendlyName(ni.index) ?: ni.displayName ?: ni.name,
                index = ni.index,
                isUp = ni.isUp,
                addresses = ni.inetAddresses.toList(),
            )
        }
        return out
    }

    private fun friendlyName(index: Int): String? = try {
        val row = com.sun.jna.platform.win32.IPHlpAPI.MIB_IF_ROW2()
        row.InterfaceIndex = index
        if (com.sun.jna.platform.win32.IPHlpAPI.INSTANCE.GetIfEntry2(row) == 0) String(row.Alias).substringBefore('\u0000').ifBlank { null } else null
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}
