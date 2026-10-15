package xyz.mdhv.asom.lab.proto.pairing

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.ledger.MemorySink
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.proto.session.CLOCK_BASE
import xyz.mdhv.asom.lab.proto.session.Ev
import xyz.mdhv.asom.lab.proto.session.Fr
import xyz.mdhv.asom.lab.proto.session.Frames
import xyz.mdhv.asom.lab.proto.session.Log
import xyz.mdhv.asom.lab.proto.session.MemConnection
import xyz.mdhv.asom.lab.proto.session.PairingChannel
import xyz.mdhv.asom.lab.proto.session.SeededIds
import xyz.mdhv.asom.lab.proto.session.SpySink
import xyz.mdhv.asom.lab.proto.session.all
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.StatusLookup
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.FrameTypes

/**
 * The two pairing machines driven through two real `PairingChannel`s with real encoded messages, oriented as trust.md 4.3 draws the ceremony and with every r4 default
 * in place (R4-T-10): S is the TLS client and sends the hello, the hello carries the commitment, S's approval carries the opening, D's approval is the typed code, a
 * second connection cannot supply a decision, and the listener's session is named by the hello it received. The hosts here are test code; they only turn frames into
 * events and effects into frames. Evidence label: LAB, oracle: self.
 */
class PairCeremonyOverChannelsTest {
    companion object {
        val laws = LawCounters("pair-ceremony-channels")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(
            setOf(
                "ceremony-completes-on-both-ends", "hello-carries-the-commitment", "reveal-travels-with-s-approval-only", "wrong-codes-end-the-ceremony", "second-connection-decision-refused",
                "listener-session-id-from-hello", "typed-code-before-opening-kept", "directions-on-the-wire",
            ),
        )

        private fun pin(label: String) = Pin.ofHash(MessageDigest.getInstance("SHA-256").digest(label.toByteArray()))
    }

    private val pinD = pin("asom-vector/spki/D")
    private val pinS = pin("asom-vector/spki/S")
    private val pinX = pin("asom-vector/spki/attacker")
    private val secret = ByteArray(32) { it.toByte() }
    private val nonceS = ByteArray(32) { 0xA5.toByte() }
    private val nonceD = ByteArray(32) { 0x5A.toByte() }
    private val payload = QrPayload(pinD, listOf(Endpoints.parse("192.168.1.40:11436")!!), secret, 1_790_000_120, "Dell tower")

