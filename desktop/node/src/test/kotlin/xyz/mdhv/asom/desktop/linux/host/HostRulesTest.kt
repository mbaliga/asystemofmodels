package xyz.mdhv.asom.desktop.linux.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.PowerSource
import xyz.mdhv.asom.desktop.governor.HostSignals
import xyz.mdhv.asom.desktop.linux.Fixtures
import xyz.mdhv.asom.desktop.linux.Report
import xyz.mdhv.asom.desktop.linux.probes.PowerProbe

class HostRulesTest {
    private val private700 = DirMeta(1000, 0b111_000_000)

    private fun input(
        real: Int = 1000, eff: Int = 1000, user: String = "alice", env: Map<String, String> = mapOf("XDG_RUNTIME_DIR" to "/run/user/1000"),
        os: OsRelease = OsRelease.parse("ID=ubuntu\n"), dir: DirMeta? = private700,
    ) = HostCheckInput(real, eff, user, env, os, dir)

    @Test
    fun `os-release parsing handles quotes, comments and ID`() {
        val os = OsRelease.parse("# c\nNAME=\"SteamOS\"\nID=steamos\nID_LIKE='arch'\n\nBAD LINE\nVARIANT_ID=steamdeck\n")
        assertEquals("steamos", os.id)
        assertTrue(os.isSteamOS)
        assertEquals("arch", os["ID_LIKE"])
        assertFalse(OsRelease.parse("ID=ubuntu\n").isSteamOS)
        assertFalse(OsRelease.parse("ID=\"steamos-like\"\n").isSteamOS, "exact match only")
        assertFalse(OsRelease.parse(null).isSteamOS)
        assertTrue(OsRelease.parse(Fixtures.fs("deck-oled").read("/etc/os-release")).isSteamOS)
        assertFalse(OsRelease.parse(Fixtures.fs("dell").read("/etc/os-release")).isSteamOS)
    }

    @Test
    fun `root is refused in every mode by real or effective uid`() {
        var n = 0
        for (m in HostMode.entries) {
            for ((r, e) in listOf(0 to 0, 0 to 1000, 1000 to 0)) {
                val why = LinuxHostModeRules.refusal(m, input(real = r, eff = e, user = "asom", env = mapOf("INVOCATION_ID" to "x", "XDG_RUNTIME_DIR" to "/run/user/1000")))
                assertNotNull(why, "$m $r/$e")
                assertTrue("root" in why)
                n++
            }
        }
        Report.line("host modes: $n root-refusal cases")
    }

    @Test
    fun `SYSTEM needs user asom, INVOCATION_ID and is not offered on SteamOS`() {
        val ok = input(user = "asom", env = mapOf("INVOCATION_ID" to "abc"))
        assertNull(LinuxHostModeRules.refusal(HostMode.SYSTEM, ok))
        assertNotNull(LinuxHostModeRules.refusal(HostMode.SYSTEM, ok.copy(userName = "alice")))
        assertNotNull(LinuxHostModeRules.refusal(HostMode.SYSTEM, ok.copy(env = emptyMap())))
        assertNotNull(LinuxHostModeRules.refusal(HostMode.SYSTEM, ok.copy(env = mapOf("INVOCATION_ID" to ""))))
        val steam = LinuxHostModeRules.refusal(HostMode.SYSTEM, ok.copy(osRelease = OsRelease.parse("ID=steamos\n")))
        assertNotNull(steam)
        assertTrue("SteamOS" in steam)
        assertNull(LinuxHostModeRules.refusal(HostMode.USER, input(os = OsRelease.parse("ID=steamos\n"))))
        assertNull(LinuxHostModeRules.refusal(HostMode.FOREGROUND, input(os = OsRelease.parse("ID=steamos\n"))))
    }

