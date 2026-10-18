package xyz.mdhv.asom.desktop.win

import java.util.TreeMap

/** What the Windows host reads from the process. Replaced wholesale in tests. */
class WinEnv(
    val userName: String,
    vars: Map<String, String>,
    /** The SID of the account this process runs as, or null when it cannot be read. */
    val userSid: () -> String?,
) {
    /** Windows environment variable names are case-insensitive. */
    val vars: Map<String, String> = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).also { it.putAll(vars) }

    companion object {
        fun system(): WinEnv = WinEnv(
            userName = System.getProperty("user.name") ?: "",
            vars = System.getenv(),
            userSid = { xyz.mdhv.asom.desktop.win.jna.JnaIdentity.currentUserSid() },
        )
    }
}

/**
 * Windows-host options that have no place in the seam or in `NodeConfig`. Defaults are the spec's.
 *
 * @param serveWhileLoggedOut service mode only; OFF by default (M1 gate 10). With it off, a service node with no console
 *   session does not lend.
 * @param peerPort the peer listener port the firewall rule names (windows.md 4.2).
 */
data class WinOptions(
    val serveWhileLoggedOut: Boolean = false,
    val peerPort: Int = DEFAULT_PEER_PORT,
    val serviceName: String = SERVICE_NAME,
    val installDir: String? = null,
) {
    init {
        require(peerPort in 1024..65535) { "peer port out of range" }
    }

    companion object {
        const val DEFAULT_PEER_PORT = 11436
        const val SERVICE_NAME = "asom"
        const val HOLD_REASON = "asom: lending compute to your paired devices"
    }
}
