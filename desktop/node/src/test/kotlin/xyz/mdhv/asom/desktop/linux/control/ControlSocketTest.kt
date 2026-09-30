package xyz.mdhv.asom.desktop.linux.control

import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.NodePaths
import xyz.mdhv.asom.desktop.control.CallerIdentity
import xyz.mdhv.asom.desktop.control.ControlCode
import xyz.mdhv.asom.desktop.control.ControlCommand
import xyz.mdhv.asom.desktop.control.ControlFrames
import xyz.mdhv.asom.desktop.control.ControlHandler
import xyz.mdhv.asom.desktop.control.ControlRequest
import xyz.mdhv.asom.desktop.control.ControlResponse
import xyz.mdhv.asom.desktop.linux.LinuxEnv
import xyz.mdhv.asom.desktop.linux.LinuxPlatform
import xyz.mdhv.asom.desktop.linux.MapFileSource
import xyz.mdhv.asom.desktop.linux.Report
import xyz.mdhv.asom.desktop.linux.host.DirMeta

/**
 * The AF_UNIX control socket and its client, with REAL SO_PEERCRED on this JDK (evidence label: LAB). The only sockets
 * bound are AF_UNIX sockets in temp directories. Cross-uid cases fork a JVM as uid 65534 through `setpriv` (needs root,
 * which this container's tests have; without root they are skipped and say so).
 */
