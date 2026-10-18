package xyz.mdhv.asom.lab.ledger

/**
 * The lab's egress classes (LAB_SPEC 7.5). This is NOT the frozen v1 `Egress`: v1 keeps its four values, and no lab code
 * ever gives the frozen name a peer member (rule R4). The peer class is reached as [peerClass] so that no source line
 * spells the frozen enum name with a peer member.
 */
enum class LabEgress(val wire: String) {
    LOCAL("local"),
    CLOUD("cloud"),
    CATALOGUE("catalogue"),
    DOWNLOAD("download"),
    PEER("peer"),
    CONTRIBUTION("contribution"),
    ;

    /** Position in the reach order local < peer < cloud (contract.md 4.6 E-2). Null for classes that never carry request content. */
    val reachRank: Int?
        get() = when (this) {
            LOCAL -> 0
            PEER -> 1
            CLOUD -> 2
            else -> null
        }

    companion object {
        val peerClass: LabEgress get() = PEER

        fun fromWire(wire: String): LabEgress? = entries.firstOrNull { it.wire == wire }

        /** The furthest class that received content, or [LOCAL] when none did. */
        fun reachOf(classes: Iterable<LabEgress>): LabEgress =
            classes.maxByOrNull { it.reachRank ?: throw IllegalArgumentException("$it carries no request content") } ?: LOCAL
    }
}

enum class Phase(val wire: String) {
    INTENT("intent"),
    OUTCOME("outcome"),
    ;

    companion object {
        fun fromWire(wire: String): Phase? = entries.firstOrNull { it.wire == wire }
    }
}

enum class MeshKind(val wire: String) {
    INFER_SENT("infer-sent"),
    INFER_SERVED("infer-served"),
    DIAL("dial"),
    SESSION("session"),
    CONTROL("control"),
    INBOUND_REFUSED("inbound-refused"),
    MANIFEST_SENT("manifest-sent"),
    MANIFEST_RECEIVED("manifest-received"),
    PAIRING("pairing"),
    REVOCATION("revocation"),
    ;

    companion object {
        fun fromWire(wire: String): MeshKind? = entries.firstOrNull { it.wire == wire }
    }
}

enum class OverheadBasis(val wire: String) {
    MEASURED("measured"),
    ESTIMATED("estimated"),
    ;

    companion object {
        fun fromWire(wire: String): OverheadBasis? = entries.firstOrNull { it.wire == wire }
    }
}

enum class PeerPath(val wire: String) {
    LAN("lan"),
    OVERLAY("overlay"),
    ;

    companion object {
        fun fromWire(wire: String): PeerPath? = entries.firstOrNull { it.wire == wire }
    }
}

/** Ceiling division for non-negative operands (LAB_SPEC 6.4 `ceilDiv`). */
fun ceilDiv(a: Long, b: Long): Long {
    require(a >= 0 && b > 0) { "ceilDiv needs a >= 0 and b > 0" }
    return (a + b - 1) / b
}
