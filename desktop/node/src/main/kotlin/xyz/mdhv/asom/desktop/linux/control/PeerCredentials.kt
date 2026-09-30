package xyz.mdhv.asom.desktop.linux.control

import java.net.StandardProtocolFamily
import java.nio.channels.SocketChannel
import jdk.net.ExtendedSocketOptions

/**
 * What the kernel says about the process at the other end of an AF_UNIX stream socket (SO_PEERCRED). The JDK exposes a
 * user NAME and a primary group NAME, not numbers (linux.md 7.3, LF25); a uid the JDK cannot resolve arrives as its
 * decimal string, which no allow-list contains, so it is denied.
 */
data class PeerPrincipal(val user: String, val group: String)

interface PeerCredentialReader {
    /** False when this runtime cannot read peer credentials; the control socket then refuses to start. */
    fun isSupported(): Boolean

    /** The peer's credentials, or null when they cannot be read. Null is always a denial. */
    fun read(channel: SocketChannel): PeerPrincipal?
}

/**
 * The real reader: `jdk.net.ExtendedSocketOptions.SO_PEERCRED`, present on JDK 17 and 21 (both probed on Linux,
 * desktop/ERRATA.md ERR-DL2-1). There is no weaker fallback (no path-permission-only mode).
 */
object SoPeerCred : PeerCredentialReader {
    override fun isSupported(): Boolean = try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ExtendedSocketOptions.SO_PEERCRED in it.supportedOptions() }
    } catch (_: Exception) {
        false
    }

    override fun read(channel: SocketChannel): PeerPrincipal? = try {
        val p = channel.getOption(ExtendedSocketOptions.SO_PEERCRED)
        PeerPrincipal(p.user().name, p.group().name)
    } catch (_: Exception) {
        null
    }
}

/** Decides whether a peer may use the control socket. */
fun interface PeerAuthorizer {
    fun authorize(peer: PeerPrincipal): Boolean
}

object PeerAuthorizers {
    /** USER and FOREGROUND mode: only the node's own user (linux.md 7.3). */
    fun sameUser(userName: String): PeerAuthorizer {
        require(userName.isNotEmpty())
        return PeerAuthorizer { it.user == userName }
    }

    /**
     * SYSTEM mode: the service user itself, or a member of the service group. SO_PEERCRED reports only the peer's
     * PRIMARY group, so supplementary members are found from the group database's member list ([membersOf]); a
     * membership held only in a directory service that `/etc/group` does not list is not seen and is denied (fail
     * closed, ERR-DL2-2).
     */
    fun serviceGroup(serviceUser: String, serviceGroup: String, membersOf: (String) -> Set<String>): PeerAuthorizer {
        require(serviceUser.isNotEmpty() && serviceGroup.isNotEmpty())
        return PeerAuthorizer { p -> p.user == serviceUser || p.group == serviceGroup || p.user in membersOf(serviceGroup) }
    }

    /** Members listed for [group] in `/etc/group`-format text: the fourth field, comma separated. */
    fun membersFromGroupFile(text: String?, group: String): Set<String> {
        if (text == null) return emptySet()
        for (line in text.lineSequence()) {
            val f = line.split(':')
            if (f.size >= 4 && f[0] == group) return f[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        return emptySet()
    }
}
