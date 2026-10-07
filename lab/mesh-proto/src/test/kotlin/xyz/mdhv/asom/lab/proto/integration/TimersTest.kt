package xyz.mdhv.asom.lab.proto.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.session.Build
import xyz.mdhv.asom.lab.proto.session.Counts
import xyz.mdhv.asom.lab.proto.session.EngineEvent
import xyz.mdhv.asom.lab.proto.session.Refusal
import xyz.mdhv.asom.lab.proto.session.SessionLimits
import xyz.mdhv.asom.lab.proto.session.StateResult
import xyz.mdhv.asom.lab.proto.session.attemptIdOf
import xyz.mdhv.asom.lab.proto.session.spec
import xyz.mdhv.asom.lab.proto.wire.CancelReason
import xyz.mdhv.asom.lab.proto.wire.Limits
import xyz.mdhv.asom.lab.proto.wire.Terminal

/**
 * The timers and limits of trust.md 3.3 and 5.1 on real sessions over loopback TLS, on an INJECTED CLOCK: no test waits for time to pass, it moves the clock of the
 * node under test and the session driver (a thread that ticks the session with that same clock) or an explicit `tick` does the rest. Evidence label: LAB, oracle: self.
 */
@Timeout(300)
class TimersTest {
    private fun quiet(w: TlsWorld) = Wait.stable("the ledgers") { w.a.rows().size to w.b.rows().size }

    @Test
    fun aSessionWithNoStreamForFiveMinutesSaysGoawayIdleAndBothEndsClose() {
        TlsWorld(1).use { w ->
            val link = w.connect()
            w.awaitEstablished(link)
            val sa = link.sessionA!!
            val sb = link.sessionB!!
            quiet(w)
            val boundary = sa.lastStreamActivity + SessionLimits.IDLE_MS
            w.clockA.advance(boundary - 1 - w.clockA.peek())
            sa.tick(w.clockA.peek())
            Wait.stable("the session to stay open one millisecond before the boundary") { sa.closed || sb.closed }
            assertTrue(!sa.closed && !sb.closed, "the idle timer fired one millisecond early")
            w.clockA.advance(1)
            sa.tick(w.clockA.peek())
            assertTrue(sa.closed, "the idle timer did not fire at exactly five minutes")
            Wait.until("the peer to close") { sb.closed }
            quiet(w)
            assertEquals("GOAWAY:idle", w.a.rows().first { it.meshKind == MeshKind.CONTROL && it.meshCode?.startsWith("GOAWAY") == true }.meshCode)
            assertEquals("GOAWAY:idle", w.b.rows().first { it.meshKind == MeshKind.CONTROL && it.meshCode?.startsWith("GOAWAY") == true }.meshCode)
            TlsOracle.check(link, strict = true, c = Counts(), l15 = L15Stats())
        }
    }

    @Test
    fun anOpenStreamKeepsTheSessionAliveAndConnectionLevelFramesDoNot() {
        TlsWorld(2).use { w ->
            val link = w.connect()
            w.awaitEstablished(link)
            val sa = link.sessionA!!
            val sb = link.sessionB!!
            quiet(w)
            val idleAt = sa.lastStreamActivity + SessionLimits.IDLE_MS
            w.b.engine.script = { listOf(EngineEvent.Head(200, "m1")) + List(2_000) { EngineEvent.Chunk(ByteArray(8)) } + EngineEvent.End(Terminal.DONE, 200, 1) }
            w.b.engine.paceNanos = PacedEngine.DEFAULT_PACE_NANOS
            val rec = Waiting()
            val id = sa.offer(spec(), "{}".toByteArray(), rec)
            Wait.until("the stream to run") { rec.chunks.get() >= 2 }
            w.clockA.advance(idleAt + 600_000 - w.clockA.peek())
            sa.tick(w.clockA.peek())
            assertTrue(!sa.closed, "an open stream was cut by the idle timer")
            sa.cancel(id, CancelReason.CLIENT_GONE)
            val outcome = rec.await()
            assertEquals(Terminal.CANCELLED, outcome.terminal)
            w.b.engine.paceNanos = 0
            quiet(w)
            // five minutes after the last stream activity, extension frames from the peer do not move the timer
            val last = sa.lastStreamActivity
            w.clockA.advance(last + SessionLimits.IDLE_MS - 1_000 - w.clockA.peek())
            val before = w.a.rows().count { it.meshCode == "EXT_IGNORED" }
            link.connB!!.writeRaw(Build.ext(0x92, 0, ByteArray(4)))
            Wait.until("the extension frame to be seen") { w.a.rows().count { it.meshCode == "EXT_IGNORED" } == before + 1 }
            // a dispatched connection-level frame (stream 0) does not move it either
            val notices = w.a.rows().count { it.meshKind == MeshKind.REVOCATION }
            sb.sendRevokeNotice()
            Wait.until("the notice to be seen") { w.a.rows().count { it.meshKind == MeshKind.REVOCATION } == notices + 1 }
            sa.tick(w.clockA.peek())
            assertTrue(!sa.closed)
            w.clockA.advance(1_000)
            sa.tick(w.clockA.peek())
            assertTrue(sa.closed, "an extension frame kept the session alive past the idle limit")
            Wait.until("the peer to close") { sb.closed }
        }
    }

