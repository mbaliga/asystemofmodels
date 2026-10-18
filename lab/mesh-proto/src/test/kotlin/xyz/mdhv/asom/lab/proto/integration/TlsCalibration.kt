package xyz.mdhv.asom.lab.proto.integration

import xyz.mdhv.asom.lab.proto.tls.End
import xyz.mdhv.asom.lab.proto.tls.HonestNode
import xyz.mdhv.asom.lab.proto.tls.Loop
import xyz.mdhv.asom.lab.proto.tls.both

/**
 * What the JSSE of THIS JVM puts on the wire, measured under a record tap on an idle established connection, never assumed (ERRATA ERR-PI-2). The ESTIMATED form of
 * LAB_SPEC 7.6 assumes 22 bytes per record (5 header, 1 inner type, 16 tag, [F51]); JDK 17.0.12 and JDK 21.0.10 measure more, and the laws that need a record size
 * take it from here.
 */
object TlsCalibration {
    class Result(val cipherSuite: String, val recordOverhead: Long, val alertRecord: Long, val maxRecordPlaintext: Long)

    val measured: Result by lazy { measure() }

    val recordOverhead: Long get() = measured.recordOverhead
    val alertRecord: Long get() = measured.alertRecord

    private fun measure(): Result {
        val (a, b) = HonestNode.pairedPair()
        val (cc, sc) = Loop.pair()
        val (ca, sa) = both({ a.dialPaired(cc, b.pin) }, { b.accept(sc) })
        val client = (ca as End.Ok).value
        val server = (sa as End.Ok).value
        val conn = (client.end as End.Ok).value
        val sConn = (server.end as End.Ok).value
        val reader = Thread {
            val buf = ByteArray(65_536)
            try {
                while (sConn.input.read(buf, 0, buf.size) >= 0) Unit
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }

        fun cost(n: Int): Pair<Long, Int> {
            val before = client.tap.bytesWritten
            val records = client.tap.recordsOut
            conn.output.write(ByteArray(n))
            return (client.tap.bytesWritten - before - n) to (client.tap.recordsOut - records)
        }

        val (small, smallRecords) = cost(1)
        val (mid, midRecords) = cost(1_000)
        check(smallRecords == 1 && midRecords == 1 && small == mid) { "calibration: a write of 1 and of 1000 bytes did not cost one record of equal overhead ($small/$smallRecords, $mid/$midRecords)" }
        var lo = 1_000
        var hi = 16_385
        check(cost(hi).second == 2) { "calibration: a write of $hi bytes fits one record" }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cost(mid).second == 1) lo = mid else hi = mid
        }
        val max = lo.toLong()
        val before = client.tap.bytesWritten
        val records = client.tap.recordsOut
        conn.close()
        val alert = client.tap.bytesWritten - before
        check(client.tap.recordsOut - records == 1) { "calibration: a close wrote ${client.tap.recordsOut - records} records" }
        reader.join(5_000)
        runCatching { sConn.close() }
        return Result(conn.facts.cipherSuite, small, alert, max)
    }
}
