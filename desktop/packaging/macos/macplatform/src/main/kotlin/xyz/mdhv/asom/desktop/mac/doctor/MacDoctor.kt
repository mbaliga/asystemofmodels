package xyz.mdhv.asom.desktop.mac.doctor

import java.io.PrintStream
import xyz.mdhv.asom.desktop.KeyStorage
import xyz.mdhv.asom.desktop.mac.MacLayout
import xyz.mdhv.asom.desktop.mac.exec.ProcessRunner
import xyz.mdhv.asom.desktop.mac.exec.SystemProcessRunner
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.ProtocolSpec
import xyz.mdhv.asom.desktop.mac.keys.NikTier

/** Severity is a WORD, never a colour (Invariant 6). */
enum class DoctorLevel(val tag: String) {
    OK("ok"),
    INFO("info"),
    WARN("warn"),
    UNKNOWN("unknown"),
    NOT_YET("not-yet-implemented"),
}

class DoctorLine(val level: DoctorLevel, val check: String, val detail: String)

/**
 * Parsers of the read-only tools' text. English output only: anything not recognised is `unknown`, never a guess (a check that
 * cannot tell says so). The samples in the tests are SYNTHETIC hand-written listings, not captures from a Mac: they are
 * UNVERIFIED against a real macOS until the owner-device run (mac DEVICE_CHECKLIST).
 */
object DoctorParsers {
    enum class Firewall { ENABLED, DISABLED, UNKNOWN }

    /** `socketfilterfw --getglobalstate`: "Firewall is enabled. (State = 1)" or "Firewall is disabled. (State = 0)". */
    fun firewallGlobalState(text: String): Firewall = when {
        Regex("(?i)firewall is enabled").containsMatchIn(text) -> Firewall.ENABLED
        Regex("(?i)firewall is disabled").containsMatchIn(text) -> Firewall.DISABLED
        else -> Firewall.UNKNOWN
    }

    /** True when `pmset -g assertions` lists an assertion with asom's reason. */
    fun asomAssertionHeld(text: String): Boolean = ProtocolSpec.HOLD_REASON in text

    /** `pmset -g batt`: "Now drawing from 'AC Power'" or "'Battery Power'". Null when not recognised. */
    fun powerSource(text: String): String? = Regex("Now drawing from '([^']+)'").find(text)?.groupValues?.get(1)

    /** `ioreg -r -k AppleClamshellState -d 4`: `"AppleClamshellState" = Yes` means the lid is closed. Null when there is no such key (a desktop). */
    fun lidClosed(text: String): Boolean? =
        Regex("\"AppleClamshellState\"\\s*=\\s*(Yes|No)").find(text)?.groupValues?.get(1)?.let { it == "Yes" }

    class TailscaleProcess(val commandLine: String, val noLogs: Boolean)

    /** `pgrep -fl tailscaled` lines that really are a tailscaled command line, and whether `--no-logs-no-support` is on it. */
    fun tailscaledProcesses(text: String): List<TailscaleProcess> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.substringAfter(' ', it) }
        .filter { Regex("(^|/)tailscaled(\\s|$)").containsMatchIn(it) }
        .map { TailscaleProcess(it, "--no-logs-no-support" in it) }
        .toList()
}

/** What the doctor reads. Every member is a function so the doctor never touches the operating system by itself. */
class MacDoctorInputs(
    val client: HelperClient?,
    val helperLossReason: () -> String?,
    val keyStorage: () -> KeyStorage,
    val layout: MacLayout?,
    val teamId: String?,
    val runner: ProcessRunner,
    val gpuCounterExists: () -> Boolean?,
    val appPath: String?,
    val exists: (String) -> Boolean,
    val runningVersion: String,
    val installedVersion: () -> String?,
    val serviceStatus: () -> String?,
    val lockHeldByOther: () -> Boolean?,
)

/**
 * `asom doctor` for macOS (macos.md 3.3, 4.1, 4.2, 4.3, 5, 8.3): what the node knows about itself and its surroundings, in words.
 * It reads through the read-only allowlisted [SystemProcessRunner] and the helper only; it changes nothing (never `socketfilterfw`
 * set options, never `pmset` settings). Anything it cannot read is `unknown`. Output goes to an injected stream.
 */
