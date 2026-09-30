package xyz.mdhv.asom.desktop.governor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.LawCounter
import xyz.mdhv.asom.desktop.Report

/**
 * The exhaustive transition test (DL1 gate). 4 states x 9 events = 36 (state, event) pairs; every pair is driven from
 * every context that changes its guard, and compared with an ORACLE written independently below as data (state after,
 * effects), not derived from the implementation. The test prints `transitions exercised: N/N` and FAILS if any pair,
 * any guard branch, or any law exercised nothing.
 */
class ProviderFsmExhaustiveTest {
    private val hold = FsmConfig.LP2_HOLD_DOWN_MS
    private val t0 = 5_000_000L

    private val cfg = FsmConfig(graceMs = 30_000)
    private val cfgPf = FsmConfig(graceMs = 30_000, presenceFirst = true)

    private fun snap(
        state: LenderState, lastPresence: Long? = null, asleep: Boolean = false, needsStart: Boolean = false,
        disable: Boolean = false, deadline: Long? = null, cause: DrainCause? = null,
    ) = FsmSnapshot(state, lastPresence, asleep, needsStart, disable, deadline, cause)

    /** One row of the oracle: a context, the event, and what must result. */
    private class Case(
        val branch: String,
        val config: FsmConfig,
        val before: FsmSnapshot,
        val event: FsmEvent,
        val now: Long,
        val to: LenderState,
        val effects: List<FsmEffect>,
        val check: (FsmSnapshot) -> Boolean = { true },
        val law: String? = null,
    )