    @Test
    fun `USER needs XDG_RUNTIME_DIR owned by the invoking uid with mode 0700 exactly`() {
        assertNull(LinuxHostModeRules.refusal(HostMode.USER, input()))
        assertNotNull(LinuxHostModeRules.refusal(HostMode.USER, input(env = emptyMap())))
        assertNotNull(LinuxHostModeRules.refusal(HostMode.USER, input(dir = null)))
        assertNotNull(LinuxHostModeRules.refusal(HostMode.USER, input(dir = DirMeta(1001, 0b111_000_000))), "someone else's directory")
        for (bad in listOf(0b111_101_000, 0b111_000_100, 0b110_000_000, 0b111_111_111, 0b101_000_000)) {
            assertNotNull(LinuxHostModeRules.refusal(HostMode.USER, input(dir = DirMeta(1000, bad))), "mode ${bad.toString(8)}")
        }
        assertNull(LinuxHostModeRules.refusal(HostMode.FOREGROUND, input(env = emptyMap(), dir = null)), "foreground has no session requirement")
    }

    @Test
    fun `paths resolve to the spec layout and create nothing`() {
        val home = Path.of("/home/deck")
        val sys = LinuxPaths.resolve(HostMode.SYSTEM, emptyMap(), home)
        assertEquals(Path.of("/var/lib/asom"), sys.stateDir)
        assertEquals(Path.of("/var/lib/asom/ledger"), sys.ledgerDir)
        assertEquals(Path.of("/var/lib/asom/identity"), sys.identityDir)
        assertEquals(Path.of("/run/asom/ctl.sock"), sys.controlSocket)
        val user = LinuxPaths.resolve(HostMode.USER, mapOf("XDG_RUNTIME_DIR" to "/run/user/1000"), home)
        assertEquals(Path.of("/home/deck/.local/state/asom"), user.stateDir)
        assertEquals(Path.of("/home/deck/.local/share/asom"), user.dataDir)
        assertEquals(Path.of("/home/deck/.local/state/asom/ledger"), user.ledgerDir)
        assertEquals(Path.of("/home/deck/.local/share/asom/identity"), user.identityDir)
        assertFalse(user.identityDir.startsWith(user.stateDir), "the identity is outside the ledger/state directory (T17(f))")
        assertEquals(Path.of("/run/user/1000/asom/ctl.sock"), user.controlSocket)
        val xdg = LinuxPaths.resolve(HostMode.USER, mapOf("XDG_STATE_HOME" to "/s", "XDG_DATA_HOME" to "/d", "XDG_RUNTIME_DIR" to "/r"), home)
        assertEquals(Path.of("/s/asom"), xdg.stateDir)
        assertEquals(Path.of("/d/asom/identity"), xdg.identityDir)
        // relative XDG values are invalid per the spec and are ignored
        assertEquals(Path.of("/home/deck/.local/state/asom"), LinuxPaths.resolve(HostMode.USER, mapOf("XDG_STATE_HOME" to "rel", "XDG_RUNTIME_DIR" to "/r"), home).stateDir)
        // foreground without a runtime dir falls back to a private directory under the state dir (ERR-HOST-2)
        assertEquals(Path.of("/home/deck/.local/state/asom/run/ctl.sock"), LinuxPaths.resolve(HostMode.FOREGROUND, emptyMap(), home).controlSocket)
    }

    @Test
    fun `a control socket path over the AF_UNIX limit is refused`() {
        val long = "/r" + "x".repeat(120)
        assertFailsWith<HostRefusedException> { LinuxPaths.resolve(HostMode.USER, mapOf("XDG_RUNTIME_DIR" to long), Path.of("/h")) }
        val just = LinuxPaths.MAX_SOCKET_PATH_BYTES - "/asom/ctl.sock".length
        val exactly = "/" + "y".repeat(just - 1)
        assertEquals(107, LinuxPaths.resolve(HostMode.USER, mapOf("XDG_RUNTIME_DIR" to exactly), Path.of("/h")).controlSocket.toString().length)
    }

