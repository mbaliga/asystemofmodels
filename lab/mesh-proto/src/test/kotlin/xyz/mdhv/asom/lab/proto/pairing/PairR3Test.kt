package xyz.mdhv.asom.lab.proto.pairing

import java.security.MessageDigest
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.StatusLookup

/**
 * The R3 profile of the pairing machines (ERRATA ERR-FX2-1, -2, -4, -5): S commits to nonce_S before D chooses nonce_D, D's approval is the code typed
 * from S's screen, a window closed on D is not an S-side clock verdict, and D refuses a decision or an acknowledgement from another connection.
 * The R0_COMPAT cases are reproductions: they show what the first build allowed, and they are the reason the profile exists. Evidence label: LAB, oracle: self.
 */
class PairR3Test {
    companion object {
        val laws = LawCounters("pair-r3")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(
            setOf(
                "r0-sas-predictable-from-wire", "r3-sas-not-predictable-from-wire", "r3-typed-code-required", "r3-wrong-code-counted", "r3-premature-code-kept",
                "r3-reveal-must-open-commitment", "r3-d-shows-no-code", "r3-photographed-qr-race", "r3-end-to-end", "r3-connection-binding", "r0-connection-unbound", "r3-window-probe",
                "r3-s-reveals-only-with-approval", "r3-window-closed-is-not-a-decline", "r3-commitment-vectors", "r3-typed-code-normalisation",
            ),
        )

        private fun pin(label: String): Pin = Pin.ofHash(MessageDigest.getInstance("SHA-256").digest(label.toByteArray()))
    }

    private val pinD = pin("asom-vector/spki/D")
    private val pinS = pin("asom-vector/spki/S")
    private val pinX = pin("asom-vector/spki/attacker")
    private val secret = ByteArray(32) { it.toByte() }
    private val rS = ByteArray(32) { 0xA5.toByte() }
    private val nonceD = ByteArray(32) { 0x5A.toByte() }
    private val commitment = PairCrypto.commitNonce(rS)

    private fun r3(own: Pin) = PairFsmConfig(own, profile = PairProfile.R3)

    private fun r0(own: Pin) = PairFsmConfig(own)

    private class Run<S, E>(var state: S, val step: (S, E) -> Pair<S, List<PairEffect>>) {
        val log = ArrayList<String>()
        var last: List<PairEffect> = emptyList()

        fun go(e: E): List<PairEffect> {
            val (s, fx) = step(state, e)
            state = s
            last = fx
            log += fx.joinToString(",") { it.describe() }
            return fx
        }
    }

    private fun dRun(cfg: PairFsmConfig): Run<DState, DEvent> {
        val f = DFsm(cfg)
        return Run(DState.Closed) { s, e -> f.step(s, e).let { it.state to it.effects } }
    }

    private fun sRun(cfg: PairFsmConfig): Run<SState, SEvent> {
        val f = SFsm(cfg)
        return Run(SState.Idle) { s, e -> f.step(s, e).let { it.state to it.effects } }
    }

    private fun consumedBy(run: Run<DState, DEvent>, helloNonce: ByteArray, from: Pin = pinS, connId: Long = 0) {
        run.go(DEvent.UserOpenWindow(secret, 1_000))
        val proof = PairCrypto.proof(secret, pinD, from, helloNonce)
        run.go(DEvent.HelloReceived(from, helloNonce, proof, StatusLookup.Absent, nonceD, 2_000, connId))
        run.go(DEvent.ChallengeSent)
    }

    private fun sasOf(r: ByteArray, remote: Pin = pinS) = PairCrypto.sas(pinD, remote, r, nonceD)

    // ------------------------------------------------------------------------------------------------ PPW-1: commit before reveal

