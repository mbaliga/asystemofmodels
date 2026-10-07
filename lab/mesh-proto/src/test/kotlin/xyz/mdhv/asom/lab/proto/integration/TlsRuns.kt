package xyz.mdhv.asom.lab.proto.integration

import java.util.SplittableRandom
import java.util.concurrent.atomic.AtomicBoolean
import xyz.mdhv.asom.lab.ledger.LedgerUnavailableException
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.proto.session.Build
import xyz.mdhv.asom.lab.proto.session.CLOCK_BASE
import xyz.mdhv.asom.lab.proto.session.Counts
import xyz.mdhv.asom.lab.proto.session.EngineEvent
import xyz.mdhv.asom.lab.proto.session.Ev
import xyz.mdhv.asom.lab.proto.session.FailPlan
import xyz.mdhv.asom.lab.proto.session.ManifestResult
import xyz.mdhv.asom.lab.proto.session.ServingView
import xyz.mdhv.asom.lab.proto.session.Session
import xyz.mdhv.asom.lab.proto.session.SessionClosedForSend
import xyz.mdhv.asom.lab.proto.session.StateResult
import xyz.mdhv.asom.lab.proto.session.spec
import xyz.mdhv.asom.lab.proto.trust.Scope
import xyz.mdhv.asom.lab.proto.wire.CancelReason
import xyz.mdhv.asom.lab.proto.wire.GoAwayReason
import xyz.mdhv.asom.lab.proto.wire.Terminal

/** The outcome of one seeded run over real TLS: the world (kept open for the caller to inspect, closed by [close]) and what the ends were. */
class RunResult(val world: TlsWorld, val link: TlsLink?, val bGrantsA: Set<Scope>, val crashed: String?) : AutoCloseable {
    override fun close() = world.close()
}

object TlsRuns {
    private fun scripts(rnd: SplittableRandom): (xyz.mdhv.asom.lab.proto.session.EngineRequest) -> List<Any> = when (rnd.nextInt(7)) {
        0 -> { _ -> listOf(EngineEvent.Head(200, "m1")) + List(25) { EngineEvent.Chunk(ByteArray(20) { 1 }) } + EngineEvent.End(Terminal.DONE, 200, 7) }
        1 -> { _ -> listOf(EngineEvent.Head(200, "m1"), EngineEvent.Chunk("partial".toByteArray()), IllegalStateException("engine failed")) }
        2 -> { _ -> listOf(EngineEvent.End(Terminal.ERROR, 500, null)) }
        3 -> { _ -> listOf(EngineEvent.Head(200, "m1"), EngineEvent.End(Terminal.OOM, 503, null)) }
        4 -> { _ -> listOf(EngineEvent.Chunk("no head first".toByteArray()), EngineEvent.End(Terminal.INTERRUPTED, 499, null)) }
        else -> { _ -> listOf(EngineEvent.Head(200, "m1"), EngineEvent.Chunk("hello".toByteArray()), EngineEvent.Chunk(" world".toByteArray()), EngineEvent.End(Terminal.DONE, 200, 12)) }
    }

    private val longScript: (xyz.mdhv.asom.lab.proto.session.EngineRequest) -> List<Any> =
        { listOf(EngineEvent.Head(200, "m1")) + List(300) { EngineEvent.Chunk(ByteArray(16) { 1 }) } + EngineEvent.End(Terminal.DONE, 200, 7) }