class MacDoctor(private val inputs: MacDoctorInputs) {
    fun run(): List<DoctorLine> {
        val lines = ArrayList<DoctorLine>()
        helper(lines)
        keyTier(lines)
        container(lines)
        localNetwork(lines)
        firewall(lines)
        power(lines)
        tailscale(lines)
        gpu(lines)
        loginItem(lines)
        lock(lines)
        version(lines)
        lines += DoctorLine(DoctorLevel.INFO, "homebrew", "Homebrew sends analytics unless HOMEBREW_NO_ANALYTICS=1 or `brew analytics off`; that traffic is Homebrew's, not asom's, and asom's own ledger does not cover it.")
        lines += DoctorLine(DoctorLevel.NOT_YET, "tray", "the menu-bar companion is not built (after D14 part A)")
        lines += DoctorLine(DoctorLevel.NOT_YET, "daemon-mode", "mode B (LaunchDaemon, serve while logged out) is not built (M2, after spike S-M5)")
        return lines
    }

    fun render(out: PrintStream) {
        val lines = run()
        out.println("asom doctor (macOS)")
        for (l in lines) out.println("  [${l.level.tag}] ${l.check}: ${l.detail}")
        val counts = DoctorLevel.entries.joinToString(", ") { lv -> "${lines.count { it.level == lv }} ${lv.tag}" }
        out.println("doctor: $counts")
    }

    private fun helper(lines: MutableList<DoctorLine>) {
        val c = inputs.client
        if (c == null) {
            lines += DoctorLine(DoctorLevel.UNKNOWN, "helper", "no helper client")
            return
        }
        try {
            val h = c.hello()
            lines += DoctorLine(DoctorLevel.OK, "helper", "asom-mac-helper ${h.helper} on macOS ${h.macos} ${h.arch} (${h.model}); Secure Enclave ${if (h.se) "available" else "NOT available"}")
        } catch (e: Exception) {
            lines += DoctorLine(DoctorLevel.WARN, "helper", "not reachable: ${inputs.helperLossReason() ?: e.message ?: e::class.simpleName}; every probe is lost until it restarts (at most once per minute)")
        }
    }

    private fun keyTier(lines: MutableList<DoctorLine>) {
        val ks = inputs.keyStorage()
        val tier = NikTier.entries.firstOrNull { it.keyStorage == ks }
        if (tier == null) {
            lines += DoctorLine(DoctorLevel.INFO, "key-tier", "no node identity yet (it is made at the first mesh enable): Secure Enclave where available, otherwise a file")
        } else {
            lines += DoctorLine(DoctorLevel.INFO, "key-tier", "${tier.id} ${tier.keyStorage.wire} (${tier.label})")
            for (g in tier.doesNotGuarantee) lines += DoctorLine(DoctorLevel.INFO, "key-tier", "does NOT guarantee: $g")
        }
        lines += DoctorLine(DoctorLevel.INFO, "key-tier", NikTier.REJECTED_LOGIN_KEYCHAIN)
    }

    private fun container(lines: MutableList<DoctorLine>) {
        val l = inputs.layout
        if (inputs.teamId == null) {
            lines += DoctorLine(DoctorLevel.WARN, "container", "UNSIGNED BUILD: container protection absent (state is in ${l?.root ?: "the dev state directory"}; the Team-ID group container needs a Developer-ID signed build, owner decision M-D10)")
        } else {
            lines += DoctorLine(DoctorLevel.INFO, "container", "Team-ID group container ${l?.root}: protected by SIP on macOS 15 and 26 and denied to other developer teams by default on macOS 27 (whether that covers every kind of process is unverified, AM08)")
        }
    }

    private fun localNetwork(lines: MutableList<DoctorLine>) {
        lines += DoctorLine(
            DoctorLevel.INFO, "local-network",
            "macOS 15 asks once, when asom first dials a LAN address (not for inbound connections, not for the overlay). If it was denied, dials to LAN peers fail with \"No route to host\" and asom writes the DIAL outcome local-network-denied; change it in System Settings > Privacy & Security > Local Network (there is no way for an app to reset it).",
        )
    }