    private fun sasShownByS(cfg: PairFsmConfig, remoteNonceD: ByteArray): Pair<String, ByteArray> {
        val s = sRun(cfg)
        val payload = QrPayload(pinD, listOf(Endpoints.parse("192.168.1.40:11436")!!), secret, 1_790_000_120, "Dell tower")
        s.go(SEvent.Scanned(QrParse.Ok(payload)))
        s.go(SEvent.UserConfirmConnect(true, 1_000))
        val hello = s.go(SEvent.DialResult(pinD, rS, 2_000)).filterIsInstance<PairEffect.SendHello>().single()
        val shown = s.go(SEvent.ChallengeReceived(remoteNonceD, 3_000)).filterIsInstance<PairEffect.ShowConsent>().single().sas
        return shown to hello.nonceS
    }

    @Test
    fun underR0AnAttackerInTheDRoleCanPredictTheCodeSWillShowFromWhatSHasSent() {
        val rnd = SplittableRandom(11)
        repeat(50) {
            val nd = ByteArray(32) { rnd.nextInt(256).toByte() }
            val (shown, wireNonce) = sasShownByS(r0(pinS), nd)
            assertContentEquals(rS, wireNonce, "R0 puts nonce_S itself on the wire")
            assertEquals(PairCrypto.sas(pinD, pinS, wireNonce, nd), shown, "the attacker's prediction is exactly the code S shows, so it can grind nonce_D for a chosen code")
            laws.bump("r0-sas-predictable-from-wire")
        }
    }

    @Test
    fun underR3NothingSHasSentBeforeNonceDIsChosenDeterminesTheCodeSShows() {
        val rnd = SplittableRandom(12)
        var hits = 0
        repeat(2_000) {
            val nd = ByteArray(32) { rnd.nextInt(256).toByte() }
            val (shown, wireValue) = sasShownByS(r3(pinS), nd)
            assertContentEquals(commitment, wireValue, "R3 puts only the commitment on the wire")
            assertFalse(wireValue.contentEquals(rS))
            if (PairCrypto.sas(pinD, pinS, wireValue, nd) == shown) hits++
            assertEquals(PairCrypto.sas(pinD, pinS, rS, nd), shown)
            laws.bump("r3-sas-not-predictable-from-wire")
        }
        assertTrue(hits <= 1, "a prediction from the wire matched $hits of 2000 draws; chance is 1 in a million each")
    }

    @Test
    fun theCommitmentFunctionsAreDomainSeparatedAndStrict() {
        assertFalse(PairCrypto.commitNonce(rS).contentEquals(rS))
        assertTrue(PairCrypto.commitmentOpens(commitment, rS))
        assertFalse(PairCrypto.commitmentOpens(commitment, ByteArray(32) { 0xA4.toByte() }))
        assertFalse(PairCrypto.commitmentOpens(commitment, rS.copyOf(31)))
        assertFalse(PairCrypto.commitmentOpens(commitment.copyOf(31), rS))
        assertFalse(PairCrypto.commitmentOpens(rS, rS), "a nonce is not its own commitment")
        val plain = MessageDigest.getInstance("SHA-256").digest(rS)
        assertFalse(plain.contentEquals(commitment), "the commitment is label-separated, not a bare hash")
        val independent = MessageDigest.getInstance("SHA-256").digest("asom-pair-v1/commit".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + rS)
        assertEquals(Hex.encode(independent), Hex.encode(commitment), "SHA-256 of the label, a zero byte and the nonce")
        laws.bump("r3-commitment-vectors")
    }

    // ------------------------------------------------------------------------------------------------ PPW-2: D's approval is a typed entry

    @Test
    fun underR3DShowsNoCodeAndAsksForOne() {
        val d = dRun(r3(pinD))
        consumedBy(d, commitment)
        assertEquals("AWAIT_NONE", d.state.kind)
        assertEquals("PromptTypedCode", d.last.joinToString(",") { it.describe() })
        assertTrue(d.last.none { it is PairEffect.ShowConsent })
        assertTrue(d.log.none { it.contains("ShowConsent") && !it.contains("Refuse") })
        laws.bump("r3-d-shows-no-code")
    }

