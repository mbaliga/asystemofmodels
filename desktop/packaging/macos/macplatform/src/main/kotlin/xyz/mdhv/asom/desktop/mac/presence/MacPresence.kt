package xyz.mdhv.asom.desktop.mac.presence

import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.PresencePort
import xyz.mdhv.asom.desktop.PresenceSample
import xyz.mdhv.asom.desktop.PresenceTagged
import xyz.mdhv.asom.desktop.governor.DesktopRules
import xyz.mdhv.asom.desktop.governor.HostRules
import xyz.mdhv.asom.desktop.governor.HostSignals
import xyz.mdhv.asom.desktop.governor.HostVerdict
import xyz.mdhv.asom.desktop.mac.helper.HelperClient

/** Presence-classified (LP-0): never serialised, and it leaves the process only as the availability the FSM derives (LP-1). */
@PresenceTagged
data class MacPresenceVerdict(val blocks: List<String>) {
    /** True when a local user may be present and lending must not happen. */
    val present: Boolean get() = blocks.isNotEmpty()
}

/**
 * "Yield to the local user" on macOS (macos.md 2.1). The Mac may be lent only when
 *   the console user is the node's own user AND (HID idle for at least [idleThresholdMs] OR the screen is locked).
 * A DIFFERENT console user (fast user switching) counts as presence, and so does every unreadable input: unknown is the unsafe
 * answer. This is the reading R3-OVERCLAIM-4(c) states: a Mac lends only after ten minutes idle or when locked, where Linux lends
 * while someone is at the keyboard, and the user copy must say so per OS (mac ERRATA MAC-PRES-1).
 * The idle counter is HID keyboard and mouse (and trackpad) input only: a user on a game controller looks idle until the GPU
 * contention rule sees the game. Whether the counters track real use is S-M8 (owner device).
 *
 * The 10-minute idle threshold and the LP-2 10-minute hold-down COMPOSE: presence signals are emitted while the user is present
 * and the hold-down runs from the last one, so lending starts about 20 minutes after the last input (the more conservative of two
 * readings, as on Windows: mac ERRATA MAC-PRES-2).
 */
class MacPresenceReader(
    private val client: HelperClient,
    val idleThresholdMs: Long = MIN_IDLE_MS,
) {
    init {
        require(idleThresholdMs >= MIN_IDLE_MS) { "the idle threshold must not be below $MIN_IDLE_MS ms" }
    }

    fun verdict(): MacPresenceVerdict = try {
        classify(client.presence(), idleThresholdMs)
    } catch (_: Exception) {
        MacPresenceVerdict(listOf("presence-unreadable"))
    }

    companion object {
        const val MIN_IDLE_MS = 600_000L

        fun classify(p: HelperClient.PresenceInfo, idleThresholdMs: Long = MIN_IDLE_MS): MacPresenceVerdict {
            val blocks = ArrayList<String>(2)
            when (p.consoleUserIsSelf) {
                null -> blocks += "console-user-unknown"
                false -> blocks += "console-user-other"
                true -> {}
            }
            val idleEnough = p.hidIdleMs != null && p.hidIdleMs >= idleThresholdMs
            val locked = p.screenLocked == true
            if (!idleEnough && !locked) {
                blocks += if (p.hidIdleMs == null && p.screenLocked == null) "presence-unreadable" else "user-active"
            }
            return MacPresenceVerdict(blocks)
        }
    }
}

/** Where the JDK's own CPU accounting comes from; a seam so tests need no `jdk.management`. Values are 0.0 to 1.0. */
interface CpuLoadSource {
    fun systemLoad(): Double?
    fun processLoad(): Double?
}

/** The JDK's OperatingSystemMXBean. Null when the platform bean is not the `com.sun.management` one or a value is unavailable. */
class JdkCpuLoadSource : CpuLoadSource {
    private val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    override fun systemLoad(): Double? = bean?.cpuLoad?.takeIf { it >= 0.0 }
    override fun processLoad(): Double? = bean?.processCpuLoad?.takeIf { it >= 0.0 }
}

/**
 * The seam's presence input on macOS is the other-process CPU contention (a permille of all CPU capacity, like Linux and
 * Windows). The first sample is null, never 0. It reads the JDK's own accounting, which needs the `jdk.management` module in the
 * jlinked runtime.
 */
class MacPresencePort(private val cpu: CpuLoadSource = JdkCpuLoadSource()) : PresencePort {
    private var first = true

    override fun sample(): PresenceSample {
        val sys = cpu.systemLoad()
        val proc = cpu.processLoad()
        val other = if (sys == null || proc == null) null else ((sys - proc).coerceIn(0.0, 1.0) * 1000.0).toInt()
        val wasFirst = first
        first = false
        return PresenceSample(if (wasFirst) null else other, null)
    }
}

/**
 * The macOS rules: the desktop rules (a machine with a battery serves only on AC; an unknown power source blocks), plus Low
 * Power Mode as a condition block, plus the presence causes of [MacPresenceReader]. Grace is 30,000 ms, EXCEPT on a sleep drain,
 * where the node closes the listener at once and the helper is acknowledged within [SLEEP_BUDGET_MS].
 */
class MacRules(config: NodeConfig, private val presence: () -> MacPresenceVerdict) : HostRules {
    private val base = DesktopRules(config)
    override val label: String = "macos-desktop"
    override val graceMs: Long = config.graceMsDesktop
    override val blockLockAllowed: Boolean = true

    override fun evaluate(power: PowerReading, signals: HostSignals): HostVerdict {
        val v = base.evaluate(power, signals)
        val conditions = ArrayList(v.conditionBlocks)
        if (power.saver == true) conditions += "low-power-mode"
        val presenceBlocks = ArrayList(v.presenceBlocks)
        presenceBlocks += try {
            presence().blocks
        } catch (_: Exception) {
            listOf("presence-unreadable")
        }
        return HostVerdict(conditions, presenceBlocks)
    }

    companion object {
        /**
         * The delay budget of a sleep drain (macos.md 2.1). `NodeRuntime` builds its FSM from `HostRules`, which has no member for
         * it, so the node-core FSM still uses its own default of 5,000 ms (mac ERRATA MAC-SLEEP-1: a change for the desktop-core owner).
         */
        const val SLEEP_BUDGET_MS = 2_000L
    }
}
