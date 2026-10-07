package xyz.mdhv.asom.desktop.governor

import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.ThermalReading

/**
 * A two-threshold gate with dwell: turns ACTIVE when the value stays above [onAbove] for [onHoldMs], and ELIGIBLE when
 * it stays below [offBelow] for [offHoldMs] ("drain above 400 permille for 10 s; eligible below 200 permille for 60 s").
 * A gate starts UNSETTLED: neither drained nor known to be eligible, because nothing has yet shown that the owner is away
 * (finding HLU-4). It leaves that state only through one of the two dwells above, so a node enabled while the machine is
 * in use does not serve until the machine has been quiet for the whole eligible dwell. [startEligible] restores the
 * pre-HLU-4 start for a host that has not adopted [SettlesBeforeServing] yet.
 * An unreadable value (null) changes nothing and restarts both dwell timers, so a blind sample never counts either way.
 */
class DwellGate(
    private val onAbove: Int,
    private val onHoldMs: Long,
    private val offBelow: Int,
    private val offHoldMs: Long,
    startEligible: Boolean = false,
) {
    private enum class Phase { UNSETTLED, ACTIVE, ELIGIBLE }

    private var phase = if (startEligible) Phase.ELIGIBLE else Phase.UNSETTLED
    val active: Boolean get() = phase == Phase.ACTIVE

    /** True once the value has stayed below [offBelow] for [offHoldMs] (and the gate has not been ACTIVE since). */
    val eligible: Boolean get() = phase == Phase.ELIGIBLE
    private var aboveSince: Long? = null
    private var belowSince: Long? = null

    fun update(nowMs: Long, value: Int?): Boolean {
        if (value == null) {
            aboveSince = null
            belowSince = null
            return active
        }
        if (phase != Phase.ACTIVE) {
            if (value > onAbove) {
                belowSince = null
                val since = aboveSince ?: nowMs.also { aboveSince = it }
                if (nowMs - since >= onHoldMs) {
                    phase = Phase.ACTIVE
                    aboveSince = null
                }
            } else {
                aboveSince = null
                if (phase == Phase.UNSETTLED && value < offBelow) {
                    val since = belowSince ?: nowMs.also { belowSince = it }
                    if (nowMs - since >= offHoldMs) {
                        phase = Phase.ELIGIBLE
                        belowSince = null
                    }
                } else {
                    belowSince = null
                }
            }
        } else {
            aboveSince = null
            if (value < offBelow) {
                val since = belowSince ?: nowMs.also { belowSince = it }
                if (nowMs - since >= offHoldMs) {
                    phase = Phase.ELIGIBLE
                    belowSince = null
                }
            } else {
                belowSince = null
            }
        }
        return active
    }
}

/** Host signals that need a mechanism the spec has not fixed yet (R3-OVERCLAIM-4); null means "not known". */
data class HostSignals(
    val docked: Boolean? = null,
    val gameRunning: Boolean? = null,
    val gameMode: Boolean? = null,
    /** Condition input: PSI memory `full avg10` in hundredths of a percent (500 = 5%). */
    val memoryPsiFullCenti: Int? = null,
)

/**
 * Marker for a host whose presence counters are read from the first evaluation on, so the governor can require a full quiet
 * dwell before the first CONDITIONS_MET (HLU-4). A host without it keeps the old start (eligible at once, a blind sample is
 * eligible); the Windows and macOS hosts adopt the marker together with their tests (desktop/ERRATA.md ERR-FX-5).
 */
interface SettlesBeforeServing

/** Implemented by a host that can name its own rules; the seam itself stays exactly PLATFORM_PLAN section 2. */
interface HostRulesProvider {
    fun hostRules(config: NodeConfig, mode: HostMode): HostRules
}

interface HostSignalsProvider {
    fun hostSignals(): HostSignals
}

/** Per-host rules for the conditions of SERVING (desktop, laptop, Deck). Presence-class blocks are separate from condition blocks. */
data class HostVerdict(val conditionBlocks: List<String>, val presenceBlocks: List<String>)

