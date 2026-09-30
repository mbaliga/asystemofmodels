package xyz.mdhv.asom.lab.ledger

import xyz.mdhv.asom.lab.ledger.sim.AttemptPlan
import xyz.mdhv.asom.lab.ledger.sim.CloseStep
import xyz.mdhv.asom.lab.ledger.sim.DialStep
import xyz.mdhv.asom.lab.ledger.sim.FrameStep
import xyz.mdhv.asom.lab.ledger.sim.InboundRefusedStep
import xyz.mdhv.asom.lab.ledger.sim.RequestStep
import xyz.mdhv.asom.lab.ledger.sim.Side
import xyz.mdhv.asom.lab.ledger.sim.SimWorld
import xyz.mdhv.asom.lab.ledger.sim.Step

/**
 * One deterministic run that writes every kind of row the model has: a refused dial, an established session with control, state and manifest
 * frames, a decline, a served attempt, a cancelled attempt, a cloud-served request, a revocation and a GOAWAY; a pairing session; a hostile node's
 * extension frame; and the inbound-refused counter.
 */
object FullScript {
    fun steps(w: SimWorld): List<Step> {
        val p = w.payloads
        val s = ArrayList<Step>()
        s += DialStep(outcome = "refused", source = "hello")
        s += DialStep(SessionMode.ESTABLISHED, "connected", "qr", IfaceKind.WIFI)
        s += FrameStep(Side.A, p.hello(p.b64u(16)))
        s += FrameStep(Side.B, p.helloAck())
        s += FrameStep(Side.A, p.stateReq(3))
        s += FrameStep(Side.B, p.state(3))
        s += FrameStep(Side.A, p.manifestReq(5))
        s += FrameStep(Side.B, p.manifest(5, 2_000))
        s += RequestStep("req-1", "qwen3-8b", listOf(AttemptPlan.PeerDecline("PEER_BUSY"), AttemptPlan.PeerServed("done", 2, w.fuzzBody(40, 80))))
        s += RequestStep("req-2", "qwen3-8b", listOf(AttemptPlan.PeerCancelled(w.fuzzBody(40, 80)), AttemptPlan.CloudOk))
        s += RequestStep("req-3", "qwen3-8b", listOf(AttemptPlan.PeerServed("interrupted", 1, w.fuzzBody(40, 80)), AttemptPlan.LocalOk))
        s += FrameStep(Side.B, p.error("SCOPE_DENIED", 0))
        s += FrameStep(Side.A, p.revoke())
        s += FrameStep(Side.B, p.goaway("shutdown"))
        s += CloseStep(Side.A)
        s += DialStep(SessionMode.PAIRING, "connected", "qr", IfaceKind.OVERLAY)
        s += FrameStep(Side.B, p.pair(FrameKind.PAIR_HELLO))
        s += FrameStep(Side.A, p.pair(FrameKind.PAIR_CHALLENGE))
        s += FrameStep(Side.A, p.pair(FrameKind.PAIR_DECISION))
        s += FrameStep(Side.B, p.pair(FrameKind.PAIR_DECISION))
        s += FrameStep(Side.A, p.pair(FrameKind.PAIR_COMMIT))
        s += FrameStep(Side.B, p.pair(FrameKind.PAIR_COMMIT_ACK))
        s += CloseStep(Side.B)
        s += DialStep(SessionMode.ESTABLISHED, "connected", "user", IfaceKind.ETHERNET, hostile = true)
        s += FrameStep(Side.B, p.extension(64))
        s += CloseStep(Side.A)
        s += InboundRefusedStep(3)
        return s
    }

    /** The durability points of LAB_SPEC 7.6 / contract.md 4.4, each with the predicate that recognises the row written AT that point. */
    val POINTS: List<Pair<String, (LabRouteRecord) -> Boolean>> = listOf(
        "DIAL-INTENT (before the SYN)" to { r -> r.meshKind == MeshKind.DIAL && r.phase == Phase.INTENT },
        "DIAL-OUTCOME" to { r -> r.meshKind == MeshKind.DIAL && r.phase == Phase.OUTCOME },
        "SESSION-OPEN" to { r -> r.meshKind == MeshKind.SESSION && r.meshCode in setOf("established", "pairing") },
        "CONTROL-ROW (before the frame is sent)" to { r -> r.meshKind == MeshKind.CONTROL },
        "D1 requester intent (before INFER_OFFER)" to { r -> r.meshKind == MeshKind.INFER_SENT && r.phase == Phase.INTENT },
        "D2 requester outcome" to { r -> r.meshKind == MeshKind.INFER_SENT && r.phase == Phase.OUTCOME },
        "D3 lender decline outcome (before INFER_DECLINE)" to { r -> r.meshKind == MeshKind.INFER_SERVED && r.phase == Phase.OUTCOME && r.status == 503 },
        "D4 lender intent (before the engine reads the body)" to { r -> r.meshKind == MeshKind.INFER_SERVED && r.phase == Phase.INTENT },
        "D5 lender outcome (before INFER_END)" to { r -> r.meshKind == MeshKind.INFER_SERVED && r.phase == Phase.OUTCOME && r.status != 503 },
        "MANIFEST-SENT" to { r -> r.meshKind == MeshKind.MANIFEST_SENT },
        "MANIFEST-RECEIVED" to { r -> r.meshKind == MeshKind.MANIFEST_RECEIVED },
        "PAIRING-ROW" to { r -> r.meshKind == MeshKind.PAIRING },
        "REVOCATION-ROW" to { r -> r.meshKind == MeshKind.REVOCATION },
        "TERMINAL request row" to { r -> r.terminal == true },
        "SESSION-CLOSE" to { r -> r.meshKind == MeshKind.SESSION && r.meshCode == "close" },
        "INBOUND-REFUSED" to { r -> r.meshKind == MeshKind.INBOUND_REFUSED },
    )
}
