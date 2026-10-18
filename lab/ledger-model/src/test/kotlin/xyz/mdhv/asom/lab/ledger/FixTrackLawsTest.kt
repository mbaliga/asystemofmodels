package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.ledger.laws.AppendFailed
import xyz.mdhv.asom.lab.ledger.laws.Appended
import xyz.mdhv.asom.lab.ledger.laws.ContentSent
import xyz.mdhv.asom.lab.ledger.laws.Ev
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.laws.ProviderCall
import xyz.mdhv.asom.lab.ledger.laws.Received
import xyz.mdhv.asom.lab.ledger.laws.Secrets
import xyz.mdhv.asom.lab.ledger.laws.Sent
import xyz.mdhv.asom.lab.ledger.sim.AttemptPlan
import xyz.mdhv.asom.lab.ledger.sim.CancelReasonWire
import xyz.mdhv.asom.lab.ledger.sim.CloseStep
import xyz.mdhv.asom.lab.ledger.sim.DialStep
import xyz.mdhv.asom.lab.ledger.sim.FrameStep
import xyz.mdhv.asom.lab.ledger.sim.RequestStep
import xyz.mdhv.asom.lab.ledger.sim.Side
import xyz.mdhv.asom.lab.ledger.sim.SimConfig
import xyz.mdhv.asom.lab.ledger.sim.SimWorld
import xyz.mdhv.asom.lab.ledger.sim.Step

/**
 * The laws and the model behaviour added by the fix4-ledger track (ERRATA ERR-FX4-LG-*): each law is shown to FAIL on a trace that breaks it and to
 * PASS on the trace a real node produces; the simulator plans that reach the new paths are run through every trace law.
 */
class FixTrackLawsTest {
    private val id = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun frame(kind: FrameKind, len: Int = 10, attempt: String? = id) = FrameSpec(kind, len, 1, attempt)

    private fun attemptRow(
        kind: MeshKind, phase: Phase, node: String = "B", attempt: String = id, status: Int = 0, session: String = "s1", request: String? = null, index: Int? = null,
        servedModel: String? = null, peerNode: String? = "peerx", egress: LabEgress = LabEgress.peerClass, servedProvider: String? = null,
    ) = LabRouteRecord(
        ts = 1, callerPkg = "peer:x", requestedModel = "m", servedProvider = servedProvider, servedModel = servedModel, egress = egress, status = status, requestId = request,
        attemptId = attempt, phase = phase, attemptIndex = index, peerNode = peerNode, meshKind = kind, bytesIn = if (phase == Phase.INTENT) null else 0, sessionId = session,
    )

    private fun closeRow(node: String = "B", code: String = "close") = Appended(
        node, LabRouteRecord(ts = 1, callerPkg = "peer:x", requestedModel = "", egress = LabEgress.peerClass, meshKind = MeshKind.SESSION, meshCode = code, sessionId = "s1", bytesIn = 0),
    )

    // ------------------------------------------------------------------------------------------------------------ L-L16, driven from the frames

    @Test
    fun l16FailsForAnAttemptThatCrossedFramesAndGotNoOutcomeRowOnASessionThatEndedInOrder_ERR_FX4_LG_3() {
        val noRow = listOf<Ev>(Received("B", "s1", frame(FrameKind.INFER_OFFER)), Sent("B", "s1", frame(FrameKind.INFER_ACCEPT), null), closeRow())
        val r = LedgerLaws.l16(noRow).result
        assertEquals(1, r.detail["attemptsWithFramesChecked"], "the attempt was counted")
        assertTrue(r.violations.any { "no outcome row" in it || "holds no outcome row" in it }, r.violations.toString())
        val withRow = noRow + Appended("B", attemptRow(MeshKind.INFER_SERVED, Phase.OUTCOME, status = 499))
        assertTrue(LedgerLaws.l16(withRow).result.violations.none { "outcome row" in it && "holds no" in it }, "an outcome row satisfies the clause")
    }

