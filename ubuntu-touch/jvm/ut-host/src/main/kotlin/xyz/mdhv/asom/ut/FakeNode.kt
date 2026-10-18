package xyz.mdhv.asom.ut

import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.PeerPath

/**
 * `--fake-ui`: a scripted node for the QML tests (`tests/qml`). It speaks `asom-ut-ctl/1` through the real [NodeSession] and
 * the real lifecycle FSM, but its peers and answers are canned and nothing leaves the process. Model `fake` answers,
 * `fake-interrupt` answers half and then reports `MESH_STREAM_INTERRUPTED`, `fake-cooling` reports
 * `ALL_PROVIDERS_COOLING`, anything else `MODEL_UNKNOWN`.
 */
object FakeNode {
    private val PEERS = listOf(
        PeerView("fake-deck", setOf("fake", "fake-interrupt"), cooling = false),
        PeerView("fake-cooling-peer", setOf("fake-cooling"), cooling = true),
    )

    fun session(reader: LineReader, out: FrameSink, clock: MonotonicClock, diag: Diag): NodeSession =
        NodeSession(
            reader = reader,
            out = out,
            lifecycle = NodeLifecycle(clock),
            diag = diag,
            openLedger = { FakeLedger },
            selfTest = { fakeSelfTest() },
            requester = FakeRequester,
            peers = { PEERS },
            nodeTag = "fake0fake0fake0fa",
            keyStorage = "file",
        )

    private fun fakeSelfTest(): JObject = JObject(listOf("selftest" to JString("ok"), "profile" to JString("fake-ui")))

    private object FakeLedger : LedgerPort {
        override fun rows(since: Long, limit: Int): List<JObject> = listOf(terminalRow("fake-1", "fake", 1_000L).toRow()).take(limit)
        override fun close() {}
    }

    fun terminalRow(rid: String, model: String, ts: Long): LabRouteRecord = LabRouteRecord(
        ts = ts, callerPkg = "self-ui:${UtPaths.PKG}", requestedModel = model, servedProvider = "peer", servedModel = "fake",
        egress = LabEgress.PEER, tokensIn = 3, tokensOut = 4, latencyMs = 12, status = 200, requestId = rid,
        reach = LabEgress.PEER, terminal = true, servedClass = LabEgress.PEER, peerNode = "fake0fake0fake0fa", peerAlias = "fake-deck",
        peerPath = PeerPath.LAN,
    )

    private object FakeRequester : Requester {
        override fun start(request: UiFrame.Borrow, sink: FrameSink) {
            sink.send(NodeFrame.Chunk(request.rid, "Hello "))
            if (request.model == "fake-interrupt") {
                sink.send(NodeFrame.Error(request.rid, UiErrorCode.MESH_STREAM_INTERRUPTED))
                return
            }
            sink.send(NodeFrame.Chunk(request.rid, "from the fake node."))
            sink.send(NodeFrame.End(request.rid, UiProjection.project(terminalRow(request.rid, request.model, 2_000L))))
        }

        override fun cancel(rid: String) {}
        override fun cancelAll() {}
        override fun closeSessions(closedBy: String) {}
        override fun writeInterruptedRows(attemptIds: List<String>) {}
    }
}
