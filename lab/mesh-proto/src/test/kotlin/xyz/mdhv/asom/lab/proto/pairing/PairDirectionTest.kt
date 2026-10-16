package xyz.mdhv.asom.lab.proto.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.ledger.MemorySink
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.proto.session.CLOCK_BASE
import xyz.mdhv.asom.lab.proto.session.Frames
import xyz.mdhv.asom.lab.proto.session.Log
import xyz.mdhv.asom.lab.proto.session.MemConnection
import xyz.mdhv.asom.lab.proto.session.PairingChannel
import xyz.mdhv.asom.lab.proto.session.IdRegistry
import xyz.mdhv.asom.lab.proto.session.SeededIds
import xyz.mdhv.asom.lab.proto.session.SpySink
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.FrameTypes

/**
 * The direction of `PAIR_*` frames (ERRATA ERR-PW-4, ERR-FX2-3) and the per-connection budget of frames that each force a durable row (ERR-FX2-6).
 * The client end of the in-memory pair is the TLS client, which trust.md 4.3 makes S. Evidence label: LAB, oracle: self.
 */
class PairDirectionTest {
    companion object {
        val laws = LawCounters("pair-direction")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(
            setOf(
                "tls-role-refuses-wrong-direction", "tls-role-accepts-right-direction", "hello-orientation-refuses-cold-start", "hello-orientation-refuses-reflection", "outbound-refused",
                "frame-budget-closes", "extension-budget-closes", "legit-ceremony-fits-the-budget", "default-is-tls-role", "tls-role-refuses-cold-frame",
            ),
        )
    }

    /** [orientation] null leaves the channel's own default in place, which R4-T-10 makes the TLS role. */
    private class Kit(orientation: PairOrientation? = null) {
        val log = Log()
        val memC = MemorySink()
        val memS = MemorySink()
        val conns = MemConnection.pair(null, null, log, "C>S", "S<C", ConnMode.PAIRING)
        private val ledgerC = NodeLedger("C", SpySink("C", memC, log)) { CLOCK_BASE + log.events.size }
        private val ledgerS = NodeLedger("S", SpySink("S", memS, log)) { CLOCK_BASE + log.events.size }
        val client = if (orientation == null) PairingChannel(ledgerC, conns.first, SeededIds(1), IdRegistry()) else PairingChannel(ledgerC, conns.first, SeededIds(1), IdRegistry(), orientation = orientation)
        val server = if (orientation == null) PairingChannel(ledgerS, conns.second, SeededIds(2), IdRegistry()) else PairingChannel(ledgerS, conns.second, SeededIds(2), IdRegistry(), orientation = orientation)
        val atClient = ArrayList<Int>()
        val atServer = ArrayList<Int>()

        init {
            client.onFrame = { t, _ -> atClient += t }
            server.onFrame = { t, _ -> atServer += t }
        }

        fun pump() {
            while (client.pumpAvailable(64) + server.pumpAvailable(64) > 0) Unit
        }
    }

    private val b32 = ByteArray(32) { 3 }

    private fun payload(type: Int): ByteArray = when (type) {
        FrameTypes.PAIR_HELLO -> PairMessages.encodeHello(PairHello(b32, b32, "Phone", "android", "strongbox", emptyList()))
        FrameTypes.PAIR_CHALLENGE -> PairMessages.encodeChallenge(PairChallenge(b32, "Desk", "linux", "file"))
        FrameTypes.PAIR_DECISION -> PairMessages.encodeDecision(PairDecision(true))
        FrameTypes.PAIR_COMMIT -> PairMessages.encodeCommit(PairCommit(b32))
        else -> PairMessages.encodeCommitAck(PairCommitAck(b32))
    }

    private fun raw(type: Int) = Frames.encode(type, 1, payload(type))

    private val allPair = listOf(FrameTypes.PAIR_HELLO, FrameTypes.PAIR_CHALLENGE, FrameTypes.PAIR_DECISION, FrameTypes.PAIR_COMMIT, FrameTypes.PAIR_COMMIT_ACK)

    private fun errorsWritten(k: Kit, mem: MemorySink) = mem.all().count { it.meshKind == MeshKind.CONTROL && it.meshCode == "ERROR:PROTOCOL_ERROR" }

