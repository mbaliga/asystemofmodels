package xyz.mdhv.asom.lab.proto.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.StatusLookup

/**
 * The exhaustive transition tests of the two pairing state machines under the default profile, R3 (trust.md 4.6 as amended by R4-T-10), where
 * [PairingFsmExhaustiveTest] pins the first-built machines under R0_COMPAT. Every (state, event) pair of the cross product is run and compared with a table typed from
 * the amended spec; a pair that is not in the table must be a no-op (state kind unchanged, no effects). The test fails if any state or event class of the code has no
 * representative here, and counts the pairs it exercised. The configuration is the bare default, so a default that moved back to R0_COMPAT fails every cell.
 * Evidence label: LAB, oracle: self.
 */
class PairingFsmExhaustiveR3Test {
    companion object {
        val laws = LawCounters("pairing-fsm-r3")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(
            setOf(
                "D-pairs-exercised", "S-pairs-exercised", "D-non-noop-cells", "S-non-noop-cells", "D-default-profile-is-r3", "D-three-wrong-codes-decline", "D-no-row-without-opening-and-code",
                "D-foreign-connection-refused-in-every-bound-state",
            ),
        )
    }

    private val pinD = Pin.ofHash(java.security.MessageDigest.getInstance("SHA-256").digest("asom-vector/spki/D".toByteArray()))
    private val pinS = Pin.ofHash(java.security.MessageDigest.getInstance("SHA-256").digest("asom-vector/spki/S".toByteArray()))
    private val secret = ByteArray(32) { it.toByte() }
    private val rS = ByteArray(32) { 0xA5.toByte() }
    private val nonceD = ByteArray(32) { 0x5A.toByte() }
    private val commitment = PairCrypto.commitNonce(rS)
    private val transcript = PairCrypto.transcript(pinD, pinS, rS, nonceD)
    private val goodProof = PairCrypto.proof(secret, pinD, pinS, commitment)
    private val code = "865 412"
    private val foreign = 9L

    // ------------------------------------------------------------------------------------------------ D

    private class DCase(val kind: String, val make: () -> DState)

    private val dStates: List<Pair<String, DCase>> = listOf(
        "CLOSED" to DCase("CLOSED") { DState.Closed },
        "OPEN" to DCase("OPEN") { DState.Open(secret, 120_000, 0) },
        "CONSUMED" to DCase("CONSUMED") { DState.Consumed(pinS, commitment, nonceD, 5_000) },
        "AWAIT_NONE" to DCase("AWAIT_NONE") { DState.AwaitDecisions(pinS, commitment, nonceD, 125_000, local = false, remote = false) },
        "AWAIT_TYPED" to DCase("AWAIT_NONE") { DState.AwaitDecisions(pinS, commitment, nonceD, 125_000, local = false, remote = false, typed = code) },
        "AWAIT_TYPED_WRONG" to DCase("AWAIT_NONE") { DState.AwaitDecisions(pinS, commitment, nonceD, 125_000, local = false, remote = false, typed = "000 000") },
        "AWAIT_REVEALED" to DCase("AWAIT_REMOTE") { DState.AwaitDecisions(pinS, commitment, nonceD, 125_000, local = false, remote = true, reveal = rS) },
        "AWAIT_REVEALED_2" to DCase("AWAIT_REMOTE") { DState.AwaitDecisions(pinS, commitment, nonceD, 125_000, local = false, remote = true, reveal = rS, wrongCodes = 2) },
        "COMMITTING" to DCase("COMMITTING") { DState.Committing(pinS, rS, nonceD) },
        "AWAIT_ACK" to DCase("AWAIT_ACK") { DState.AwaitAck(transcript, 16_000) },
    )

    private fun hello(proof: ByteArray, status: StatusLookup) = DEvent.HelloReceived(pinS, commitment, proof, status, nonceD, 1_000)

