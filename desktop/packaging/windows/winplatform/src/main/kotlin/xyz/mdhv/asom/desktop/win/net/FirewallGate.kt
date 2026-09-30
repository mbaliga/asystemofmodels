package xyz.mdhv.asom.desktop.win.net

import xyz.mdhv.asom.desktop.ListenerGate
import xyz.mdhv.asom.desktop.ListenerGateDecision
import xyz.mdhv.asom.desktop.win.api.ProcessRunner

/** One inbound firewall rule as `netsh advfirewall firewall show rule ... verbose` prints it. Unknown fields are null. */
data class FirewallRule(
    val displayName: String,
    val enabled: Boolean?,
    val inbound: Boolean?,
    /** "Allow", "Block" or "Bypass". */
    val action: String?,
    val protocol: String?,
    val localPort: String?,
    val program: String?,
    val service: String?,
    val profiles: String?,
    val remoteIp: String?,
) {
    /** The four fields without which a rule cannot be judged. */
    val judgeable: Boolean get() = enabled != null && inbound != null && action != null
}

class ParsedRules(val rules: List<FirewallRule>) {
    val judgeableCount: Int get() = rules.count { it.judgeable }
}

/**
 * Parses the English output of `netsh advfirewall firewall show rule name=all dir=in verbose`. The labels are localised on
 * other Windows languages; there the parse recognises nothing, [FirewallGate] sees zero judgeable rules and stays CLOSED
 * (fail closed, never "no rules, so open"). `COM INetFwPolicy2` is the locale-proof reader and belongs to a later step.
 * CRLF and LF are both accepted (netsh prints CRLF).
 */
object NetshRuleParser {
    private val KEY = Regex("^([A-Za-z][A-Za-z ]*?):\\s+(.*)$")
    private val KEY_EMPTY = Regex("^([A-Za-z][A-Za-z ]*?):\\s*$")

    fun parse(text: String): ParsedRules {
        val rules = ArrayList<FirewallRule>()
        var cur: MutableMap<String, String>? = null
        fun flush() {
            val m = cur ?: return
            rules += FirewallRule(
                displayName = m["Rule Name"].orEmpty(),
                enabled = m["Enabled"]?.let { yesNo(it) },
                inbound = m["Direction"]?.let { dir(it) },
                action = m["Action"]?.trim(),
                protocol = m["Protocol"]?.trim(),
                localPort = m["LocalPort"]?.trim(),
                program = m["Program"]?.trim(),
                service = m["Service"]?.trim(),
                profiles = m["Profiles"]?.trim(),
                remoteIp = m["RemoteIP"]?.trim(),
            )
            cur = null
        }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r', ' ', '\t')
            if (line.isEmpty() || line.startsWith("---")) continue
            val m = KEY.matchEntire(line)
            val key: String
            val value: String
            if (m != null) {
                key = m.groupValues[1].trim()
                value = m.groupValues[2].trim()
            } else {
                val e = KEY_EMPTY.matchEntire(line) ?: continue
                key = e.groupValues[1].trim()
                value = ""
            }
            if (key == "Rule Name") {
                flush()
                cur = HashMap()
            }
            cur?.put(key, value)
        }
        flush()
        return ParsedRules(rules)
    }

    private fun yesNo(v: String): Boolean? = when (v.trim().lowercase()) { "yes" -> true; "no" -> false; else -> null }
    private fun dir(v: String): Boolean? = when (v.trim().lowercase()) { "in" -> true; "out" -> false; else -> null }
}

/** The result of reading the firewall: rules, or a reason the read failed. */
sealed interface FirewallRead {
    class Rules(val parsed: ParsedRules) : FirewallRead
    class Failed(val reason: String) : FirewallRead
}

interface FirewallProbe {
    fun read(): FirewallRead
}

/** Runs the read-only `netsh` listing. `netsh.exe` is addressed by absolute path under `%SystemRoot%\System32`, never by PATH. */
class NetshFirewallProbe(private val runner: ProcessRunner, private val systemRoot: String, private val timeoutMs: Long = 20_000) : FirewallProbe {
    override fun read(): FirewallRead {
        val r = try {
            runner.run("$systemRoot\\System32\\netsh.exe", listOf("advfirewall", "firewall", "show", "rule", "name=all", "dir=in", "verbose"), timeoutMs)
        } catch (e: Exception) {
            return FirewallRead.Failed("could not run netsh: ${e.message ?: e::class.simpleName}")
        }
        if (r.timedOut) return FirewallRead.Failed("netsh timed out after $timeoutMs ms")
        // The console output is OEM-encoded; ISO-8859-1 keeps every byte, so ASCII labels and paths parse exactly.
        val text = String(r.stdout, Charsets.ISO_8859_1)
        val parsed = NetshRuleParser.parse(text)
        if (parsed.judgeableCount == 0) return FirewallRead.Failed("netsh output was not recognised (exit ${r.exitCode}; a non-English Windows prints other labels)")
        return FirewallRead.Rules(parsed)
    }
}

/** What the listener would bind, so the gate can tell which rules concern it. */
data class FirewallTarget(val programPath: String?, val serviceName: String?, val port: Int) {
    init {
        require(programPath != null || serviceName != null) { "a target needs a program or a service" }
    }
}

sealed interface GateResult {
    class Open(val allowRule: String) : GateResult
    data object RuleMissing : GateResult
    class BlockRulePresent(val ruleNames: List<String>) : GateResult
    class ProbeFailed(val reason: String) : GateResult

    /** The state name `asom status` shows. */
    val stateName: String
        get() = when (this) {
            is Open -> "OPEN"
            RuleMissing -> "FIREWALL_RULE_MISSING"
            is BlockRulePresent -> "FIREWALL_BLOCK_RULE_PRESENT"
            is ProbeFailed -> "FIREWALL_PROBE_FAILED"
        }
}

