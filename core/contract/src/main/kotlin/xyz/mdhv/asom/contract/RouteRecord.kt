package xyz.mdhv.asom.contract

import java.math.BigDecimal
import kotlinx.serialization.Serializable

/** Egress classes (brief §1.3, §9). Exhaustive — no additions (invariant). */
enum class Egress(val wire: String) {
    LOCAL("local"),
    CLOUD("cloud"),
    CATALOGUE("catalogue"),
    DOWNLOAD("download"),
}

/** Basis for a cost estimate (§5.4). Never estimate without a basis. */
enum class CostBasis(val wire: String) {
    USAGE("usage"),
    HEURISTIC("heuristic"),

    /** No cost derivable — the cost headers are omitted entirely. */
    NONE("none"),
}

/**
 * THE single source of truth for what a request did (invariant §1.9):
 * the per-response echo headers AND the ledger row are both built from this
 * struct, so the API answer and the dashboard can never disagree.
 *
 * Field set mirrors the `route_log` ledger table (brief §9).
 */
@Serializable
data class RouteRecord(
    /** Epoch millis at request completion. */
    val ts: Long,
    /** Verified caller package ("desktop" for the JVM dev server). */
    val callerPkg: String,
    /** The raw `model` field of the request (concrete id or virtual policy). */
    val requestedModel: String,
    /** Provider that actually served, null if nothing served (error path). */
    val servedProvider: String? = null,
    /** Concrete model that actually served, null if nothing served. */
    val servedModel: String? = null,
    val egress: Egress,
    /** Request body bytes that left the device (0 for local/error paths). */
    val bytesOut: Long = 0,
    val tokensIn: Long? = null,
    val tokensOut: Long? = null,
    /** USD estimate — only meaningful when [costBasis] != NONE. */
    val costEst: Double? = null,
    val costBasis: CostBasis = CostBasis.NONE,
    val latencyMs: Long,
    /** HTTP status returned to the caller. */
    val status: Int,
) {
    /**
     * Builds the §5.4 echo headers. Laws:
     *  - `X-Asom-Served-By: <provider>/<model>` only when both are known.
     *  - `X-Asom-Egress` is always present (`local|cloud`).
     *  - Cost headers appear ONLY when an estimate exists AND has a basis.
     */
    fun toEchoHeaders(): Map<String, String> = buildMap {
        if (servedProvider != null && servedModel != null) {
            put(AsomHeaders.SERVED_BY, "$servedProvider/$servedModel")
        }
        put(AsomHeaders.EGRESS, egress.wire)
        if (costEst != null && costBasis != CostBasis.NONE) {
            put(AsomHeaders.COST_EST, formatUsd(costEst))
            put(AsomHeaders.COST_BASIS, costBasis.wire)
        }
    }

    companion object {
        /**
         * Locale-safe plain-decimal USD rendering: no scientific notation,
         * rounded to 8 decimal places (sub-hundredth-of-a-cent is noise).
         */
        fun formatUsd(value: Double): String =
            BigDecimal.valueOf(value)
                .setScale(8, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
    }
}
