package xyz.mdhv.asom.lab.proto.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.session.Build
import xyz.mdhv.asom.lab.proto.session.EngineEvent
import xyz.mdhv.asom.lab.proto.session.Frames
import xyz.mdhv.asom.lab.proto.session.Refusal
import xyz.mdhv.asom.lab.proto.session.attemptIdOf
import xyz.mdhv.asom.lab.proto.session.spec
import xyz.mdhv.asom.lab.proto.wire.CancelReason
import xyz.mdhv.asom.lab.proto.wire.GoAwayReason
import xyz.mdhv.asom.lab.proto.wire.Terminal

/**
 * The three risks the session track named (ERR-PS-8, ERR-PS-14 and the residual-bytes rule of ERR-PS-2), proved against real TLS counts. Evidence label: LAB,
 * oracle: self, JDK 17 and JDK 21, NOT DEVICE EVIDENCE.
 */
@Timeout(300)
class RisksTest {
    private fun closed(w: TlsWorld, s: xyz.mdhv.asom.lab.proto.session.Session) {
        Wait.until("the session to close") { s.closed }
        TlsRuns.settle(w)
    }

    // ------------------------------------------------------------------------------------------------------------ the handshake snapshot

    @Test
    fun theHandshakeFigureIsTheEngineCountAtTheHandoverAndNeverIncludesAnApplicationRecordThatHadAlreadyArrived() {
        TlsWorld(11).use { w ->
            var hold: HoldingNet? = null
            val c = w.rawClient(wrapNet = { HoldingNet(it).also { h -> hold = h } })
            val hello = Build.hello(c.hostileNodeId)
            c.peer.writeRaw(hello)
            hold!!.release()
            c.peer.awaitFrames(1, "HELLO_ACK")
            val conn = c.accepted.conn!!
            val est = conn.atEstablishment
            val open = w.b.rows().first { it.meshKind == MeshKind.SESSION && it.meshCode == "established" }
            assertEquals(est.engine, open.overheadBytes, "SESSION open carries the engine's count at the hand-over")
            val record = hello.size + TlsCalibration.recordOverhead
            assertEquals(record, est.tapTotal - est.engine, "the first application record was already in the engine's input buffer, and is exactly what the handshake figure leaves out")
            assertTrue(est.engine in 1_500..8_192, "a handshake of two P-256 chains is a few KB: ${est.engine}")
            c.peer.close()
            closed(w, c.honest)
            val stats = L15Stats()
            TlsOracle.l15(w, Side(w.b, conn, c.honest), c.peer.conn, stats)
            val close = w.b.rows().last { it.meshKind == MeshKind.SESSION }
            val tap = conn.tap.bytesRead + conn.tap.bytesWritten
            assertTrue(tap - (conn.plaintextRead + conn.plaintextWritten) - est.engine >= 0, "the close overhead is not negative: nothing was clamped")
            assertTrue(close.overheadBytes!! > 0)
            assertEquals(1, stats.measuredSessions)
        }
    }

    @Test
    fun overManySessionsTheDiallerAndTheListenerRecordTheEngineCountAtTheHandoverAndTheSplitNeverGoesNegative() {
        var sessions = 0
        for (seed in 21L..28L) {
            TlsWorld(seed).use { w ->
                val link = w.connect()
                w.awaitEstablished(link)
                val a = link.connA!!
                val b = link.connB!!
                val dial = w.a.rows().first { it.meshKind == MeshKind.DIAL && it.phase == Phase.OUTCOME }
                val open = w.b.rows().first { it.meshKind == MeshKind.SESSION && it.meshCode == "established" }
                assertEquals(a.atEstablishment.engine, dial.overheadBytes, "DIAL outcome: the dialler's handshake figure")
                assertEquals(b.atEstablishment.engine, open.overheadBytes, "SESSION open: the listener's handshake figure")
                for (e in listOf(a.atEstablishment, b.atEstablishment)) {
                    assertTrue(e.engine <= e.tapTotal && e.tapTotal - e.engine < 2_048, "unconsumed bytes at the hand-over are a ticket at most: ${e.tapTotal - e.engine}")
                    assertTrue(e.engine in 1_500..8_192)
                }
                link.sessionA!!.goAway(GoAwayReason.SHUTDOWN)
                closed(w, link.sessionA!!)
                closed(w, link.sessionB!!)
                val stats = L15Stats()
                TlsOracle.check(link, strict = true, c = xyz.mdhv.asom.lab.proto.session.Counts(), l15 = stats)
                assertEquals(2, stats.measuredSessions)
                for ((conn, hs) in listOf(a to a.atEstablishment.engine, b to b.atEstablishment.engine)) {
                    val unclamped = conn.tap.bytesRead + conn.tap.bytesWritten - (conn.plaintextRead + conn.plaintextWritten) - hs
                    assertTrue(unclamped >= 0, "close overhead before clamping: $unclamped")
                }
                sessions += 2
            }
        }
        assertEquals(16, sessions)
    }

    // ------------------------------------------------------------------------------------------------------------ residual bytes on the SESSION close row

