package xyz.mdhv.asom.lab.proto.session

import java.util.SplittableRandom
import xyz.mdhv.asom.lab.ledger.LedgerUnavailableException
import xyz.mdhv.asom.lab.ledger.MemorySink
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.pairing.PairChallenge
import xyz.mdhv.asom.lab.proto.pairing.PairCommit
import xyz.mdhv.asom.lab.proto.pairing.PairCommitAck
import xyz.mdhv.asom.lab.proto.pairing.PairDecision
import xyz.mdhv.asom.lab.proto.pairing.PairHello
import xyz.mdhv.asom.lab.proto.pairing.PairMessages
import xyz.mdhv.asom.lab.proto.trust.Scope
import xyz.mdhv.asom.lab.proto.wire.CancelReason
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.GoAwayReason
import xyz.mdhv.asom.lab.proto.wire.Terminal

/** A seeded random session between two honest nodes: handshake, offers (served, declined, cancelled, failing), state, manifests, extensions, notices, and an end. */
object RandomRuns {
    private fun scripts(rnd: SplittableRandom): (EngineRequest) -> List<Any> = when (rnd.nextInt(7)) {
        0 -> { _ -> listOf(EngineEvent.Head(200, "m1")) + List(25) { EngineEvent.Chunk(ByteArray(20) { 1 }) } + EngineEvent.End(Terminal.DONE, 200, 7) }
        1 -> { _ -> listOf(EngineEvent.Head(200, "m1"), EngineEvent.Chunk("partial".toByteArray()), IllegalStateException("engine failed")) }
        2 -> { _ -> listOf(EngineEvent.End(Terminal.ERROR, 500, null)) }
        3 -> { _ -> listOf(EngineEvent.Head(200, "m1"), EngineEvent.End(Terminal.OOM, 503, null)) }
        4 -> { _ -> listOf(EngineEvent.Chunk("no head first".toByteArray()), EngineEvent.End(Terminal.INTERRUPTED, 499, null)) }
        else -> { _ -> listOf(EngineEvent.Head(200, "m1"), EngineEvent.Chunk("hello".toByteArray()), EngineEvent.Chunk(" world".toByteArray()), EngineEvent.End(Terminal.DONE, 200, 12)) }
    }

    private val scriptsLong: (EngineRequest) -> List<Any> =
        { listOf(EngineEvent.Head(200, "m1")) + List(40) { EngineEvent.Chunk(ByteArray(20) { 1 }) } + EngineEvent.End(Terminal.DONE, 200, 7) }

