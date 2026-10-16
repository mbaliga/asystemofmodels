package xyz.mdhv.asom.ut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString

class FakeClock(var now: Long = 0) : MonotonicClock {
    override fun nowMs(): Long = now
}

/** UTC02: the lifecycle FSM of ubuntu-touch.md 3.3 and the laws L-UT1 to L-UT3, driven by scripted events on a fake clock. */
class Utc02LifecycleTest {
    private class Runner {
        val clock = FakeClock()
        val lifecycle = NodeLifecycle(clock)
    }

    private fun parseEvent(name: String): LcEvent = when {
        name == "ledger-ready" -> LcEvent.LedgerReady
        name == "ledger-failed" -> LcEvent.LedgerFailed
        name == "ui:active" -> LcEvent.Ui(UiLifecycle.ACTIVE)
        name == "ui:inactive" -> LcEvent.Ui(UiLifecycle.INACTIVE)
        name == "ui:suspending" -> LcEvent.Ui(UiLifecycle.SUSPENDING)
        name == "peers-open" -> LcEvent.PeersOpened
        name == "peers-close" -> LcEvent.PeersClosed
        name.startsWith("request:") -> LcEvent.RequestArrived(name.removePrefix("request:"))
        name == "session-open" -> LcEvent.SessionOpened
        name == "session-close" -> LcEvent.SessionClosed
        name.startsWith("attempt:") -> LcEvent.AttemptStarted(name.removePrefix("attempt:"))
        name.startsWith("body:") -> LcEvent.BodySent(name.removePrefix("body:"))
        name.startsWith("finished:") -> LcEvent.AttemptFinished(name.removePrefix("finished:"))
        name == "drained" -> LcEvent.Drained
        name == "resumed" -> LcEvent.Resumed
        name == "tick" -> LcEvent.Tick
        name == "shutdown" -> LcEvent.Shutdown
        else -> fail("unknown event $name")
    }

    private class Expanded(val at: Long, val name: String, val check: Boolean)

    private fun expand(events: List<JObject>): List<Expanded> = events.flatMap { e ->
        val at = e.int("at")
        val repeat = if (e["repeat"] != null) e.int("repeat") else 1L
        val every = if (e["every"] != null) e.int("every") else 0L
        (0 until repeat).map { Expanded(at + it * every, e.str("ev"), e.boolOr("check", false)) }
    }

    private fun step(v: UtcVector, laws: Laws, r: Runner, e: Expanded, heartbeat: Boolean): LcStep {
        r.clock.now = e.at
        if (heartbeat && e.name == "tick") r.lifecycle.apply(LcEvent.Ui(UiLifecycle.ACTIVE))
        val before = r.lifecycle.state
        val ev = parseEvent(e.name)
        val s = r.lifecycle.apply(ev)
        laws.bump("state-${s.state.name}")
        s.effects.forEach { laws.bump("effect-${it.label.substringBefore('(')}") }
        s.refused?.let { laws.bump("refused-${it.name}") }
        val kind = e.name.substringBefore(':')
        if (s.state != before || s.effects.isNotEmpty()) laws.bump("$kind->${s.state.name}")
        return s
    }

