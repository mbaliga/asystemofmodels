package xyz.mdhv.asom.desktop.linux.host

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodePaths

/**
 * HLU-7: `LinuxPaths.prepare` enforces what linux.md's file tree promises (0700 directories, 0600 files) on directories
 * that already exist, not only on the ones it creates. A directory made earlier by `mkdir -p` under umask 022, or a symlink
 * planted in its place, must not be accepted as it is.
 */
class LinuxPathsPrepareTest {
    private fun mode(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS))

    private fun userPaths(root: Path): NodePaths =
        LinuxPaths.resolve(HostMode.USER, mapOf("XDG_STATE_HOME" to "$root/s", "XDG_DATA_HOME" to "$root/d", "XDG_RUNTIME_DIR" to "$root/r"), root)

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("asom-prep-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an existing directory that others can reach is tightened to 0700`() = withRoot { root ->
        val p = userPaths(root)
        for (d in listOf(p.stateDir, p.dataDir, p.ledgerDir, p.identityDir, p.runtimeDir)) {
            Files.createDirectories(d)
            Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        LinuxPaths.prepare(p)
        for (d in listOf(p.stateDir, p.dataDir, p.ledgerDir, p.identityDir, p.runtimeDir)) assertEquals("rwx------", mode(d), d.toString())
    }

    @Test
    fun `a symlink in place of a state directory is refused, and nothing behind it is touched`() = withRoot { root ->
        val p = userPaths(root)
        val elsewhere = Files.createDirectories(root.resolve("elsewhere"))
        Files.setPosixFilePermissions(elsewhere, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.createDirectories(p.stateDir)
        Files.createSymbolicLink(p.ledgerDir, elsewhere)
        assertFailsWith<HostRefusedException> { LinuxPaths.prepare(p) }
        assertEquals("rwxr-xr-x", mode(elsewhere), "the symlink target was left alone")
    }

    @Test
    fun `a symlinked identity directory is refused too`() = withRoot { root ->
        val p = userPaths(root)
        val elsewhere = Files.createDirectories(root.resolve("elsewhere"))
        Files.createDirectories(p.dataDir)
        Files.createSymbolicLink(p.identityDir, elsewhere)
        assertFailsWith<HostRefusedException> { LinuxPaths.prepare(p) }
    }

    @Test
    fun `CACHEDIR_TAG is written 0600, not with the umask`() = withRoot { root ->
        val p = userPaths(root)
        LinuxPaths.prepare(p)
        assertEquals("rw-------", mode(p.identityDir.resolve("CACHEDIR.TAG")))
    }

    @Test
    fun `a system runtime directory keeps its group access but loses the access of others`() = withRoot { root ->
        val base = Files.createDirectories(root.resolve("var-lib-asom"))
        val run = Files.createDirectories(root.resolve("run-asom"))
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"))
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-xr-x"))
        val p = NodePaths(HostMode.SYSTEM, base, base, base.resolve("ledger"), base.resolve("identity"), run, run.resolve("ctl.sock"))
        LinuxPaths.prepare(p)
        assertEquals("rwxr-x---", mode(run), "RuntimeDirectoryMode=0750: the asom group reaches the control socket, others do not")
        assertTrue(mode(base) == "rwx------")
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-x---"))
        LinuxPaths.prepare(p)
        assertEquals("rwxr-x---", mode(run), "an already-right mode is left alone")
    }

    @Test
    fun `a directory owned by someone else is refused, not tightened`() = withRoot { root ->
        val p = userPaths(root)
        LinuxPaths.prepare(p)
        val me = java.nio.file.Files.getAttribute(p.stateDir, "unix:uid") as Int
        assertFailsWith<HostRefusedException> { LinuxPaths.prepare(p, uid = me + 1) }
        LinuxPaths.prepare(p, uid = me)
    }
}
