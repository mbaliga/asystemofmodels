package xyz.mdhv.asom.desktop.win.doctor

import java.io.PrintStream
import java.nio.file.Path
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.win.api.ProcessRunner
import xyz.mdhv.asom.desktop.win.api.TextFiles
import xyz.mdhv.asom.desktop.win.gpu.GpuEngineProbe
import xyz.mdhv.asom.desktop.win.keys.NikTier
import xyz.mdhv.asom.desktop.win.net.GateResult
import xyz.mdhv.asom.desktop.win.power.WinPowerPort
import xyz.mdhv.asom.desktop.win.thermal.ThermalZoneProbe

/** Every status carries a label as well as a glyph: nothing here is conveyed by colour (Invariant 6). */
enum class DoctorStatus(val tag: String) { OK("[ok]"), WARN("[warn]"), INFO("[info]"), NOT_READ("[not read]") }

data class DoctorLine(val id: String, val status: DoctorStatus, val text: String)

/** `powercfg /query` for the lid-close action, parsed without depending on the console language (GUIDs in, hex out). */
object LidAction {
    const val SUB_BUTTONS = "4f971e89-eebd-4455-a8de-9e59040e7347"
    const val LIDACTION = "5ca83367-6e45-459f-a27b-476b1d01c936"
    private val HEX = Regex("0x([0-9a-fA-F]{8})")

    data class Setting(val ac: Int, val dc: Int)

    fun parse(text: String): Setting? {
        val values = HEX.findAll(text).map { it.groupValues[1].toLong(16).toInt() }.toList()
        if (values.size < 2) return null
        return Setting(values[values.size - 2], values[values.size - 1])
    }

    fun name(v: Int): String = when (v) { 0 -> "Do nothing"; 1 -> "Sleep"; 2 -> "Hibernate"; 3 -> "Shut down"; else -> "unknown ($v)" }

    fun read(runner: ProcessRunner, systemRoot: String): Setting? = try {
        val r = runner.run("$systemRoot\\System32\\powercfg.exe", listOf("/query", "SCHEME_CURRENT", SUB_BUTTONS, LIDACTION), 10_000)
        if (r.timedOut || r.exitCode != 0) null else parse(String(r.stdout, Charsets.ISO_8859_1))
    } catch (_: Exception) {
        null
    }
}

/** What `asom doctor` reads. Everything is injected so the doctor runs, and is tested, on any OS. */
class WinDoctorInputs(
    val keyStorage: () -> KeyStorage,
    val gate: () -> GateResult,
    val lidAction: () -> LidAction.Setting?,
    val files: TextFiles,
    val programData: String?,
    val power: WinPowerPort?,
    val thermal: ThermalZoneProbe?,
    val gpu: GpuEngineProbe?,
    val jnaProperties: Map<String, String?>,
    val serviceMode: Boolean,
)

class WinDoctor(private val inputs: WinDoctorInputs) {
    fun run(): List<DoctorLine> {
        val out = ArrayList<DoctorLine>()
        out += keyTier()
        out += firewall()
        out += DoctorLine("network-profile", DoctorStatus.NOT_READ, "network profile not read: a Public profile blocks the LAN listener; asom never changes the profile")
        out += lid()
        out += tailscale()
        out += thermal()
        out += gpu()
        out += presence()
        out += power()
        out += native()
        out += DoctorLine("sleep", DoctorStatus.INFO, "asom cannot prevent user-initiated sleep (power button, lid, Start > Sleep) and never changes power settings; lending ends when the machine sleeps or the display turns off")
        return out
    }

    fun print(out: PrintStream, lines: List<DoctorLine>) {
        for (l in lines) out.println("${l.status.tag} ${l.id}: ${l.text}")
    }

    private fun keyTier(): DoctorLine = when (val k = inputs.keyStorage()) {
        KeyStorage.TPM -> tier("key-tier", NikTier.T2_TPM)
        KeyStorage.OS_KEYSTORE -> tier("key-tier", NikTier.T1_OS_KEYSTORE)
        KeyStorage.FILE -> tier("key-tier", NikTier.T0_FILE)
        else -> DoctorLine("key-tier", DoctorStatus.INFO, "no node key yet (${k.wire}); it is created at the first mesh enable, never earlier")
    }

    private fun tier(id: String, t: NikTier): DoctorLine = DoctorLine(
        id, if (t == NikTier.T0_FILE) DoctorStatus.WARN else DoctorStatus.OK,
        "${t.id} ${t.label}. Does NOT guarantee: ${t.doesNotGuarantee.joinToString("; ")}",
    )

