package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.lab.ledger.laws.AppendFailed
import xyz.mdhv.asom.lab.ledger.laws.Appended
import xyz.mdhv.asom.lab.ledger.laws.ConnFacts
import xyz.mdhv.asom.lab.ledger.laws.ContentSent
import xyz.mdhv.asom.lab.ledger.laws.EngineRead
import xyz.mdhv.asom.lab.ledger.laws.Ev
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.laws.PlainTap
import xyz.mdhv.asom.lab.ledger.laws.Received
import xyz.mdhv.asom.lab.ledger.laws.Responded
import xyz.mdhv.asom.lab.ledger.laws.Secrets
import xyz.mdhv.asom.lab.ledger.laws.Sent
import xyz.mdhv.asom.lab.ledger.laws.ServedBy
import xyz.mdhv.asom.lab.ledger.laws.Syn
import xyz.mdhv.asom.lab.ledger.laws.Tap
import xyz.mdhv.asom.lab.ledger.sim.EngineFault
import xyz.mdhv.asom.lab.ledger.sim.SimConfig

/**
 * A law that cannot fail proves nothing (R3-OVERCLAIM-3). Every law is shown to FAIL on a trace that breaks it, and law L-L15, the one the review
 * called true by construction, is shown to fail against three miscounting MUTANTS run through the whole simulator.
 */
class LawNegativeControlsTest {
    private fun row(
        kind: MeshKind? = MeshKind.CONTROL, phase: Phase? = null, attempt: String? = null, session: String? = "s1", code: String? = "HELLO", out: Long = 0, inn: Long? = 0,
        egress: LabEgress = LabEgress.peerClass,
    ) = LabRouteRecord(ts = 1, callerPkg = "peer:x", requestedModel = "", egress = egress, bytesOut = out, bytesIn = inn, meshKind = kind, phase = phase, attemptId = attempt, sessionId = session, meshCode = code)

    private fun frame(kind: FrameKind, attempt: String? = null, len: Int = 10) = FrameSpec(kind, len, 1, attempt)

    @Test
    fun l1FailsWhenABodyIsSentBeforeItsIntentRow() {
        val bad = listOf<Ev>(
            Sent("A", "s1", frame(FrameKind.INFER_OFFER, "a1"), null),
            Appended("A", row(MeshKind.INFER_SENT, Phase.INTENT, "a1", inn = null)),
            Sent("A", "s1", frame(FrameKind.INFER_BODY, "a1"), null),
        )
        val r = LedgerLaws.l1(bad)
        assertEquals(3 - 1, r.cases)
        assertTrue(r.violations.isNotEmpty())
        val missing = LedgerLaws.l1(listOf(Sent("A", "s1", frame(FrameKind.INFER_OFFER, "a1"), null)))
        assertTrue(missing.violations.isNotEmpty())
    }

    @Test
    fun l2FailsWhenTheEngineReadsBeforeTheLenderIntent() {
        val bad = listOf<Ev>(EngineRead("B", "a1"), Appended("B", row(MeshKind.INFER_SERVED, Phase.INTENT, "a1", inn = null)))
        assertTrue(LedgerLaws.l2(bad).violations.isNotEmpty())
    }

    @Test
    fun l3FailsWhenInferEndPrecedesTheOutcomeRow() {
        val bad = listOf<Ev>(Sent("B", "s1", frame(FrameKind.INFER_END, "a1"), null), Appended("B", row(MeshKind.INFER_SERVED, Phase.OUTCOME, "a1")))
        assertTrue(LedgerLaws.l3(bad).violations.isNotEmpty())
    }

    @Test
    fun l4FailsWhenBytesToAPeerAreLabelledCloud() {
        val bad = listOf<Ev>(
            Sent("A", "s1", frame(FrameKind.HELLO), null),
            Appended("A", row(out = 19, egress = LabEgress.CLOUD)),
        )
        assertTrue(LedgerLaws.l4(bad).violations.isNotEmpty())
    }

