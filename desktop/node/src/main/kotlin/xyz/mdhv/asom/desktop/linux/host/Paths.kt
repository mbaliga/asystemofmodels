package xyz.mdhv.asom.desktop.linux.host

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodePaths

/**
 * XDG and `/var/lib/asom` resolution (linux.md 3.1, 5). [resolve] is pure: it creates nothing. [prepare] creates the
 * directories 0700 and the identity directory's `CACHEDIR.TAG` (C7: tar, borg and restic `--exclude-caches` skip it);
 * the node calls it only when it is about to write.
 */
object LinuxPaths {
    /** AF_UNIX `sun_path` is 108 bytes on Linux including the NUL. */
    const val MAX_SOCKET_PATH_BYTES = 107
    private const val CACHEDIR_TAG_SIGNATURE = "Signature: 8a477f597d28d172789f06886806bc55"

    fun resolve(mode: HostMode, env: Map<String, String>, home: Path): NodePaths {
        val paths = when (mode) {
            HostMode.SYSTEM -> {
                val base = Path.of("/var/lib/asom")
                val runtime = Path.of("/run/asom")
                NodePaths(mode, base, base, base.resolve("ledger"), base.resolve("identity"), runtime, runtime.resolve("ctl.sock"))
            }
            HostMode.USER, HostMode.FOREGROUND -> {
                val state = xdg(env, "XDG_STATE_HOME") ?: home.resolve(".local/state")
                val data = xdg(env, "XDG_DATA_HOME") ?: home.resolve(".local/share")
                val stateDir = state.resolve("asom")
                val dataDir = data.resolve("asom")
                // FOREGROUND is a development and PF mode that may start in a container without a session; it falls back to a
                // private directory under the state dir (desktop/ERRATA.md ERR-HOST-2). USER mode was already refused
                // by LinuxHostModeRules if XDG_RUNTIME_DIR is missing.
                val xdgRuntime = xdg(env, "XDG_RUNTIME_DIR")
                val runtime = if (xdgRuntime != null) xdgRuntime.resolve("asom") else stateDir.resolve("run")
                NodePaths(mode, stateDir, dataDir, stateDir.resolve("ledger"), dataDir.resolve("identity"), runtime, runtime.resolve("ctl.sock"))
            }
        }
        val socketBytes = paths.controlSocket.toString().toByteArray(Charsets.UTF_8).size
        if (socketBytes > MAX_SOCKET_PATH_BYTES) {
            throw HostRefusedException("control socket path is $socketBytes bytes, over the AF_UNIX limit of $MAX_SOCKET_PATH_BYTES: ${paths.controlSocket}")
        }
        return paths
    }

    /** An XDG base directory is honoured only if it is an absolute path (the spec says relative values are invalid). */
    private fun xdg(env: Map<String, String>, key: String): Path? =
        env[key]?.takeIf { it.isNotEmpty() && it.startsWith("/") }?.let { Path.of(it) }

    fun prepare(paths: NodePaths) {
        for (d in listOf(paths.stateDir, paths.dataDir, paths.ledgerDir, paths.identityDir, paths.runtimeDir)) mkPrivate(d)
        val tag = paths.identityDir.resolve("CACHEDIR.TAG")
        if (!Files.exists(tag)) {
            Files.writeString(tag, "$CACHEDIR_TAG_SIGNATURE\n# This directory holds the asom node identity; do not back it up (asom).\n", Charsets.UTF_8)
        }
    }

    private fun mkPrivate(dir: Path) {
        if (Files.isDirectory(dir)) return
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }
}
