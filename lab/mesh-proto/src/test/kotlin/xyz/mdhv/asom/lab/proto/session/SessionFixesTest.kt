package xyz.mdhv.asom.lab.proto.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.Limits
import xyz.mdhv.asom.lab.proto.wire.ManifestMsg
import xyz.mdhv.asom.lab.proto.wire.MeshError
import xyz.mdhv.asom.lab.proto.wire.PeerError

/**
 * The fixes of the review of the session layer (ERRATA ERR-FX2-1 to ERR-FX2-8), each on a hostile or wedged peer over in-memory connections, on the injected clock
 * of the node under test. Evidence label: LAB, oracle: self.
 */
class SessionFixesTest {
    private fun establishedWithOneFinishedAttempt(seed: Long): Pair<World, HostileClient> {
        val w = World(seed)
        val h = HostileClient(w)
        h.establish()
        h.send(Build.offer(attemptIdOf(1), 1))
        h.send(Build.body(1))
        val types = h.frames().map { it.type }
        assertEquals(0x16, types.last(), "the attempt ended: $types")
        return w to h
    }

    // ------------------------------------------------------------------------------------------------------------ PSL-1

    @Test
    fun aLateCancelDoesNotRestartTheIdleTimer() {
        val (w, h) = establishedWithOneFinishedAttempt(901)
        val last = h.honest.lastStreamActivity
        w.b.advance(SessionLimits.IDLE_MS - 1_000 - (w.b.peek() - last))
        h.send(Build.cancel(attemptIdOf(1), 1))
        assertEquals(1, w.b.node.lateCancels.get(), "the first late cancel is the tolerated race")
        assertTrue(!h.honest.closed)
        h.honest.tick(last + SessionLimits.IDLE_MS)
        assertTrue(h.honest.closed, "an ignored frame restarted the idle timer")
        assertEquals("GOAWAY:idle", w.b.rows().first { it.meshCode?.startsWith("GOAWAY") == true }.meshCode)
    }

    @Test
    fun aSecondLateCancelForTheSameAttemptIsAnUnknownAttempt() {
        val (w, h) = establishedWithOneFinishedAttempt(902)
        h.send(Build.cancel(attemptIdOf(1), 1))
        assertTrue(!h.honest.closed)
        h.send(Build.cancel(attemptIdOf(1), 1))
        val e = h.frames().single { it.type == 6 }
        assertEquals("PROTOCOL_ERROR", e.code)
        assertTrue(h.honest.closed, "a repeated late cancel was tolerated")
        assertEquals(1, w.b.node.lateCancels.get())
        assertEquals(1, w.b.node.counters.of(Refusal.CANCEL_UNKNOWN_ATTEMPT))
    }

    // ------------------------------------------------------------------------------------------------------------ PSL-3

    @Test
    fun aBodyThatArrivesJustAfterTheBodyWaitExpiredIsIgnoredOnceAndTheSessionStays() {
        val w = World(903)
        val h = HostileClient(w)
        h.establish()
        h.send(Build.offer(attemptIdOf(2), 1, deadlineMs = 1_000))
        assertEquals(listOf(0x11), h.frames().map { it.type })
        w.b.advance(1_500)
        h.honest.tick(w.b.peek())
        val end = h.frames().single()
        assertEquals(0x16, end.type)
        assertTrue(end.text.contains("\"status\":408"), end.text)
        h.send(Build.body(1))
        assertEquals(emptyList(), h.frames().map { it.name }, "the late body was answered")
        assertTrue(!h.honest.closed, "an honest body that raced with the expiry closed the session")
        assertEquals(0, w.b.node.counters.of(Refusal.BODY_WITHOUT_OFFER))
        h.send(Build.offer(attemptIdOf(3), 3))
        assertEquals(listOf(0x11), h.frames().map { it.type }, "the session still serves")
        h.send(Build.body(1))
        assertEquals("PROTOCOL_ERROR", h.frames().single { it.type == 6 }.code)
        assertTrue(h.honest.closed, "a second late body was tolerated")
        assertEquals(1, w.b.node.counters.of(Refusal.BODY_WITHOUT_OFFER))
    }

