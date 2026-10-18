package xyz.mdhv.asom.lab.proto.session

import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.proto.wire.InferAccept
import xyz.mdhv.asom.lab.proto.wire.Limits

/**
 * The fixes of the lender review of fix wave 4 (ERRATA ERR-FX4-LD-*): the lender's own availability is a decision input, host-port failures become typed declines,
 * the concurrency limits are admitted atomically across sessions, a requester re-reads the registry just before the body, the live-state sequence is per requester,
 * and the maximum session age is a hard cap with a bounded grace. Evidence label: LAB, oracle: self.
 */
class LenderFixesTest {
    private fun openServing() = ServingView(servingConditionsOk = true, presenceActive = false)

    private fun wide(maxConcurrent: Long) = Limits(300_000, 8_388_608, maxConcurrent, 4_096, 30)

    // ------------------------------------------------------------------------------------------------------------ A-MP-2

    @Test
    fun aLenderThatIsNotServingDeclinesEveryOfferPeerUnavailableAndRunsNoEngine() {
        var declines = 0
        for ((i, fsm) in listOf(Fsm.OFF, Fsm.ARMED, Fsm.DRAINING).withIndex()) {
            val w = World(940L + i)
            w.b.serving = openServing()
            w.b.live.fsm = fsm
            val h = HostileClient(w)
            h.establish()
            h.send(Build.offer(attemptIdOf(1), 1))
            val reply = h.frames().single()
            assertEquals(0x12, reply.type, "fsm=$fsm: the offer was not declined (${reply.name})")
            assertEquals("PEER_UNAVAILABLE", reply.code, "fsm=$fsm")
            assertTrue(w.b.engine.opened.isEmpty(), "fsm=$fsm: an engine was opened")
            assertTrue(!h.honest.closed, "fsm=$fsm: a decline closed the session")
            val out = w.b.rows().filter { it.meshKind == MeshKind.INFER_SERVED }
            assertEquals(listOf(Phase.OUTCOME), out.map { it.phase }, "fsm=$fsm: one outcome row and no intent row")
            assertEquals(503, out.single().status)
            declines++
        }
        assertEquals(3, declines, "non-vacuity: three non-serving states exercised")
        val w = World(949)
        w.b.serving = openServing()
        val h = HostileClient(w)
        h.establish()
        h.send(Build.offer(attemptIdOf(1), 1))
        assertEquals(0x11, h.frames().single().type, "control: a SERVING lender accepts")
    }

    // ------------------------------------------------------------------------------------------------------------ A-MP-5

    private fun establishedWith(seed: Long, prepare: (World) -> Unit): Pair<World, HostileClient> {
        val w = World(seed)
        w.b.serving = openServing()
        prepare(w)
        val h = HostileClient(w)
        h.establish()
        return w to h
    }

    @Test
    fun aStateThatTheStrictParserWouldRefuseIsAnsweredWithATypedErrorAndTheSessionStays() {
        val cases = listOf<Pair<String, (World) -> Unit>>(
            "commit not hex" to { w -> w.b.live.commit = "4f1c2ab-dirty" },
            "confVersion not semver" to { w -> w.b.live.confVersion = "1.0" },
            "thermal band 3" to { w -> w.b.live.thermalBand = 3 },
        )
        var seen = 0
        for ((i, c) in cases.withIndex()) {
            val (w, h) = establishedWith(950L + i, c.second)
            val baseline = w.b.node.callbackErrors.get()
            h.send(Build.stateReq(1))
            val reply = h.frames().single()
            assertEquals(0x06, reply.type, "${c.first}: no typed answer (${reply.name})")
            assertEquals("PEER_UNAVAILABLE", reply.code, c.first)
            assertTrue(!h.honest.closed, "${c.first}: the session closed")
            assertTrue(w.b.node.callbackErrors.get() > baseline, "${c.first}: the bad host view is counted")
            seen++
        }
        assertEquals(3, seen)
    }

