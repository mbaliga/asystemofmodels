package xyz.mdhv.asom.desktop.win.net

import java.io.PrintStream

class FirewallCommandRefused(message: String) : IllegalArgumentException(message)

/**
 * What the owner asked the firewall helper to print. [lanAlias] must be an interface the owner confirmed
 * (`asom lan-confirm`); [confirmedLans] carries that record so the renderer can refuse an unconfirmed one.
 */
data class FirewallRequest(
    val kind: PeerPathKind,
    val overlayAlias: String = "Tailscale",
    val lanAlias: String? = null,
    val confirmedLans: Set<String> = emptySet(),
    val port: Int = 11436,
    /** User mode: the full program path. Service mode: null, and the rule names the service instead. */
    val programPath: String? = "C:\\Program Files\\asom\\asom.exe",
    val serviceName: String? = null,
    /** yyyy-MM-dd, supplied by the caller so the output is reproducible. */
    val dateIso: String,
)

class FirewallScript(val lines: List<String>) {
    val text: String get() = lines.joinToString("\n") + "\n"
}

/**
 * The exact `New-NetFirewallRule` text of windows.md 4.2. THE NODE PRINTS THESE COMMANDS AND NEVER APPLIES THEM: nothing in
 * this module starts PowerShell, requests elevation or runs a rule command (a source test fails the build if it did).
 * The owner reads the text and runs it in an elevated console, or does not. `asom mesh firewall enable`'s elevated apply
 * step of the spec is NOT built (windows ERRATA WIN-FW-1).
 *
 * Interpolated values are validated, not escaped. A value outside a conservative whitelist is refused, so a hostile
 * interface alias cannot end the quoted string and append a command. Aliases containing PowerShell wildcard characters
 * (`*`, `?`, `[`, `]`) are refused too: `-InterfaceAlias` treats them as wildcards, which would widen the rule.
 */
object FirewallCommand {
    const val OVERLAY_RULE = "asom-peer-overlay-in"
    const val LAN_RULE = "asom-peer-lan-in"
    private const val OVERLAY_REMOTE = "100.64.0.0/10,fd7a:115c:a1e0::/48"

    private val ALIAS = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} ._()#-]{0,63}$")
    private val PROGRAM = Regex("^[A-Za-z]:\\\\[\\p{L}\\p{N} ._()\\\\-]{1,240}\\.exe$")
    private val SERVICE = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun render(req: FirewallRequest): FirewallScript {
        if (req.port !in 1024..65535) throw FirewallCommandRefused("port out of range: ${req.port}")
        if (!DATE.matches(req.dateIso)) throw FirewallCommandRefused("date must be yyyy-MM-dd")
        if (req.programPath?.split('\\')?.any { it == ".." || it == "." } == true) throw FirewallCommandRefused("the program path must not contain relative segments")
        val scope = when {
            req.serviceName != null && req.programPath == null ->
                "-Service ${quote(req.serviceName, SERVICE, "service name")}"
            req.programPath != null && req.serviceName == null ->
                "-Program ${quote(req.programPath, PROGRAM, "program path")}"
            else -> throw FirewallCommandRefused("exactly one of a program path (user mode) and a service name (service mode) is required")
        }
        val (name, display, alias, remote) = when (req.kind) {
            PeerPathKind.OVERLAY -> Quad(OVERLAY_RULE, "asom peer listener (overlay)", quote(req.overlayAlias, ALIAS, "interface alias"), OVERLAY_REMOTE)
            PeerPathKind.LAN -> {
                val a = req.lanAlias ?: throw FirewallCommandRefused("a LAN rule needs the alias of a confirmed interface")
                if (req.confirmedLans.none { it.equals(a, ignoreCase = true) }) {
                    throw FirewallCommandRefused("interface \"$a\" is not confirmed as a LAN (asom lan-confirm); refusing to print a rule for it")
                }
                Quad(LAN_RULE, "asom peer listener (LAN)", quote(a, ALIAS, "interface alias"), "LocalSubnet")
            }
        }
        val description = "'asom: paired devices only; created by asom mesh firewall enable on ${req.dateIso}'"
        return FirewallScript(
            listOf(
                "New-NetFirewallRule -Name '$name' -DisplayName '$display' `",
                "  -Direction Inbound -Action Allow -Protocol TCP -LocalPort ${req.port} `",
                "  $scope `",
                "  -InterfaceAlias $alias -RemoteAddress $remote `",
                "  -Profile Private -EdgeTraversalPolicy Block `",
                "  -Description $description",
            ),
        )
    }

    /** The removal command (`asom mesh firewall disable`), printed the same way. */
    fun renderRemoval(): FirewallScript =
        FirewallScript(listOf("Remove-NetFirewallRule -Name '$OVERLAY_RULE','$LAN_RULE' -ErrorAction SilentlyContinue"))

    /** Writes the header, the commands and the stated limits to [out]. Nothing is executed. */
    fun print(out: PrintStream, script: FirewallScript) {
        out.println("# asom PRINTS these commands and never runs them. Read them; run them yourself in an elevated PowerShell, or do not.")
        out.println("# asom listens only after a matching allow rule exists, because a dismissed Windows Firewall prompt creates BLOCK rules")
        out.println("# that override allow rules.")
        out.print(script.text)
        out.println("# What the rule does NOT do: it narrows who can open a TCP connection; it is not the authorisation boundary")
        out.println("# (the pinned mTLS verifier is). Any process running as this program inherits it. A Group Policy that disables")
        out.println("# local rule merge makes it inert. It applies to the Private profile only; a Public network blocks the listener.")
    }

    private data class Quad(val name: String, val display: String, val alias: String, val remote: String)

    private fun quote(value: String, allowed: Regex, what: String): String {
        if (!allowed.matches(value)) throw FirewallCommandRefused("$what \"$value\" contains characters that cannot be quoted safely; refusing to print a command with it")
        return "'$value'"
    }
}