    private val dEvents: List<Pair<String, () -> DEvent>> = listOf(
        "UserOpenWindow" to { DEvent.UserOpenWindow(secret, 0) },
        "HelloValid" to { hello(goodProof, StatusLookup.Absent) },
        "HelloBadProof" to { hello(ByteArray(32), StatusLookup.Absent) },
        "HelloRevoked" to { hello(goodProof, StatusLookup.Known(PeerStatus.REVOKED)) },
        "HelloExisting" to { hello(goodProof, StatusLookup.Known(PeerStatus.PAIRED)) },
        "HelloCorrupt" to { hello(goodProof, StatusLookup.Corrupt(9)) },
        "ChallengeSent" to { DEvent.ChallengeSent },
        "LocalApproveNoCode" to { DEvent.LocalDecision(true) },
        "LocalApproveRight" to { DEvent.LocalDecision(true, code) },
        "LocalApproveWrong" to { DEvent.LocalDecision(true, "000 000") },
        "LocalDecline" to { DEvent.LocalDecision(false) },
        "RemoteApproveOpens" to { DEvent.RemoteDecision(true, rS) },
        "RemoteApproveBad" to { DEvent.RemoteDecision(true, ByteArray(32) { 1 }) },
        "RemoteApproveNoReveal" to { DEvent.RemoteDecision(true) },
        "RemoteApproveForeign" to { DEvent.RemoteDecision(true, rS, foreign) },
        "RemoteDecline" to { DEvent.RemoteDecision(false) },
        "RemoteDeclineForeign" to { DEvent.RemoteDecision(false, null, foreign) },
        "TickEarly" to { DEvent.Tick(1_000) },
        "TickLate" to { DEvent.Tick(1_000_000) },
        "UserCancel" to { DEvent.UserCancel },
        "ConnectionLost" to { DEvent.ConnectionLost },
        "RowDurable" to { DEvent.RowDurable(6_000) },
        "RowWriteFailed" to { DEvent.RowWriteFailed },
        "AckGood" to { DEvent.AckReceived(transcript) },
        "AckBad" to { DEvent.AckReceived(ByteArray(32)) },
        "AckForeign" to { DEvent.AckReceived(transcript, foreign) },
        "SecondUnknown" to { DEvent.SecondUnknownConnection },
    )

    private val helloCols = listOf("HelloValid", "HelloBadProof", "HelloRevoked", "HelloExisting", "HelloCorrupt")
    private val remoteCols = listOf("RemoteApproveOpens", "RemoteApproveBad", "RemoteApproveNoReveal", "RemoteApproveForeign", "RemoteDecline", "RemoteDeclineForeign")
    private val remoteBound = listOf("RemoteApproveOpens", "RemoteApproveBad", "RemoteApproveNoReveal", "RemoteDecline")
    private val remoteForeign = listOf("RemoteApproveForeign", "RemoteDeclineForeign")
    private val refusedClosed = "Refuse(PAIRING_WINDOW_CLOSED)"
    private val refusedProtocol = "Refuse(PROTOCOL_ERROR)"
    private val declineLocal = "SendDecision(false),Abort(DECLINED_LOCAL,PAIRING_REFUSED)"
    private val abortProtocol = "Abort(PROTOCOL,PROTOCOL_ERROR)"
    private val t8 = Hex.encode(transcript.copyOf(4))
    private val commitRow = "COMMITTING:SendDecision(true),WritePairedRow"