    @Test
    fun l5AndL5bFailOnAHeaderThatUnderstatesReachOrTwoTerminalRows() {
        val understated = listOf<Ev>(
            ContentSent("A", "r1", "a1", LabEgress.CLOUD),
            Responded("A", "r1", mapOf(AsomHeaders.EGRESS to "local")),
        )
        assertTrue(LedgerLaws.l5(understated).violations.isNotEmpty())
        fun terminal(egress: LabEgress, served: LabEgress?) = LabRouteRecord(
            ts = 1, callerPkg = "c", requestedModel = "m", egress = egress, requestId = "r1", reach = egress, terminal = true, servedClass = served,
        )
        val two = listOf<Ev>(
            Appended("A", terminal(LabEgress.peerClass, LabEgress.LOCAL)), Appended("A", terminal(LabEgress.peerClass, LabEgress.LOCAL)),
            Responded("A", "r1", mapOf(AsomHeaders.EGRESS to "peer")), ServedBy("A", "r1", LabEgress.LOCAL),
        )
        assertTrue(LedgerLaws.l5b(two).violations.isNotEmpty())
        val wrongClass = listOf<Ev>(
            Appended("A", terminal(LabEgress.peerClass, LabEgress.CLOUD)), Responded("A", "r1", mapOf(AsomHeaders.EGRESS to "peer")), ServedBy("A", "r1", LabEgress.LOCAL),
        )
        assertTrue(LedgerLaws.l5b(wrongClass).violations.isNotEmpty())
        val headerDiffers = listOf<Ev>(
            Appended("A", terminal(LabEgress.peerClass, LabEgress.LOCAL)), Responded("A", "r1", mapOf(AsomHeaders.EGRESS to "local")), ServedBy("A", "r1", LabEgress.LOCAL),
        )
        assertTrue(LedgerLaws.l5b(headerDiffers).violations.isNotEmpty())
    }

    @Test
    fun l6FailsWhenEarlierBytesChange() {
        val before = "abc\ndef\n".toByteArray()
        assertTrue(LedgerLaws.l6PrefixPreserved(before, before + "ghi\n".toByteArray()).ok)
        assertFalse(LedgerLaws.l6PrefixPreserved(before, "abX\ndef\nghi\n".toByteArray()).ok)
        assertFalse(LedgerLaws.l6PrefixPreserved(before, "abc\n".toByteArray()).ok, "a truncated file")
    }

    @Test
    fun l7FailsWhenAFramePayloadHoldsARequestId() {
        val bad = listOf<Ev>(ContentSent("A", "req-xyz", "a1", LabEgress.CLOUD), Sent("A", "s1", frame(FrameKind.INFER_OFFER, "a1"), """{"attemptId":"a1","note":"req-xyz"}"""))
        assertTrue(LedgerLaws.l7(bad).violations.isNotEmpty())
    }

    @Test
    fun l8FailsOnABodyFragmentAKeyANameOrAnAddressOutsideDial() {
        val secrets = Secrets("A", listOf("the quick brown fox 12345"), listOf("sk-LAB-SECRET-x"), listOf("Deck of Doom"))
        fun withCode(code: String) = listOf<Ev>(secrets, Appended("A", row(code = code)))
        assertTrue(LedgerLaws.l8(withCode("leak:the quick")).violations.isNotEmpty(), "an 8-character body fragment")
        assertTrue(LedgerLaws.l8(withCode("sk-LAB-SECRET-x")).violations.isNotEmpty(), "a key")
        assertTrue(LedgerLaws.l8(withCode("Deck of Doom")).violations.isNotEmpty(), "a display name")
        assertTrue(LedgerLaws.l8(withCode("10.0.0.7")).violations.isNotEmpty(), "an IPv4 literal")
        assertTrue(LedgerLaws.l8(withCode("fe80::1:2")).violations.isNotEmpty(), "an IPv6 literal")
        assertEquals(emptyList(), LedgerLaws.l8(withCode("HELLO")).violations)
        val dial = LabRouteRecord(ts = 1, callerPkg = "p", requestedModel = "", egress = LabEgress.peerClass, meshKind = MeshKind.DIAL, phase = Phase.INTENT, destAddr = "192.168.1.9:11436", addrSource = "qr", sessionId = "s1")
        assertEquals(emptyList(), LedgerLaws.l8(listOf(secrets, Appended("A", dial))).violations, "an address is allowed in a DIAL row's destAddr")
    }

    @Test
    fun l9FailsWhenAnIntentRowCarriesBytes() {
        val bad = listOf<Ev>(Appended("A", row(MeshKind.INFER_SENT, Phase.INTENT, "a1", out = 5, inn = null)))
        assertTrue(LedgerLaws.l9(bad).violations.isNotEmpty())
    }

    @Test
    fun l10FailsOnAPeerPathThatDoesNotMatchTheInterface() {
        val bad = listOf<Ev>(ConnFacts("A", "s1", PeerPath.OVERLAY, true), Appended("A", row().copy(peerPath = PeerPath.LAN)))
        assertTrue(LedgerLaws.l10(bad).violations.isNotEmpty())
    }