    // ------------------------------------------------------------------------------------------------------------ PSL-4

    @Test
    fun anExtensionFrameBeforeHelloLeavesEveryRowOfTheSessionUnderOneId() {
        val w = World(904)
        val h = HostileClient(w)
        val nonce = nonceOf(5)
        h.send(Build.ext())
        h.send(Build.ext(0x91, 0, ByteArray(3)))
        h.send(Build.hello(h.hostileNodeId, nonce))
        assertEquals(listOf(2), h.frames().map { it.type })
        h.honest.close()
        val rows = w.b.rows()
        assertEquals(setOf(nonce), rows.mapNotNull { it.sessionId }.toSet(), "rows under more than one session id: ${rows.map { it.sessionId }}")
        assertEquals(listOf("established", "EXT_IGNORED", "EXT_IGNORED", "HELLO", "HELLO_ACK", "close"), rows.map { it.meshCode })
        assertEquals(nonce, rows.first().attemptId, "the open row carries the join id")
        assertEquals(setOf(nonce), rows.mapNotNull { it.attemptId }.toSet())
    }

    @Test
    fun aListenerHoldsOnlyAFewExtensionFramesBeforeHello() {
        val w = World(905)
        val h = HostileClient(w)
        repeat(SessionLimits.MAX_EARLY_EXTENSIONS) { h.send(Build.ext(0x80 + it, 0, ByteArray(2))) }
        assertTrue(!h.honest.closed)
        h.send(Build.ext(0x90, 0, ByteArray(2)))
        assertEquals("PROTOCOL_ERROR", h.frames().single { it.type == 6 }.code)
        assertTrue(h.honest.closed)
        assertEquals(1, w.b.node.counters.of(Refusal.FRAME_BEFORE_HELLO))
        val rows = w.b.rows()
        assertEquals(1, rows.mapNotNull { it.sessionId }.toSet().size, "one id for every row")
        assertEquals(SessionLimits.MAX_EARLY_EXTENSIONS, rows.count { it.meshCode == "EXT_IGNORED" })
    }

    @Test
    fun aHelloThatReusesTheNonceOfAnotherSessionOfThisNodeIsRefused() {
        val w = World(906)
        val first = HostileClient(w)
        first.establish(nonceOf(7))
        val second = HostileClient(w)
        second.send(Build.hello(second.hostileNodeId, nonceOf(7)))
        assertEquals("PROTOCOL_ERROR", second.frames().single { it.type == 6 }.code)
        assertTrue(second.honest.closed, "a reused session nonce established a session")
        assertTrue(!first.honest.closed)
        assertEquals(1, w.b.node.counters.of(Refusal.HELLO_NODE_MISMATCH))
        assertTrue(second.honest.sessionId != nonceOf(7), "the refused session is grouped under the id of the first")
    }

    @Test
    fun aHelloThatEchoesTheIdThisNodeUsedForItsOwnDialIsRefused() {
        val w = World(907)
        val (sa, _) = w.connect()
        val (cc, sc) = MemConnection.pair(w.a.pin, w.b.pin, w.log, "H>A", "A<H", ConnMode.ESTABLISHED)
        val honest = w.a.node.accept(sc)
        cc.writeRaw(Build.hello(w.b.pin.nodeId, sa.sessionId))
        while (honest.pumpAvailable(1 shl 20) > 0) Unit
        assertTrue(honest.closed, "a session was established under the id of this node's own dial")
        assertEquals(1, w.a.node.counters.of(Refusal.HELLO_NODE_MISMATCH))
    }

    // ------------------------------------------------------------------------------------------------------------ PSL-7