    private fun dTable(): Map<Pair<String, String>, String> {
        val t = LinkedHashMap<Pair<String, String>, String>()
        val kinds = dStates.associate { it.first to it.second.kind }
        fun put(state: String, event: String, expected: String) {
            assertTrue(t.put(state to event, expected) == null, "table cell $state/$event typed twice")
        }
        fun k(state: String) = kinds.getValue(state)

        for (h in helloCols) for (s in dStates.map { it.first }.filter { it != "OPEN" }) put(s, h, "${k(s)}:$refusedClosed")
        put("CLOSED", "UserOpenWindow", "OPEN:WindowOpened")

        put("OPEN", "HelloValid", "CONSUMED:SendChallenge")
        put("OPEN", "HelloBadProof", "OPEN:Refuse(PAIRING_PROOF_INVALID)")
        put("OPEN", "HelloRevoked", "CLOSED:Warn(revoked-device-tried-to-pair),Abort(REVOKED_PEER,PAIRING_REFUSED)")
        put("OPEN", "HelloExisting", "CLOSED:Abort(PEER_EXISTS,PAIRING_REFUSED)")
        put("OPEN", "HelloCorrupt", "CLOSED:Abort(REGISTRY_UNREADABLE,PAIRING_REFUSED)")
        for (e in remoteCols + listOf("AckGood", "AckBad", "AckForeign")) put("OPEN", e, "OPEN:$refusedProtocol")
        put("OPEN", "TickLate", "CLOSED:Closed(window-expired)")
        put("OPEN", "UserCancel", "CLOSED:Closed(window-cancelled)")

        put("CONSUMED", "ChallengeSent", "AWAIT_NONE:PromptTypedCode")
        for (e in remoteBound + listOf("AckGood", "AckBad")) put("CONSUMED", e, "CLOSED:$abortProtocol")
        for (e in remoteForeign + listOf("AckForeign")) put("CONSUMED", e, "CONSUMED:$refusedProtocol")
        put("CONSUMED", "TickLate", "CLOSED:Abort(TIMEOUT,PAIRING_REFUSED)")
        put("CONSUMED", "UserCancel", "CLOSED:Abort(CANCELLED,PAIRING_REFUSED)")
        put("CONSUMED", "ConnectionLost", "CLOSED:Abort(CONNECTION_LOST,-)")
        put("CONSUMED", "SecondUnknown", "CONSUMED:Warn(second-connection)")

        val awaiting = dStates.map { it.first }.filter { it.startsWith("AWAIT_") && it != "AWAIT_ACK" }
        for (s in awaiting) {
            val kind = k(s)
            put(s, "LocalApproveNoCode", "$kind:Warn(typed-code-required)")
            put(s, "LocalDecline", "CLOSED:$declineLocal")
            put(s, "RemoteDecline", "CLOSED:Abort(DECLINED_REMOTE,PAIRING_REFUSED)")
            for (e in remoteForeign + listOf("AckForeign")) put(s, e, "$kind:$refusedProtocol")
            put(s, "TickLate", "CLOSED:Abort(TIMEOUT,PAIRING_REFUSED)")
            put(s, "UserCancel", "CLOSED:$declineLocal")
            put(s, "ConnectionLost", "CLOSED:Abort(CONNECTION_LOST,-)")
            put(s, "AckGood", "CLOSED:$abortProtocol")
            put(s, "AckBad", "CLOSED:$abortProtocol")
            put(s, "SecondUnknown", "$kind:Warn(second-connection)")
        }
        // before S's opening has arrived: a code is kept, an opening is judged against the kept code
        put("AWAIT_NONE", "LocalApproveRight", "AWAIT_NONE:")
        put("AWAIT_NONE", "LocalApproveWrong", "AWAIT_NONE:")
        put("AWAIT_NONE", "RemoteApproveOpens", "AWAIT_REMOTE:")
        put("AWAIT_TYPED", "LocalApproveRight", "AWAIT_NONE:")
        put("AWAIT_TYPED", "LocalApproveWrong", "AWAIT_NONE:")
        put("AWAIT_TYPED", "RemoteApproveOpens", commitRow)
        put("AWAIT_TYPED_WRONG", "LocalApproveRight", "AWAIT_NONE:")
        put("AWAIT_TYPED_WRONG", "LocalApproveWrong", "AWAIT_NONE:")
        put("AWAIT_TYPED_WRONG", "RemoteApproveOpens", "AWAIT_REMOTE:Warn(wrong-code)")
        for (s in listOf("AWAIT_NONE", "AWAIT_TYPED", "AWAIT_TYPED_WRONG")) {
            put(s, "RemoteApproveBad", "CLOSED:$abortProtocol")
            put(s, "RemoteApproveNoReveal", "CLOSED:$abortProtocol")
        }
        // after the opening: the code is judged, three wrong codes decline, a second approval changes nothing
        put("AWAIT_REVEALED", "LocalApproveRight", commitRow)
        put("AWAIT_REVEALED", "LocalApproveWrong", "AWAIT_REMOTE:Warn(wrong-code)")
        put("AWAIT_REVEALED_2", "LocalApproveRight", commitRow)
        put("AWAIT_REVEALED_2", "LocalApproveWrong", "CLOSED:SendDecision(false),Abort(TRIES_EXHAUSTED,PAIRING_REFUSED)")

        put("COMMITTING", "RowDurable", "AWAIT_ACK:SendCommit($t8)")
        put("COMMITTING", "RowWriteFailed", "CLOSED:Abort(WRITE_FAILED,PAIRING_REFUSED)")
        put("COMMITTING", "ConnectionLost", "CLOSED:MarkUnconfirmed(connection-lost-while-committing),Abort(CONNECTION_LOST,-)")
        put("COMMITTING", "AckGood", "CLOSED:MarkUnconfirmed(acknowledgement-before-commit),$abortProtocol")
        put("COMMITTING", "AckBad", "CLOSED:MarkUnconfirmed(acknowledgement-before-commit),$abortProtocol")
        for (e in remoteForeign + listOf("AckForeign")) put("COMMITTING", e, "COMMITTING:$refusedProtocol")
        put("COMMITTING", "SecondUnknown", "COMMITTING:Warn(second-connection)")

        put("AWAIT_ACK", "AckGood", "CLOSED:Done,Closed(done)")
        put("AWAIT_ACK", "AckBad", "CLOSED:MarkUnconfirmed(transcript-mismatch),Warn(transcript-mismatch),Closed(transcript-mismatch)")
        for (e in remoteForeign + listOf("AckForeign")) put("AWAIT_ACK", e, "AWAIT_ACK:$refusedProtocol")
        put("AWAIT_ACK", "TickLate", "CLOSED:MarkUnconfirmed(ack-timeout),Warn(may-not-have-finished),Closed(ack-timeout)")
        put("AWAIT_ACK", "ConnectionLost", "CLOSED:MarkUnconfirmed(connection-lost),Warn(may-not-have-finished),Closed(connection-lost)")
        put("AWAIT_ACK", "SecondUnknown", "AWAIT_ACK:Warn(second-connection)")
        return t
    }

