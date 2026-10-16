package xyz.mdhv.asom.lab.policy

/** The provider availability FSM (design 3.2, platforms.md 2.1). Only this name, never a reason, goes on the wire (`availability.fsm`). */
enum class Fsm { OFF, ARMED, SERVING, DRAINING }

enum class Governor { RUN, QUEUE, HOLD }

enum class InputKind {
    /** Someone is using the device (LP-0). A presence signal drains at once and holds the return to SERVING down for 10 minutes. */
    PRESENCE,

    /** A property of the device (power, heat, memory, path, sleep). A condition-caused drain has no hold-down. */
    CONDITION,

    /** On a foreground-only (PF) node, the owner using the lend screen IS the consent to lend, not presence. */
    CONSENT,
}

/**
 * Raw host inputs, classified by LP-0. The names describe WHAT was observed, not how a host detects it: several PF mechanisms are unspecified
 * (R3-OVERCLAIM-4, ERRATA ERR-LP-2), and this model claims nothing about which host can observe which input.
 */
enum class HostInput {
    // presence (LP-0)
    SCREEN_INTERACTIVE, INPUT_ACTIVITY, KEYGUARD_DISMISSED, FOREGROUND_APP, HEAVY_FOREGROUND_PROCESS, CONSOLE_USER, LOGIN_STATE, OTHER_PROCESS_CONTENTION,

    // condition (LP-0)
    POWER_SOURCE, CHARGING, BATTERY_LEVEL, BATTERY_TEMPERATURE, THERMAL_BAND, MEMORY, PATH, SLEEP_IMMINENT,

    // foreground-only nodes: the lend screen and what happens inside and outside it
    LEND_SCREEN_FRONTMOST, INPUT_INSIDE_LEND_SCREEN, LEND_SCREEN_LEFT_FOREGROUND, SCENE_RESIGN_ACTIVE, TERMINAL_FOCUS_LOST, SCREEN_OFF, INPUT_OUTSIDE_LEND_SCREEN,
}

object PresenceLaw {
    private val presence = setOf(
        HostInput.SCREEN_INTERACTIVE, HostInput.INPUT_ACTIVITY, HostInput.KEYGUARD_DISMISSED, HostInput.FOREGROUND_APP, HostInput.HEAVY_FOREGROUND_PROCESS,
        HostInput.CONSOLE_USER, HostInput.LOGIN_STATE, HostInput.OTHER_PROCESS_CONTENTION,
    )
    private val condition = setOf(
        HostInput.POWER_SOURCE, HostInput.CHARGING, HostInput.BATTERY_LEVEL, HostInput.BATTERY_TEMPERATURE, HostInput.THERMAL_BAND, HostInput.MEMORY, HostInput.PATH,
        HostInput.SLEEP_IMMINENT,
    )
    private val pfConsent = setOf(HostInput.LEND_SCREEN_FRONTMOST, HostInput.INPUT_INSIDE_LEND_SCREEN)
    private val pfPresence = setOf(
        HostInput.LEND_SCREEN_LEFT_FOREGROUND, HostInput.SCENE_RESIGN_ACTIVE, HostInput.TERMINAL_FOCUS_LOST, HostInput.SCREEN_OFF, HostInput.INPUT_OUTSIDE_LEND_SCREEN,
    )

    /**
     * LP-0. On a PF node the lend screen being frontmost, the screen-on state it requires (`SCREEN_INTERACTIVE`) and input INSIDE it are consent; the lend screen
     * leaving the foreground (or its scene resigning, or its terminal losing focus), the screen turning off and input OUTSIDE it are presence. Every other
     * presence input stays presence on a PF node too (conservative: more draining, never less). The PF-only inputs do not exist on a node that is not PF.
     */
    fun classify(input: HostInput, pf: Boolean): InputKind = when {
        input in pfConsent || input in pfPresence -> {
            require(pf) { "$input exists only on a foreground-only (PF) node" }
            if (input in pfConsent) InputKind.CONSENT else InputKind.PRESENCE
        }
        input == HostInput.SCREEN_INTERACTIVE && pf -> InputKind.CONSENT
        input in presence -> InputKind.PRESENCE
        input in condition -> InputKind.CONDITION
        else -> error("unclassified input $input")
    }
}

data class FsmConfig(
    /** A node that lends only while its own lend screen is frontmost (iPad M5, Android lend screen, Deck `asom lend --foreground`, Ubuntu Touch). */
    val pf: Boolean = false,
    /** LP-2: `fsm` returns to SERVING no sooner than this long after the last presence signal. */
    val holdDownMs: Long = 600_000,
    /** How long in-flight work may finish after a drain starts (30 s desktops, 2 s Deck, 10 s Android, none on iOS). */
    val graceMs: Long = 30_000,
)