    @Test
    fun theFourStreamLimitIsPerPeerAcrossItsSessions() {
        val w = World(908, limitsB = Limits(300_000, 8_388_608, 8, 4_096, 30))
        val one = HostileClient(w)
        one.establish(nonceOf(1))
        for (n in 0 until 4) one.send(Build.offer(attemptIdOf(10 + n), 1L + 2 * n))
        assertEquals(List(4) { 0x11 }, one.frames().map { it.type })
        val two = HostileClient(w)
        two.establish(nonceOf(2))
        two.send(Build.offer(attemptIdOf(20), 1))
        val busy = two.frames().single()
        assertEquals(0x12, busy.type, "a second session of the same peer got a fifth stream")
        assertEquals("PEER_BUSY", busy.code)
        one.send(Build.cancel(attemptIdOf(10), 1))
        assertEquals(0x16, one.frames().single().type)
        two.send(Build.offer(attemptIdOf(21), 3))
        assertEquals(0x11, two.frames().single().type, "a finished stream of the other session frees a slot")
    }

    // ------------------------------------------------------------------------------------------------------------ PSL-2

    private fun wedgedLender(seed: Long): Pair<World, HostileServer> {
        val w = World(seed)
        val hs = HostileServer(w)
        hs.establish()
        hs.frames()
        return w to hs
    }

    @Test
    fun aStateRequestThatIsNeverAnsweredFailsAsLostAndDoesNotPinTheSession() {
        val (w, hs) = wedgedLender(910)
        var got: StateResult? = null
        hs.honest.requestState { got = it }
        w.a.advance(SessionLimits.REQUEST_WAIT_MS / 2)
        hs.honest.tick(w.a.peek())
        assertNull(got, "the request failed before its wait was over")
        w.a.advance(SessionLimits.REQUEST_WAIT_MS)
        hs.honest.tick(w.a.peek())
        assertTrue(got is StateResult.Lost, "the unanswered request never failed ($got)")
        assertEquals(0, hs.honest.openStreams())
        w.a.advance(SessionLimits.MAX_AGE_MS)
        hs.honest.tick(w.a.peek())
        assertTrue(hs.honest.closed, "a wedged lender pinned the session past the maximum age")
    }

    @Test
    fun aReplyToARequestTheRequesterGaveUpOnIsIgnoredOnce() {
        val (w, hs) = wedgedLender(911)
        var got: StateResult? = null
        hs.honest.requestState { got = it }
        w.a.advance(SessionLimits.REQUEST_WAIT_MS + 1)
        hs.honest.tick(w.a.peek())
        assertTrue(got is StateResult.Lost, "$got")
        hs.send(Build.msg(PeerError(MeshError.SCOPE_DENIED), 1))
        assertTrue(!hs.honest.closed, "the late reply of a slow lender closed the session")
        hs.send(Build.msg(PeerError(MeshError.SCOPE_DENIED), 1))
        assertTrue(hs.honest.closed, "a second reply on the same stream was tolerated")
    }

    @Test
    fun aManifestReplyToALapsedRequestIsIgnoredOnceAndDoesNotRestartTheIdleTimer() {
        val (w, hs) = wedgedLender(915)
        var got: ManifestResult? = null
        hs.honest.requestManifest(ByteArray(32)) { got = it }
        w.a.advance(SessionLimits.REQUEST_WAIT_MS + 1)
        hs.honest.tick(w.a.peek())
        assertTrue(got is ManifestResult.Lost, "$got")
        val before = hs.honest.lastStreamActivity
        hs.send(Build.msg(ManifestMsg("{\"a\":1}".toByteArray()), 1))
        assertTrue(!hs.honest.closed, "the late reply of a slow lender closed the session")
        assertEquals(1, w.a.node.lateReplies.get())
        assertEquals(before, hs.honest.lastStreamActivity, "an ignored reply restarted the idle timer")
        hs.send(Build.msg(ManifestMsg("{\"a\":1}".toByteArray()), 1))
        assertTrue(hs.honest.closed, "a second reply on the same stream was tolerated")
        assertEquals(1, w.a.node.counters.of(Refusal.UNSOLICITED_REPLY))
    }

