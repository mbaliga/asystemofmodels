package xyz.mdhv.asom.desktop.linux.host

import xyz.mdhv.asom.desktop.HostMode

/** Ownership and permission bits of a directory (permissions as the low nine bits, e.g. 0b111000000 for 0700). */
data class DirMeta(val ownerUid: Int, val permissions: Int)

/** What the mode rules look at. All of it is injected so the rules are pure and testable. */
data class HostCheckInput(
    val realUid: Int,
    val effectiveUid: Int,
    val userName: String,
    val env: Map<String, String>,
    val osRelease: OsRelease,
    val runtimeDir: DirMeta?,
)

/**
 * Mode detection and refusal rules (linux.md 3.1).
 *  - The node refuses to run as root in ANY mode (real or effective uid 0).
 *  - SYSTEM requires user name `asom` and INVOCATION_ID set (started by systemd); it is not offered on SteamOS.
 *  - USER requires XDG_RUNTIME_DIR owned by the invoking uid with mode 0700.
 *  - FOREGROUND has no further requirement (development and PF lending); it uses the USER layout.
 */
object LinuxHostModeRules {
    const val SYSTEM_USER = "asom"

    /** The refusal text, or null when [mode] may run. */
    fun refusal(mode: HostMode, i: HostCheckInput): String? {
        if (i.realUid == 0 || i.effectiveUid == 0) return "refusing to run as root (uid 0) in any mode"
        return when (mode) {
            HostMode.SYSTEM -> when {
                i.osRelease.isSteamOS -> "SYSTEM mode is not offered on SteamOS (no root-managed install survives an update); use --mode=user"
                i.userName != SYSTEM_USER -> "SYSTEM mode must run as user \"$SYSTEM_USER\", not \"${i.userName}\""
                i.env["INVOCATION_ID"].isNullOrEmpty() -> "SYSTEM mode must be started by systemd (INVOCATION_ID is not set)"
                else -> null
            }
            HostMode.USER -> {
                val dir = i.runtimeDir
                when {
                    i.env["XDG_RUNTIME_DIR"].isNullOrEmpty() -> "USER mode needs XDG_RUNTIME_DIR"
                    dir == null -> "USER mode: XDG_RUNTIME_DIR does not exist or cannot be inspected"
                    dir.ownerUid != i.realUid -> "USER mode: XDG_RUNTIME_DIR is not owned by the invoking uid"
                    dir.permissions != 0b111_000_000 -> "USER mode: XDG_RUNTIME_DIR must have mode 0700"
                    else -> null
                }
            }
            HostMode.FOREGROUND -> null
        }
    }
}
