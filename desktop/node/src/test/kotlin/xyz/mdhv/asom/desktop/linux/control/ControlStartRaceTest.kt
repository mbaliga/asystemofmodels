package xyz.mdhv.asom.desktop.linux.control

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.control.ControlCommand
import xyz.mdhv.asom.desktop.control.ControlHandler
import xyz.mdhv.asom.desktop.control.ControlRequest
import xyz.mdhv.asom.desktop.control.ControlResponse
import xyz.mdhv.asom.desktop.linux.LinuxEnv

/**
 * A-LA-4, B-LA-6: USER and FOREGROUND share `$XDG_RUNTIME_DIR/asom/ctl.sock`. Two starts that overlap both pass the stale-socket
 * check (no file yet), one binds, and the loser's bind fails with EADDRINUSE. The loser must leave the winner's socket alone.
 */
class ControlStartRaceTest {
    private val me: String = System.getProperty("user.name")
    private val myUid: Int = Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("Uid:") }.removePrefix("Uid:").trim().split(Regex("\\s+"))[0].toInt()
    private val ok = ControlHandler { req, _ -> ControlResponse(req.id, true) }

    private fun server(sock: Path, beforeBind: () -> Unit = {}) = ControlServer(
        sock, PeerAuthorizers.sameUser(me), SocketDirRule.PRIVATE, PosixFilePermissions.fromString("rw-------"), { myUid }, LinuxEnv.system().dirMeta,
        SoPeerCred, ControlLimits(), beforeBind,
    )

    private fun newSock(): Path {
        val dir = Files.createTempDirectory("asom-race-")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        return dir.resolve("ctl.sock")
    }

    @Test
    fun `a start that loses the bind race fails and leaves the winner's socket in place and serving`() {
        val sock = newSock()
        var winner: AutoCloseable? = null
        val loser = server(sock) { winner = server(sock).start(ok) }
        try {
            assertFailsWith<IOException> { loser.start(ok) }
            assertTrue(Files.exists(sock), "the loser deleted the winner's socket")
            val r = ControlClient(sock, me).call(ControlRequest(7, ControlCommand.STATUS))
            assertTrue(r.ok, r.toString())
            assertEquals(7, r.id)
        } finally {
            winner?.close()
        }
        assertTrue(!Files.exists(sock), "the winner removes its own socket on close")
    }

    @Test
    fun `a start whose bind fails for another reason still leaves no file of its own`() {
        val sock = newSock()
        val s = server(sock) { throw IOException("injected failure before bind") }
        assertFailsWith<IOException> { s.start(ok) }
        assertTrue(!Files.exists(sock))
    }

    @Test
    fun `refusing because another node is live does not delete its socket`() {
        val sock = newSock()
        val keeper = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { it.bind(UnixDomainSocketAddress.of(sock)) }
        try {
            assertFailsWith<ControlSocketRefusedException> { server(sock).start(ok) }
            assertTrue(Files.exists(sock), "refusing because another node is live must not delete its socket")
        } finally {
            keeper.close()
            Files.deleteIfExists(sock)
        }
    }
}