    private fun firewall(lines: MutableList<DoctorLine>) {
        val r = try {
            inputs.runner.run(SystemProcessRunner.SOCKETFILTERFW, listOf("--getglobalstate"), 5_000)
        } catch (e: Exception) {
            lines += DoctorLine(DoctorLevel.UNKNOWN, "firewall", "cannot read the Application Firewall state: ${e.message ?: e::class.simpleName}")
            return
        }
        when (DoctorParsers.firewallGlobalState(r.text)) {
            DoctorParsers.Firewall.ENABLED -> {
                lines += DoctorLine(DoctorLevel.INFO, "firewall", "the Application Firewall is on: it may prompt for or block incoming connections; signed software can be allowed automatically if you chose that option. asom never changes it.")
                inputs.appPath?.let { p ->
                    try {
                        val blocked = inputs.runner.run(SystemProcessRunner.SOCKETFILTERFW, listOf("--getappblocked", p), 5_000).text
                        val verdict = if (Regex("(?i)blocked").containsMatchIn(blocked) && !Regex("(?i)not blocked|permitted").containsMatchIn(blocked)) "asom is blocked from incoming connections" else "no block on asom was recognised (output not verified against a real Mac)"
                        lines += DoctorLine(DoctorLevel.INFO, "firewall", verdict)
                    } catch (_: Exception) {
                        lines += DoctorLine(DoctorLevel.UNKNOWN, "firewall", "cannot read whether asom is blocked")
                    }
                }
            }
            DoctorParsers.Firewall.DISABLED -> lines += DoctorLine(DoctorLevel.INFO, "firewall", "the Application Firewall is off")
            DoctorParsers.Firewall.UNKNOWN -> lines += DoctorLine(DoctorLevel.UNKNOWN, "firewall", "the firewall state was not recognised (English output only)")
        }
    }

