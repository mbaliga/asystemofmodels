package xyz.mdhv.asom.lab.proto.integration

import java.util.concurrent.atomic.AtomicInteger
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.proto.session.AttemptListener
import xyz.mdhv.asom.lab.proto.session.AttemptOutcome
import xyz.mdhv.asom.lab.proto.session.Build
import xyz.mdhv.asom.lab.proto.session.Counts
import xyz.mdhv.asom.lab.proto.session.EngineEvent
import xyz.mdhv.asom.lab.proto.session.ManifestResult
import xyz.mdhv.asom.lab.proto.session.StateResult
import xyz.mdhv.asom.lab.proto.session.spec
import xyz.mdhv.asom.lab.proto.wire.CancelReason
import xyz.mdhv.asom.lab.proto.wire.GoAwayReason
import xyz.mdhv.asom.lab.proto.wire.InferAccept
import xyz.mdhv.asom.lab.proto.wire.InferHead
import xyz.mdhv.asom.lab.proto.wire.Terminal

/** An attempt listener whose completion a test thread can wait for. */
class Waiting : AttemptListener {
    @Volatile
    var outcome: AttemptOutcome? = null

    @Volatile
    var head: Int? = null
    val chunks = AtomicInteger()
    val bytes = java.io.ByteArrayOutputStream()

    @Volatile
    var onChunkHook: (Int) -> Unit = {}

    override fun onAccepted(accept: InferAccept): Boolean = true

    override fun onHead(head: InferHead) {
        this.head = head.status
    }

    override fun onChunk(bytes: ByteArray) {
        synchronized(this.bytes) { this.bytes.write(bytes) }
        onChunkHook(chunks.incrementAndGet())
    }

    override fun onFinished(outcome: AttemptOutcome) {
        this.outcome = outcome
    }

    fun await(what: String = "the attempt to finish"): AttemptOutcome {
        Wait.until(what) { outcome != null }
        return outcome!!
    }

    fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8) }
}

class LifecycleResult(val rowsA: List<LabRouteRecord>, val rowsB: List<LabRouteRecord>, val counts: Counts, val l15: L15Stats)

/**
 * LAB_SPEC 7.6 walked once over a real two-node TLS session: the DIAL intent and outcome, SESSION open, HELLO and HELLO_ACK, STATE_REQ and STATE, MANIFEST_REQ and
 * MANIFEST (signed by a test provider and verified by the lab verifier), one served attempt (OFFER, ACCEPT, BODY, HEAD, CHUNK, END), one cancelled attempt, an
 * extension frame, GOAWAY and the SESSION close rows. Every row is read back from the real JSONL file.
 */
object Lifecycle {
    fun run(seed: Long = 1): LifecycleResult {
        TlsWorld(seed).use { w ->
            val link = w.connect()
            w.awaitEstablished(link)
            val sa = link.sessionA!!
            val sb = link.sessionB!!

            var state: StateResult? = null
            sa.requestState { state = it }
            Wait.until("STATE") { state != null }
            check(state is StateResult.Ok) { "STATE: $state" }

            var manifest: ManifestResult? = null
            sa.requestManifest(ByteArray(32) { (it + 1).toByte() }) { manifest = it }
            Wait.until("MANIFEST") { manifest != null }
            check(manifest is ManifestResult.Accepted) { "MANIFEST: ${(manifest as? ManifestResult.Rejected)?.code} $manifest" }

            val served = Waiting()
            val body = "{\"messages\":[{\"role\":\"user\",\"content\":\"lifecycle\"}]}".toByteArray()
            w.noteBody(body, "req-served")
            sa.offer(spec(requestId = "req-served"), body, served)
            val o = served.await("the served attempt")
            check(o.status == 200 && o.terminal == Terminal.DONE) { "served attempt: ${o.status} ${o.terminal}" }
            check(served.text() == "hello world") { "chunks: ${served.text()}" }

            w.b.engine.script = { listOf(EngineEvent.Head(200, "m1")) + List(400) { EngineEvent.Chunk(ByteArray(16) { 1 }) } + EngineEvent.End(Terminal.DONE, 200, 1) }
            w.b.engine.paceNanos = PacedEngine.DEFAULT_PACE_NANOS
            val cancelled = Waiting()
            val cancelBody = "{\"messages\":[]}".toByteArray()
            w.noteBody(cancelBody, "req-cancel")
            val id = sa.offer(spec(index = 1, requestId = "req-cancel"), cancelBody, cancelled)
            cancelled.onChunkHook = { n -> if (n == 3) sa.cancel(id, CancelReason.CLIENT_GONE) }
            val co = cancelled.await("the cancelled attempt")
            check(co.terminal == Terminal.CANCELLED) { "cancelled attempt: ${co.terminal} ${co.status}" }
            w.b.engine.paceNanos = 0

            val extensionRows = { w.b.rows().count { it.meshCode == "EXT_IGNORED" } }
            link.connA!!.writeRaw(Build.ext(0x91, 0, ByteArray(7) { 3 }))
            Wait.until("EXT_IGNORED") { extensionRows() == 1 }

            sa.goAway(GoAwayReason.SHUTDOWN)
            Wait.until("both sessions closed") { sa.closed && sb.closed }
            Wait.stable("the ledgers") { w.a.rows().size to w.b.rows().size }
            Wait.until("the connections drained") { link.connA!!.closedByUs && link.connB!!.closedByUs }

            val counts = Counts()
            val l15 = L15Stats()
            TlsOracle.check(link, strict = true, c = counts, l15 = l15, verdicts = listOf("VERIFIED"))
            return LifecycleResult(w.a.rows(), w.b.rows(), counts, l15)
        }
    }
}