    private fun firewall(): DoctorLine = when (val g = inputs.gate()) {
        is GateResult.Open -> DoctorLine("firewall", DoctorStatus.OK, "allow rule \"${g.allowRule}\" found and no asom block rule; this narrows who can open a TCP connection and is not the authorisation boundary")
        GateResult.RuleMissing -> DoctorLine("firewall", DoctorStatus.WARN, "${g.stateName}: no asom allow rule; the listener stays closed. Run `asom mesh firewall print`, read it, and run it yourself elevated")
        is GateResult.BlockRulePresent -> DoctorLine("firewall", DoctorStatus.WARN, "${g.stateName}: ${g.ruleNames.joinToString()} (a dismissed Windows Firewall prompt creates block rules that override allow rules); asom does not remove them")
        is GateResult.ProbeFailed -> DoctorLine("firewall", DoctorStatus.WARN, "${g.stateName}: ${g.reason}; the listener stays closed")
    }

    private fun lid(): DoctorLine {
        val s = inputs.lidAction()
            ?: return DoctorLine("lid-action", DoctorStatus.NOT_READ, "lid-close action not read (no lid setting, or powercfg unavailable)")
        val lends = s.ac == 0
        return DoctorLine(
            "lid-action", if (lends) DoctorStatus.OK else DoctorStatus.INFO,
            "lid close on AC: ${LidAction.name(s.ac)}; on battery: ${LidAction.name(s.dc)}. " +
                "Closing the lid ends lending unless the AC action is Do nothing. asom never changes it; to change it yourself: " +
                "powercfg /setacvalueindex SCHEME_CURRENT ${LidAction.SUB_BUTTONS} ${LidAction.LIDACTION} 0",
        )
    }

    private fun tailscale(): List<DoctorLine> {
        val path = inputs.programData?.let { "$it\\Tailscale\\tailscaled-env.txt" }
        val text = path?.let { inputs.files.read(Path.of(it)) }
        val optedOut = text?.lineSequence()?.any { it.trim().equals("TS_NO_LOGS_NO_SUPPORT=true", ignoreCase = true) } == true
        val log = if (optedOut) {
            DoctorLine("tailscale-log-optout", DoctorStatus.OK, "TS_NO_LOGS_NO_SUPPORT=true is set in ${path}; whether that stops every upload under Headscale is unverified")
        } else {
            DoctorLine(
                "tailscale-log-optout", DoctorStatus.WARN,
                "Tailscale on this PC uploads its own logs to Tailscale Inc. unless you add TS_NO_LOGS_NO_SUPPORT=true to C:\\ProgramData\\Tailscale\\tailscaled-env.txt and restart the Tailscale service. asom cannot see or ledger that traffic.",
            )
        }
        return listOf(
            log,
            DoctorLine("tailscale-unattended", DoctorStatus.NOT_READ, "\"Run unattended\" not read: without it the overlay drops at logoff and reboot, and a service-mode node that serves while logged out would find no eligible interface"),
        )
    }

    private fun thermal(): DoctorLine {
        val t = inputs.thermal
        return if (t == null || !t.hasSignal) {
            DoctorLine("thermal", DoctorStatus.WARN, "no usable thermal source (NO_THERMAL_SIGNAL): the thermal band is reported as 0; benchmark plans standard and sustained refuse to start and only quick may run")
        } else {
            DoctorLine("thermal", DoctorStatus.INFO, "thermal zones are read; the hold threshold is a PROVISIONAL 85 C because Windows gives no trip points")
        }
    }

    private fun gpu(): DoctorLine =
        if (inputs.gpu == null) DoctorLine("gpu-contention", DoctorStatus.WARN, "GPU engine counters not found: the GPU-contention rule is OFF")
        else DoctorLine("gpu-contention", DoctorStatus.INFO, "GPU engine counters present; the rule uses the busiest engine, which can drain earlier than needed (the conservative direction)")

    private fun presence(): DoctorLine = DoctorLine(
        "presence", DoctorStatus.INFO,
        if (inputs.serviceMode) {
            "service mode: lends only when the console session has been idle for 10 minutes; it cannot see a full-screen game, and gamepad input does not count as activity"
        } else {
            "user mode: lends only after 10 minutes without keyboard or mouse input or while the workstation is locked, and never with a full-screen, D3D or presentation app running; gamepad input does not count as activity, and the 10-minute hold-down applies on top"
        },
    )

    private fun power(): DoctorLine {
        val err = inputs.power?.lastHoldError
        return if (err == null) DoctorLine("keep-awake", DoctorStatus.INFO, "power request reason while lending: \"asom: lending compute to your paired devices\" (visible in powercfg /requests)")
        else DoctorLine("keep-awake", DoctorStatus.WARN, "the last power request was refused: $err; the machine may sleep while lending")
    }

    private fun native(): DoctorLine {
        val p = inputs.jnaProperties
        val ok = p["jna.nounpack"] == "true" && p["jna.noclasspath"] == "true" && !p["jna.boot.library.path"].isNullOrBlank()
        return if (ok) DoctorLine("native-loading", DoctorStatus.OK, "native code loads only from ${p["jna.boot.library.path"]} (jna.nounpack, jna.noclasspath)")
        else DoctorLine("native-loading", DoctorStatus.WARN, "jna.nounpack, jna.noclasspath and jna.boot.library.path are not all set: JNA may extract its DLL to a user-writable temp directory (fine for development, not for the installed node)")
    }
}
