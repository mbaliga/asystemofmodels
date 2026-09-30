package xyz.mdhv.asom.desktop.mac

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PathsAndGuardTest {
    private val laws = LawCounter(
        listOf(
            "layout-agent", "layout-dev", "layout-daemon", "layout-refuses", "socket-limit", "identity-dir-separate", "prepare-0700",
            "prepare-detects", "guard-fresh", "guard-bound", "guard-migrated", "guard-unverifiable", "guard-enforce-unpairs",
            "lock-exclusive", "lock-symlink", "lock-file-mode",
        ),
    )

    @AfterAll
    fun report() = laws.assertAllExercised("paths-guard")

    private val me: Int? = runCatching { Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int }.getOrNull()

    private fun env(home: String = "/Users/alice", temp: String? = "/var/folders/zz/abcdef0123456789abcdef0123456789/T/", uid: Int? = 501, user: String = "alice") =
        MacEnv(user, home, emptyMap(), { temp }, { uid })

    // ---- layout -----------------------------------------------------------------------------------------------------------

    @Test
    fun `a signed build keeps its state in the Team-ID group container`() {
        val l = MacPaths.layout(MacMode.AGENT, env(), "ABCDE12345")
        assertEquals("/Users/alice/Library/Group Containers/ABCDE12345.xyz.mdhv.asom", l.root)
        assertEquals("${l.root}/node", l.nodeDir)
        assertEquals("${l.root}/ledger", l.ledgerDir)
        assertEquals("${l.root}/node/binding.json", l.bindingFile)
        assertEquals("/var/folders/zz/abcdef0123456789abcdef0123456789/T/xyz.mdhv.asom/ctl.sock", l.controlSocket)
        assertFalse(l.unsigned)
        assertTrue(l.controlSocket.startsWith("/var/folders"), "the socket is in the per-user temp dir, NOT in the group container")
        assertFalse(l.controlSocket.startsWith(l.root))
        laws.hit("layout-agent")
        assertNotEquals(l.nodeDir, l.ledgerDir); laws.hit("identity-dir-separate")
    }

    @Test
    fun `an unsigned build uses the dev state directory and is marked unsigned`() {
        val l = MacPaths.layout(MacMode.DEV, env(), null)
        assertEquals("/Users/alice/Library/Application Support/xyz.mdhv.asom-dev", l.root)
        assertTrue(l.unsigned)
        assertEquals(MacMode.DEV, MacPaths.modeFor(HostMode.USER, null))
        assertEquals(MacMode.DEV, MacPaths.modeFor(HostMode.FOREGROUND, null))
        assertEquals(MacMode.AGENT, MacPaths.modeFor(HostMode.USER, "ABCDE12345"))
        assertEquals(MacMode.DAEMON, MacPaths.modeFor(HostMode.SYSTEM, "ABCDE12345"))
        laws.hit("layout-dev")
    }

    @Test
    fun `the daemon layout is computed but the platform refuses it`() {
        val l = MacPaths.layout(MacMode.DAEMON, env(), "ABCDE12345")
        assertEquals("/Library/Application Support/xyz.mdhv.asom", l.root)
        laws.hit("layout-daemon")
        val p = MacPlatform(helperTransport = null, env = env(), options = MacOptions("ABCDE12345"))
        val e = assertFailsWith<HostRefusedException> { p.paths(HostMode.SYSTEM) }
        assertTrue("M2" in e.message!! && "not built" in e.message!!)
    }

    @Test
    fun `nothing that is not a plain absolute path or a real Team ID is accepted`() {
        val refused = listOf(
            "relative home" to { MacPaths.layout(MacMode.AGENT, env(home = "Users/alice"), "ABCDE12345") },
            "empty home" to { MacPaths.layout(MacMode.AGENT, env(home = ""), "ABCDE12345") },
            "dotdot" to { MacPaths.layout(MacMode.AGENT, env(home = "/Users/../etc"), "ABCDE12345") },
            "dot segment" to { MacPaths.layout(MacMode.AGENT, env(home = "/Users/./alice"), "ABCDE12345") },
            "control char" to { MacPaths.layout(MacMode.AGENT, env(home = "/Users/al\u0001ice"), "ABCDE12345") },
            "no temp dir" to { MacPaths.layout(MacMode.AGENT, env(temp = null), "ABCDE12345") },
            "relative temp dir" to { MacPaths.layout(MacMode.AGENT, env(temp = "tmp"), "ABCDE12345") },
            "lower-case team id" to { MacPaths.layout(MacMode.AGENT, env(), "abcde12345") },
            "short team id" to { MacPaths.layout(MacMode.AGENT, env(), "ABCDE1234") },
            "team id with a dot" to { MacPaths.layout(MacMode.AGENT, env(), "ABCDE1234.") },
            "team id with a slash" to { MacPaths.layout(MacMode.AGENT, env(), "../../ABCD") },
        )
        for ((why, f) in refused) assertFailsWith<HostRefusedException>(why) { f() }
        assertFailsWith<IllegalArgumentException> { MacOptions("abcde12345") }
        laws.hit("layout-refuses", refused.size.toLong())
    }

    @Test
    fun `the control socket path must fit sun_path (102 bytes on macOS)`() {
        // a temp dir long enough that "<tmp>/xyz.mdhv.asom/ctl.sock" is exactly 102 bytes, then 103
        val tail = "/xyz.mdhv.asom/ctl.sock"
        val exact = "/" + "t".repeat(102 - tail.length - 1)
        val ok = MacPaths.layout(MacMode.DEV, env(temp = exact), null)
        assertEquals(102, ok.controlSocket.toByteArray().size)
        assertFailsWith<HostRefusedException> { MacPaths.layout(MacMode.DEV, env(temp = exact + "t"), null) }
        // the limit is in BYTES, not characters
        assertFailsWith<HostRefusedException> { MacPaths.layout(MacMode.DEV, env(temp = "/" + "é".repeat(45)), null) }
        laws.hit("socket-limit", 3)
    }

    @Test
    fun `toNodePaths matches the seam and keeps the identity directory apart from the ledger`() {
        val l = MacPaths.layout(MacMode.AGENT, env(), "ABCDE12345")
        val p = MacPaths.toNodePaths(l, HostMode.USER)
        assertEquals(HostMode.USER, p.mode)
        assertEquals(Path.of(l.root), p.stateDir)
        assertEquals(Path.of(l.ledgerDir), p.ledgerDir)
        assertEquals(Path.of(l.nodeDir), p.identityDir)
        assertEquals(Path.of(l.runDir), p.runtimeDir)
        assertNotEquals(p.identityDir, p.ledgerDir)
    }

    @Test
    fun `the platform refuses root by name and by uid, and computes paths without creating anything`() {
        val dir = Files.createTempDirectory(shortTempBase(), "ah-")
        val e = MacEnv("alice", dir.toString(), emptyMap(), { dir.resolve("T").toString() }, { 501 })
        val p = MacPlatform(helperTransport = null, env = e, options = MacOptions(null))
        val paths = p.paths(HostMode.USER)
        assertTrue(paths.stateDir.toString().contains("xyz.mdhv.asom-dev"))
        assertEquals(0, Files.list(dir).use { it.count() }, "paths() is pure: it created nothing")
        for ((n, u) in listOf("root" to 501, "alice" to 0)) {
            val pr = MacPlatform(helperTransport = null, env = MacEnv(n, dir.toString(), emptyMap(), { "/tmp" }, { u }), options = MacOptions(null))
            assertTrue("root" in assertFailsWith<HostRefusedException> { pr.paths(HostMode.USER) }.message!!)
        }
    }

    // ---- directories and files ----------------------------------------------------------------------------------------------

    @Test
    fun `prepare creates 0700 directories and a CACHEDIR tag, and detects a directory that is too open`() {
        val root = Files.createTempDirectory("asom-prep-")
        val home = root.toString()
        val l = MacPaths.layout(MacMode.DEV, MacEnv("me", home, emptyMap(), { "/tmp" }, { me }), null)
        val problems = MacPaths.prepare(l, me)
        assertEquals(emptyList(), problems)
        for (d in l.stateDirs) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(Path.of(d)), d)
        }
        assertTrue(Files.exists(Path.of(l.nodeDir, "CACHEDIR.TAG")))
        laws.hit("prepare-0700")
        // an existing directory that is too open is REPORTED, not silently chmod-ed
        Files.setPosixFilePermissions(Path.of(l.ledgerDir), PosixFilePermissions.fromString("rwxr-x---"))
        assertTrue(MacPaths.prepare(l, me).any { "grants group or other access" in it })
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(Path.of(l.ledgerDir)))
        // a symlink is refused
        val target = Files.createTempDirectory("asom-target-")
        Files.delete(Path.of(l.diagDir))
        Files.createSymbolicLink(Path.of(l.diagDir), target)
        assertTrue(MacPaths.prepare(l, me).any { "symbolic link" in it })
        // a wrong owner is refused
        assertTrue(MacPaths.privateDirProblems(Path.of(l.nodeDir), (me ?: 0) + 1).any { "not by this user" in it })
        assertTrue(MacPaths.privateDirProblems(Path.of(l.nodeDir), null).any { "uid cannot be read" in it })
        laws.hit("prepare-detects", 4)
    }

    @Test
    fun `a new private file is created with its mode, never overwritten`() {
        val dir = Files.createTempDirectory("asom-file-")
        val f = dir.resolve("x")
        MacPaths.writeNewPrivateFile(f, "abc".toByteArray())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f))
        assertEquals("abc", Files.readString(f))
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { MacPaths.writeNewPrivateFile(f, "def".toByteArray()) }
        assertEquals("abc", Files.readString(f))
        assertTrue(MacPaths.privateFileProblems(f, me).isEmpty())
        assertTrue(MacPaths.privateFileProblems(dir.resolve("missing"), me).isNotEmpty())
    }

    @Test
    fun `the backup exclusion is a helper call whose failure is a state, not a crash`() {
        val l = MacPaths.layout(MacMode.DEV, env(), null)
        val seen = ArrayList<String>()
        assertTrue(MacPaths.excludeFromBackup(l) { seen += it })
        assertEquals(listOf(l.root), seen)
        assertFalse(MacPaths.excludeFromBackup(l) { throw RuntimeException("no") })
    }

    // ---- the clone guard ----------------------------------------------------------------------------------------------------

    private class Registry : PairedRegistry {
        var unpaired = 0
        override fun unpairAll(): Int = 3.also { unpaired++ }
    }

    private fun guardIn(dir: Path, digest: () -> ByteArray) = MigrationGuard(dir.resolve("binding.json"), digest)

    @Test
    fun `no binding and no identity is fresh, a bound identity on the same Mac is bound`() {
        val dir = Files.createTempDirectory("asom-guard-")
        val d = ByteArray(32) { 7 }
        val g = guardIn(dir) { d }
        assertIs<MigrationGuard.Verdict.Fresh>(g.check(identityExists = false)); laws.hit("guard-fresh")
        g.bind()
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(dir.resolve("binding.json")))
        val text = Files.readString(dir.resolve("binding.json"))
        assertTrue(text.startsWith("{\"v\":1,\"salt\":\"") && "\"binding\":\"" in text)
        assertFalse(java.util.Base64.getEncoder().encodeToString(d) in text, "the platform digest itself is never stored, only its salted hash")
        assertIs<MigrationGuard.Verdict.Bound>(g.check(true)); laws.hit("guard-bound")
        assertIs<MigrationGuard.Verdict.Bound>(g.check(false))
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { g.bind() }
    }

    @Test
    fun `another Mac, a missing binding, a corrupt binding and an unreadable one are all NIK_MIGRATED`() {
        val dir = Files.createTempDirectory("asom-guard-")
        var digest = ByteArray(32) { 1 }
        val g = guardIn(dir) { digest }
        g.bind()
        digest = ByteArray(32) { 2 }
        val other = g.check(true)
        assertIs<MigrationGuard.Verdict.Migrated>(other)
        assertTrue("differs" in other.reason)
        // an identity with no binding cannot be told from a copy
        g.unbind()
        assertIs<MigrationGuard.Verdict.Migrated>(g.check(true))
        // corrupt shapes
        val f = dir.resolve("binding.json")
        for (bad in listOf("", "not json", "{}", "{\"v\":2,\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"binding\":\"AAAA\"}", "{\"v\":1,\"salt\":\"AAAA\",\"binding\":\"AAAA\"}",
            "{\"v\":1,\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"binding\":\"AAAA\"}", "{\"v\":1,\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"binding\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\",\"x\":1}")) {
            Files.writeString(f, bad)
            assertIs<MigrationGuard.Verdict.Migrated>(g.check(true), bad)
            Files.delete(f)
        }
        // a directory where the file should be: unreadable
        Files.createDirectory(f)
        assertIs<MigrationGuard.Verdict.Migrated>(g.check(true))
        laws.hit("guard-migrated", 10)
    }

    @Test
    fun `when this Mac's own digest cannot be read nothing is concluded and the identity is not presented`() {
        val dir = Files.createTempDirectory("asom-guard-")
        var fail = false
        val g = guardIn(dir) { if (fail) throw java.io.IOException("helper lost") else ByteArray(32) }
        g.bind()
        fail = true
        val v = g.check(true)
        assertIs<MigrationGuard.Verdict.Unverifiable>(v)
        assertTrue("helper lost" in v.reason)
        laws.hit("guard-unverifiable")
    }

    @Test
    fun `a migrated identity unpairs every peer and only then`() {
        val dir = Files.createTempDirectory("asom-guard-")
        var digest = ByteArray(32) { 1 }
        val g = guardIn(dir) { digest }
        g.bind()
        val reg = Registry()
        assertEquals(0, g.enforce(true, reg).second)
        assertEquals(0, reg.unpaired)
        digest = ByteArray(32) { 9 }
        val (v, n) = g.enforce(true, reg)
        assertIs<MigrationGuard.Verdict.Migrated>(v)
        assertEquals(3, n)
        assertEquals(1, reg.unpaired)
        laws.hit("guard-enforce-unpairs")
    }

    // ---- the single-lending-node lock ---------------------------------------------------------------------------------------

    @Test
    fun `only one holder at a time, a second try in this process and a real second process both see HeldByOther`() {
        val dir = Files.createTempDirectory("asom-lock-")
        val path = dir.resolve("node.lock")
        val a = NodeLock(path).tryAcquire()
        assertIs<NodeLock.Result.Acquired>(a)
        assertIs<NodeLock.Result.HeldByOther>(NodeLock(path).tryAcquire())
        // a real second process
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val cp = System.getProperty("asom.testClasspath")
        val child = ProcessBuilder(javaBin, "-cp", cp, "xyz.mdhv.asom.desktop.mac.LockChildMainKt", path.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val line = child.inputStream.bufferedReader().readLine()
        assertEquals("HELD_BY_OTHER", line, "a separate JVM is refused while this process holds the lock")
        child.waitFor(30, TimeUnit.SECONDS)
        a.handle.close()
        val child2 = ProcessBuilder(javaBin, "-cp", cp, "xyz.mdhv.asom.desktop.mac.LockChildMainKt", path.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        assertEquals("ACQUIRED", child2.inputStream.bufferedReader().readLine(), "after release a separate JVM gets it")
        child2.waitFor(30, TimeUnit.SECONDS)
        val b = NodeLock(path).tryAcquire()
        assertIs<NodeLock.Result.Acquired>(b)
        b.handle.close()
        laws.hit("lock-exclusive", 4)
    }

    @Test
    fun `the lock file is created open to every user (the spec's 0644 would lock a second user out forever) and never followed through a symlink`() {
        val dir = Files.createTempDirectory("asom-lock-")
        val path = dir.resolve("node.lock")
        (NodeLock(path).tryAcquire() as NodeLock.Result.Acquired).handle.close()
        assertEquals(PosixFilePermissions.fromString("rw-rw-rw-"), Files.getPosixFilePermissions(path))
        laws.hit("lock-file-mode")
        // a symbolic link planted at the lock path: not followed, its target untouched
        val victim = dir.resolve("victim")
        Files.writeString(victim, "precious")
        Files.setPosixFilePermissions(victim, PosixFilePermissions.fromString("rw-------"))
        val planted = dir.resolve("planted.lock")
        Files.createSymbolicLink(planted, victim)
        val r = NodeLock(planted).tryAcquire()
        assertIs<NodeLock.Result.Unavailable>(r)
        assertEquals("precious", Files.readString(victim))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(victim), "the victim's mode was not changed")
        laws.hit("lock-symlink")
    }
}
