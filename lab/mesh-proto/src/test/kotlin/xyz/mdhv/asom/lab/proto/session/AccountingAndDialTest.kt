package xyz.mdhv.asom.lab.proto.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.LedgerUnavailableException
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.OverheadBasis
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.wire.GoAwayReason

/** Transport accounting (L-L15 as far as it can be proven without TLS), the DIAL rows (L-L14), and the honest-honest registry paths. */
class AccountingAndDialTest {
    private fun ceil(n: Long) = (n + 16_383L) / 16_384L

    @Test
    fun measuredOverheadIsRecordArithmeticAndTheRowsAreTheExactApplicationBytes() {
        val w = World(3)
        val (a, _) = w.connect()
        w.pump()
        val rec = Recorder()
        a.offer(spec(), ByteArray(40_000) { 'x'.code.toByte() }, rec)
        w.pump()
        assertEquals(200, rec.outcome?.status)
        a.goAway(GoAwayReason.SHUTDOWN)
        w.pump()
        val counts = RunOracle(w.log, w, strict = true).checkAll()
        assertEquals(2, counts.measuredSessions, "both sessions are MEASURED and the sum of rows plus overhead equals the network tap")
        val body = w.log.all<Ev.Write>().flatMap { Frames.parse(it.bytes) }.single { it.type == 0x13 }
        assertTrue(body.app > 2 * 16_384, "the body spans three TLS records")
        val rowA = w.a.rows().filter { it.overheadBytes != null }
        assertEquals(listOf(MeshKind.DIAL, MeshKind.SESSION), rowA.map { it.meshKind })
        assertTrue(rowA.all { it.overheadBasis == OverheadBasis.MEASURED })
        assertEquals(2_700L, rowA[0].overheadBytes, "the DIAL outcome carries the handshake figure")
        println("L-L15 (unit, counting connection, MEASURED): rows + overhead == network tap for ${counts.measuredSessions} sessions; the body of ${body.app} application bytes cost ${22 * ceil(body.app)} bytes of records")
    }

    @Test
    fun aMeterThatReportsOneByteLessPerWriteIsCaught() {
        val w = World(3)
        val (a, _) = w.connect()
        w.links[0].conn.meterBiasPerWrite = 1
        w.pump()
        a.offer(spec(), "{}".toByteArray(), Recorder())
        w.pump()
        a.goAway(GoAwayReason.SHUTDOWN)
        w.pump()
        val e = assertFailsWith<AssertionError> { RunOracle(w.log, w, strict = true).checkAll() }
        assertTrue(e.message!!.contains("overhead") || e.message!!.contains("network tap"), e.message)
    }

    @Test
    fun theEstimatedFormIsUsedWhenTheStackCannotMeasure() {
        val w = World(4, measured = false)
        val (a, _) = w.connect()
        w.pump()
        val rec = Recorder()
        a.offer(spec(), ByteArray(40_000) { 1 }, rec)
        w.pump()
        a.goAway(GoAwayReason.SHUTDOWN)
        w.pump()
        val frames = w.log.all<Ev.Write>().flatMap { Frames.parse(it.bytes) }
        val records = frames.sumOf { 22 * ceil(it.app) }
        val rowsA = w.a.rows()
        val dial = rowsA.single { it.meshKind == MeshKind.DIAL && it.phase == Phase.OUTCOME }
        assertEquals(OverheadBasis.ESTIMATED, dial.overheadBasis)
        assertEquals(8_192L, dial.overheadBytes, "4,096 bytes per direction for the handshake (A36)")
        val open = w.b.rows().single { it.meshKind == MeshKind.SESSION && it.meshCode == "established" }
        assertEquals(OverheadBasis.ESTIMATED to 8_192L, open.overheadBasis to open.overheadBytes)
        val close = (rowsA + w.b.rows()).filter { it.meshKind == MeshKind.SESSION && it.meshCode == "close" }
        assertEquals(2, close.size)
        assertTrue(close.all { it.overheadBasis == OverheadBasis.ESTIMATED })
        assertEquals(records, close[0].overheadBytes, "22 x ceilDiv(application bytes of the flush, 16384) for every frame of the session")
        assertEquals(records, close[1].overheadBytes)
        val counts = RunOracle(w.log, w, strict = true).checkAll()
        assertEquals(0, counts.measuredSessions, "ESTIMATED sessions are excluded from the L-L15 equality, never counted as MEASURED")
    }

    // ------------------------------------------------------------------------------------------------------------ DIAL

    @Test
    fun everyDialOutcomeHasADurableIntentBeforeTheConnectAndTheRightRow() {
        var connects = 0
        for (code in MeshNode.DIAL_CODES) {
            val w = World(5)
            val conns = MemConnection.pair(w.b.pin, w.a.pin, w.log, "A>B", "B<A")
            val report = w.a.node.dial(w.b.pin, "192.168.1.20:11436", "hello") {
                connects++
                w.log.add(Ev.Connect("A", it))
                ConnectResult(code, if (code == "connected") conns.first else null)
            }
            assertEquals(code, report.code)
            val rows = w.a.rows()
            assertEquals(listOf(Phase.INTENT, Phase.OUTCOME), rows.map { it.phase })
            assertTrue(rows.all { it.meshKind == MeshKind.DIAL && it.destAddr == "192.168.1.20:11436" && it.addrSource == "hello" && it.attemptId == report.sessionId && it.sessionId == report.sessionId })
            assertEquals(code, rows[1].meshCode)
            assertEquals(if (code == "connected") 200 else 599, rows[1].status)
            assertEquals(if (code == "connected") 2_700L else null, rows[1].overheadBytes)
            assertNull(rows[0].overheadBytes)
            assertTrue(w.log.all<Ev.Appended>().first { it.row.phase == Phase.INTENT }.seq < w.log.all<Ev.Connect>().single().seq)
            L14.check(w.log)
        }
        assertEquals(MeshNode.DIAL_CODES.size, connects)
    }

