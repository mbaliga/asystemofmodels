package xyz.mdhv.asom.desktop.win.ctl

import java.nio.file.Path
import xyz.mdhv.asom.desktop.ControlSocketServer
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.NotYetImplementedFeature
import xyz.mdhv.asom.desktop.PartiallyImplemented
import xyz.mdhv.asom.desktop.control.CallerIdentity
import xyz.mdhv.asom.desktop.control.ControlHandler
import xyz.mdhv.asom.desktop.win.WinPaths
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.AclSnapshot
import xyz.mdhv.asom.desktop.win.acl.AclVerifier
import xyz.mdhv.asom.desktop.win.acl.WinAcl
import xyz.mdhv.asom.desktop.win.acl.ServiceSid

/**
 * The owner-CLI control socket on Windows: an AF_UNIX socket whose file DACL is the ONLY local-caller identity check
 * (windows.md 3.3(c), W-D11, D25(a)). The JDK exposes no peer credentials on Windows (AW04), and connecting needs write
 * permission on the socket file [FW33]. Ledger rows therefore carry `callerPkg = local-sid:<owner SID>(acl)`.
 *
 * STATED LIMITS: any process running as the owner can connect, exactly as on Linux same-uid, and there is no
 * second-direction credential check like `SO_PEERCRED`. The client-side precheck ([precheck]) reads the socket
 * directory's owner and DACL before connecting; it is TOCTOU-prone (the directory can change between the read and the
 * connect) and says so. An Administrator or SYSTEM can do everything here.
 *
 * WHAT THIS CLASS DOES NOT DO: it does not bind. [start] throws [NotYetImplementedException]. Two reasons, both recorded in
 * windows ERRATA WIN-CTL-1: (1) `desktop/tools/check_law.py` and the desktop hygiene rules forbid a listener API in any main
 * source this wave (ERR-SCOPE-1, R3-CONFORMANCE-13: the control socket is built ahead of the open D23/D25 rulings and
 * is bound only inside tests); (2) nothing may listen by default. The ACL policy, the identity string, the path rules and
 * the precheck are built and tested; `AfUnixAclIT` binds a real AF_UNIX socket inside the test and verifies them on Windows.
 */
class WinControlSocket(override val path: Path) : ControlSocketServer, PartiallyImplemented {
    override fun start(handler: ControlHandler): AutoCloseable =
        throw NotYetImplementedException("control-socket bind (AF_UNIX)", "W5 / D-v2 (D25, D23)")

    override val notYetImplemented: List<NotYetImplementedFeature> =
        listOf(NotYetImplementedFeature("control-socket bind (AF_UNIX)", "W5 / D-v2 (D25, D23)") { start { _, _ -> error("unreachable") } })

    companion object {
        /** The ledger caller identity for an owner CLI connection. */
        fun callerIdentity(ownerSid: String): CallerIdentity = CallerIdentity("local-sid:$ownerSid(acl)")

        /** The DACL the socket's `run\` directory must carry. User mode: owner, SYSTEM, Administrators. Service mode: the service, SYSTEM, Administrators, and the owner with write only. */
        fun runDirPlan(mode: HostMode, ownerSid: String, serviceName: String = "asom"): AclPlan =
            if (mode == HostMode.SYSTEM) AclPlan.serviceRunDir(ownerSid, ServiceSid.of(serviceName)) else AclPlan.userRunDir(ownerSid)

        /** Null when [path] fits `sun_path`; otherwise the reason. Counted over UTF-8. */
        fun pathProblem(path: String): String? {
            val bytes = path.toByteArray(Charsets.UTF_8).size
            return if (bytes > WinPaths.MAX_SOCKET_PATH_BYTES) "socket path is $bytes bytes, over the limit of ${WinPaths.MAX_SOCKET_PATH_BYTES}" else null
        }

        /**
         * The client-side check of the owner CLI before it connects: the socket's directory must be owned by an expected
         * principal and its DACL must honour [plan]. Returns the violations; empty means the check passed (TOCTOU-prone).
         */
        fun precheck(plan: AclPlan, dir: AclSnapshot): List<String> = AclVerifier.violations(plan, dir)

        fun precheck(acl: WinAcl, runDir: Path, plan: AclPlan): List<String> = try {
            precheck(plan, acl.snapshot(runDir))
        } catch (e: Exception) {
            listOf("cannot read the socket directory's ACL: ${e.message ?: e::class.simpleName}")
        }
    }
}