    private fun cases(): List<Case> {
        val O = LenderState.OFF; val A = LenderState.ARMED; val V = LenderState.SERVING; val D = LenderState.DRAINING
        val none = emptyList<FsmEffect>()
        val open = listOf(FsmEffect.OPEN_LISTENER, FsmEffect.TAKE_DELAY_LOCK)
        val close = listOf(FsmEffect.CLOSE_LISTENER)
        val release = listOf(FsmEffect.RELEASE_DELAY_LOCK)
        val abortRelease = listOf(FsmEffect.ABORT_INFLIGHT, FsmEffect.RELEASE_DELAY_LOCK)
        val c = ArrayList<Case>()
        fun add(branch: String, config: FsmConfig, before: FsmSnapshot, ev: FsmEvent, to: LenderState, fx: List<FsmEffect>,
                now: Long = t0, law: String? = null, check: (FsmSnapshot) -> Boolean = { true }) {
            c += Case(branch, config, before, ev, now, to, fx, check, law)
        }

        // ---- OFF
        add("OFF+USER_ENABLE:enable", cfg, snap(O, needsStart = true), FsmEvent.USER_ENABLE, A, none) { !it.needsExplicitStart }
        add("OFF+USER_DISABLE:noop", cfg, snap(O), FsmEvent.USER_DISABLE, O, none)
        add("OFF+CONDITIONS_MET:ignored", cfg, snap(O), FsmEvent.CONDITIONS_MET, O, none, law = "listener-only-on-serve")
        add("OFF+CONDITION_LOST:ignored", cfg, snap(O), FsmEvent.CONDITION_LOST, O, none)
        add("OFF+PRESENCE_SIGNAL:recorded", cfgPf, snap(O), FsmEvent.PRESENCE_SIGNAL, O, none) { it.lastPresenceMs == t0 && !it.needsExplicitStart }
        add("OFF+SLEEP_IMMINENT:asleep", cfg, snap(O), FsmEvent.SLEEP_IMMINENT, O, none) { it.asleep }
        add("OFF+RESUMED:awake", cfg, snap(O, asleep = true), FsmEvent.RESUMED, O, none) { !it.asleep }
        add("OFF+INFLIGHT_DONE:stale", cfg, snap(O), FsmEvent.INFLIGHT_DONE, O, none)
        add("OFF+GRACE_EXPIRED:stale", cfg, snap(O), FsmEvent.GRACE_EXPIRED, O, none)

        // ---- ARMED
        add("ARMED+USER_ENABLE:explicit-start", cfgPf, snap(A, needsStart = true), FsmEvent.USER_ENABLE, A, none, law = "LP-2-pf-explicit-start") { !it.needsExplicitStart }
        add("ARMED+USER_DISABLE:off", cfg, snap(A), FsmEvent.USER_DISABLE, O, none)
        add("ARMED+CONDITIONS_MET:blocked-asleep", cfg, snap(A, asleep = true), FsmEvent.CONDITIONS_MET, A, none, law = "never-serve-asleep")
        add("ARMED+CONDITIONS_MET:blocked-explicit-start", cfgPf, snap(A, needsStart = true), FsmEvent.CONDITIONS_MET, A, none, law = "LP-2-pf-explicit-start")
        add("ARMED+CONDITIONS_MET:blocked-hold-down", cfg, snap(A, lastPresence = t0 - (hold - 1)), FsmEvent.CONDITIONS_MET, A, none, law = "LP-2-hold-down")
        add("ARMED+CONDITIONS_MET:serve", cfg, snap(A, lastPresence = t0 - hold), FsmEvent.CONDITIONS_MET, V, open, law = "LP-2-hold-down")
        add("ARMED+CONDITION_LOST:stay", cfg, snap(A), FsmEvent.CONDITION_LOST, A, none)
        add("ARMED+PRESENCE_SIGNAL:recorded", cfgPf, snap(A), FsmEvent.PRESENCE_SIGNAL, A, none, law = "LP-2-pf-explicit-start") { it.lastPresenceMs == t0 && it.needsExplicitStart }
        add("ARMED+SLEEP_IMMINENT:asleep", cfg, snap(A), FsmEvent.SLEEP_IMMINENT, A, none) { it.asleep && it.lastPresenceMs == null }
        add("ARMED+RESUMED:awake", cfg, snap(A, asleep = true), FsmEvent.RESUMED, A, none) { !it.asleep }
        add("ARMED+INFLIGHT_DONE:stale", cfg, snap(A), FsmEvent.INFLIGHT_DONE, A, none)
        add("ARMED+GRACE_EXPIRED:stale", cfg, snap(A), FsmEvent.GRACE_EXPIRED, A, none)

        // ---- SERVING
        add("SERVING+USER_ENABLE:noop", cfg, snap(V), FsmEvent.USER_ENABLE, V, none)
        add("SERVING+USER_DISABLE:drain-user", cfg, snap(V), FsmEvent.USER_DISABLE, D, close) { it.disableRequested && it.drainDeadlineMs == t0 }
        add("SERVING+CONDITIONS_MET:noop", cfg, snap(V), FsmEvent.CONDITIONS_MET, V, none)
        add("SERVING+CONDITION_LOST:drain-condition", cfg, snap(V), FsmEvent.CONDITION_LOST, D, close, law = "condition-drain-no-hold-down") {
            it.drainDeadlineMs == t0 + 30_000 && it.lastPresenceMs == null
        }
        add("SERVING+PRESENCE_SIGNAL:drain-presence", cfg, snap(V), FsmEvent.PRESENCE_SIGNAL, D, close, law = "LP-2-presence-drains-at-once") {
            it.lastPresenceMs == t0 && it.drainDeadlineMs == t0 + 30_000
        }
        add("SERVING+SLEEP_IMMINENT:drain-sleep", cfg, snap(V), FsmEvent.SLEEP_IMMINENT, D, close, law = "sleep-drain-not-presence") {
            it.asleep && it.lastPresenceMs == null && it.drainDeadlineMs == t0 + 5_000
        }
        add("SERVING+RESUMED:awake", cfg, snap(V, asleep = true), FsmEvent.RESUMED, V, none) { !it.asleep }
        add("SERVING+INFLIGHT_DONE:noop", cfg, snap(V), FsmEvent.INFLIGHT_DONE, V, none)
        add("SERVING+GRACE_EXPIRED:stale", cfg, snap(V), FsmEvent.GRACE_EXPIRED, V, none)

        // ---- DRAINING
        add("DRAINING+USER_ENABLE:cancel-disable", cfg, snap(D, disable = true, deadline = t0 + 9, cause = DrainCause.USER), FsmEvent.USER_ENABLE, D, none) { !it.disableRequested }
        add("DRAINING+USER_DISABLE:disable", cfg, snap(D, deadline = t0 + 9_000, cause = DrainCause.CONDITION), FsmEvent.USER_DISABLE, D, none) { it.disableRequested && it.drainDeadlineMs == t0 }
        add("DRAINING+CONDITIONS_MET:ignored", cfg, snap(D, deadline = t0 + 9_000), FsmEvent.CONDITIONS_MET, D, none, law = "drain-is-irreversible")
        add("DRAINING+CONDITION_LOST:noop", cfg, snap(D, deadline = t0 + 9_000), FsmEvent.CONDITION_LOST, D, none)
        add("DRAINING+PRESENCE_SIGNAL:shorten", cfg, snap(D, deadline = t0 + 20_000, cause = DrainCause.CONDITION), FsmEvent.PRESENCE_SIGNAL, D, none, law = "LP-2-presence-drains-at-once") {
            it.drainDeadlineMs == t0 + 20_000 && it.lastPresenceMs == t0
        }
        add("DRAINING+SLEEP_IMMINENT:shorten", cfg, snap(D, deadline = t0 + 20_000, cause = DrainCause.CONDITION), FsmEvent.SLEEP_IMMINENT, D, none) {
            it.asleep && it.drainDeadlineMs == t0 + 5_000
        }
        add("DRAINING+RESUMED:awake", cfg, snap(D, asleep = true, deadline = t0 + 9_000), FsmEvent.RESUMED, D, none) { !it.asleep }
        add("DRAINING+INFLIGHT_DONE:to-armed", cfg, snap(D, deadline = t0 + 9_000, cause = DrainCause.PRESENCE), FsmEvent.INFLIGHT_DONE, A, release) { it.drainDeadlineMs == null }
        add("DRAINING+INFLIGHT_DONE:to-off", cfg, snap(D, disable = true, deadline = t0, cause = DrainCause.USER), FsmEvent.INFLIGHT_DONE, O, release) { !it.disableRequested }
        add("DRAINING+GRACE_EXPIRED:to-armed", cfg, snap(D, deadline = t0, cause = DrainCause.PRESENCE), FsmEvent.GRACE_EXPIRED, A, abortRelease)
        add("DRAINING+GRACE_EXPIRED:to-off", cfg, snap(D, disable = true, deadline = t0, cause = DrainCause.USER), FsmEvent.GRACE_EXPIRED, O, abortRelease)
        return c
    }