    @Test
    fun everyDStatePairIsExercisedAndMatchesTheTableUnderTheBareDefault() {
        val declaredStates = DState::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertEquals(declaredStates, dStates.map { it.second.make().javaClass.simpleName }.toSet(), "every DState class needs a representative")
        val declaredEvents = DEvent::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertEquals(declaredEvents, dEvents.map { it.second().javaClass.simpleName }.toSet(), "every DEvent class needs a representative")

        val cfg = PairFsmConfig(pinD)
        assertEquals(PairProfile.R3, cfg.profile)
        laws.bump("D-default-profile-is-r3")
        val fsm = DFsm(cfg)
        val table = dTable()
        val exercised = HashSet<Pair<String, String>>()
        for ((sn, case) in dStates) for ((en, ev) in dEvents) {
            val step = fsm.step(case.make(), ev())
            val expected = table[sn to en] ?: "${case.kind}:"
            assertEquals(expected, step.signature, "D state $sn, event $en")
            exercised += sn to en
            laws.bump("D-pairs-exercised")
        }
        assertEquals(dStates.size * dEvents.size, exercised.size)
        assertTrue(table.keys.all { it in exercised }, "a table cell names a pair that does not exist")
        laws.bump("D-non-noop-cells", table.size)
        println("D matrix (R3): ${dStates.size} states x ${dEvents.size} events = ${exercised.size} pairs exercised, ${table.size} non-no-op cells typed")
    }

    @Test
    fun aForeignConnectionIsRefusedWithoutAStateChangeInEveryStateThatIsBoundToAHello() {
        val fsm = DFsm(PairFsmConfig(pinD))
        val bound = dStates.filter { it.first != "CLOSED" && it.first != "OPEN" }
        for ((sn, case) in bound) for (e in listOf(DEvent.RemoteDecision(true, rS, foreign), DEvent.RemoteDecision(false, null, foreign), DEvent.AckReceived(transcript, foreign))) {
            val s = case.make()
            val step = fsm.step(s, e)
            assertTrue(step.state === s, "$sn: a frame from another connection changed the state")
            assertEquals(listOf<PairEffect>(PairEffect.Refuse(Refusal.PROTOCOL_ERROR)), step.effects, "$sn: ${e.kind}")
            laws.bump("D-foreign-connection-refused-in-every-bound-state")
        }
    }