    @Test
    fun underR0DShowsTheCodeAndOneTapApprovesWithoutAnyComparison() {
        val d = dRun(r0(pinD))
        consumedBy(d, rS)
        assertEquals("ShowConsent(${sasOf(rS)})", d.last.single().describe())
        d.go(DEvent.RemoteDecision(true))
        d.go(DEvent.LocalDecision(true))
        assertEquals("COMMITTING", d.state.kind, "R0 reproduction: a bare boolean is D's whole consent")
    }

    private fun openAt(d: Run<DState, DEvent>, remoteReveal: ByteArray? = rS) {
        consumedBy(d, commitment)
        if (remoteReveal != null) d.go(DEvent.RemoteDecision(true, remoteReveal))
    }

    @Test
    fun underR3AnApprovalWithoutACodeNeverCountsEvenWhenSHasApproved() {
        val d = dRun(r3(pinD))
        openAt(d)
        assertEquals("AWAIT_REMOTE", d.state.kind)
        d.go(DEvent.LocalDecision(true))
        assertEquals("AWAIT_REMOTE", d.state.kind)
        assertEquals("Warn(typed-code-required)", d.last.single().describe())
        d.go(DEvent.LocalDecision(true, ""))
        assertEquals("AWAIT_REMOTE", d.state.kind, "an empty entry is not a code")
        d.go(DEvent.LocalDecision(true, "86541"))
        assertEquals("AWAIT_REMOTE", d.state.kind, "five digits is not a code")
        assertTrue(d.log.none { it.contains("WritePairedRow") })
        laws.bump("r3-typed-code-required")
    }

    @Test
    fun underR3AWrongCodeIsCountedAndTheThirdWrongCodeEndsTheCeremony() {
        val d = dRun(r3(pinD))
        openAt(d)
        val right = sasOf(rS)
        val wrong = if (right == "000 000") "000 001" else "000 000"
        d.go(DEvent.LocalDecision(true, wrong))
        assertEquals("AWAIT_REMOTE:Warn(wrong-code)", d.state.kind + ":" + d.last.single().describe())
        d.go(DEvent.LocalDecision(true, wrong))
        assertEquals("AWAIT_REMOTE", d.state.kind)
        d.go(DEvent.LocalDecision(true, wrong))
        assertEquals("CLOSED", d.state.kind)
        assertEquals("SendDecision(false),Abort(TRIES_EXHAUSTED,PAIRING_REFUSED)", d.last.joinToString(",") { it.describe() })
        assertTrue(d.log.none { it.contains("WritePairedRow") })
        laws.bump("r3-wrong-code-counted")
    }

    @Test
    fun underR3TheRightCodeWithOrWithoutSpacesCompletesOnceTheRevealIsIn() {
        for (typed in listOf(sasOf(rS), sasOf(rS).replace(" ", ""), " " + sasOf(rS) + " ", sasOf(rS).replace(" ", " "))) {
            val d = dRun(r3(pinD))
            openAt(d)
            d.go(DEvent.LocalDecision(true, typed))
            assertEquals("COMMITTING", d.state.kind, "typed '$typed'")
            assertEquals("SendDecision(true),WritePairedRow", d.last.joinToString(",") { it.describe() })
            laws.bump("r3-typed-code-normalisation")
        }
        assertFalse(PairCrypto.typedCodeMatches("865 412", "865-412"))
        assertFalse(PairCrypto.typedCodeMatches("865 412", "86541\uFF12"))
        assertFalse(PairCrypto.typedCodeMatches("865 412", "865 4120"))
    }

