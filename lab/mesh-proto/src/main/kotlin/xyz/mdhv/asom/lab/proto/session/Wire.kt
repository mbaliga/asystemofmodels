package xyz.mdhv.asom.lab.proto.session

import java.io.InputStream
import java.io.OutputStream
import xyz.mdhv.asom.lab.ledger.Overhead
import xyz.mdhv.asom.lab.proto.tls.TlsMeshConnection
import xyz.mdhv.asom.lab.proto.transport.MeshConnection
import xyz.mdhv.asom.lab.proto.wire.FrameDecoder
import xyz.mdhv.asom.lab.proto.wire.FrameEncoder
import xyz.mdhv.asom.lab.proto.wire.RawFrame
import xyz.mdhv.asom.lab.proto.wire.WireLimits

/** Application bytes of one frame: the 9-byte header plus the payload (LAB_SPEC 7.1). Every row counts through this function. */
internal fun appBytes(f: RawFrame): Long = WireLimits.HEADER_BYTES.toLong() + f.payload.size

/**
 * A connection that knows the record arithmetic of the stack under it (R4-L-25, ERR-R4B-LT-3). A wrapper that is not itself a [TlsMeshConnection] but runs over
 * one reports it here; a transport that reports nothing is an unknown stack.
 */
internal interface ReportsOverheadStack {
    val overheadStack: Overhead.Stack
}

/** The stack of the ESTIMATED form for [conn]: what it reports, JSSE for the SSLEngine transport, else the RFC 8446 floor ([Overhead.UNCALIBRATED]). */
internal fun overheadStackOf(conn: MeshConnection): Overhead.Stack = when (conn) {
    is ReportsOverheadStack -> conn.overheadStack
    is TlsMeshConnection -> Overhead.JSSE_17_21
    else -> Overhead.UNCALIBRATED
}

/** Counts the plaintext bytes that cross the stream boundary. It sits between the session and the transport and reads nothing the row writer produces. */
internal class CountingIn(private val inner: InputStream) : InputStream() {
    var count: Long = 0
        private set

    override fun read(): Int {
        val b = inner.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = inner.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun available(): Int = inner.available()
}

internal class CountingOut(private val inner: OutputStream) : OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        inner.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        inner.write(b, off, len)
        count += len
    }

    override fun flush() = inner.flush()
}

/**
 * One connection as the session sees it: the decoder for what arrives, the only place a frame is handed to the transport, the plaintext counters, and the
 * network figures for the overhead of a row. [handshakeNetwork] is read when the session is built, which is right after the TLS handshake and before any
 * application byte (ERRATA ERR-PS-8).
 */
internal class Wire(val conn: MeshConnection) {
    val input = CountingIn(conn.input)
    val output = CountingOut(conn.output)
    val decoder = FrameDecoder(conn.role, conn.mode)
    val stack: Overhead.Stack = overheadStackOf(conn)
    private val firstCounters = conn.counters()
    val measured: Boolean = firstCounters.measured
    val handshakeNetwork: Long = firstCounters.networkBytesIn + firstCounters.networkBytesOut

    var dead: Boolean = false
        private set

    var estimatedRecordBytes: Long = 0
        private set

    fun networkNow(): Long = conn.counters().let { it.networkBytesIn + it.networkBytesOut }

    /** The first byte of the frame leaves here, and nowhere else. A dead wire refuses: after a control-row failure nothing more is sent (FC-2). */
    fun write(frame: RawFrame) {
        check(!dead) { "the wire is closed: nothing further is sent on this session" }
        val bytes = FrameEncoder.encode(frame)
        output.write(bytes, 0, bytes.size)
        output.flush()
        estimatedRecordBytes += Overhead.estimatedRecords(listOf(appBytes(frame)), stack)
    }

    fun noteReceived(frameAppBytes: Long) {
        estimatedRecordBytes += Overhead.estimatedRecords(listOf(frameAppBytes), stack)
    }

    fun kill() {
        dead = true
        conn.close()
    }
}