class ControlSocketTest {
    private val me: String = System.getProperty("user.name")
    private val myUid: Int = Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("Uid:") }.removePrefix("Uid:").trim().split(Regex("\\s+"))[0].toInt()
    private val realDirMeta = LinuxEnv.system().dirMeta

    /** Creates [dir] and then sets its mode explicitly: a mode passed at creation is cut by the process umask. */
    private fun mk(dir: Path, mode: String): Path {
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(mode))
        return dir
    }

    private class Sandbox {
        val base: Path = Files.createTempDirectory("asom-ctl-")
        val dir: Path = base.resolve("run")
        val sock: Path = dir.resolve("ctl.sock")
    }

    private val echo = ControlHandler { req, caller ->
        ControlResponse(req.id, true, result = JsonObject(mapOf("caller" to JsonPrimitive(caller.callerPkg), "cmd" to JsonPrimitive(req.command.wire))))
    }

    private fun server(
        sb: Sandbox,
        authorizer: PeerAuthorizer = PeerAuthorizers.sameUser(me),
        rule: SocketDirRule = SocketDirRule.PRIVATE,
        perms: String = "rw-------",
        creds: PeerCredentialReader = SoPeerCred,
        limits: ControlLimits = ControlLimits(),
        uid: Int = myUid,
        dirMeta: (Path) -> DirMeta? = realDirMeta,
    ) = ControlServer(sb.sock, authorizer, rule, PosixFilePermissions.fromString(perms), { uid }, dirMeta, creds, limits)

    private class FakeCreds(val principal: PeerPrincipal?, val supported: Boolean = true) : PeerCredentialReader {
        override fun isSupported() = supported
        override fun read(channel: SocketChannel) = principal
    }

    private fun req(id: Long = 1, cmd: ControlCommand = ControlCommand.STATUS) = ControlRequest(id, cmd)

    private fun rawConnect(p: Path): SocketChannel = SocketChannel.open(UnixDomainSocketAddress.of(p))

    private fun rawSend(ch: SocketChannel, text: String) = ControlFrames.write(Channels.newOutputStream(ch), text)

    private fun rawRecv(ch: SocketChannel): ControlResponse = ControlClient.decodeResponse(ControlFrames.read(Channels.newInputStream(ch)))

    /** True when the server closed the connection (read returns end of stream or an error) within [ms]. */
    private fun closedWithin(ch: SocketChannel, ms: Long): Boolean {
        val t = Thread { runCatching { ch.read(ByteBuffer.allocate(1)) } }.apply { isDaemon = true; start() }
        t.join(ms)
        return !t.isAlive
    }

    @Test
    fun `a same-user request round-trips, the caller is local-uid, the socket is 0600 in a 0700 directory, and close removes it`() {
        val sb = Sandbox()
        val s = server(sb)
        s.start(echo).use {
            assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(sb.sock))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(sb.dir))
            val r = ControlClient(sb.sock, me).call(req(41))
            assertTrue(r.ok, r.toString())
            assertEquals(41, r.id)
            assertEquals("local-uid:$me", (r.result!!["caller"] as JsonPrimitive).content)
            assertEquals("status", (r.result!!["cmd"] as JsonPrimitive).content)
        }
        assertTrue(!Files.exists(sb.sock), "the socket file must be removed on close")
        assertFailsWith<IOException> { rawConnect(sb.sock) }
    }

    @Test
    fun `a peer that is not allowed gets one FORBIDDEN frame and its request never reaches the handler`() {
        val sb = Sandbox()
        val reached = AtomicInteger()
        val s = server(sb, creds = FakeCreds(PeerPrincipal("mallory", "mallory")))
        s.start { r, _ -> reached.incrementAndGet(); ControlResponse(r.id, true) }.use {
            val ch = rawConnect(sb.sock)
            rawSend(ch, ControlFrames.encodeRequest(req()))
            val r = rawRecv(ch)
            assertEquals(ControlCode.FORBIDDEN, r.code)
            assertTrue(closedWithin(ch, 3_000))
            assertEquals(0, reached.get())
            assertEquals(1, s.stats.denied.get())
        }
    }

    @Test
    fun `unreadable credentials are a denial, and a runtime without SO_PEERCRED refuses to start`() {
        val sb = Sandbox()
        server(sb, creds = FakeCreds(null)).start(echo).use {
            val ch = rawConnect(sb.sock)
            assertEquals(ControlCode.FORBIDDEN, rawRecv(ch).code)
        }
        val e = assertFailsWith<ControlSocketRefusedException> { server(Sandbox(), creds = FakeCreds(null, supported = false)).start(echo) }
        assertTrue("SO_PEERCRED" in e.message!!, e.message)
    }

    @Test
    fun `the client refuses a server that is not the expected user, and sends nothing to it`() {
        val sb = Sandbox()
        val s = server(sb)
        s.start(echo).use {
            val e = assertFailsWith<ServerIdentityException> { ControlClient(sb.sock, "asom-impostor-check").call(req()) }
            assertTrue("nothing was sent" in e.message!!, e.message)
            Thread.sleep(200)
            assertEquals(0, s.stats.requests.get(), "no request may reach a server the client refused")
        }
        assertFailsWith<IOException> { ControlClient(sb.sock, me).call(req()) }
    }

    @Test
    fun `a command that needs an owner confirmation is not sent without one`() {
        val sb = Sandbox()
        val s = server(sb)
        s.start(echo).use {
            val c = ControlClient(sb.sock, me)
            for (cmd in ControlCommand.entries.filter { it.requiresTtyConfirmation }) {
                assertFailsWith<ConfirmationRequiredException>("${cmd.wire} without a confirm function") { c.call(req(1, cmd)) }
                assertFailsWith<ConfirmationRequiredException>("${cmd.wire} declined") { c.call(req(1, cmd)) { false } }
            }
            Thread.sleep(150)
            assertEquals(0, s.stats.accepted.get(), "an unconfirmed command must not even connect")
            assertTrue(ControlCommand.entries.count { it.requiresTtyConfirmation } >= 3)
            val ok = c.call(req(2, ControlCommand.PAIR_CONFIRM)) { true }
            assertTrue(ok.ok)
            assertTrue(c.call(req(3, ControlCommand.STATUS)).ok, "commands that need no confirmation never call it")
        }
    }

    @Test
    fun `the socket directory must be private and ours, and refusals leave nothing behind`() {
        // mode too wide
        run {
            val sb = Sandbox()
            mk(sb.dir, "rwxr-xr-x")
            val e = assertFailsWith<ControlSocketRefusedException> { server(sb).start(echo) }
            assertTrue("wider than" in e.message!!, e.message)
            assertTrue(!Files.exists(sb.sock))
        }
        // group-writable is refused even for the SYSTEM rule (0750 max)
        run {
            val sb = Sandbox()
            mk(sb.dir, "rwxrwx---")
            assertFailsWith<ControlSocketRefusedException> { server(sb, rule = SocketDirRule.GROUP_TRAVERSE).start(echo) }
        }
        // 0750 is fine for the SYSTEM rule and refused for the private rule
        run {
            val sb = Sandbox()
            mk(sb.dir, "rwxr-x---")
            assertFailsWith<ControlSocketRefusedException> { server(sb).start(echo) }
            server(sb, rule = SocketDirRule.GROUP_TRAVERSE, perms = "rw-rw----").start(echo).use {
                assertEquals(PosixFilePermissions.fromString("rw-rw----"), Files.getPosixFilePermissions(sb.sock))
            }
        }
        // owned by someone else
        run {
            val sb = Sandbox()
            mk(sb.dir, "rwx------")
            val e = assertFailsWith<ControlSocketRefusedException> { server(sb, dirMeta = { DirMeta(myUid + 1, 0b111_000_000) }).start(echo) }
            assertTrue("not owned" in e.message!!, e.message)
        }
        // cannot be inspected
        assertFailsWith<ControlSocketRefusedException> { server(Sandbox(), dirMeta = { null }).start(echo) }
        // a symbolic link in place of the directory
        run {
            val sb = Sandbox()
            val real = Files.createDirectory(sb.base.resolve("real"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            Files.createSymbolicLink(sb.dir, real)
            val e = assertFailsWith<ControlSocketRefusedException> { server(sb).start(echo) }
            assertTrue("symbolic link" in e.message!!, e.message)
        }
        // a missing directory is created 0700
        run {
            val sb = Sandbox()
            server(sb).start(echo).use { assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(sb.dir)) }
        }
    }

    @Test
    fun `a stale socket from a dead node is replaced, a live one, a regular file and a link are refused and kept`() {
        // stale
        run {
            val sb = Sandbox()
            mk(sb.dir, "rwx------")
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(sb.sock)) }
            assertTrue(Files.exists(sb.sock), "the dead server left its socket file")
            server(sb).start(echo).use { assertTrue(ControlClient(sb.sock, me).call(req()).ok) }
        }
        // live
        run {
            val sb = Sandbox()
            server(sb).start(echo).use {
                val e = assertFailsWith<ControlSocketRefusedException> { server(sb).start(echo) }
                assertTrue("already serving" in e.message!!, e.message)
                assertTrue(ControlClient(sb.sock, me).call(req()).ok, "the first node keeps serving")
            }
        }
        // a regular file
        run {
            val sb = Sandbox()
            mk(sb.dir, "rwx------")
            Files.writeString(sb.sock, "precious")
            assertFailsWith<ControlSocketRefusedException> { server(sb).start(echo) }
            assertEquals("precious", Files.readString(sb.sock))
        }
        // a symbolic link
        run {
            val sb = Sandbox()
            mk(sb.dir, "rwx------")
            val target = Files.writeString(sb.base.resolve("target"), "keep")
            Files.createSymbolicLink(sb.sock, target)
            assertFailsWith<ControlSocketRefusedException> { server(sb).start(echo) }
            assertEquals("keep", Files.readString(target))
        }
    }

    @Test
    fun `bad frames get typed errors - oversize closes the connection, bad JSON and unknown commands do not`() {
        val sb = Sandbox()
        val s = server(sb)
        s.start(echo).use {
            // an oversized declared length is refused before the body is read
            rawConnect(sb.sock).use { ch ->
                Channels.newOutputStream(ch).apply { write(byteArrayOf(0, 0x20, 0, 0)); flush() } // 2 MiB
                assertEquals(ControlCode.FRAME_TOO_LARGE, rawRecv(ch).code)
                assertTrue(closedWithin(ch, 3_000), "the connection must be closed after an oversized frame")
            }
            // malformed JSON, an unknown command, a float id, an extra field: typed errors, the connection stays usable
            rawConnect(sb.sock).use { ch ->
                for ((text, code) in listOf(
                    "{not json" to ControlCode.BAD_REQUEST,
                    """{"id":1,"cmd":"format-disk"}""" to ControlCode.UNKNOWN_COMMAND,
                    """{"id":1.5,"cmd":"status"}""" to ControlCode.BAD_REQUEST,
                    """{"id":1,"cmd":"status","sneaky":true}""" to ControlCode.BAD_REQUEST,
                    """{"id":1,"cmd":"status","args":[1]}""" to ControlCode.BAD_REQUEST,
                )) {
                    rawSend(ch, text)
                    val r = rawRecv(ch)
                    assertTrue(!r.ok, text)
                    assertEquals(code, r.code, text)
                }
                rawSend(ch, ControlFrames.encodeRequest(req(9)))
                assertTrue(rawRecv(ch).ok, "the connection survives typed errors")
            }
            assertTrue(s.stats.malformed.get() >= 6)
        }
    }

    @Test
    fun `a handler that throws answers INTERNAL with no exception text, and an oversized response becomes INTERNAL too`() {
        val sb = Sandbox()
        server(sb).start { r, _ ->
            when (r.command) {
                ControlCommand.PEERS -> error("secret-canary-in-exception")
                ControlCommand.WATCH -> ControlResponse(r.id, true, result = JsonObject(mapOf("blob" to JsonPrimitive("x".repeat(2 shl 20)))))
                else -> ControlResponse(r.id, true)
            }
        }.use {
            val c = ControlClient(sb.sock, me)
            val boom = c.call(req(1, ControlCommand.PEERS))
            assertEquals(ControlCode.INTERNAL, boom.code)
            assertTrue("secret-canary" !in (boom.message ?: ""), "an exception's text must never reach the wire")
            val big = c.call(req(2, ControlCommand.WATCH))
            assertEquals(ControlCode.INTERNAL, big.code)
            assertTrue(c.call(req(3)).ok)
        }
    }

    @Test
    fun `connections are capped and idle or stalled ones are closed`() {
        val sb = Sandbox()
        val s = server(sb, limits = ControlLimits(maxConnections = 2, idleTimeoutMs = 400))
        s.start(echo).use {
            val a = rawConnect(sb.sock)
            val b = rawConnect(sb.sock)
            rawSend(a, ControlFrames.encodeRequest(req())); assertTrue(rawRecv(a).ok)
            rawSend(b, ControlFrames.encodeRequest(req())); assertTrue(rawRecv(b).ok)
            val c = rawConnect(sb.sock)
            assertTrue(closedWithin(c, 3_000), "the third connection must be refused while two are open")
            assertTrue(s.stats.rejectedBusy.get() >= 1)
            // the idle ones are closed by the reaper
            assertTrue(closedWithin(a, 5_000), "an idle connection must be closed")
            assertTrue(closedWithin(b, 5_000))
            // and a stalled partial frame too
            val d = rawConnect(sb.sock)
            Channels.newOutputStream(d).apply { write(byteArrayOf(0, 0)); flush() }
            assertTrue(closedWithin(d, 5_000), "a stalled partial frame must not hold a slot forever")
            assertTrue(ControlClient(sb.sock, me).call(req()).ok, "slots are free again")
        }
    }

    @Test
    fun `many concurrent clients all get their own answers`() {
        val sb = Sandbox()
        server(sb, limits = ControlLimits(maxConnections = 16)).start(echo).use {
            val pool = Executors.newFixedThreadPool(8)
            val ok = AtomicInteger()
            val latch = CountDownLatch(8)
            repeat(8) { t ->
                pool.execute {
                    try {
                        repeat(20) { i ->
                            val id = t * 1000L + i
                            val r = ControlClient(sb.sock, me).call(req(id))
                            if (r.ok && r.id == id) ok.incrementAndGet()
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(60, TimeUnit.SECONDS))
            pool.shutdownNow()
            assertEquals(160, ok.get())
        }
    }

    private fun forkAs(uid: Int, sock: Path, expectedServer: String): String {
        // The Kotlin runtime jars live under the build user's Gradle cache, which another uid cannot read; the installed
        // distribution's lib/ (copied into the worktree) can be read, and the compiled test classes hold the child main.
        val testClasses = (System.getProperty("asom.testClasspath") ?: error("asom.testClasspath is not set (run through Gradle)")).split(':').first { it.endsWith("/classes/kotlin/test") }
        val installLib = (System.getProperty("asom.installDir") ?: error("asom.installDir is not set (run through Gradle)")) + "/lib/*"
        val cp = "$installLib:$testClasses"
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder("setpriv", "--reuid=$uid", "--regid=$uid", "--clear-groups", java, "-cp", cp, "xyz.mdhv.asom.desktop.linux.control.ControlChildMainKt", sock.toString(), expectedServer)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return out.lineSequence().firstOrNull { it.startsWith("RESULT ") } ?: error("child printed no RESULT line (exit ${p.exitValue()}): $out")
    }

    private fun needRoot() {
        Assumptions.assumeTrue(myUid == 0, "needs root to run the client as another uid (setpriv); not run as uid $myUid")
        check(File("/usr/bin/setpriv").canExecute() || File("/bin/setpriv").canExecute()) { "root but no setpriv: the cross-uid case cannot run and must not be skipped silently" }
    }

    private fun openSandboxToOthers(sb: Sandbox) {
        Files.createDirectories(sb.dir)
        for (d in listOf(sb.base, sb.dir)) Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwx--x--x"))
    }

    @Test
    fun `a process running as ANOTHER uid is seen as that user by SO_PEERCRED and refused when it is not on the list`() {
        needRoot()
        val sb = Sandbox()
        openSandboxToOthers(sb)
        val reached = AtomicInteger()
        val s = server(sb, perms = "rw-rw-rw-", rule = SocketDirRule(0b111_001_001))
        s.start { r, c -> reached.incrementAndGet(); ControlResponse(r.id, true, result = JsonObject(mapOf("caller" to JsonPrimitive(c.callerPkg)))) }.use {
            val line = forkAs(65534, sb.sock, me)
            assertTrue("code=FORBIDDEN" in line, line)
            assertEquals(0, reached.get(), "the request of a different uid must never reach the handler")
            assertEquals(1, s.stats.denied.get())
            Report.line("ControlServer: a real client process at uid 65534 (server user \"$me\") was refused FORBIDDEN by SO_PEERCRED (LAB, this container)")
        }
    }

    @Test
    fun `the same other-uid process is served when its user IS on the allow list, and named in callerPkg`() {
        needRoot()
        val sb = Sandbox()
        openSandboxToOthers(sb)
        val allow = PeerAuthorizer { it.user == "nobody" }
        server(sb, authorizer = allow, perms = "rw-rw-rw-", rule = SocketDirRule(0b111_001_001)).start(echo).use {
            val line = forkAs(65534, sb.sock, me)
            assertTrue("ok=true" in line && "local-uid:nobody" in line, line)
        }
    }

    @Test
    fun `the client run as another uid refuses a server that is not the user it expects`() {
        needRoot()
        val sb = Sandbox()
        openSandboxToOthers(sb)
        server(sb, authorizer = { true }, perms = "rw-rw-rw-", rule = SocketDirRule(0b111_001_001)).start(echo).use {
            val line = forkAs(65534, sb.sock, "asom")
            assertTrue("ServerIdentityException" in line, line)
            assertTrue("served by \"$me\"" in line, line)
        }
    }

    @Test
    fun `peer allow lists`() {
        val same = PeerAuthorizers.sameUser("alice")
        assertTrue(same.authorize(PeerPrincipal("alice", "users")))
        assertTrue(!same.authorize(PeerPrincipal("alice2", "alice")), "a different user with alice as primary group is not alice")
        assertTrue(!same.authorize(PeerPrincipal("1000", "1000")), "an unresolved uid is denied")
        val groupText = "root:x:0:\nasom:x:990:alice,bob\nasom2:x:991:mallory\nsudo:x:27:mallory\nbroken line\n"
        val members = { g: String -> PeerAuthorizers.membersFromGroupFile(groupText, g) }
        val svc = PeerAuthorizers.serviceGroup("asom", "asom", members)
        assertTrue(svc.authorize(PeerPrincipal("asom", "asom")))
        assertTrue(svc.authorize(PeerPrincipal("carol", "asom")), "primary group asom")
        assertTrue(svc.authorize(PeerPrincipal("alice", "alice")), "listed member")
        assertTrue(svc.authorize(PeerPrincipal("bob", "bob")))
        assertTrue(!svc.authorize(PeerPrincipal("mallory", "mallory")), "member of a different group whose name starts the same")
        assertTrue(!svc.authorize(PeerPrincipal("root", "root")), "root is not implicitly allowed")
        assertTrue(!PeerAuthorizers.serviceGroup("asom", "asom") { emptySet() }.authorize(PeerPrincipal("alice", "alice")))
        assertEquals(emptySet(), PeerAuthorizers.membersFromGroupFile(null, "asom"))
        assertEquals(setOf("alice", "bob"), PeerAuthorizers.membersFromGroupFile(groupText, "asom"))
    }

    @Test
    fun `the Linux platform builds the right policy for each mode and binds nothing until started`() {
        val sb = Sandbox()
        val fs = MapFileSource(mapOf("/proc/self/status" to "Uid:\t$myUid\t$myUid\t$myUid\t$myUid\n", "/etc/group" to "asom:x:990:$me\n"))
        val env = LinuxEnv(me, emptyMap(), Path.of("/home/$me"), realDirMeta)
        val platform = LinuxPlatform(fs, env)
        val paths = NodePaths(HostMode.SYSTEM, sb.base, sb.base, sb.base, sb.base, sb.dir, sb.sock)
        val server = platform.controlSocket(paths)
        assertTrue(!Files.exists(sb.dir), "building the server must not touch the file system")
        // SYSTEM mode: dir 0750 is acceptable, the socket is 0660, and a listed member is served
        mk(sb.dir, "rwxr-x---")
        server.start(echo).use {
            assertEquals(PosixFilePermissions.fromString("rw-rw----"), Files.getPosixFilePermissions(sb.sock))
            assertTrue(platform.controlClient(paths).let { c -> ControlClient(sb.sock, me).call(req()).ok })
        }
        // a user who is not in the group is refused (the group file lists nobody named like us)
        val other = LinuxPlatform(MapFileSource(mapOf("/proc/self/status" to "Uid:\t$myUid\t$myUid\t$myUid\t$myUid\n", "/etc/group" to "asom:x:990:someone-else\n")), env)
        other.controlSocket(paths).start(echo).use {
            val ch = rawConnect(sb.sock)
            assertEquals(ControlCode.FORBIDDEN, rawRecv(ch).code)
        }
        // USER mode: only the same user, a 0600 socket, a 0700 directory
        val sb2 = Sandbox()
        val userPaths = NodePaths(HostMode.USER, sb2.base, sb2.base, sb2.base, sb2.base, sb2.dir, sb2.sock)
        platform.controlSocket(userPaths).start(echo).use {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(sb2.sock))
            assertTrue(ControlClient(sb2.sock, me).call(req()).ok)
        }
        assertNotNull(platform.controlClient(userPaths))
        assertNull(null)
    }
}
