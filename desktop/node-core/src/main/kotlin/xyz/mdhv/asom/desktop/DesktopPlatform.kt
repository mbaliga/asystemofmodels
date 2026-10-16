package xyz.mdhv.asom.desktop

import java.nio.file.Path
import xyz.mdhv.asom.desktop.control.ControlHandler

/**
 * The seam every desktop host implements (PLATFORM_PLAN section 2). `ServiceLoader` picks the one on the classpath
 * (`META-INF/services/xyz.mdhv.asom.desktop.DesktopPlatform`). Members are exactly the plan's; hosts that cannot
 * provide one yet throw [NotYetImplementedException], which `asom-node --mode=selftest` reports by name.
 */
interface DesktopPlatform {
    /** "linux" | "windows" | "macos" | "ubuntu-touch" */
    val id: String

    /** Private state dirs (0700/0600 or DACL), backup exclusion (C7). Pure: creates nothing. Refuses illegal modes. */
    fun paths(mode: HostMode): NodePaths

    /** Reports keyStorage: tpm | os-keystore | secure-enclave | file. */
    fun nikStore(paths: NodePaths): NikStore

    /** Source, charging, band, saver; hold()/release() keep-awake; os_sleep_imminent / resumed events. */
    fun power(): PowerPort

    /** Inputs tagged PRESENCE (LP-0); never serialised. */
    fun presence(): PresencePort

    /** Other-busy permille, or null (rule off; `asom doctor` says so). */
    fun gpuContention(): GpuContentionPort?

    /** Band 0/1/2 with hysteresis. */
    fun thermal(): ThermalPort

    /** Windows firewall consent gate (C16); OPEN elsewhere. */
    fun listenerGate(): ListenerGate

    /** SO_PEERCRED (Linux) | getpeereid (macOS) | ACL (Windows). */
    fun controlSocket(paths: NodePaths): ControlSocketServer
}

/** The three hosting modes of `asom-node --mode=`. The label is what `asom status` shows and peers are told (self-reported). */
enum class HostMode(val cliName: String, val label: String) {
    SYSTEM("system", "dedicated-user"),
    USER("user", "shared-uid"),
    FOREGROUND("foreground", "foreground");

    companion object {
        fun parse(name: String): HostMode? = entries.firstOrNull { it.cliName == name }
    }
}

/** Where a node keeps things. The identity directory is outside the ledger/state directory (T17(f)). */
data class NodePaths(
    val mode: HostMode,
    val stateDir: Path,
    val dataDir: Path,
    val ledgerDir: Path,
    val identityDir: Path,
    val runtimeDir: Path,
    val controlSocket: Path,
)

/** The manifest's closed `keyStorage` enum. */
enum class KeyStorage(val wire: String) {
    STRONGBOX("strongbox"), TEE("tee"), SECURE_ENCLAVE("secure-enclave"), TPM("tpm"),
    OS_KEYSTORE("os-keystore"), FILE("file"), EPHEMERAL("ephemeral"), UNKNOWN("unknown"),
}

interface NikStore {
    val keyStorage: KeyStorage
}

enum class PowerSource(val wire: String) { AC("ac"), BATTERY("battery"), UNKNOWN("unknown") }

enum class BatteryBand(val wire: String) {
    GE80("ge80"), B50_79("50-79"), B20_49("20-49"), LT20("lt20");

    companion object {
        fun ofPercent(p: Int): BatteryBand = when {
            p >= 80 -> GE80
            p >= 50 -> B50_79
            p >= 20 -> B20_49
            else -> LT20
        }
    }
}

/** A power reading. CONDITION inputs (LP-0): power source, charging, battery. */
data class PowerReading(
    val source: PowerSource,
    val charging: Boolean,
    val hasBattery: Boolean,
    val batteryPercent: Int?,
    val batteryBand: BatteryBand?,
    /** Battery saver / low-power mode, null where the host has no such notion or it is not read. */
    val saver: Boolean?,
)

enum class SleepEvent { SLEEP_IMMINENT, RESUMED }

enum class LockKind { DELAY, BLOCK }

interface KeepAwakeHold {
    val kind: LockKind
    fun release()
}

interface PowerPort {
    fun read(): PowerReading

