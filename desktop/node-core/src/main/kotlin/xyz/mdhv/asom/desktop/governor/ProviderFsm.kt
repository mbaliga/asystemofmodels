package xyz.mdhv.asom.desktop.governor

enum class LenderState { OFF, ARMED, SERVING, DRAINING }

/**
 * Nine events, four states: 36 (state, event) pairs. `CONDITIONS_MET` is a level event: the governor re-emits it at
 * every evaluation, so "SERVING at the first evaluation at or after t_last + 600,000 ms" needs no timer event.
 */
enum class FsmEvent {
    USER_ENABLE, USER_DISABLE, CONDITIONS_MET, CONDITION_LOST, PRESENCE_SIGNAL, SLEEP_IMMINENT, RESUMED,
    INFLIGHT_DONE, GRACE_EXPIRED,
}

enum class DrainCause { USER, PRESENCE, CONDITION, SLEEP }

/** Declarative requests to the host. This wave nothing acts on them: nothing listens and nothing holds a lock. */
enum class FsmEffect { OPEN_LISTENER, CLOSE_LISTENER, ABORT_INFLIGHT, TAKE_DELAY_LOCK, RELEASE_DELAY_LOCK }

/**
 * @param graceMs in-flight grace on a presence or condition drain: 30,000 on desktops, 2,000 on the Deck.
 * @param presenceFirst true for a PF node (its own lend screen must be frontmost): after a presence drain it also
 *   needs a new explicit USER_ENABLE (LP-2).
 * @param holdDownMs LP-2: no SERVING sooner than this after the last presence signal. Not configurable below 600,000.
 * @param sleepBudgetMs the delay-lock budget for a sleep drain (logind `InhibitDelayMaxSec=5`).
 */
data class FsmConfig(
    val graceMs: Long,
    val presenceFirst: Boolean = false,
    val holdDownMs: Long = LP2_HOLD_DOWN_MS,
    val sleepBudgetMs: Long = 5_000,
) {
    init {
        require(holdDownMs >= LP2_HOLD_DOWN_MS) { "LP-2 forbids a hold-down below $LP2_HOLD_DOWN_MS ms" }
        require(graceMs >= 0 && sleepBudgetMs >= 0) { "grace must not be negative" }
    }

    companion object {
        const val LP2_HOLD_DOWN_MS = 600_000L
    }
}

data class FsmSnapshot(
    val state: LenderState = LenderState.OFF,
    val lastPresenceMs: Long? = null,
    val asleep: Boolean = false,
    val needsExplicitStart: Boolean = false,
    val disableRequested: Boolean = false,
    val drainDeadlineMs: Long? = null,
    val drainCause: DrainCause? = null,
)

data class FsmStep(
    val event: FsmEvent,
    val nowMs: Long,
    val from: LenderState,
    val to: LenderState,
    val effects: List<FsmEffect>,
    /** `<STATE>+<EVENT>:<guard outcome>`; every one of [ProviderFsm.BRANCHES] must be exercised by the exhaustive test. */
    val branch: String,
)

class ProviderFsm(val config: FsmConfig, initial: FsmSnapshot = FsmSnapshot()) {
    private var s = initial

    val state: LenderState get() = s.state
    fun snapshot(): FsmSnapshot = s

    fun wire(): AvailabilityWire = wireOf(s.state)

    private fun holdDownElapsed(nowMs: Long): Boolean {
        val last = s.lastPresenceMs ?: return true
        return nowMs - last >= config.holdDownMs
    }

    private fun drainDeadline(nowMs: Long, graceMs: Long): Long {
        val d = nowMs + graceMs
        return s.drainDeadlineMs?.let { minOf(it, d) } ?: d
    }

