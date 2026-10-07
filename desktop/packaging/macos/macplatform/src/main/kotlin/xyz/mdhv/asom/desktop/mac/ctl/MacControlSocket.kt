package xyz.mdhv.asom.desktop.mac.ctl

import java.net.StandardProtocolFamily
import java.nio.channels.SocketChannel
import java.nio.file.Path
import jdk.net.ExtendedSocketOptions
import xyz.mdhv.asom.desktop.ControlSocketServer
import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.NotYetImplementedFeature
import xyz.mdhv.asom.desktop.PartiallyImplemented
import xyz.mdhv.asom.desktop.control.CallerIdentity
import xyz.mdhv.asom.desktop.control.ControlHandler
import xyz.mdhv.asom.desktop.mac.MacPaths

/**
 * What the kernel says about the process at the other end of an AF_UNIX stream socket. macOS answers with `getpeereid` (uid and gid,
 * no pid), which the JDK exposes as `SO_PEERCRED` (FM27). The JDK gives a user NAME and a primary group NAME, not numbers; a uid
 * it cannot resolve arrives as its decimal string, which never equals a real user name, so it is denied.
 */
data class PeerPrincipal(val user: String, val group: String)

interface PeerCredentialReader {
    /** False when this runtime cannot read peer credentials; the control socket then refuses to start. */
    fun isSupported(): Boolean

    /** The peer's credentials, or null when they cannot be read. Null is always a denial. */
    fun read(channel: SocketChannel): PeerPrincipal?
}

/** The real reader: `jdk.net.ExtendedSocketOptions.SO_PEERCRED`. There is no weaker fallback (no path-permission-only mode). */
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

/**
 * The owner-CLI control socket on macOS (macos.md 3.3 c, 7.2, FM27): an AF_UNIX socket at `<per-user temp dir>/xyz.mdhv.asom/ctl.sock`
 * (directory 0700), with the caller identified by uid only (`callerPkg = local-uid:<user>`). The check runs IN BOTH DIRECTIONS: the
 * node checks every client, and the CLI checks the server it connected to, so a socket planted by another user in a guessable
 * place is not trusted with a command. The socket is not in the group container, because the CLI runs under Terminal and may be
 * denied the container (AM08).
 *
 * WHAT THIS CLASS DOES NOT DO: it does not bind. [start] throws [NotYetImplementedException]. Two reasons, both recorded in mac ERRATA
 * MAC-CTL-2 (the same as windows WIN-CTL-1): `desktop/tools/check_law.py` and the desktop hygiene rules forbid a listener API in any main
 * source this wave, and the socket is built ahead of the open D23/D25 rulings (R3-CONFORMANCE-13), so nothing may listen by default.
 * The identity string, the peer check in both directions, the path limit and the directory rule are built and tested; `ControlSocketIT`
 * (macOS) binds a real AF_UNIX socket inside the test and verifies them with real peer credentials.
 *
 * STATED LIMIT: any process running as the owner passes the check, exactly as on Linux same-uid. Whether Invariant 5 governs the
 * owner CLI is ruled in D2/D25(a), not here (R2-CONFORMANCE-6).
 */
class MacControlSocket(override val path: Path) : ControlSocketServer, PartiallyImplemented {
    override fun start(handler: ControlHandler): AutoCloseable =
        throw NotYetImplementedException("control-socket bind (AF_UNIX)", "MC4 / D-v2 (D25, D23)")

    override val notYetImplemented: List<NotYetImplementedFeature> =
        listOf(NotYetImplementedFeature("control-socket bind (AF_UNIX)", "MC4 / D-v2 (D25, D23)") { start { _, _ -> error("unreachable") } })

    companion object {
        /** The ledger caller identity for an owner CLI connection: the same form as Linux, from the peer's user name. */
        fun callerIdentity(peer: PeerPrincipal): CallerIdentity = CallerIdentity("local-uid:${peer.user}")

        /** Null when [path] fits `sun_path` (at most 102 bytes, measured on macOS); otherwise the reason. Counted over UTF-8. */
        fun pathProblem(path: String): String? {
            val bytes = path.toByteArray(Charsets.UTF_8).size
            return if (bytes > MacPaths.MAX_SOCKET_PATH_BYTES) "socket path is $bytes bytes, over the limit of ${MacPaths.MAX_SOCKET_PATH_BYTES}" else null
        }

        /** The node's check of a connecting client: only the node's own user. A peer that cannot be read is denied. */
        fun authorizeClient(peer: PeerPrincipal?, ownUser: String): Boolean = peer != null && ownUser.isNotEmpty() && peer.user == ownUser

        /** The CLI's check of the server it connected to: the same rule, the other direction. */
        fun verifyServer(peer: PeerPrincipal?, ownUser: String): Boolean = authorizeClient(peer, ownUser)

        /** The rule for the directory that holds the socket: a real directory, owned by this user, mode 0700. Empty means it passed. */
        fun dirProblems(dir: Path, ownUid: Int?): List<String> = MacPaths.privateDirProblems(dir, ownUid)
    }
}
