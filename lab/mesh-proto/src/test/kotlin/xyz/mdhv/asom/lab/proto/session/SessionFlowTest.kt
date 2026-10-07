package xyz.mdhv.asom.lab.proto.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.wire.CancelReason
import xyz.mdhv.asom.lab.proto.wire.InferOp
import xyz.mdhv.asom.lab.proto.wire.MeshError
import xyz.mdhv.asom.lab.proto.wire.Terminal

class Recorder : AttemptListener {
    var accepted = false
    var head: Int? = null
    val chunks = java.io.ByteArrayOutputStream()
    var outcome: AttemptOutcome? = null

    override fun onAccepted(accept: xyz.mdhv.asom.lab.proto.wire.InferAccept): Boolean = true.also { accepted = true }

    override fun onHead(head: xyz.mdhv.asom.lab.proto.wire.InferHead) {
        this.head = head.status
    }

    override fun onChunk(bytes: ByteArray) {
        chunks.write(bytes)
    }

    override fun onFinished(outcome: AttemptOutcome) {
        this.outcome = outcome
    }
}

fun spec(model: String = "m1", index: Int = 0, requestId: String? = "req-1", stream: Boolean = true) =
    OfferSpec("com.example.app", requestId, index, model, InferOp.CHAT, 60_000, 100, 256, stream)

class SessionFlowTest {
    private fun connected(seed: Long = 1): Triple<World, Session, Session> {
        val w = World(seed)
        val (a, b) = w.connect()
        w.pump()
        return Triple(w, a, b)
    }

    @Test
    fun theHandshakeEstablishesBothRolesAndWritesTheRowsOfTheTable() {
        val (w, a, b) = connected()
        assertTrue(a.established && b.established)
        assertEquals(setOf(xyz.mdhv.asom.lab.proto.wire.Scope.INFER, xyz.mdhv.asom.lab.proto.wire.Scope.MANIFEST, xyz.mdhv.asom.lab.proto.wire.Scope.STATE), a.granted)
        assertNotNull(a.limits)
        val rowsA = w.a.rows().map { it.meshKind to it.meshCode }
        assertEquals(
            listOf(MeshKind.DIAL to null, MeshKind.DIAL to "connected", MeshKind.CONTROL to "HELLO", MeshKind.CONTROL to "HELLO_ACK"),
            rowsA,
        )
        val rowsB = w.b.rows().map { it.meshKind to it.meshCode }
        assertEquals(listOf(MeshKind.SESSION to "established", MeshKind.CONTROL to "HELLO", MeshKind.CONTROL to "HELLO_ACK"), rowsB)
        a.close()
        w.pump()
        RunOracle(w.log, w, strict = true).checkAll()
    }

    @Test
    fun aServedAttemptStreamsAndEveryRowIsWhereTheTableSaysItIs() {
        val (w, a, b) = connected()
        val rec = Recorder()
        a.offer(spec(), "{\"messages\":[]}".toByteArray(), rec)
        w.pump()
        val o = assertNotNull(rec.outcome)
        assertEquals(200, o.status)
        assertEquals(Terminal.DONE, o.terminal)
        assertEquals("hello world", rec.chunks.toString())
        assertEquals(200, rec.head)
        assertEquals(1, w.b.engine.opened.size)
        val b1 = w.b.rows().filter { it.meshKind == MeshKind.INFER_SERVED }
        assertEquals(listOf(Phase.INTENT, Phase.OUTCOME), b1.map { it.phase })
        a.close()
        w.pump()
        RunOracle(w.log, w, strict = true).checkAll()
    }

    @Test
    fun aDeclinedAttemptHasAnOutcomeOnlyOnTheLender() {
        val (w, a, b) = connected()
        val rec = Recorder()
        a.offer(spec(model = "nope"), "{}".toByteArray(), rec)
        w.pump()
        val o = assertNotNull(rec.outcome)
        assertEquals(503, o.status)
        assertEquals("MODEL_NOT_OFFERED", o.meshCode)
        assertTrue(w.b.engine.opened.isEmpty())
        assertEquals(listOf(Phase.OUTCOME), w.b.rows().filter { it.meshKind == MeshKind.INFER_SERVED }.map { it.phase })
        a.close()
        w.pump()
        RunOracle(w.log, w, strict = true).checkAll()
    }

    @Test
    fun stateAndManifestRoundTrip() {
        val (w, a, b) = connected()
        var st: StateResult? = null
        a.requestState { st = it }
        var mf: ManifestResult? = null
        val challenge = ByteArray(32) { it.toByte() }
        a.requestManifest(challenge) { mf = it }
        w.pump()
        assertTrue(st is StateResult.Ok)
        assertTrue(mf is ManifestResult.Accepted, "manifest result: ${mf?.let { it::class.simpleName }} ${(mf as? ManifestResult.Rejected)?.code}")
        a.close()
        w.pump()
        RunOracle(w.log, w, strict = true).checkAll()
    }

    @Test
    fun cancelMidStreamEndsWithACancelledOutcome() {
        val (w, a, b) = connected()
        w.b.engine.script = { listOf(xyz.mdhv.asom.lab.proto.session.EngineEvent.Head(200, "m1")) + List(50) { xyz.mdhv.asom.lab.proto.session.EngineEvent.Chunk(ByteArray(10)) } }
        val rec = Recorder()
        val id = a.offer(spec(), "{}".toByteArray(), rec)
        repeat(6) { a.pumpAvailable(); b.pumpAvailable(); a.advance(); b.advance() }
        a.cancel(id, CancelReason.CLIENT_GONE)
        w.pump()
        val o = assertNotNull(rec.outcome)
        assertEquals(Terminal.CANCELLED, o.terminal)
        assertEquals("CANCELLED", o.meshCode)
        a.close()
        w.pump()
        RunOracle(w.log, w, strict = true).checkAll()
    }
}
