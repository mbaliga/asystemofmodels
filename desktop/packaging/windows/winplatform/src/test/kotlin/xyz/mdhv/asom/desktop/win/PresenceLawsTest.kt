package xyz.mdhv.asom.desktop.win

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.PresenceTagged
import xyz.mdhv.asom.desktop.governor.AvailabilityWire
import xyz.mdhv.asom.desktop.governor.DECLINE_PEER_UNAVAILABLE
import xyz.mdhv.asom.desktop.governor.FsmEvent
import xyz.mdhv.asom.desktop.governor.HostSignals
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.win.api.NotificationState
import xyz.mdhv.asom.desktop.win.api.RawPowerStatus
import xyz.mdhv.asom.desktop.win.api.WtsSession
import xyz.mdhv.asom.desktop.win.fakes.FakeClock
import xyz.mdhv.asom.desktop.win.fakes.FakeCpu
import xyz.mdhv.asom.desktop.win.fakes.FakeNative
import xyz.mdhv.asom.desktop.win.fakes.FakePowerStatus
import xyz.mdhv.asom.desktop.win.fakes.FakeSessions
import xyz.mdhv.asom.desktop.win.fakes.FakeUserInput
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.fakeEnv
import xyz.mdhv.asom.desktop.win.power.PowerStatusParser
import xyz.mdhv.asom.desktop.win.presence.PresenceVerdict
import xyz.mdhv.asom.desktop.win.presence.WinPresencePort
import xyz.mdhv.asom.desktop.win.presence.WinPresenceReader
import xyz.mdhv.asom.desktop.win.presence.WinRules