    @Test
    fun threeWrongCodesDeclineAndAnApprovalWithoutBothTheOpeningAndTheCodeNeverWritesARow() {
        val fsm = DFsm(PairFsmConfig(pinD))
        var s: DState = DState.AwaitDecisions(pinS, commitment, nonceD, 125_000, local = false, remote = false)
        val log = ArrayList<String>()
        for (e in listOf(DEvent.RemoteDecision(true, rS), DEvent.LocalDecision(true, "000 000"), DEvent.LocalDecision(true, "111 111"), DEvent.LocalDecision(true, "222 222"))) {
            val step = fsm.step(s, e)
            assertTrue(step.effects.none { it is PairEffect.WritePairedRow })
            s = step.state
            log += step.signature
        }
        assertEquals(listOf("AWAIT_REMOTE:", "AWAIT_REMOTE:Warn(wrong-code)", "AWAIT_REMOTE:Warn(wrong-code)", "CLOSED:SendDecision(false),Abort(TRIES_EXHAUSTED,PAIRING_REFUSED)"), log)
        laws.bump("D-three-wrong-codes-decline")

        // every ordering of the three inputs that includes a bad opening, a missing code or a missing opening ends without a row
        val inputs = listOf<() -> DEvent>(
            { DEvent.RemoteDecision(true, rS) }, { DEvent.LocalDecision(true, code) }, { DEvent.LocalDecision(true) }, { DEvent.RemoteDecision(true, ByteArray(32) { 1 }) }, { DEvent.RemoteDecision(true) },
        )
        for (a in inputs.indices) for (b in inputs.indices) for (c in inputs.indices) {
            var st: DState = DState.AwaitDecisions(pinS, commitment, nonceD, 125_000, local = false, remote = false)
            var wrote = false
            var opened = false
            var coded = false
            for (i in listOf(a, b, c)) {
                val ev = inputs[i]()
                val step = fsm.step(st, ev)
                if (step.effects.any { it is PairEffect.WritePairedRow }) wrote = true
                if (ev is DEvent.RemoteDecision && ev.reveal != null && ev.reveal.contentEquals(rS) && st is DState.AwaitDecisions) opened = true
                if (ev is DEvent.LocalDecision && ev.typedCode == code) coded = true
                if (st is DState.Closed) break
                st = step.state
                if (st is DState.Closed || st is DState.Committing) break
            }
            if (wrote) assertTrue(opened && coded, "a row was requested without the opening and the right code: $a$b$c")
            laws.bump("D-no-row-without-opening-and-code")
        }
    }

    // ------------------------------------------------------------------------------------------------ S

    private val qrText = "asom-pair:1?k=${pinD.nodeId}&a=192.168.1.40:11436,100.101.7.9:11436&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&x=1790000120&n=Dell%20tower"
    private val now = 1_790_000_000L
    private val payload = (QrUri.parse(qrText, now) as QrParse.Ok).payload

    private class SCase(val kind: String, val make: () -> SState)

    private val sStates: List<Pair<String, SCase>> = listOf(
        "IDLE" to SCase("IDLE") { SState.Idle },
        "CONFIRM_CONNECT" to SCase("CONFIRM_CONNECT") { SState.ConfirmConnect(payload) },
        "DIALING" to SCase("DIALING") { SState.Dialing(payload, 0) },
        "DIALING_LAST" to SCase("DIALING") { SState.Dialing(payload, 1) },
        "SENT_HELLO" to SCase("SENT_HELLO") { SState.SentHello(payload, rS) },
        "AWAIT_NONE" to SCase("AWAIT_NONE") { SState.AwaitDecisions(payload, rS, nonceD, 125_000, local = false, remote = false) },
        "AWAIT_LOCAL" to SCase("AWAIT_LOCAL") { SState.AwaitDecisions(payload, rS, nonceD, 125_000, local = true, remote = false) },
        "AWAIT_REMOTE" to SCase("AWAIT_REMOTE") { SState.AwaitDecisions(payload, rS, nonceD, 125_000, local = false, remote = true) },
        "AWAIT_COMMIT" to SCase("AWAIT_COMMIT") { SState.AwaitCommit(payload, rS, nonceD, 125_000) },
        "WRITING" to SCase("WRITING") { SState.Writing(transcript) },
        "DONE" to SCase("DONE") { SState.Done },
        "FAILED" to SCase("FAILED") { SState.Failed(AbortReason.TIMEOUT, null) },
    )

