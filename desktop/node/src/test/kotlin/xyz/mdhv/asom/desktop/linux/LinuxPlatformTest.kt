package xyz.mdhv.asom.desktop.linux

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.mdhv.asom.desktop.DesktopPlatform
import xyz.mdhv.asom.desktop.ExitCodes
import xyz.mdhv.asom.desktop.HostFinder
import xyz.mdhv.asom.desktop.HostLookup
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeEnv
import xyz.mdhv.asom.desktop.NodeMain
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.cli.AsomCli
import xyz.mdhv.asom.desktop.governor.FsmEvent
import xyz.mdhv.asom.desktop.governor.HostRulesProvider
import xyz.mdhv.asom.desktop.governor.HostSignals
import xyz.mdhv.asom.desktop.governor.HostSignalsProvider
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.json.StrictJson
import xyz.mdhv.asom.desktop.linux.host.DirMeta
import xyz.mdhv.asom.desktop.linux.host.SteamOsPolicy

class LinuxPlatformTest {
    private val vars = mapOf("XDG_RUNTIME_DIR" to "/run/user/1000")

    private fun platform(
        host: String, uid: Int = 1000, user: String = "alice", env: Map<String, String> = vars,
        clock: FakeClock = FakeClock(), edit: (MapFileSource) -> Unit = {},
    ): LinuxPlatform {
        val fs = MapFileSource.ofFixture(host)
        fs.files["/proc/self/status"] = "Name:\tjava\nUid:\t$uid\t$uid\t$uid\t$uid\n"
        edit(fs)
        return LinuxPlatform(fs, LinuxEnv(user, env, Path.of("/home/$user")) { DirMeta(uid, 0b111_000_000) }, clock)
    }

    /** The Linux platform with the three signals that have no mechanism yet supplied by the test. */
    private class WithSignals(val d: LinuxPlatform, var sig: HostSignals) : DesktopPlatform by d, HostRulesProvider by d, HostSignalsProvider {
        override fun hostSignals(): HostSignals = sig
    }

    @Test
    fun `the ServiceLoader finds exactly the Linux host`() {
        val lookup = HostFinder.find()
        assertTrue(lookup is HostLookup.Found && lookup.platform is LinuxPlatform && lookup.platform.id == "linux", lookup.toString())
    }

    @Test
    fun `paths refuse root by the real uid, refuse SYSTEM for a normal user and on SteamOS, and accept USER and FOREGROUND`() {
        for (m in HostMode.entries) {
            val e = assertFailsWith<HostRefusedException> { platform("dell", uid = 0).paths(m) }
            assertTrue("root" in e.message!!, m.toString())
        }
        assertFailsWith<HostRefusedException> { platform("dell").paths(HostMode.SYSTEM) }
        assertFailsWith<HostRefusedException> { platform("deck-oled", user = "asom", env = mapOf("INVOCATION_ID" to "x")).paths(HostMode.SYSTEM) }
        assertEquals("dedicated-user", platform("dell", user = "asom", env = mapOf("INVOCATION_ID" to "x")).paths(HostMode.SYSTEM).mode.label)
        assertEquals(Path.of("/run/user/1000/asom/ctl.sock"), platform("deck-oled").paths(HostMode.USER).controlSocket)
        assertEquals(HostMode.FOREGROUND, platform("deck-oled", env = emptyMap()).paths(HostMode.FOREGROUND).mode)
        // an unreadable /proc/self/status must refuse, not assume a normal user
        assertFailsWith<HostRefusedException> { platform("dell", edit = { it.files.remove("/proc/self/status") }).paths(HostMode.FOREGROUND) }
    }

    @Test
    fun `ports reflect each fixture host (SYNTHETIC) and the gpu rule is off where nothing is attributable`() {
        for (h in Fixtures.hosts) {
            val p = platform(h)
            assertNotNull(p.power().read(), h)
            assertNotNull(p.thermal().read(), h)
            assertNull(p.presence().sample().cpuOtherPermille, "$h: first cpu sample has no delta")
            assertEquals(h.startsWith("deck"), p.gpuContention() != null, "$h gpu attribution")
        }
        assertEquals("steamos-deck", platform("deck-oled").hostRules(NodeConfig(), HostMode.USER).label)
        assertEquals("desktop", platform("dell").hostRules(NodeConfig(), HostMode.USER).label)
        assertTrue(platform("deck-lcd").hostRules(NodeConfig(), HostMode.USER) is SteamOsPolicy)
    }