    @Test
    fun aSessionOlderThanMaxAgeSaysGoawayMaxAgeAtTheFirstMomentWithoutAStreamNeverBefore() {
        TlsWorld(3).use { w ->
            val link = w.connect()
            w.awaitEstablished(link)
            val sa = link.sessionA!!
            val sb = link.sessionB!!
            quiet(w)
            val start = w.clockA.peek()
            // keep the session from going idle with a STATE request every 200 s of injected time, until it is one step short of the maximum age
            var steps = 0
            while (w.clockA.peek() - start + 200_000 < SessionLimits.MAX_AGE_MS) {
                w.clockA.advance(200_000)
                sa.tick(w.clockA.peek())
                assertTrue(!sa.closed, "the session closed at step $steps, before the maximum age")
                var got: StateResult? = null
                sa.requestState { got = it }
                Wait.until("a STATE answer") { got != null }
                steps++
            }
            assertTrue(steps > 400, "the loop did not cover the maximum age ($steps steps)")
            // a stream is open when the maximum age passes: it is not cut
            w.b.engine.script = { listOf(EngineEvent.Head(200, "m1")) + List(2_000) { EngineEvent.Chunk(ByteArray(8)) } + EngineEvent.End(Terminal.DONE, 200, 1) }
            w.b.engine.paceNanos = PacedEngine.DEFAULT_PACE_NANOS
            val rec = Waiting()
            val id = sa.offer(spec(), "{}".toByteArray(), rec)
            Wait.until("the stream to run") { rec.chunks.get() >= 2 }
            w.clockA.advance(SessionLimits.MAX_AGE_MS)
            sa.tick(w.clockA.peek())
            assertTrue(!sa.closed, "a session with an open stream was cut for age")
            sa.cancel(id, CancelReason.CLIENT_GONE)
            rec.await()
            w.b.engine.paceNanos = 0
            sa.tick(w.clockA.peek())
            assertTrue(sa.closed, "the session did not close at the first moment without a stream")
            Wait.until("the peer to close") { sb.closed }
            quiet(w)
            assertEquals("GOAWAY:max-age", w.a.rows().first { it.meshCode?.startsWith("GOAWAY") == true }.meshCode)
            assertEquals("GOAWAY:max-age", w.b.rows().first { it.meshCode?.startsWith("GOAWAY") == true }.meshCode)
        }
    }