    private val sEvents: List<Pair<String, () -> SEvent>> = listOf(
        "ScannedOk" to { SEvent.Scanned(QrUri.parse(qrText, now)) },
        "ScannedLate" to { SEvent.Scanned(QrUri.parse(qrText, now + 1_000)) },
        "ScannedBad" to { SEvent.Scanned(QrUri.parse("$qrText&z=1", now)) },
        "ConfirmYes" to { SEvent.UserConfirmConnect(true, 0) },
        "ConfirmNo" to { SEvent.UserConfirmConnect(false, 0) },
        "DialOk" to { SEvent.DialResult(pinD, rS, 100) },
        "DialFail" to { SEvent.DialResult(null, rS, 100) },
        "DialWrongPin" to { SEvent.DialResult(pinS, rS, 100) },
        "ChallengeReceived" to { SEvent.ChallengeReceived(nonceD, 200) },
        "ErrorWindowClosed" to { SEvent.ErrorReceived("PAIRING_WINDOW_CLOSED") },
        "ErrorOther" to { SEvent.ErrorReceived("PAIRING_PROOF_INVALID") },
        "LocalApprove" to { SEvent.LocalDecision(true) },
        "LocalDecline" to { SEvent.LocalDecision(false) },
        "RemoteApprove" to { SEvent.RemoteDecision(true) },
        "RemoteDecline" to { SEvent.RemoteDecision(false) },
        "CommitGood" to { SEvent.CommitReceived(transcript) },
        "CommitBad" to { SEvent.CommitReceived(ByteArray(32)) },
        "RowDurable" to { SEvent.RowDurable },
        "RowWriteFailed" to { SEvent.RowWriteFailed },
        "TickEarly" to { SEvent.Tick(1_000) },
        "TickLate" to { SEvent.Tick(1_000_000) },
        "UserCancel" to { SEvent.UserCancel },
        "ConnectionLost" to { SEvent.ConnectionLost },
    )

    private val sHello = "SendHello(${Hex.encode(PairCrypto.proof(secret, pinD, pinS, commitment).copyOf(4))})"
    private val declinedRemote = "FAILED:Abort(DECLINED_REMOTE,-)"
    private val declinedLocal = "FAILED:SendDecision(false),Abort(DECLINED_LOCAL,-)"
    private val awaitCommon = mapOf(
        "ErrorWindowClosed" to declinedRemote, "ErrorOther" to declinedRemote, "TickLate" to "FAILED:Abort(TIMEOUT,-)", "UserCancel" to declinedLocal,
        "ConnectionLost" to "FAILED:Abort(CONNECTION_LOST,-)", "LocalDecline" to declinedLocal, "RemoteDecline" to declinedRemote,
    )