    @Test
    fun l12FailsWhenInferEndDisagreesWithTheOutcomeRow() {
        val outcome = LabRouteRecord(
            ts = 1, callerPkg = "p", requestedModel = "m", servedModel = "m", egress = LabEgress.peerClass, status = 200, attemptId = "a1", phase = Phase.OUTCOME,
            meshKind = MeshKind.INFER_SERVED, sessionId = "s1",
        )
        val bad = listOf<Ev>(Appended("B", outcome), Sent("B", "s1", frame(FrameKind.INFER_END, "a1"), """{"attemptId":"a1","status":502,"terminal":"error"}"""))
        assertTrue(LedgerLaws.l12(bad).violations.isNotEmpty())
        val ok = listOf<Ev>(Appended("B", outcome), Sent("B", "s1", frame(FrameKind.INFER_END, "a1"), """{"attemptId":"a1","status":200,"terminal":"done"}"""))
        assertEquals(emptyList(), LedgerLaws.l12(ok).violations)
    }

    @Test
    fun l13FailsWhenFramesFollowAControlRowFailureOrABodyFollowsAnIntentFailure() {
        val control = listOf<Ev>(AppendFailed("A", row()), Sent("A", "s1", frame(FrameKind.GOAWAY), null))
        assertTrue(LedgerLaws.l13(control).violations.isNotEmpty(), "a GOAWAY after a control-row failure")
        val intent = listOf<Ev>(AppendFailed("A", row(MeshKind.INFER_SENT, Phase.INTENT, "a1", inn = null)), Sent("A", "s1", frame(FrameKind.INFER_BODY, "a1"), null))
        assertTrue(LedgerLaws.l13(intent).violations.isNotEmpty())
        val engine = listOf<Ev>(AppendFailed("B", row(MeshKind.INFER_SERVED, Phase.INTENT, "a1", inn = null)), EngineRead("B", "a1"))
        assertTrue(LedgerLaws.l13(engine).violations.isNotEmpty())
        val outcome = listOf<Ev>(AppendFailed("B", row(MeshKind.INFER_SERVED, Phase.OUTCOME, "a1")), Sent("B", "s1", frame(FrameKind.INFER_END, "a1"), null))
        assertTrue(LedgerLaws.l13(outcome).violations.isNotEmpty())
        val syn = listOf<Ev>(AppendFailed("A", row(MeshKind.DIAL, Phase.INTENT, "s1", inn = null)), Syn("A", "s1"))
        assertTrue(LedgerLaws.l13(syn).violations.isNotEmpty())
    }

    @Test
    fun l14FailsOnASynWithoutADurableDialIntent() {
        assertTrue(LedgerLaws.l14(listOf(Syn("A", "s1"))).violations.isNotEmpty())
        val late = listOf<Ev>(Syn("A", "s1"), Appended("A", row(MeshKind.DIAL, Phase.INTENT, "s1", session = "s1", inn = null)))
        assertTrue(LedgerLaws.l14(late).violations.isNotEmpty())
    }

    @Test
    fun l15FailsOnARowsPlusOverheadThatDisagreesWithTheTapAndOnAnOutOfBoundOverhead() {
        val measured = row(MeshKind.SESSION, code = "close", out = 0, inn = 0).copy(overheadBytes = 100, overheadBasis = OverheadBasis.MEASURED)
        val counted = row(out = 50)
        val good = listOf<Ev>(Appended("A", counted), Appended("A", measured), Tap("A", "s1", 150), PlainTap("A", "s1", 50, 0, 1, 0))
        assertEquals(emptyList(), LedgerLaws.l15(good).result.violations.filter { "socket tap" in it }, "rows 50 + overhead 100 = the tap's 150")
        val bad = listOf<Ev>(Appended("A", counted), Appended("A", measured), Tap("A", "s1", 151), PlainTap("A", "s1", 50, 0, 1, 0))
        assertTrue(LedgerLaws.l15(bad).mismatches > 0)
        val rowsMiscount = listOf<Ev>(Appended("A", counted), Appended("A", measured), Tap("A", "s1", 150), PlainTap("A", "s1", 59, 0, 1, 0))
        assertTrue(LedgerLaws.l15(rowsMiscount).mismatches > 0, "rows disagree with the plaintext tap")
        val hugeOverhead = row(MeshKind.SESSION, code = "close").copy(overheadBytes = 1_000_000, overheadBasis = OverheadBasis.MEASURED)
        val outOfBound = listOf<Ev>(Appended("A", counted), Appended("A", hugeOverhead), Tap("A", "s1", 1_000_050), PlainTap("A", "s1", 50, 0, 1, 0))
        assertTrue(LedgerLaws.l15(outOfBound).mismatches > 0, "an overhead outside the RFC 8446 bound")
    }

