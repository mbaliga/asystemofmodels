package xyz.mdhv.asom.desktop.win

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.api.MutexResult
import xyz.mdhv.asom.desktop.win.fakes.FakeAcl
import xyz.mdhv.asom.desktop.win.fakes.FakeMutex
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.OWNER_SID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PathsAndMutexTest {
    private val laws = LawCounter(
        listOf(
            "layout-user", "layout-service", "layout-refuses-unsafe", "socket-path-limit", "env-case-insensitive", "prepare-applies-acl",
            "mutex-single-identity", "mutex-refuses-on-denied-and-failed", "mutex-releases",
        ),
    )

    private val vars = mapOf("LOCALAPPDATA" to "C:\\Users\\alice\\AppData\\Local", "ProgramData" to "C:\\ProgramData")

    @Test
    fun `user mode and foreground live under LOCALAPPDATA, service mode under ProgramData`() {
        val u = WinPaths.layout(HostMode.USER, vars)
        assertEquals("C:\\Users\\alice\\AppData\\Local\\asom", u.base)
        assertEquals("C:\\Users\\alice\\AppData\\Local\\asom\\ledger", u.ledgerDir)
        assertEquals("C:\\Users\\alice\\AppData\\Local\\asom\\node", u.identityDir)
        assertEquals("C:\\Users\\alice\\AppData\\Local\\asom\\run\\ctl.sock", u.controlSocket)
        assertEquals("C:\\Users\\alice\\AppData\\Local\\asom\\config.json", u.configFile)
        assertEquals(u.copy(mode = HostMode.FOREGROUND), WinPaths.layout(HostMode.FOREGROUND, vars))
        laws.hit("layout-user")
        val s = WinPaths.layout(HostMode.SYSTEM, vars)
        assertEquals("C:\\ProgramData\\asom\\run\\ctl.sock", s.controlSocket)
        assertEquals("C:\\ProgramData\\asom\\node", s.identityDir)
        laws.hit("layout-service")
        // The identity directory is not the ledger directory and not the run directory (T17(f), and the socket directory has its own DACL).
        assertTrue(setOf(u.ledgerDir, u.identityDir, u.runDir).size == 3)
    }

    @Test
    fun `unsafe roots are refused, never guessed`() {
        val bad = mapOf(
            "missing" to emptyMap(),
            "relative" to mapOf("LOCALAPPDATA" to "Local\\asom"),
            "unc" to mapOf("LOCALAPPDATA" to "\\\\server\\share\\x"),
            "device" to mapOf("LOCALAPPDATA" to "\\\\?\\C:\\x"),
            "dotdot" to mapOf("LOCALAPPDATA" to "C:\\Users\\alice\\..\\bob\\AppData\\Local"),
            "roaming" to mapOf("LOCALAPPDATA" to "C:\\Users\\alice\\AppData\\Roaming"),
            "wildcard" to mapOf("LOCALAPPDATA" to "C:\\Users\\a*\\Local"),
            "control-char" to mapOf("LOCALAPPDATA" to "C:\\Users\\al\u0001ice"),
            "blank" to mapOf("LOCALAPPDATA" to "   "),
        )
        for ((name, v) in bad) {
            assertFailsWith<HostRefusedException>(name) { WinPaths.layout(HostMode.USER, v) }
            laws.hit("layout-refuses-unsafe")
        }
        assertFailsWith<HostRefusedException> { WinPaths.layout(HostMode.SYSTEM, mapOf("LOCALAPPDATA" to "C:\\x")) }
    }

    @Test
    fun `a socket path over the AF_UNIX limit is refused at path time, counted in UTF-8 bytes`() {
        // exactly at the limit: a root whose socket path is 107 bytes
        val root = "C:\\" + "r".repeat(WinPaths.MAX_SOCKET_PATH_BYTES - "C:\\".length - "\\asom\\run\\ctl.sock".length)
        val ok = WinPaths.layout(HostMode.USER, mapOf("LOCALAPPDATA" to root))
        assertEquals(WinPaths.MAX_SOCKET_PATH_BYTES, ok.controlSocket.length)
        assertFailsWith<HostRefusedException> { WinPaths.layout(HostMode.USER, mapOf("LOCALAPPDATA" to root + "r")) }
        // a multi-byte character costs more than one
        assertFailsWith<HostRefusedException> { WinPaths.layout(HostMode.USER, mapOf("LOCALAPPDATA" to root.dropLast(1) + "\u00e9")) }
        laws.hit("socket-path-limit")
    }

    @Test
    fun `environment variable names are case-insensitive`() {
        val env = WinEnv("alice", mapOf("localappdata" to "C:\\Users\\alice\\AppData\\Local", "PROGRAMDATA" to "C:\\ProgramData")) { null }
        assertEquals("C:\\Users\\alice\\AppData\\Local\\asom", WinPaths.layout(HostMode.USER, env.vars).base)
        assertEquals("C:\\ProgramData\\asom", WinPaths.layout(HostMode.SYSTEM, env.vars).base)
        laws.hit("env-case-insensitive")
    }

    @Test
    fun `prepare creates the directories, applies the DACL to each and tags the identity directory`() {
        val base = Files.createTempDirectory("asom-prepare-")
        val paths = NodePaths(HostMode.USER, base, base, base.resolve("ledger"), base.resolve("node"), base.resolve("run"), base.resolve("run/ctl.sock"))
        val acl = FakeAcl(OWNER_SID)
        val plan = AclPlan.userState(OWNER_SID)
        WinPaths.prepare(paths, acl, plan)
        for (d in listOf(paths.stateDir, paths.ledgerDir, paths.identityDir, paths.runtimeDir)) {
            assertTrue(Files.isDirectory(d), "$d")
            assertEquals(plan.required, acl.store.getValue(d).aces, "$d")
        }
        assertTrue(Files.readString(paths.identityDir.resolve("CACHEDIR.TAG")).startsWith("Signature: 8a477f597d28d172789f06886806bc55"))
        // service mode: the run directory takes its own plan
        val acl2 = FakeAcl(OWNER_SID)
        val svc = AclPlan.serviceState("S-1-5-80-1-2-3-4-5")
        val run = AclPlan.serviceRunDir(OWNER_SID, "S-1-5-80-1-2-3-4-5")
        WinPaths.prepare(paths, acl2, svc, run)
        assertEquals(run.required, acl2.store.getValue(paths.runtimeDir).aces)
        assertEquals(svc.required, acl2.store.getValue(paths.ledgerDir).aces)
        laws.hit("prepare-applies-acl")
    }

    // ---- NodeMutex ---------------------------------------------------------------------------------------------------

    @Test
    fun `the second node on a machine refuses to start and says why, and a released mutex can be taken again`() {
        val port = FakeMutex()
        val first = NodeMutex(port).acquire()
        assertTrue("Global\\asom-node" in port.held)
        val e = assertFailsWith<HostRefusedException> { NodeMutex(port).acquire() }
        assertTrue("another asom node already runs" in e.message!! && "Global\\asom-node" in e.message!!, e.message)
        laws.hit("mutex-single-identity")
        first.close()
        assertTrue(port.held.isEmpty())
        NodeMutex(port).acquire().close()
        laws.hit("mutex-releases")
    }

    @Test
    fun `an existing mutex that cannot be opened counts as held, and a failure to create it refuses too`() {
        val denied = FakeMutex().also { it.result = MutexResult.AccessDenied }
        assertTrue("different account" in assertFailsWith<HostRefusedException> { NodeMutex(denied).acquire() }.message!!)
        val failed = FakeMutex().also { it.result = MutexResult.Failed(87) }
        assertTrue("refusing to start without the single-identity guard" in assertFailsWith<HostRefusedException> { NodeMutex(failed).acquire() }.message!!)
        laws.hit("mutex-refuses-on-denied-and-failed")
        assertEquals("Global\\asom-node", NodeMutex.NAME)
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("paths-and-mutex")
}