    @Test
    fun l16DoesNotDemandARowFromASessionThatDiedOrWasClosedByALedgerFailure_ERR_FX4_LG_3() {
        val frames = listOf<Ev>(Received("B", "s1", frame(FrameKind.INFER_OFFER)), Sent("B", "s1", frame(FrameKind.INFER_ACCEPT), null))
        fun clause(t: List<Ev>) = LedgerLaws.l16(t).result.violations.filter { "holds no outcome row" in it }
        assertEquals(emptyList(), clause(frames), "no close row: the process died, the crash tests judge that run")
        assertEquals(emptyList(), clause(frames + closeRow(code = "close:ledger-failure")), "closed by ledger failure")
        assertEquals(emptyList(), clause(frames + AppendFailed("B", attemptRow(MeshKind.INFER_SERVED, Phase.OUTCOME)) + closeRow()), "a failed append on the session")
        assertEquals(1, clause(frames + closeRow()).size)
    }

    @Test
    fun l16FailsWhenAnAttemptFrameIsSentAfterTheOutcomeRow_ERR_FX4_LG_4() {
        fun requesterOutcome() = Appended("A", attemptRow(MeshKind.INFER_SENT, Phase.OUTCOME, status = 503))
        val late = listOf<Ev>(requesterOutcome(), Sent("A", "s1", frame(FrameKind.INFER_BODY), null))
        val r = LedgerLaws.l16(late).result
        assertEquals(1, r.detail["outcomesCheckedForLateFrames"])
        assertTrue(r.violations.any { "after the outcome row" in it }, r.violations.toString())
        assertTrue(LedgerLaws.l16(listOf<Ev>(requesterOutcome())).result.violations.none { "after the outcome row" in it })
        val lender = Appended("B", attemptRow(MeshKind.INFER_SERVED, Phase.OUTCOME, status = 502))
        val endOnly = listOf<Ev>(lender, Sent("B", "s1", frame(FrameKind.INFER_END), null))
        assertTrue(LedgerLaws.l16(endOnly).result.violations.none { "after the outcome row" in it }, "the one frame the lender's row counts follows it")
        val twice = endOnly + Sent("B", "s1", frame(FrameKind.INFER_CHUNK), null)
        assertTrue(LedgerLaws.l16(twice).result.violations.any { "after the outcome row" in it }, "a chunk after the end")
    }

    // ------------------------------------------------------------------------------------------------------------ L-L12

    private fun served(status: Int, terminal: String = if (status == 200) "done" else "error", model: String = "m") = ServedRecord(model, status, terminal, null)

    private fun lenderTrace(head: ServedRecord, end: ServedRecord, row: ServedRecord): List<Ev> {
        val outcome = attemptRow(MeshKind.INFER_SERVED, Phase.OUTCOME, status = row.status, servedModel = row.servedModel).copy(meshCode = row.meshCode)
        return listOf(
            Sent("B", "s1", frame(FrameKind.INFER_HEAD), Jcs.serializeToString(head.headPayload(id))),
            Sent("B", "s1", frame(FrameKind.INFER_END), Jcs.serializeToString(end.endPayload(id))),
            Appended("B", outcome),
        )
    }

    @Test
    fun l12AcceptsAHeadThatCommittedWith200AndAnEndThatFailedAfterwards_ERR_FX4_LG_9() {
        val r = LedgerLaws.l12(lenderTrace(head = served(200), end = served(502), row = served(502)))
        assertEquals(2, r.cases)
        assertEquals(emptyList(), r.violations)
        assertEquals(emptyList(), LedgerLaws.l12(lenderTrace(head = served(200), end = served(499, "cancelled"), row = served(499, "cancelled"))).violations)
    }

