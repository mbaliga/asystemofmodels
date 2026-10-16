package xyz.mdhv.asom.lab.proto.tls

import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/**
 * The byte pipe under the TLS engine: what a `SocketChannel` is to the transport, narrowed to the four things it needs, with a read timeout
 * that works on every platform. A test wraps one in a record tap; the transport never sees the difference.
 */
interface NetChannel : AutoCloseable {
    /** Bytes read into [dst], or -1 at end of stream. Throws [SocketTimeoutException] if [timeoutMs] (> 0) passes with nothing read; 0 waits for ever. */
    fun read(dst: ByteBuffer, timeoutMs: Long): Int

    /** Writes every remaining byte of [src]. [timeoutMs] <= 0 waits for ever. */
    fun write(src: ByteBuffer, timeoutMs: Long)

    /** Half-close: nothing more will be sent, but reads still work. */
    fun shutdownOutput()

    override fun close()
}

/**
 * A [NetChannel] over a connected `SocketChannel`. The channel is switched to non-blocking mode and driven with two selectors, one for readers
 * and one for writers, so a reader thread and a writer thread never share a selector. The channel is created by the caller (a dial, or an accept
 * done by the host); this class never binds or listens.
 */
class SocketNet(private val channel: SocketChannel) : NetChannel {
    private val readSelector: Selector = Selector.open()
    private val writeSelector: Selector = Selector.open()

    @Volatile
    private var closed = false

    init {
        channel.configureBlocking(false)
        channel.register(readSelector, SelectionKey.OP_READ)
        channel.register(writeSelector, SelectionKey.OP_WRITE)
    }

    private fun remainingMs(deadlineNanos: Long): Long = (deadlineNanos - System.nanoTime() + 999_999L) / 1_000_000L

    private fun await(selector: Selector, deadlineNanos: Long?) {
        try {
            if (deadlineNanos == null) {
                selector.select()
            } else {
                val ms = remainingMs(deadlineNanos)
                if (ms > 0) selector.select(ms)
            }
            selector.selectedKeys().clear()
        } catch (e: ClosedSelectorException) {
            throw ClosedChannelException()
        }
    }

    override fun read(dst: ByteBuffer, timeoutMs: Long): Int {
        require(dst.hasRemaining()) { "a read needs room in the buffer" }
        val deadline = if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000L else null
        while (true) {
            if (closed) throw ClosedChannelException()
            val n = channel.read(dst)
            if (n != 0) return n
            if (deadline != null && remainingMs(deadline) <= 0) throw SocketTimeoutException()
            await(readSelector, deadline)
        }
    }

    override fun write(src: ByteBuffer, timeoutMs: Long) {
        val deadline = if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000L else null
        while (src.hasRemaining()) {
            if (closed) throw ClosedChannelException()
            val n = channel.write(src)
            if (n == 0) {
                if (deadline != null && remainingMs(deadline) <= 0) throw SocketTimeoutException()
                await(writeSelector, deadline)
            }
        }
    }

    override fun shutdownOutput() {
        try {
            channel.shutdownOutput()
        } catch (e: IOException) {
            // the peer may already be gone; a half-close that fails is a close that already happened
        }
    }

    override fun close() {
        closed = true
        runCatching { readSelector.wakeup() }
        runCatching { writeSelector.wakeup() }
        runCatching { channel.close() }
        runCatching { readSelector.close() }
        runCatching { writeSelector.close() }
    }
}