/**
 * W07-presence on Windows inputs (LP-0, LP-1, LP-2 through the shared FSM). The reader's verdict table is checked case by
 * case; the FSM laws are then driven through the real `NodeRuntime` with a real `WinPlatform` over fakes, and the timing
 * expectations are computed from the spec's numbers (10 minutes idle, 600,000 ms hold-down), not from the implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresenceLawsTest {
    private val laws = LawCounter(
        listOf(
            "user-idle-or-locked", "user-fullscreen-blocks", "user-unknown-is-present", "lock-needs-two-signals", "service-idle",
            "service-no-session", "service-serve-while-logged-out", "idle-threshold-floor", "cpu-first-sample-null",
            "lp2-composed-timing", "lp2-locked-serves-at-once", "lp1-wire-state-only", "battery-saver-blocks", "presence-tagged-not-serialised",
        ),
    )

    private val tenMin = 600_000L
    private fun session(user: String = "alice", locked: Boolean? = false, idle: Long? = 0) = WtsSession(1, 0, user, locked, idle)

    private fun reader(service: Boolean, input: FakeUserInput, sessions: FakeSessions, options: WinOptions = WinOptions()) =
        WinPresenceReader(service, input, sessions, options)

    // ---- user mode --------------------------------------------------------------------------------------------------

    @Test
    fun `user mode lends only when idle for ten minutes or locked, and never with a full-screen app`() {
        data class C(val name: String, val idle: Long?, val state: NotificationState?, val locked: Boolean?, val expectBlocked: Boolean)
        val cases = listOf(
            C("active", 0, NotificationState.ACCEPTS_NOTIFICATIONS, false, true),
            C("idle 599,999", tenMin - 1, NotificationState.ACCEPTS_NOTIFICATIONS, false, true),
            C("idle exactly 10 min", tenMin, NotificationState.ACCEPTS_NOTIFICATIONS, false, false),
            C("idle 3 h", 3 * 3_600_000L, NotificationState.QUIET_TIME, false, false),
            C("locked (both signals)", 0, NotificationState.NOT_PRESENT, true, false),
            C("lock flag alone", 0, NotificationState.ACCEPTS_NOTIFICATIONS, true, true),
            C("NOT_PRESENT alone (screen saver)", 0, NotificationState.NOT_PRESENT, false, true),
            C("fullscreen D3D, idle 1 h", 3_600_000, NotificationState.RUNNING_D3D_FULL_SCREEN, false, true),
            C("busy (full-screen app), locked", 0, NotificationState.BUSY, true, true),
            C("presentation mode, idle", 3_600_000, NotificationState.PRESENTATION_MODE, false, true),
            C("Store app full-screen, idle", 3_600_000, NotificationState.APP, false, true),
            C("idle unreadable", null, NotificationState.ACCEPTS_NOTIFICATIONS, false, true),
            C("state unreadable, idle 1 h", 3_600_000, null, false, true),
            C("everything unreadable", null, null, null, true),
        )
        for (c in cases) {
            val r = reader(false, FakeUserInput(c.idle, c.state), FakeSessions(current = session(locked = c.locked)))
            val v = r.verdict()
            assertEquals(c.expectBlocked, v.present, "${c.name}: ${v.blocks}")
            laws.hit(if (c.name.startsWith("idle") || c.name.startsWith("locked")) "user-idle-or-locked" else "user-unknown-is-present")
            if (c.state in setOf(NotificationState.RUNNING_D3D_FULL_SCREEN, NotificationState.BUSY, NotificationState.PRESENTATION_MODE, NotificationState.APP)) laws.hit("user-fullscreen-blocks")
            if (c.name.contains("alone")) laws.hit("lock-needs-two-signals")
            if (c.idle == null || c.state == null) laws.hit("user-unknown-is-present")
        }
    }

    @Test
    fun `a session or input API that throws counts as present`() {
        val r = reader(false, FakeUserInput(tenMin, NotificationState.ACCEPTS_NOTIFICATIONS), FakeSessions(throws = true))
        assertEquals(PresenceVerdict(listOf("presence-unreadable")), r.verdict())
        assertEquals(listOf("presence-unreadable"), WinRules(NodeConfig()) { error("boom") }.evaluate(PowerStatusParser.parse(FakePowerStatus.DESKTOP_AC), HostSignals()).presenceBlocks)
        laws.hit("user-unknown-is-present")
    }

    // ---- service mode -----------------------------------------------------------------------------------------------

    @Test
    fun `service mode uses the console session idle time and has no full-screen check`() {
        val idle = reader(true, FakeUserInput(), FakeSessions(console = session(idle = tenMin)))
        assertFalse(idle.verdict().present)
        laws.hit("service-idle")
        assertTrue(reader(true, FakeUserInput(), FakeSessions(console = session(idle = tenMin - 1))).verdict().present)
        assertTrue(reader(true, FakeUserInput(), FakeSessions(console = session(idle = null))).verdict().present, "unreadable idle is present")
        // a locked flag without idle does not open it (its polarity is disputed, AW07)
        assertTrue(reader(true, FakeUserInput(), FakeSessions(console = session(locked = true, idle = 0))).verdict().present)
        // the user-mode inputs are not consulted from session 0: a "full-screen" state changes nothing here
        assertFalse(reader(true, FakeUserInput(tenMin, NotificationState.RUNNING_D3D_FULL_SCREEN), FakeSessions(console = session(idle = tenMin))).verdict().present)
    }

    @Test
    fun `no console user blocks unless serveWhileLoggedOut is on`() {
        for (s in listOf(null, session(user = ""), session(user = "  "))) {
            val off = reader(true, FakeUserInput(), FakeSessions(console = s))
            assertEquals(listOf("no-user-session"), off.verdict().blocks)
            laws.hit("service-no-session")
            val on = reader(true, FakeUserInput(), FakeSessions(console = s), WinOptions(serveWhileLoggedOut = true))
            assertFalse(on.verdict().present)
            laws.hit("service-serve-while-logged-out")
        }
        // with a user present, serveWhileLoggedOut changes nothing
        assertTrue(reader(true, FakeUserInput(), FakeSessions(console = session(idle = 0)), WinOptions(serveWhileLoggedOut = true)).verdict().present)
    }

    @Test
    fun `the idle threshold cannot be configured below ten minutes`() {
        assertFailsWith<IllegalArgumentException> { WinPresenceReader(false, FakeUserInput(), FakeSessions(), WinOptions(), tenMin - 1) }
        assertEquals(tenMin, WinPresenceReader(false, FakeUserInput(), FakeSessions()).idleThresholdMs)
        laws.hit("idle-threshold-floor")
    }

    @Test
    fun `the CPU contention sample is null the first time, never zero, and never negative`() {
        val cpu = FakeCpu(0.9, 0.1)
        val p = WinPresencePort(cpu)
        assertNull(p.sample().cpuOtherPermille)
        laws.hit("cpu-first-sample-null")
        assertEquals(800, p.sample().cpuOtherPermille)
        cpu.system = 0.05
        cpu.process = 0.2
        assertEquals(0, p.sample().cpuOtherPermille, "process load above system load clamps to zero")
        cpu.system = null
        assertNull(p.sample().cpuOtherPermille, "unavailable is null, not zero")
    }

    // ---- the FSM laws through the real runtime ----------------------------------------------------------------------

    private class Rig(status: RawPowerStatus = FakePowerStatus.DESKTOP_AC) {
        val clock = FakeClock(10_000_000L)
        val input = FakeUserInput(0, NotificationState.ACCEPTS_NOTIFICATIONS)
        val sessions = FakeSessions(current = WtsSession(1, 0, "alice", false, 0))
        val native = FakeNative(userInput = input, sessions = sessions, powerStatus = FakePowerStatus(status))
        val platform = WinPlatform(fakeEnv(), native.bundle(), WinOptions(), clock)
        val config = NodeConfig()
        val rt = NodeRuntime(platform, HostMode.USER, config, clock)
    }

    @Test
    fun `LP-2 composes with the ten-minute idle rule and never serves before the hold-down has elapsed`() {
        val rig = Rig()
        val t0 = rig.clock.now
        rig.rt.enableLending()
        var lastPresenceTick = -1L
        var firstServing = -1L
        val tick = 2_000L
        for (i in 0..700) {
            val now = t0 + i * tick
            rig.clock.now = now
            rig.input.idle = now - t0 // the last input was at t0
            val steps = rig.rt.tick()
            if (steps.any { it.event == FsmEvent.PRESENCE_SIGNAL }) lastPresenceTick = now
            if (rig.rt.fsm.state == LenderState.SERVING && firstServing < 0) firstServing = now
        }
        assertTrue(lastPresenceTick >= t0 + tenMin - tick, "presence signals run while idle < 10 min (last at ${lastPresenceTick - t0})")
        assertTrue(lastPresenceTick < t0 + tenMin, "and stop once idle reaches 10 min")
        assertTrue(firstServing >= lastPresenceTick + tenMin, "SERVING no sooner than 600,000 ms after the last presence signal")
        assertTrue(firstServing < lastPresenceTick + tenMin + tick, "and at the first evaluation after that")
        assertTrue(firstServing >= t0 + 2 * tenMin - tick, "so about twenty minutes after the last input (windows ERRATA WIN-PRES-1)")
        laws.hit("lp2-composed-timing")
    }

    @Test
    fun `a locked workstation lends at once when no presence signal is recent`() {
        val rig = Rig()
        rig.input.state = NotificationState.NOT_PRESENT
        rig.sessions.current = WtsSession(1, 0, "alice", true, 0)
        rig.rt.enableLending()
        rig.rt.tick()
        assertEquals(LenderState.SERVING, rig.rt.fsm.state)
        laws.hit("lp2-locked-serves-at-once")
        // the user returns: presence drains at once
        rig.input.state = NotificationState.ACCEPTS_NOTIFICATIONS
        rig.sessions.current = WtsSession(1, 0, "alice", false, 0)
        rig.clock.now += 2_000
        rig.rt.tick()
        assertEquals(LenderState.DRAINING, rig.rt.fsm.state)
    }

    @Test
    fun `LP-1 the wire shows only the state and one code, whatever the presence reason`() {
        val reasons = mutableSetOf<String>()
        val wires = HashSet<AvailabilityWire>()
        for (setup in 0..3) {
            val rig = Rig()
            when (setup) {
                0 -> { rig.input.idle = 0 }
                1 -> { rig.input.idle = 3_600_000; rig.input.state = NotificationState.RUNNING_D3D_FULL_SCREEN }
                2 -> { rig.input.idle = null }
                3 -> { rig.sessions.throws = true }
            }
            rig.rt.enableLending()
            rig.rt.tick()
            val v = rig.platform.presenceReader(HostMode.USER).verdict()
            assertTrue(v.present)
            reasons += v.blocks
            wires += rig.rt.fsm.wire()
        }
        assertTrue(reasons.size >= 4, "several different local reasons: $reasons")
        assertEquals(setOf(AvailabilityWire(LenderState.ARMED, DECLINE_PEER_UNAVAILABLE, null)), wires, "yet one wire projection")
        laws.hit("lp1-wire-state-only")
    }

    @Test
    fun `battery saver and a laptop on battery are condition blocks`() {
        val saver = Rig(RawPowerStatus(1, 9, 80, 1))
        saver.input.idle = 3_600_000
        saver.rt.enableLending()
        saver.rt.tick()
        assertEquals(LenderState.ARMED, saver.rt.fsm.state, "battery saver blocks")
        laws.hit("battery-saver-blocks")
        val onBattery = Rig(FakePowerStatus.LAPTOP_BATTERY)
        onBattery.input.idle = 3_600_000
        onBattery.rt.enableLending()
        onBattery.rt.tick()
        assertEquals(LenderState.ARMED, onBattery.rt.fsm.state)
        val ok = Rig(FakePowerStatus.LAPTOP_AC)
        ok.input.idle = 3_600_000
        ok.rt.enableLending()
        ok.rt.tick()
        assertEquals(LenderState.SERVING, ok.rt.fsm.state, "control: the same idle laptop on AC without saver serves")
    }

    @Test
    fun `presence-tagged types are never serialisable`() {
        assertTrue(PresenceVerdict::class.java.isAnnotationPresent(PresenceTagged::class.java))
        assertTrue(PresenceVerdict::class.java.annotations.none { it.annotationClass.simpleName == "Serializable" })
        laws.hit("presence-tagged-not-serialised")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("presence")
}