    @Test
    fun moreThanSixtyFourHeldFilesAreAnsweredWithTheFirstSixtyFourSortedNotWithAnException() {
        val files = List(65) { "%064x".format(65 - it) }
        val (_, h) = establishedWith(954) { w -> w.b.live.held = files }
        h.send(Build.stateReq(1))
        val reply = h.frames().single()
        assertEquals(0x21, reply.type, "the STATE was not sent (${reply.name})")
        val held = Regex("\"held\":\\[([^\\]]*)\\]").find(reply.text)!!.groupValues[1].split(",").map { it.trim('"') }
        assertEquals(files.sorted().take(64), held)
    }

    @Test
    fun aHostPortThatThrowsDuringAnOfferIsATypedDeclineWithItsRowNotAnEscape() {
        val faults = listOf<Pair<String, (World) -> Unit>>(
            "policy view" to { w -> w.b.policyFault = IllegalStateException("policy") },
            "engine offered" to { w -> w.b.engine.offeredFault = IllegalStateException("engine") },
            "live view" to { w -> w.b.live.viewFault = IllegalStateException("live") },
        )
        var seen = 0
        for ((i, f) in faults.withIndex()) {
            val (w, h) = establishedWith(960L + i, f.second)
            h.send(Build.offer(attemptIdOf(1), 1))
            val reply = h.frames().single { it.type == 0x12 || it.type == 0x11 }
            assertEquals(0x12, reply.type, "${f.first}: accepted with a failing host port")
            assertEquals("PEER_UNAVAILABLE", reply.code, f.first)
            assertTrue(!h.honest.closed, "${f.first}: the session closed")
            assertTrue(w.b.engine.opened.isEmpty())
            val out = w.b.rows().filter { it.meshKind == MeshKind.INFER_SERVED }
            assertEquals(listOf(Phase.OUTCOME), out.map { it.phase }, "${f.first}: the offer has its row")
            assertTrue(w.b.node.callbackErrors.get() >= 1, "${f.first}: the failure is counted")
            seen++
        }
        assertEquals(3, seen)
    }

    // ------------------------------------------------------------------------------------------------------------ A-MP-6, A-F1, B-MP-3

    private fun race(w: World, a: HostileClient, b: HostileClient, offerA: ByteArray, offerB: ByteArray) {
        a.conn.writeRaw(offerA)
        b.conn.writeRaw(offerB)
        val barrier = CyclicBarrier(2)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val ts = listOf(a, b).map { h ->
            Thread {
                try {
                    barrier.await()
                    h.drain()
                } catch (t: Throwable) {
                    errors += t
                }
            }.also { it.start() }
        }
        ts.forEach { it.join() }
        assertTrue(errors.isEmpty(), "a racing driver threw: ${errors.firstOrNull()}")
    }

    @Test
    fun twoSessionsOfferingAtOnceCannotBothPassAMaxConcurrentOfOne() {
        var both = 0
        var none = 0
        val runs = 400
        repeat(runs) { r ->
            val w = World(1000L + r, limitsB = wide(1))
            w.b.serving = openServing()
            val one = HostileClient(w)
            one.establish(nonceOf(1))
            val two = HostileClient(w)
            two.establish(nonceOf(2))
            one.autoAdvance = false
            two.autoAdvance = false
            race(w, one, two, Build.offer(attemptIdOf(1), 1), Build.offer(attemptIdOf(2), 1))
            val accepted = (one.frames() + two.frames()).count { it.type == 0x11 }
            if (accepted > 1) both++
            if (accepted == 0) none++
            assertTrue(w.b.node.inflight.get() <= 1, "run $r: inflight ${w.b.node.inflight.get()} with maxConcurrent 1")
        }
        assertEquals(0, both, "$both of $runs racing pairs were both accepted with maxConcurrent 1")
        assertEquals(0, none, "$none of $runs racing pairs were both declined")
    }

    @Test
    fun theSequentialControlOfTheRaceAcceptsExactlyOne() {
        repeat(20) { r ->
            val w = World(1500L + r, limitsB = wide(1))
            w.b.serving = openServing()
            val one = HostileClient(w)
            one.establish(nonceOf(1))
            val two = HostileClient(w)
            two.establish(nonceOf(2))
            one.send(Build.offer(attemptIdOf(1), 1))
            two.send(Build.offer(attemptIdOf(2), 1))
            val types = (one.frames() + two.frames()).map { it.type }
            assertEquals(listOf(0x11, 0x12), types, "run $r")
        }
    }