/**
 * The firewall consent gate (C16; windows.md 4.2). The listener never starts until the probe finds an allow rule that asom's
 * own printed command creates AND no block rule that concerns asom. Windows Firewall lets an explicit block rule override
 * any allow rule [FW05], and a cancelled first-listen prompt creates block rules, so listening first is not an option.
 *
 * Every failure path is CLOSED: a probe that throws, times out or cannot read the output is [GateResult.ProbeFailed], never OPEN.
 *
 * An allow rule counts only if all of these hold: enabled, inbound, Allow, TCP, its display name starts with
 * "asom peer listener" (so a foreign rule never stands in for the owner's consent to asom's own rule), its local port is
 * exactly the peer port, it names asom's program or service, its remote scope is not Any, and its profiles include Private.
 * A block rule concerns asom if it is enabled, inbound, Block, TCP or any protocol, and names asom's program or service or
 * asom's display prefix, or (no program, port covers the peer port, remote Any) blocks the port for everyone. A rule that
 * names asom but cannot be judged (a missing action) counts as a block.
 *
 * What it does NOT guarantee: that the rule is scoped to the interface alias (netsh does not print it), that the current
 * network profile is Private, that a Group Policy has not disabled local rule merge, or that the state stays true after
 * the read (the gate is a moment; the listener re-checks on every bind).
 */
class FirewallGate(
    private val probe: FirewallProbe,
    private val target: () -> FirewallTarget,
) : ListenerGate {
    fun evaluate(): GateResult {
        val read = try {
            probe.read()
        } catch (e: Exception) {
            return GateResult.ProbeFailed("probe threw ${e::class.simpleName}: ${e.message}")
        }
        val rules = when (read) {
            is FirewallRead.Failed -> return GateResult.ProbeFailed(read.reason)
            is FirewallRead.Rules -> read.parsed.rules
        }
        val t = try {
            target()
        } catch (e: Exception) {
            return GateResult.ProbeFailed("no target: ${e.message}")
        }
        val blocks = rules.filter { blocksAsom(it, t) }
        if (blocks.isNotEmpty()) return GateResult.BlockRulePresent(blocks.map { it.displayName })
        val allow = rules.firstOrNull { allowsAsom(it, t) }
        return if (allow != null) GateResult.Open(allow.displayName) else GateResult.RuleMissing
    }

    override fun decision(): ListenerGateDecision =
        if (evaluate() is GateResult.Open) ListenerGateDecision.OPEN else ListenerGateDecision.CLOSED_UNTIL_CONSENT

    companion object {
        const val DISPLAY_PREFIX = "asom peer listener"

        private fun namesAsom(r: FirewallRule, t: FirewallTarget): Boolean =
            (t.programPath != null && r.program != null && samePath(r.program, t.programPath)) ||
                (t.serviceName != null && r.service != null && r.service.equals(t.serviceName, ignoreCase = true)) ||
                r.displayName.startsWith(DISPLAY_PREFIX, ignoreCase = true)

        internal fun samePath(a: String, b: String): Boolean =
            a.trim().trim('"').replace('/', '\\').equals(b.trim().trim('"').replace('/', '\\'), ignoreCase = true)

        internal fun portsCover(spec: String?, port: Int): Boolean {
            val s = spec?.trim().orEmpty()
            if (s.isEmpty() || s.equals("Any", ignoreCase = true) || s == "*") return true
            return s.split(',').any { part ->
                val p = part.trim()
                val dash = p.indexOf('-')
                if (dash > 0) {
                    val lo = p.substring(0, dash).trim().toIntOrNull()
                    val hi = p.substring(dash + 1).trim().toIntOrNull()
                    lo != null && hi != null && port in lo..hi
                } else p.toIntOrNull() == port
            }
        }

        private fun isAny(v: String?): Boolean = v == null || v.isBlank() || v.equals("Any", ignoreCase = true) || v == "*" || v.equals("All", ignoreCase = true)

        private fun tcpOrAny(r: FirewallRule): Boolean = r.protocol == null || r.protocol.equals("TCP", ignoreCase = true) || r.protocol.equals("Any", ignoreCase = true)

        fun blocksAsom(r: FirewallRule, t: FirewallTarget): Boolean {
            if (r.enabled == false || r.inbound == false) return false
            val ours = namesAsom(r, t)
            if (!r.judgeable) return ours
            if (!r.action.equals("Block", ignoreCase = true)) return false
            if (!tcpOrAny(r)) return false
            if (ours) return true
            return isAny(r.program) && isAny(r.service) && portsCover(r.localPort, t.port) && isAny(r.remoteIp)
        }

        fun allowsAsom(r: FirewallRule, t: FirewallTarget): Boolean {
            if (r.enabled != true || r.inbound != true || !r.action.equals("Allow", ignoreCase = true)) return false
            if (r.protocol == null || !r.protocol.equals("TCP", ignoreCase = true)) return false
            if (!r.displayName.startsWith(DISPLAY_PREFIX, ignoreCase = true)) return false
            if (isAny(r.localPort) || !portsCover(r.localPort, t.port)) return false
            val names = (t.programPath != null && r.program != null && samePath(r.program, t.programPath)) ||
                (t.serviceName != null && r.service != null && r.service.equals(t.serviceName, ignoreCase = true))
            if (!names) return false
            if (isAny(r.remoteIp)) return false
            val prof = r.profiles ?: return false
            return prof.contains("Private", ignoreCase = true) || prof.equals("All", ignoreCase = true) || prof.equals("Any", ignoreCase = true)
        }
    }
}