    /** One ceremony: S and D machines, one channel each over an in-memory pairing connection, and the effects each machine asked for. */
    private inner class World {
        val log = Log()
        val memS = MemorySink()
        val memD = MemorySink()
        val conns = MemConnection.pair(null, null, log, "S>D", "D<S", ConnMode.PAIRING)
        val chS = PairingChannel(NodeLedger("S", SpySink("S", memS, log)) { CLOCK_BASE + log.events.size }, conns.first, SeededIds(1))
        val chD = PairingChannel(NodeLedger("D", SpySink("D", memD, log)) { CLOCK_BASE + log.events.size }, conns.second, SeededIds(2))
        val sFsm = SFsm(PairFsmConfig(pinS))
        val dFsm = DFsm(PairFsmConfig(pinD))
        var sState: SState = SState.Idle
        var dState: DState = DState.Closed
        val sEffects = ArrayList<PairEffect>()
        val dEffects = ArrayList<PairEffect>()
        var now = 1_000L
        var rowsS = 0
        var rowsD = 0

        init {
            chS.onFrame = { type, body -> fromD(type, body) }
            chD.onFrame = { type, body -> fromS(type, body, 1L) }
        }

        fun pump() {
            while (chS.pumpAvailable(64) + chD.pumpAvailable(64) > 0) Unit
        }

        fun s(e: SEvent) {
            val step = sFsm.step(sState, e)
            sState = step.state
            for (fx in step.effects) {
                sEffects += fx
                when (fx) {
                    is PairEffect.SendHello -> chS.send(FrameTypes.PAIR_HELLO, PairMessages.encodeHello(PairHello(fx.nonceS, fx.proof, "Phone", "android", "strongbox", emptyList())))
                    is PairEffect.SendDecision -> chS.send(FrameTypes.PAIR_DECISION, PairMessages.encodeDecision(PairDecision(fx.approve, fx.reveal)))
                    is PairEffect.SendAck -> chS.send(FrameTypes.PAIR_COMMIT_ACK, PairMessages.encodeCommitAck(PairCommitAck(fx.transcript)))
                    else -> Unit
                }
            }
            if (step.effects.any { it is PairEffect.WritePairedRow }) {
                rowsS++
                s(SEvent.RowDurable)
            }
        }

        fun d(e: DEvent) {
            val step = dFsm.step(dState, e)
            dState = step.state
            for (fx in step.effects) {
                dEffects += fx
                when (fx) {
                    is PairEffect.SendChallenge -> chD.send(FrameTypes.PAIR_CHALLENGE, PairMessages.encodeChallenge(PairChallenge(nonceD, "Desk", "linux", "file")))
                    is PairEffect.SendDecision -> chD.send(FrameTypes.PAIR_DECISION, PairMessages.encodeDecision(PairDecision(fx.approve, fx.reveal)))
                    is PairEffect.SendCommit -> chD.send(FrameTypes.PAIR_COMMIT, PairMessages.encodeCommit(PairCommit(fx.transcript)))
                    else -> Unit
                }
            }
            if (step.effects.any { it is PairEffect.SendChallenge }) d(DEvent.ChallengeSent)
            if (step.effects.any { it is PairEffect.WritePairedRow }) {
                rowsD++
                d(DEvent.RowDurable(now))
            }
        }

        fun fromS(type: Int, body: ByteArray, conn: Long) {
            when (type) {
                FrameTypes.PAIR_HELLO -> {
                    val h = (PairMessages.parseHello(body) as MsgParse.Ok).value
                    d(DEvent.HelloReceived(helloPin(conn), h.nonceS, h.proof, StatusLookup.Absent, nonceD, now, conn))
                }
                FrameTypes.PAIR_DECISION -> {
                    val x = (PairMessages.parseDecision(body) as MsgParse.Ok).value
                    d(DEvent.RemoteDecision(x.approve, x.reveal, conn))
                }
                FrameTypes.PAIR_COMMIT_ACK -> d(DEvent.AckReceived((PairMessages.parseCommitAck(body) as MsgParse.Ok).value.transcript, conn))
            }
        }

        var helloPin: (Long) -> Pin = { pinS }

        fun fromD(type: Int, body: ByteArray) {
            when (type) {
                FrameTypes.PAIR_CHALLENGE -> s(SEvent.ChallengeReceived((PairMessages.parseChallenge(body) as MsgParse.Ok).value.nonceD, now))
                FrameTypes.PAIR_DECISION -> s(SEvent.RemoteDecision((PairMessages.parseDecision(body) as MsgParse.Ok).value.approve))
                FrameTypes.PAIR_COMMIT -> s(SEvent.CommitReceived((PairMessages.parseCommit(body) as MsgParse.Ok).value.transcript))
            }
        }

        /** The window is open on D, S has scanned and dialled: the hello is on the wire and D has answered with the challenge. Returns the code S shows. */
        fun toTheDecisions(): String {
            d(DEvent.UserOpenWindow(secret, now))
            s(SEvent.Scanned(QrParse.Ok(payload)))
            s(SEvent.UserConfirmConnect(true, now))
            s(SEvent.DialResult(pinD, nonceS, now))
            pump()
            val shown = sEffects.filterIsInstance<PairEffect.ShowConsent>().single().sas
            assertTrue(dEffects.any { it is PairEffect.PromptTypedCode }, "D prompts for a code")
            assertTrue(dEffects.none { it is PairEffect.ShowConsent }, "D shows no code of its own")
            return shown
        }

        fun written(conn: String): List<Fr> = log.all<Ev.Write>().filter { it.conn == conn && !it.raw }.flatMap { Frames.parse(it.bytes) }
    }

    private fun b64(b: ByteArray) = Base64Strict.encodeUrlNoPad(b)

    @Test
    fun aCeremonyInTheSpecDirectionCompletesOnBothEndsAndEveryR4MarkIsOnTheWire() {
        val w = World()
        val shown = w.toTheDecisions()
        assertEquals(PairCrypto.sas(pinD, pinS, nonceS, nonceD), shown)

        // the person reads S's screen and types the code on D; S's person approves
        w.d(DEvent.LocalDecision(true, shown.replace(" ", "")))
        w.pump()
        assertTrue(w.dState is DState.AwaitDecisions, "the typed code is kept until S opens its commitment")
        assertEquals(0, w.rowsD)
        laws.bump("typed-code-before-opening-kept")
        w.s(SEvent.LocalDecision(true))
        w.pump()

        assertEquals(SState.Done, w.sState)
        assertTrue(w.dState is DState.Closed)
        assertTrue(w.dEffects.any { it is PairEffect.Done })
        assertEquals(1, w.rowsS)
        assertEquals(1, w.rowsD)
        laws.bump("ceremony-completes-on-both-ends")

        // the hello carries the commitment, never the nonce
        val toD = w.written("S>D")
        val hello = (PairMessages.parseHello(toD.first { it.type == FrameTypes.PAIR_HELLO }.payload) as MsgParse.Ok).value
        assertContentEquals(PairCrypto.commitNonce(nonceS), hello.nonceS)
        assertFalse(hello.nonceS.contentEquals(nonceS))
        assertTrue(PairCrypto.proofValid(secret, pinD, pinS, hello.nonceS, hello.proof), "the proof binds the commitment")
        laws.bump("hello-carries-the-commitment")

        // S's approval carries the opening; D's approval does not
        val sDecision = (PairMessages.parseDecision(toD.first { it.type == FrameTypes.PAIR_DECISION }.payload) as MsgParse.Ok).value
        assertTrue(sDecision.approve)
        assertContentEquals(nonceS, sDecision.reveal)
        val dDecision = (PairMessages.parseDecision(w.written("D<S").first { it.type == FrameTypes.PAIR_DECISION }.payload) as MsgParse.Ok).value
        assertTrue(dDecision.approve && dDecision.reveal == null)
        laws.bump("reveal-travels-with-s-approval-only")

        // who sent what (trust.md 4.4)
        assertEquals(listOf(FrameTypes.PAIR_HELLO, FrameTypes.PAIR_DECISION, FrameTypes.PAIR_COMMIT_ACK), toD.map { it.type })
        assertEquals(listOf(FrameTypes.PAIR_CHALLENGE, FrameTypes.PAIR_DECISION, FrameTypes.PAIR_COMMIT), w.written("D<S").map { it.type })
        laws.bump("directions-on-the-wire")

        // the listener (D) names its session by the hello it received, and writes its open row first; the dialler keeps its own id and carries the derived one as attemptId
        val derived = b64(hello.nonceS.copyOfRange(0, 16))
        val rowsD = w.memD.all()
        assertEquals(MeshKind.SESSION to "pairing", rowsD.first().meshKind to rowsD.first().meshCode)
        assertTrue(rowsD.all { it.sessionId == derived })
        val rowsS = w.memS.all()
        assertTrue(rowsS.none { it.meshKind == MeshKind.SESSION && it.meshCode == "pairing" })
        assertTrue(rowsS.all { it.sessionId != derived } && rowsS.first().attemptId == derived)
        laws.bump("listener-session-id-from-hello")
    }