    @Test
    fun l12StillFailsOnAnEndOrHeadThatDisagreesWithTheRow_ERR_FX4_LG_9() {
        assertTrue(LedgerLaws.l12(lenderTrace(head = served(200), end = served(200), row = served(502))).violations.isNotEmpty(), "END status must equal the row")
        assertTrue(LedgerLaws.l12(lenderTrace(head = served(500), end = served(200), row = served(200))).violations.isNotEmpty(), "a head that claims a failure for a success")
        assertTrue(LedgerLaws.l12(lenderTrace(head = served(200, model = "other"), end = served(200), row = served(200))).violations.isNotEmpty(), "served model differs")
        assertEquals(emptyList(), LedgerLaws.l12(lenderTrace(head = served(502), end = served(502), row = served(502))).violations, "a head with the row's own status")
    }

    // ------------------------------------------------------------------------------------------------------------ L-L13, DIAL outcome

    @Test
    fun l13FailsWhenAFrameLeavesAfterTheDialOutcomeRowFailed_ERR_FX4_LG_5() {
        val dial = LabRouteRecord(
            ts = 1, callerPkg = "peer:x", requestedModel = "", egress = LabEgress.peerClass, status = 200, attemptId = "s1", phase = Phase.OUTCOME, peerNode = "x", meshKind = MeshKind.DIAL,
            bytesIn = 0, meshCode = "connected", destAddr = "192.168.1.2:1", addrSource = "qr", sessionId = "s1",
        )
        val bad = listOf<Ev>(AppendFailed("A", dial), Sent("A", "s1", frame(FrameKind.HELLO, attempt = null), null))
        val r = LedgerLaws.l13(bad)
        assertEquals(1, r.cases)
        assertEquals(1, r.violations.size)
        assertEquals(emptyList(), LedgerLaws.l13(listOf(AppendFailed("A", dial))).violations)
    }

    // ------------------------------------------------------------------------------------------------------------ L-L17, L-L18, L-L19

    @Test
    fun l17FailsForContentWithoutAnEarlierIntentRowOfTheSameRequestAndClass_ERR_FX4_LG_8() {
        fun intent(request: String = "r1", egress: LabEgress = LabEgress.CLOUD) =
            Appended("A", LabRouteRecord(ts = 1, callerPkg = "c", requestedModel = "m", egress = egress, requestId = request, attemptId = "c1", phase = Phase.INTENT, attemptIndex = 0))
        val sent = ContentSent("A", "r1", "c1", LabEgress.CLOUD)
        assertEquals(emptyList(), LedgerLaws.l17(listOf(intent(), sent)).violations)
        assertEquals(1, LedgerLaws.l17(listOf(sent)).violations.size, "no row at all")
        assertEquals(1, LedgerLaws.l17(listOf(sent, intent())).violations.size, "the row came after the transmission")
        assertEquals(1, LedgerLaws.l17(listOf(intent(request = "r2"), sent)).violations.size, "the intent belongs to another request")
        assertEquals(1, LedgerLaws.l17(listOf(intent(egress = LabEgress.peerClass), sent)).violations.size, "the intent names another class")
    }

    @Test
    fun l18FailsOnAServedByIdentityNoAttemptRowNamesAndOnARepeatedAttemptIndex_ERR_FX4_LG_7() {
        fun terminal(provider: String?, model: String?) = Appended(
            "A", LabRouteRecord(
                ts = 1, callerPkg = "c", requestedModel = "m", servedProvider = provider, servedModel = model, egress = LabEgress.peerClass, status = 200, requestId = "r1",
                reach = LabEgress.peerClass, terminal = true, servedClass = LabEgress.peerClass,
            ),
        )
        val outcome = Appended("A", attemptRow(MeshKind.INFER_SENT, Phase.OUTCOME, node = "A", status = 200, request = "r1", index = 0, servedModel = "qwen3-8b", servedProvider = "peer:tagx", peerNode = "tagx"))
        assertEquals(emptyList(), LedgerLaws.l18(listOf(outcome, terminal("peer:tagx", "qwen3-8b"))).violations)
        assertTrue(LedgerLaws.l18(listOf(outcome, terminal("peer:deck", "qwen3-8b"))).violations.isNotEmpty(), "a literal no row names")
        assertTrue(LedgerLaws.l18(listOf(outcome, terminal("peer:tagx", "a-model-nobody-served"))).violations.isNotEmpty(), "a model the peer never claimed")
        assertTrue(LedgerLaws.l18(listOf(terminal("peer:tagx", "qwen3-8b"))).violations.isNotEmpty(), "no serving attempt row at all")
        val a2 = Appended("A", attemptRow(MeshKind.INFER_SENT, Phase.OUTCOME, node = "A", attempt = "second", status = 503, request = "r1", index = 0))
        assertTrue(LedgerLaws.l18(listOf(outcome, a2)).violations.isNotEmpty(), "two attempts of one request with index 0")
    }

