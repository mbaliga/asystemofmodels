package xyz.mdhv.asom.lab.proto.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.proto.pairing.PairChallenge
import xyz.mdhv.asom.lab.proto.pairing.PairHello
import xyz.mdhv.asom.lab.proto.pairing.PairMessages
import xyz.mdhv.asom.lab.proto.wire.ConnMode

class PairingAndLoopTest {
    @Test
    fun pairingFramesProduceOnePairingRowPerFramePerNodeAndTheListenerDerivesTheSessionIdFromThePairHelloItReceives() {
        val p = PairWorld(1)
        p.run()
        val derived = Base64Strict.encodeUrlNoPad(p.nonceS.copyOfRange(0, 16))
        val d = p.rowsD()
        assertEquals(MeshKind.SESSION to "pairing", d[0].meshKind to d[0].meshCode, "the listener's first row is the SESSION open row")
        assertTrue(d.drop(1).filter { it.meshKind == MeshKind.PAIRING }.map { it.meshCode }.containsAll(listOf("PAIR_HELLO", "PAIR_CHALLENGE", "PAIR_DECISION", "PAIR_COMMIT", "PAIR_COMMIT_ACK")))
        assertTrue(d.all { it.sessionId == derived }, "the listener's rows, the open row included, carry the id the received PAIR_HELLO names (R4-L-24)")
        val s = p.rowsS()
        assertEquals("PAIR_HELLO" to derived, s.first().meshCode to s.first().attemptId, "the dialler sends PAIR_HELLO, so its first row already carries the derived id as attemptId")
        assertTrue(s.none { it.meshKind == MeshKind.SESSION && it.meshCode == "pairing" }, "a dialler writes DIAL rows, not a SESSION open")
        assertTrue(s.all { it.sessionId != derived }, "the dialler's rows stay under its own connection id")
        assertTrue(s.last().attemptId == derived)
        PairOracle.check(p, Counts())
    }

    @Test
    fun aNonPairingFrameOnAPairingConnectionIsAProtocolErrorAndCloses() {
        val p = PairWorld(2)
        p.connS.writeRaw(Frames.encode(0x10, 1, """{"v":1}"""))
        p.pump()
        assertTrue(p.d.isClosed)
        val written = p.log.all<Ev.Write>().filter { it.conn == "D<S" }.flatMap { Frames.parse(it.bytes) }.single()
        assertEquals("PROTOCOL_ERROR", written.code)
        assertEquals(listOf("open:pairing", "ERROR:PROTOCOL_ERROR", "close"), p.rowsD().map { if (it.meshKind == MeshKind.SESSION && it.meshCode == "pairing") "open:pairing" else it.meshCode!! })
    }

    @Test
    fun aListenerThatReceivesAColdFrameFirstOpensItsSessionUnderALocalId() {
        val p = PairWorld(5)
        val challenge = Frames.encode(0x31, 1, PairMessages.encodeChallenge(PairChallenge(ByteArray(32) { 5 }, "Phone", "android", "strongbox")))
        p.connS.writeRaw(challenge)
        p.pump()
        assertTrue(p.d.isClosed, "S may not send a challenge, and nothing may precede the hello")
        val derived = Base64Strict.encodeUrlNoPad(p.nonceS.copyOfRange(0, 16))
        val d = p.rowsD()
        assertEquals(listOf("pairing", "ERROR:PROTOCOL_ERROR", "close"), d.map { it.meshCode })
        assertTrue(d.map { it.sessionId }.distinct().size == 1 && d[0].sessionId != derived)
    }

    @Test
    fun aListenerPairHelloRowThatIsNotDurableDeliversAndSendsNothingFurther() {
        val p = PairWorld(4, failD = FailPlan(setOf(1), sticky = false))
        p.s.send(0x30, PairMessages.encodeHello(PairHello(p.nonceS, ByteArray(32), "Desk", "linux", "file", emptyList())))
        p.pump()
        assertTrue(p.d.isClosed)
        assertEquals(emptyList(), p.receivedByD.map { it.first }, "the PAIR_HELLO row failed, so the frame was never delivered")
        assertEquals(0, p.log.all<Ev.Write>().filter { it.conn == "D<S" }.size, "and the listener sent nothing")
        assertEquals(listOf("pairing", "close:ledger-failure"), p.rowsD().map { it.meshCode })
    }

