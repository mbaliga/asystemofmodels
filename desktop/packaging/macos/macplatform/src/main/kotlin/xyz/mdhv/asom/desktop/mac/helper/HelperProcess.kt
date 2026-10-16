package xyz.mdhv.asom.desktop.mac.helper

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.SystemMonotonicClock

/** A running helper: its pipes and its lifecycle. The only file that starts an operating-system process is [ProcessHelperLauncher]. */
interface HelperChild {
    val stdin: OutputStream
    val stdout: InputStream
    fun isAlive(): Boolean
    fun destroyForcibly()
    fun waitFor(timeoutMs: Long): Boolean
}

fun interface HelperLauncher {
    fun launch(): HelperChild
}

/**
 * Starts `<executable> serve` with pipes only: no socket, no XPC service. stderr is discarded (the helper writes none), and the
 * environment is reduced to [HelperEnvironment.scrub] so nothing the node inherited (secrets, `DYLD_*`, `JAVA_TOOL_OPTIONS`)
 * reaches the helper.
 */
class ProcessHelperLauncher(private val command: List<String>, private val parentEnv: Map<String, String> = System.getenv()) : HelperLauncher {
    constructor(executable: java.nio.file.Path) : this(listOf(executable.toString(), "serve"))

    override fun launch(): HelperChild {
        val pb = ProcessBuilder(command)
        val env = pb.environment()
        env.clear()
        env.putAll(HelperEnvironment.scrub(parentEnv))
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        val p = pb.start()
        return object : HelperChild {
            override val stdin: OutputStream = p.outputStream
            override val stdout: InputStream = p.inputStream
            override fun isAlive(): Boolean = p.isAlive
            override fun destroyForcibly() {
                p.destroyForcibly()
            }

            override fun waitFor(timeoutMs: Long): Boolean = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }
}

object HelperEnvironment {
    /** Only `TMPDIR` survives: `NSTemporaryDirectory()` in the helper is the per-user directory the CLI also sees as `$TMPDIR`. */
    fun scrub(parent: Map<String, String>): Map<String, String> =
        parent["TMPDIR"]?.takeIf { it.startsWith("/") && '\u0000' !in it }?.let { mapOf("TMPDIR" to it) } ?: emptyMap()
}

enum class HelperState { NOT_STARTED, RUNNING, LOST, CLOSED }

/**
 * The node's side of the helper pipe (macos.md 3.5). Requests carry ids, so responses and unsolicited events can interleave.
 * Rules:
 *  - the first call starts the helper and says `hello` with protocol version 1; a helper that answers anything else is dropped;
 *  - a helper that exits, hangs (no reply within [callTimeoutMs]) or breaks the protocol is LOST: every pending call fails with
 *    [HelperLostException] (`PROBE_LOST`), the child is killed, and callers treat every probe as lost;
 *  - a lost helper is restarted at most once per [minRestartIntervalMs] (one minute), counting a failed start; in between,
 *    calls fail at once without touching the operating system;
 *  - [close] closes the helper's stdin (its EOF is what makes it release its power assertion and exit) and then waits.
 * Events run on their own thread, so an event listener may call back into [call] (for example `sleep.ack`).
 */
class HelperProcess(
    private val launcher: HelperLauncher,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val callTimeoutMs: Long = 10_000,
    private val minRestartIntervalMs: Long = 60_000,
    private val closeWaitMs: Long = 2_000,
) : HelperTransport, AutoCloseable {
    private class Pending(val op: String) {
        val future = CompletableFuture<Reply>()
    }

    private inner class Session(val child: HelperChild) {
        val pending = LinkedHashMap<Long, Pending>()
        val writeLock = Any()

        @Volatile
        var dead = false
        lateinit var reader: Thread
    }

    private val lock = Any()
    private var session: Session? = null
    private var lastStartMs: Long? = null
    private var closed = false
    private var hello: HelperClient.Hello? = null
    private val nextId = AtomicLong(1)
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()
    private val lostListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val events: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "asom-helper-events").also { it.isDaemon = true } }

    @Volatile
    var lastLossReason: String? = null
        private set

    @Volatile
    var lostCount: Int = 0
        private set

    @Volatile
    var startCount: Int = 0
        private set

    val state: HelperState
        get() = synchronized(lock) {
            when {
                closed -> HelperState.CLOSED
                session?.dead == false -> HelperState.RUNNING
                lastStartMs == null -> HelperState.NOT_STARTED
                else -> HelperState.LOST
            }
        }

    override val alive: Boolean get() = state == HelperState.RUNNING

    @Volatile
    override var epoch: Long = 0L
        private set

    /** What the last successful handshake said, or null when the helper has not been reached. */
    val helloInfo: HelperClient.Hello? get() = synchronized(lock) { hello }

    fun onLost(listener: (String) -> Unit): AutoCloseable {
        lostListeners += listener
        return AutoCloseable { lostListeners -= listener }
    }

    override fun subscribe(listener: (Event) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun call(request: Request): Reply {
        val s = ensureSession()
        return callOn(s, request)
    }

    private fun ensureSession(): Session {
        synchronized(lock) {
            if (closed) throw HelperLostException("the helper client is closed")
            session?.takeIf { !it.dead }?.let { return it }
            val now = clock.nowMs()
            val last = lastStartMs
            if (last != null && now - last < minRestartIntervalMs) {
                throw HelperLostException("the helper is lost and may be restarted at most once per ${minRestartIntervalMs / 1000} s (last start ${now - last} ms ago)")
            }
            lastStartMs = now
            startCount++
            val child = try {
                launcher.launch()
            } catch (e: Exception) {
                lastLossReason = "cannot start the helper: ${e.message ?: e::class.simpleName}"
                lostCount++
                throw HelperLostException(lastLossReason!!, e)
            }
            val s = Session(child)
            session = s
            epoch++
            s.reader = Thread({ readLoop(s) }, "asom-helper-reader").also { it.isDaemon = true; it.start() }
            try {
                val reply = callOn(s, Request("hello", null, Fields.of("v" to HValue.I(ProtocolSpec.VERSION))))
                if (reply !is Reply.Success) throw HelperProtocolException("hello was refused: ${(reply as Reply.Failure).code}")
                val f = reply.fields
                hello = HelperClient.Hello(f.text("helper")!!, f.text("macos")!!, f.text("arch")!!, f.bool("se")!!, f.text("model")!!)
            } catch (e: HelperLostException) {
                throw e
            } catch (e: Exception) {
                lose(s, "handshake failed: ${e.message ?: e::class.simpleName}")
                throw HelperLostException("handshake failed: ${e.message ?: e::class.simpleName}", e)
            }
            return s
        }
    }

    private fun callOn(s: Session, request: Request): Reply {
        val id = nextId.getAndIncrement()
        val line = HelperCodec.encode(Request(request.op, id, request.fields))
        val p = Pending(request.op)
        synchronized(s.pending) {
            if (s.dead) throw HelperLostException("the helper is gone")
            s.pending[id] = p
        }
        try {
            synchronized(s.writeLock) {
                s.child.stdin.write(line)
                s.child.stdin.write('\n'.code)
                s.child.stdin.flush()
            }
        } catch (e: IOException) {
            lose(s, "write failed: ${e.message}")
            throw HelperLostException("write failed: ${e.message}", e)
        }
        try {
            return p.future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            lose(s, "no reply to ${request.op} within $callTimeoutMs ms")
            throw HelperLostException("no reply to ${request.op} within $callTimeoutMs ms")
        } catch (e: ExecutionException) {
            when (val c = e.cause) {
                is HelperLostException -> throw c
                is HelperProtocolException -> throw c
                else -> throw HelperProtocolException("unexpected failure", c)
            }
        } finally {
            synchronized(s.pending) { s.pending.remove(id) }
        }
    }

    private fun readLoop(s: Session) {
        val input = s.child.stdout
        val buf = ByteArray(8192)
        var line = java.io.ByteArrayOutputStream()
        var discarding = false
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = buf[i]
                    if (b == '\n'.code.toByte()) {
                        if (discarding) throw HelperProtocolException("the helper wrote a line longer than ${ProtocolSpec.MAX_LINE_BYTES} bytes")
                        val bytes = line.toByteArray()
                        line = java.io.ByteArrayOutputStream()
                        handleLine(s, bytes)
                    } else if (!discarding) {
                        line.write(b.toInt())
                        if (line.size() > ProtocolSpec.MAX_LINE_BYTES) {
                            discarding = true
                            line = java.io.ByteArrayOutputStream()
                        }
                    }
                }
            }
            lose(s, "the helper closed its output (exited)")
        } catch (e: HelperProtocolException) {
            lose(s, "protocol violation: ${e.message}")
        } catch (e: IOException) {
            lose(s, "read failed: ${e.message}")
        } catch (e: Exception) {
            lose(s, "reader failed: ${e::class.simpleName}")
        }
    }

    private fun handleLine(s: Session, line: ByteArray) {
        if (HelperCodec.isEventLine(line)) {
            val event = try {
                HelperCodec.decodeEvent(line)
            } catch (e: Reject) {
                throw HelperProtocolException("event line rejected: ${e.code.wire}")
            }
            events.execute {
                for (l in listeners) {
                    try {
                        l(event)
                    } catch (_: Exception) {
                        // A listener that throws must not stop the others or the reader.
                    }
                }
            }
            return
        }
        val id = HelperCodec.replyId(line)
        val pending = synchronized(s.pending) {
            if (id != null) s.pending[id] else s.pending.values.firstOrNull { !it.future.isDone }
        } ?: throw HelperProtocolException("a reply arrived that no request is waiting for")
        try {
            pending.future.complete(HelperCodec.decodeReply(pending.op, line))
        } catch (e: Reject) {
            val err = HelperProtocolException("reply to ${pending.op} rejected: ${e.code.wire}")
            pending.future.completeExceptionally(err)
            throw err
        }
    }

    /** Marks the session dead (once), kills the child, fails every pending call, and tells the loss listeners. */
    private fun lose(s: Session, reason: String) {
        val firstTime = synchronized(s.pending) {
            if (s.dead) false else { s.dead = true; true }
        }
        if (!firstTime) return
        runCatching { s.child.destroyForcibly() }
        runCatching { s.child.stdin.close() }
        val victims = synchronized(s.pending) { s.pending.values.toList() }
        for (p in victims) p.future.completeExceptionally(HelperLostException(reason))
        synchronized(lock) {
            lastLossReason = reason
            lostCount++
        }
        for (l in lostListeners) runCatching { l(reason) }
    }

    override fun close() {
        val s = synchronized(lock) {
            if (closed) return
            closed = true
            session
        }
        if (s != null && !s.dead) {
            synchronized(s.pending) { s.dead = true }
            runCatching { s.child.stdin.close() }
            if (!s.child.waitFor(closeWaitMs)) s.child.destroyForcibly()
            val victims = synchronized(s.pending) { s.pending.values.toList() }
            for (p in victims) p.future.completeExceptionally(HelperLostException("the helper client is closed"))
        }
        events.shutdown()
    }
}