    @Test
    fun l19FailsOnAnEnumValueOutsideTheClosedSet_ERR_FX4_LG_10() {
        fun cancel(reason: String) = Sent("A", "s1", frame(FrameKind.CANCEL), """{"attemptId":"$id","reason":"$reason"}""")
        assertEquals(emptyList(), LedgerLaws.l19(listOf(cancel("deadline"), cancel("policy-changed"))).violations)
        for (bad in listOf("timeout", "reeval", "head-timeout")) assertEquals(1, LedgerLaws.l19(listOf(cancel(bad))).violations.size, bad)
        assertEquals(1, LedgerLaws.l19(listOf(Sent("B", "s1", frame(FrameKind.INFER_END), """{"attemptId":"$id","status":200,"terminal":"finished"}"""))).violations.size)
    }

    @Test
    fun l8CountsTheProviderCallsThatCarriedAKey_ERR_FX4_LG_11() {
        val keys = Secrets("A", emptyList(), listOf("sk-LAB-SECRET-x"), emptyList())
        val call = ProviderCall("A", "r1", "c1", "Bearer sk-LAB-SECRET-x", "401 Incorrect API key provided: sk-LAB-SECRET-x")
        val r = LedgerLaws.l8(listOf(keys, call, Appended("A", attemptRow(MeshKind.CONTROL, Phase.INTENT).copy(meshKind = null, phase = null, sessionId = null))))
        assertEquals(1, r.detail["providerCallsCarryingAKey"])
        assertEquals(1, r.detail["providerErrorsEchoingAKey"])
        assertEquals(emptyList(), r.violations)
    }

    // ------------------------------------------------------------------------------------------------------------ the model, driven by the simulator

    private fun script(w: SimWorld, vararg plans: List<AttemptPlan>): List<Step> {
        val p = w.payloads
        val s = ArrayList<Step>()
        s += DialStep()
        s += FrameStep(Side.A, p.hello(p.b64u(16)))
        s += FrameStep(Side.B, p.helloAck())
        plans.forEachIndexed { i, plan -> s += RequestStep("req-$i", "qwen3-8b", plan) }
        s += CloseStep(Side.A)
        return s
    }

    private fun run(vararg plans: List<AttemptPlan>, seed: Long = 5): SimWorld {
        val w = SimWorld(SimConfig(seed = seed))
        w.run(script(w, *plans))
        assertNull(w.abortReason)
        return w
    }

    private fun noViolations(w: SimWorld) {
        for (r in traceLaws(w.trace.events)) assertEquals(emptyList(), r.violations, r.law)
    }

    @Test
    fun anAcceptedAttemptCancelledBeforeTheBodyLeavesAnOutcomeRowOnBothNodesAndEveryLawHolds_ERR_FX4_LG_3() {
        val w = run(listOf(AttemptPlan.PeerCancelledBeforeBody, AttemptPlan.LocalOk))
        val b = w.rows("B").filter { it.meshKind == MeshKind.INFER_SERVED }
        assertEquals(listOf(Phase.OUTCOME), b.map { it.phase }, "one outcome row and no intent: no content was processed")
        assertEquals(499, b.single().status)
        assertEquals("CANCELLED", b.single().meshCode)
        assertNull(b.single().servedModel)
        val a = w.rows("A").filter { it.meshKind == MeshKind.INFER_SENT }
        assertEquals(listOf(Phase.INTENT, Phase.OUTCOME), a.map { it.phase })
        assertEquals(b.single().bytesIn, a.last().bytesOut, "the lender received exactly what the requester sent (offer, cancel) plus nothing else")
        noViolations(w)
        assertTrue(LedgerLaws.l16(w.trace.events).result.detail.getValue("attemptsWithFramesChecked") >= 1)
    }