interface HostRules {
    val label: String
    /** Grace on a presence or condition drain: 30,000 ms on desktops, 2,000 ms on the Deck. */
    val graceMs: Long

    /** Never true on SteamOS: a block lock in Game Mode produces a fake sleep (LF05). */
    val blockLockAllowed: Boolean

    fun evaluate(power: PowerReading, signals: HostSignals): HostVerdict
}

class Readings(
    val power: PowerReading,
    val thermal: ThermalReading,
    val signals: HostSignals = HostSignals(),
    /** Presence inputs; null where the host has no counter (the rule is then off). */
    val cpuOtherPermille: Int? = null,
    val gpuOtherPermille: Int? = null,
    /**
     * Whether the host has a GPU own-versus-other counter at all. False (NVIDIA, no attribution) switches the GPU rule off;
     * true with a null [gpuOtherPermille] is a blind sample of a counter that exists. Defaults to "a value was given".
     */
    val gpuCounterPresent: Boolean = gpuOtherPermille != null,
)

data class Evaluation(val events: List<FsmEvent>, val presenceReasons: List<String>, val conditionReasons: List<String>)

/**
 * Turns readings into FSM events. Presence causes emit PRESENCE_SIGNAL at every evaluation while they hold, which keeps
 * refreshing the LP-2 hold-down; condition causes emit CONDITION_LOST; otherwise CONDITIONS_MET.
 * NVIDIA and other GPUs with no own-versus-other attribution pass `gpuOtherPermille = null`: the GPU rule is off and
 * only the thermal band applies (linux.md 3.4).
 * A presence input that is not yet known to be eligible (its gate has not completed either dwell) or that cannot be read
 * right now produces NO event while no cause holds: the node neither becomes eligible nor is drained by a blind sample
 * (finding HLU-4). The CPU counter is always taken to exist; a null CPU sample is a blind one.
 */
class Governor(private val cfg: NodeConfig, private val rules: HostRules, private val settleBeforeServing: Boolean = true) {
    private val cpuGate = DwellGate(cfg.cpuOtherDrainPermille, cfg.cpuOtherDrainHoldMs, cfg.eligiblePermille, cfg.eligibleHoldMs, startEligible = !settleBeforeServing)
    private val gpuGate = DwellGate(cfg.gpuOtherDrainPermille, cfg.gpuOtherDrainHoldMs, cfg.eligiblePermille, cfg.eligibleHoldMs, startEligible = !settleBeforeServing)
    private val memGate = DwellGate(cfg.memoryPsiFullDrainCenti, cfg.memoryPsiHoldMs, cfg.memoryPsiFullDrainCenti / 2, cfg.eligibleHoldMs)

    fun evaluate(nowMs: Long, r: Readings): Evaluation {
        val presence = ArrayList<String>()
        val condition = ArrayList<String>()
        val verdict = rules.evaluate(r.power, r.signals)
        presence += verdict.presenceBlocks
        condition += verdict.conditionBlocks
        if (cpuGate.update(nowMs, r.cpuOtherPermille)) presence += "cpu-contention"
        if (gpuGate.update(nowMs, r.gpuOtherPermille)) presence += "gpu-contention"
        if (memGate.update(nowMs, r.signals.memoryPsiFullCenti)) condition += "memory-pressure"
        if (r.thermal.band >= 2) condition += "thermal-hold"
        val events = ArrayList<FsmEvent>(2)
        if (presence.isNotEmpty()) events += FsmEvent.PRESENCE_SIGNAL
        if (condition.isNotEmpty()) events += FsmEvent.CONDITION_LOST
        val unknown = settleBeforeServing && (
            r.cpuOtherPermille == null || !cpuGate.eligible ||
                (r.gpuCounterPresent && (r.gpuOtherPermille == null || !gpuGate.eligible))
            )
        if (events.isEmpty() && !unknown) events += FsmEvent.CONDITIONS_MET
        return Evaluation(events, presence, condition)
    }
}