    fun apply(event: FsmEvent, nowMs: Long): FsmStep {
        val from = s.state
        val effects = ArrayList<FsmEffect>(3)
        var to = from
        val branch: String
        when (from) {
            LenderState.OFF -> when (event) {
                FsmEvent.USER_ENABLE -> {
                    to = LenderState.ARMED
                    s = s.copy(state = to, needsExplicitStart = false, disableRequested = false)
                    branch = "OFF+USER_ENABLE:enable"
                }
                FsmEvent.USER_DISABLE -> branch = "OFF+USER_DISABLE:noop"
                FsmEvent.CONDITIONS_MET -> branch = "OFF+CONDITIONS_MET:ignored"
                FsmEvent.CONDITION_LOST -> branch = "OFF+CONDITION_LOST:ignored"
                FsmEvent.PRESENCE_SIGNAL -> {
                    s = s.copy(lastPresenceMs = nowMs)
                    branch = "OFF+PRESENCE_SIGNAL:recorded"
                }
                FsmEvent.SLEEP_IMMINENT -> { s = s.copy(asleep = true); branch = "OFF+SLEEP_IMMINENT:asleep" }
                FsmEvent.RESUMED -> { s = s.copy(asleep = false); branch = "OFF+RESUMED:awake" }
                FsmEvent.INFLIGHT_DONE -> branch = "OFF+INFLIGHT_DONE:stale"
                FsmEvent.GRACE_EXPIRED -> branch = "OFF+GRACE_EXPIRED:stale"
            }

            LenderState.ARMED -> when (event) {
                FsmEvent.USER_ENABLE -> { s = s.copy(needsExplicitStart = false); branch = "ARMED+USER_ENABLE:explicit-start" }
                FsmEvent.USER_DISABLE -> {
                    to = LenderState.OFF
                    s = s.copy(state = to, needsExplicitStart = false, disableRequested = false)
                    branch = "ARMED+USER_DISABLE:off"
                }
                FsmEvent.CONDITIONS_MET -> when {
                    s.asleep -> branch = "ARMED+CONDITIONS_MET:blocked-asleep"
                    s.needsExplicitStart -> branch = "ARMED+CONDITIONS_MET:blocked-explicit-start"
                    !holdDownElapsed(nowMs) -> branch = "ARMED+CONDITIONS_MET:blocked-hold-down"
                    else -> {
                        to = LenderState.SERVING
                        s = s.copy(state = to)
                        effects += FsmEffect.OPEN_LISTENER
                        effects += FsmEffect.TAKE_DELAY_LOCK
                        branch = "ARMED+CONDITIONS_MET:serve"
                    }
                }
                FsmEvent.CONDITION_LOST -> branch = "ARMED+CONDITION_LOST:stay"
                FsmEvent.PRESENCE_SIGNAL -> {
                    s = s.copy(lastPresenceMs = nowMs, needsExplicitStart = s.needsExplicitStart || config.presenceFirst)
                    branch = "ARMED+PRESENCE_SIGNAL:recorded"
                }
                FsmEvent.SLEEP_IMMINENT -> { s = s.copy(asleep = true); branch = "ARMED+SLEEP_IMMINENT:asleep" }
                FsmEvent.RESUMED -> { s = s.copy(asleep = false); branch = "ARMED+RESUMED:awake" }
                FsmEvent.INFLIGHT_DONE -> branch = "ARMED+INFLIGHT_DONE:stale"
                FsmEvent.GRACE_EXPIRED -> branch = "ARMED+GRACE_EXPIRED:stale"
            }

            LenderState.SERVING -> when (event) {
                FsmEvent.USER_ENABLE -> branch = "SERVING+USER_ENABLE:noop"
                FsmEvent.USER_DISABLE -> {
                    to = LenderState.DRAINING
                    s = s.copy(state = to, disableRequested = true, drainCause = DrainCause.USER, drainDeadlineMs = nowMs)
                    effects += FsmEffect.CLOSE_LISTENER
                    branch = "SERVING+USER_DISABLE:drain-user"
                }
                FsmEvent.CONDITIONS_MET -> branch = "SERVING+CONDITIONS_MET:noop"
                FsmEvent.CONDITION_LOST -> {
                    to = LenderState.DRAINING
                    s = s.copy(state = to, drainCause = DrainCause.CONDITION, drainDeadlineMs = nowMs + config.graceMs)
                    effects += FsmEffect.CLOSE_LISTENER
                    branch = "SERVING+CONDITION_LOST:drain-condition"
                }
                FsmEvent.PRESENCE_SIGNAL -> {
                    to = LenderState.DRAINING
                    s = s.copy(
                        state = to, lastPresenceMs = nowMs, drainCause = DrainCause.PRESENCE,
                        drainDeadlineMs = nowMs + config.graceMs,
                        needsExplicitStart = s.needsExplicitStart || config.presenceFirst,
                    )
                    effects += FsmEffect.CLOSE_LISTENER
                    branch = "SERVING+PRESENCE_SIGNAL:drain-presence"
                }
                FsmEvent.SLEEP_IMMINENT -> {
                    to = LenderState.DRAINING
                    s = s.copy(
                        state = to, asleep = true, drainCause = DrainCause.SLEEP,
                        drainDeadlineMs = nowMs + minOf(config.graceMs, config.sleepBudgetMs),
                    )
                    effects += FsmEffect.CLOSE_LISTENER
                    branch = "SERVING+SLEEP_IMMINENT:drain-sleep"
                }
                FsmEvent.RESUMED -> { s = s.copy(asleep = false); branch = "SERVING+RESUMED:awake" }
                FsmEvent.INFLIGHT_DONE -> branch = "SERVING+INFLIGHT_DONE:noop"
                FsmEvent.GRACE_EXPIRED -> branch = "SERVING+GRACE_EXPIRED:stale"
            }

            LenderState.DRAINING -> when (event) {
                FsmEvent.USER_ENABLE -> { s = s.copy(disableRequested = false); branch = "DRAINING+USER_ENABLE:cancel-disable" }
                FsmEvent.USER_DISABLE -> {
                    s = s.copy(disableRequested = true, drainDeadlineMs = nowMs)
                    branch = "DRAINING+USER_DISABLE:disable"
                }
                FsmEvent.CONDITIONS_MET -> branch = "DRAINING+CONDITIONS_MET:ignored"
                FsmEvent.CONDITION_LOST -> branch = "DRAINING+CONDITION_LOST:noop"
                FsmEvent.PRESENCE_SIGNAL -> {
                    s = s.copy(
                        lastPresenceMs = nowMs, drainDeadlineMs = drainDeadline(nowMs, config.graceMs),
                        needsExplicitStart = s.needsExplicitStart || config.presenceFirst,
                    )
                    branch = "DRAINING+PRESENCE_SIGNAL:shorten"
                }
                FsmEvent.SLEEP_IMMINENT -> {
                    s = s.copy(asleep = true, drainDeadlineMs = drainDeadline(nowMs, minOf(config.graceMs, config.sleepBudgetMs)))
                    branch = "DRAINING+SLEEP_IMMINENT:shorten"
                }
                FsmEvent.RESUMED -> { s = s.copy(asleep = false); branch = "DRAINING+RESUMED:awake" }
                FsmEvent.INFLIGHT_DONE -> {
                    to = if (s.disableRequested) LenderState.OFF else LenderState.ARMED
                    branch = if (s.disableRequested) "DRAINING+INFLIGHT_DONE:to-off" else "DRAINING+INFLIGHT_DONE:to-armed"
                    effects += FsmEffect.RELEASE_DELAY_LOCK
                    s = s.copy(state = to, disableRequested = false, drainDeadlineMs = null, drainCause = null)
                }
                FsmEvent.GRACE_EXPIRED -> {
                    to = if (s.disableRequested) LenderState.OFF else LenderState.ARMED
                    branch = if (s.disableRequested) "DRAINING+GRACE_EXPIRED:to-off" else "DRAINING+GRACE_EXPIRED:to-armed"
                    effects += FsmEffect.ABORT_INFLIGHT
                    effects += FsmEffect.RELEASE_DELAY_LOCK
                    s = s.copy(state = to, disableRequested = false, drainDeadlineMs = null, drainCause = null)
                }
            }
        }
        return FsmStep(event, nowMs, from, to, effects, branch)
    }

