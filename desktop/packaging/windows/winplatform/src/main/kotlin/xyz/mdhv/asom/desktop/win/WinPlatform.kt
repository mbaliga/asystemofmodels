package xyz.mdhv.asom.desktop.win

import xyz.mdhv.asom.desktop.ControlSocketServer
import xyz.mdhv.asom.desktop.DesktopPlatform
import xyz.mdhv.asom.desktop.GpuContentionPort
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.ListenerGate
import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.NikStore
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.PowerPort
import xyz.mdhv.asom.desktop.PresencePort
import xyz.mdhv.asom.desktop.SystemMonotonicClock
import xyz.mdhv.asom.desktop.ThermalPort
import xyz.mdhv.asom.desktop.governor.HostRules
import xyz.mdhv.asom.desktop.governor.HostRulesProvider
import xyz.mdhv.asom.desktop.win.ctl.WinControlSocket
import xyz.mdhv.asom.desktop.win.doctor.LidAction
import xyz.mdhv.asom.desktop.win.doctor.WinDoctor
import xyz.mdhv.asom.desktop.win.doctor.WinDoctorInputs
import xyz.mdhv.asom.desktop.win.gpu.GpuEngineProbe
import xyz.mdhv.asom.desktop.win.keys.WinNikBackends
import xyz.mdhv.asom.desktop.win.keys.WinNikStore
import xyz.mdhv.asom.desktop.win.net.FirewallGate
import xyz.mdhv.asom.desktop.win.net.FirewallTarget
import xyz.mdhv.asom.desktop.win.net.GateResult
import xyz.mdhv.asom.desktop.win.net.ListenSelection
import xyz.mdhv.asom.desktop.win.net.NetshFirewallProbe
import xyz.mdhv.asom.desktop.win.net.WinListenerGate
import xyz.mdhv.asom.desktop.win.power.WinPowerPort
import xyz.mdhv.asom.desktop.win.presence.PresenceVerdict
import xyz.mdhv.asom.desktop.win.presence.WinPresencePort
import xyz.mdhv.asom.desktop.win.presence.WinPresenceReader
import xyz.mdhv.asom.desktop.win.presence.WinRules
import xyz.mdhv.asom.desktop.win.thermal.ThermalZoneProbe

/**
 * The Windows host of the desktop node (windows.md 3-6). `ServiceLoader` finds it through
 * `META-INF/services/xyz.mdhv.asom.desktop.DesktopPlatform`. Constructing it touches no Windows API: every DLL loads on the
 * first call that needs it, so a host with no Windows underneath (the Linux container, the fakes) constructs it and gets
 * [xyz.mdhv.asom.desktop.win.api.WinApiUnavailableException] only from a real call.
 *
 * Hosting modes map onto the seam's [HostMode]: USER is the per-user process (`%LOCALAPPDATA%\asom`), SYSTEM is service
 * mode (`%ProgramData%\asom`, the virtual account `NT SERVICE\asom`), FOREGROUND uses the user layout. The node refuses
 * to run as the SYSTEM account or a machine account in every mode; it does NOT refuse an elevated Administrator, because
 * the hosted CI runner is one (windows ERRATA WIN-HOST-1).
 */
