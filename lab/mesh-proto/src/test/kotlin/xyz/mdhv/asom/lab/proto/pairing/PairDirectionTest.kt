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
                "frame-budget-closes", "extension-budget-closes", "legit-ceremony-fits-the-budget",
            ),
        )
    }

    private class Kit(orientation: PairOrientation) {
        val log = Log()
        val memC = MemorySink()
        val memS = MemorySink()
        val conns = MemConnection.pair(null, null, log, "C>S", "S<C", ConnMode.PAIRING)
        val client = PairingChannel(NodeLedger("C", SpySink("C", memC, log)) { CLOCK_BASE + log.events.size }, conns.first, SeededIds(1), orientation = orientation)
        val server = PairingChannel(NodeLedger("S", SpySink("S", memS, log)) { CLOCK_BASE + log.events.size }, conns.second, SeededIds(2), orientation = orientation)
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

    @Test
    fun withTheTlsRoleOrientationDAcceptsOnlyWhatSMaySendAndSAcceptsOnlyWhatDMaySend() {
        for (type in allPair) {
            val toD = Kit(PairOrientation.FROM_TLS_ROLE)
            toD.conns.first.writeRaw(raw(type))
            toD.pump()
            val dMayReceive = type in listOf(FrameTypes.PAIR_HELLO, FrameTypes.PAIR_DECISION, FrameTypes.PAIR_COMMIT_ACK)
            if (dMayReceive) {
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
            toS.conns.second.writeRaw(raw(type))
            toS.pump()
            val sMayReceive = type in listOf(FrameTypes.PAIR_CHALLENGE, FrameTypes.PAIR_DECISION, FrameTypes.PAIR_COMMIT)
            if (sMayReceive) {
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
    fun aPeerThatRepeatsPairFramesWithoutEndIsClosedAfterTheBudget() {
        val k = Kit(PairOrientation.FROM_TLS_ROLE)
        repeat(1_000) { k.conns.first.writeRaw(raw(FrameTypes.PAIR_DECISION)) }
        k.pump()
        assertTrue(k.server.isClosed)
        assertTrue(k.atServer.size <= PairingChannel.MAX_INBOUND_FRAMES, "delivered: ${k.atServer.size}")
        assertTrue(k.memS.all().count { it.meshKind == MeshKind.PAIRING } <= PairingChannel.MAX_INBOUND_FRAMES)
        assertEquals(1, errorsWritten(k, k.memS))
        laws.bump("frame-budget-closes")
    }
}