    @Test
    fun underR3ACodeTypedBeforeTheRevealIsKeptAndJudgedWhenItArrives() {
        val d = dRun(r3(pinD))
        openAt(d, remoteReveal = null)
        d.go(DEvent.LocalDecision(true, sasOf(rS)))
        assertEquals("AWAIT_NONE", d.state.kind)
        assertTrue(d.last.isEmpty(), "nothing is decided before S has opened its commitment")
        d.go(DEvent.RemoteDecision(true, rS))
        assertEquals("COMMITTING", d.state.kind)
        assertEquals("SendDecision(true),WritePairedRow", d.last.joinToString(",") { it.describe() })

        val e = dRun(r3(pinD))
        openAt(e, remoteReveal = null)
        e.go(DEvent.LocalDecision(true, "000 000"))
        e.go(DEvent.RemoteDecision(true, rS))
        assertEquals("AWAIT_REMOTE", e.state.kind, "the wrong early code is judged against the opened nonce")
        assertEquals("Warn(wrong-code)", e.last.single().describe())
        laws.bump("r3-premature-code-kept")
    }

    @Test
    fun underR3AnApprovalFromSThatDoesNotOpenTheCommitmentEndsTheCeremony() {
        val cases = listOf<Pair<String, ByteArray?>>("missing" to null, "other nonce" to ByteArray(32) { 0xA4.toByte() }, "short" to rS.copyOf(31), "the commitment itself" to commitment)
        for ((label, reveal) in cases) {
            val d = dRun(r3(pinD))
            openAt(d, remoteReveal = null)
            d.go(DEvent.RemoteDecision(true, reveal))
            assertEquals("CLOSED", d.state.kind, label)
            assertEquals("Abort(PROTOCOL,PROTOCOL_ERROR)", d.last.joinToString(",") { it.describe() }, label)
            laws.bump("r3-reveal-must-open-commitment")
        }
        val declined = dRun(r3(pinD))
        openAt(declined, remoteReveal = null)
        declined.go(DEvent.RemoteDecision(false))
        assertEquals("Abort(DECLINED_REMOTE,PAIRING_REFUSED)", declined.last.single().describe(), "a decline needs no reveal")
    }

    @Test
    fun theTypedCodeIsTheCodeOfTheOpenedNonceNotOfTheWireValue() {
        val d = dRun(r3(pinD))
        openAt(d, remoteReveal = null)
        d.go(DEvent.RemoteDecision(true, rS))
        d.go(DEvent.LocalDecision(true, PairCrypto.sas(pinD, pinS, commitment, nonceD)))
        assertEquals("AWAIT_REMOTE", d.state.kind, "the code computed from the wire value must be wrong")
    }

    @Test
    fun thePhotographedQrRaceCannotCompleteBecauseTheRealSShowsNoCodeThatMatchesTheAttackersSheet() {
        val attackerNonce = ByteArray(32) { 0x77 }
        val attackerCommitment = PairCrypto.commitNonce(attackerNonce)
        val d = dRun(r3(pinD))
        consumedBy(d, attackerCommitment, from = pinX)
        d.go(DEvent.RemoteDecision(true, attackerNonce))
        assertEquals("AWAIT_REMOTE", d.state.kind)
        val whatTheRealSMightType = listOf("865 412", "000 000", sasOf(rS), PairCrypto.sas(pinD, pinS, attackerNonce, nonceD))
        for (typed in whatTheRealSMightType) {
            d.go(DEvent.LocalDecision(true, typed))
            if (d.state.kind == "CLOSED") break
        }
        assertTrue(d.log.none { it.contains("WritePairedRow") }, "no code the real S could show opens the attacker's sheet")
        laws.bump("r3-photographed-qr-race")
    }

    // ------------------------------------------------------------------------------------------------ S side of R3

    private val qr = QrPayload(pinD, listOf(Endpoints.parse("192.168.1.40:11436")!!), secret, 1_790_000_120, "Dell tower")

    private fun sToAwait(cfg: PairFsmConfig): Run<SState, SEvent> {
        val s = sRun(cfg)
        s.go(SEvent.Scanned(QrParse.Ok(qr)))
        s.go(SEvent.UserConfirmConnect(true, 1_000))
        s.go(SEvent.DialResult(pinD, rS, 2_000))
        return s
    }

