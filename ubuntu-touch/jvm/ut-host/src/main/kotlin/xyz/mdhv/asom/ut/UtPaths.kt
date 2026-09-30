package xyz.mdhv.asom.ut

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodePaths

/**
 * Where the node keeps things under click confinement (ubuntu-touch.md 5.1 and UF11): `~/.local/share/<pkg>` (data, ledger,
 * identity), `~/.cache/<pkg>` (the CDS archive), `~/.config/<pkg>`, `/run/user/<uid>/<pkg>` and the confined `TMPDIR`. The
 * identity directory is a sibling of the ledger directory, never inside it (T17(f)). [resolve] creates nothing.
 */
class UtLayout(val paths: NodePaths, val cacheDir: Path, val configDir: Path, val tmpDir: Path)

object UtPaths {
    const val PKG = "xyz.mdhv.asom.ut"

    private val PRIVATE_DIR = PosixFilePermissions.fromString("rwx------")
    private val GROUP_OR_OTHER = setOf(
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
    )

    fun resolve(env: Map<String, String>): UtLayout {
        val home = absolute(env["HOME"], "HOME")
        if (home.nameCount == 0) throw HostRefusedException("HOME is the filesystem root")
        fun underHome(name: String, fallback: String): Path {
            val base = env[name]?.takeIf { it.isNotEmpty() }?.let { absolute(it, name) } ?: home.resolve(fallback)
            if (!base.startsWith(home)) throw HostRefusedException("$name is outside HOME")
            return base.resolve(PKG)
        }
        val data = underHome("XDG_DATA_HOME", ".local/share")
        val cache = underHome("XDG_CACHE_HOME", ".cache")
        val config = underHome("XDG_CONFIG_HOME", ".config")
        val runtime = env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotEmpty() }?.let { absolute(it, "XDG_RUNTIME_DIR").resolve(PKG) } ?: data.resolve("run")
        val tmp = env["TMPDIR"]?.takeIf { it.isNotEmpty() }?.let { absolute(it, "TMPDIR") } ?: runtime.resolve("tmp")
        val ledger = data.resolve("ledger")
        val identity = data.resolve("identity")
        val paths = NodePaths(
            mode = HostMode.FOREGROUND, stateDir = data, dataDir = data, ledgerDir = ledger, identityDir = identity,
            runtimeDir = runtime, controlSocket = runtime.resolve("no-control-socket"),
        )
        return UtLayout(paths, cache, config, tmp)
    }

    private fun absolute(value: String?, name: String): Path {
        if (value.isNullOrEmpty()) throw HostRefusedException("$name is not set")
        val p = try {
            Paths.get(value)
        } catch (e: java.nio.file.InvalidPathException) {
            throw HostRefusedException("$name is not a valid path")
        }
        if (!p.isAbsolute) throw HostRefusedException("$name is not absolute")
        if (p.any { it.toString() == ".." }) throw HostRefusedException("$name contains ..")
        return p.normalize()
    }

    /** Creates the private directories (0700). An existing directory that the group or others can reach is tightened, or refused. */
    fun ensure(layout: UtLayout) {
        val p = layout.paths
        for (d in listOf(p.dataDir, p.ledgerDir, p.identityDir, layout.cacheDir, layout.configDir, p.runtimeDir)) privateDir(d)
    }

    fun privateDir(dir: Path) {
        if (!Files.exists(dir)) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PRIVATE_DIR))
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir)) throw HostRefusedException("not a plain directory: ${dir.fileName}")
        val perms = Files.getPosixFilePermissions(dir)
        if (perms.any { it in GROUP_OR_OTHER }) {
            try {
                Files.setPosixFilePermissions(dir, PRIVATE_DIR)
            } catch (e: java.io.IOException) {
                throw HostRefusedException("directory ${dir.fileName} is reachable by other users and cannot be tightened")
            }
        }
    }

    fun privateFile(file: Path) {
        if (!Files.exists(file)) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    }
}
