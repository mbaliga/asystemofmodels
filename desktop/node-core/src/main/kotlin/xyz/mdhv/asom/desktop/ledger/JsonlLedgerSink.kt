package xyz.mdhv.asom.desktop.ledger

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.server.ledger.LedgerSink

/** The ledger could not make a row durable. Fail-closed: the caller must not proceed with the egress the row describes. */
class LedgerUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Where a crash can fall inside one append; the SIGKILL harness kills the process at each. */
enum class DurabilityPoint { BEFORE_WRITE, MID_WRITE, AFTER_WRITE_BEFORE_FORCE, AFTER_FORCE_BEFORE_ACK, AFTER_ACK }

fun interface DurabilityHook {
    /** [rowIndex] counts rows appended by this sink instance, starting at 1. */
    fun at(point: DurabilityPoint, rowIndex: Int)

    companion object {
        val NONE = DurabilityHook { _, _ -> }
    }
}

/** The one file operation set the sink needs, so tests can record order and inject failures. */
interface DurableChannel : AutoCloseable {
    fun size(): Long
    fun readTail(maxBytes: Int): ByteArray

    /**
     * [length] bytes starting at [position], with bounded memory. The default goes through [readTail] and is for in-memory
     * doubles only; a real file overrides it with a positional read.
     */
    fun readRange(position: Long, length: Int): ByteArray = readTail((size() - position).toInt()).copyOf(length)
    fun write(bytes: ByteArray)
    fun force()
    fun truncate(size: Long)
}

class FileDurableChannel private constructor(private val ch: FileChannel) : DurableChannel {
    override fun size(): Long = ch.size()

    override fun readTail(maxBytes: Int): ByteArray {
        val size = ch.size()
        val n = minOf(size, maxBytes.toLong()).toInt()
        val buf = ByteBuffer.allocate(n)
        var pos = size - n
        while (buf.hasRemaining()) {
            val r = ch.read(buf, pos)
            if (r < 0) break
            pos += r
        }
        return buf.array()
    }

    override fun readRange(position: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = position
        while (buf.hasRemaining()) {
            val r = ch.read(buf, pos)
            if (r < 0) break
            pos += r
        }
        return buf.array().copyOf(buf.position())
    }

    override fun write(bytes: ByteArray) {
        // Not APPEND (the JDK forbids READ together with APPEND): the sink is the only writer, so it writes at the end.
        ch.position(ch.size())
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) ch.write(buf)
    }

    override fun force() = ch.force(false)

    override fun truncate(size: Long) {
        ch.truncate(size)
    }

    override fun close() = ch.close()

    companion object {
        private val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

        /** Creates the directory 0700 and the file 0600 where the filesystem has POSIX permissions. */
        fun open(path: Path): FileDurableChannel {
            val dir = path.toAbsolutePath().parent
            if (dir != null && !Files.exists(dir)) {
                if (posix) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                } else {
                    Files.createDirectories(dir)
                }
            }
            if (!Files.exists(path)) {
                try {
                    if (posix) {
                        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    } else {
                        Files.createFile(path)
                    }
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                }
            }
            return FileDurableChannel(FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.READ))
        }
    }
}

/**
 * The desktop ledger (design 8.4): one JSON row per line, UTF-8, `\n`; `write` then `force(false)` per append; the
 * append returns only after `force` returned; any failure THROWS (fail-closed) and rolls the file back to the last
 * acknowledged length, so a torn line is never left in front of the next row. A rollback that itself fails poisons
 * the sink: every later append throws until the process reopens the file.
 *
 * Claims: process death only (a killed process loses no acknowledged row, and no acknowledged row is torn). Nothing
 * is claimed about power loss beyond what `force` gives on the underlying filesystem.
 * Rows never reach stdout or stderr (T17(c)).
 */
class JsonlLedgerSink internal constructor(
    private val channel: DurableChannel,
    private val hook: DurabilityHook = DurabilityHook.NONE,
) : LedgerSink, AutoCloseable {

    /** Bytes of a torn trailing line (a row whose append never returned) removed when the file was opened. */
    var recoveredTornBytes: Long = 0
        private set

    private var committedSize: Long
    private var rowsThisInstance = 0
    private var poisoned: Throwable? = null

    init {
        committedSize = channel.size()
        if (committedSize > 0) {
            val end = lastNewlineEnd()
            if (end < committedSize) {
                recoveredTornBytes = committedSize - end
                channel.truncate(end)
                channel.force()
                committedSize = end
            }
        }
    }

    /**
     * The offset just after the last newline, found by scanning backwards in fixed-size chunks all the way to offset 0, so a
     * torn tail of any length (a crash can leave a file extended with zeros) is found and removed with bounded memory
     * (finding HLU-5: a capped window looped for ever on a tail longer than the cap with no newline).
     */
    private fun lastNewlineEnd(): Long {
        var end = channel.size()
        while (end > 0) {
            val start = maxOf(0L, end - SCAN_CHUNK)
            val bytes = channel.readRange(start, (end - start).toInt())
            val i = bytes.lastIndexOf('\n'.code.toByte())
            if (i >= 0) return start + i + 1
            end = start
        }
        return 0
    }

    override suspend fun append(record: RouteRecord) {
        val line = JSON.encodeToString(RouteRecord.serializer(), record)
        withContext(Dispatchers.IO) { appendLine(line) }
    }

    /** Appends one row. [line] must not contain a line break. Returns only after the bytes were forced to the file. */
    @Synchronized
    fun appendLine(line: String) {
        require('\n' !in line && '\r' !in line) { "a ledger row is one line" }
        poisoned?.let { throw LedgerUnavailableException("ledger unavailable after an earlier unrecoverable failure", it) }
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val index = rowsThisInstance + 1
        try {
            hook.at(DurabilityPoint.BEFORE_WRITE, index)
            channel.write(bytes)
            hook.at(DurabilityPoint.AFTER_WRITE_BEFORE_FORCE, index)
            channel.force()
            hook.at(DurabilityPoint.AFTER_FORCE_BEFORE_ACK, index)
        } catch (t: Throwable) {
            rollback(t)
            throw LedgerUnavailableException("ledger append failed; nothing was acknowledged", t)
        }
        committedSize += bytes.size
        rowsThisInstance = index
        hook.at(DurabilityPoint.AFTER_ACK, index)
    }

    private fun rollback(original: Throwable) {
        try {
            // No force here: an unacknowledged row may survive a crash, and a torn one is removed at the next open.
            channel.truncate(committedSize)
        } catch (t: Throwable) {
            t.addSuppressed(original)
            poisoned = t
        }
    }

    override fun close() = channel.close()

    companion object {
        private const val SCAN_CHUNK = 64L * 1024

        /** Explicit defaults so every row carries every field, in a fixed order. */
        val JSON = Json { encodeDefaults = true; explicitNulls = true }

        fun open(path: Path, hook: DurabilityHook = DurabilityHook.NONE): JsonlLedgerSink =
            JsonlLedgerSink(FileDurableChannel.open(path), hook)
    }
}