    @Test
    fun anAcceptedAttemptWhoseBodyNeverCameIsEndedByTheSessionClose_ERR_FX4_LG_3() {
        val w = run(listOf(AttemptPlan.PeerAcceptedThenLost, AttemptPlan.LocalOk))
        val b = w.rows("B").filter { it.meshKind == MeshKind.INFER_SERVED }
        assertEquals("INTERRUPTED", b.single().meshCode)
        assertEquals(499, b.single().status)
        val close = w.rows("B").last { it.meshKind == MeshKind.SESSION }
        assertEquals(0L, close.bytesOut + (close.bytesIn ?: 0), "the close row has nothing the attempt row holds")
        noViolations(w)
    }

    @Test
    fun theServedByHeaderNamesThePeerThatServedAndTheModelItClaimedAndTheAttemptRowsSayTheSame_ERR_FX4_LG_7() {
        val w = run(listOf(AttemptPlan.PeerServed("done", 2, 60)))
        val terminal = w.rows("A").single { it.terminal == true }
        val outcome = w.rows("A").single { it.meshKind == MeshKind.INFER_SENT && it.phase == Phase.OUTCOME }
        assertEquals("peer:${w.b.tag}", outcome.servedProvider)
        assertEquals("qwen3-8b", outcome.servedModel, "the model as claimed in INFER_HEAD")
        assertEquals(w.b.tag, outcome.peerNode)
        assertEquals("peer:${w.b.tag}/qwen3-8b", terminal.toEchoHeaders()[AsomHeaders.SERVED_BY])
        assertEquals(outcome.servedProvider, terminal.servedProvider)
    }

    @Test
    fun attemptIndexRunsFromZeroWithinEachRequest_ERR_FX4_LG_12() {
        val w = run(
            listOf(AttemptPlan.PeerDecline("PEER_BUSY"), AttemptPlan.CloudFail, AttemptPlan.CloudOk),
            listOf(AttemptPlan.PeerDecline("PEER_BUSY"), AttemptPlan.PeerCancelledBeforeBody, AttemptPlan.LocalOk),
        )
        val perRequest = w.rows("A").filter { it.requestId != null && it.attemptId != null && it.terminal != true }.groupBy { it.requestId }
        assertEquals(setOf("req-0", "req-1"), perRequest.keys)
        assertEquals(listOf(0, 0, 1, 1, 2, 2), perRequest.getValue("req-0").map { it.attemptIndex })
        assertEquals(listOf(0, 0, 1, 1), perRequest.getValue("req-1").map { it.attemptIndex })
    }

    @Test
    fun aCloudAttemptWritesItsIntentBeforeTheTransmissionAndItsOutcomeNamingTheProviderAfter_ERR_FX4_LG_8() {
        val w = run(listOf(AttemptPlan.CloudFail, AttemptPlan.CloudOk))
        val ev = w.trace.events
        val sent = ev.withIndex().filter { it.value is ContentSent }.map { it.index }
        val rows = ev.withIndex().filter { it.value is Appended && (it.value as Appended).row.meshKind == null && (it.value as Appended).row.attemptId != null }
        assertEquals(4, rows.size, "an intent and an outcome per cloud attempt")
        val phases = rows.map { (it.value as Appended).row.phase }
        assertEquals(listOf(Phase.INTENT, Phase.OUTCOME, Phase.INTENT, Phase.OUTCOME), phases)
        for ((k, s) in sent.withIndex()) assertTrue(rows[2 * k].index < s && s < rows[2 * k + 1].index, "intent, then the transmission, then the outcome")
        val outcomes = rows.map { (it.value as Appended).row }.filter { it.phase == Phase.OUTCOME }
        assertEquals(listOf("openrouter", "openrouter"), outcomes.map { it.servedProvider }, "a failed attempt still names the provider that received the content")
        assertEquals(listOf(401, 200), outcomes.map { it.status })
        assertTrue(outcomes.all { it.bytesOut > 0 && it.egress == LabEgress.CLOUD })
        noViolations(w)
    }