    @Test
    fun `prepare creates 0700 directories and a CACHEDIR_TAG in the identity directory only`() {
        val root = Files.createTempDirectory("asom-paths-")
        try {
            val p = LinuxPaths.resolve(HostMode.USER, mapOf("XDG_STATE_HOME" to "$root/s", "XDG_DATA_HOME" to "$root/d", "XDG_RUNTIME_DIR" to "$root/r"), root)
            LinuxPaths.prepare(p)
            for (d in listOf(p.stateDir, p.dataDir, p.ledgerDir, p.identityDir, p.runtimeDir)) {
                assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(d)), d.toString())
            }
            assertTrue(Files.readString(p.identityDir.resolve("CACHEDIR.TAG")).startsWith("Signature: 8a477f597d28d172789f06886806bc55"))
            assertFalse(Files.exists(p.stateDir.resolve("CACHEDIR.TAG")))
            LinuxPaths.prepare(p) // idempotent
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun steam(config: NodeConfig = NodeConfig()) = SteamOsPolicy(config)
    private val ac = PowerProbe(Fixtures.fs("deck-oled")).read()
    private val onBattery = PowerProbe(Fixtures.fs("deck-lcd")).read()
    private val allClear = HostSignals(docked = true, gameRunning = false, gameMode = false)

    @Test
    fun `deck rules - never a block lock, 2 s grace, battery hard NO, docked on AC with no game`() {
        val cfg = NodeConfig(gameModeLending = true)
        val p = steam(cfg)
        assertFalse(p.blockLockAllowed)
        assertFalse(steam(NodeConfig(gameModeLending = true, keepAwake = true)).blockLockAllowed, "no config turns a block lock on")
        assertEquals(2_000, p.graceMs)
        assertEquals(1_500, steam(NodeConfig(graceMsDeck = 1_500)).graceMs)

        var cases = 0
        fun blocked(power: xyz.mdhv.asom.desktop.PowerReading, s: HostSignals, why: String, config: NodeConfig = cfg) {
            val v = steam(config).evaluate(power, s)
            assertTrue((v.conditionBlocks + v.presenceBlocks).any { it.contains(why) }, "expected a block containing '$why', got $v")
            cases++
        }
        // the all-clear case: docked, AC, no game, Game Mode opted in or Desktop Mode
        assertEquals(emptyList(), steam(cfg).evaluate(ac, allClear).conditionBlocks)
        assertEquals(emptyList(), steam(cfg).evaluate(ac, allClear).presenceBlocks)
        cases++
        blocked(onBattery, allClear, "battery-hard-no")
        assertEquals(PowerSource.BATTERY, onBattery.source)
        blocked(ac.copy(source = PowerSource.UNKNOWN), allClear, "battery-hard-no")
        blocked(ac, allClear.copy(docked = false), "not-docked")
        blocked(ac, allClear.copy(docked = null), "docked-unknown")
        blocked(ac, allClear.copy(gameRunning = true), "game-running")
        blocked(ac, allClear.copy(gameRunning = null), "game-unknown")
        blocked(ac, allClear.copy(gameMode = true), "game-mode-lending-needs-opt-in", config = NodeConfig())
        blocked(ac, allClear.copy(gameMode = null), "game-mode-lending-needs-opt-in", config = NodeConfig())
        // Desktop Mode (gameMode=false) needs no opt-in; Game Mode with the opt-in is allowed
        assertEquals(emptyList(), steam(NodeConfig()).evaluate(ac, allClear.copy(gameMode = false)).conditionBlocks)
        assertEquals(emptyList(), steam(NodeConfig(gameModeLending = true)).evaluate(ac, allClear.copy(gameMode = true)).conditionBlocks)
        cases += 2
        // a game is a PRESENCE input (LP-0), battery and docking are CONDITIONS
        assertTrue(steam(cfg).evaluate(ac, allClear.copy(gameRunning = true)).presenceBlocks.isNotEmpty())
        assertTrue(steam(cfg).evaluate(onBattery, allClear).presenceBlocks.isEmpty())
        // the default signals (nothing known) never allow lending: unknown is the unsafe answer (ERR-DECK-1)
        val unknown = steam(cfg).evaluate(ac, HostSignals())
        assertTrue(unknown.conditionBlocks.isNotEmpty() || unknown.presenceBlocks.isNotEmpty())
        Report.line("steamos policy: $cases blocking/allowing cases")
    }
}