    @Test
    fun twoSessionsOfOnePinCannotTogetherOpenAFifthStream() {
        var five = 0
        val runs = 300
        repeat(runs) { r ->
            val w = World(2000L + r, limitsB = wide(8))
            w.b.serving = openServing()
            val one = HostileClient(w)
            one.establish(nonceOf(1))
            val two = HostileClient(w)
            two.establish(nonceOf(2))
            one.autoAdvance = false
            two.autoAdvance = false
            for (n in 0 until 3) one.send(Build.offer(attemptIdOf(10 + n), 1L + 2 * n))
            assertEquals(List(3) { 0x11 }, one.frames().map { it.type })
            race(w, one, two, Build.offer(attemptIdOf(20), 7), Build.offer(attemptIdOf(21), 1))
            one.frames()
            two.frames()
            val served = w.b.node.servedOf(w.a.pin).get()
            if (served > 4) five++
            assertTrue(served <= 4, "run $r: $served streams of one pin")
        }
        assertEquals(0, five, "$five of $runs racing pairs opened a fifth stream of one pin")
    }

    // ------------------------------------------------------------------------------------------------------------ B-TL-1

    @Test
    fun aRevokeInsideTheAcceptedCallbackStopsTheBodyFromLeaving() {
        val w = World(4711)
        w.b.serving = openServing()
        val (sa, _) = w.connect()
        w.pump()
        var outcome: AttemptOutcome? = null
        val l = object : AttemptListener {
            override fun onAccepted(accept: InferAccept): Boolean {
                w.a.registry.revoke(w.b.pin, CLOCK_BASE + 10)
                return true
            }

            override fun onFinished(outcome: AttemptOutcome) {
                this@LenderFixesTest.lastOutcome = outcome
            }
        }
        sa.offer(spec(), "SECRET-PROMPT".toByteArray(), l)
        w.pump()
        outcome = lastOutcome
        assertTrue(w.b.engine.opened.isEmpty(), "the engine of a revoked lender was opened with the prompt")
        assertTrue(sa.closed, "the session of a revoked peer stayed open")
        assertEquals(599, assertNotNull(outcome).status)
        assertTrue(w.log.all<Ev.Write>().none { String(it.bytes, Charsets.ISO_8859_1).contains("SECRET-PROMPT") }, "the prompt crossed the wire")
    }

    private var lastOutcome: AttemptOutcome? = null

    // ------------------------------------------------------------------------------------------------------------ B-MP-8

    private fun seqOf(f: Fr): Long = Regex("\"seq\":(\\d+)").find(f.text)!!.groupValues[1].toLong()

    @Test
    fun theLiveStateSequenceOfOneRequesterDoesNotCountWhatOtherRequestersDid() {
        val w = World(970)
        w.b.serving = openServing()
        val third = randomPin(77)
        w.b.pair(third)
        val one = HostileClient(w)
        one.establish(nonceOf(1))
        val other = HostileClient(w, peerPin = third)
        other.establish(nonceOf(2))
        one.send(Build.stateReq(1))
        val first = seqOf(one.frames().single { it.type == 0x21 })
        repeat(5) {
            other.send(Build.stateReq(1 + 2L * it))
            other.frames()
        }
        one.send(Build.stateReq(3))
        val second = seqOf(one.frames().single { it.type == 0x21 })
        assertEquals(first + 1, second, "the sequence of one requester jumped by what another requester did")
    }

    // ------------------------------------------------------------------------------------------------------------ the maximum session age

    private val maxAge = 1_800_000L
    private val grace = 60_000L

    private fun lenderWithTwoOpenAttempts(seed: Long): Triple<World, HostileClient, Long> {
        val w = World(seed, limitsB = wide(8))
        w.b.serving = openServing()
        val h = HostileClient(w)
        h.establish()
        h.autoAdvance = false
        val est = h.honest.lastStreamActivity
        h.send(Build.offer(attemptIdOf(1), 1))
        h.send(Build.body(1))
        h.frames()
        return Triple(w, h, est)
    }

