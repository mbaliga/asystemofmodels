package xyz.mdhv.asom.ut

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.HostRefusedException

class UtPathsTest {
    private val home = "/home/phablet"

    @Test
    fun defaultsAreTheConfinedDirectories() {
        val l = UtPaths.resolve(mapOf("HOME" to home, "XDG_RUNTIME_DIR" to "/run/user/32011", "TMPDIR" to "/run/user/32011/confined/xyz.mdhv.asom.ut"))
        assertEquals("/home/phablet/.local/share/xyz.mdhv.asom.ut", l.paths.dataDir.toString())
        assertEquals("/home/phablet/.local/share/xyz.mdhv.asom.ut/ledger", l.paths.ledgerDir.toString())
        assertEquals("/home/phablet/.local/share/xyz.mdhv.asom.ut/identity", l.paths.identityDir.toString())
        assertEquals("/home/phablet/.cache/xyz.mdhv.asom.ut", l.cacheDir.toString())
        assertEquals("/home/phablet/.config/xyz.mdhv.asom.ut", l.configDir.toString())
        assertEquals("/run/user/32011/xyz.mdhv.asom.ut", l.paths.runtimeDir.toString())
        assertEquals("/run/user/32011/confined/xyz.mdhv.asom.ut", l.tmpDir.toString())
    }

    @Test
    fun theIdentityDirectoryIsNeverInsideTheLedgerDirectoryOrTheOtherWayRound() {
        val p = UtPaths.resolve(mapOf("HOME" to home)).paths
        assertFalse(p.identityDir.startsWith(p.ledgerDir))
        assertFalse(p.ledgerDir.startsWith(p.identityDir))
        assertEquals(p.identityDir.parent, p.ledgerDir.parent)
    }

    @Test
    fun anUnusableHomeOrAnEscapingBaseIsRefused() {
        for (env in listOf(
            emptyMap<String, String>(),
            mapOf("HOME" to ""),
            mapOf("HOME" to "relative/home"),
            mapOf("HOME" to "/"),
            mapOf("HOME" to "/home/../etc"),
            mapOf("HOME" to home, "XDG_DATA_HOME" to "/etc"),
            mapOf("HOME" to home, "XDG_CACHE_HOME" to "/home/other/.cache"),
            mapOf("HOME" to home, "XDG_CONFIG_HOME" to "relative"),
            mapOf("HOME" to home, "XDG_RUNTIME_DIR" to "run/user"),
            mapOf("HOME" to home, "TMPDIR" to "tmp"),
        )) {
            assertFailsWith<HostRefusedException>("$env") { UtPaths.resolve(env) }
        }
    }

    @Test
    fun ensureCreatesPrivateDirectoriesAndTightensLooseOnes() {
        val root = Files.createTempDirectory("asom-ut-paths-")
        try {
            val loose = root.resolve(".local/share/xyz.mdhv.asom.ut")
            Files.createDirectories(loose)
            Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rwxr-xr-x"))
            val layout = UtPaths.resolve(mapOf("HOME" to root.toString(), "XDG_RUNTIME_DIR" to root.resolve("run").toString()))
            UtPaths.ensure(layout)
            for (d in listOf(layout.paths.dataDir, layout.paths.ledgerDir, layout.paths.identityDir, layout.cacheDir, layout.configDir, layout.paths.runtimeDir)) {
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(d), "mode of $d")
            }
            assertTrue(Files.getPosixFilePermissions(layout.paths.dataDir).none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
            UtPaths.privateFile(layout.paths.identityDir.resolve("nik.p8"))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(layout.paths.identityDir.resolve("nik.p8")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aSymlinkedDirectoryIsRefused() {
        val root = Files.createTempDirectory("asom-ut-paths-link-")
        try {
            val target = Files.createDirectory(root.resolve("elsewhere"))
            Files.createDirectories(root.resolve(".local/share"))
            Files.createSymbolicLink(root.resolve(".local/share/xyz.mdhv.asom.ut"), target)
            val layout = UtPaths.resolve(mapOf("HOME" to root.toString(), "XDG_RUNTIME_DIR" to root.resolve("run").toString()))
            assertFailsWith<HostRefusedException> { UtPaths.ensure(layout) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
