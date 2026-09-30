package xyz.mdhv.asom.desktop.governor

import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.ThermalReading

/**
 * A two-threshold gate with dwell: turns ACTIVE when the value stays above [onAbove] for [onHoldMs], and INACTIVE when
 * it stays below [offBelow] for [offHoldMs] ("drain above 400 permille for 10 s; eligible below 200 permille for 60 s").
 * An unreadable value (null) changes nothing and restarts both dwell timers, so a blind sample never counts either way.
 */
class DwellGate(
    private val onAbove: Int,
    private val onHoldMs: Long,
    private val offBelow: Int,
    private val offHoldMs: Long,
) {
    var active: Boolean = false
        private set
    private var aboveSince: Long? = null
    private var belowSince: Long? = null

    fun update(nowMs: Long, value: Int?): Boolean {
        if (value == null) {
            aboveSince = null
            belowSince = null
            return active
        }
        if (!active) {
            belowSince = null
            if (value > onAbove) {
                val since = aboveSince ?: nowMs.also { aboveSince = it }
                if (nowMs - since >= onHoldMs) {
                    active = true
                    aboveSince = null
                }
            } else {
                aboveSince = null
            }
        } else {
            aboveSince = null
            if (value < offBelow) {
                val since = belowSince ?: nowMs.also { belowSince = it }
                if (nowMs - since >= offHoldMs) {
                    active = false
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
)

data class Evaluation(val events: List<FsmEvent>, val presenceReasons: List<String>, val conditionReasons: List<String>)

/**
 * Turns readings into FSM events. Presence causes emit PRESENCE_SIGNAL at every evaluation while they hold, which keeps
 * refreshing the LP-2 hold-down; condition causes emit CONDITION_LOST; otherwise CONDITIONS_MET.
 * NVIDIA and other GPUs with no own-versus-other attribution pass `gpuOtherPermille = null`: the GPU rule is off and
 * only the thermal band applies (linux.md 3.4).
 */
class Governor(private val cfg: NodeConfig, private val rules: HostRules) {
    private val cpuGate = DwellGate(cfg.cpuOtherDrainPermille, cfg.cpuOtherDrainHoldMs, cfg.eligiblePermille, cfg.eligibleHoldMs)
    private val gpuGate = DwellGate(cfg.gpuOtherDrainPermille, cfg.gpuOtherDrainHoldMs, cfg.eligiblePermille, cfg.eligibleHoldMs)
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
        if (events.isEmpty()) events += FsmEvent.CONDITIONS_MET
        return Evaluation(events, presence, condition)
    }
}
