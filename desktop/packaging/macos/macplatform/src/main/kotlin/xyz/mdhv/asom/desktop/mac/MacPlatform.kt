package xyz.mdhv.asom.desktop.mac

import java.nio.file.Path
import xyz.mdhv.asom.desktop.ControlSocketServer
import xyz.mdhv.asom.desktop.DesktopPlatform
import xyz.mdhv.asom.desktop.GpuContentionPort
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.ListenerGate
import xyz.mdhv.asom.desktop.NikStore
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.NotYetImplementedFeature
import xyz.mdhv.asom.desktop.OpenListenerGate
import xyz.mdhv.asom.desktop.PartiallyImplemented
import xyz.mdhv.asom.desktop.PowerPort
import xyz.mdhv.asom.desktop.PresencePort
import xyz.mdhv.asom.desktop.ThermalPort
import xyz.mdhv.asom.desktop.governor.HostRules
import xyz.mdhv.asom.desktop.governor.HostRulesProvider
import xyz.mdhv.asom.desktop.mac.ctl.MacControlSocket
import xyz.mdhv.asom.desktop.mac.doctor.MacDoctor
import xyz.mdhv.asom.desktop.mac.doctor.MacDoctorInputs
import xyz.mdhv.asom.desktop.mac.exec.ProcessRunner
import xyz.mdhv.asom.desktop.mac.exec.SystemProcessRunner
import xyz.mdhv.asom.desktop.mac.gpu.MacGpuProbe
import xyz.mdhv.asom.desktop.mac.gpu.NoEngineBusy
import xyz.mdhv.asom.desktop.mac.gpu.OwnGpuBusy
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperLostException
import xyz.mdhv.asom.desktop.mac.helper.HelperProcess
import xyz.mdhv.asom.desktop.mac.helper.HelperTransport
import xyz.mdhv.asom.desktop.mac.helper.ProcessHelperLauncher
import xyz.mdhv.asom.desktop.mac.helper.Event
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.helper.Request
import xyz.mdhv.asom.desktop.mac.keys.MacNikStore
import xyz.mdhv.asom.desktop.mac.net.InterfaceLister
import xyz.mdhv.asom.desktop.mac.net.JdkInterfaceLister
import xyz.mdhv.asom.desktop.mac.power.MacPowerPort
import xyz.mdhv.asom.desktop.mac.presence.CpuLoadSource
import xyz.mdhv.asom.desktop.mac.presence.JdkCpuLoadSource
import xyz.mdhv.asom.desktop.mac.presence.MacPresencePort
import xyz.mdhv.asom.desktop.mac.presence.MacPresenceReader
import xyz.mdhv.asom.desktop.mac.presence.MacPresenceVerdict
import xyz.mdhv.asom.desktop.mac.presence.MacRules
import xyz.mdhv.asom.desktop.mac.svc.ServiceKind
import xyz.mdhv.asom.desktop.mac.svc.ServiceRegistration
import xyz.mdhv.asom.desktop.mac.thermal.MacThermalPort

/** Stands in for the helper when none is configured: every call is a lost probe, with the reason. Nothing is spawned. */
private object NoHelper : HelperTransport {
    override fun call(request: Request): Reply =
        throw HelperLostException("no helper executable is configured (the system property asom.mac.helper names it; there is no PATH lookup)")

    override fun subscribe(listener: (Event) -> Unit): AutoCloseable = AutoCloseable {}
    override val alive: Boolean = false
}

/**
 * The macOS host of the desktop node (macos.md 3, 5, 7.2; PLATFORM_PLAN section 5, step MC1). `ServiceLoader` finds it through
 * `META-INF/services/xyz.mdhv.asom.desktop.DesktopPlatform`. Constructing it starts nothing and touches no macOS API: the helper
 * is started by the first call that needs it, so a host with no Mac underneath (the Linux container, the fakes) constructs it and
 * sees only lost probes, which every port answers conservatively (unknown power blocks, unknown heat holds, unknown presence is
 * present).
 *
 * Hosting modes map onto the seam's [HostMode]: USER and FOREGROUND are the per-user LaunchAgent state (the Team-ID group container,
 * or the dev state directory when the build carries no Team ID); SYSTEM is the mode-B LaunchDaemon, which is M2 work and is
 * refused here (mac ERRATA MAC-MODE-1). The node refuses to run as root in every mode.
 *
 * The helper executable comes only from the system property `asom.mac.helper`, set by the launcher: never from the environment and
 * never from PATH, so a same-user process cannot choose which program the entitled node spawns.
 */
