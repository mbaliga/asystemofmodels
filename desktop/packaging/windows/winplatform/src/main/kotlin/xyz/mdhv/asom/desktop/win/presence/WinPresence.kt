package xyz.mdhv.asom.desktop.win.presence

import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.PresenceSample
import xyz.mdhv.asom.desktop.PresenceTagged
import xyz.mdhv.asom.desktop.PresencePort
import xyz.mdhv.asom.desktop.governor.DesktopRules
import xyz.mdhv.asom.desktop.governor.HostRules
import xyz.mdhv.asom.desktop.governor.HostSignals
import xyz.mdhv.asom.desktop.governor.HostVerdict
import xyz.mdhv.asom.desktop.win.WinOptions
import xyz.mdhv.asom.desktop.win.api.CpuLoadSource
import xyz.mdhv.asom.desktop.win.api.NotificationState
import xyz.mdhv.asom.desktop.win.api.SessionApi
import xyz.mdhv.asom.desktop.win.api.UserInputApi

/** Presence-classified (LP-0): never serialised, and it leaves the process only as the availability the FSM derives (LP-1). */
@PresenceTagged
data class PresenceVerdict(val blocks: List<String>) {
    /** True when a local user may be present and lending must not happen. */
    val present: Boolean get() = blocks.isNotEmpty()
}

/**
 * "Yield to the local user" on Windows (windows.md 2.1). The user may be lent around only when
 *  (idle for at least [idleThresholdMs] OR the workstation is locked) AND no full-screen, D3D or presentation app runs.
 * This is the reading R3-OVERCLAIM-4(c) states: Windows lends only after ten minutes idle or when locked, where Linux
 * lends while someone is at the keyboard. Every unreadable input counts as "present" (unknown is the unsafe answer).
 *
 * "Locked" needs two independent signals to agree (WTS lock flag AND `SHQueryUserNotificationState` = NOT_PRESENT),
 * because the polarity of the WTS flag is disputed between Windows versions (AW07); a wrong polarity therefore cannot
 * make an active session look locked. The idle counter counts keyboard and mouse only: a user on a gamepad looks idle
 * until the full-screen check or the GPU-contention rule sees the game (stated in `asom doctor`).
 *
 * Service mode runs in session 0 and cannot see the user's shell, so it has only the console session's idle time and
 * has NO full-screen check (windows ERRATA WIN-PRES-2).
 *
 * The 10-minute idle threshold and the LP-2 10-minute hold-down COMPOSE (up to about 20 minutes after the last input
 * before lending): presence signals are emitted while the user is present, and the hold-down runs from the last one
 * (windows ERRATA WIN-PRES-1, the more conservative of two readings).
 */
class WinPresenceReader(
    private val service: Boolean,
    private val input: UserInputApi,
    private val sessions: SessionApi,
    private val options: WinOptions = WinOptions(),
    val idleThresholdMs: Long = MIN_IDLE_MS,
) {
    init {
        require(idleThresholdMs >= MIN_IDLE_MS) { "the idle threshold must not be below $MIN_IDLE_MS ms" }
    }

    fun verdict(): PresenceVerdict = try {
        PresenceVerdict(if (service) serviceBlocks() else userBlocks())
    } catch (_: Exception) {
        PresenceVerdict(listOf("presence-unreadable"))
    }

    private fun userBlocks(): List<String> {
        val blocks = ArrayList<String>(2)
        val state = input.notificationState()
        when (state) {
            null -> blocks += "notification-state-unreadable"
            NotificationState.BUSY, NotificationState.RUNNING_D3D_FULL_SCREEN, NotificationState.PRESENTATION_MODE, NotificationState.APP ->
                blocks += "fullscreen-or-presentation-app"
            else -> {}
        }
        val idle = input.idleMs()
        val idleEnough = idle != null && idle >= idleThresholdMs
        val locked = sessions.currentSession()?.lockFlagLocked == true && state == NotificationState.NOT_PRESENT
        if (!idleEnough && !locked) blocks += if (idle == null) "input-idle-unreadable" else "user-active"
        return blocks
    }

    private fun serviceBlocks(): List<String> {
        val s = sessions.consoleSession()
        if (s == null || s.userName.isBlank()) {
            return if (options.serveWhileLoggedOut) emptyList() else listOf("no-user-session")
        }
        val idle = s.idleMs
        return if (idle != null && idle >= idleThresholdMs) emptyList() else listOf(if (idle == null) "session-idle-unreadable" else "user-active")
    }

    companion object {
        const val MIN_IDLE_MS = 600_000L
    }
}

/**
 * The seam's presence input on Windows is the other-process CPU contention (a permille of all CPU capacity, like Linux,
 * ERR-CPU-1). The first sample is null, never 0. It reads the JDK's own accounting, which needs the `jdk.management`
 * module in the jlinked runtime.
 */
class WinPresencePort(private val cpu: CpuLoadSource) : PresencePort {
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
 * The Windows rules: the desktop rules (a machine with a battery serves only on AC; unknown power blocks), plus battery
 * saver as a condition block, plus the presence causes of [WinPresenceReader]. Grace is 30,000 ms.
 */
class WinRules(config: NodeConfig, private val presence: () -> PresenceVerdict) : HostRules {
    private val base = DesktopRules(config)
    override val label: String = "windows-desktop"
    override val graceMs: Long = config.graceMsDesktop
    override val blockLockAllowed: Boolean = true

    override fun evaluate(power: PowerReading, signals: HostSignals): HostVerdict {
        val v = base.evaluate(power, signals)
        val conditions = ArrayList(v.conditionBlocks)
        if (power.saver == true) conditions += "battery-saver"
        val presenceBlocks = ArrayList(v.presenceBlocks)
        presenceBlocks += try {
            presence().blocks
        } catch (_: Exception) {
            listOf("presence-unreadable")
        }
        return HostVerdict(conditions, presenceBlocks)
    }
}

/** Reads the JDK's OperatingSystemMXBean. Null when the platform bean is not the `com.sun.management` one or a value is unavailable. */
class JdkCpuLoadSource : CpuLoadSource {
    private val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    override fun systemLoad(): Double? = bean?.cpuLoad?.takeIf { it >= 0.0 }
    override fun processLoad(): Double? = bean?.processCpuLoad?.takeIf { it >= 0.0 }
}
