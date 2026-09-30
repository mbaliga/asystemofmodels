package xyz.mdhv.asom.desktop.win

import java.nio.file.Files
import java.nio.file.Path
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.WinAcl

/**
 * The directory layout of windows.md 3.2, as strings (Windows syntax, so it can be computed and tested on any OS).
 * Layout: `<base>\ledger`, `<base>\registry`, `<base>\node` (identity), `<base>\run\ctl.sock`, `<base>\diag`, `<base>\config.json`.
 * User mode and FOREGROUND: `%LOCALAPPDATA%\asom` (non-roaming). Service mode (SYSTEM): `%ProgramData%\asom`.
 */
data class WinLayout(
    val mode: HostMode,
    val base: String,
    val ledgerDir: String,
    val registryDir: String,
    val identityDir: String,
    val runDir: String,
    val controlSocket: String,
    val diagDir: String,
    val configFile: String,
)

object WinPaths {
    /** `sockaddr_un.sun_path` is 108 bytes on Windows. The count is over UTF-8, an upper bound of the ANSI encoding the OS uses. */
    const val MAX_SOCKET_PATH_BYTES = 107

    private val localAbsolute = Regex("^[A-Za-z]:\\\\[^\\u0000-\\u001f<>\"|?*]*$")

    /** Pure: computes strings, creates nothing. Throws [HostRefusedException] for anything that is not a plain local absolute path. */
    fun layout(mode: HostMode, vars: Map<String, String>): WinLayout {
        val (varName, root) = when (mode) {
            HostMode.SYSTEM -> "ProgramData" to vars["ProgramData"]
            HostMode.USER, HostMode.FOREGROUND -> "LOCALAPPDATA" to vars["LOCALAPPDATA"]
        }
        val raw = root?.trim()?.trimEnd('\\')
        if (raw.isNullOrEmpty()) throw HostRefusedException("%$varName% is not set; the node refuses to guess a state directory")
        if (!localAbsolute.matches("$raw\\")) throw HostRefusedException("%$varName% is not a plain local absolute path (UNC, device and relative paths are refused): $raw")
        if (raw.split('\\').any { it == ".." || it == "." }) throw HostRefusedException("%$varName% contains a relative segment: $raw")
        if (raw.contains("\\appdata\\roaming", ignoreCase = true)) throw HostRefusedException("%$varName% is under a roaming profile; node state must not roam: $raw")
        val base = "$raw\\asom"
        val layout = WinLayout(
            mode = mode,
            base = base,
            ledgerDir = "$base\\ledger",
            registryDir = "$base\\registry",
            identityDir = "$base\\node",
            runDir = "$base\\run",
            controlSocket = "$base\\run\\ctl.sock",
            diagDir = "$base\\diag",
            configFile = "$base\\config.json",
        )
        val bytes = layout.controlSocket.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_SOCKET_PATH_BYTES) {
            throw HostRefusedException("control socket path is $bytes bytes, over the AF_UNIX limit of $MAX_SOCKET_PATH_BYTES: ${layout.controlSocket}")
        }
        return layout
    }

    fun toNodePaths(l: WinLayout): NodePaths = NodePaths(
        mode = l.mode,
        stateDir = Path.of(l.base),
        dataDir = Path.of(l.base),
        ledgerDir = Path.of(l.ledgerDir),
        identityDir = Path.of(l.identityDir),
        runtimeDir = Path.of(l.runDir),
        controlSocket = Path.of(l.controlSocket),
    )

    /**
     * Creates the directories and applies the DACL of [plan]. The node calls it only when it is about to write, never
     * from `paths()`. The identity directory also gets a `CACHEDIR.TAG` (restic, borg `--exclude-caches`); Windows Backup
     * and OneDrive Known Folder Move do not sync `%LOCALAPPDATA%` by default, which is assumption AW16 (owner check).
     */
    fun prepare(paths: NodePaths, acl: WinAcl, plan: AclPlan, runPlan: AclPlan = plan) {
        val dirs = listOf(paths.stateDir, paths.ledgerDir, paths.identityDir, paths.runtimeDir)
        for (d in dirs) {
            Files.createDirectories(d)
            acl.replace(d, if (d == paths.runtimeDir) runPlan.required else plan.required)
        }
        val tag = paths.identityDir.resolve("CACHEDIR.TAG")
        if (!Files.exists(tag)) {
            Files.writeString(tag, "Signature: 8a477f597d28d172789f06886806bc55\n# This directory holds the asom node identity; do not back it up (asom).\n", Charsets.UTF_8)
        }
    }
}
