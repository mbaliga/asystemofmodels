package xyz.mdhv.asom.contract

/**
 * The complete, FROZEN set of `X-Asom-*` headers (brief §5.3–§5.4).
 * No new headers without owner sign-off.
 */
object AsomHeaders {
    // Request headers (§5.3) — all optional except Authorization.
    const val POLICY = "X-Asom-Policy"
    const val FALLBACK = "X-Asom-Fallback"
    const val NO_TRAIN = "X-Asom-No-Train"

    // Response echo headers (§5.4) — on every /v1/* response.
    const val SERVED_BY = "X-Asom-Served-By"
    const val EGRESS = "X-Asom-Egress"
    const val COST_EST = "X-Asom-Cost-Est"
    const val COST_BASIS = "X-Asom-Cost-Basis"
}