    @Test
    fun aRefusedFrameAndAHalfFrameLandOnTheCloseRowAndTheRowsStillAddUpToThePlaintext() {
        TlsWorld(31).use { w ->
            val c = w.rawClient()
            c.establish()
            val bad = Frames.encode(0x24, 0, "{}")
            c.peer.writeRaw(bad)
            val err = c.peer.awaitFrames(1, "ERROR").single()
            assertEquals("PROTOCOL_ERROR", err.code)
            closed(w, c.honest)
            val close = w.b.rows().last { it.meshKind == MeshKind.SESSION }
            assertEquals(bad.size.toLong(), close.bytesIn, "the refused frame is on the SESSION close row")
            assertEquals(0L, close.bytesOut)
            val stats = L15Stats()
            TlsOracle.l15(w, Side(w.b, c.accepted.conn!!, c.honest), c.peer.conn, stats)
            assertEquals(1, stats.measuredSessions)
        }
        TlsWorld(32).use { w ->
            val c = w.rawClient()
            c.establish()
            val half = Frames.encode(0x20, 1, """{"v":1}""").copyOf(11)
            c.peer.writeRaw(half)
            c.peer.close()
            closed(w, c.honest)
            assertEquals(1, Wait.refusals(w.b, Refusal.TRUNCATED, 1))
            val close = w.b.rows().last { it.meshKind == MeshKind.SESSION }
            assertEquals(half.size.toLong(), close.bytesIn, "a frame cut in half is residual input on the close row")
            val stats = L15Stats()
            TlsOracle.l15(w, Side(w.b, c.accepted.conn!!, c.honest), c.peer.conn, stats)
            assertEquals(1, stats.measuredSessions)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the late CANCEL

    @Test
    fun aCancelAfterTheAttemptFinishedIsIgnoredItsBytesAreResidualAndAnUnknownOneStillCloses() {
        TlsWorld(41).use { w ->
            val c = w.rawClient()
            c.establish()
            c.peer.writeRaw(Build.offer(attemptIdOf(7), 1))
            c.peer.writeRaw(Build.body(1))
            val answers = c.peer.awaitType(0x16, "INFER_END")
            assertEquals(listOf(0x11, 0x14, 0x15, 0x15, 0x16), answers.map { it.type })
            Wait.until("the attempt's outcome row") { w.b.rows().any { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.OUTCOME } }
            val late = Build.cancel(attemptIdOf(7), 1)
            c.peer.writeRaw(late)
            // a barrier: the next reply on the wire proves the CANCEL was dispatched, and it is not an answer to it
            c.peer.writeRaw(Build.stateReq(3))
            val reply = c.peer.awaitFrames(1, "STATE").single()
            assertEquals(0x21, reply.type, "a late cancel gets no reply of its own")
            Wait.until("the late cancel to be counted") { w.b.node.lateCancels.get() >= 1 }
            assertEquals(1, w.b.node.lateCancels.get())
            assertEquals(0, w.b.counters.of(Refusal.CANCEL_UNKNOWN_ATTEMPT))
            assertTrue(!c.honest.closed)
            val unknown = Build.cancel(attemptIdOf(8), 1)
            c.peer.writeRaw(unknown)
            val e = c.peer.awaitFrames(1, "ERROR").single()
            assertEquals("PROTOCOL_ERROR", e.code)
            closed(w, c.honest)
            assertEquals(1, Wait.refusals(w.b, Refusal.CANCEL_UNKNOWN_ATTEMPT, 1))
            val close = w.b.rows().last { it.meshKind == MeshKind.SESSION }
            assertEquals(late.size.toLong() + unknown.size, close.bytesIn, "both cancels are uncovered input on the SESSION close row")
            val stats = L15Stats()
            TlsOracle.l15(w, Side(w.b, c.accepted.conn!!, c.honest), c.peer.conn, stats)
            assertEquals(1, stats.measuredSessions)
        }
    }

    @Test
    fun anHonestRequesterThatCancelsAsTheLenderFinishesLeavesRowsThatAddUpWhicheverWayTheRaceFalls() {
        TlsWorld(42).use { w ->
            val link = w.connect()
            w.awaitEstablished(link)
            val sa = link.sessionA!!
            w.b.engine.script = { listOf(EngineEvent.Head(200, "m1"), EngineEvent.Chunk(ByteArray(8)), EngineEvent.Chunk(ByteArray(8)), EngineEvent.End(Terminal.DONE, 200, 1)) }
            val cancelledInTime = AtomicInteger()
            val finished = AtomicInteger()
            repeat(20) { i ->
                val rec = Waiting()
                var id = ""
                rec.onChunkHook = { n -> if (n == 1) sa.cancel(id, CancelReason.CLIENT_GONE) }
                id = sa.offer(spec(index = i, requestId = "req-race-$i"), "{}".toByteArray(), rec)
                val o = rec.await()
                if (o.terminal == Terminal.CANCELLED) cancelledInTime.incrementAndGet()
                finished.incrementAndGet()
            }
            assertEquals(20, finished.get())
            sa.goAway(GoAwayReason.SHUTDOWN)
            closed(w, sa)
            closed(w, link.sessionB!!)
            val late = w.b.node.lateCancels.get()
            println("late-cancel race: 20 attempts, cancelled in time $cancelledInTime, late cancels ignored by the lender $late")
            assertEquals(0, w.b.counters.of(Refusal.CANCEL_UNKNOWN_ATTEMPT), "a cancel that crosses the end is never an unknown attempt")
            val counts = xyz.mdhv.asom.lab.proto.session.Counts()
            val stats = L15Stats()
            TlsOracle.check(link, strict = true, c = counts, l15 = stats)
            assertEquals(2, stats.measuredSessions)
            assertEquals(0, counts.residualViolations)
        }
    }
}