    private fun power(lines: MutableList<DoctorLine>) {
        try {
            val a = inputs.runner.run(SystemProcessRunner.PMSET, listOf("-g", "assertions"), 5_000).text
            lines += DoctorLine(
                DoctorLevel.INFO, "pmset-assertion",
                if (DoctorParsers.asomAssertionHeld(a)) "asom holds its keep-awake assertion (\"${ProtocolSpec.HOLD_REASON}\"), so the Mac will not idle-sleep; lid close, Apple-menu sleep and low battery still sleep it"
                else "asom holds no keep-awake assertion (it holds one only while SERVING)",
            )
        } catch (e: Exception) {
            lines += DoctorLine(DoctorLevel.UNKNOWN, "pmset-assertion", "cannot read power assertions: ${e.message ?: e::class.simpleName}")
        }
        try {
            val b = DoctorParsers.powerSource(inputs.runner.run(SystemProcessRunner.PMSET, listOf("-g", "batt"), 5_000).text)
            lines += DoctorLine(if (b == null) DoctorLevel.UNKNOWN else DoctorLevel.INFO, "power-source", b?.let { "drawing from $it (a Mac lends only on AC power, and never on battery by default)" } ?: "the power source was not recognised")
        } catch (e: Exception) {
            lines += DoctorLine(DoctorLevel.UNKNOWN, "power-source", "cannot read the power source: ${e.message ?: e::class.simpleName}")
        }
        try {
            val lid = DoctorParsers.lidClosed(inputs.runner.run(SystemProcessRunner.IOREG, listOf("-r", "-k", "AppleClamshellState", "-d", "4"), 5_000).text)
            lines += DoctorLine(
                DoctorLevel.INFO, "lid",
                when (lid) {
                    null -> "no lid (a desktop Mac), or the lid state is not readable"
                    true -> "the lid is closed: a MacBook sleeps unless it is in closed-display mode with an external display, power, keyboard and pointer"
                    false -> "the lid is open"
                },
            )
        } catch (e: Exception) {
            lines += DoctorLine(DoctorLevel.UNKNOWN, "lid", "cannot read the lid state: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun tailscale(lines: MutableList<DoctorLine>) {
        val procs = try {
            DoctorParsers.tailscaledProcesses(inputs.runner.run(SystemProcessRunner.PGREP, listOf("-fl", "tailscaled"), 5_000).text)
        } catch (e: Exception) {
            lines += DoctorLine(DoctorLevel.UNKNOWN, "tailscale", "cannot look for tailscaled: ${e.message ?: e::class.simpleName}")
            return
        }
        val app = "/Applications/Tailscale.app"
        when {
            procs.isNotEmpty() -> {
                val p = procs.first()
                lines += DoctorLine(DoctorLevel.INFO, "tailscale", "open-source tailscaled (kernel utun, runs before login)")
                lines += if (p.noLogs) DoctorLine(DoctorLevel.OK, "tailscale", "--no-logs-no-support is on the tailscaled command line")
                else DoctorLine(DoctorLevel.WARN, "tailscale", "--no-logs-no-support is NOT on the tailscaled command line: this client uploads its logs to log.tailscale.com")
            }
            inputs.exists(app) -> {
                val store = inputs.exists("$app/Contents/_MASReceipt/receipt")
                lines += DoctorLine(
                    DoctorLevel.WARN, "tailscale",
                    "the ${if (store) "Mac App Store" else "Standalone"} Tailscale app is installed: it uploads its logs to log.tailscale.com with no documented opt-out on macOS (only the open-source tailscaled has --no-logs-no-support). asom never installs, configures or starts Tailscale.",
                )
            }
            else -> lines += DoctorLine(DoctorLevel.INFO, "tailscale", "no Tailscale client found (only the LAN path is available)")
        }
        lines += DoctorLine(DoctorLevel.INFO, "tailscale", "the operator (or your Headscale) learns your device graph; relayed traffic crosses DERP servers encrypted; a Funnel-type feature publishes a port beyond the overlay: asom never uses it, check that it is off")
    }

    private fun gpu(lines: MutableList<DoctorLine>) {
        when (inputs.gpuCounterExists()) {
            true -> lines += DoctorLine(DoctorLevel.INFO, "gpu-contention", "the GPU utilisation counter exists; \"other busy\" is the device figure minus asom's own (an estimate: no per-process GPU accounting exists without root)")
            false -> lines += DoctorLine(DoctorLevel.INFO, "gpu-contention", "no GPU utilisation counter: the GPU contention rule is OFF and only the thermal band applies")
            null -> lines += DoctorLine(DoctorLevel.UNKNOWN, "gpu-contention", "could not decide (the helper is not reachable)")
        }
    }

    private fun loginItem(lines: MutableList<DoctorLine>) {
        val s = inputs.serviceStatus()
        lines += when (s) {
            null -> DoctorLine(DoctorLevel.UNKNOWN, "login-item", "the registration state could not be read")
            "enabled" -> DoctorLine(DoctorLevel.INFO, "login-item", "registered and approved (System Settings > General > Login Items shows ASOM)")
            "requiresApproval" -> DoctorLine(DoctorLevel.WARN, "login-item", "registered, waiting for your approval in System Settings > General > Login Items & Extensions")
            "notRegistered" -> DoctorLine(DoctorLevel.INFO, "login-item", "not registered: nothing runs at login")
            else -> DoctorLine(DoctorLevel.INFO, "login-item", "not found in this build ($s)")
        }
    }

    private fun lock(lines: MutableList<DoctorLine>) {
        lines += when (inputs.lockHeldByOther()) {
            true -> DoctorLine(DoctorLevel.INFO, "node-lock", "another user's asom node holds this Mac's single lending slot: this node stays ARMED (another-user-node); borrowing is unaffected")
            false -> DoctorLine(DoctorLevel.INFO, "node-lock", "no other user's node holds the lending slot")
            null -> DoctorLine(DoctorLevel.UNKNOWN, "node-lock", "the lending slot could not be checked")
        }
    }

    private fun version(lines: MutableList<DoctorLine>) {
        val installed = inputs.installedVersion()
        lines += when {
            installed == null -> DoctorLine(DoctorLevel.UNKNOWN, "version", "running ${inputs.runningVersion}; the installed version could not be read")
            installed == inputs.runningVersion -> DoctorLine(DoctorLevel.OK, "version", "running ${inputs.runningVersion}")
            else -> DoctorLine(DoctorLevel.WARN, "version", "running ${inputs.runningVersion} but $installed is installed: run `asom node restart` to use the new code")
        }
    }
}