    @Test
    fun aLenderPastMaxAgeDeclinesNewOffersGivesInFlightStreamsSixtySecondsAndThenCancelsThemAndCloses() {
        val (w, h, est) = lenderWithTwoOpenAttempts(980)
        assertEquals(1, w.b.engine.opened.size)
        w.b.advance(est + maxAge - 10_000 - w.b.peek())
        h.honest.tick(w.b.peek())
        h.send(Build.offer(attemptIdOf(2), 3))
        assertEquals(0x11, h.frames().single().type, "an offer before the maximum age is accepted")
        h.send(Build.body(3))
        assertEquals(2, w.b.engine.opened.size)
        w.b.advance(est + maxAge + 1_000 - w.b.peek())
        h.honest.tick(w.b.peek())
        assertTrue(!h.honest.closed, "streams are open: the grace has begun, nothing is cut yet")
        h.send(Build.offer(attemptIdOf(3), 5))
        val late = h.frames().single()
        assertEquals(0x12, late.type, "an offer after the maximum age was ${late.name}")
        assertEquals("PEER_UNAVAILABLE", late.code)
        w.b.advance(est + maxAge + grace - 1 - w.b.peek())
        h.honest.tick(w.b.peek())
        assertTrue(!h.honest.closed, "the grace ended one millisecond early")
        w.b.advance(1 + 3)
        h.honest.tick(w.b.peek())
        assertTrue(h.honest.closed, "the in-flight streams outlived the maximum age plus the grace")
        assertEquals(listOf("GOAWAY:max-age"), w.b.rows().filter { it.meshCode?.startsWith("GOAWAY") == true }.map { it.meshCode })
        assertEquals(2, w.b.engine.cancelled.size, "the running engines were not cancelled")
        val interrupted = w.b.rows().filter { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.OUTCOME && it.meshCode == "INTERRUPTED" }
        assertEquals(2, interrupted.size, "both open attempts end with an interrupted outcome row")
    }

    @Test
    fun aRequesterPastMaxAgeOpensNoNewStreamAndTheSessionEndsWithinTheGrace() {
        val w = World(981, maxChunk = 1 shl 20, limitsB = wide(8))
        w.b.serving = openServing()
        w.b.engine.script = { listOf(EngineEvent.Head(200, "m1")) + List(100_000) { EngineEvent.Chunk(ByteArray(4)) } }
        val (sa, sb) = w.connect()
        w.pump(1_000)
        val est = sa.lastStreamActivity
        val rec = Recorder()
        sa.offer(spec(), "{}".toByteArray(), rec)
        fun step() {
            w.a.advance(20_000)
            w.b.advance(20_000)
            sa.tick(w.a.peek())
            sb.tick(w.b.peek())
            repeat(3) { w.pumpOnce() }
        }
        repeat(3) { w.pumpOnce() }
        assertEquals(1, sa.openStreams(), "the first attempt is in flight")
        while (w.a.peek() - est < maxAge) {
            step()
            assertTrue(!sa.closed && !sb.closed, "a streaming session closed before its maximum age (sa=${sa.closed} sb=${sb.closed} age=${w.a.peek() - est} a=${w.a.rows().takeLast(3).map { it.meshKind to it.meshCode }} b=${w.b.rows().takeLast(3).map { it.meshKind to it.meshCode }})")
        }
        assertTrue(!sa.closed && !sb.closed, "streams are open: the grace has begun")
        assertFailsWith<SessionClosedForSend>("a requester opened a stream on a session past its maximum age") {
            sa.offer(spec(index = 1), "{}".toByteArray(), Recorder())
        }
        var state: StateResult? = null
        sa.requestState { state = it }
        assertTrue(state is StateResult.Lost, "a state request on a session past its maximum age is not sent")
        while (!sa.closed && w.a.peek() - est < maxAge + grace + 120_000) step()
        assertTrue(sa.closed, "the session outlived the maximum age plus the grace")
        val closedAt = w.a.peek() - est
        assertTrue(closedAt >= maxAge + grace - 1_000, "the session was cut inside the grace (${closedAt}ms)")
        assertTrue(closedAt <= maxAge + grace + 60_000, "the session closed late (${closedAt}ms)")
        w.pump(5_000)
        assertTrue(sb.closed)
        assertEquals(599, assertNotNull(rec.outcome).status)
    }