    /**
     * A seeded session between two honest nodes over real loopback TLS: handshake, offers (served, declined, cancelled, failing), state, manifests, extension
     * frames, notices, serving changes, and a seeded end. Every operation is waited for through its own completion callback, never through a sleep.
     */
    fun clean(seed: Long, failA: FailPlan? = null, failB: FailPlan? = null): RunResult {
        val rnd = SplittableRandom(seed * 7919 + 13)
        val w = TlsWorld(seed, failA, failB)
        val grants = Scope.entries.filter { rnd.nextInt(6) != 0 }.toSet()
        w.b.registry.setInboundScopes(w.a.pin, grants)
        val link = try {
            w.connect()
        } catch (e: LedgerUnavailableException) {
            w.lastAccepted?.session?.let { orphan -> Wait.until("the listener's session to end after the failed dial") { orphan.closed } }
            settle(w)
            return RunResult(w, null, grants, null)
        }
        val sa = link.sessionA ?: return RunResult(w, link, grants, null)
        val sb = link.sessionB
        if (sb != null) Wait.until("the handshake to settle") { sa.established || sa.closed }
        var index = 0
        repeat(1 + rnd.nextInt(6)) {
            if (sa.closed || sb == null || sb.closed) return@repeat
            when (rnd.nextInt(10)) {
                0, 1, 2, 7 -> {
                    val cancelling = rnd.nextInt(5) == 0
                    w.b.engine.script = if (cancelling) longScript else scripts(rnd)
                    w.b.engine.paceNanos = if (cancelling) PacedEngine.DEFAULT_PACE_NANOS else 0
                    val model = listOf("m1", "m1", "m2", "nope")[rnd.nextInt(4)]
                    val prompt = if (rnd.nextInt(8) == 0) 9_000_000 else 100 + rnd.nextInt(200)
                    try {
                        val rec = Waiting()
                        val body = ByteArray(prompt.coerceAtMost(800)) { "abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)].code.toByte() }
                        w.noteBody(body, "req-$index-$seed")
                        val id = sa.offer(spec(model, index++, "req-${index - 1}-$seed", rnd.nextBoolean()), body, rec)
                        if (cancelling) {
                            val after = 1 + rnd.nextInt(4)
                            rec.onChunkHook = { n -> if (n == after) sa.cancel(id, CancelReason.CLIENT_GONE) }
                        }
                        Wait.until("attempt $id to finish") { rec.outcome != null || sa.closed }
                    } catch (e: LedgerUnavailableException) {
                    } catch (e: SessionClosedForSend) {
                    }
                    w.b.engine.paceNanos = 0
                }
                3 -> {
                    var got: StateResult? = null
                    sa.requestState { got = it }
                    Wait.until("a STATE answer") { got != null || sa.closed }
                }
                4 -> {
                    w.b.manifest.available = rnd.nextInt(4) != 0
                    var got: ManifestResult? = null
                    sa.requestManifest(ByteArray(32) { (rnd.nextInt(3) + it).toByte() }) { got = it }
                    Wait.until("a MANIFEST answer") { got != null || sa.closed }
                }
                5 -> {
                    val toB = rnd.nextBoolean()
                    val src = if (toB) link.connA!! else link.connB!!
                    val dst = if (toB) w.b else w.a
                    val n = 1 + rnd.nextInt(3)
                    val before = dst.rows().count { it.meshCode == "EXT_IGNORED" }
                    repeat(n) { src.writeRaw(Build.ext(0x80 + rnd.nextInt(128), rnd.nextLong(1L shl 32), ByteArray(rnd.nextInt(30)))) }
                    Wait.until("$n EXT_IGNORED rows") { dst.rows().count { it.meshCode == "EXT_IGNORED" } >= before + n || sa.closed || sb.closed }
                }
                6 -> {
                    val fromA = rnd.nextBoolean()
                    val s = if (fromA) sa else sb
                    val dst = if (fromA) w.b else w.a
                    if (!s.closed) {
                        val before = dst.rows().count { it.meshKind == MeshKind.REVOCATION }
                        s.sendRevokeNotice()
                        Wait.until("a REVOCATION row") { dst.rows().count { it.meshKind == MeshKind.REVOCATION } > before || sa.closed || sb.closed }
                    }
                }
                8 -> Unit
                else -> w.b.serving = when (rnd.nextInt(3)) {
                    0 -> ServingView(servingConditionsOk = false)
                    1 -> ServingView(presenceActive = true, presenceHoldRemainingMs = 60_000)
                    else -> ServingView()
                }
            }
        }
        when (rnd.nextInt(6)) {
            0 -> sa.goAway(GoAwayReason.SHUTDOWN)
            1 -> sb?.goAway(GoAwayReason.IDLE)
            2 -> w.b.registry.revoke(w.a.pin, CLOCK_BASE)
            3 -> w.a.registry.pause(w.b.pin, CLOCK_BASE)
            4 -> sa.close()
            else -> sb?.close()
        }
        Wait.until("both sessions to close") { sa.closed && (sb == null || sb.closed) }
        settle(w)
        return RunResult(w, link, grants, null)
    }

    /** Waits until neither ledger grows and both streams are done (rows are written asynchronously with respect to the peer's close). */
    fun settle(w: TlsWorld, vararg extra: Pair<String, Session>) {
        awaitCloseRows(w, extra.toList())
        Wait.stable("the ledgers of both nodes") { w.a.rows().size to w.b.rows().size }
    }

    /**
     * A session's `closed` flag can be seen before its SESSION close row (or the failed append of it) reaches the log. For every session of every link that is
     * closed, waits (up to 5 s, then lets the oracle report what is really missing) until that row or failure is logged.
     */
    private fun awaitCloseRows(w: TlsWorld, extra: List<Pair<String, Session>>) {
        val deadline = System.nanoTime() + 5_000L * 1_000_000L
        val all = w.links.flatMap { listOf("A" to it.sessionA, "B" to it.sessionB) } + extra
        for ((node, session) in all) {
            if (session == null) continue
            while (session.closed && System.nanoTime() < deadline) {
                val ev = w.log.snapshot()
                val done = ev.filterIsInstance<Ev.Appended>().any { it.node == node && it.row.sessionId == session.sessionId && it.row.meshKind == MeshKind.SESSION && it.row.meshCode!!.startsWith("close") } ||
                    ev.filterIsInstance<Ev.AppendFailed>().any { it.node == node && it.row.sessionId == session.sessionId && it.row.meshKind == MeshKind.SESSION }
                if (done) break
                Thread.sleep(2)
            }
        }
    }

    /**
     * A run cut mid-stream by a peer reset: a long paced stream is running when one node dies (its sink stops being durable and its socket is reset with
     * SO_LINGER 0, no close_notify). [crashDialer] picks the node. The survivor must end its session cleanly with MEASURED rows.
     */
    fun cutByReset(seed: Long, crashDialer: Boolean, chunksBeforeCut: Int): RunResult {
        val w = TlsWorld(seed)
        val link = w.connect()
        w.awaitEstablished(link)
        val sa = link.sessionA!!
        val sb = link.sessionB!!
        w.b.engine.script = longScript
        w.b.engine.paceNanos = PacedEngine.DEFAULT_PACE_NANOS
        val rec = Waiting()
        val cut = AtomicBoolean(false)
        val body = "{\"messages\":[]}".toByteArray()
        w.noteBody(body, "req-cut-$seed")
        sa.offer(spec(requestId = "req-cut-$seed"), body, rec)
        rec.onChunkHook = { n ->
            if (n == chunksBeforeCut && cut.compareAndSet(false, true)) {
                if (crashDialer) {
                    w.a.dead.crash()
                    link.connA!!.reset()
                } else {
                    w.b.dead.crash()
                    link.connB!!.reset()
                }
            }
        }
        Wait.until("the cut") { cut.get() }
        val survivor: Session = if (crashDialer) sb else sa
        Wait.until("the survivor's session to end") { survivor.closed }
        w.b.engine.paceNanos = 0
        if (crashDialer) link.driverA?.close() else link.accepted?.driver?.close()
        settle(w)
        return RunResult(w, link, Scope.entries.toSet(), if (crashDialer) "A" else "B")
    }

    fun check(r: RunResult, strict: Boolean, counts: Counts, l15: L15Stats) {
        val l = r.link
        if (l == null || l.sessionA == null || l.sessionB == null) TlsOracle.structuralOnly(r.world, counts) else TlsOracle.check(l, strict, counts, l15, bGrantsA = r.bGrantsA)
    }
}