    private val sendsToD = listOf(FrameTypes.PAIR_HELLO, FrameTypes.PAIR_DECISION, FrameTypes.PAIR_COMMIT_ACK)
    private val sendsToS = listOf(FrameTypes.PAIR_CHALLENGE, FrameTypes.PAIR_DECISION, FrameTypes.PAIR_COMMIT)

    @Test
    fun withTheTlsRoleOrientationDAcceptsOnlyWhatSMaySendAndSAcceptsOnlyWhatDMaySendOnceTheHelloHasCrossed() {
        for (type in allPair) {
            val toD = Kit(PairOrientation.FROM_TLS_ROLE)
            if (type != FrameTypes.PAIR_HELLO) {
                toD.client.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO))
                toD.pump()
                toD.atServer.clear()
            }
            toD.conns.first.writeRaw(raw(type))
            toD.pump()
            if (type in sendsToD) {
                assertEquals(listOf(type), toD.atServer, "D (TLS server) receives ${FrameTypes.nameOf(type)} from S")
                assertTrue(!toD.server.isClosed)
                laws.bump("tls-role-accepts-right-direction")
            } else {
                assertEquals(emptyList(), toD.atServer, "${FrameTypes.nameOf(type)} from the TLS client is the wrong direction")
                assertTrue(toD.server.isClosed)
                assertEquals(1, errorsWritten(toD, toD.memS))
                laws.bump("tls-role-refuses-wrong-direction")
            }

