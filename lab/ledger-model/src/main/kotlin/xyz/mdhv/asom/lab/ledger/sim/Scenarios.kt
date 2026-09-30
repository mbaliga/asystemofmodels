package xyz.mdhv.asom.lab.ledger.sim

import java.util.SplittableRandom
import xyz.mdhv.asom.lab.ledger.FrameKind
import xyz.mdhv.asom.lab.ledger.IfaceKind
import xyz.mdhv.asom.lab.ledger.SessionMode

/** Random but valid protocol runs for the property tests of the ledger laws. Deterministic in the seed (a `SplittableRandom`, no clock, no hash order). */
object Scenarios {
    private val declineCodes = listOf("PEER_BUSY", "PEER_UNAVAILABLE", "MODEL_NOT_OFFERED", "SCOPE_DENIED", "DUPLICATE_ATTEMPT")
    private val goawayReasons = listOf("shutdown", "idle", "network-change", "max-age", "revoked", "suspended")
    private val failedDials = listOf("refused", "timeout", "pin-mismatch", "not-tls", "firewall-blocked")

    private fun <T> SplittableRandom.pick(xs: List<T>): T = xs[nextInt(xs.size)]

    fun attemptPlans(world: SimWorld, rng: SplittableRandom, withPeer: Boolean): List<AttemptPlan> {
        val out = ArrayList<AttemptPlan>()
        val n = 1 + rng.nextInt(3)
        repeat(n) {
            out += if (!withPeer) {
                rng.pick(listOf(AttemptPlan.CloudFail, AttemptPlan.CloudOk, AttemptPlan.LocalOk))
            } else {
                when (rng.nextInt(7)) {
                    0 -> AttemptPlan.PeerDecline(rng.pick(declineCodes))
                    1 -> AttemptPlan.PeerServed(rng.pick(listOf("done", "done", "done", "interrupted", "error", "oom")), rng.nextInt(4), world.fuzzBody(20, 300))
                    2 -> AttemptPlan.PeerServed("done", 1 + rng.nextInt(3), world.fuzzBody(20, 300))
                    3 -> AttemptPlan.PeerCancelled(world.fuzzBody(20, 200))
                    4 -> AttemptPlan.CloudFail
                    5 -> AttemptPlan.CloudOk
                    else -> AttemptPlan.LocalOk
                }
            }
        }
        return out
    }

    fun random(world: SimWorld, seed: Long): List<Step> {
        val rng = SplittableRandom(seed)
        val p = world.payloads
        val steps = ArrayList<Step>()
        var req = 0
        fun requestId() = "req-${seed}-${req++}"

        // Requests before any connection can use only local and cloud.
        if (rng.nextInt(3) == 0) steps += RequestStep(requestId(), "llama-3.3-70b", attemptPlans(world, rng, withPeer = false))

        val connections = 1 + rng.nextInt(3)
        repeat(connections) {
            val iface = rng.pick(listOf(IfaceKind.WIFI, IfaceKind.ETHERNET, IfaceKind.OVERLAY))
            when (rng.nextInt(10)) {
                0 -> steps += DialStep(outcome = rng.pick(failedDials), source = rng.pick(listOf("qr", "hello", "user")), iface = iface)
                1, 2 -> pairing(steps, world, rng, iface)
                3 -> hostile(steps, world, rng, iface)
                else -> established(steps, world, rng, iface, ::requestId)
            }
            if (rng.nextInt(4) == 0) steps += InboundRefusedStep(1 + rng.nextInt(5))
        }
        return steps
    }

    private fun established(steps: MutableList<Step>, world: SimWorld, rng: SplittableRandom, iface: IfaceKind, requestId: () -> String) {
        val p = world.payloads
        steps += DialStep(SessionMode.ESTABLISHED, "connected", rng.pick(listOf("qr", "hello", "user")), iface)
        steps += FrameStep(Side.A, p.hello(p.b64u(16)))
        steps += FrameStep(Side.B, p.helloAck())
        var stream = 3
        if (rng.nextInt(2) == 0) {
            steps += FrameStep(Side.A, p.stateReq(stream))
            steps += FrameStep(Side.B, p.state(stream))
            stream += 2
        }
        if (rng.nextInt(3) == 0) {
            steps += FrameStep(Side.A, p.manifestReq(stream))
            steps += FrameStep(Side.B, p.manifest(stream, 200 + rng.nextInt(30_000)))
            stream += 2
        }
        repeat(rng.nextInt(3)) { steps += RequestStep(requestId(), "qwen3-8b", attemptPlans(world, rng, withPeer = true)) }
        if (rng.nextInt(4) == 0) steps += FrameStep(Side.B, p.error(listOf("PEER_NOT_PAIRED", "SCOPE_DENIED", "PROTOCOL_ERROR").let { it[rng.nextInt(it.size)] }, 0))
        if (rng.nextInt(4) == 0) steps += FrameStep(if (rng.nextBoolean()) Side.A else Side.B, p.revoke())
        if (rng.nextInt(3) == 0) steps += FrameStep(Side.B, p.goaway(rng.pick(goawayReasons)))
        steps += CloseStep(if (rng.nextBoolean()) Side.A else Side.B)
    }

    private fun pairing(steps: MutableList<Step>, world: SimWorld, rng: SplittableRandom, iface: IfaceKind) {
        val p = world.payloads
        steps += DialStep(SessionMode.PAIRING, "connected", "qr", iface)
        steps += FrameStep(Side.B, p.pair(FrameKind.PAIR_HELLO))
        steps += FrameStep(Side.A, p.pair(FrameKind.PAIR_CHALLENGE))
        steps += FrameStep(Side.A, p.pair(FrameKind.PAIR_DECISION))
        steps += FrameStep(Side.B, p.pair(FrameKind.PAIR_DECISION))
        steps += FrameStep(Side.A, p.pair(FrameKind.PAIR_COMMIT))
        steps += FrameStep(Side.B, p.pair(FrameKind.PAIR_COMMIT_ACK))
        steps += CloseStep(if (rng.nextBoolean()) Side.A else Side.B)
    }

    private fun hostile(steps: MutableList<Step>, world: SimWorld, rng: SplittableRandom, iface: IfaceKind) {
        val p = world.payloads
        steps += DialStep(SessionMode.ESTABLISHED, "connected", "user", iface, hostile = true)
        repeat(1 + rng.nextInt(2)) { steps += FrameStep(Side.B, p.extension(1 + rng.nextInt(400))) }
        steps += CloseStep(Side.A)
    }
}