    @Test
    fun aDialIntentThatIsNotDurableMakesNoConnect() {
        val w = World(6, failA = FailPlan(setOf(0), sticky = false))
        var connected = false
        assertFailsWith<LedgerUnavailableException> {
            w.a.node.dial(w.b.pin, "10.0.0.2:11436", "user") {
                connected = true
                ConnectResult("refused")
            }
        }
        assertTrue(!connected, "L-L14: no SYN without a durable intent")
        assertTrue(w.a.ledger.unavailable)
    }

    @Test
    fun aDialOutcomeThatIsNotDurableClosesTheNewConnectionBeforeAnyByteCrossesIt() {
        val w = World(7, failA = FailPlan(setOf(1), sticky = false))
        val conns = MemConnection.pair(w.b.pin, w.a.pin, w.log, "A>B", "B<A")
        assertFailsWith<LedgerUnavailableException> { w.a.node.dial(w.b.pin, "10.0.0.2:11436", "qr") { ConnectResult("connected", conns.first) } }
        assertTrue(conns.first.closedByUs)
        assertEquals(0, w.log.all<Ev.Write>().size, "no HELLO, no byte")
    }

    @Test
    fun anInboundRefusalIsCountedInOneRowPerWindowWithNoAddress() {
        val w = World(8)
        repeat(7) { w.b.node.inboundRefused.refused() }
        val row = assertNotNull(w.b.node.inboundRefused.flush(force = true))
        assertEquals(MeshKind.INBOUND_REFUSED, row.meshKind)
        assertEquals("refused:7", row.meshCode)
        assertNull(row.destAddr)
        assertEquals(1, w.b.rows().count { it.meshKind == MeshKind.INBOUND_REFUSED })
    }

    // ------------------------------------------------------------------------------------------------------------ the registry, honest on both ends

    @Test
    fun aRevocationOnTheLenderMidStreamEndsBothSidesWithTheRightRows() {
        val w = World(9)
        val (a, b) = w.connect()
        w.pump()
        w.b.engine.script = { listOf(EngineEvent.Head(200, "m1")) + List(40) { EngineEvent.Chunk(ByteArray(10)) } }
        val rec = Recorder()
        a.offer(spec(), "{}".toByteArray(), rec)
        while (w.b.engine.opened.isEmpty()) w.pumpOnce()
        repeat(3) { w.pumpOnce() }
        assertTrue(rec.outcome == null, "the attempt is still streaming")
        w.b.registry.revoke(w.a.pin, CLOCK_BASE)
        w.pump()
        assertTrue(a.closed && b.closed)
        assertEquals(599, rec.outcome?.status, "the requester treats it as a peer failure")
        assertEquals("PEER_UNREACHABLE", rec.outcome?.meshCode)
        assertEquals("INTERRUPTED", w.b.rows().last { it.meshKind == MeshKind.INFER_SERVED }.meshCode)
        assertTrue(w.b.rows().any { it.meshCode == "GOAWAY:revoked" } && w.a.rows().any { it.meshCode == "GOAWAY:revoked" })
        RunOracle(w.log, w, strict = true).checkAll()
    }

    @Test
    fun aPauseOnTheRequesterSideSendsGoawaySuspendedAndTheLenderServesNoMore() {
        val w = World(10)
        val (a, b) = w.connect()
        w.pump()
        w.a.registry.pause(w.b.pin, CLOCK_BASE)
        w.pump()
        assertTrue(a.closed && b.closed)
        assertTrue(w.a.rows().any { it.meshCode == "GOAWAY:suspended" })
        RunOracle(w.log, w, strict = true).checkAll()
    }

    @Test
    fun theGrantedScopesAndTheLimitsOfHelloAckReachTheRequester() {
        val w = World(11)
        w.b.registry.setInboundScopes(w.a.pin, setOf(xyz.mdhv.asom.lab.proto.trust.Scope.INFER))
        val (a, _) = w.connect()
        w.pump()
        assertEquals(setOf(xyz.mdhv.asom.lab.proto.wire.Scope.INFER), a.granted)
        assertEquals(1L, a.limits?.maxConcurrent)
        assertEquals(30L, a.limits?.rpm)
        val ack = w.log.all<Ev.Write>().flatMap { Frames.parse(it.bytes) }.single { it.type == 2 }
        assertTrue(!ack.text.contains("\"st\""), "no st without scope state")
    }

    @Test
    fun theLiveStateThatReachesAStateScopedPeerHasNoPresenceField() {
        val w = World(12)
        val (a, _) = w.connect()
        w.pump()
        var st: StateResult? = null
        a.requestState { st = it }
        w.pump()
        assertTrue(st is StateResult.Ok)
        val frame = w.log.all<Ev.Write>().flatMap { Frames.parse(it.bytes) }.single { it.type == 0x21 }
        for (needle in listOf("SECRET", "123456789", "\"user\"", "inflight", "screen", "loaded", "\"busy")) assertTrue(!frame.text.contains(needle), "STATE leaks $needle")
        assertEquals(null, xyz.mdhv.asom.lab.policy.ProducerStrict.check(frame.payload))
        assertTrue(frame.text.contains("\"queue\":{\"bucket\":1}"), "the lender's queue includes its own local request: the one LP-1 exception")
    }
}
