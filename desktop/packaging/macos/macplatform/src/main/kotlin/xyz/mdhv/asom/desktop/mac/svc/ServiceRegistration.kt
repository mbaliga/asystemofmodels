package xyz.mdhv.asom.desktop.mac.svc

import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.mac.helper.HelperClient

enum class ServiceKind(val wire: String) {
    AGENT("agent"),

    /** Mode B (macos.md 3.2): opt-in, M2, only after S-M5. NOT BUILT. */
    DAEMON("daemon"),
}

/** `SMAppService.Status` as the helper reports it. */
enum class ServiceStatus(val wire: String) {
    NOT_REGISTERED("notRegistered"),
    ENABLED("enabled"),
    REQUIRES_APPROVAL("requiresApproval"),
    NOT_FOUND("notFound"),
    ;

    companion object {
        fun ofWire(w: String): ServiceStatus = entries.first { it.wire == w }
    }
}

class ServiceReport(val status: ServiceStatus, val guidance: List<String>)

/**
 * Registration of the node's LaunchAgent through the helper's `SMAppService` calls (macos.md 3.2, 3.4). Boot start is not a separate
 * toggle: nothing is registered at install, and registration happens ONLY when the owner runs `asom node enable` (or the
 * companion's button); macOS announces it with a notification and lists it in System Settings > General > Login Items, where the
 * user can switch it off. Registering does not turn lending on: lending stays OFF until `asom mesh lend on`. Used by the CLI and the
 * companion, never by the running node.
 *
 * Whether `SMAppService.agent(...).register()` works when called from a secondary executable in `Contents/MacOS` is assumption AM03
 * (spike S-M2); on a hosted runner its result is recorded and non-gating (AM20).
 */
class ServiceRegistration(private val client: HelperClient) {
    private fun wire(kind: ServiceKind): String {
        if (kind == ServiceKind.DAEMON) throw NotYetImplementedException("daemon registration (mode B)", "MC9 / M2, after S-M5")
        return kind.wire
    }

    fun status(kind: ServiceKind = ServiceKind.AGENT): ServiceReport = report(ServiceStatus.ofWire(client.svc("status", wire(kind))))

    fun enable(kind: ServiceKind = ServiceKind.AGENT): ServiceReport = report(ServiceStatus.ofWire(client.svc("register", wire(kind))))

    fun disable(kind: ServiceKind = ServiceKind.AGENT): ServiceReport = report(ServiceStatus.ofWire(client.svc("unregister", wire(kind))))

    companion object {
        const val APPROVAL_GUIDANCE = "Open System Settings, then General, then Login Items & Extensions, and allow ASOM."

        fun report(status: ServiceStatus): ServiceReport = ServiceReport(
            status,
            when (status) {
                ServiceStatus.ENABLED -> listOf("The ASOM launch agent is registered and approved; launchd starts the node at login. Lending stays OFF until you turn it on.")
                ServiceStatus.REQUIRES_APPROVAL -> listOf(
                    "The ASOM launch agent is registered but macOS is waiting for your approval before it may run.",
                    APPROVAL_GUIDANCE,
                    "The same switch turns it off again at any time.",
                )
                ServiceStatus.NOT_REGISTERED -> listOf("The ASOM launch agent is not registered: nothing runs at login. `asom node enable` registers it.")
                ServiceStatus.NOT_FOUND -> listOf(
                    "macOS cannot find the launch agent's plist in the ASOM app bundle (Contents/Library/LaunchAgents/xyz.mdhv.asom.node.plist).",
                    "Run asom from the installed ASOM.app; a development build cannot register.",
                )
            },
        )
    }
}