            val toS = Kit(PairOrientation.FROM_TLS_ROLE)
            toS.client.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO))
            toS.pump()
            toS.conns.second.writeRaw(raw(type))
            toS.pump()
            if (type in sendsToS) {
                assertEquals(listOf(type), toS.atClient, "S (TLS client) receives ${FrameTypes.nameOf(type)} from D")
                laws.bump("tls-role-accepts-right-direction")
            } else {
                assertEquals(emptyList(), toS.atClient, "${FrameTypes.nameOf(type)} from the TLS server is the wrong direction")
                assertTrue(toS.client.isClosed)
                assertEquals(1, errorsWritten(toS, toS.memC))
                laws.bump("tls-role-refuses-wrong-direction")
            }
        }
    }

    @Test
    fun theDefaultOrientationIsTheTlsRoleSoTheListenerCannotSendTheHelloAndTheDiallerCanNot() {
        val k = Kit()
        assertFailsWith<IllegalStateException> { k.server.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO)) }
        assertFailsWith<IllegalStateException> { k.client.send(FrameTypes.PAIR_CHALLENGE, payload(FrameTypes.PAIR_CHALLENGE)) }
        assertEquals(0, k.memC.all().size + k.memS.all().size, "a refused send writes no row")
        k.client.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO))
        k.pump()
        assertEquals(listOf(FrameTypes.PAIR_HELLO), k.atServer)
        laws.bump("default-is-tls-role")
        val reversed = Kit()
        reversed.conns.second.writeRaw(raw(FrameTypes.PAIR_HELLO))
        reversed.pump()
        assertEquals(emptyList(), reversed.atClient, "a hello from the TLS server is the wrong direction by default")
        assertTrue(reversed.client.isClosed)
        laws.bump("default-is-tls-role")
    }

    @Test
    fun aColdFrameIsRefusedByEitherEndUnderTheTlsRole() {
        for (type in sendsToD.filter { it != FrameTypes.PAIR_HELLO }) {
            val k = Kit(PairOrientation.FROM_TLS_ROLE)
            k.conns.first.writeRaw(raw(type))
            k.pump()
            assertEquals(emptyList(), k.atServer, "${FrameTypes.nameOf(type)} cannot be the first frame D sees")
            assertTrue(k.server.isClosed)
            assertEquals(1, errorsWritten(k, k.memS))
            laws.bump("tls-role-refuses-cold-frame")
        }
        for (type in sendsToS) {
            val k = Kit(PairOrientation.FROM_TLS_ROLE)
            k.conns.second.writeRaw(raw(type))
            k.pump()
            assertEquals(emptyList(), k.atClient, "${FrameTypes.nameOf(type)} cannot reach S before S has sent its hello")
            assertTrue(k.client.isClosed)
            assertEquals(1, errorsWritten(k, k.memC))
            laws.bump("tls-role-refuses-cold-frame")
        }
    }

    @Test
    fun theColdRuleDoesNotRefuseAFurtherHelloBecauseTheMachineAnswersItWithTheWindowClosed() {
        val k = Kit(PairOrientation.FROM_TLS_ROLE)
        k.client.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO))
        k.pump()
        k.client.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO))
        k.pump()
        assertEquals(listOf(FrameTypes.PAIR_HELLO, FrameTypes.PAIR_HELLO), k.atServer)
        assertTrue(!k.server.isClosed)
        laws.bump("tls-role-accepts-right-direction")
    }

    @Test
    fun aReflectedFrameIsRefusedUnderBothOrientations() {
        for (o in PairOrientation.entries) {
            val k = Kit(o)
            k.client.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO))
            k.pump()
            assertEquals(listOf(FrameTypes.PAIR_HELLO), k.atServer)
            k.conns.second.writeRaw(raw(FrameTypes.PAIR_HELLO))
            k.pump()
            assertTrue(k.client.isClosed, "$o: S sent a hello, so a hello coming back is a reflection")
            assertEquals(emptyList(), k.atClient)
            laws.bump("hello-orientation-refuses-reflection")
        }
    }

    @Test
    fun underHelloOrientationTheFirstFrameMustBeAHelloAndThenSidesAreFixed() {
        for (type in allPair.filter { it != FrameTypes.PAIR_HELLO }) {
            val k = Kit(PairOrientation.FROM_HELLO)
            k.conns.first.writeRaw(raw(type))
            k.pump()
            assertEquals(emptyList(), k.atServer, "${FrameTypes.nameOf(type)} cannot open a ceremony")
            assertTrue(k.server.isClosed)
            laws.bump("hello-orientation-refuses-cold-start")
        }
        val k = Kit(PairOrientation.FROM_HELLO)
        k.client.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO))
        k.pump()
        k.server.send(FrameTypes.PAIR_CHALLENGE, payload(FrameTypes.PAIR_CHALLENGE))
        k.pump()
        assertEquals(listOf(FrameTypes.PAIR_CHALLENGE), k.atClient)
        k.conns.second.writeRaw(raw(FrameTypes.PAIR_COMMIT_ACK))
        k.pump()
        assertTrue(k.client.isClosed, "a commit acknowledgement comes from S, and S is the hello sender")
        assertEquals(emptyList(), k.atClient.drop(1))
        laws.bump("hello-orientation-refuses-reflection")
    }

    @Test
    fun aNodeCannotSendAFrameItsOwnSideMayNotSend() {
        val tls = Kit(PairOrientation.FROM_TLS_ROLE)
        assertFailsWith<IllegalStateException> { tls.client.send(FrameTypes.PAIR_CHALLENGE, payload(FrameTypes.PAIR_CHALLENGE)) }
        assertFailsWith<IllegalStateException> { tls.server.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO)) }
        val inferred = Kit(PairOrientation.FROM_HELLO)
        assertFailsWith<IllegalStateException> { inferred.server.send(FrameTypes.PAIR_COMMIT, payload(FrameTypes.PAIR_COMMIT)) }
        inferred.client.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO))
        assertFailsWith<IllegalStateException> { inferred.client.send(FrameTypes.PAIR_COMMIT, payload(FrameTypes.PAIR_COMMIT)) }
        assertEquals(1, inferred.memC.all().count { it.meshKind == MeshKind.PAIRING }, "a refused send writes no row")
        laws.bump("outbound-refused", 4)
    }

    @Test
    fun aWholeCeremonyInTheSpecDirectionFitsTheBudgetUnderBothOrientations() {
        for (o in PairOrientation.entries) {
            val k = Kit(o)
            k.client.send(FrameTypes.PAIR_HELLO, payload(FrameTypes.PAIR_HELLO))
            k.pump()
            k.server.send(FrameTypes.PAIR_CHALLENGE, payload(FrameTypes.PAIR_CHALLENGE))
            k.pump()
            k.client.send(FrameTypes.PAIR_DECISION, payload(FrameTypes.PAIR_DECISION))
            k.server.send(FrameTypes.PAIR_DECISION, payload(FrameTypes.PAIR_DECISION))
            k.pump()
            k.server.send(FrameTypes.PAIR_COMMIT, payload(FrameTypes.PAIR_COMMIT))
            k.pump()
            k.client.send(FrameTypes.PAIR_COMMIT_ACK, payload(FrameTypes.PAIR_COMMIT_ACK))
            k.pump()
            assertEquals(listOf(FrameTypes.PAIR_HELLO, FrameTypes.PAIR_DECISION, FrameTypes.PAIR_COMMIT_ACK), k.atServer)
            assertEquals(listOf(FrameTypes.PAIR_CHALLENGE, FrameTypes.PAIR_DECISION, FrameTypes.PAIR_COMMIT), k.atClient)
            assertTrue(!k.client.isClosed && !k.server.isClosed)
            laws.bump("legit-ceremony-fits-the-budget")
        }
    }

    // ------------------------------------------------------------------------------------------------ the budget

    private fun ledgerRows(mem: MemorySink) = mem.all().filter { it.meshKind == MeshKind.CONTROL && it.meshCode == "EXT_IGNORED" }.size

    @Test
    fun aPeerThatSendsExtensionFramesWithoutEndGrowsTheLedgerByAtMostTheBudget() {
        val k = Kit(PairOrientation.FROM_HELLO)
        repeat(1_000) { k.conns.first.writeRaw(Frames.encode(0x85, 0, ByteArray(12) { 1 })) }
        k.pump()
        assertTrue(k.server.isClosed, "the connection is closed when the budget is spent")
        val rows = ledgerRows(k.memS)
        assertTrue(rows <= PairingChannel.MAX_INBOUND_FRAMES, "EXT_IGNORED rows: $rows")
        assertTrue(k.memS.all().size <= PairingChannel.MAX_INBOUND_FRAMES + 3, "all rows: ${k.memS.all().size}")
        assertEquals(1, errorsWritten(k, k.memS))
        laws.bump("extension-budget-closes")
    }

    @Test
    fun theBudgetIsTheSixteenFramesOfR4L25AndTheSeventeenthIsAnErrorRowedOnce() {
        assertEquals(16, PairingChannel.MAX_INBOUND_FRAMES, "LAB_SPEC R4-L-25: a pairing-mode connection admits at most 16 inbound frames")
        val k = Kit()
        k.conns.first.writeRaw(raw(FrameTypes.PAIR_HELLO))
        repeat(15) { k.conns.first.writeRaw(raw(FrameTypes.PAIR_DECISION)) }
        k.pump()
        assertEquals(16, k.atServer.size)
        assertTrue(!k.server.isClosed, "sixteen frames are within the budget")
        k.conns.first.writeRaw(raw(FrameTypes.PAIR_DECISION))
        k.pump()
        assertEquals(16, k.atServer.size, "the seventeenth is not delivered")
        assertTrue(k.server.isClosed)
        assertEquals(1, errorsWritten(k, k.memS))
        laws.bump("frame-budget-closes")
    }

    @Test
    fun aPeerThatRepeatsPairFramesWithoutEndIsClosedAfterTheBudget() {
        val k = Kit(PairOrientation.FROM_TLS_ROLE)
        k.conns.first.writeRaw(raw(FrameTypes.PAIR_HELLO))
        repeat(1_000) { k.conns.first.writeRaw(raw(FrameTypes.PAIR_DECISION)) }
        k.pump()
        assertTrue(k.server.isClosed)
        assertEquals(PairingChannel.MAX_INBOUND_FRAMES, k.atServer.size, "delivered: the hello and then decisions up to the budget")
        assertTrue(k.memS.all().count { it.meshKind == MeshKind.PAIRING } <= PairingChannel.MAX_INBOUND_FRAMES)
        assertEquals(1, errorsWritten(k, k.memS))
        laws.bump("frame-budget-closes")
    }
}
