package xyz.mdhv.asom.lab.proto.integration

import java.util.SplittableRandom
import java.util.concurrent.CopyOnWriteArrayList
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.proto.pairing.PairChallenge
import xyz.mdhv.asom.lab.proto.pairing.PairCommit
import xyz.mdhv.asom.lab.proto.pairing.PairCommitAck
import xyz.mdhv.asom.lab.proto.pairing.PairDecision
import xyz.mdhv.asom.lab.proto.pairing.PairHello
import xyz.mdhv.asom.lab.proto.pairing.PairMessages
import xyz.mdhv.asom.lab.proto.session.Build
import xyz.mdhv.asom.lab.proto.session.Counts
import xyz.mdhv.asom.lab.proto.session.Ev
import xyz.mdhv.asom.lab.proto.session.FailPlan
import xyz.mdhv.asom.lab.proto.session.Frames
import xyz.mdhv.asom.lab.proto.session.PairingChannel
import xyz.mdhv.asom.lab.proto.session.SeededIds
import xyz.mdhv.asom.lab.proto.session.Table76
import xyz.mdhv.asom.lab.proto.tls.HandshakeObserver
import xyz.mdhv.asom.lab.proto.tls.MeshTls
import xyz.mdhv.asom.lab.proto.tls.RecordTap
import xyz.mdhv.asom.lab.proto.tls.SocketNet
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.wire.ConnMode

/**
 * Two pairing-mode channels over a real TLS connection: B has its pairing window open and has never heard of A, A dials B from a QR pin (`EXPECT_PAIRING`), B's
 * verifier chooses `PAIRING_SERVER`, and the `PAIR_*` frames flow with their per-frame `PAIRING` rows. Nothing here implements pairing; the bodies are opaque to the
 * session layer, which only writes the rows (ERRATA ERR-PS-16).
 */
class PairRun(val w: TlsWorld, val s: PairingChannel, val d: PairingChannel, val connS: TappedConn, val connD: TappedConn, val nonceS: ByteArray, val dialSessionId: String) : AutoCloseable {
    override fun close() = w.close()
}

object TlsPairing {
    fun run(seed: Long, failS: FailPlan? = null): PairRun {
        val w = TlsWorld(seed, failB = failS, paired = false)
        w.b.pairingWindow = true
        val rnd = SplittableRandom(seed)
        Loopback().use { lb ->
            var acc: PairingChannel? = null
            var accConn: TappedConn? = null
            var accError: Throwable? = null
            val t = Thread {
                try {
                    val ch = lb.accept()
                    val tap = RecordTap(SocketNet(ch), w.b.tapBook)
                    val obs = HandshakeObserver()
                    val tls = MeshTls.accept(tap, w.b.identity(), w.b.env(), obs)
                    noteEstablishedListener(tls, obs)
                    val tc = TappedConn(tls, "B<A", w.log, tap, obs, ch)
                    w.b.conn = tc
                    check(tc.mode == ConnMode.PAIRING) { "the listener admitted an unknown pin without a pairing connection" }
                    accConn = tc
                    acc = PairingChannel(w.b.ledger, tc, SeededIds(seed * 3 + 2))
                } catch (e: Throwable) {
                    accError = e
                }
            }.also { it.isDaemon = true; it.name = "pair-accept"; it.start() }
            val dialer = TlsDialer(w.a, w.b.pin, w.log, "A>B", ChainMode.ExpectPairing(w.b.pin))
            val report = w.a.node.dial(w.b.pin, lb.address, "qr", dialer)
            t.join(15_000)
            accError?.let { throw it }
            val connD = dialer.conn!!
            check(connD.mode == ConnMode.PAIRING) { "the dialler's connection is not a pairing connection" }
            val d = PairingChannel(w.a.ledger, connD, SeededIds(seed * 3 + 1), report.sessionId, report.handshakeRecorded)
            val s = acc!!
            val receivedByS = CopyOnWriteArrayList<Int>()
            val receivedByD = CopyOnWriteArrayList<Int>()
            s.onFrame = { type, _ -> receivedByS += type }
            d.onFrame = { type, _ -> receivedByD += type }
            val ts = Thread { s.runReadLoop() }.also { it.isDaemon = true; it.name = "pair-read-S"; it.start() }
            val td = Thread { d.runReadLoop() }.also { it.isDaemon = true; it.name = "pair-read-D"; it.start() }
            val nonceS = ByteArray(32) { rnd.nextInt(256).toByte() }
            fun bytes(n: Int) = ByteArray(n) { rnd.nextInt(256).toByte() }
            fun waitFor(list: List<Int>, n: Int, what: String) = Wait.until(what) { list.size >= n || s.isClosed || d.isClosed }

            s.send(0x30, PairMessages.encodeHello(PairHello(nonceS, bytes(32), "Desk", "linux", "file", emptyList())))
            waitFor(receivedByD, 1, "PAIR_HELLO at D")
            d.send(0x31, PairMessages.encodeChallenge(PairChallenge(bytes(32), "Phone", "android", "strongbox")))
            waitFor(receivedByS, 1, "PAIR_CHALLENGE at S")
            s.send(0x32, PairMessages.encodeDecision(PairDecision(true)))
            d.send(0x32, PairMessages.encodeDecision(PairDecision(rnd.nextInt(5) != 0)))
            waitFor(receivedByD, 2, "PAIR_DECISION at D")
            waitFor(receivedByS, 2, "PAIR_DECISION at S")
            val transcript = bytes(32)
            d.send(0x33, PairMessages.encodeCommit(PairCommit(transcript)))
            waitFor(receivedByS, 3, "PAIR_COMMIT at S")
            s.send(0x34, PairMessages.encodeCommitAck(PairCommitAck(transcript)))
            waitFor(receivedByD, 3, "PAIR_COMMIT_ACK at D")
            if (rnd.nextInt(3) == 0 && !s.isClosed) {
                connSSend(accConn!!, Build.ext(0x91, 0, bytes(rnd.nextInt(20))))
                Wait.until("EXT_IGNORED at D") { w.a.rows().any { it.meshCode == "EXT_IGNORED" } || d.isClosed || s.isClosed }
            }
            if (rnd.nextBoolean()) s.close() else d.close()
            Wait.until("both channels to close") { s.isClosed && d.isClosed }
            ts.join(5_000)
            td.join(5_000)
            TlsRuns.settle(w)
            return PairRun(w, s, d, accConn!!, connD, nonceS, report.sessionId)
        }
    }

