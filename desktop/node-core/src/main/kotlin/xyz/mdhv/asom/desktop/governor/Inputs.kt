package xyz.mdhv.asom.desktop.governor

/** PA lends while awake with nobody required at a screen; PF lends only while its own lend screen is frontmost. */
enum class NodeKind { PA, PF }

/** LP-0: every governor input is a presence input or a condition input; on a PF node some are consent. */
enum class InputClass { PRESENCE, CONDITION, CONSENT }

/**
 * The LP-0 classification table (design 7.4; LAB_SPEC 6.5). `classOn(kind)` is null where the input does not exist on
 * that kind of node. The pure JVM node reads only the contention inputs on Linux (linux.md 3.4); the rest name what
 * other hosts observe, so the table is one shared thing.
 */
enum class GovernorInput(private val pa: InputClass?, private val pf: InputClass?) {
    SCREEN_ON(InputClass.PRESENCE, InputClass.CONSENT),
    INPUT_IDLE_TIME(InputClass.PRESENCE, null),
    KEYGUARD_DISMISSAL(InputClass.PRESENCE, InputClass.PRESENCE),
    FOREGROUND_APP(InputClass.PRESENCE, InputClass.PRESENCE),
    HEAVY_FOREGROUND_PROCESS(InputClass.PRESENCE, InputClass.PRESENCE),
    CONSOLE_USER(InputClass.PRESENCE, InputClass.PRESENCE),
    LOGIN_STATE(InputClass.PRESENCE, InputClass.PRESENCE),
    OTHER_PROCESS_CONTENTION(InputClass.PRESENCE, InputClass.PRESENCE),

    POWER_SOURCE_AND_CHARGING(InputClass.CONDITION, InputClass.CONDITION),
    BATTERY_LEVEL(InputClass.CONDITION, InputClass.CONDITION),
    BATTERY_TEMPERATURE(InputClass.CONDITION, InputClass.CONDITION),
    THERMAL_BAND(InputClass.CONDITION, InputClass.CONDITION),
    MEMORY(InputClass.CONDITION, InputClass.CONDITION),
    PATH(InputClass.CONDITION, InputClass.CONDITION),
    SLEEP_IMMINENT(InputClass.CONDITION, InputClass.CONDITION),

    // PF exception: the lend screen being frontmost, the screen-on state it requires, and input inside it are consent.
    LEND_SCREEN_FRONTMOST(null, InputClass.CONSENT),
    INPUT_INSIDE_LEND_SCREEN(null, InputClass.CONSENT),
    // ... and presence is the lend screen leaving the foreground, the screen turning off, or input outside it.
    LEND_SCREEN_LEFT_FOREGROUND(null, InputClass.PRESENCE),
    SCREEN_OFF(null, InputClass.PRESENCE),
    INPUT_OUTSIDE_LEND_SCREEN(null, InputClass.PRESENCE);

    fun classOn(kind: NodeKind): InputClass? = if (kind == NodeKind.PA) pa else pf
}

/**
 * The wire projection of availability (LP-1): the accept/decline decision with its code and `retryAfterMs`,
 * `availability.fsm`, and (later) `queue.bucket`. Nothing else is computed from presence inputs.
 * `retryAfterMs` stays null on this wave: a computed hint would expose the hold-down's remaining time, which is
 * presence-derived (see desktop/ERRATA.md ERR-FSM-3).
 */
data class AvailabilityWire(val fsm: LenderState, val declineCode: String?, val retryAfterMs: Long?)

const val DECLINE_PEER_UNAVAILABLE = "PEER_UNAVAILABLE"

fun wireOf(state: LenderState): AvailabilityWire =
    AvailabilityWire(state, if (state == LenderState.SERVING) null else DECLINE_PEER_UNAVAILABLE, null)

/** The only way an input becomes a presence event: PRESENCE-class inputs signal; CONSENT and CONDITION inputs do not. */
fun presenceEventFor(input: GovernorInput, kind: NodeKind): FsmEvent? =
    if (input.classOn(kind) == InputClass.PRESENCE) FsmEvent.PRESENCE_SIGNAL else null