    @Test
    fun aDiallerPairingRowThatIsNotDurableSendsNothingFurther() {
        val p = PairWorld(3, FailPlan(setOf(0), sticky = false))
        p.s.send(0x30, PairMessages.encodeHello(PairHello(p.nonceS, ByteArray(32), "Desk", "linux", "file", emptyList())))
        p.pump()
        assertTrue(p.s.isClosed)
        assertEquals(0, p.log.all<Ev.Write>().size, "the PAIR_HELLO row failed, so the frame was never sent")
        assertEquals(listOf("close:ledger-failure"), p.rowsS().map { it.meshCode })
    }

    @Test
    fun extensionFramesThatArriveBeforeThePairHelloAreRowedAfterTheOpenRowUnderTheIdTheHelloNames() {
        for (early in listOf(1, 3)) {
            val p = PairWorld(30L + early)
            p.run(earlyExtensions = early)
            val derived = Base64Strict.encodeUrlNoPad(p.nonceS.copyOfRange(0, 16))
            val d = p.rowsD()
            assertEquals(MeshKind.SESSION to "pairing", d[0].meshKind to d[0].meshCode)
            assertEquals(List(early) { "EXT_IGNORED" }, d.drop(1).take(early).map { it.meshCode }, "the held extension frames are rowed right after the open row")
            assertEquals("PAIR_HELLO", d[1 + early].meshCode)
            assertTrue(d.all { it.sessionId == derived }, "every listener row, the early extension rows included, carries the id the PAIR_HELLO names (R4-L-24)")
            PairOracle.check(p, Counts())
        }
    }

    @Test
    fun aListenerThatIsClosedByEarlyExtensionsAloneRowsThemUnderItsLocalId() {
        val p = PairWorld(33)
        p.connS.writeRaw(Build.ext(0x91, 0, ByteArray(3)))
        p.pump()
        p.d.close()
        val d = p.rowsD()
        assertEquals(listOf("pairing", "EXT_IGNORED", "close"), d.map { it.meshCode })
        assertEquals(1, d.map { it.sessionId }.distinct().size)
    }

    @Test
    fun aDiallerWhoseHelloRowIsNotDurableHasNoJoinIdOnItsCloseRow() {
        val p = PairWorld(3, FailPlan(setOf(0), sticky = false))
        p.s.send(0x30, PairMessages.encodeHello(PairHello(p.nonceS, ByteArray(32), "Desk", "linux", "file", emptyList())))
        val close = p.rowsS().single()
        assertEquals("close:ledger-failure", close.meshCode)
        assertEquals(null, close.attemptId, "the hello never crossed, so the close row joins no session the listener saw")
    }

    private fun helloFrame(nonceS: ByteArray) = Frames.encode(0x30, 1, PairMessages.encodeHello(PairHello(nonceS, ByteArray(32), "Desk", "linux", "file", emptyList())))

    private class Listener(val ledger: xyz.mdhv.asom.lab.ledger.NodeLedger, val mem: xyz.mdhv.asom.lab.ledger.MemorySink, val log: Log) {
        fun open(n: Int, claims: SessionIdClaims, hello: ByteArray, onFrame: (Int, ByteArray) -> Unit = { _, _ -> }): Pair<PairingChannel, MemConnection> {
            val conns = MemConnection.pair(null, null, log, "S$n>D", "D$n<S", ConnMode.PAIRING)
            val d = PairingChannel(ledger, conns.second, SeededIds(100L + n), claims)
            d.onFrame = onFrame
            conns.first.writeRaw(hello)
            d.pumpAvailable(4096)
            return d to conns.first
        }
    }

    private fun listener(): Listener {
        val log = Log()
        val mem = xyz.mdhv.asom.lab.ledger.MemorySink()
        return Listener(xyz.mdhv.asom.lab.ledger.NodeLedger("D", SpySink("D", mem, log)) { CLOCK_BASE + log.events.size }, mem, log)
    }

    @Test
    fun aSecondPairHelloWithTheSameNonceIsRefusedUnderALocalIdAndNeverSharesTheFirstSessionsId() {
        val l = listener()
        val claims = IdRegistry()
        val hello = helloFrame(ByteArray(32) { 7 })
        val derived = Base64Strict.encodeUrlNoPad(ByteArray(16) { 7 })
        val first = l.open(1, claims, hello)
        val second = l.open(2, claims, hello)
        val third = l.open(3, claims, hello)
        assertEquals(derived, first.first.sessionId)
        assertTrue(second.first.sessionId != derived && third.first.sessionId != derived && second.first.sessionId != third.first.sessionId, "a repeated nonce names no session")
        assertTrue(second.first.isClosed && third.first.isClosed, "the repeat is refused and closed")
        assertTrue(!first.first.isClosed, "the first connection is untouched")
        val byId = l.mem.all().groupBy { it.sessionId }
        assertEquals(3, byId.size)
        assertEquals(listOf("pairing", "PAIR_HELLO"), byId.getValue(derived).map { it.meshCode })
        for (c in listOf(second.first, third.first)) {
            assertEquals(listOf("pairing", "ERROR:PROTOCOL_ERROR", "close"), byId.getValue(c.sessionId).map { it.meshCode })
        }
        val refusal = l.log.all<Ev.Write>().filter { it.conn == "D2<S" }.flatMap { Frames.parse(it.bytes) }.single()
        assertEquals("PROTOCOL_ERROR", refusal.code)
    }