    @Test
    fun anAcceptedAttemptWhoseBodyNeverComesExpiresReleasesItsSlotAndIsRowedOnce() {
        TlsWorld(4).use { w ->
            val c = w.rawClient()
            c.establish()
            quiet(w)
            val offer = Build.offer(attemptIdOf(1), 1, deadlineMs = 60_000)
            c.peer.writeRaw(offer)
            val accept = c.peer.awaitFrames(1, "INFER_ACCEPT").single()
            assertEquals(0x11, accept.type)
            val acceptedAt = w.clockB.peek()
            w.clockB.advance(SessionLimits.BODY_WAIT_MS - 1 - 5)
            c.honest.tick(w.clockB.peek())
            Wait.stable("no INFER_END before the limit") { c.peer.receivedBytes }
            assertEquals(0, c.peer.frames().size, "the attempt expired before BODY_WAIT_MS")
            w.clockB.advance(1_000)
            c.honest.tick(w.clockB.peek())
            val end = c.peer.awaitFrames(1, "INFER_END").single()
            assertEquals(0x16, end.type)
            assertTrue(end.text.contains("\"terminal\":\"error\"") && end.text.contains("\"status\":408"), end.text)
            assertTrue(!c.honest.closed, "an expired attempt does not close the session")
            val rows = w.b.rows().filter { it.meshKind == MeshKind.INFER_SERVED }
            assertEquals(listOf(Phase.OUTCOME), rows.map { it.phase }, "no body, so no intent row: one outcome row")
            assertEquals(408, rows.single().status)
            assertEquals("ERROR", rows.single().meshCode)
            assertEquals(offer.size.toLong(), rows.single().bytesIn)
            assertEquals(accept.app + end.app, rows.single().bytesOut)
            assertTrue(w.b.engine.opened.isEmpty(), "the engine never started")
            // the single concurrency slot is free again
            c.peer.writeRaw(Build.offer(attemptIdOf(2), 3))
            assertEquals(0x11, c.peer.awaitFrames(1, "INFER_ACCEPT of the second offer").single().type)
            assertTrue(acceptedAt > 0)
        }
    }

    @Test
    fun theBodyWaitIsNeverLongerThanTheOffersOwnDeadline() {
        TlsWorld(5).use { w ->
            val c = w.rawClient()
            c.establish()
            quiet(w)
            c.peer.writeRaw(Build.offer(attemptIdOf(3), 1, deadlineMs = 5_000))
            c.peer.awaitFrames(1, "INFER_ACCEPT")
            w.clockB.advance(4_900)
            c.honest.tick(w.clockB.peek())
            Wait.stable("no INFER_END before the deadline") { c.peer.receivedBytes }
            assertEquals(0, c.peer.frames().size)
            w.clockB.advance(200)
            c.honest.tick(w.clockB.peek())
            assertEquals(0x16, c.peer.awaitFrames(1, "INFER_END").single().type)
        }
    }

    @Test
    fun aFifthConcurrentStreamIsDeclinedPeerBusyAndAFinishedOneFreesASlot() {
        TlsWorld(6, limitsB = Limits(300_000, 8_388_608, 8, 4_096, 30)).use { w ->
            val c = w.rawClient()
            c.establish()
            quiet(w)
            for (n in 0 until 4) c.peer.writeRaw(Build.offer(attemptIdOf(10 + n), 1L + 2 * n))
            val first = c.peer.awaitFrames(4, "four INFER_ACCEPT")
            assertEquals(listOf(0x11, 0x11, 0x11, 0x11), first.map { it.type })
            c.peer.writeRaw(Build.offer(attemptIdOf(20), 9))
            val busy = c.peer.awaitFrames(1, "the fifth answer").single()
            assertEquals(0x12, busy.type)
            assertEquals("PEER_BUSY", busy.code)
            assertTrue(busy.text.contains("\"retryAfterMs\":5000"))
            assertEquals(1, w.b.counters.of(Refusal.PEER_BUSY))
            assertTrue(!c.honest.closed, "a decline does not close the session")
            assertEquals(4, c.honest.openStreams())
            c.peer.writeRaw(Build.cancel(attemptIdOf(10), 1))
            val ended = c.peer.awaitFrames(1, "INFER_END of the cancelled stream").single()
            assertEquals(0x16, ended.type)
            c.peer.writeRaw(Build.offer(attemptIdOf(21), 11))
            val again = c.peer.awaitFrames(1, "the sixth answer").single()
            assertEquals(0x11, again.type, "a stream that finished did not free its slot")
            assertNotNull(w.b.rows().firstOrNull { it.meshCode == "PEER_BUSY" })
        }
    }
}