    fun run(seed: Long, failA: FailPlan? = null, failB: FailPlan? = null): World {
        val rnd = SplittableRandom(seed * 7919 + 13)
        val w = World(seed, failA, failB, maxChunk = 1 + rnd.nextInt(90))
        w.bGrantsA = Scope.entries.filter { rnd.nextInt(6) != 0 }.toSet()
        w.b.registry.setInboundScopes(w.a.pin, w.bGrantsA)
        val (sa, sb) = try {
            w.connect()
        } catch (e: LedgerUnavailableException) {
            return w
        }
        w.pump()
        var index = 0
        repeat(1 + rnd.nextInt(6)) {
            if (sa.closed) return@repeat
            when (rnd.nextInt(10)) {
                0, 1, 2, 7 -> {
                    val cancelling = rnd.nextInt(5) == 0
                    w.b.engine.script = if (cancelling) scriptsLong else scripts(rnd)
                    val model = listOf("m1", "m1", "m2", "nope")[rnd.nextInt(4)]
                    val prompt = if (rnd.nextInt(8) == 0) 9_000_000 else 100 + rnd.nextInt(200)
                    try {
                        val rec = Recorder()
                        val body = ByteArray(prompt.coerceAtMost(800)) { "abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)].code.toByte() }
                        w.noteBody(body, "req-$index-$seed")
                        val id = sa.offer(spec(model, index++, "req-${index - 1}-$seed", rnd.nextBoolean()), body, rec)
                        if (cancelling) {
                            repeat(rnd.nextInt(5)) { w.pumpOnce() }
                            sa.cancel(id, CancelReason.CLIENT_GONE)
                        }
                    } catch (e: LedgerUnavailableException) {
                    } catch (e: SessionClosedForSend) {
                    }
                }
                3 -> sa.requestState {}
                4 -> {
                    w.b.manifest.available = rnd.nextInt(4) != 0
                    sa.requestManifest(ByteArray(32) { (rnd.nextInt(3) + it).toByte() }) {}
                }
                5 -> {
                    val l = w.links[rnd.nextInt(2)]
                    if (!l.session.closed && !l.conn.closedByUs) repeat(1 + rnd.nextInt(3)) { l.conn.writeRaw(Build.ext(0x80 + rnd.nextInt(128), rnd.nextLong(1L shl 32), ByteArray(rnd.nextInt(30)))) }
                }
                6 -> (if (rnd.nextBoolean()) sa else sb).let { if (!it.closed) it.sendRevokeNotice() }
                8 -> repeat(rnd.nextInt(10)) { w.pumpOnce() }
                else -> w.b.serving = when (rnd.nextInt(3)) {
                    0 -> ServingView(servingConditionsOk = false)
                    1 -> ServingView(presenceActive = true, presenceHoldRemainingMs = 60_000)
                    else -> ServingView()
                }
            }
            w.pump()
        }
        when (rnd.nextInt(6)) {
            0 -> sa.goAway(GoAwayReason.SHUTDOWN)
            1 -> sb.goAway(GoAwayReason.IDLE)
            2 -> w.b.registry.revoke(w.a.pin, CLOCK_BASE)
            3 -> w.a.registry.pause(w.b.pin, CLOCK_BASE)
            4 -> sa.close()
            else -> sb.close()
        }
        w.pump()
        return w
    }
}

/** Two pairing-mode channels exchanging `PAIR_*` frames with their per-frame rows. Each has its own ledger; nothing here implements pairing. */
class PairWorld(val seed: Long, failS: FailPlan? = null) {
    val log = Log()
    val rnd = SplittableRandom(seed)
    private val memS = MemorySink()
    private val memD = MemorySink()
    private val crash = failS?.let { xyz.mdhv.asom.lab.ledger.CrashInjectingSink(memS, it.failAt, it.mode, it.sticky) }
    val ledgerS = NodeLedger("S", SpySink("S", crash ?: memS, log)) { CLOCK_BASE + log.events.size }
    val ledgerD = NodeLedger("D", SpySink("D", memD, log)) { CLOCK_BASE + log.events.size }
    private val conns = MemConnection.pair(null, null, log, "D>S", "S<D", ConnMode.PAIRING)
    val d = PairingChannel(ledgerD, conns.first, SeededIds(seed * 3 + 1))
    val s = PairingChannel(ledgerS, conns.second, SeededIds(seed * 3 + 2))
    val connD = conns.first
    val connS = conns.second
    val nonceS = ByteArray(32) { rnd.nextInt(256).toByte() }
    val receivedByS = ArrayList<Pair<Int, ByteArray>>()
    val receivedByD = ArrayList<Pair<Int, ByteArray>>()

    init {
        s.onFrame = { t, p -> receivedByS += t to p }
        d.onFrame = { t, p -> receivedByD += t to p }
    }

    fun pump() {
        while (s.pumpAvailable(1 + rnd.nextInt(60)) + d.pumpAvailable(1 + rnd.nextInt(60)) > 0) Unit
    }

    private fun bytes(n: Int) = ByteArray(n) { rnd.nextInt(256).toByte() }

    fun run() {
        s.send(0x30, PairMessages.encodeHello(PairHello(nonceS, bytes(32), "Desk", "linux", "file", emptyList())))
        pump()
        d.send(0x31, PairMessages.encodeChallenge(PairChallenge(bytes(32), "Phone", "android", "strongbox")))
        pump()
        s.send(0x32, PairMessages.encodeDecision(PairDecision(true)))
        d.send(0x32, PairMessages.encodeDecision(PairDecision(rnd.nextInt(5) != 0)))
        pump()
        val transcript = bytes(32)
        d.send(0x33, PairMessages.encodeCommit(PairCommit(transcript)))
        pump()
        s.send(0x34, PairMessages.encodeCommitAck(PairCommitAck(transcript)))
        pump()
        if (rnd.nextInt(3) == 0) connS.writeRaw(Build.ext(0x91, 0, bytes(rnd.nextInt(20))))
        pump()
        if (rnd.nextBoolean()) s.close() else d.close()
        pump()
        if (connS.peerClosed() && !d.isClosed) d.close()
        if (connD.peerClosed() && !s.isClosed) s.close()
    }

    fun rowsS() = memS.all()

    fun rowsD() = memD.all()
}
