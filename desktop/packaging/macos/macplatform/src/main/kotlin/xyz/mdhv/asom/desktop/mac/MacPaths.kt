package xyz.mdhv.asom.desktop.mac

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodePaths

/** The three states of macos.md 3.2: a per-user agent, a daemon (M2, not built) and the unsigned dev state. */
enum class MacMode(val label: String) {
    AGENT("agent"),
    DAEMON("daemon"),
    DEV("dev"),
}

/** The directory layout of macos.md 3.3 and 7.2, as strings, so it can be computed and tested on any operating system. */
data class MacLayout(
    val mode: MacMode,
    val root: String,
    val nodeDir: String,
    val registryDir: String,
    val ledgerDir: String,
    val manifestsDir: String,
    val modelsDir: String,
    val diagDir: String,
    val tmpDir: String,
    val runDir: String,
    val controlSocket: String,
    val bindingFile: String,
) {
    /** The directories the node writes under [root] (never [runDir], which lives in the per-user temporary directory). */
    val stateDirs: List<String> get() = listOf(root, nodeDir, registryDir, ledgerDir, manifestsDir, modelsDir, diagDir, tmpDir)
    val unsigned: Boolean get() = mode == MacMode.DEV
}

object MacPaths {
    const val GROUP_NAME = "xyz.mdhv.asom"
    const val DEV_DIR = "xyz.mdhv.asom-dev"

    /** `sockaddr_un.sun_path` is 104 bytes on macOS including the NUL, so a path of at most 103 bytes fits. */
    const val MAX_SOCKET_PATH_BYTES = 103

    private fun plainAbsolute(what: String, p: String?): String {
        val raw = p?.trim()?.trimEnd('/')
        if (raw.isNullOrEmpty() || !raw.startsWith("/")) throw HostRefusedException("$what is not an absolute path (\"${p ?: "unset"}\"); the node refuses to guess a state directory")
        if (raw.any { it < ' ' || it == '\u007F' }) throw HostRefusedException("$what contains a control character")
        if (raw.split('/').any { it == ".." || it == "." }) throw HostRefusedException("$what contains a relative segment: $raw")
        return raw
    }

    /** What the seam's [HostMode] means here: USER and FOREGROUND are the per-user agent (or the dev state when unsigned); SYSTEM is the M2 daemon. */
    fun modeFor(mode: HostMode, teamId: String?): MacMode = when (mode) {
        HostMode.SYSTEM -> MacMode.DAEMON
        HostMode.USER, HostMode.FOREGROUND -> if (teamId == null) MacMode.DEV else MacMode.AGENT
    }