class MacPlatform(
    helperTransport: HelperTransport? = defaultHelperTransport(),
    env: MacEnv? = null,
    private val options: MacOptions = MacOptions(),
    private val runner: ProcessRunner = SystemProcessRunner(),
    private val thresholds: NodeConfig = NodeConfig(),
    private val ownGpuBusy: OwnGpuBusy = NoEngineBusy,
    private val cpu: CpuLoadSource = JdkCpuLoadSource(),
    private val interfaces: InterfaceLister = JdkInterfaceLister,
    private val nodeLockPath: Path = Path.of(NodeLock.DEFAULT_PATH),
    private val runningVersion: String = xyz.mdhv.asom.desktop.NodeVersion.STRING,
    private val installedVersion: () -> String? = { null },
) : DesktopPlatform, HostRulesProvider, PartiallyImplemented {
    override val id: String = "macos"

    private val transport: HelperTransport = helperTransport ?: NoHelper
    val client: HelperClient = HelperClient(transport)
    private val env: MacEnv = env ?: MacEnv.system(if (helperTransport == null) null else client)

    @Volatile
    private var layout: MacLayout? = null

    private val powerPort: MacPowerPort by lazy { MacPowerPort(client) }
    private val presencePort: MacPresencePort by lazy { MacPresencePort(cpu) }
    private val thermalPort: MacThermalPort by lazy { MacThermalPort(client) }
    private val gpuProbe: MacGpuProbe? by lazy {
        if (MacGpuProbe.counterExists(client) == false) null else MacGpuProbe(client, ownGpuBusy)
    }
    val presenceReader: MacPresenceReader by lazy { MacPresenceReader(client) }

    /** True for a build that carries no Team ID: the state is in the dev directory and `asom status` must say `UNSIGNED BUILD: container protection absent`. */
    val devState: Boolean get() = options.teamId == null

    override fun paths(mode: HostMode): NodePaths {
        if (mode == HostMode.SYSTEM) {
            throw HostRefusedException("daemon mode (mode B, a LaunchDaemon running as a role user) is M2 work, after spike S-M5 and an owner ruling (M-D2); it is not built")
        }
        if (env.ownUid() == 0 || env.userName.equals("root", ignoreCase = true)) {
            throw HostRefusedException("refusing to run as root: a root process parsing peer input is the highest-value target on the Mac")
        }
        val l = MacPaths.layout(MacPaths.modeFor(mode, options.teamId), env, options.teamId)
        layout = l
        return MacPaths.toNodePaths(l, mode)
    }

    override fun nikStore(paths: NodePaths): NikStore =
        MacNikStore(paths, client, MigrationGuard(paths.identityDir.resolve("binding.json"), { client.platformDigest() }), env.ownUid)

    override fun power(): PowerPort = powerPort

    override fun presence(): PresencePort = presencePort

    override fun gpuContention(): GpuContentionPort? = gpuProbe

    override fun thermal(): ThermalPort = thermalPort

    /** Always OPEN: macOS has no OS gate before accepting a connection [FM01]. The Application Firewall is reported by the doctor only. */
    override fun listenerGate(): ListenerGate = OpenListenerGate

    override fun controlSocket(paths: NodePaths): ControlSocketServer = MacControlSocket(paths.controlSocket)

    override fun hostRules(config: NodeConfig, mode: HostMode): HostRules = MacRules(config) { presenceVerdict() }

    fun presenceVerdict(): MacPresenceVerdict = presenceReader.verdict()

    /** The single-lending-node lock (macos.md 3.2). The node takes it before entering SERVING; nothing here does. */
    fun nodeLock(): NodeLock = NodeLock(nodeLockPath)

    fun serviceRegistration(): ServiceRegistration = ServiceRegistration(client)

    fun interfaceLister(): InterfaceLister = interfaces

    fun doctor(mode: HostMode = HostMode.USER): MacDoctor {
        val l = layout ?: runCatching { MacPaths.layout(MacPaths.modeFor(mode, options.teamId), env, options.teamId) }.getOrNull()
        val paths = l?.let { MacPaths.toNodePaths(it, mode) }
        val store = paths?.let { nikStore(it) }
        return MacDoctor(
            MacDoctorInputs(
                client = client,
                helperLossReason = { (transport as? HelperProcess)?.lastLossReason },
                keyStorage = { store?.keyStorage ?: xyz.mdhv.asom.desktop.KeyStorage.UNKNOWN },
                layout = l,
                teamId = options.teamId,
                runner = runner,
                gpuCounterExists = { MacGpuProbe.counterExists(client) },
                appPath = null,
                exists = { java.nio.file.Files.exists(Path.of(it)) },
                runningVersion = runningVersion,
                installedVersion = installedVersion,
                serviceStatus = { runCatching { serviceRegistration().status(ServiceKind.AGENT).status.wire }.getOrNull() },
                lockHeldByOther = {
                    when (val r = nodeLock().tryAcquire()) {
                        is NodeLock.Result.Acquired -> { r.handle.close(); false }
                        NodeLock.Result.HeldByOther -> true
                        is NodeLock.Result.Unavailable -> null
                    }
                },
            ),
        )
    }

    /** Declared as belonging to a later step; `asom-node --mode=selftest` calls each probe and requires it to throw the NOT_YET_IMPLEMENTED exception. */
    override val notYetImplemented: List<NotYetImplementedFeature> = listOf(
        NotYetImplementedFeature("daemon registration (mode B)", "MC9 / M2, after S-M5") { serviceRegistration().enable(ServiceKind.DAEMON) },
    )

    companion object {
        const val HELPER_PROPERTY = "asom.mac.helper"

        fun defaultHelperTransport(): HelperTransport? =
            System.getProperty(HELPER_PROPERTY)?.takeIf { it.startsWith("/") }?.let { HelperProcess(ProcessHelperLauncher(Path.of(it))) }
    }
}