    @Test
    fun vectors() {
        val vectors = UtcVectors.load("UTC02-lifecycle.json", "UTC02")
        val laws = Laws("UTC02")
        for (v in vectors) {
            val r = Runner()
            val heartbeat = v.input.boolOr("heartbeat", false)
            val events = expand(v.input.arr("events").map { it.asObj() })
            val want = v.expectOk!!.asObj().arr("steps").map { it as JArray }
            val got = ArrayList<List<Any?>>()
            for (e in events) {
                val s = step(v, laws, r, e, heartbeat)
                if (e.check) got += listOf(s.state.name, s.effects.map { it.label }, s.refused?.name, r.lifecycle.mayDial())
            }
            assertEquals(want.size, got.size, "${v.id}: ${v.description}: number of checked steps")
            for ((i, w) in want.withIndex()) {
                val expected = listOf(
                    (w.items[0] as JString).value,
                    (w.items[1] as JArray).items.map { (it as JString).value },
                    (w.items[2] as? JString)?.value,
                    (w.items[3] as JBool).value,
                )
                assertEquals(expected, got[i], "${v.id}: ${v.description}: step ${i + 1}")
            }
            v.expectOk.asObj().objOrNull("final")?.let { f ->
                assertEquals(f.str("state"), r.lifecycle.state.name, "${v.id}: final state")
                assertEquals(f.int("sessions").toInt(), r.lifecycle.sessions, "${v.id}: final sessions")
                assertEquals(f.arr("openAttempts").map { it.asStr() }, r.lifecycle.openAttempts, "${v.id}: final open attempts")
                assertEquals(f.bool("displayHeld"), r.lifecycle.displayHeld, "${v.id}: final displayHeld")
            }
        }
        val required = NodeState.entries.filter { it != NodeState.STARTING }.map { "state-${it.name}" } +
            listOf("effect-HoldDisplay", "effect-ReleaseDisplay", "effect-CancelAttempts", "effect-CloseSessions", "effect-WriteInterruptedRows", "effect-ForceLedger", "effect-ExitNow") +
            listOf("refused-LEDGER_UNAVAILABLE", "refused-INTERRUPTED_BY_SUSPEND") +
            listOf("request->ACTIVE", "peers-open->ACTIVE", "tick->FREEZING", "tick->RESUMING", "tick->IDLE", "ui->FREEZING", "ui->RESUMING", "drained->RESUMING", "drained->FROZEN",
                "resumed->IDLE", "ledger-failed->LEDGER_FAIL", "shutdown->ACTIVE", "body->ACTIVE", "finished->ACTIVE")
        laws.requireAll(required.toSet(), minimumVectors = 19, vectors = vectors.size)
    }