    @Test
    fun `every state event pair and every guard branch is exercised and matches the oracle`() {
        val pairsSeen = HashSet<Pair<LenderState, FsmEvent>>()
        val branchesSeen = HashSet<String>()
        val lawNames = listOf(
            "LP-2-presence-drains-at-once", "LP-2-hold-down", "LP-2-pf-explicit-start", "condition-drain-no-hold-down",
            "sleep-drain-not-presence", "listener-only-on-serve", "never-serve-asleep", "drain-is-irreversible",
        )
        val laws = LawCounter(lawNames)

        for (case in cases()) {
            val fsm = ProviderFsm(case.config, case.before)
            val step = fsm.apply(case.event, case.now)
            assertEquals(case.branch, step.branch, "branch for ${case.branch}")
            assertEquals(case.before.state, step.from, "from state for ${case.branch}")
            assertEquals(case.to, step.to, "to state for ${case.branch}")
            assertEquals(case.to, fsm.state, "machine state for ${case.branch}")
            assertEquals(case.effects, step.effects, "effects for ${case.branch}")
            assertTrue(case.check(fsm.snapshot()), "post-condition for ${case.branch}: ${fsm.snapshot()}")
            pairsSeen += case.before.state to case.event
            branchesSeen += step.branch
            case.law?.let { laws.hit(it) }
        }

        val total = LenderState.entries.size * FsmEvent.entries.size
        Report.line("transitions exercised: ${pairsSeen.size}/$total")
        Report.line("guard branches exercised: ${branchesSeen.size}/${ProviderFsm.BRANCHES.size}")
        assertEquals(36, total, "the spec fixes 4 states x 9 events = 36")
        assertEquals(total, pairsSeen.size, "unexercised pairs: " + (LenderState.entries.flatMap { s -> FsmEvent.entries.map { s to it } } - pairsSeen))
        assertEquals(ProviderFsm.BRANCHES.toSet(), branchesSeen, "branch list and exercised branches must be identical")
        assertEquals(ProviderFsm.BRANCHES.size, ProviderFsm.BRANCHES.toSet().size, "no duplicate branch ids")
        laws.assertAllExercised("fsm-exhaustive")
    }

    @Test
    fun `presence during a drain cannot lengthen it and a condition drain records no presence`() {
        val fsm = ProviderFsm(cfg, snap(LenderState.SERVING))
        fsm.apply(FsmEvent.CONDITION_LOST, t0)
        val before = fsm.snapshot().drainDeadlineMs!!
        fsm.apply(FsmEvent.SLEEP_IMMINENT, t0 + 1)
        assertTrue(fsm.snapshot().drainDeadlineMs!! <= before)
        assertEquals(null, fsm.snapshot().lastPresenceMs, "a sleep or condition drain is not a presence drain")
    }
}