    @Test
    fun theSessionCloseRuleAndTheQuiescenceLawAgreeOnEveryBoundary() {
        val cases = listOf(
            Triple(0L, 1_000L, 0), Triple(299_999L, 299_999L, 0), Triple(300_000L, 300_000L, 0), Triple(maxAge - 1, maxAge - 1, 0),
            Triple(maxAge, maxAge, 0), Triple(0L, maxAge - 1, 1), Triple(0L, maxAge, 1), Triple(0L, maxAge + grace - 1, 1), Triple(0L, maxAge + grace, 1),
        )
        var checked = 0
        for ((n, c) in cases.withIndex()) {
            val (w, h, est) = if (c.third == 1) lenderWithTwoOpenAttempts(990L + n) else Triple(World(990L + n), null, 0L)
            val session: Session
            val clock: Kit
            val base: Long
            if (h != null) {
                session = h.honest
                clock = w.b
                base = est
            } else {
                val (sa, _) = w.connect()
                w.pump()
                session = sa
                clock = w.a
                base = sa.lastStreamActivity
            }
            clock.advance(base + c.second - clock.peek())
            session.tick(clock.peek())
            val expected = xyz.mdhv.asom.lab.policy.Quiescence.sessionShouldClose(c.first, c.second, c.third)
            assertEquals(expected, session.closed, "idle=${c.first} age=${c.second} streams=${c.third}: Quiescence says $expected")
            checked++
        }
        assertEquals(cases.size, checked)
    }

    // ------------------------------------------------------------------------------------------------------------ B-F2

    private fun wedgedWithBodySent(seed: Long): Triple<World, HostileServer, String> {
        val w = World(seed)
        val hs = HostileServer(w)
        hs.establish()
        hs.frames()
        val rec = Recorder()
        val id = hs.honest.offer(spec(), "{}".toByteArray(), rec)
        hs.frames()
        hs.send(Build.msg(InferAccept(id, "a".repeat(64), "m1", null), 1))
        hs.frames()
        hs.send(xyz.mdhv.asom.lab.proto.session.Frames.encode(0x14, 1, """{"attemptId":"$id","engine":"local","servedModel":"m1","status":200}"""))
        return Triple(w, hs, id)
    }

    @Test
    fun aLenderThatDripsEmptyChunksDoesNotKeepTheAttemptAlivePastItsDeadline() {
        val (w, hs, id) = wedgedWithBodySent(1100)
        var cancel: Fr? = null
        var elapsed = 0L
        repeat(10) {
            w.a.advance(59_000)
            elapsed += 59_000
            hs.honest.tick(w.a.peek())
            hs.send(Frames.encode(0x15, 1, ByteArray(0)))
            hs.frames().firstOrNull { it.type == 0x17 }?.let { if (cancel == null) cancel = it }
        }
        val c = assertNotNull(cancel, "no CANCEL after ${elapsed}ms of empty chunks")
        assertEquals("deadline", c.reason)
        assertEquals(id, c.attemptId)
    }

    @Test
    fun aLenderThatDripsOneByteChunksIsStillBoundedByTheMaximumSessionAge() {
        val (w, hs, _) = wedgedWithBodySent(1101)
        val est = hs.honest.lastStreamActivity
        var elapsed = 0L
        while (!hs.honest.closed && elapsed < 2 * maxAge) {
            w.a.advance(30_000)
            elapsed += 30_000
            hs.honest.tick(w.a.peek())
            if (!hs.honest.closed) hs.send(Frames.encode(0x15, 1, ByteArray(1)))
        }
        assertTrue(hs.honest.closed, "a dripping lender kept the session open for ${elapsed}ms")
        assertTrue(w.a.peek() - est <= maxAge + grace + 60_000, "the session closed only after ${w.a.peek() - est}ms")
    }
}