    /** Random event sequences: the laws hold in every state the FSM can reach, not only on the scripted paths. */
    @Test
    fun randomSequencesKeepTheLaws() {
        val rnd = java.util.SplittableRandom(20260930)
        val ids = listOf("a1", "a2", "a3")
        var steps = 0L
        var freezes = 0
        var resumes = 0
        var holds = 0
        repeat(4000) {
            val clock = FakeClock()
            val lc = NodeLifecycle(clock)
            var held = false
            var stoppedSeen = false
            repeat(200) {
                clock.now += when (val d = rnd.nextInt(100)) { in 0..24 -> 0; in 25..49 -> 1; in 50..74 -> 500; in 75..93 -> 1000; in 94..96 -> 3001; else -> 10_001 + d }
                val ev: LcEvent = when (rnd.nextInt(17)) {
                    0 -> if (rnd.nextInt(4) == 0) LcEvent.LedgerReady else LcEvent.Ui(UiLifecycle.ACTIVE)
                    1 -> if (rnd.nextInt(60) == 0) LcEvent.LedgerFailed else LcEvent.Tick
                    2 -> LcEvent.Ui(if (rnd.nextInt(6) == 0) UiLifecycle.entries[1 + rnd.nextInt(2)] else UiLifecycle.ACTIVE)
                    3 -> LcEvent.Ui(UiLifecycle.ACTIVE)
                    4 -> LcEvent.PeersOpened
                    5 -> LcEvent.RequestArrived("r")
                    6 -> LcEvent.SessionOpened
                    7 -> LcEvent.SessionClosed
                    8 -> LcEvent.AttemptStarted(ids[rnd.nextInt(3)])
                    9, 10 -> LcEvent.BodySent(ids[rnd.nextInt(3)])
                    11 -> LcEvent.AttemptFinished(ids[rnd.nextInt(3)])
                    12 -> LcEvent.Drained
                    13 -> LcEvent.Resumed
                    14, 15 -> LcEvent.Tick
                    else -> if (rnd.nextInt(40) == 0) LcEvent.Shutdown else LcEvent.Tick
                }
                val before = lc.state
                val s = lc.apply(ev)
                steps++
                for (e in s.effects) when (e) {
                    LcEffect.HoldDisplay -> {
                        assertTrue(!held, "HoldDisplay while already held")
                        assertEquals(NodeState.ACTIVE, before, "the display may be held only in ACTIVE")
                        held = true
                        holds++
                    }
                    LcEffect.ReleaseDisplay -> {
                        assertTrue(held, "ReleaseDisplay without a hold")
                        held = false
                    }
                    LcEffect.CancelAttempts, is LcEffect.CloseSessions, is LcEffect.WriteInterruptedRows, LcEffect.ForceLedger, LcEffect.ExitNow -> {}
                }
                assertEquals(held, lc.displayHeld, "the FSM's displayHeld disagrees with the effect stream after $ev")
                if (lc.mayDial()) assertEquals(NodeState.ACTIVE, lc.state, "mayDial outside ACTIVE")
                if (lc.state == NodeState.FREEZING || lc.state == NodeState.FROZEN || lc.state == NodeState.RESUMING) {
                    assertTrue(lc.openAttempts.isEmpty() && lc.sessions == 0 && !lc.displayHeld, "work survived into ${lc.state}")
                    assertTrue(!lc.mayDial())
                }
                if (s.state == NodeState.FREEZING && before != NodeState.FREEZING) freezes++
                if (s.state == NodeState.RESUMING && before != NodeState.RESUMING) resumes++
                if (s.refused != null) assertEquals(before, s.state, "a refusal must not change state")
                if (before == NodeState.LEDGER_FAIL) assertEquals(NodeState.LEDGER_FAIL, s.state, "LEDGER_FAIL is absorbing")
                if (stoppedSeen) assertTrue(s.effects.isEmpty() && s.state == before, "an event after ExitNow did something")
                if (s.effects.contains(LcEffect.ExitNow)) {
                    stoppedSeen = true
                    assertTrue(lc.stopped && !lc.displayHeld && lc.openAttempts.isEmpty())
                }
                assertTrue(lc.sessions >= 0)
            }
        }
        assertTrue(freezes > 100 && resumes > 100 && holds > 100, "the random walk did not reach the interesting states: freezes=$freezes resumes=$resumes holds=$holds")
        println("UTC02 random walk: $steps steps, freezes=$freezes resumes=$resumes holds=$holds")
    }

    @Test
    fun wireStateIsOneOfTheThreeValuesForEveryState() {
        val seen = HashSet<String>()
        for (state in NodeState.entries) {
            val lc = NodeLifecycle(FakeClock())
            when (state) {
                NodeState.STARTING -> {}
                NodeState.IDLE -> lc.apply(LcEvent.LedgerReady)
                NodeState.ACTIVE -> { lc.apply(LcEvent.LedgerReady); lc.apply(LcEvent.Ui(UiLifecycle.ACTIVE)); lc.apply(LcEvent.PeersOpened) }
                NodeState.FREEZING -> { lc.apply(LcEvent.LedgerReady); lc.apply(LcEvent.Ui(UiLifecycle.INACTIVE)) }
                NodeState.FROZEN -> { lc.apply(LcEvent.LedgerReady); lc.apply(LcEvent.Ui(UiLifecycle.INACTIVE)); lc.apply(LcEvent.Drained) }
                NodeState.RESUMING -> { lc.apply(LcEvent.LedgerReady); lc.apply(LcEvent.Ui(UiLifecycle.INACTIVE)); lc.apply(LcEvent.Drained); lc.apply(LcEvent.Ui(UiLifecycle.ACTIVE)) }
                NodeState.LEDGER_FAIL -> lc.apply(LcEvent.LedgerFailed)
            }
            assertEquals(state, lc.state)
            assertTrue(lc.wireState() in NodeStates.ALL)
            seen += lc.wireState()
        }
        assertEquals(NodeStates.ALL, seen)
    }
}
