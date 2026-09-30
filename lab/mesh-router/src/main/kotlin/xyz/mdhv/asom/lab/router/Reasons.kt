package xyz.mdhv.asom.lab.router

/** Route reasons (LAB_SPEC 6.7): `tier ":" code [ "/" term ] *( ";" flag )`. */
object RouteReasons {
    val TIERS = setOf("v1", "self", "peer", "cloud")
    val CODES = setOf("policy", "only-eligible", "best-score", "no-usable-sovereign", "policy-rank", "policy-fastest", "policy-cheapest", "failover")

    /** Row statuses of a peer attempt (router.md 8.2 with the r3 codes). All of them collapse to `peer-unavailable` in the app-facing projection. */
    val PEER_STATUSES = setOf(
        "PEER_UNREACHABLE", "PEER_BUSY", "PEER_UNAVAILABLE", "MODEL_NOT_OFFERED", "SCOPE_DENIED", "PEER_NOT_PAIRED", "OFFER_TIMEOUT", "CANCELLED_BEFORE_BODY",
        "PEER_LOST_PRE_HEAD", "PEER_LOST", "DUPLICATE_ATTEMPT", "PEER_INTERRUPTED", "PEER_OOM", "PEER_ERROR", "PEER_CANCELLED", "PEER_LOST_MID_STREAM", "PEER_INTERRUPTED_MID_STREAM",
    )

    /** The dominant term of a win: `d_t = runnerUp.S_t - winner.S_t`, the largest wins, ties in the order S1..S6; null when no term favours the winner. */
    fun dominantTerm(winner: ScoreBreakdown, runnerUp: ScoreBreakdown): String? {
        var best = 0L
        var name: String? = null
        for (i in 1..6) {
            val d = runnerUp.term(i) - winner.term(i)
            if (d > best) {
                best = d
                name = ScoreBreakdown.TERM_NAMES[i - 1]
            }
        }
        return name
    }

    fun compose(tier: String, code: String, term: String?, flags: List<String>): String {
        require(tier in TIERS && code in CODES) { "unknown tier or code" }
        return buildString {
            append(tier).append(':').append(code)
            if (term != null) append('/').append(term)
            flags.forEach { append(';').append(it) }
        }
    }

    /**
     * The app-facing projection (design 7.7, D12.4): `tier ":" code ["/" term]` plus at most `;prev:<status>`. A battery, heat or uncertainty term is dropped,
     * the flags `stale`, `probe` and `cap:unverified` are dropped, and every peer decline or failure becomes `;prev:peer-unavailable`. It never carries a `PEER_*` code.
     */
    fun appFacing(reason: String): String {
        val flagsStart = reason.indexOf(';')
        val head = if (flagsStart < 0) reason else reason.substring(0, flagsStart)
        val flags = if (flagsStart < 0) emptyList() else reason.substring(flagsStart + 1).split(';')
        val term = head.substringAfter('/', "")
        val kept = if (term in setOf("battery", "heat", "uncertainty")) head.substringBefore('/') else head
        val prev = flags.firstOrNull { it.startsWith("prev:") }?.removePrefix("prev:")
        val out = StringBuilder(kept)
        if (prev != null) out.append(";prev:").append(if (prev in PEER_STATUSES || prev.startsWith("PEER_")) "peer-unavailable" else prev)
        return out.toString()
    }

    /** `flag = "cap:unverified" | "probe" | "stale" | "prev:" <status>`; [prevStatus] is the previous attempt's row status. */
    fun withPrev(reason: String, prevStatus: String): String = "$reason;prev:$prevStatus"
}