    @Test
    fun aProviderErrorThatEchoesTheKeyNeverReachesARow_ERR_FX4_LG_11() {
        val w = run(listOf(AttemptPlan.CloudFail, AttemptPlan.CloudOk))
        val r = LedgerLaws.l8(w.trace.events)
        assertEquals(2, r.detail["providerCallsCarryingAKey"], "both calls carried the key in their Authorization value")
        assertEquals(1, r.detail["providerErrorsEchoingAKey"], "and the failed call's error text echoed it")
        assertEquals(emptyList(), r.violations)
    }

    @Test
    fun aFailedDialOutcomeRowClosesTheMadeConnectionAndSendsNothing_ERR_FX4_LG_5() {
        val w = SimWorld(SimConfig(seed = 3, sinkFor = { name -> if (name == "A") CrashInjectingSink(MemorySink(), setOf(1)) else MemorySink() }))
        val p = w.payloads
        w.run(listOf(DialStep(), FrameStep(Side.A, p.hello(p.b64u(16)))))
        assertEquals("LEDGER_UNAVAILABLE", w.abortReason)
        assertEquals(1, w.connectionsClosed, "the connection connect() made was closed")
        assertEquals(null, w.conn)
        assertTrue(w.trace.events.none { it is Sent }, "no frame was sent on the session whose DIAL outcome is not durable")
        val l13 = LedgerLaws.l13(w.trace.events)
        assertTrue(l13.cases >= 1)
        assertEquals(emptyList(), l13.violations)
        assertEquals(emptyList(), LedgerLaws.l15(w.trace.events).result.violations)
    }

    // ------------------------------------------------------------------------------------------------------------ the session-level contracts

    private class RecWire : WireOut {
        override val meter: TransportMeter? = null
        val sent = ArrayList<FrameSpec>()

        override fun send(frame: FrameSpec) {
            sent += frame
        }

        override fun close() {}
    }

    private class ScriptedSink(private val failAt: Set<Int> = emptySet(), val onAppend: (LabRouteRecord) -> Unit = {}) : RowSink {
        val rows = ArrayList<LabRouteRecord>()
        var attempts = 0

        override fun append(row: LabRouteRecord) {
            val i = attempts++
            onAppend(row)
            if (i in failAt) throw LedgerWriteException("injected failure at append #$i")
            rows += row
        }
    }

    @Test
    fun finishWithoutBodyIsDurableBeforeInferEndNamesNoModelAndNeedsAnAcceptWithoutABody_ERR_FX4_LG_3() {
        val wire = RecWire()
        lateinit var seenOnWire: List<FrameKind>
        val sink = ScriptedSink { if (it.phase == Phase.OUTCOME) seenOnWire = wire.sent.map { f -> f.kind } }
        val s = SessionLedger(NodeLedger("B", sink, { 7L }), "sess", SessionMode.ESTABLISHED, SessionRole.LISTENER, "peerx", PeerPath.LAN, wire)
        val la = s.lenderAttempt(id, "qwen3-8b")
        la.receiveOffer(frame(FrameKind.INFER_OFFER, 150))
        assertFailsWith<IllegalStateException>("not accepted yet") { la.finishWithoutBody(frame(FrameKind.INFER_END, 60), served(499, "cancelled")) }
        la.accept(frame(FrameKind.INFER_ACCEPT, 120))
        la.receiveOther(frame(FrameKind.CANCEL, 50))
        assertTrue(la.finishWithoutBody(frame(FrameKind.INFER_END, 60), ServedRecord("qwen3-8b", 499, "cancelled", null)))
        assertEquals(listOf(FrameKind.INFER_ACCEPT), seenOnWire, "the outcome row was durable before the INFER_END was handed to the wire")
        assertEquals(listOf(FrameKind.INFER_ACCEPT, FrameKind.INFER_END), wire.sent.map { it.kind })
        val row = sink.rows.single()
        assertEquals(Phase.OUTCOME, row.phase)
        assertEquals(129L + 69L, row.bytesOut)
        assertEquals(159L + 59L, row.bytesIn)
        assertNull(row.servedModel)
        assertFailsWith<IllegalStateException>("one outcome per attempt") { la.finishWithoutBody(frame(FrameKind.INFER_END, 60), served(499, "cancelled")) }
        val close = s.close()
        assertNotNull(close)
        assertEquals(0L, close.bytesOut + (close.bytesIn ?: 0))
        assertEquals(1, sink.rows.count { it.meshKind == MeshKind.INFER_SERVED }, "the close ended nothing twice")
    }