    @Test
    fun underR3SSendsTheCommitmentAndABoundProofAndOpensItOnlyWithItsOwnApproval() {
        val s = sRun(r3(pinS))
        s.go(SEvent.Scanned(QrParse.Ok(qr)))
        s.go(SEvent.UserConfirmConnect(true, 1_000))
        val hello = s.go(SEvent.DialResult(pinD, rS, 2_000)).single() as PairEffect.SendHello
        assertContentEquals(commitment, hello.nonceS)
        assertContentEquals(PairCrypto.proof(secret, pinD, pinS, commitment), hello.proof)
        val shown = s.go(SEvent.ChallengeReceived(nonceD, 3_000)).single() as PairEffect.ShowConsent
        assertEquals(sasOf(rS), shown.sas)
        val decline = sToAwait(r3(pinS)).also { it.go(SEvent.ChallengeReceived(nonceD, 3_000)) }
        decline.go(SEvent.LocalDecision(false))
        assertEquals("SendDecision(false),Abort(DECLINED_LOCAL,-)", decline.last.joinToString(",") { it.describe() })
        val approve = s.go(SEvent.LocalDecision(true)).single() as PairEffect.SendDecision
        assertTrue(approve.approve)
        assertContentEquals(rS, approve.reveal)
        laws.bump("r3-s-reveals-only-with-approval")
    }

    @Test
    fun underR3WindowClosedFromDIsTheOtherDevicesClockNotADecline() {
        val r3s = sToAwait(r3(pinS))
        assertEquals("SENT_HELLO", r3s.state.kind)
        r3s.go(SEvent.ErrorReceived("PAIRING_WINDOW_CLOSED"))
        assertEquals("FAILED", r3s.state.kind)
        assertEquals("Abort(WINDOW_EXPIRED,-)", r3s.last.single().describe())
        assertEquals(AbortReason.WINDOW_EXPIRED, (r3s.state as SState.Failed).reason)
        val other = sToAwait(r3(pinS))
        other.go(SEvent.ErrorReceived("PAIRING_REFUSED"))
        assertEquals(AbortReason.DECLINED_REMOTE, (other.state as SState.Failed).reason)
        val old = sToAwait(r0(pinS))
        old.go(SEvent.ErrorReceived("PAIRING_WINDOW_CLOSED"))
        assertEquals(AbortReason.DECLINED_REMOTE, (old.state as SState.Failed).reason, "R0 behaviour is unchanged")
        laws.bump("r3-window-closed-is-not-a-decline")
    }

    // ------------------------------------------------------------------------------------------------ both machines together

    @Test
    fun underR3TheTwoMachinesPairWhenThePersonTypesTheCodeSShows() {
        val d = dRun(r3(pinD))
        val s = sRun(r3(pinS))
        d.go(DEvent.UserOpenWindow(secret, 1_000))
        s.go(SEvent.Scanned(QrParse.Ok(qr)))
        s.go(SEvent.UserConfirmConnect(true, 1_000))
        val hello = s.go(SEvent.DialResult(pinD, rS, 2_000)).single() as PairEffect.SendHello
        d.go(DEvent.HelloReceived(pinS, hello.nonceS, hello.proof, StatusLookup.Absent, nonceD, 2_100))
        d.go(DEvent.ChallengeSent)
        val shown = (s.go(SEvent.ChallengeReceived(nonceD, 2_200)).single() as PairEffect.ShowConsent).sas
        val approve = s.go(SEvent.LocalDecision(true)).single() as PairEffect.SendDecision
        d.go(DEvent.RemoteDecision(true, approve.reveal))
        d.go(DEvent.LocalDecision(true, shown))
        assertEquals("COMMITTING", d.state.kind)
        s.go(SEvent.RemoteDecision(true))
        assertEquals("AWAIT_COMMIT", s.state.kind)
        val commit = d.go(DEvent.RowDurable(3_000)).single() as PairEffect.SendCommit
        val write = s.go(SEvent.CommitReceived(commit.transcript))
        assertEquals("WritePairedRow", write.single().describe())
        val ack = s.go(SEvent.RowDurable).filterIsInstance<PairEffect.SendAck>().single()
        assertContentEquals(commit.transcript, ack.transcript)
        d.go(DEvent.AckReceived(ack.transcript))
        assertEquals("CLOSED", d.state.kind)
        assertEquals("Done,Closed(done)", d.last.joinToString(",") { it.describe() })
        assertEquals("DONE", s.state.kind)
        assertContentEquals(PairCrypto.transcript(pinD, pinS, rS, nonceD), commit.transcript, "the transcript binds the opened nonce")
        laws.bump("r3-end-to-end")
    }