enum class DrainCause { PRESENCE, CONDITION, USER }

data class FsmState(
    val fsm: Fsm = Fsm.OFF,
    val lastPresenceMs: Long? = null,
    val conditionsOk: Boolean = false,
    val explicitStartNeeded: Boolean = false,
    val drainStartMs: Long? = null,
    val drainCause: DrainCause? = null,
    val inflight: Int = 0,
)

sealed interface FsmEvent {
    /** `user_enable` (OFF to ARMED). On a PF node the return also needs an explicit [StartLending]. */
    data object Enable : FsmEvent

    /** `user_disable`: from any state to OFF at once. */
    data object Disable : FsmEvent

    /** The explicit "Start lending" of a PF node. */
    data object StartLending : FsmEvent

    /** A host input, already named; the FSM classifies it (LP-0). For a condition input, [conditionsOk] is the new verdict of all conditions. */
    data class Input(val input: HostInput, val conditionsOk: Boolean? = null) : FsmEvent

    data class InflightChanged(val inflight: Int) : FsmEvent

    /** An evaluation at the event's time: completes a drain whose grace expired or whose work finished, and returns to SERVING when everything allows. */
    data object Tick : FsmEvent
}

/**
 * The pure availability FSM with the presence laws (LAB_SPEC 6.5). No clock is read: every step takes the time.
 *  - LP-0: [PresenceLaw.classify] decides whether an input is presence, a condition or (PF only) consent.
 *  - LP-2: a presence signal drains at once (SERVING to DRAINING in the same step); the return to SERVING is no sooner than [FsmConfig.holdDownMs] after the LAST presence
 *    signal; a PF node additionally returns only after a new explicit [FsmEvent.StartLending]; a condition-caused drain has no hold-down.
 *  - LP-1 (the wire): only the [Fsm] name leaves this class; causes stay in [FsmState] and are never serialised.
 */
class AvailabilityFsm(private val cfg: FsmConfig = FsmConfig()) {
    fun step(s: FsmState, e: FsmEvent, nowMs: Long): FsmState = settle(apply(s, e, nowMs), nowMs)

    private fun apply(s: FsmState, e: FsmEvent, now: Long): FsmState = when (e) {
        FsmEvent.Enable -> if (s.fsm == Fsm.OFF) s.copy(fsm = Fsm.ARMED, explicitStartNeeded = cfg.pf) else s
        FsmEvent.Disable -> s.copy(fsm = Fsm.OFF, drainStartMs = null, drainCause = null)
        FsmEvent.StartLending -> s.copy(explicitStartNeeded = false)
        is FsmEvent.InflightChanged -> s.copy(inflight = e.inflight)
        FsmEvent.Tick -> s
        is FsmEvent.Input -> when (PresenceLaw.classify(e.input, cfg.pf)) {
            InputKind.CONSENT -> s
            InputKind.PRESENCE -> {
                var n = s.copy(lastPresenceMs = now, explicitStartNeeded = s.explicitStartNeeded || cfg.pf)
                if (s.fsm == Fsm.SERVING) n = n.copy(fsm = Fsm.DRAINING, drainStartMs = now, drainCause = DrainCause.PRESENCE)
                n
            }
            InputKind.CONDITION -> {
                val ok = e.conditionsOk ?: error("a condition input carries the new verdict of the conditions")
                var n = s.copy(conditionsOk = ok)
                if (!ok && s.fsm == Fsm.SERVING) n = n.copy(fsm = Fsm.DRAINING, drainStartMs = now, drainCause = DrainCause.CONDITION)
                n
            }
        }
    }

    private fun settle(s: FsmState, now: Long): FsmState {
        var n = s
        if (n.fsm == Fsm.DRAINING) {
            val start = n.drainStartMs ?: now
            // DRAINING is always observable in the step that starts it (even with a grace of 0); it completes at the first later evaluation.
            if (now > start && (n.inflight == 0 || now - start >= cfg.graceMs)) n = n.copy(fsm = Fsm.ARMED, drainStartMs = null)
        }
        if (n.fsm == Fsm.ARMED && n.conditionsOk && !n.explicitStartNeeded && holdDownElapsed(n, now)) n = n.copy(fsm = Fsm.SERVING, drainCause = null)
        return n
    }

    private fun holdDownElapsed(s: FsmState, now: Long): Boolean = s.lastPresenceMs?.let { now >= it + cfg.holdDownMs } ?: true
}