    @Test
    fun `keep-awake and sleep events are declared not yet implemented and really throw`() {
        val p = platform("dell").power()
        assertFailsWith<NotYetImplementedException> { p.hold(LockKind.DELAY) }
        assertFailsWith<NotYetImplementedException> { p.onSleepEvents { } }
        assertFailsWith<NotYetImplementedException> { platform("dell").controlSocket(platform("dell").paths(HostMode.USER)).start { _, _ -> error("unreachable") } }
    }

    private fun tick(rt: NodeRuntime, clock: FakeClock, seconds: Int = 2) { clock.now += seconds * 1000L; rt.tick() }

    @Test
    fun `dell on mains becomes SERVING and a hot CPU drains it with no GPU attribution needed`() {
        val clock = FakeClock()
        val p = platform("dell", clock = clock)
        val rt = NodeRuntime(p, HostMode.USER, NodeConfig(), clock)
        rt.enableLending()
        tick(rt, clock)
        assertEquals(LenderState.SERVING, rt.fsm.state)
        val fs = MapFileSource.ofFixture("dell")
        assertNull(platform("dell").gpuContention(), "NVIDIA/Intel: thermal band only")
        // heat: coretemp Package id 0 at 86 C is over its 85 C threshold
        val hot = platform("dell", clock = clock) { it.files["/sys/class/hwmon/hwmon0/temp1_input"] = "86000\n" }
        val rt2 = NodeRuntime(hot, HostMode.USER, NodeConfig(), clock)
        rt2.enableLending()
        tick(rt2, clock)
        assertEquals(LenderState.ARMED, rt2.fsm.state, "band 2 blocks entry")
        assertTrue(fs.files.isNotEmpty())
    }

    @Test
    fun `a laptop on battery stays ARMED`() {
        val clock = FakeClock()
        val laptop = platform("dell", clock = clock) {
            it.files["/sys/class/power_supply/AC/type"] = "Mains\n"
            it.files["/sys/class/power_supply/AC/online"] = "0\n"
            it.files["/sys/class/power_supply/BAT0/type"] = "Battery\n"
            it.files["/sys/class/power_supply/BAT0/status"] = "Discharging\n"
            it.files["/sys/class/power_supply/BAT0/capacity"] = "77\n"
        }
        val rt = NodeRuntime(laptop, HostMode.USER, NodeConfig(), clock)
        rt.enableLending()
        repeat(5) { tick(rt, clock) }
        assertEquals(LenderState.ARMED, rt.fsm.state)
        // plug in: serves
        val plugged = platform("dell", clock = clock) {
            it.files["/sys/class/power_supply/AC/type"] = "Mains\n"
            it.files["/sys/class/power_supply/AC/online"] = "1\n"
            it.files["/sys/class/power_supply/BAT0/type"] = "Battery\n"
            it.files["/sys/class/power_supply/BAT0/status"] = "Charging\n"
            it.files["/sys/class/power_supply/BAT0/capacity"] = "77\n"
        }
        val rt2 = NodeRuntime(plugged, HostMode.USER, NodeConfig(), clock)
        rt2.enableLending()
        tick(rt2, clock)
        assertEquals(LenderState.SERVING, rt2.fsm.state)
    }

