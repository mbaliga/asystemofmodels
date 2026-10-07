package xyz.mdhv.asom.lab.proto.tls

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException
import kotlin.concurrent.withLock

/** The peer closed the socket without a close_notify (or before the handshake finished). */
class PeerEof(val handshake: Boolean) : IOException("end of stream")

/**
 * A blocking driver for one `SSLEngine` over a [NetChannel]: it runs the handshake (wrap, unwrap, delegated tasks), then offers the decrypted
 * bytes as an [input] stream and takes plaintext on an [output] stream, and closes with close_notify. It does not know what the bytes mean.
 *
 * Byte counts are EXACT network counts: [networkBytesOut] is the sum of `bytesProduced` over every `wrap`, [networkBytesIn] the sum of
 * `bytesConsumed` over every `unwrap` (LAB_SPEC 7.6, "MEASURED"). They include handshake flights, alerts and close_notify records.
 *
 * One reader and one writer may work at once (the engine allows a concurrent wrap and unwrap); a post-handshake message that needs a reply
 * (a key update) is answered by whichever thread met it, under the write lock.
 */
class TlsEngineIo(private val net: NetChannel, val engine: SSLEngine) {
    private val readLock = ReentrantLock()
    private val writeLock = ReentrantLock()
    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var appReady = false
    private val empty = ByteBuffer.allocate(0)
    private val inCount = AtomicLong()
    private val readCount = AtomicLong()
    private val outCount = AtomicLong()

    @Volatile
    private var finished = false
    private val closing = AtomicBoolean(false)
    private val inboundLatch = CountDownLatch(1)

    @Volatile
    private var inboundDone = false

    @Volatile
    private var handshaking = true

    /**
     * Network bytes received. While the connection is open this is the sum of `bytesConsumed` (so a record that has arrived but not been unwrapped yet is not
     * counted, which keeps the handshake snapshot of a session free of the first application record, ERRATA ERR-PI-6). Once the connection is closed it is every
     * byte the channel delivered: the start of a record that a reset cut in half was received, and the tap at the socket counted it (ERRATA ERR-PI-1).
     */
    val networkBytesIn: Long
        get() {
            if (!finished) return inCount.get()
            if (readLock.tryLock(CLOSE_WAIT_FOR_READER_MS, TimeUnit.MILLISECONDS)) readLock.unlock()
            return readCount.get()
        }

    /** Network bytes handed to the channel and accepted by it: a record the engine produced but a reset socket refused is not counted. */
    val networkBytesOut: Long get() = outCount.get()

    // ------------------------------------------------------------------------------------------------- handshake

    /** Runs the handshake to completion or throws. [deadlineNanos] is a `System.nanoTime()` value; passing it makes every wait bounded. */
    fun handshake(deadlineNanos: Long) {
        engine.beginHandshake()
        var hs = engine.handshakeStatus
        while (hs != HandshakeStatus.NOT_HANDSHAKING && hs != HandshakeStatus.FINISHED) {
            hs = when (hs) {
                HandshakeStatus.NEED_TASK -> {
                    runTasks()
                    engine.handshakeStatus
                }
                HandshakeStatus.NEED_WRAP -> wrapAndSend(empty, remainingMs(deadlineNanos)).handshakeStatus
                else -> unwrapOnce(deadlineNanos, handshake = true)
            }
        }
        if (inboundDone) throw PeerEof(handshake = true)
        handshaking = false
    }

    private fun remainingMs(deadlineNanos: Long): Long {
        val ms = (deadlineNanos - System.nanoTime() + 999_999L) / 1_000_000L
        if (ms <= 0) throw SocketTimeoutException()
        return ms
    }

    private fun runTasks() {
        while (true) {
            (engine.delegatedTask ?: return).run()
        }
    }

    // ------------------------------------------------------------------------------------------------- wrap and unwrap

    private fun grow(b: ByteBuffer, minimum: Int): ByteBuffer {
        val bigger = ByteBuffer.allocate(maxOf(minimum, b.capacity() * 2))
        b.flip()
        bigger.put(b)
        return bigger
    }

    /**
     * Writes the flipped [netOut] to the network and counts exactly the bytes the channel took, also when the write fails half way: a record the engine
     * produced but the socket refused (a reset peer) never crossed the network, and L-L15 compares this count with a tap at the socket (ERRATA ERR-PI-1).
     */
    private fun countWritten(timeoutMs: Long) {
        val produced = netOut.remaining()
        if (produced == 0) return
        try {
            net.write(netOut, timeoutMs)
        } finally {
            outCount.addAndGet((produced - netOut.remaining()).toLong())
        }
    }

    /** Wraps [src] (possibly empty) into network bytes and writes them all. Caller holds no lock; this takes the write lock. */
    private fun wrapAndSend(src: ByteBuffer, timeoutMs: Long): SSLEngineResult {
        writeLock.lock()
        try {
            while (true) {
                netOut.clear()
                val res = engine.wrap(src, netOut)
                if (res.status == Status.BUFFER_OVERFLOW) {
                    netOut = ByteBuffer.allocate(maxOf(engine.session.packetBufferSize, netOut.capacity() * 2))
                    continue
                }
                netOut.flip()
                countWritten(timeoutMs)
                return res
            }
        } finally {
            writeLock.unlock()
        }
    }