    @Test
    fun aPairHelloThatNamesTheIdOfAnEstablishedSessionOrOfTheNodesOwnDialIsRefusedAndItsRowsStayOutOfThatSession() {
        val w = World(41)
        val (cc, _) = MemConnection.pair(w.b.pin, w.a.pin, w.log, "A>B", "B<A")
        val report = w.a.node.dial(w.b.pin, "10.0.0.2:11436", "qr") { ConnectResult("connected", cc) }
        val known = java.util.Base64.getUrlDecoder().decode(report.sessionId)
        val l = listener()
        val received = ArrayList<Int>()
        val (d, _) = l.open(1, SessionIdClaims { w.a.node.claimSessionId(it) }, helloFrame(known + ByteArray(16) { 3 })) { t, _ -> received += t }
        assertTrue(d.isClosed)
        assertTrue(l.mem.all().none { it.sessionId == report.sessionId }, "no pairing row joins the session this node dialled")
        assertEquals(listOf("pairing", "ERROR:PROTOCOL_ERROR", "close"), l.mem.all().map { it.meshCode })
        assertEquals(emptyList(), received)
    }

    @Test
    fun theBlockingReadLoopDrivesTwoSessionsOnThreads() {
        val w = World(20)
        val (cc, sc) = MemConnection.pair(w.b.pin, w.a.pin, w.log, "A>B", "B<A", blocking = true)
        val report = w.a.node.dial(w.b.pin, "10.0.0.2:11436", "qr") { ConnectResult("connected", cc) }
        val sb = w.b.node.accept(sc)
        val sa = w.a.node.openDialed(report, w.b.pin)
        w.links += Link("A", sa, cc)
        w.links += Link("B", sb, sc)
        val ta = Thread { sa.runReadLoop() }.also { it.isDaemon = true }
        val tb = Thread { sb.runReadLoop() }.also { it.isDaemon = true }
        val driver = Thread {
            while (!sb.closed) {
                sb.advance()
                Thread.sleep(1)
            }
        }.also { it.isDaemon = true }
        ta.start()
        tb.start()
        driver.start()
        val ready = CountDownLatch(1)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!sa.established && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(sa.established)
        val done = CountDownLatch(1)
        val rec = object : AttemptListener {
            var outcome: AttemptOutcome? = null

            override fun onFinished(outcome: AttemptOutcome) {
                this.outcome = outcome
                done.countDown()
            }
        }
        sa.offer(spec(), "{}".toByteArray(), rec)
        assertTrue(done.await(10, TimeUnit.SECONDS), "the attempt did not finish")
        assertEquals(200, rec.outcome?.status)
        sa.goAway(xyz.mdhv.asom.lab.proto.wire.GoAwayReason.SHUTDOWN)
        ta.join(10_000)
        tb.join(10_000)
        driver.join(10_000)
        ready.countDown()
        assertTrue(!ta.isAlive && !tb.isAlive && sb.closed && sa.closed)
        RunOracle(w.log, w, strict = true).checkAll()
    }

    @Test
    fun aSessionThatNeverSaysHelloIsClosedAtTheHandshakeTimeout() {
        val w = World(21)
        val h = HostileClient(w)
        h.honest.tick(CLOCK_BASE + 100_000)
        assertTrue(h.honest.closed)
        assertEquals(listOf("established", "close"), w.b.rows().map { it.meshCode })
        assertEquals(0, h.frames().size, "a silent peer gets no frame")
    }

    @Test
    fun aPairingModeConnectionIsNotAnEstablishedSession() {
        val w = World(22)
        val (cc, sc) = MemConnection.pair(null, null, w.log, "x", "y", ConnMode.PAIRING)
        assertTrue(runCatching { w.b.node.accept(sc) }.isFailure)
        cc.close()
    }
}