    private fun sTable(): Map<Pair<String, String>, String> {
        val t = LinkedHashMap<Pair<String, String>, String>()
        fun put(state: String, event: String, expected: String) {
            assertTrue(t.put(state to event, expected) == null, "table cell $state/$event typed twice")
        }
        put("IDLE", "ScannedOk", "CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)")
        put("IDLE", "ScannedLate", "CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)")
        put("IDLE", "ScannedBad", "IDLE:ScanRejected(UNKNOWN_FIELD)")
        put("CONFIRM_CONNECT", "ConfirmYes", "DIALING:Dial(192.168.1.40:11436)")
        put("CONFIRM_CONNECT", "ConfirmNo", "IDLE:")
        put("CONFIRM_CONNECT", "UserCancel", "IDLE:")
        put("DIALING", "DialOk", "SENT_HELLO:$sHello")
        put("DIALING", "DialFail", "DIALING:Dial(100.101.7.9:11436)")
        put("DIALING", "DialWrongPin", "DIALING:Dial(100.101.7.9:11436)")
        put("DIALING", "UserCancel", "FAILED:Abort(CANCELLED,-)")
        put("DIALING_LAST", "DialOk", "SENT_HELLO:$sHello")
        put("DIALING_LAST", "DialFail", "FAILED:Abort(CONNECTION_LOST,-)")
        put("DIALING_LAST", "DialWrongPin", "FAILED:Abort(CONNECTION_LOST,-)")
        put("DIALING_LAST", "UserCancel", "FAILED:Abort(CANCELLED,-)")
        put("SENT_HELLO", "ChallengeReceived", "AWAIT_NONE:ShowConsent(865 412)")
        put("SENT_HELLO", "ErrorWindowClosed", "FAILED:Abort(WINDOW_EXPIRED,-)")
        put("SENT_HELLO", "ErrorOther", declinedRemote)
        put("SENT_HELLO", "ConnectionLost", "FAILED:Abort(CONNECTION_LOST,-)")
        put("SENT_HELLO", "UserCancel", "FAILED:Abort(CANCELLED,-)")
        for (e in listOf("RemoteApprove", "RemoteDecline", "CommitGood", "CommitBad")) put("SENT_HELLO", e, "FAILED:Abort(PROTOCOL,-)")
        for (s in listOf("AWAIT_NONE", "AWAIT_LOCAL", "AWAIT_REMOTE")) {
            for ((e, x) in awaitCommon) put(s, e, x)
            put(s, "CommitGood", "FAILED:Abort(PROTOCOL,-)")
            put(s, "CommitBad", "FAILED:Abort(PROTOCOL,-)")
        }
        put("AWAIT_NONE", "LocalApprove", "AWAIT_LOCAL:SendDecision(true,reveal)")
        put("AWAIT_NONE", "RemoteApprove", "AWAIT_REMOTE:")
        put("AWAIT_LOCAL", "RemoteApprove", "AWAIT_COMMIT:")
        put("AWAIT_REMOTE", "LocalApprove", "AWAIT_COMMIT:SendDecision(true,reveal)")
        for ((e, x) in awaitCommon) put("AWAIT_COMMIT", e, x)
        put("AWAIT_COMMIT", "CommitGood", "WRITING:WritePairedRow")
        put("AWAIT_COMMIT", "CommitBad", "FAILED:Abort(TRANSCRIPT_MISMATCH,-)")
        put("WRITING", "RowDurable", "DONE:SendAck($t8),Done")
        put("WRITING", "RowWriteFailed", "FAILED:Abort(WRITE_FAILED,-)")
        return t
    }

    @Test
    fun everySStatePairIsExercisedAndMatchesTheTableUnderTheBareDefault() {
        val declaredStates = SState::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertEquals(declaredStates, sStates.map { it.second.make().javaClass.simpleName }.toSet(), "every SState class needs a representative")
        val declaredEvents = SEvent::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertEquals(declaredEvents, sEvents.map { it.second().javaClass.simpleName }.toSet(), "every SEvent class needs a representative")

        val fsm = SFsm(PairFsmConfig(pinS))
        val table = sTable()
        val exercised = HashSet<Pair<String, String>>()
        for ((sn, case) in sStates) for ((en, ev) in sEvents) {
            val step = fsm.step(case.make(), ev())
            val expected = table[sn to en] ?: "${case.kind}:"
            assertEquals(expected, step.signature, "S state $sn, event $en")
            exercised += sn to en
            laws.bump("S-pairs-exercised")
        }
        assertEquals(sStates.size * sEvents.size, exercised.size)
        assertTrue(table.keys.all { it in exercised }, "a table cell names a pair that does not exist")
        laws.bump("S-non-noop-cells", table.size)
        println("S matrix (R3): ${sStates.size} states x ${sEvents.size} events = ${exercised.size} pairs exercised, ${table.size} non-no-op cells typed")
    }
}