    /** Keep-awake. A refused lock is a state, not an error loop (linux.md 3.3); hosts throw only when unimplemented. */
    fun hold(kind: LockKind): KeepAwakeHold

    /** os_sleep_imminent / resumed. The returned handle unsubscribes. */
    fun onSleepEvents(listener: (SleepEvent) -> Unit): AutoCloseable
}

/** Marks a type as carrying a PRESENCE-classified input (LP-0). Such types are never serialised (LP-1). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class PresenceTagged

/** Presence inputs the host observes. On Linux the presence inputs are exactly the contention rows (linux.md 3.4). */
@PresenceTagged
data class PresenceSample(
    /** Other processes' CPU busy, permille of all CPU time since the previous sample; null on the first sample. */
    val cpuOtherPermille: Int?,
    /** PSI `cpu some avg10` in hundredths of a percent; null if unavailable. */
    val cpuPsiSomeAvg10Centi: Int?,
)

interface PresencePort {
    fun sample(): PresenceSample
}

@PresenceTagged
data class GpuSample(
    val deviceBusyPermille: Int,
    val ownBusyPermille: Int,
    val otherBusyPermille: Int,
)

interface GpuContentionPort {
    /** Null when the counters could not be read this tick. */
    fun sample(): GpuSample?
}

/** Band 0 = RUN, 1 = QUEUE, 2 = HOLD (`thermal.band` on the wire). */
data class ThermalReading(
    val band: Int,
    val hottestMilliC: Int?,
    val holdThresholdMilliC: Int?,
    val watchedSensors: List<String>,
)

interface ThermalPort {
    fun read(): ThermalReading
}

enum class ListenerGateDecision { OPEN, CLOSED_UNTIL_CONSENT }

interface ListenerGate {
    fun decision(): ListenerGateDecision
}

/** Linux and macOS: no OS gate before binding. Windows replaces this with the firewall consent gate (C16). */
object OpenListenerGate : ListenerGate {
    override fun decision(): ListenerGateDecision = ListenerGateDecision.OPEN
}

interface ControlSocketServer {
    val path: Path

    /** Starts serving owner-CLI frames. The handle stops it. */
    fun start(handler: ControlHandler): AutoCloseable
}

/** A feature that belongs to a later track. Hosts throw this; the selftest reports it by name. */
class NotYetImplementedException(val feature: String, val track: String) :
    UnsupportedOperationException("$feature: NOT_YET_IMPLEMENTED ($track)")

/** The host or a mode rule refuses to run (for example as root). Callers map it to a clear message, never a stack trace. */
class HostRefusedException(message: String) : RuntimeException(message)

/**
 * A member the host declares as belonging to a later track. [probe] calls it; the selftest requires it to throw
 * [NotYetImplementedException], which proves the declaration is true rather than a label.
 */
class NotYetImplementedFeature(val feature: String, val track: String, val probe: () -> Unit)

/** Implemented by a platform or port that is only partly built. */
interface PartiallyImplemented {
    val notYetImplemented: List<NotYetImplementedFeature>
}

/** A control socket that does not exist yet (DL2). Nothing is bound, ever, by this class. */
class NotYetImplementedControlSocket(override val path: Path) : ControlSocketServer, PartiallyImplemented {
    override fun start(handler: ControlHandler): AutoCloseable = throw NotYetImplementedException("control-socket", "DL2")

    override val notYetImplemented: List<NotYetImplementedFeature> =
        listOf(NotYetImplementedFeature("control-socket", "DL2") { start { _, _ -> error("unreachable") } })
}

/** A node-identity store that cannot hold a key yet. It reports `unknown` rather than a tier it does not provide. */
class NotYetImplementedNikStore(private val plannedTier: KeyStorage) : NikStore, PartiallyImplemented {
    override val keyStorage: KeyStorage = KeyStorage.UNKNOWN

    override val notYetImplemented: List<NotYetImplementedFeature> =
        listOf(NotYetImplementedFeature("nik-store (planned tier: ${plannedTier.wire})", "DL2") {
            throw NotYetImplementedException("nik-store (planned tier: ${plannedTier.wire})", "DL2")
        })
}

fun interface MonotonicClock {
    fun nowMs(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