    @Test
    fun aMalformedManifestChallengeThrowsBeforeAnyStateIsRegistered() {
        val (w, hs) = wedgedLender(912)
        assertFailsWith<IllegalArgumentException> { hs.honest.requestManifest(ByteArray(31)) { } }
        assertEquals(0, hs.honest.openStreams(), "a request that was never sent stays pending")
        assertEquals(emptyList(), hs.frames().map { it.name })
        var got: ManifestResult? = null
        hs.honest.requestManifest(ByteArray(32)) { got = it }
        assertEquals(listOf(0x22 to 1L), hs.frames().map { it.type to it.stream }, "the first real request uses the first stream id")
        w.a.advance(SessionLimits.MAX_AGE_MS)
        hs.honest.tick(w.a.peek())
        assertTrue(got is ManifestResult.Lost)
    }

    @Test
    fun anOfferThatIsNeverAnsweredIsCancelledAtItsDeadlineAndAWedgedLenderClosesTheSession() {
        val (w, hs) = wedgedLender(913)
        val rec = Recorder()
        val id = hs.honest.offer(spec(), "{}".toByteArray(), rec)
        assertEquals(listOf(0x10), hs.frames().map { it.type })
        w.a.advance(spec().deadlineMs - 1_000)
        hs.honest.tick(w.a.peek())
        assertEquals(emptyList(), hs.frames().map { it.name }, "cancelled before the deadline")
        w.a.advance(2_000)
        hs.honest.tick(w.a.peek())
        val cancel = hs.frames().single()
        assertEquals(0x17, cancel.type)
        assertEquals("deadline", cancel.reason)
        assertEquals(id, cancel.attemptId)
        assertNull(rec.outcome)
        assertTrue(!hs.honest.closed)
        w.a.advance(SessionLimits.CANCEL_GRACE_MS + 1_000)
        hs.honest.tick(w.a.peek())
        assertTrue(hs.honest.closed, "a lender that never answered a cancel pinned the session")
        assertEquals(599, assertNotNull(rec.outcome).status)
        assertEquals("PEER_UNREACHABLE", rec.outcome!!.meshCode)
    }

    // ------------------------------------------------------------------------------------------------------------ PSL-8

    @Test
    fun aHostCallbackThatThrowsCannotStopTheCloseOrTheCloseRow() {
        val (w, hs) = wedgedLender(914)
        val seen = ArrayList<String>()
        var closeRowAtCallback = false
        var connClosedAtCallback = false
        val bad = object : AttemptListener {
            override fun onFinished(outcome: AttemptOutcome) {
                closeRowAtCallback = w.a.rows().any { it.meshKind == MeshKind.SESSION && it.meshCode == "close" }
                connClosedAtCallback = hs.serverConn.peerClosed()
                seen += "bad"
                throw IllegalStateException("a bug in the host")
            }
        }
        val good = Recorder()
        hs.honest.offer(spec(), "{}".toByteArray(), bad)
        hs.honest.offer(spec(index = 1), "{}".toByteArray(), good)
        var state: StateResult? = null
        hs.honest.requestState {
            state = it
            throw IllegalStateException("another bug")
        }
        var closedCalled = false
        hs.honest.listener = object : SessionListener {
            override fun onClosed() {
                closedCalled = true
                throw IllegalStateException("and another")
            }
        }
        val result = runCatching { hs.honest.close() }
        assertTrue(result.isSuccess, "the close threw: ${result.exceptionOrNull()}")
        assertTrue(hs.honest.closed)
        assertTrue(hs.serverConn.peerClosed(), "the connection stayed open")
        val close = w.a.rows().filter { it.meshKind == MeshKind.SESSION && it.meshCode == "close" }
        assertEquals(1, close.size, "no SESSION close row")
        assertEquals(listOf("bad"), seen)
        assertTrue(closeRowAtCallback && connClosedAtCallback, "a callback ran before the connection was closed and the close row written")
        assertEquals(599, assertNotNull(good.outcome).status, "a callback after the one that threw never ran")
        assertTrue(state is StateResult.Lost)
        assertTrue(closedCalled)
        assertEquals(3, w.a.node.callbackErrors.get(), "the three throwing callbacks are counted")
        val last = w.a.rows().last()
        assertEquals("close", last.meshCode, "the close row is the last row of the session")
    }
}