class WinPlatform(
    private val env: WinEnv = WinEnv.system(),
    private val natives: WinNative = WinNative.system(env.vars["SystemRoot"] ?: "C:\\Windows"),
    private val options: WinOptions = WinOptions(),
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val thresholds: NodeConfig = NodeConfig(),
    val listenSelection: ListenSelection = ListenSelection(),
) : DesktopPlatform, HostRulesProvider {
    override val id: String = "windows"

    /** The DACL reader the service host verifies its state directory with (windows ERRATA ERR-FX-HWM-3). */
    val acl: xyz.mdhv.asom.desktop.win.acl.WinAcl get() = natives.acl

    val serviceName: String get() = options.serviceName

    @Volatile
    private var mode: HostMode = HostMode.USER

    private val powerPort: WinPowerPort by lazy { WinPowerPort(natives.powerStatus, natives.powerRequests, natives.suspend) }
    private val presencePort: WinPresencePort by lazy { WinPresencePort(natives.cpu) }
    private val gpuProbe: GpuEngineProbe? by lazy { GpuEngineProbe.create(natives.pdh) }
    private val thermalProbe: ThermalZoneProbe by lazy {
        val counters = try {
            natives.pdh.open(ThermalZoneProbe.COUNTER_PATH)
        } catch (_: Exception) {
            null
        }
        ThermalZoneProbe(counters, thresholds, clock)
    }
    private val firewallGate: FirewallGate by lazy {
        FirewallGate(NetshFirewallProbe(natives.runner, env.vars["SystemRoot"] ?: "C:\\Windows")) { firewallTarget(mode) }
    }
    private val listenerGate: WinListenerGate by lazy {
        WinListenerGate({ listenSelection.selected }, { listenSelection.lanConfirmed }, natives.interfaces, firewallGate)
    }

    override fun paths(mode: HostMode): NodePaths {
        val u = env.userName
        if (u.equals("SYSTEM", ignoreCase = true) || u.endsWith("$")) {
            throw HostRefusedException("refusing to run as the SYSTEM or a machine account (\"$u\") in any mode; use your own account or the service's virtual account")
        }
        val paths = WinPaths.toNodePaths(WinPaths.layout(mode, env.vars))
        this.mode = mode
        return paths
    }

    override fun nikStore(paths: NodePaths): NikStore =
        WinNikStore(paths, WinNikBackends(natives.cng, natives.dpapi, natives.acl), env.userSid, options.serviceName)

    override fun power(): PowerPort = powerPort

    override fun presence(): PresencePort = presencePort

    override fun gpuContention(): GpuContentionPort? = gpuProbe

    override fun thermal(): ThermalPort = thermalProbe

    override fun listenerGate(): ListenerGate = listenerGate

    override fun controlSocket(paths: NodePaths): ControlSocketServer = WinControlSocket(paths.controlSocket)

    override fun hostRules(config: NodeConfig, mode: HostMode): HostRules = WinRules(config) { presenceReader(mode).verdict() }

    fun presenceReader(mode: HostMode): WinPresenceReader =
        WinPresenceReader(mode == HostMode.SYSTEM, natives.userInput, natives.sessions, options)

    /** The program or service the firewall rule names, from the installed layout. */
    fun firewallTarget(mode: HostMode): FirewallTarget =
        if (mode == HostMode.SYSTEM) {
            FirewallTarget(null, options.serviceName, options.peerPort)
        } else {
            val dir = options.installDir ?: "${env.vars["ProgramFiles"] ?: "C:\\Program Files"}\\asom"
            FirewallTarget("$dir\\asom.exe", null, options.peerPort)
        }

    fun doctor(mode: HostMode): WinDoctor {
        val pd = env.vars["ProgramData"]
        val paths = try {
            paths(mode)
        } catch (_: Exception) {
            null
        }
        val nik = paths?.let { nikStore(it) as WinNikStore }
        return WinDoctor(
            WinDoctorInputs(
                keyStorage = { nik?.keyStorage ?: xyz.mdhv.asom.desktop.KeyStorage.UNKNOWN },
                gate = {
                    try {
                        firewallGate.evaluate()
                    } catch (e: Exception) {
                        GateResult.ProbeFailed(e.message ?: e::class.simpleName.orEmpty())
                    }
                },
                lidAction = { LidAction.read(natives.runner, env.vars["SystemRoot"] ?: "C:\\Windows") },
                files = natives.files,
                programData = pd,
                power = powerPort,
                thermal = thermalProbe,
                gpu = gpuProbe,
                jnaProperties = listOf("jna.nounpack", "jna.noclasspath", "jna.boot.library.path").associateWith { System.getProperty(it) },
                serviceMode = mode == HostMode.SYSTEM,
            ),
        )
    }
}

/** The presence verdict for tests and for `asom status`: presence-classified, never serialised. */
fun WinPlatform.presenceVerdict(mode: HostMode): PresenceVerdict = presenceReader(mode).verdict()
