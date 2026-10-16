package xyz.mdhv.asom.desktop.win.windows

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.control.ControlFrames
import xyz.mdhv.asom.desktop.win.acl.Ace
import xyz.mdhv.asom.desktop.win.acl.AclPlan
import xyz.mdhv.asom.desktop.win.acl.AclRight
import xyz.mdhv.asom.desktop.win.acl.AclVerifier
import xyz.mdhv.asom.desktop.win.acl.JdkWinAcl
import xyz.mdhv.asom.desktop.win.acl.WellKnownSid
import xyz.mdhv.asom.desktop.win.ctl.WinControlSocket
import xyz.mdhv.asom.desktop.win.fakes.Report
import xyz.mdhv.asom.desktop.win.jna.JnaIdentity
import xyz.mdhv.asom.desktop.win.jna.JnaSidResolver

/**
 * CI-ONLY (hosted Windows runner; SKIPPED elsewhere). The control socket is bound INSIDE THIS TEST, never by the node
 * (windows ERRATA WIN-CTL-1). It shows on a real NTFS disk that an owner-only DACL can be applied and read back, that the
 * verifier accepts it and rejects a leak, and that an AF_UNIX socket in the directory is reachable by the owner.
 * It does not show that another local user is refused (a hosted runner has one user); that stays NEEDS-DEVICE-VALIDATION.
 */
@EnabledOnOs(OS.WINDOWS)
class AfUnixAclIT {
    private val acl = JdkWinAcl(JnaSidResolver())

    @Test
    fun `an owner-only DACL applied to a directory reads back and passes the verifier, and a leak is caught`() {
        val owner = assertNotNull(JnaIdentity.currentUserSid(), "the runner's account SID")
        val dir = Files.createTempDirectory("asom-it-acl-")
        try {
            val plan = WinControlSocket.runDirPlan(HostMode.USER, owner)
            acl.replace(dir, plan.required)
            val snap = acl.snapshot(dir)
            Report.line("IT AfUnixAclIT: read-back owner=${snap.ownerSid} aces=${snap.aces.map { "${it.sid}:${it.rights.sorted()}" }}")
            assertEquals(emptyList(), AclVerifier.violations(plan, snap), "an applied plan must read back as the plan")
            assertEquals(emptyList(), WinControlSocket.precheck(acl, dir, plan))
            // a leak: Users may write. The read-back must show it and the precheck must refuse.
            acl.replace(dir, plan.required + Ace(WellKnownSid.USERS, true, setOf(AclRight.WRITE, AclRight.READ)))
            val leaked = WinControlSocket.precheck(acl, dir, plan)
            assertTrue(leaked.any { WellKnownSid.USERS in it }, "the verifier must see the Users ACE: $leaked")
        } finally {
            runCatching { acl.replace(dir, AclPlan.userState(owner).required) }
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an AF_UNIX socket in the protected directory accepts the owner and carries a control frame`() {
        val owner = assertNotNull(JnaIdentity.currentUserSid())
        val dir = Files.createTempDirectory("asom-it-sock-")
        try {
            acl.replace(dir, AclPlan.userRunDir(owner).required)
            val sock = dir.resolve("ctl.sock")
            assertEquals(null, WinControlSocket.pathProblem(sock.toString()), "the temp path fits sun_path")
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(sock))
                var received: String? = null
                val t = thread {
                    server.accept().use { c ->
                        received = ControlFrames.read(Channels.newInputStream(c))
                        ControlFrames.write(Channels.newOutputStream(c), "pong")
                    }
                }
                SocketChannel.open(StandardProtocolFamily.UNIX).use { c ->
                    c.connect(UnixDomainSocketAddress.of(sock))
                    ControlFrames.write(Channels.newOutputStream(c), "ping")
                    assertEquals("pong", ControlFrames.read(Channels.newInputStream(c)))
                }
                t.join(10_000)
                assertEquals("ping", received)
                Report.line("IT AfUnixAclIT: AF_UNIX connect and one frame each way as the owner ok (JDK ${System.getProperty("java.version")})")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
