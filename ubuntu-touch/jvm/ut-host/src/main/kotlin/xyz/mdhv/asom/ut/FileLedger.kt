package xyz.mdhv.asom.ut

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.ledger.CorruptRowException
import xyz.mdhv.asom.lab.ledger.JsonlSink
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerWriteException

/**
 * The node's own ledger file (ubuntu-touch.md 3.4; ERRATA ERR-FX-UT-2). The file is the lab's JSONL (one JCS row and `\n` per line);
 * what this class adds to the lab [JsonlSink] is the recovery the desktop sink has:
 *
 *  * opening removes a torn tail (bytes after the last `\n`: a write cut by process death was never acknowledged), so the next row
 *    is never glued onto it, and checks the last complete row, so a damaged file fails STARTING closed (FC-1);
 *  * a failed append rolls the file back to the last acknowledged length; a rollback that fails poisons the ledger: every later
 *    append throws until the process reopens the file;
 *  * a query streams the file, stops at `limit` rows and returns at most [LedgerFile.ROWS_BUDGET] bytes of rows, so neither a long
 *    ledger nor a fat row can exhaust the 128 MiB heap or exceed the 1 MiB frame cap.
 *
 * Open and query run under the session lock, so they must stay short: open reads the tail only, and a query stops as soon as it has
 * its rows.
 */
class FileLedger private constructor(
    private val path: Path,
    private val sink: JsonlSink,
    private val truncate: (Path, Long) -> Unit,
) : LedgerPort {
    private val poisoned = AtomicReference<Throwable?>(null)

    override fun rows(since: Long, limit: Int): List<JObject> = LedgerFile.scan(path, since, limit)

    /**
     * Appends one row and returns only when it is durable. On failure the file is back at its last acknowledged length, or, when
     * that cannot be done, the ledger refuses every further append.
     */
    @Synchronized
    fun append(row: LabRouteRecord) {
        poisoned.get()?.let { throw LedgerWriteException("ledger unavailable after an earlier unrecoverable failure", it) }
        val committed = try {
            Files.size(path)
        } catch (e: IOException) {
            poisoned.set(e)
            throw LedgerWriteException("ledger size unreadable: ${e.message}", e)
        }
        try {
            sink.append(row)
        } catch (e: LedgerWriteException) {
            try {
                truncate(path, committed)
            } catch (t: IOException) {
                t.addSuppressed(e)
                poisoned.set(t)
            }
            throw e
        }
    }

    override fun close() = sink.close()

    companion object {
        /** Null when the file cannot be made safe to append to: the caller fails STARTING closed. */
        fun open(
            file: Path,
            force: (FileChannel) -> Unit = { it.force(false) },
            truncate: (Path, Long) -> Unit = LedgerFile::truncate,
        ): FileLedger? = try {
            Files.createDirectories(file.parent)
            if (Files.exists(file)) {
                LedgerFile.recoverTail(file)
                LedgerFile.checkLastRow(file)
            }
            FileLedger(file, JsonlSink(file, force), truncate)
        } catch (e: IOException) {
            null
        } catch (e: CorruptRowException) {
            null
        }
    }
}

internal object LedgerFile {
    const val MAX_ROW_BYTES = CtlProtocol.MAX_LINE_BYTES

    /** Encoded rows per answer: well under the frame cap, which also has to hold the envelope and the escaping of a `rows` frame. */
    const val ROWS_BUDGET = 768 * 1024

    private const val WINDOW = 8192
    private const val NL = '\n'.code.toByte()

    /** Removes the bytes after the last `\n`. Returns how many. */
    fun recoverTail(path: Path): Long = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
        val size = ch.size()
        val cut = endOfLastLine(ch)
        if (cut < size) {
            ch.truncate(cut)
            ch.force(false)
        }
        size - cut
    }

    fun truncate(path: Path, size: Long) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.truncate(size) }
    }

    private fun readFully(ch: FileChannel, buf: ByteBuffer, at: Long) {
        val start = buf.position()
        while (buf.hasRemaining()) {
            val r = ch.read(buf, at + (buf.position() - start))
            if (r < 0) throw IOException("file shrank while it was read")
        }
    }

    private fun endOfLastLine(ch: FileChannel): Long {
        val buf = ByteBuffer.allocate(WINDOW)
        var pos = ch.size()
        while (pos > 0) {
            val n = minOf(pos, WINDOW.toLong()).toInt()
            pos -= n
            buf.clear()
            buf.limit(n)
            readFully(ch, buf, pos)
            for (i in n - 1 downTo 0) if (buf.get(i) == NL) return pos + i + 1
        }
        return 0
    }

    /** The file now ends with `\n` or is empty. The last row must be a row. Reads at most one row. */
    fun checkLastRow(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            val size = ch.size()
            if (size == 0L) return
            val take = minOf(size, MAX_ROW_BYTES + 2L).toInt()
            val buf = ByteBuffer.allocate(take)
            readFully(ch, buf, size - take)
            val bytes = buf.array()
            var start = -1
            for (i in take - 2 downTo 0) if (bytes[i] == NL) {
                start = i
                break
            }
            if (start < 0 && take.toLong() < size) throw CorruptRowException(0, "the last line is longer than $MAX_ROW_BYTES bytes")
            parse(0, bytes.copyOfRange(start + 1, take - 1))
        }
    }

    private fun parse(lineNo: Int, line: ByteArray): LabRouteRecord {
        val parsed = StrictJson.parse(line)
        if (parsed !is ParseResult.Ok || parsed.value !is JObject) throw CorruptRowException(lineNo, "line $lineNo is not a JSON object")
        return try {
            LabRouteRecord.fromRow(parsed.value as JObject)
        } catch (e: IllegalArgumentException) {
            throw CorruptRowException(lineNo, "line $lineNo is not a row: ${e.message}")
        }
    }

    /**
     * The first [limit] rows with `ts >= since`, oldest first, as many as fit in [ROWS_BUDGET]. A complete line that is not a row
     * throws [CorruptRowException]; bytes after the last `\n` (an append in progress) are not a row and are ignored. A single row
     * over the budget cannot be answered at all and throws [FrameTooLargeException].
     */
    fun scan(path: Path, since: Long, limit: Int): List<JObject> {
        if (!Files.exists(path)) return emptyList()
        val out = ArrayList<JObject>()
        var budget = 0
        val line = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var lineNo = 0
        Files.newInputStream(path).use { input ->
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                var from = 0
                for (i in 0 until n) {
                    if (chunk[i] != NL) continue
                    if (line.size() + (i - from) > MAX_ROW_BYTES) throw CorruptRowException(lineNo + 1, "line ${lineNo + 1} is longer than $MAX_ROW_BYTES bytes")
                    line.write(chunk, from, i - from)
                    from = i + 1
                    lineNo++
                    val record = parse(lineNo, line.toByteArray())
                    line.reset()
                    if (record.ts < since) continue
                    val size = record.toRowBytes().size
                    if (budget + size > ROWS_BUDGET) {
                        if (out.isEmpty()) throw FrameTooLargeException()
                        return out
                    }
                    budget += size
                    out += record.toRow()
                    if (out.size >= limit) return out
                }
                if (line.size() + (n - from) > MAX_ROW_BYTES) throw CorruptRowException(lineNo + 1, "line ${lineNo + 1} is longer than $MAX_ROW_BYTES bytes")
                line.write(chunk, from, n - from)
            }
        }
        return out
    }
}
