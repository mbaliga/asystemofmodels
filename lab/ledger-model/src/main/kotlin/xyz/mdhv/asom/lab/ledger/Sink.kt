package xyz.mdhv.asom.lab.ledger

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/** An append that did not become durable. The caller sends nothing further that depends on the row (LAB_SPEC 7.7). */
class LedgerWriteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The only operation a sink offers is `append`: rows are never updated or deleted (law L-L6, principle P4). `append` returns only
 * after the row is durable and throws [LedgerWriteException] when it is not.
 */
interface RowSink {
    @Throws(LedgerWriteException::class)
    fun append(row: LabRouteRecord)
}

class MemorySink : RowSink {
    private val rows = ArrayList<LabRouteRecord>()

    @Synchronized
    override fun append(row: LabRouteRecord) {
        rows += row.requireWellFormed()
    }

    @Synchronized
    fun all(): List<LabRouteRecord> = rows.toList()
}

/**
 * The desktop and lab ledger (LAB_SPEC 7.5): one JCS row per line, UTF-8, `\n`; `FileChannel.write` then `force(false)` per append, and
 * the append returns only after `force`. It throws on failure. Only process death is claimed, never power loss.
 */
class JsonlSink(val path: Path, private val force: (FileChannel) -> Unit = { it.force(false) }) : RowSink, Closeable {
    private val channel: FileChannel = open(path)

    @Synchronized
    override fun append(row: LabRouteRecord) {
        row.requireWellFormed()
        val line = row.toRowBytes() + '\n'.code.toByte()
        try {
            val buf = ByteBuffer.wrap(line)
            while (buf.hasRemaining()) channel.write(buf)
            force(channel)
        } catch (e: IOException) {
            throw LedgerWriteException("append failed: ${e.message}", e)
        }
    }

    override fun close() = channel.close()

    private companion object {
        /** The desktop ledger file is mode 0600 (contract.md 4.10) where the file system has POSIX permissions; an existing file keeps its mode. */
        fun open(path: Path): FileChannel {
            if (!Files.exists(path)) {
                try {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                } catch (_: UnsupportedOperationException) {
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                }
            }
            return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        }
    }
}

class CorruptRowException(val lineNo: Int, message: String) : Exception(message)

class JsonlRead(val rows: List<LabRouteRecord>, val tornTailBytes: Int)

object JsonlReader {
    /**
     * Every complete line must be a valid row. Bytes after the last `\n` are a torn tail (a write cut by process death): they are
     * reported, never parsed and never counted as a row.
     */
    fun read(path: Path): JsonlRead {
        val bytes = if (Files.exists(path)) Files.readAllBytes(path) else ByteArray(0)
        val rows = ArrayList<LabRouteRecord>()
        var start = 0
        var lineNo = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                lineNo++
                val line = bytes.copyOfRange(start, i)
                val parsed = StrictJson.parse(line)
                if (parsed !is ParseResult.Ok || parsed.value !is JObject) throw CorruptRowException(lineNo, "line $lineNo is not a JSON object: $parsed")
                rows += try {
                    LabRouteRecord.fromRow(parsed.value as JObject)
                } catch (e: IllegalArgumentException) {
                    throw CorruptRowException(lineNo, "line $lineNo is not a row: ${e.message}")
                }
                start = i + 1
            }
        }
        return JsonlRead(rows, bytes.size - start)
    }
}

/** Where in an append the injected failure strikes. */
enum class FailMode {
    /** The append throws before anything is written: the row is absent. */
    BEFORE_WRITE,

    /** The row is written and then the append throws (a crash between write and force): the row may be present, the caller must act as if it is not durable. */
    AFTER_WRITE,
}

/**
 * Wraps a sink and throws at the append indexes in [failAt] (0-based, counted over every append attempt). With [sticky] every append from
 * the first injected failure on throws too (a full disk); otherwise only the listed appends fail and the next one succeeds.
 */
class CrashInjectingSink(
    private val inner: RowSink,
    private val failAt: Set<Int>,
    private val mode: FailMode = FailMode.BEFORE_WRITE,
    private val sticky: Boolean = false,
) : RowSink {
    var attempts: Int = 0
        private set
    var failures: Int = 0
        private set

    @Synchronized
    override fun append(row: LabRouteRecord) {
        val index = attempts++
        val failing = index in failAt || (sticky && failures > 0)
        if (failing) {
            failures++
            if (mode == FailMode.AFTER_WRITE && index in failAt) inner.append(row)
            throw LedgerWriteException("injected failure at append #$index")
        }
        inner.append(row)
    }
}