    @Test
    fun anAttemptWithABodyCannotEndWithoutOne_ERR_FX4_LG_3() {
        val s = SessionLedger(NodeLedger("B", MemorySink(), { 7L }), "sess", SessionMode.ESTABLISHED, SessionRole.LISTENER, "peerx", PeerPath.LAN, RecWire())
        val la = s.lenderAttempt(id, "qwen3-8b")
        la.receiveOffer(frame(FrameKind.INFER_OFFER, 150))
        la.accept(frame(FrameKind.INFER_ACCEPT, 120))
        assertTrue(la.receiveBody(frame(FrameKind.INFER_BODY, 500)) { frame(FrameKind.INFER_DECLINE, 40) })
        assertFailsWith<IllegalStateException> { la.finishWithoutBody(frame(FrameKind.INFER_END, 60), served(499, "cancelled")) }
    }

    @Test
    fun theCloseRowCarriesTheBytesOfALateFrameThatNoAttemptRowHolds_ERR_FX4_LG_4() {
        val sink = MemorySink()
        val s = SessionLedger(NodeLedger("A", sink, { 7L }), "sess", SessionMode.ESTABLISHED, SessionRole.DIALER, "peerx", PeerPath.LAN, RecWire())
        val ra = s.requesterAttempt("req", id, 0, "com.example", "qwen3-8b")
        ra.begin()
        ra.send(frame(FrameKind.INFER_OFFER, 150))
        val outcome = ra.finish(504, "OFFER_TIMEOUT", null)
        assertEquals(159L, outcome.bytesOut)
        ra.receive(frame(FrameKind.INFER_DECLINE, 60))
        assertEquals(0L, outcome.bytesIn, "the outcome row was written before that frame")
        val close = s.close()
        assertNotNull(close)
        assertEquals(69L, close.bytesIn, "so the SESSION close row carries it: the rows of a session add up to the plaintext")
    }

    @Test
    fun aRequesterAttemptStillOpenAtCloseEndsAs599PeerUnreachable_ERR_FX4_LG_3() {
        val sink = MemorySink()
        val s = SessionLedger(NodeLedger("A", sink, { 7L }), "sess", SessionMode.ESTABLISHED, SessionRole.DIALER, "peerx", PeerPath.LAN, RecWire())
        val ra = s.requesterAttempt("req", id, 0, "com.example", "qwen3-8b")
        ra.begin()
        ra.send(frame(FrameKind.INFER_OFFER, 150))
        s.close()
        val o = sink.all().single { it.phase == Phase.OUTCOME }
        assertEquals(599, o.status)
        assertEquals("PEER_UNREACHABLE", o.meshCode)
        assertEquals(159L, o.bytesOut)
    }