    @Test
    fun l16FailsOnAMissingRowAWrongKindWrongBytesOrARowAfterTheSend() {
        val hello = frame(FrameKind.HELLO, len = 12)
        val missing = listOf<Ev>(Sent("A", "s1", hello, null))
        assertTrue(LedgerLaws.l16(missing).result.violations.isNotEmpty())
        val wrongBytes = listOf<Ev>(Appended("A", row(code = "HELLO", out = 12)), Sent("A", "s1", hello, null))
        assertTrue(LedgerLaws.l16(wrongBytes).result.violations.isNotEmpty(), "9 + 12 = 21 bytes, not 12")
        val wrongKind = listOf<Ev>(Appended("A", row(code = "STATE", out = 21)), Sent("A", "s1", hello, null))
        assertTrue(LedgerLaws.l16(wrongKind).result.violations.isNotEmpty())
        val late = listOf<Ev>(Sent("A", "s1", hello, null), Appended("A", row(code = "HELLO", out = 21)))
        assertTrue(LedgerLaws.l16(late).result.violations.isNotEmpty(), "the row must precede the send")
        val recvLate = listOf<Ev>(
            Received("B", "s1", hello), Sent("B", "s1", frame(FrameKind.HELLO_ACK), null), Appended("B", row(code = "HELLO", out = 0, inn = 21)),
        )
        assertTrue(LedgerLaws.l16(recvLate).result.violations.isNotEmpty(), "the row must precede any reply")
        val doubled = listOf<Ev>(Appended("A", row(code = "HELLO", out = 21)), Appended("A", row(code = "HELLO", out = 21)), Sent("A", "s1", hello, null))
        assertTrue(LedgerLaws.l16(doubled).result.violations.isNotEmpty(), "exactly one row per frame")
    }

    // ---- L-L15 mutants, through the whole simulator (R3-OVERCLAIM-3) ----

    private fun tally(cfg: (Long) -> SimConfig): LawTally {
        val t = LawTally()
        for (seed in 1L..40L) t.add(runWorld(seed, cfg(seed)).trace.events)
        return t
    }

    @Test
    fun theUnmutatedSimulatorPassesTheSameSeeds() {
        val t = tally { SimConfig(seed = it) }
        assertEquals(emptyList(), t.result("L-L15").violations)
        assertTrue(t.measuredSessions > 0)
    }

    @Test
    fun mutantThatForgetsTheNineByteFrameHeaderIsCaughtByL15() {
        val t = tally { SimConfig(seed = it, counter = ByteCounter { f -> f.payloadLen.toLong() }) }
        assertTrue(t.result("L-L15").violations.isNotEmpty(), "rows that count payload only must break L-L15")
        assertTrue(t.result("L-L15").violations.any { "plaintext tap" in it || "socket tap" in it })
    }

    @Test
    fun mutantThatCountsAFrameTwiceIsCaughtByL15AndL16() {
        val t = tally { SimConfig(seed = it, counter = ByteCounter { f -> 2 * f.appBytes }) }
        assertTrue(t.result("L-L15").violations.isNotEmpty())
        assertTrue(t.result("L-L16").violations.isNotEmpty())
    }

    @Test
    fun mutantEngineThatMisreportsWhatItWrappedIsCaughtByL15() {
        val t = tally { SimConfig(seed = it, fault = EngineFault.WRAP_REPORTS_ONE_LESS) }
        assertTrue(t.result("L-L15").violations.isNotEmpty(), "an engine that reports one byte less per wrap breaks rows + overhead = tap")
        val u = tally { SimConfig(seed = it, fault = EngineFault.IGNORES_ALERTS) }
        assertTrue(u.result("L-L15").violations.isNotEmpty(), "an engine that ignores the close alerts")
    }

    @Test
    fun overheadIsNotDerivedFromTheRowWriter() {
        // If the overhead came from the writer's own byte counts, the payload-only mutant would still balance. It does not (mutantThatForgetsTheNineByteFrameHeaderIsCaughtByL15),
        // because the meter reports plaintext bytes from the engine. This pins the meter interface: it has no access to the ByteCounter.
        val names = TransportMeter::class.java.methods.map { it.name }.toSet()
        assertTrue(setOf("networkBytes", "plaintextBytes", "handshakeNetworkBytes").all { it in names })
    }
}