    private fun connSSend(c: TappedConn, bytes: ByteArray) = c.writeRaw(bytes)

    /** The per-frame rows of both ends against LAB_SPEC 7.6 and the session-id rule of 7.5, plus L-L15 on each end. */
    fun check(p: PairRun, counts: Counts, l15: L15Stats) {
        val events = p.w.log.snapshot()
        val derived = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(p.nonceS.copyOfRange(0, 16))
        for ((side, kit, conn, peerConn) in listOf(Quad("S", p.w.b, p.connS, p.connD), Quad("D", p.w.a, p.connD, p.connS))) {
            val rows: List<LabRouteRecord> = kit.rows()
            val writes = events.filterIsInstance<Ev.Write>().filter { it.conn == conn.name && !it.raw }
            val sent = writes.map { Frames.one(it.bytes) }
            val read = Frames.parsePrefix(events.filterIsInstance<Ev.Read>().filter { it.conn == conn.name }.fold(ByteArray(0)) { acc, e -> acc + e.bytes })
            val appended = events.filterIsInstance<Ev.Appended>().filter { it.node == kit.name }
            val failed = events.filterIsInstance<Ev.AppendFailed>().filter { it.node == kit.name }
            val expected = ArrayList<Triple<MeshKind, String, Pair<Long, Long>>>()
            for (f in sent) Table76.rowFor(f, true)?.let { expected += Triple(it.kind, it.code, f.app to 0L) }
            for (f in read) Table76.rowFor(f, false)?.let { expected += Triple(it.kind, it.code, 0L to f.app) }
            val actual = appended.filter { it.row.meshKind in Table76.PER_FRAME_KINDS }.toMutableList()
            val failedKeys = failed.filter { it.row.meshKind in Table76.PER_FRAME_KINDS }
            for (e in expected) {
                val i = actual.indexOfFirst { it.row.meshKind == e.first && it.row.meshCode == e.second && it.row.bytesOut == e.third.first && (it.row.bytesIn ?: 0) == e.third.second }
                if (i >= 0) {
                    actual.removeAt(i)
                    counts.checks++
                } else if (failedKeys.none { it.row.meshKind == e.first && it.row.meshCode == e.second }) {
                    throw AssertionError("pairing $side: no row ${e.first}/${e.second} for a ${e.second} frame")
                }
            }
            if (actual.isNotEmpty()) throw AssertionError("pairing $side: rows without a frame: ${actual.map { it.row.meshCode }}")
            for ((idx, wr) in writes.withIndex()) {
                val f = sent[idx]
                val shape = Table76.rowFor(f, true)!!
                val row = appended.firstOrNull { it.row.meshKind == shape.kind && it.row.meshCode == shape.code && it.row.bytesOut == f.app } ?: throw AssertionError("pairing $side: ${f.name} sent with no row")
                if (row.seq > wr.seq) throw AssertionError("pairing $side: ${f.name} was handed to the TLS engine before its row was durable")
                counts.checks++
            }
            sent.forEach { Table76.l16Name(it)?.let(counts::type) }
            read.forEach { Table76.l16Name(it)?.let(counts::type) }
            val session = rows.filter { it.meshKind == MeshKind.SESSION }
            val ids = rows.filter { it.meshKind != null }
            if (side == "S") {
                if (session.firstOrNull()?.meshCode != "pairing") throw AssertionError("pairing S: the first SESSION row is not the pairing open row")
                if (rows.firstOrNull()?.meshKind != MeshKind.SESSION) throw AssertionError("pairing S: a row precedes the SESSION open row")
                if (ids.any { it.sessionId != derived }) throw AssertionError("pairing S: a row carries a session id other than the base64url of the first 16 bytes of nonceS")
            } else {
                if (ids.any { it.sessionId != p.dialSessionId }) throw AssertionError("pairing D: rows do not share the one connection id of the DIAL rows")
                val frameRows = rows.filter { it.meshKind != MeshKind.DIAL }
                val firstHello = frameRows.indexOfFirst { it.meshCode == "PAIR_HELLO" }
                for ((k, r) in frameRows.withIndex()) {
                    val want = if (firstHello in 0..k) derived else null
                    if (r.attemptId != want) throw AssertionError("pairing D: attemptId ${r.attemptId} on row $k, expected $want")
                }
            }
            if (session.count { it.meshCode!!.startsWith("close") } != 1) throw AssertionError("pairing $side: not exactly one SESSION close row")
            counts.sessions++
            TlsOracle.l15(p.w, Side(kit, conn, null, if (side == "S") derived else p.dialSessionId), peerConn, l15)
        }
    }

    private data class Quad(val side: String, val kit: TNode, val conn: TappedConn, val peerConn: TappedConn)
}
