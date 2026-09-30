package xyz.mdhv.asom.desktop.linux

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import xyz.mdhv.asom.desktop.DesktopPlatform
import xyz.mdhv.asom.desktop.GpuContentionPort
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.KeepAwakeHold
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.ListenerGate
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.NikStore
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.NotYetImplementedControlSocket
import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.NotYetImplementedFeature
import xyz.mdhv.asom.desktop.NotYetImplementedNikStore
import xyz.mdhv.asom.desktop.OpenListenerGate
import xyz.mdhv.asom.desktop.PartiallyImplemented
import xyz.mdhv.asom.desktop.PowerPort
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.PresencePort
import xyz.mdhv.asom.desktop.SleepEvent
import xyz.mdhv.asom.desktop.SystemMonotonicClock
import xyz.mdhv.asom.desktop.ThermalPort
import xyz.mdhv.asom.desktop.governor.DesktopRules
import xyz.mdhv.asom.desktop.governor.HostRules
import xyz.mdhv.asom.desktop.governor.HostRulesProvider
import xyz.mdhv.asom.desktop.governor.HostSignals
import xyz.mdhv.asom.desktop.governor.HostSignalsProvider
import xyz.mdhv.asom.desktop.linux.host.DirMeta
import xyz.mdhv.asom.desktop.linux.host.HostCheckInput
import xyz.mdhv.asom.desktop.linux.host.LinuxHostModeRules
import xyz.mdhv.asom.desktop.linux.host.LinuxPaths
import xyz.mdhv.asom.desktop.linux.host.OsRelease
import xyz.mdhv.asom.desktop.linux.host.SteamOsPolicy
import xyz.mdhv.asom.desktop.linux.probes.CpuProbe
import xyz.mdhv.asom.desktop.linux.probes.FileSource
import xyz.mdhv.asom.desktop.linux.probes.GpuProbe
import xyz.mdhv.asom.desktop.linux.probes.MemoryProbe
import xyz.mdhv.asom.desktop.linux.probes.PowerProbe
import xyz.mdhv.asom.desktop.linux.probes.RealFileSource
import xyz.mdhv.asom.desktop.linux.probes.ThermalProbe

/** What the Linux host reads from the process; replaced wholesale in tests. */
class LinuxEnv(
    val userName: String,
    val vars: Map<String, String>,
    val home: Path,
    val dirMeta: (Path) -> DirMeta?,
) {
    companion object {
        fun system(): LinuxEnv = LinuxEnv(
            userName = System.getProperty("user.name") ?: "",
            vars = System.getenv(),
            home = Path.of(System.getProperty("user.home") ?: "/"),
            dirMeta = ::realDirMeta,
        )

        private fun realDirMeta(p: Path): DirMeta? = try {
            val owner = (Files.getAttribute(p, "unix:uid") as Int)
            val perms = Files.getPosixFilePermissions(p)
            var bits = 0
            for (perm in PosixFilePermission.entries) {
                if (perm in perms) bits = bits or (1 shl (8 - perm.ordinal))
            }
            DirMeta(owner, bits)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The Linux host: probes and Deck rules only this wave (DL0 + DL1). The control socket, the sleep watcher, the
 * keep-awake locks and the identity store belong to DL2 and are declared NOT_YET_IMPLEMENTED, not faked.
 */
class LinuxPlatform(
    private val fs: FileSource = RealFileSource(),
    private val env: LinuxEnv = LinuxEnv.system(),
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val thresholds: NodeConfig = NodeConfig(),
) : DesktopPlatform, HostRulesProvider, HostSignalsProvider {
    override val id: String = "linux"

    private val osRelease: OsRelease by lazy { OsRelease.parse(fs.read("/etc/os-release")) }
    private val powerProbe = PowerProbe(fs)
    private val memoryProbe = MemoryProbe(fs)
    private val cpuProbe = CpuProbe(fs)
    private val thermalProbe = ThermalProbe(fs, thresholds.thermalHysteresisMilliC, thresholds.thermalDwellMs, thresholds.thermalNoTripHoldMilliC, clock)
    private val gpuProbe: GpuProbe? by lazy { GpuProbe.find(fs, clock) }
    private val powerPort = LinuxPowerPort(powerProbe)

    override fun paths(mode: HostMode): NodePaths {
        val (real, effective) = uids()
        val input = HostCheckInput(
            realUid = real, effectiveUid = effective, userName = env.userName, env = env.vars, osRelease = osRelease,
            runtimeDir = env.vars["XDG_RUNTIME_DIR"]?.takeIf { it.startsWith("/") }?.let { env.dirMeta(Path.of(it)) },
        )
        LinuxHostModeRules.refusal(mode, input)?.let { throw HostRefusedException(it) }
        return LinuxPaths.resolve(mode, env.vars, env.home)
    }

    override fun nikStore(paths: NodePaths): NikStore = NotYetImplementedNikStore(KeyStorage.FILE)

    override fun power(): PowerPort = powerPort

    override fun presence(): PresencePort = cpuProbe

    override fun gpuContention(): GpuContentionPort? = gpuProbe

    override fun thermal(): ThermalPort = thermalProbe

    override fun listenerGate(): ListenerGate = OpenListenerGate

    override fun controlSocket(paths: NodePaths) = NotYetImplementedControlSocket(paths.controlSocket)

    override fun hostRules(config: NodeConfig, mode: HostMode): HostRules =
        if (osRelease.isSteamOS) SteamOsPolicy(config) else DesktopRules(config)

    /**
     * Only the memory pressure is known. Whether the Deck is docked, whether a game runs and whether it is in Game Mode
     * have no specified mechanism (R3-OVERCLAIM-4), so they stay null: unknown, which the Deck rules treat as the
     * unsafe answer.
     */
    override fun hostSignals(): HostSignals = HostSignals(memoryPsiFullCenti = memoryProbe.read().psiFullAvg10Centi)

    /** Real and effective uid from `/proc/self/status` (the JDK has no getuid). Unreadable counts as root: refuse. */
    private fun uids(): Pair<Int, Int> {
        val line = fs.read("/proc/self/status")?.lineSequence()?.firstOrNull { it.startsWith("Uid:") }
            ?: return 0 to 0
        val f = line.removePrefix("Uid:").trim().split(Regex("\\s+"))
        return (f.getOrNull(0)?.toIntOrNull() ?: 0) to (f.getOrNull(1)?.toIntOrNull() ?: 0)
    }
}

/** `read()` is real (sysfs). Keep-awake locks and sleep events are the DL2 Inhibitor and SleepWatcher; not built here. */
class LinuxPowerPort(private val probe: PowerProbe) : PowerPort, PartiallyImplemented {
    override fun read(): PowerReading = probe.read()

    override fun hold(kind: LockKind): KeepAwakeHold = throw NotYetImplementedException("keep-awake locks (Inhibitor)", "DL2")

    override fun onSleepEvents(listener: (SleepEvent) -> Unit): AutoCloseable =
        throw NotYetImplementedException("sleep watcher (logind PrepareForSleep)", "DL2")

    override val notYetImplemented: List<NotYetImplementedFeature> = listOf(
        NotYetImplementedFeature("keep-awake locks (Inhibitor)", "DL2") { hold(LockKind.DELAY) },
        NotYetImplementedFeature("sleep watcher (logind PrepareForSleep)", "DL2") { onSleepEvents { } },
    )
}