    @Test
    fun threeWrongCodesTypedOnDEndTheCeremonyWithoutARowOnEitherEnd() {
        val w = World()
        val shown = w.toTheDecisions()
        w.s(SEvent.LocalDecision(true))
        w.pump()
        val wrong = "%06d".format((shown.replace(" ", "").toInt() + 1) % 1_000_000)
        repeat(3) {
            w.d(DEvent.LocalDecision(true, wrong))
            w.pump()
        }
        assertTrue(w.dState is DState.Closed)
        assertTrue(w.dEffects.any { it is PairEffect.Abort && it.reason == AbortReason.TRIES_EXHAUSTED })
        assertTrue(w.sState is SState.Failed && (w.sState as SState.Failed).reason == AbortReason.DECLINED_REMOTE)
        assertEquals(0, w.rowsS + w.rowsD)
        laws.bump("wrong-codes-end-the-ceremony")
    }

    @Test
    fun aSecondConnectionCannotSupplyADecisionWhileTheFirstOneIsInTheCeremony() {
        val w = World()
        w.toTheDecisions()
        assertFalse(w.dFsm.admitsPairingConnection(w.dState, w.now), "the window is consumed: the TLS probe refuses a second unknown connection")

        val log2 = Log()
        val conns2 = MemConnection.pair(null, null, log2, "X>D", "D<X", ConnMode.PAIRING)
        val mem2 = MemorySink()
        val chX = PairingChannel(NodeLedger("X", SpySink("X", MemorySink(), log2)) { CLOCK_BASE + log2.events.size }, conns2.first, SeededIds(5))
        val chD2 = PairingChannel(NodeLedger("D", SpySink("D", mem2, log2)) { CLOCK_BASE + log2.events.size }, conns2.second, SeededIds(6))
        chD2.onFrame = { type, body -> w.fromS(type, body, 2L) }
        w.helloPin = { c -> if (c == 2L) pinX else pinS }
        val before = w.dState
        val effectsBefore = w.dEffects.size
        chX.send(FrameTypes.PAIR_HELLO, PairMessages.encodeHello(PairHello(PairCrypto.commitNonce(nonceS), ByteArray(32) { 1 }, "Phone", "android", "strongbox", emptyList())))
        chX.send(FrameTypes.PAIR_DECISION, PairMessages.encodeDecision(PairDecision(true, nonceS)))
        while (chX.pumpAvailable(64) + chD2.pumpAvailable(64) > 0) Unit
        assertTrue(w.dState === before, "the second connection moved D's state")
        assertEquals(
            listOf<PairEffect>(PairEffect.Refuse(Refusal.PAIRING_WINDOW_CLOSED), PairEffect.Refuse(Refusal.PROTOCOL_ERROR)), w.dEffects.drop(effectsBefore),
            "the hello meets a closed window and the decision is refused",
        )
        assertEquals(0, w.rowsD)
        laws.bump("second-connection-decision-refused")

        // the first connection still completes
        w.d(DEvent.LocalDecision(true, PairCrypto.sas(pinD, pinS, nonceS, nonceD)))
        w.s(SEvent.LocalDecision(true))
        w.pump()
        assertEquals(SState.Done, w.sState)
        assertEquals(1, w.rowsD)
        assertNotNull(w.written("D<S").firstOrNull { it.type == FrameTypes.PAIR_COMMIT })
        laws.bump("ceremony-completes-on-both-ends")
    }
}
