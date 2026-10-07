package xyz.mdhv.asom.desktop.linux.host

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodePaths

/**
 * XDG and `/var/lib/asom` resolution (linux.md 3.1, 5). [resolve] is pure: it creates nothing. [prepare] creates the
 * directories 0700 and the identity directory's `CACHEDIR.TAG` (C7: tar, borg and restic `--exclude-caches` skip it);
 * the node calls it only when it is about to write.
 * The same enforcement as the Ubuntu Touch host's `UtPaths.privateDir`: no symlink, our own uid, no group or other access.
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

    /**
     * Creates what is missing and then ENFORCES what is there (finding HLU-7): every directory must be a plain directory (no
     * symlink), owned by this process's uid, and reachable by nobody else, or the call refuses. A group or other bit on an
     * existing directory of ours is removed (0700); the only exception is the SYSTEM-mode runtime directory, which systemd
     * creates 0750 so the `asom` group can reach the control socket, and from which only the access of others is removed.
     */
    fun prepare(paths: NodePaths, uid: Int = currentUid()) {
        val groupMayReach = paths.mode == HostMode.SYSTEM
        for (d in listOf(paths.stateDir, paths.dataDir, paths.ledgerDir, paths.identityDir)) privateDir(d, uid, allowGroup = false)
        privateDir(paths.runtimeDir, uid, allowGroup = groupMayReach)
        val tag = paths.identityDir.resolve("CACHEDIR.TAG")
        if (!Files.exists(tag, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(tag, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                Files.writeString(tag, "$CACHEDIR_TAG_SIGNATURE\n# This directory holds the asom node identity; do not back it up (asom).\n", Charsets.UTF_8)
            } catch (_: FileAlreadyExistsException) {
            }
        }
    }

    /** The effective uid of this process: the owner of `/proc/self`. */
    private fun currentUid(): Int = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int

    private fun privateDir(dir: Path, uid: Int, allowGroup: Boolean) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(if (allowGroup) "rwxr-x---" else "rwx------")))
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw HostRefusedException("not a plain directory (a symlink or a file is in its place): $dir")
        }
        val owner = Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int
        if (owner != uid) throw HostRefusedException("directory $dir is owned by uid $owner, not by this process's uid $uid")
        val perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS)
        val forbidden = if (allowGroup) OTHERS else OTHERS + GROUP
        if (perms.any { it in forbidden }) {
            try {
                Files.setPosixFilePermissions(dir, perms - forbidden)
            } catch (e: java.io.IOException) {
                throw HostRefusedException("directory $dir is reachable by other users and cannot be tightened: ${e.message}")
            }
        }
    }

    private val GROUP = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE)
    private val OTHERS = setOf(PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
}
