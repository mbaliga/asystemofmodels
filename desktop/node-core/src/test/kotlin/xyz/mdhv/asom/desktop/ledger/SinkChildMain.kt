package xyz.mdhv.asom.desktop.ledger

import java.nio.file.Path
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord

/** The row the harness expects at index [i]; the parent process builds the same one to compare bytes. */
fun harnessRow(i: Int): RouteRecord =
    RouteRecord(ts = i.toLong(), callerPkg = "harness", requestedModel = "m-$i", egress = Egress.LOCAL, latencyMs = i.toLong(), status = 200)

/**
 * Models a channel whose unforced bytes die with the process: writes wait in memory and reach the file only at `force()`.
 * A process death claim is otherwise unfalsifiable for a missing fsync, because the OS page cache survives SIGKILL; this
 * model is what makes "the row was acknowledged before it was forced" observable. It is a MODEL of the durability
 * contract, not of any real disk.
 */
class VolatileChannel(private val real: DurableChannel) : DurableChannel {
    private var pending = ByteArray(0)
    override fun size(): Long = real.size() + pending.size
    override fun readTail(maxBytes: Int): ByteArray = real.readTail(maxBytes)
    override fun write(bytes: ByteArray) { pending += bytes }
    override fun force() {
        if (pending.isNotEmpty()) real.write(pending)
        pending = ByteArray(0)
        real.force()
    }
    override fun truncate(size: Long) {
        pending = ByteArray(0)
        real.truncate(size)
    }
    override fun close() = real.close()
}

/** Writes each row in two halves so a kill can land in the middle of a write. */
private class SplitChannel(private val inner: DurableChannel, private val onMid: (Int) -> Unit) : DurableChannel {
    private var writes = 0
    override fun size(): Long = inner.size()
    override fun readTail(maxBytes: Int): ByteArray = inner.readTail(maxBytes)
    override fun write(bytes: ByteArray) {
        writes++
        val half = bytes.size / 2
        inner.write(bytes.copyOfRange(0, half))
        onMid(writes)
        inner.write(bytes.copyOfRange(half, bytes.size))
    }
    override fun force() = inner.force()
    override fun truncate(size: Long) = inner.truncate(size)
    override fun close() = inner.close()
}

/**
 * Forked by `LedgerSigkillHarnessTest`. Appends rows 1..total through the real sink; at (point, row) it prints
 * `AT <point> <row>` and parks so the parent can SIGKILL it there. After each append returns it prints `ACK <i>`.
 * args: file mode(real|volatile) point row total
 */
object SinkChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = Path.of(args[0])
        val volatileMode = args[1] == "volatile"
        val point = args[2]
        val killRow = args[3].toInt()
        val total = args[4].toInt()

        fun park(p: String, row: Int) {
            println("AT $p $row")
            System.out.flush()
            Thread.sleep(Long.MAX_VALUE)
        }

        val base: DurableChannel = FileDurableChannel.open(file).let { if (volatileMode) VolatileChannel(it) else it }
        val channel = SplitChannel(base) { row -> if (point == "MID_WRITE" && row == killRow) park("MID_WRITE", row) }
        val sink = JsonlLedgerSink(channel) { p, row ->
            if (p.name == point && row == killRow && p != DurabilityPoint.MID_WRITE && p != DurabilityPoint.AFTER_ACK) park(p.name, row)
        }
        for (i in 1..total) {
            sink.appendLine(JsonlLedgerSink.JSON.encodeToString(RouteRecord.serializer(), harnessRow(i)))
            println("ACK $i")
            System.out.flush()
            if (point == "AFTER_ACK" && i == killRow) park("AFTER_ACK", i)
        }
        println("DONE")
    }
}
