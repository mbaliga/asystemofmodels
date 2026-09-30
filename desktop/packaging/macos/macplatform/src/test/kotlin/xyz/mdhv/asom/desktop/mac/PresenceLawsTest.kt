package xyz.mdhv.asom.desktop.mac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.governor.FsmEvent
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.mac.fakes.FakeHelper
import xyz.mdhv.asom.desktop.mac.helper.Fields
import xyz.mdhv.asom.desktop.mac.helper.HValue
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.presence.MacPresenceReader

/**
 * W07-presence on macOS inputs (design LP-0 to LP-2, macos.md 2.1): the classification table over EVERY combination of the three
 * helper inputs against an independent oracle, then the composition of the 10-minute idle rule with the 10-minute hold-down through
 * the real NodeRuntime, its FSM and this platform's rules, on a fake clock.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresenceLawsTest {
    private val laws = LawCounter(
        listOf(
            "table-oracle", "other-console-user-is-presence", "unknown-is-presence", "idle-boundary", "locked-alone-suffices",
            "composition-20-minutes", "presence-drains-at-once", "user-returns-drains", "hold-down-not-configurable-below",
        ),
    )

    @AfterAll
    fun report() = laws.assertAllExercised("presence")

    /** The rule, written a second way: eligible iff console user is self AND (idle >= 10 min OR locked). */
    private fun oracleEligible(self: Boolean?, idle: Long?, locked: Boolean?): Boolean =
        self == true && ((idle != null && idle >= 600_000) || locked == true)

    @Test
    fun `the classification table over every combination of the three inputs agrees with the oracle`() {
        val selfs = listOf<Boolean?>(null, false, true)
        val idles = listOf<Long?>(null, 0L, 1L, 599_999L, 600_000L, 600_001L, 86_400_000L)
        val lockeds = listOf<Boolean?>(null, false, true)
        var cases = 0
        for (s in selfs) for (i in idles) for (l in lockeds) {
            val v = MacPresenceReader.classify(HelperClient.PresenceInfo(i, l, s))
            assertEquals(!oracleEligible(s, i, l), v.present, "self=$s idle=$i locked=$l -> ${v.blocks}")
            if (!v.present) assertEquals(emptyList(), v.blocks)
            cases++
        }
        assertEquals(3 * 7 * 3, cases)
        laws.hit("table-oracle", cases.toLong())
    }

    @Test
    fun `another console user (fast user switching) is presence even when idle and locked`() {
        val v = MacPresenceReader.classify(HelperClient.PresenceInfo(99_999_999, true, false))
        assertTrue(v.present)
        assertEquals(listOf("console-user-other"), v.blocks)
        laws.hit("other-console-user-is-presence")
    }

    @Test
    fun `every unknown input is presence`() {
        assertEquals(listOf("console-user-unknown"), MacPresenceReader.classify(HelperClient.PresenceInfo(700_000, false, null)).blocks)
        assertEquals(listOf("presence-unreadable"), MacPresenceReader.classify(HelperClient.PresenceInfo(null, null, true)).blocks)
        assertTrue(MacPresenceReader.classify(HelperClient.PresenceInfo(null, false, true)).present)
        val h = FakeHelper()
        h.lost = "gone"
        assertEquals(listOf("presence-unreadable"), MacPresenceReader(HelperClient(h)).verdict().blocks)
        h.lost = null
        h.failures["presence.get"] = Reply.Failure(null, "UNAVAILABLE", "x")
        assertTrue(MacPresenceReader(HelperClient(h)).verdict().present)
        laws.hit("unknown-is-presence", 4)
    }

    @Test
    fun `ten minutes idle is the boundary and a locked screen alone is enough`() {
        assertTrue(MacPresenceReader.classify(HelperClient.PresenceInfo(599_999, false, true)).present)
        assertFalse(MacPresenceReader.classify(HelperClient.PresenceInfo(600_000, false, true)).present)
        laws.hit("idle-boundary", 2)
        assertFalse(MacPresenceReader.classify(HelperClient.PresenceInfo(0, true, true)).present)
        assertFalse(MacPresenceReader.classify(HelperClient.PresenceInfo(null, true, true)).present)
        laws.hit("locked-alone-suffices", 2)
    }

    /** A platform whose presence input the test scripts as a function of the fake clock. */
    private class Scenario(val clock: FakeClock) {
        var lastInputAt = clock.now
        var locked = false
        var console = true
        val helper = FakeHelper(handler = { r ->
            if (r.op == "presence.get") Reply.Success(
                "presence.get", r.id,
                Fields.of(
                    "hidIdleMs" to HValue.I(clock.now - lastInputAt), "screenLocked" to HValue.B(locked), "consoleUserIsSelf" to HValue.B(console),
                ),
            ) else null
        })
        val idleCpu = object : xyz.mdhv.asom.desktop.mac.presence.CpuLoadSource {
            override fun systemLoad() = 0.05
            override fun processLoad() = 0.01
        }
        val platform = MacPlatform(helperTransport = helper, env = MacEnv("me", "/Users/me", emptyMap(), { "/tmp" }, { 501 }), options = MacOptions(null), cpu = idleCpu)
        val runtime = NodeRuntime(platform, HostMode.USER, NodeConfig(), clock)

        fun step(ms: Long): List<LenderState> {
            clock.now += ms
            return runtime.tick().map { it.to }
        }
    }

    @Test
    fun `idle ten minutes plus the ten minute hold-down, lending starts about twenty minutes after the last input`() {
        val clock = FakeClock(50_000_000)
        val s = Scenario(clock)
        s.runtime.enableLending()
        assertEquals(LenderState.ARMED, s.runtime.fsm.state)
        s.lastInputAt = clock.now
        var servedAt: Long? = null
        val t0 = clock.now
        var t = 0L
        while (t <= 25 * 60_000L && servedAt == null) {
            s.step(2_000)
            t += 2_000
            if (s.runtime.fsm.state == LenderState.SERVING) servedAt = t
        }
        assertTrue(servedAt != null, "the node served eventually")
        // presence signals continue until idle reaches 600,000 ms; the hold-down runs from the last one: 600,000 + 600,000
        assertTrue(servedAt!! in 1_190_000..1_206_000, "served at ${servedAt / 1000} s after the last input, expected about 1200 s")
        laws.hit("composition-20-minutes")
        assertEquals(t0 + servedAt, clock.now)
    }

    @Test
    fun `the user coming back drains at once, and lending waits another ten idle minutes and ten hold-down minutes`() {
        val clock = FakeClock(70_000_000)
        val s = Scenario(clock)
        s.runtime.enableLending()
        s.lastInputAt = clock.now - 3_600_000 // idle for an hour
        s.step(1_000)
        // the hold-down has not started (no presence signal yet), so an idle Mac serves at the first tick
        assertEquals(LenderState.SERVING, s.runtime.fsm.state)
        s.lastInputAt = clock.now // the owner touches the keyboard
        val steps = s.runtime.tick()
        assertTrue(LenderState.DRAINING in steps.map { it.to }, "the same evaluation that sees the input drains: $steps")
        laws.hit("user-returns-drains")
        laws.hit("presence-drains-at-once")
        s.runtime.fsm.apply(FsmEvent.INFLIGHT_DONE, clock.now)
        assertEquals(LenderState.ARMED, s.runtime.fsm.state)
        // idle again from now: not SERVING for 20 more minutes
        var served = false
        var waited = 0L
        while (waited < 19 * 60_000L) {
            s.step(2_000)
            waited += 2_000
            if (s.runtime.fsm.state == LenderState.SERVING) served = true
        }
        assertFalse(served, "still ARMED 19 minutes after the last input")
    }

    @Test
    fun `a different console user drains a serving Mac`() {
        val clock = FakeClock(90_000_000)
        val s = Scenario(clock)
        s.runtime.enableLending()
        s.lastInputAt = clock.now - 3_600_000
        s.step(1_000)
        assertEquals(LenderState.SERVING, s.runtime.fsm.state)
        s.console = false
        s.step(1_000)
        assertEquals(LenderState.DRAINING, s.runtime.fsm.state)
        laws.hit("other-console-user-is-presence")
    }

    @Test
    fun `the hold-down cannot be configured below ten minutes, and the idle threshold cannot be either`() {
        assertFailsWith<IllegalArgumentException> { xyz.mdhv.asom.desktop.governor.FsmConfig(graceMs = 1, holdDownMs = 599_999) }
        assertFailsWith<IllegalArgumentException> { MacPresenceReader(HelperClient(FakeHelper()), 1) }
        laws.hit("hold-down-not-configurable-below", 2)
    }
}
