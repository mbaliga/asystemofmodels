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
 * The exhaustive transition tests of the two pairing state machines (trust.md 4.6). Every (state, event) pair of the cross product is run and
 * compared with an expected-result table typed from the spec; a pair that is not in the table must be a no-op (state kind unchanged, no effects).
 * The test fails if any state or event class of the code has no representative here, and counts the pairs it exercised. Evidence label: LAB, oracle: self.
 */
class PairingFsmExhaustiveTest {
    companion object {
        val laws = LawCounters("pairing-fsm")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("D-pairs-exercised", "S-pairs-exercised", "D-non-noop-cells", "S-non-noop-cells", "D-window-closes-after-3-tries", "registry-L6-single-use", "registry-L7-revoked-pin-refused", "pairing-L1-no-row-without-both"))
    }

    private val pinD = Pin.ofHash(java.security.MessageDigest.getInstance("SHA-256").digest("asom-vector/spki/D".toByteArray()))
    private val pinS = Pin.ofHash(java.security.MessageDigest.getInstance("SHA-256").digest("asom-vector/spki/S".toByteArray()))
    private val secret = ByteArray(32) { it.toByte() }
    private val nonceS = ByteArray(32) { 0xA5.toByte() }
    private val nonceD = ByteArray(32) { 0x5A.toByte() }
    private val transcript = PairCrypto.transcript(pinD, pinS, nonceS, nonceD)
    private val goodProof = PairCrypto.proof(secret, pinD, pinS, nonceS)

    // ------------------------------------------------------------------------------------------------ D

    private val dStates: List<Pair<String, () -> DState>> = listOf(
        "CLOSED" to { DState.Closed },
        "OPEN" to { DState.Open(secret, 120_000, 0) },
        "CONSUMED" to { DState.Consumed(pinS, nonceS, nonceD, 5_000) },
        "AWAIT_NONE" to { DState.AwaitDecisions(pinS, nonceS, nonceD, 125_000, local = false, remote = false) },
        "AWAIT_LOCAL" to { DState.AwaitDecisions(pinS, nonceS, nonceD, 125_000, local = true, remote = false) },
        "AWAIT_REMOTE" to { DState.AwaitDecisions(pinS, nonceS, nonceD, 125_000, local = false, remote = true) },
        "COMMITTING" to { DState.Committing(pinS, nonceS, nonceD) },
        "AWAIT_ACK" to { DState.AwaitAck(transcript, 16_000) },
    )

    private fun hello(proof: ByteArray, status: StatusLookup) = DEvent.HelloReceived(pinS, nonceS, proof, status, nonceD, 1_000)

    private val dEvents: List<Pair<String, () -> DEvent>> = listOf(
        "UserOpenWindow" to { DEvent.UserOpenWindow(secret, 0) },
        "HelloValid" to { hello(goodProof, StatusLookup.Absent) },
        "HelloBadProof" to { hello(ByteArray(32), StatusLookup.Absent) },
        "HelloRevoked" to { hello(goodProof, StatusLookup.Known(PeerStatus.REVOKED)) },
        "HelloExisting" to { hello(goodProof, StatusLookup.Known(PeerStatus.PAIRED)) },
        "HelloCorrupt" to { hello(goodProof, StatusLookup.Corrupt(9)) },
        "ChallengeSent" to { DEvent.ChallengeSent },
        "LocalApprove" to { DEvent.LocalDecision(true) },
        "LocalDecline" to { DEvent.LocalDecision(false) },
        "RemoteApprove" to { DEvent.RemoteDecision(true) },
        "RemoteDecline" to { DEvent.RemoteDecision(false) },
        "TickEarly" to { DEvent.Tick(1_000) },
        "TickLate" to { DEvent.Tick(1_000_000) },
        "UserCancel" to { DEvent.UserCancel },
        "ConnectionLost" to { DEvent.ConnectionLost },
        "RowDurable" to { DEvent.RowDurable(6_000) },
        "RowWriteFailed" to { DEvent.RowWriteFailed },
        "AckGood" to { DEvent.AckReceived(transcript) },
        "AckBad" to { DEvent.AckReceived(ByteArray(32)) },
        "SecondUnknown" to { DEvent.SecondUnknownConnection },
    )

    private val helloCols = listOf("HelloValid", "HelloBadProof", "HelloRevoked", "HelloExisting", "HelloCorrupt")
    private val refusedClosed = "Refuse(PAIRING_WINDOW_CLOSED)"
    private val decideAbortLocal = "SendDecision(false),Abort(DECLINED_LOCAL,PAIRING_REFUSED)"

    private fun dTable(): Map<Pair<String, String>, String> {
        val t = LinkedHashMap<Pair<String, String>, String>()
        fun put(state: String, event: String, expected: String) {
            assertTrue(t.put(state to event, expected) == null, "table cell $state/$event typed twice")
        }
        for (h in helloCols) for (s in listOf("CLOSED", "CONSUMED", "AWAIT_NONE", "AWAIT_LOCAL", "AWAIT_REMOTE", "COMMITTING", "AWAIT_ACK")) put(s, h, "$s:$refusedClosed")
        put("CLOSED", "UserOpenWindow", "OPEN:WindowOpened")
        put("OPEN", "HelloValid", "CONSUMED:SendChallenge")
        put("OPEN", "HelloBadProof", "OPEN:Refuse(PAIRING_PROOF_INVALID)")
        put("OPEN", "HelloRevoked", "CLOSED:Warn(revoked-device-tried-to-pair),Abort(REVOKED_PEER,PAIRING_REFUSED)")
        put("OPEN", "HelloExisting", "CLOSED:Abort(PEER_EXISTS,PAIRING_REFUSED)")
        put("OPEN", "HelloCorrupt", "CLOSED:Abort(REGISTRY_UNREADABLE,PAIRING_REFUSED)")
        for (e in listOf("RemoteApprove", "RemoteDecline", "AckGood", "AckBad")) put("OPEN", e, "OPEN:Refuse(PROTOCOL_ERROR)")
        put("OPEN", "TickLate", "CLOSED:Closed(window-expired)")
        put("OPEN", "UserCancel", "CLOSED:Closed(window-cancelled)")

        put("CONSUMED", "ChallengeSent", "AWAIT_NONE:ShowConsent(865 412)")
        for (e in listOf("RemoteApprove", "RemoteDecline", "AckGood", "AckBad")) put("CONSUMED", e, "CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)")
        put("CONSUMED", "TickLate", "CLOSED:Abort(TIMEOUT,PAIRING_REFUSED)")
        put("CONSUMED", "UserCancel", "CLOSED:Abort(CANCELLED,PAIRING_REFUSED)")
        put("CONSUMED", "ConnectionLost", "CLOSED:Abort(CONNECTION_LOST,-)")
        put("CONSUMED", "SecondUnknown", "CONSUMED:Warn(second-connection)")

        for (s in listOf("AWAIT_NONE", "AWAIT_LOCAL", "AWAIT_REMOTE")) {
            put(s, "LocalDecline", "CLOSED:$decideAbortLocal")
            put(s, "RemoteDecline", "CLOSED:Abort(DECLINED_REMOTE,PAIRING_REFUSED)")
            put(s, "TickLate", "CLOSED:Abort(TIMEOUT,PAIRING_REFUSED)")
            put(s, "UserCancel", "CLOSED:$decideAbortLocal")
            put(s, "ConnectionLost", "CLOSED:Abort(CONNECTION_LOST,-)")
            put(s, "AckGood", "CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)")
            put(s, "AckBad", "CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)")
            put(s, "SecondUnknown", "$s:Warn(second-connection)")
        }
        put("AWAIT_NONE", "LocalApprove", "AWAIT_LOCAL:SendDecision(true)")
        put("AWAIT_NONE", "RemoteApprove", "AWAIT_REMOTE:")
        put("AWAIT_LOCAL", "RemoteApprove", "COMMITTING:WritePairedRow")
        put("AWAIT_REMOTE", "LocalApprove", "COMMITTING:SendDecision(true),WritePairedRow")

        put("COMMITTING", "RowDurable", "AWAIT_ACK:SendCommit(${Hex.encode(transcript.copyOf(4))})")
        put("COMMITTING", "RowWriteFailed", "CLOSED:Abort(WRITE_FAILED,PAIRING_REFUSED)")
        put("COMMITTING", "ConnectionLost", "CLOSED:MarkUnconfirmed(connection-lost-while-committing),Abort(CONNECTION_LOST,-)")
        put("COMMITTING", "AckGood", "CLOSED:MarkUnconfirmed(acknowledgement-before-commit),Abort(PROTOCOL,PROTOCOL_ERROR)")
        put("COMMITTING", "AckBad", "CLOSED:MarkUnconfirmed(acknowledgement-before-commit),Abort(PROTOCOL,PROTOCOL_ERROR)")
        put("COMMITTING", "SecondUnknown", "COMMITTING:Warn(second-connection)")

        put("AWAIT_ACK", "AckGood", "CLOSED:Done,Closed(done)")
        put("AWAIT_ACK", "AckBad", "CLOSED:MarkUnconfirmed(transcript-mismatch),Warn(transcript-mismatch),Closed(transcript-mismatch)")
        put("AWAIT_ACK", "TickLate", "CLOSED:MarkUnconfirmed(ack-timeout),Warn(may-not-have-finished),Closed(ack-timeout)")
        put("AWAIT_ACK", "ConnectionLost", "CLOSED:MarkUnconfirmed(connection-lost),Warn(may-not-have-finished),Closed(connection-lost)")
        put("AWAIT_ACK", "SecondUnknown", "AWAIT_ACK:Warn(second-connection)")
        return t
    }

    @Test
    fun everyDStatePairIsExercisedAndMatchesTheTable() {
        val declaredStates = DState::class.java.declaredClasses.map { it.simpleName }.toSet()
        val usedStates = dStates.map { it.second().javaClass.simpleName }.toSet()
        assertEquals(declaredStates, usedStates, "every DState class needs a representative")
        val declaredEvents = DEvent::class.java.declaredClasses.map { it.simpleName }.toSet()
        val usedEvents = dEvents.map { it.second().javaClass.simpleName }.toSet()
        assertEquals(declaredEvents, usedEvents, "every DEvent class needs a representative")

        val fsm = DFsm(PairFsmConfig(pinD))
        val table = dTable()
        val exercised = HashSet<Pair<String, String>>()
        for ((sn, mk) in dStates) for ((en, ev) in dEvents) {
            val step = fsm.step(mk(), ev())
            val expected = table[sn to en] ?: "$sn:"
            assertEquals(expected, step.signature, "D state $sn, event $en")
            exercised += sn to en
            laws.bump("D-pairs-exercised")
        }
        assertEquals(dStates.size * dEvents.size, exercised.size)
        assertTrue(table.keys.all { it in exercised }, "a table cell names a pair that does not exist")
        laws.bump("D-non-noop-cells", table.size)
        println("D matrix: ${dStates.size} states x ${dEvents.size} events = ${exercised.size} pairs exercised, ${table.size} non-no-op cells typed")
    }

    @Test
    fun threeInvalidProofsCloseTheWindowAndTheCountCarries() {
        val fsm = DFsm(PairFsmConfig(pinD))
        var s: DState = DState.Open(secret, 120_000, 0)
        val bad = hello(ByteArray(32), StatusLookup.Absent)
        repeat(2) {
            val st = fsm.step(s, bad)
            assertEquals("OPEN:Refuse(PAIRING_PROOF_INVALID)", st.signature)
            assertEquals(it + 1, (st.state as DState.Open).tries)
            s = st.state
        }
        assertEquals("CLOSED:Abort(TRIES_EXHAUSTED,PAIRING_PROOF_INVALID)", fsm.step(s, bad).signature)
        // A valid proof still works after two bad ones (the window is not closed until the third).
        assertEquals("CONSUMED:SendChallenge", fsm.step(s, hello(goodProof, StatusLookup.Absent)).signature)
        laws.bump("D-window-closes-after-3-tries")
    }

    @Test
    fun aWindowAcceptsAtMostOneValidProofAndNeverAdmitsARevokedPin() {
        val fsm = DFsm(PairFsmConfig(pinD))
        val s1 = fsm.step(DState.Open(secret, 120_000, 0), hello(goodProof, StatusLookup.Absent))
        assertTrue(s1.state is DState.Consumed)
        val other = Pin.ofHash(ByteArray(32) { 3 })
        val second = fsm.step(s1.state, DEvent.HelloReceived(other, nonceS, PairCrypto.proof(secret, pinD, other, nonceS), StatusLookup.Absent, nonceD, 2_000))
        assertEquals("CONSUMED:$refusedClosed", second.signature)
        laws.bump("registry-L6-single-use")
        for (status in listOf(StatusLookup.Known(PeerStatus.REVOKED))) {
            for (proof in listOf(goodProof, ByteArray(32))) {
                val r = fsm.step(DState.Open(secret, 120_000, 0), hello(proof, status))
                assertTrue(r.state is DState.Closed && r.effects.none { it is PairEffect.SendChallenge }, "a REVOKED pin reached the challenge")
                laws.bump("registry-L7-revoked-pin-refused")
            }
        }
    }

    @Test
    fun aRowIsWrittenOnlyAfterBothApprovalsInEitherOrder() {
        val fsm = DFsm(PairFsmConfig(pinD))
        val start = DState.AwaitDecisions(pinS, nonceS, nonceD, 125_000, local = false, remote = false)
        val orders = listOf(
            listOf(DEvent.LocalDecision(true), DEvent.RemoteDecision(true)), listOf(DEvent.RemoteDecision(true), DEvent.LocalDecision(true)),
        )
        for (order in orders) {
            var s: DState = start
            val wrote = ArrayList<Boolean>()
            for (e in order) {
                val st = fsm.step(s, e)
                wrote += st.effects.any { it is PairEffect.WritePairedRow }
                s = st.state
            }
            assertEquals(listOf(false, true), wrote, "the row is written exactly at the second approval")
            laws.bump("pairing-L1-no-row-without-both")
        }
        for (single in listOf(DEvent.LocalDecision(true), DEvent.RemoteDecision(true))) {
            val st = fsm.step(start, single)
            assertTrue(st.effects.none { it is PairEffect.WritePairedRow })
            laws.bump("pairing-L1-no-row-without-both")
        }
    }

    // ------------------------------------------------------------------------------------------------ S

    private val qrText = "asom-pair:1?k=${pinD.nodeId}&a=192.168.1.40:11436,100.101.7.9:11436&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&x=1790000120&n=Dell%20tower"
    private val now = 1_790_000_000L
    private val payload = (QrUri.parse(qrText, now) as QrParse.Ok).payload

    private val sStates: List<Pair<String, () -> SState>> = listOf(
        "IDLE" to { SState.Idle },
        "CONFIRM_CONNECT" to { SState.ConfirmConnect(payload) },
        "DIALING" to { SState.Dialing(payload, 0) },
        "DIALING_LAST" to { SState.Dialing(payload, 1) },
        "SENT_HELLO" to { SState.SentHello(payload, nonceS) },
        "AWAIT_NONE" to { SState.AwaitDecisions(payload, nonceS, nonceD, 125_000, local = false, remote = false) },
        "AWAIT_LOCAL" to { SState.AwaitDecisions(payload, nonceS, nonceD, 125_000, local = true, remote = false) },
        "AWAIT_REMOTE" to { SState.AwaitDecisions(payload, nonceS, nonceD, 125_000, local = false, remote = true) },
        "AWAIT_COMMIT" to { SState.AwaitCommit(payload, nonceS, nonceD, 125_000) },
        "WRITING" to { SState.Writing(transcript) },
        "DONE" to { SState.Done },
        "FAILED" to { SState.Failed(AbortReason.TIMEOUT, null) },
    )

    private val sEvents: List<Pair<String, () -> SEvent>> = listOf(
        "ScannedOk" to { SEvent.Scanned(QrUri.parse(qrText, now)) },
        "ScannedBad" to { SEvent.Scanned(QrUri.parse(qrText, now + 1_000)) },
        "ConfirmYes" to { SEvent.UserConfirmConnect(true, 0) },
        "ConfirmNo" to { SEvent.UserConfirmConnect(false, 0) },
        "DialOk" to { SEvent.DialResult(pinD, nonceS, 100) },
        "DialFail" to { SEvent.DialResult(null, nonceS, 100) },
        "DialWrongPin" to { SEvent.DialResult(pinS, nonceS, 100) },
        "ChallengeReceived" to { SEvent.ChallengeReceived(nonceD, 200) },
        "ErrorReceived" to { SEvent.ErrorReceived("PAIRING_WINDOW_CLOSED") },
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

    private val sHello = "SendHello(${Hex.encode(PairCrypto.proof(secret, pinD, pinS, nonceS).copyOf(4))})"
    private val t8 = Hex.encode(transcript.copyOf(4))
    private val awaitCommon = mapOf(
        "ErrorReceived" to "FAILED:Abort(DECLINED_REMOTE,-)",
        "TickLate" to "FAILED:Abort(TIMEOUT,-)",
        "UserCancel" to "FAILED:SendDecision(false),Abort(DECLINED_LOCAL,-)",
        "ConnectionLost" to "FAILED:Abort(CONNECTION_LOST,-)",
        "LocalDecline" to "FAILED:SendDecision(false),Abort(DECLINED_LOCAL,-)",
        "RemoteDecline" to "FAILED:Abort(DECLINED_REMOTE,-)",
    )

    private fun sTable(): Map<Pair<String, String>, String> {
        val t = LinkedHashMap<Pair<String, String>, String>()
        fun put(state: String, event: String, expected: String) {
            assertTrue(t.put(state to event, expected) == null, "table cell $state/$event typed twice")
        }
        put("IDLE", "ScannedOk", "CONFIRM_CONNECT:ShowConnectConfirm(Dell tower)")
        put("IDLE", "ScannedBad", "IDLE:ScanRejected(EXPIRED)")
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
        put("SENT_HELLO", "ErrorReceived", "FAILED:Abort(DECLINED_REMOTE,-)")
        put("SENT_HELLO", "ConnectionLost", "FAILED:Abort(CONNECTION_LOST,-)")
        put("SENT_HELLO", "UserCancel", "FAILED:Abort(CANCELLED,-)")
        for (e in listOf("RemoteApprove", "RemoteDecline", "CommitGood", "CommitBad")) put("SENT_HELLO", e, "FAILED:Abort(PROTOCOL,-)")
        for (s in listOf("AWAIT_NONE", "AWAIT_LOCAL", "AWAIT_REMOTE")) {
            for ((e, x) in awaitCommon) put(s, e, x)
            put(s, "CommitGood", "FAILED:Abort(PROTOCOL,-)")
            put(s, "CommitBad", "FAILED:Abort(PROTOCOL,-)")
        }
        put("AWAIT_NONE", "LocalApprove", "AWAIT_LOCAL:SendDecision(true)")
        put("AWAIT_NONE", "RemoteApprove", "AWAIT_REMOTE:")
        put("AWAIT_LOCAL", "RemoteApprove", "AWAIT_COMMIT:")
        put("AWAIT_REMOTE", "LocalApprove", "AWAIT_COMMIT:SendDecision(true)")
        for ((e, x) in awaitCommon) put("AWAIT_COMMIT", e, x)
        put("AWAIT_COMMIT", "CommitGood", "WRITING:WritePairedRow")
        put("AWAIT_COMMIT", "CommitBad", "FAILED:Abort(TRANSCRIPT_MISMATCH,-)")
        put("WRITING", "RowDurable", "DONE:SendAck($t8),Done")
        put("WRITING", "RowWriteFailed", "FAILED:Abort(WRITE_FAILED,-)")
        return t
    }

    @Test
    fun everySStatePairIsExercisedAndMatchesTheTable() {
        val declaredStates = SState::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertEquals(declaredStates, sStates.map { it.second().javaClass.simpleName }.toSet(), "every SState class needs a representative")
        val declaredEvents = SEvent::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertEquals(declaredEvents, sEvents.map { it.second().javaClass.simpleName }.toSet(), "every SEvent class needs a representative")

        val fsm = SFsm(PairFsmConfig(pinS))
        val table = sTable()
        val exercised = HashSet<Pair<String, String>>()
        for ((sn, mk) in sStates) for ((en, ev) in sEvents) {
            val step = fsm.step(mk(), ev())
            val kind = if (sn == "DIALING_LAST") "DIALING" else sn
            val expected = table[sn to en] ?: "$kind:"
            assertEquals(expected, step.signature, "S state $sn, event $en")
            exercised += sn to en
            laws.bump("S-pairs-exercised")
        }
        assertEquals(sStates.size * sEvents.size, exercised.size)
        assertTrue(table.keys.all { it in exercised }, "a table cell names a pair that does not exist")
        laws.bump("S-non-noop-cells", table.size)
        println("S matrix: ${sStates.size} states x ${sEvents.size} events = ${exercised.size} pairs exercised, ${table.size} non-no-op cells typed")
    }
}
