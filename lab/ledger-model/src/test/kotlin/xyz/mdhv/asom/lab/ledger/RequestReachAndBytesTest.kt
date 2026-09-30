package xyz.mdhv.asom.lab.ledger

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.sim.AttemptPlan
import xyz.mdhv.asom.lab.ledger.sim.DialStep
import xyz.mdhv.asom.lab.ledger.sim.FrameStep
import xyz.mdhv.asom.lab.ledger.sim.RequestStep
import xyz.mdhv.asom.lab.ledger.sim.Side
import xyz.mdhv.asom.lab.ledger.sim.SimConfig
import xyz.mdhv.asom.lab.ledger.sim.SimWorld

class RequestReachAndBytesTest {
    private fun establish(w: SimWorld) {
        val p = w.payloads
        w.apply(DialStep())
        w.apply(FrameStep(Side.A, p.hello(p.b64u(16))))
        w.apply(FrameStep(Side.B, p.helloAck()))
    }

    private fun terminal(w: SimWorld, id: String): LabRouteRecord = w.rows("A").single { it.requestId == id && it.terminal == true }

    /** W01b-reach (proposed): the peer attempt receives the body, then SELF serves: header peer, terminal egress peer, servedClass local. Read from the committed vector. */
    @Test
    fun w01bReachHoldsThroughTheLabTypes() {
        val doc = (StrictJson.parse(File(repoRoot(), "lab/conformance/wire/W01b-one-record.json").readBytes()) as ParseResult.Ok).value as JObject
        val v = (doc["vectors"] as xyz.mdhv.asom.lab.json.JArray).items.map { it as JObject }.single { (it["id"] as JString).value == "W01b-reach" }
        val expect = (v["expect"] as JObject)["ok"] as JObject
        val wantHeader = ((expect["headers"] as JObject)[AsomHeaders.EGRESS] as JString).value
        val wantRow = expect["terminalRow"] as JObject

        val w = SimWorld(SimConfig(seed = 3))
        establish(w)
        w.apply(RequestStep("r-reach", "qwen3-8b", listOf(AttemptPlan.PeerServed("interrupted", 0, w.fuzzBody(30, 60)), AttemptPlan.LocalOk)))
        val row = terminal(w, "r-reach")
        assertEquals(wantHeader, row.toEchoHeaders()[AsomHeaders.EGRESS])
        assertEquals((wantRow["egress"] as JString).value, row.egress.wire)
        assertEquals((wantRow["servedClass"] as JString).value, row.servedClass?.wire)
        assertEquals(row.egress, row.reach)
        assertEquals(true, row.terminal)
    }

    @Test
    fun reachIsTheFurthestClassThatReceivedContentAndAMetadataOnlyOfferDoesNotRaiseIt() {
        val w = SimWorld(SimConfig(seed = 4))
        establish(w)
        w.apply(RequestStep("declined", "qwen3-8b", listOf(AttemptPlan.PeerDecline("PEER_BUSY"), AttemptPlan.LocalOk)))
        assertEquals(LabEgress.LOCAL, terminal(w, "declined").egress, "E-3: an offer that was declined carried no content")
        assertEquals("local", terminal(w, "declined").toEchoHeaders()[AsomHeaders.EGRESS])
        w.apply(RequestStep("failover", "qwen3-8b", listOf(AttemptPlan.PeerServed("error", 1, w.fuzzBody(30, 60)), AttemptPlan.CloudOk)))
        val t = terminal(w, "failover")
        assertEquals(LabEgress.CLOUD, t.egress)
        assertEquals(LabEgress.CLOUD, t.servedClass)
        assertEquals("cloud", t.toEchoHeaders()[AsomHeaders.EGRESS])
        assertEquals("usage", t.costBasis)
        w.apply(RequestStep("cloud-then-local", "qwen3-8b", listOf(AttemptPlan.CloudFail, AttemptPlan.LocalOk)))
        val c = terminal(w, "cloud-then-local")
        assertEquals(LabEgress.CLOUD, c.egress, "content reached the cloud even though SELF served: reach is monotone")
        assertEquals(LabEgress.LOCAL, c.servedClass)
        val laws = listOf(LedgerLaws.l5(w.trace.events), LedgerLaws.l5b(w.trace.events))
        assertTrue(laws.all { it.ok && it.cases >= 3 })
    }

    @Test
    fun aRequestWithNoAttemptThatSentContentReachesLocalAndAnErrorHasNoServedClass() {
        val w = SimWorld(SimConfig(seed = 5))
        w.apply(RequestStep("nothing", "m", listOf(AttemptPlan.CloudFail)))
        val t = terminal(w, "nothing")
        assertEquals(LabEgress.CLOUD, t.egress)
        assertNull(t.servedClass)
        assertNull(t.servedProvider)
        assertEquals(502, t.status)
        assertFailsWith<IllegalArgumentException> { RequestLedger(NodeLedger("A", MemorySink(), { 0 }), "r", "c", "m").note(LabEgress.DOWNLOAD, false, false) }
    }

    @Test
    fun exactlyOneTerminalRowPerRequest() {
        val n = NodeLedger("A", MemorySink(), { 0 })
        val rl = RequestLedger(n, "r", "c", "m")
        rl.note(LabEgress.LOCAL, contentSent = false, served = true, provider = "local", model = "m")
        rl.terminal(200)
        assertFailsWith<IllegalStateException> { rl.terminal(200) }
    }

    // ---- byte accounting ----

