package xyz.mdhv.asom.desktop.linux.control

import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import xyz.mdhv.asom.desktop.ControlSocketServer
import xyz.mdhv.asom.desktop.NotYetImplementedException
import xyz.mdhv.asom.desktop.NotYetImplementedFeature
import xyz.mdhv.asom.desktop.PartiallyImplemented
import xyz.mdhv.asom.desktop.control.CallerIdentity
import xyz.mdhv.asom.desktop.control.ControlCode
import xyz.mdhv.asom.desktop.control.ControlFrameException
import xyz.mdhv.asom.desktop.control.ControlFrames
import xyz.mdhv.asom.desktop.control.ControlHandler
import xyz.mdhv.asom.desktop.control.ControlResponse
import xyz.mdhv.asom.desktop.linux.host.DirMeta

/** The control socket refuses to start (unsafe directory, a live node already there, no peer credentials). */
class ControlSocketRefusedException(message: String) : IllegalStateException(message)

/**
 * The directory that holds the socket. It must be owned by this process's uid and must grant group/other nothing
 * beyond [maxMode] (a subset test on the nine permission bits): 0700 for USER and FOREGROUND, 0750 for SYSTEM where
 * systemd's `RuntimeDirectoryMode=0750` lets group `asom` reach the socket.
 */
data class SocketDirRule(val maxMode: Int) {
    companion object {
        val PRIVATE = SocketDirRule(0b111_000_000)
        val GROUP_TRAVERSE = SocketDirRule(0b111_101_000)
    }
}

data class ControlLimits(
    val maxConnections: Int = 8,
    /** A connection that has not completed a request for this long is closed (also ends a stalled partial frame). */
    val idleTimeoutMs: Long = 30_000,
)

class ControlServerStats {
    val accepted = AtomicInteger()
    val denied = AtomicInteger()
    val requests = AtomicInteger()
    val malformed = AtomicInteger()
    val rejectedBusy = AtomicInteger()
}

/**
 * The owner-CLI control socket (CD-24, linux.md 7.3): an AF_UNIX stream server. Every connection is checked with
 * SO_PEERCRED BEFORE a single byte is read from it; a denied peer gets one FORBIDDEN frame and is closed. Frames are
 * the closed [ControlFrames] codec (1 MiB cap, integers only). This class is BUILT AHEAD of the D23/D25 rulings
 * (R3-CONFORMANCE-13): `asom-node` does not start it, so a running node still binds nothing; only a test or a later
 * owner-approved wiring calls [start]. Construction touches nothing.
 */