    // ------------------------------------------------------------------------------------------------ PPW-6

    @Test
    fun underR3ADecisionOrAckFromAnotherConnectionIsRefusedAndChangesNothing() {
        val d = dRun(r3(pinD))
        consumedBy(d, commitment, connId = 7)
        d.go(DEvent.RemoteDecision(true, rS, connId = 9))
        assertEquals("AWAIT_NONE:Refuse(PROTOCOL_ERROR)", d.state.kind + ":" + d.last.single().describe())
        d.go(DEvent.RemoteDecision(true, rS, connId = 7))
        assertEquals("AWAIT_REMOTE", d.state.kind)
        d.go(DEvent.LocalDecision(true, sasOf(rS)))
        assertEquals("COMMITTING", d.state.kind)
        d.go(DEvent.AckReceived(ByteArray(32), connId = 9))
        assertEquals("COMMITTING:Refuse(PROTOCOL_ERROR)", d.state.kind + ":" + d.last.single().describe())
        val transcript = PairCrypto.transcript(pinD, pinS, rS, nonceD)
        d.go(DEvent.RowDurable(4_000))
        assertEquals("AWAIT_ACK", d.state.kind)
        d.go(DEvent.AckReceived(transcript, connId = 9))
        assertEquals("AWAIT_ACK:Refuse(PROTOCOL_ERROR)", d.state.kind + ":" + d.last.single().describe(), "a matching ack from the wrong connection is not the peer's ack")
        d.go(DEvent.AckReceived(transcript, connId = 7))
        assertEquals("CLOSED", d.state.kind)

        val pre = DFsm(r3(pinD))
        val consumed = DState.Consumed(pinS, commitment, nonceD, 2_000, 7)
        assertEquals("CONSUMED:Refuse(PROTOCOL_ERROR)", pre.step(consumed, DEvent.RemoteDecision(true, rS, connId = 9)).signature)
        laws.bump("r3-connection-binding")
    }

    @Test
    fun underR0ADecisionFromAnyConnectionCountsBecauseTheEventNamesNone() {
        val d = dRun(r0(pinD))
        consumedBy(d, rS, connId = 7)
        d.go(DEvent.RemoteDecision(true, null, connId = 9))
        assertEquals("AWAIT_REMOTE", d.state.kind, "R0 reproduction: the connection id is ignored")
        laws.bump("r0-connection-unbound")
    }

    @Test
    fun onlyAnOpenUnexpiredWindowAdmitsAPairingConnection() {
        val f = DFsm(r3(pinD))
        val open = DState.Open(secret, 121_000, 0)
        assertTrue(f.admitsPairingConnection(open, 120_999))
        assertFalse(f.admitsPairingConnection(open, 121_000))
        assertFalse(f.admitsPairingConnection(DState.Closed, 0))
        assertFalse(f.admitsPairingConnection(DState.Consumed(pinS, commitment, nonceD, 5_000), 5_001), "a second unknown connection is refused once the window is consumed")
        assertFalse(f.admitsPairingConnection(DState.AwaitDecisions(pinS, commitment, nonceD, 125_000, local = false, remote = false), 5_001))
        assertFalse(f.admitsPairingConnection(DState.Committing(pinS, rS, nonceD), 5_001))
        assertFalse(f.admitsPairingConnection(DState.AwaitAck(ByteArray(32), 16_000), 5_001))
        laws.bump("r3-window-probe", 7)
    }
}