    /** One unwrap attempt, reading from the network first if there is nothing to unwrap. Returns the handshake status after it. */
    private fun unwrapOnce(deadlineNanos: Long?, handshake: Boolean): HandshakeStatus {
        if (netIn.position() == 0) fill(deadlineNanos)
        netIn.flip()
        val res = try {
            engine.unwrap(netIn, appIn)
        } finally {
            netIn.compact()
        }
        inCount.addAndGet(res.bytesConsumed().toLong())
        when (res.status) {
            Status.OK -> if (res.bytesProduced() > 0) {
                if (handshake) throw SSLException("application data during the handshake")
                appIn.flip()
                appReady = true
            }
            Status.BUFFER_UNDERFLOW -> {
                if (!netIn.hasRemaining()) netIn = grow(netIn, engine.session.packetBufferSize)
                fill(deadlineNanos)
            }
            Status.BUFFER_OVERFLOW -> appIn = ByteBuffer.allocate(maxOf(engine.session.applicationBufferSize, appIn.capacity() * 2))
            Status.CLOSED -> markInboundDone()
        }
        when (res.handshakeStatus) {
            HandshakeStatus.NEED_TASK -> {
                runTasks()
                return engine.handshakeStatus
            }
            HandshakeStatus.NEED_WRAP -> if (!handshake) {
                wrapAndSend(empty, 0)
                return engine.handshakeStatus
            }
            else -> Unit
        }
        return res.handshakeStatus
    }

    private fun fill(deadlineNanos: Long?) {
        if (!netIn.hasRemaining()) netIn = grow(netIn, engine.session.packetBufferSize)
        val n = net.read(netIn, if (deadlineNanos == null) 0 else remainingMs(deadlineNanos))
        if (n < 0) throw PeerEof(handshaking)
        readCount.addAndGet(n.toLong())
    }

    private fun markInboundDone() {
        inboundDone = true
        inboundLatch.countDown()
        if (!engine.isOutboundDone && closing.compareAndSet(false, true)) {
            engine.closeOutbound()
            flushClosing()
        }
    }

    // ------------------------------------------------------------------------------------------------- application bytes

    /** [InputStream.read] semantics: at least one byte, or -1 after the peer's close_notify. A close without close_notify is an exception. */
    fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        readLock.lock()
        try {
            while (true) {
                if (appReady) {
                    val n = minOf(len, appIn.remaining())
                    appIn.get(b, off, n)
                    if (!appIn.hasRemaining()) {
                        appIn.clear()
                        appReady = false
                    }
                    return n
                }
                if (inboundDone) return -1
                unwrapOnce(null, handshake = false)
            }
        } finally {
            readLock.unlock()
        }
    }

    fun write(b: ByteArray, off: Int, len: Int) {
        val src = ByteBuffer.wrap(b, off, len)
        var idle = 0
        while (src.hasRemaining()) {
            val before = src.remaining()
            val res = wrapAndSend(src, 0)
            if (res.status == Status.CLOSED) throw java.nio.channels.ClosedChannelException()
            if (res.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
            idle = if (src.remaining() == before && res.bytesProduced() == 0) idle + 1 else 0
            if (idle > 8) throw IOException("the engine made no progress")
        }
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val n = this@TlsEngineIo.read(one, 0, 1)
                if (n < 0) return -1
                if (n == 1) return one[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Objects.checkFromIndexSize(off, len, b.size)
            return this@TlsEngineIo.read(b, off, len)
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = this@TlsEngineIo.write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            java.util.Objects.checkFromIndexSize(off, len, b.size)
            this@TlsEngineIo.write(b, off, len)
        }
    }

    // ------------------------------------------------------------------------------------------------- closing

    /** Writes whatever the engine has left to say (close_notify, or the alert after a fatal error), bounded and without throwing. */
    private fun flushClosing() {
        writeLock.withLock {
            repeat(8) {
                netOut.clear()
                val res = try {
                    engine.wrap(empty, netOut)
                } catch (e: SSLException) {
                    return
                }
                if (res.bytesProduced() == 0) return
                netOut.flip()
                try {
                    countWritten(MeshTlsProfile.CLOSE_WAIT_MS)
                } catch (e: IOException) {
                    return
                }
                if (res.status == Status.CLOSED) return
            }
        }
    }

    /** close_notify, then wait (bounded) for the peer's, then close the socket. Idempotent. */
    fun close() {
        try {
            if (closing.compareAndSet(false, true)) {
                engine.closeOutbound()
                flushClosing()
            }
            awaitInbound(MeshTlsProfile.CLOSE_WAIT_MS)
        } finally {
            net.close()
            finished = true
        }
    }

    private fun awaitInbound(waitMs: Long) {
        if (inboundDone) return
        if (readLock.tryLock()) {
            try {
                val deadline = System.nanoTime() + waitMs * 1_000_000L
                while (!inboundDone) {
                    try {
                        unwrapOnce(deadline, handshake = false)
                    } catch (e: IOException) {
                        return
                    }
                    if (appReady) {
                        appIn.clear()
                        appReady = false
                    }
                }
            } finally {
                readLock.unlock()
            }
        } else {
            inboundLatch.await(waitMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * The close after a failed handshake: flush the alert the engine owes the peer, half-close, give the peer a moment to read it, then close.
     * Closing a socket that still has unread input resets the connection and can destroy the alert before the peer reads it, so the input is drained.
     */
    fun failClose() {
        try {
            closing.set(true)
            runCatching { engine.closeOutbound() }
            flushClosing()
            net.shutdownOutput()
            val scratch = ByteBuffer.allocate(4096)
            val deadline = System.nanoTime() + 200L * 1_000_000L
            var total = 0
            while (total < 65_536) {
                scratch.clear()
                val ms = (deadline - System.nanoTime()) / 1_000_000L
                if (ms <= 0) break
                val n = try {
                    net.read(scratch, ms)
                } catch (e: IOException) {
                    break
                }
                if (n < 0) break
                total += n
            }
        } finally {
            net.close()
        }
    }

    private companion object {
        /** After a close, how long a counter read waits for a reader thread that is still inside the engine, so that its last read is in the figure. */
        const val CLOSE_WAIT_FOR_READER_MS = 500L
    }
}