    @Test
    fun anAbortRowThatCannotBeWrittenMakesTheCloseALedgerFailure_ERR_FX4_LG_3() {
        val sink = ScriptedSink(failAt = setOf(0))
        val s = SessionLedger(NodeLedger("B", sink, { 7L }), "sess", SessionMode.ESTABLISHED, SessionRole.LISTENER, "peerx", PeerPath.LAN, RecWire())
        val la = s.lenderAttempt(id, "qwen3-8b")
        la.receiveOffer(frame(FrameKind.INFER_OFFER, 150))
        la.accept(frame(FrameKind.INFER_ACCEPT, 120))
        val close = s.close()
        assertNotNull(close)
        assertEquals("close:ledger-failure", close.meshCode)
        assertEquals(503, close.status)
        assertTrue(s.closedByLedgerFailure)
        assertEquals(288L, close.bytesOut + (close.bytesIn ?: 0), "the attempt's bytes fell to the close row")
    }

    @Test
    fun aDialOutcomeFailureClosesTheConnectionOnceAndIsLedgerUnavailable_ERR_FX4_LG_5() {
        val node = NodeLedger("A", CrashInjectingSink(MemorySink(), setOf(1)), { 7L })
        var closed = 0
        assertFailsWith<LedgerUnavailableException> {
            node.dial("sid", "peerx", "192.168.1.5:11436", "qr") { DialResult("connected", null, PeerPath.LAN, AutoCloseable { closed++ }) }
        }
        assertEquals(1, closed)
        assertTrue(node.unavailable, "the ledger is marked unavailable until a write succeeds")
        val refused = NodeLedger("A", MemorySink(), { 7L })
        var refusedClosed = 0
        assertFailsWith<IllegalArgumentException> {
            refused.dial("sid", "peerx", "192.168.1.5:11436", "qr") { DialResult("unreachable", null, null, AutoCloseable { refusedClosed++ }) }
        }
        assertEquals(1, refusedClosed, "a connection that came with an unknown code is closed too")
    }

    @Test
    fun cancelReasonsOfThePayloadFactoryAreTheClosedSet_ERR_FX4_LG_10() {
        assertEquals(setOf("client-gone", "deadline", "policy-changed", "superseded"), CancelReasonWire.entries.map { it.wire }.toSet())
    }

    @Test
    fun theNewPlansAndLawsAreExercisedByTheRandomWorlds_ERR_FX4_LG_3() {
        val t = LawTally()
        var cancelledBeforeBody = 0
        var interrupted = 0
        for (seed in 1L..400L) {
            val w = runWorld(seed)
            t.add(w.trace.events)
            val b = w.rows("B").filter { it.meshKind == MeshKind.INFER_SERVED && it.phase == Phase.OUTCOME }
            cancelledBeforeBody += b.count { it.meshCode == "CANCELLED" && it.servedModel == null }
            interrupted += b.count { it.meshCode == "INTERRUPTED" }
        }
        assertTrue(cancelledBeforeBody > 0, "no random world cancelled an accepted attempt before the body")
        assertTrue(interrupted > 0, "no random world ended an accepted attempt by closing the session")
        for (law in listOf("L-L17", "L-L18", "L-L19")) {
            assertEquals(emptyList(), t.result(law).violations, law)
            assertTrue(t.result(law).cases > 0, "$law exercised zero cases")
        }
        assertTrue(t.result("L-L16").detail.getValue("attemptsWithFramesChecked") > 0)
        assertTrue(t.result("L-L8").detail.getValue("providerCallsCarryingAKey") > 0, "the key clause of L-L8 saw a key travel through the cloud path")
        println(
            "ERR-FX4-LG: 400 random worlds: cancelled before the body ${cancelledBeforeBody}, ended by the session close $interrupted, " +
                "L-L16 attempts checked from frames ${t.result("L-L16").detail["attemptsWithFramesChecked"]}, L-L17 ${t.result("L-L17").cases} ${t.result("L-L17").detail}, " +
                "L-L18 ${t.result("L-L18").cases}, L-L19 ${t.result("L-L19").cases} ${t.result("L-L19").detail}, key-carrying provider calls ${t.result("L-L8").detail["providerCallsCarryingAKey"]}",
        )
    }
}
