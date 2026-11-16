package xyz.mdhv.asom.lab.proto.wire

import java.security.MessageDigest

data class VersionRange(val minV: Int, val maxV: Int) {
    init {
        require(minV in 1..255 && maxV in minV..255) { "a version range is 1 <= minV <= maxV <= 255" }
    }
}

/** The version rule (LAB_SPEC 7.2): the highest `v` that lies in both ranges; none means `ERROR VERSION_UNSUPPORTED` and close. There is no downgrade below a node's own `minV`. */
object VersionRule {
    fun negotiate(a: VersionRange, b: VersionRange): Int? {
        val hi = minOf(a.maxV, b.maxV)
        val lo = maxOf(a.minV, b.minV)
        return if (hi >= lo) hi else null
    }
}

sealed interface HelloDecision {
    /** The server accepts the session at [v] and answers `HELLO_ACK`. */
    class Established(val v: Int, val features: Set<Feature>) : HelloDecision

    /** Send [error] and close. */
    class Refuse(val error: PeerError) : HelloDecision
}

sealed interface AckDecision {
    class Established(val v: Int, val granted: Set<Scope>, val limits: Limits, val features: Set<Feature>) : AckDecision

    class Refuse(val error: PeerError) : AckDecision
}

/** The HELLO and HELLO_ACK negotiation as pure functions: no socket, no clock, no registry. The TLS-presented identity is passed in by the caller. */
object Handshake {
    private fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    /**
     * The server's decision on a received `HELLO`. `nodeId` must equal the identity the TLS handshake authenticated (a mismatch is `PROTOCOL_ERROR`,
     * decided before anything about versions is revealed); then the version rule applies. [localFeatures] are what this node offers.
     */
    fun onHello(local: VersionRange, localFeatures: Set<Feature>, hello: Hello, tlsPeerNodeId: String): HelloDecision {
        if (!same(hello.nodeId, tlsPeerNodeId)) return HelloDecision.Refuse(PeerError(MeshError.PROTOCOL_ERROR))
        val v = VersionRule.negotiate(local, hello.versions) ?: return HelloDecision.Refuse(PeerError(MeshError.VERSION_UNSUPPORTED))
        return HelloDecision.Established(v, hello.features intersect localFeatures)
    }

    /** The `HELLO_ACK` for an established session. `features` is the intersection of both sides' features (sorted on the wire). */
    fun ack(
        decision: HelloDecision.Established,
        localNodeId: String,
        endpoints: List<Endpoint>,
        granted: Set<Scope>,
        limits: Limits,
        st: St?,
        ts: Long,
    ): HelloAck = HelloAck(endpoints, decision.features, granted, limits, localNodeId, st, ts, decision.v)

    /**
     * The client's decision on a received `HELLO_ACK`: the ack's `nodeId` must equal the server identity TLS authenticated, and its `v` must lie in the
     * client's own range (an ack outside it is `VERSION_UNSUPPORTED`; the client never downgrades below its `minV`).
     */
    fun onAck(local: VersionRange, localFeatures: Set<Feature>, ack: HelloAck, tlsPeerNodeId: String): AckDecision {
        if (!same(ack.nodeId, tlsPeerNodeId)) return AckDecision.Refuse(PeerError(MeshError.PROTOCOL_ERROR))
        if (ack.v !in local.minV..local.maxV) return AckDecision.Refuse(PeerError(MeshError.VERSION_UNSUPPORTED))
        return AckDecision.Established(ack.v, ack.granted, ack.limits, ack.features intersect localFeatures)
    }
}