    /**
     * Pure: computes strings, creates nothing. Refuses anything that is not a plain absolute path, a Team ID that is not exactly
     * ten upper-case letters and digits, and a control socket path that does not fit `sun_path`.
     */
    fun layout(mac: MacMode, env: MacEnv, teamId: String?): MacLayout {
        if (teamId != null && !MacOptions.TEAM_ID.matches(teamId)) throw HostRefusedException("the Team ID is not ten upper-case letters and digits")
        val root = when (mac) {
            MacMode.AGENT -> {
                requireNotNull(teamId) { "agent mode needs a Team ID" }
                "${plainAbsolute("the home directory", env.home)}/Library/Group Containers/$teamId.$GROUP_NAME"
            }
            MacMode.DEV -> "${plainAbsolute("the home directory", env.home)}/Library/Application Support/$DEV_DIR"
            MacMode.DAEMON -> "/Library/Application Support/$GROUP_NAME"
        }
        val temp = plainAbsolute("the per-user temporary directory", env.tempDir())
        val run = "$temp/$GROUP_NAME"
        val socket = "$run/ctl.sock"
        val bytes = socket.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_SOCKET_PATH_BYTES) throw HostRefusedException("control socket path is $bytes bytes, over the AF_UNIX limit of $MAX_SOCKET_PATH_BYTES: $socket")
        return MacLayout(
            mode = mac, root = root, nodeDir = "$root/node", registryDir = "$root/registry", ledgerDir = "$root/ledger",
            manifestsDir = "$root/manifests", modelsDir = "$root/models", diagDir = "$root/diag", tmpDir = "$root/tmp",
            runDir = run, controlSocket = socket, bindingFile = "$root/node/binding.json",
        )
    }

    /** `nodeDir` is the identity directory: its own directory beside `ledger`, as macos.md 7.2 lays it out (mac ERRATA MAC-PATH-1). */
    fun toNodePaths(l: MacLayout, hostMode: HostMode): NodePaths = NodePaths(
        mode = hostMode,
        stateDir = Path.of(l.root),
        dataDir = Path.of(l.root),
        ledgerDir = Path.of(l.ledgerDir),
        identityDir = Path.of(l.nodeDir),
        runtimeDir = Path.of(l.runDir),
        controlSocket = Path.of(l.controlSocket),
    )

    // ---- permissions (0700 directories, 0600 files) ------------------------------------------------------------------------

    private val DIR_PERMS = PosixFilePermissions.fromString("rwx------")
    private val FILE_PERMS = PosixFilePermissions.fromString("rw-------")

    /** Empty when [dir] is a real directory (not a symlink) owned by [ownUid] that grants group and other nothing. */
    fun privateDirProblems(dir: Path, ownUid: Int?): List<String> {
        val problems = ArrayList<String>(3)
        if (Files.isSymbolicLink(dir)) return listOf("$dir is a symbolic link")
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return listOf("$dir is not a directory")
        try {
            val a = Files.readAttributes(dir, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS)
            val uid = a["uid"] as Int
            val mode = (a["mode"] as Int) and 0b111_111_111
            if (ownUid == null) problems += "this process's uid cannot be read"
            else if (uid != ownUid) problems += "$dir is owned by uid $uid, not by this user (uid $ownUid)"
            if (mode and 0b000_111_111 != 0) problems += "$dir grants group or other access (mode ${Integer.toOctalString(mode)}); it must be 0700"
        } catch (e: Exception) {
            problems += "cannot read the owner and mode of $dir: ${e.message ?: e::class.simpleName}"
        }
        return problems
    }

    fun privateFileProblems(file: Path, ownUid: Int?): List<String> {
        val problems = ArrayList<String>(3)
        if (Files.isSymbolicLink(file)) return listOf("$file is a symbolic link")
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return listOf("$file is not a regular file")
        try {
            val a = Files.readAttributes(file, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS)
            val uid = a["uid"] as Int
            val mode = (a["mode"] as Int) and 0b111_111_111
            if (ownUid == null) problems += "this process's uid cannot be read"
            else if (uid != ownUid) problems += "$file is owned by uid $uid, not by this user (uid $ownUid)"
            if (mode and 0b000_111_111 != 0) problems += "$file grants group or other access (mode ${Integer.toOctalString(mode)}); it must be 0600"
        } catch (e: Exception) {
            problems += "cannot read the owner and mode of $file: ${e.message ?: e::class.simpleName}"
        }
        return problems
    }

    /** Creates a directory (and missing parents) with mode 0700. An existing directory is left as it is: it is verified, not chmod-ed. */
    fun createPrivateDir(dir: Path) {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
        dir.parent?.let { if (!Files.exists(it)) createPrivateDir(it) }
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(DIR_PERMS))
    }

    /** Writes a NEW private file (0600, never overwrites): the file is created with its mode in one call, and only then filled, so it never exists with wider access. */
    fun writeNewPrivateFile(file: Path, bytes: ByteArray) {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMS))
        Files.write(file, bytes, java.nio.file.StandardOpenOption.WRITE)
    }

    /**
     * Creates the state directories (0700) and verifies each. Called only when the node is about to write, never from
     * `paths()`. The identity directory also gets a `CACHEDIR.TAG` (restic and borg `--exclude-caches`); the backup exclusion of
     * the root is a separate helper call ([excludeFromBackup]).
     */
    fun prepare(l: MacLayout, ownUid: Int?): List<String> {
        val problems = ArrayList<String>()
        for (d in l.stateDirs) {
            val p = Path.of(d)
            createPrivateDir(p)
            problems += privateDirProblems(p, ownUid)
        }
        val tag = Path.of(l.nodeDir).resolve("CACHEDIR.TAG")
        if (!Files.exists(tag)) {
            Files.writeString(tag, "Signature: 8a477f597d28d172789f06886806bc55\n# This directory holds the asom node identity; do not back it up (asom).\n", Charsets.UTF_8)
        }
        return problems
    }

    /** Marks the container root as excluded from backups through the helper (C7, AM18). Returns whether it worked; a failure is a state, not an error loop. */
    fun excludeFromBackup(l: MacLayout, exclude: (String) -> Unit): Boolean = try {
        exclude(l.root)
        true
    } catch (_: Exception) {
        false
    }

    /** The permissions the two helpers above set, exposed for the tests. */
    internal val dirPermissions: Set<PosixFilePermission> get() = DIR_PERMS
    internal val filePermissions: Set<PosixFilePermission> get() = FILE_PERMS
}