class ControlServer(
    override val path: Path,
    private val authorizer: PeerAuthorizer,
    private val dirRule: SocketDirRule,
    private val socketPermissions: Set<PosixFilePermission>,
    private val ownUid: () -> Int,
    private val dirMeta: (Path) -> DirMeta?,
    private val peerCreds: PeerCredentialReader = SoPeerCred,
    private val limits: ControlLimits = ControlLimits(),
) : ControlSocketServer, PartiallyImplemented {

    val stats = ControlServerStats()

    override val notYetImplemented: List<NotYetImplementedFeature> = listOf(
        NotYetImplementedFeature("control-socket (serving from asom-node: built and tested, not started until D23/D25 are ruled)", "D23/D25") {
            throw NotYetImplementedException("control-socket serving from asom-node", "D23/D25")
        },
    )

    override fun start(handler: ControlHandler): AutoCloseable {
        if (!peerCreds.isSupported()) throw ControlSocketRefusedException("SO_PEERCRED is not available on this runtime; the control socket will not start without it")
        val dir = path.parent ?: throw ControlSocketRefusedException("control socket path has no directory: $path")
        ensureDirectory(dir)
        clearStaleSocket()

        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            server.bind(UnixDomainSocketAddress.of(path))
            Files.setPosixFilePermissions(path, socketPermissions)
        } catch (e: Exception) {
            runCatching { server.close() }
            runCatching { Files.deleteIfExists(path) }
            throw e
        }
        return Running(server, handler)
    }

    private fun ensureDirectory(dir: Path) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        }
        if (Files.isSymbolicLink(dir)) throw ControlSocketRefusedException("socket directory is a symbolic link: $dir")
        val meta = dirMeta(dir) ?: throw ControlSocketRefusedException("socket directory cannot be inspected: $dir")
        if (meta.ownerUid != ownUid()) throw ControlSocketRefusedException("socket directory $dir is not owned by this user")
        if (meta.permissions and dirRule.maxMode.inv() and 0b111_111_111 != 0) {
            throw ControlSocketRefusedException("socket directory $dir has mode 0${Integer.toOctalString(meta.permissions)}, wider than 0${Integer.toOctalString(dirRule.maxMode)}")
        }
    }

    private fun clearStaleSocket() {
        val attrs = try {
            Files.readAttributes(path, "unix:mode,uid", LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return
        }
        val isSocket = ((attrs["mode"] as Int) and 0xF000) == 0xC000
        if (!isSocket || (attrs["uid"] as Int) != ownUid()) {
            throw ControlSocketRefusedException("$path exists and is not a socket owned by this user; refusing to remove it")
        }
        val live = try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).use { true }
        } catch (_: IOException) {
            false
        }
        if (live) throw ControlSocketRefusedException("another node is already serving $path")
        Files.delete(path)
    }

    private inner class Running(private val server: ServerSocketChannel, private val handler: ControlHandler) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val slots = Semaphore(limits.maxConnections)
        private val live = ConcurrentHashMap.newKeySet<Connection>()
        private val workers: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "asom-ctl-conn").apply { isDaemon = true } }
        private val reaper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "asom-ctl-reaper").apply { isDaemon = true } }
        private val acceptor = Thread(::acceptLoop, "asom-ctl-accept").apply { isDaemon = true }

        init {
            val period = (limits.idleTimeoutMs / 4).coerceIn(10, 1_000)
            reaper.scheduleWithFixedDelay({ reap() }, period, period, TimeUnit.MILLISECONDS)
            acceptor.start()
        }

        private fun reap() {
            val now = System.nanoTime()
            for (c in live) {
                val expired = c.hasHardDeadline && now - c.hardDeadlineNanos > 0
                if (expired || (!c.busy && now - c.lastActivityNanos > TimeUnit.MILLISECONDS.toNanos(limits.idleTimeoutMs))) c.close()
            }
        }

        private fun acceptLoop() {
            while (!closed.get()) {
                val ch = try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
                stats.accepted.incrementAndGet()
                if (!slots.tryAcquire()) {
                    stats.rejectedBusy.incrementAndGet()
                    runCatching { ch.close() }
                    continue
                }
                val conn = Connection(ch)
                live += conn
                try {
                    workers.execute { serve(conn) }
                } catch (_: Exception) {
                    live -= conn
                    slots.release()
                    conn.close()
                }
            }
        }

        private fun serve(conn: Connection) {
            try {
                val peer = peerCreds.read(conn.channel)
                if (peer == null || !authorizer.authorize(peer)) {
                    stats.denied.incrementAndGet()
                    conn.sendAndLinger(ControlResponse(0, false, ControlCode.FORBIDDEN, "not permitted"))
                    return
                }
                val caller = CallerIdentity("local-uid:${peer.user}")
                val input = Channels.newInputStream(conn.channel)
                while (!closed.get()) {
                    conn.lastActivityNanos = System.nanoTime()
                    val text = try {
                        ControlFrames.read(input)
                    } catch (_: EOFException) {
                        return
                    } catch (e: ControlFrameException) {
                        stats.malformed.incrementAndGet()
                        conn.sendAndLinger(ControlResponse(0, false, e.code, "bad frame"))
                        return
                    }
                    val request = try {
                        ControlFrames.decodeRequest(text)
                    } catch (e: ControlFrameException) {
                        stats.malformed.incrementAndGet()
                        conn.send(ControlResponse(0, false, e.code, e.message))
                        continue
                    }
                    stats.requests.incrementAndGet()
                    conn.busy = true
                    val response = try {
                        handler.handle(request, caller)
                    } catch (_: Exception) {
                        ControlResponse(request.id, false, ControlCode.INTERNAL, "internal error")
                    } finally {
                        conn.busy = false
                        conn.lastActivityNanos = System.nanoTime()
                    }
                    conn.send(response)
                }
            } catch (_: IOException) {
                // the peer went away or the connection was closed by the reaper or by close()
            } finally {
                live -= conn
                conn.close()
                slots.release()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { server.close() }
            for (c in live) c.close()
            reaper.shutdownNow()
            workers.shutdownNow()
            acceptor.join(2_000)
            workers.awaitTermination(2, TimeUnit.SECONDS)
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private class Connection(val channel: SocketChannel) {
        @Volatile var lastActivityNanos: Long = System.nanoTime()
        @Volatile var busy: Boolean = false
        @Volatile var hasHardDeadline: Boolean = false
        @Volatile var hardDeadlineNanos: Long = 0
        private val out = Channels.newOutputStream(channel)

        @Synchronized
        fun send(r: ControlResponse) {
            val payload = ControlFrames.encodeResponse(r)
            val bytes = try {
                ControlFrames.frame(payload.toByteArray(Charsets.UTF_8))
            } catch (_: ControlFrameException) {
                ControlFrames.frame(ControlFrames.encodeResponse(ControlResponse(r.id, false, ControlCode.INTERNAL, "response too large")).toByteArray(Charsets.UTF_8))
            }
            out.write(bytes)
            out.flush()
        }

        /**
         * Sends a final frame and then closes GRACEFULLY: a plain close with unread request bytes in the receive buffer
         * makes the kernel reset the connection, and the peer could lose the frame it is meant to read. So the write side
         * is shut down first and incoming bytes are discarded until the peer closes or [LINGER_MS] passes (the reaper closes
         * the channel then), which ends the drain.
         */
        fun sendAndLinger(r: ControlResponse) {
            hardDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LINGER_MS)
            hasHardDeadline = true
            try {
                send(r)
                channel.shutdownOutput()
                val sink = ByteBuffer.allocate(4096)
                while (channel.read(sink) >= 0) sink.clear()
            } catch (_: IOException) {
                // closed by the reaper at the deadline, or the peer reset: nothing more to do
            }
        }

        fun close() {
            runCatching { channel.close() }
        }
    }

    private companion object {
        const val LINGER_MS = 500L
    }
}
