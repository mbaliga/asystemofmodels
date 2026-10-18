package xyz.mdhv.asom.desktop.mac.macos

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.mac.MacPaths
import xyz.mdhv.asom.desktop.mac.Report
import xyz.mdhv.asom.desktop.mac.SocketLimitProbe
import xyz.mdhv.asom.desktop.mac.ctl.MacControlSocket
import xyz.mdhv.asom.desktop.mac.ctl.SoPeerCred

/**
 * `getpeereid` through the JDK's SO_PEERCRED on a real Mac (FM27), in the per-user temporary directory, in both directions. The
 * socket is bound HERE, in the test: the main sources never bind. CI (hosted VM) evidence.
 */
@EnabledOnOs(OS.MAC)
class ControlSocketIT {
    private val me = System.getProperty("user.name")
    private val uid: Int? = runCatching { Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int }.getOrNull()

    @Test
    fun `peer credentials name this user in both directions, in a 0700 directory under the per-user temp dir`() {
        assertTrue(SoPeerCred.isSupported(), "this JDK exposes peer credentials for AF_UNIX on macOS (FM27)")
        val tmp = System.getenv("TMPDIR") ?: error("TMPDIR is not set: this is not a per-user macOS session")
        val dir = Path.of(tmp.trimEnd('/'), "xyz.mdhv.asom-it-${ProcessHandle.current().pid()}")
        MacPaths.createPrivateDir(dir)
        try {
            assertEquals(emptyList(), MacControlSocket.dirProblems(dir, uid))
            val path = dir.resolve("ctl.sock")
            assertEquals(null, MacControlSocket.pathProblem(path.toString()), "the real per-user socket path fits sun_path: $path")
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(path))
                SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                    client.connect(UnixDomainSocketAddress.of(path))
                    server.accept().use { accepted ->
                        val seenByServer = SoPeerCred.read(accepted)
                        val seenByClient = SoPeerCred.read(client)
                        assertNotNull(seenByServer); assertNotNull(seenByClient)
                        assertEquals(me, seenByServer.user)
                        assertEquals(me, seenByClient.user)
                        assertTrue(MacControlSocket.authorizeClient(seenByServer, me))
                        assertTrue(MacControlSocket.verifyServer(seenByClient, me))
                        Report.line("IT ControlSocketIT: getpeereid (via SO_PEERCRED) -> user=${seenByServer.user} group=${seenByServer.group}")
                    }
                }
            }
        } finally {
            Files.deleteIfExists(dir.resolve("ctl.sock"))
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `the longest socket path this JDK can bind on this Mac is at least the bytes the node allows`() {
        // A short, fixed base: macOS TMPDIR (/var/folders/.../T) alone can be too long for every probed length to be built.
        val base = Files.createTempDirectory(Path.of("/tmp"), "asom-len-")
        val lengths = 90..110
        val result = try {
            SocketLimitProbe.probe(base, lengths) { path ->
                try {
                    ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(path)) }
                    true
                } catch (_: Exception) {
                    false
                } finally {
                    Files.deleteIfExists(path)
                }
            }
        } finally {
            Files.deleteIfExists(base)
        }
        Report.line("IT ControlSocketIT: longest bindable socket path probed in 90..110 = ${result.longest} bytes over ${result.tried.size} lengths (the node allows ${MacPaths.MAX_SOCKET_PATH_BYTES})")
        val problem = SocketLimitProbe.problem(result, lengths, MacPaths.MAX_SOCKET_PATH_BYTES)
        assertTrue(problem == null, problem.orEmpty())
    }
}
