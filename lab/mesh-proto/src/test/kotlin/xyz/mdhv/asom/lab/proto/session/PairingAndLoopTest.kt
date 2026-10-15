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