    @Test
    fun applicationBytesAreNineHeaderBytesPlusThePayloadAsInTheSpecsWorkedEncodings() {
        fun app(text: String) = FrameSpec(FrameKind.HELLO, text.toByteArray().size).appBytes
        assertEquals(16L, app("""{"v":1}"""), "HELLO {\"v\":1}")
        assertEquals(26L, app("""{"reason":"idle"}"""), "GOAWAY")
        assertEquals(16L, app("""{"v":1}"""), "STATE_REQ")
        assertEquals(67L, app("""{"attemptId":"AAAAAAAAAAAAAAAAAAAAAA","reason":"deadline"}"""), "CANCEL")
    }

    @Test
    fun estimatedOverheadIsTwentyTwoBytesPerRecordPlusFourKilobytesPerDirection() {
        assertEquals(0L, Overhead.estimatedRecords(emptyList()))
        assertEquals(22L, Overhead.estimatedRecords(listOf(1)))
        assertEquals(22L, Overhead.estimatedRecords(listOf(16_384)))
        assertEquals(44L, Overhead.estimatedRecords(listOf(16_385)))
        assertEquals(22L * 2 + 22L * 1, Overhead.estimatedRecords(listOf(20_000, 5)))
        assertEquals(8_192L, Overhead.estimatedHandshake())
        assertEquals(3L, ceilDiv(20_000, 8_192))
    }

    @Test
    fun aSessionWithoutAMeterIsEstimatedAndSaysSo() {
        val w = runWorld(9, SimConfig(seed = 9, measured = false))
        val overheadRows = w.rows("A").filter { it.overheadBytes != null } + w.rows("B").filter { it.overheadBytes != null }
        assertTrue(overheadRows.isNotEmpty())
        assertTrue(overheadRows.all { it.overheadBasis == OverheadBasis.ESTIMATED })
        assertTrue(overheadRows.any { it.meshKind == MeshKind.SESSION && it.meshCode == "close" })
    }

    @Test
    fun aMeasuredSessionCarriesTheHandshakeOnTheOpeningRowAndTheRestOnTheCloseRow() {
        val w = SimWorld(SimConfig(seed = 2))
        establish(w)
        w.apply(xyz.mdhv.asom.lab.ledger.sim.CloseStep(Side.A))
        val a = w.rows("A")
        val b = w.rows("B")
        assertEquals(MeshKind.DIAL, a.first().meshKind)
        val dialOut = a.single { it.meshKind == MeshKind.DIAL && it.phase == Phase.OUTCOME }
        val open = b.single { it.meshKind == MeshKind.SESSION && it.meshCode == "established" }
        assertEquals(dialOut.overheadBytes, open.overheadBytes, "both ends record the same handshake figure")
        assertTrue(dialOut.overheadBytes!! in 3_900L..6_000L)
        assertTrue(a.single { it.meshCode == "close" }.overheadBytes!! > 0)
        assertEquals(w.trace.events.filterIsInstance<xyz.mdhv.asom.lab.ledger.laws.Tap>().map { it.rawBytes }.distinct().size, 1, "both ends' taps count the same connection")
    }

    @Test
    fun theSessionColumnGroupsEveryRowOfAConnectionEvenBeforeTheHelloAndForPairing() {
        val w = SimWorld(SimConfig(seed = 6))
        w.run(FullScript.steps(w))
        for (node in listOf("A", "B")) {
            val bySession = w.rows(node).filter { it.sessionId != null }.groupBy { it.sessionId }
            assertTrue(bySession.size >= (if (node == "A") 4 else 2), "$node: one group per connection")
            for ((sid, rows) in bySession) {
                val kinds = rows.map { it.meshKind }.toSet()
                assertTrue(MeshKind.INBOUND_REFUSED !in kinds)
                if (node == "A") assertTrue(rows.first().meshKind == MeshKind.DIAL && rows.first().phase == Phase.INTENT, "the dialer's first row of $sid is the DIAL intent")
            }
        }
        val listenerFirst = w.rows("B").filter { it.sessionId != null }.groupBy { it.sessionId }.values.map { it.first() }
        assertTrue(listenerFirst.all { it.meshKind == MeshKind.SESSION }, "the listener's first row of every connection is SESSION open, written after the first frame that names the session")
        assertTrue(w.rows("B").single { it.meshKind == MeshKind.INBOUND_REFUSED }.sessionId == null)
    }

    @Test
    fun pathRuleGivesOverlayOnlyForTheSelectedOverlayAndLanOnlyForPrivateWifiOrEthernet() {
        assertEquals(PeerPath.OVERLAY, PeerPathRule.classify(IfaceKind.OVERLAY, true, true))
        assertNull(PeerPathRule.classify(IfaceKind.OVERLAY, false, true))
        assertEquals(PeerPath.LAN, PeerPathRule.classify(IfaceKind.WIFI, false, true))
        assertEquals(PeerPath.LAN, PeerPathRule.classify(IfaceKind.ETHERNET, false, true))
        assertNull(PeerPathRule.classify(IfaceKind.WIFI, false, false), "a public remote address is refused before TLS")
        assertNull(PeerPathRule.classify(IfaceKind.CELLULAR, false, true))
    }

    @Test
    fun theInboundRefusedCounterWritesOneAddresslessRowPerWindow() {
        var now = 0L
        val sink = MemorySink()
        val c = InboundRefusedCounter(NodeLedger("B", sink, { now }), windowMs = 600_000)
        assertNull(c.flush())
        repeat(3) { c.refused() }
        now = 599_999
        assertNull(c.flush(), "the window has not elapsed")
        now = 600_000
        val r = c.flush()
        assertEquals("refused:3", r?.meshCode)
        assertNull(r?.destAddr)
        assertNull(r?.sessionId)
        assertNull(c.flush(), "the count restarts")
        assertEquals(1, sink.all().size)
    }
}
