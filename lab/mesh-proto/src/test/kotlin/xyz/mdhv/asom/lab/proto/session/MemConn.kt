package xyz.mdhv.asom.lab.proto.session

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerWriteException
import xyz.mdhv.asom.lab.ledger.RowSink
import xyz.mdhv.asom.lab.proto.transport.MeshConnection
import xyz.mdhv.asom.lab.proto.transport.TransportCounters
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.PeerRole

/** One event of a run, in one global order. The oracles read this log; nothing in `main` writes to it except through the test doubles below. */
sealed class Ev {
    var seq: Int = 0

    class Write(val conn: String, val bytes: ByteArray, val raw: Boolean) : Ev()

    class Read(val conn: String, val bytes: ByteArray) : Ev()

    class Closed(val conn: String) : Ev()

    class Appended(val node: String, val row: LabRouteRecord) : Ev()

    class AppendFailed(val node: String, val row: LabRouteRecord) : Ev()

    class Dispatch(val node: String, val session: Session, val type: Int, val stream: Long, val payloadBytes: Int) : Ev()

    class EngineOpen(val node: String, val attemptId: String) : Ev()

    class EngineCancel(val node: String, val attemptId: String) : Ev()

    class Connect(val node: String, val destAddr: String) : Ev()
}

class Log {
    val events = ArrayList<Ev>()

    @Synchronized
    fun <E : Ev> add(e: E): E {
        e.seq = events.size
        events += e
        return e
    }

    @Synchronized
    fun snapshot(): List<Ev> = events.toList()
}

inline fun <reified E : Ev> Log.all(): List<E> = snapshot().filterIsInstance<E>()

/**
 * A byte queue with an end-of-stream flag. Every `push` is one TLS write: it carries the record overhead of RFC 8446 (22 bytes per started 16,384 plaintext
 * bytes), which the reader's network counter takes up when it consumes the first byte of that write. [blocking] makes `read` wait, for the one test that
 * drives `runReadLoop`.
 */
class ByteChannel(private val blocking: Boolean = false) {
    private class Seg(val data: ByteArray, var at: Int, val overhead: Long, var overheadTaken: Boolean = false)

    private val segs = java.util.ArrayDeque<Seg>()
    private val monitor = Object()
    var closed = false
        private set

    fun push(b: ByteArray) {
        synchronized(monitor) {
            segs.addLast(Seg(b, 0, 22L * ((b.size + 16_383L) / 16_384L)))
            monitor.notifyAll()
        }
    }

    fun close() {
        synchronized(monitor) {
            closed = true
            monitor.notifyAll()
        }
    }

    fun available(): Int = synchronized(monitor) { segs.sumOf { it.data.size - it.at } }

    /** Returns the plaintext count and, through [overheadOut], the record overhead that became visible with it. */
    fun read(dst: ByteArray, off: Int, len: Int, overheadOut: LongArray): Int = synchronized(monitor) {
        while (segs.isEmpty()) {
            if (closed) return -1
            if (!blocking) throw IllegalStateException("a non-blocking in-memory read with nothing to read")
            monitor.wait(2000)
        }
        var n = 0
        var oh = 0L
        while (n < len && segs.isNotEmpty()) {
            val g = segs.peekFirst()
            if (!g.overheadTaken) {
                g.overheadTaken = true
                oh += g.overhead
            }
            val take = minOf(len - n, g.data.size - g.at)
            g.data.copyInto(dst, off + n, g.at, g.at + take)
            g.at += take
            n += take
            if (g.at == g.data.size) segs.pollFirst()
        }
        overheadOut[0] = oh
        n
    }
}

/**
 * An in-memory [MeshConnection]. [measured] makes it report TLS-record arithmetic as MEASURED network counters: every write is `plaintext + 22 x ceilDiv(len, 16384)`
 * network bytes, and a fixed handshake figure is already on the counters when the connection is handed over. The test log records every write and read.
 */
class MemConnection(
    override val role: PeerRole,
    override val mode: ConnMode,
    override val peerPin: Pin?,
    private val rx: ByteChannel,
    private val tx: ByteChannel,
    val name: String,
    private val log: Log?,
    private val measured: Boolean = true,
    val handshakeIn: Long = 1_200,
    val handshakeOut: Long = 1_500,
) : MeshConnection {
    var closedByUs = false
        private set

    /** A lying transport meter, for negative controls: every write is reported this many bytes short. */
    var meterBiasPerWrite = 0L
    private var writes = 0L
    private var netIn = if (measured) handshakeIn else 0L
    private var netOut = if (measured) handshakeOut else 0L

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val oh = LongArray(1)
            val n = rx.read(b, off, len, oh)
            if (n > 0) {
                netIn += n + oh[0]
                log?.add(Ev.Read(name, b.copyOfRange(off, off + n)))
            }
            return n
        }

        override fun available(): Int = rx.available()
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closedByUs) throw IOException("closed")
            val bytes = b.copyOfRange(off, off + len)
            log?.add(Ev.Write(name, bytes, raw = false))
            netOut += len + 22L * ((len + 16_383L) / 16_384L)
            writes++
            tx.push(bytes)
        }
    }

    /** A write that does not go through a session (a hostile peer's bytes): logged as raw. */
    fun writeRaw(bytes: ByteArray) {
        log?.add(Ev.Write(name, bytes, raw = true))
        netOut += bytes.size + 22L * ((bytes.size + 16_383L) / 16_384L)
        tx.push(bytes)
    }

    override fun counters(): TransportCounters = TransportCounters(netIn, netOut - writes * meterBiasPerWrite, measured)

    override fun close() {
        if (closedByUs) return
        closedByUs = true
        log?.add(Ev.Closed(name))
        tx.close()
    }

    fun networkTotal(): Long = netIn + netOut

    fun drained(): Boolean = rx.available() == 0

    fun peerClosed(): Boolean = rx.closed

    /** Reads whatever has arrived without a session (a hostile peer's view). */
    fun takeAll(): ByteArray {
        val n = rx.available()
        if (n == 0) return ByteArray(0)
        val b = ByteArray(n)
        input.read(b, 0, n)
        return b
    }

    companion object {
        /** A cross-wired pair: the first plays the TLS client, the second the TLS server. */
        fun pair(
            clientPeer: Pin?, serverPeer: Pin?, log: Log?, nameC: String, nameS: String, mode: ConnMode = ConnMode.ESTABLISHED, measured: Boolean = true, blocking: Boolean = false,
        ): Pair<MemConnection, MemConnection> {
            val c2s = ByteChannel(blocking)
            val s2c = ByteChannel(blocking)
            val client = MemConnection(PeerRole.TLS_CLIENT, mode, clientPeer, s2c, c2s, nameC, log, measured)
            val server = MemConnection(PeerRole.TLS_SERVER, mode, serverPeer, c2s, s2c, nameS, log, measured)
            return client to server
        }
    }
}

/** Records every append (and every failure) in the run log, in the order the node made them. */
class SpySink(private val node: String, private val inner: RowSink, private val log: Log) : RowSink {
    override fun append(row: LabRouteRecord) {
        try {
            inner.append(row)
        } catch (e: LedgerWriteException) {
            log.add(Ev.AppendFailed(node, row))
            throw e
        }
        log.add(Ev.Appended(node, row))
    }
}