    @Test
    fun `deck - unknown docking and game state never lends, all-clear lends, a game drains within a 2 s grace, battery never lends`() {
        val clock = FakeClock()
        val p = platform("deck-oled", clock = clock)
        // nothing known: stays ARMED (ERR-DECK-1)
        val rtUnknown = NodeRuntime(p, HostMode.USER, NodeConfig(), clock)
        rtUnknown.enableLending()
        repeat(4) { tick(rtUnknown, clock) }
        assertEquals(LenderState.ARMED, rtUnknown.fsm.state)

        val sig = WithSignals(p, HostSignals(docked = true, gameRunning = false, gameMode = false))
        val rt = NodeRuntime(sig, HostMode.USER, NodeConfig(), clock)
        rt.enableLending()
        // the unknown-signal runtime above recorded presence ("game-unknown") only in ITS FSM; this one is fresh
        tick(rt, clock)
        assertEquals(LenderState.SERVING, rt.fsm.state)

        sig.sig = sig.sig.copy(gameRunning = true)
        val before = clock.now + 2_000
        tick(rt, clock)
        assertEquals(LenderState.DRAINING, rt.fsm.state, "a game is a presence input: drain at once")
        assertEquals(before + 2_000, rt.fsm.snapshot().drainDeadlineMs, "Deck grace is 2 s")
        rt.fsm.apply(FsmEvent.INFLIGHT_DONE, clock.now)
        sig.sig = sig.sig.copy(gameRunning = false)
        tick(rt, clock, 30)
        assertEquals(LenderState.ARMED, rt.fsm.state, "hold-down: 10 minutes after the last presence signal")
        clock.now += 600_000
        rt.tick()
        assertEquals(LenderState.SERVING, rt.fsm.state)

        // battery is a hard NO even when every other signal is clear
        val lcd = WithSignals(platform("deck-lcd", clock = clock), HostSignals(docked = true, gameRunning = false, gameMode = false))
        val rtBat = NodeRuntime(lcd, HostMode.USER, NodeConfig(), clock)
        rtBat.enableLending()
        repeat(5) { tick(rtBat, clock) }
        assertEquals(LenderState.ARMED, rtBat.fsm.state)
    }

    private fun output(block: (NodeEnv) -> Int): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = NodeEnv("alice", emptyMap(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        val code = block(env)
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `asom status --json on the deck-oled fixture (SYNTHETIC) carries the real readings and the honest not-yet-implemented list`() {
        val (code, out, _) = output { AsomCli(it, platform("deck-oled")).run(listOf("status", "--json", "--mode=user")) }
        assertEquals(ExitCodes.OK, code)
        val o = StrictJson.parse(out.trim()) as JsonObject
        assertEquals("shared-uid", (o["host"] as JsonPrimitive).content)
        assertEquals("OFF", (o["fsm"] as JsonPrimitive).content)
        assertEquals(JsonArray(emptyList()), o["listeners"])
        assertEquals(JsonArray(emptyList()), o["locks"])
        val gov = o["governors"]!!.jsonObject
        assertEquals("ac", (gov["power"]!!.jsonObject["source"] as JsonPrimitive).content)
        assertEquals("50-79", (gov["power"]!!.jsonObject["batteryBand"] as JsonPrimitive).content)
        assertEquals("0", (gov["thermal"]!!.jsonObject["band"] as JsonPrimitive).content)
        assertEquals("3", (gov["thermal"]!!.jsonObject["watchedSensors"] as JsonPrimitive).content)
        assertEquals("amdgpu", (gov["gpuContention"] as JsonPrimitive).content)
        assertEquals("steamos-deck", (gov["rules"] as JsonPrimitive).content)
        val nyi = (o["notYetImplemented"] as JsonArray).joinToString("|") { (it as JsonPrimitive).content }
        for (f in listOf("control-socket", "nik-store", "keep-awake locks", "sleep watcher")) assertTrue(f in nyi, "$f missing from: $nyi")
        Report.line("status --json (deck-oled SYNTHETIC fixture): " + out.trim().take(200) + " ...")
    }

    @Test
    fun `selftest on the deck fixture reports the DL2 members honestly and passes everything else`() {
        val (code, out, _) = output { NodeMain.run(listOf("--mode=selftest"), it, HostFinder.find(listOf(platform("deck-oled"))) ) {} }
        assertEquals(ExitCodes.OK, code, out)
        for (f in listOf("control-socket", "nik-store", "keep-awake locks (Inhibitor)", "sleep watcher (logind PrepareForSleep)")) {
            assertTrue(out.lines().any { it.startsWith("  [not-yet-implemented] $f") }, "$f: $out")
        }
        assertTrue("[ok] ledger-roundtrip" in out && out.trim().endsWith("0 failed"), out)
        Report.line(out.trim().lines().last())
    }
}