    companion object {
        /** Every guard outcome of the transition function. The exhaustive test fails if any is not exercised. */
        val BRANCHES: List<String> = listOf(
            "OFF+USER_ENABLE:enable", "OFF+USER_DISABLE:noop", "OFF+CONDITIONS_MET:ignored", "OFF+CONDITION_LOST:ignored",
            "OFF+PRESENCE_SIGNAL:recorded", "OFF+SLEEP_IMMINENT:asleep", "OFF+RESUMED:awake", "OFF+INFLIGHT_DONE:stale",
            "OFF+GRACE_EXPIRED:stale",
            "ARMED+USER_ENABLE:explicit-start", "ARMED+USER_DISABLE:off", "ARMED+CONDITIONS_MET:blocked-asleep",
            "ARMED+CONDITIONS_MET:blocked-explicit-start", "ARMED+CONDITIONS_MET:blocked-hold-down", "ARMED+CONDITIONS_MET:serve",
            "ARMED+CONDITION_LOST:stay", "ARMED+PRESENCE_SIGNAL:recorded", "ARMED+SLEEP_IMMINENT:asleep", "ARMED+RESUMED:awake",
            "ARMED+INFLIGHT_DONE:stale", "ARMED+GRACE_EXPIRED:stale",
            "SERVING+USER_ENABLE:noop", "SERVING+USER_DISABLE:drain-user", "SERVING+CONDITIONS_MET:noop",
            "SERVING+CONDITION_LOST:drain-condition", "SERVING+PRESENCE_SIGNAL:drain-presence", "SERVING+SLEEP_IMMINENT:drain-sleep",
            "SERVING+RESUMED:awake", "SERVING+INFLIGHT_DONE:noop", "SERVING+GRACE_EXPIRED:stale",
            "DRAINING+USER_ENABLE:cancel-disable", "DRAINING+USER_DISABLE:disable", "DRAINING+CONDITIONS_MET:ignored",
            "DRAINING+CONDITION_LOST:noop", "DRAINING+PRESENCE_SIGNAL:shorten", "DRAINING+SLEEP_IMMINENT:shorten",
            "DRAINING+RESUMED:awake", "DRAINING+INFLIGHT_DONE:to-armed", "DRAINING+INFLIGHT_DONE:to-off",
            "DRAINING+GRACE_EXPIRED:to-armed", "DRAINING+GRACE_EXPIRED:to-off",
        )
    }
}
