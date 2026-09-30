package xyz.mdhv.asom.lab.bench

enum class Confidence(val wire: String) {
    INSUFFICIENT("insufficient"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ;

    companion object {
        fun min(a: Confidence, b: Confidence): Confidence = if (a.ordinal <= b.ordinal) a else b

        fun minOf(all: Iterable<Confidence>): Confidence = all.reduceOrNull { x, y -> min(x, y) } ?: INSUFFICIENT

        fun fromWire(s: String): Confidence = entries.first { it.wire == s }
    }
}

/** The M04 statistics of one test: which reps were kept, and the conservative median of the kept ones (benchmark.md 9). */
data class Stat(
    val n: Int,
    val keptIdx: List<Int>,
    /** Null when fewer than 2 reps were kept: an insufficient result reports no value (benchmark.md 9.4). */
    val value: Long?,
    val relSpreadPermille: Long,
    val flags: List<String>,
) {
    val kept: Int get() = keptIdx.size
    val drift: Boolean get() = "THERMAL_DRIFT" in flags
}

object Stats {
    const val OUTLIER_K_MILLI: Long = 4449L
    const val MAD_ZERO_DEV_PERMILLE: Long = 250L
    const val MAX_EXCLUDE_DIV: Int = 5
    const val DRIFT_FIRST_LAST_PERMILLE: Long = 100L
    const val DRIFT_MONOTONE_MIN_KEPT: Int = 4
    const val DRIFT_FIRST_LAST_MIN_KEPT: Int = 3

    /** `x[(n-1)/2]`: the conservative median of a rate (higher is better). */
    fun lowerMedian(xs: List<Long>): Long {
        require(xs.isNotEmpty())
        return xs.sorted()[(xs.size - 1) / 2]
    }

    /** `x[n/2]`: the conservative median of a duration (lower is better). */
    fun upperMedian(xs: List<Long>): Long {
        require(xs.isNotEmpty())
        return xs.sorted()[xs.size / 2]
    }

    /** Nearest-rank percentile over the ascending values: `x[ceil(p*n/1000) - 1]` (benchmark.md 13.3, M04-009). */
    fun nearestRank(xs: List<Long>, pPermille: Long): Long {
        val s = xs.sorted()
        val k = Checked.ceilDiv(Checked.mul(pPermille, s.size.toLong()), 1000L)
        return s[(maxOf(k, 1L) - 1L).toInt()]
    }

    /**
     * Outliers by the MAD rule, at most floor(n/5) of them excluded (else UNSTABLE and none), then the thermal-drift rule of
     * design 6.5 B7 (ERRATA ERR-BENCH-3): on the kept reps in time order, slowing down at every step (needs 4 kept) or the last kept
     * rep more than 100 permille slower than the first kept rep (needs 3 kept) flags THERMAL_DRIFT, and a first rep that the
     * outlier rule excluded is put back.
     */
    fun stat(values: List<Long>): Stat {
        val n = values.size
        require(n >= 1)
        val med = lowerMedian(values)
        val mad = lowerMedian(values.map { Checked.absDiff(it, med) })
        val outliers = values.indices.filter { i ->
            val dev1000 = Checked.mul(Checked.absDiff(values[i], med), 1000L)
            if (mad > 0L) dev1000 > Checked.mul(OUTLIER_K_MILLI, mad) else dev1000 > Checked.mul(MAD_ZERO_DEV_PERMILLE, med)
        }
        val flags = mutableListOf<String>()
        var keptIdx: List<Int>
        if (outliers.size > n / MAX_EXCLUDE_DIV) {
            keptIdx = values.indices.toList()
            flags += "UNSTABLE"
        } else {
            keptIdx = values.indices.filter { it !in outliers }
            if (outliers.isNotEmpty()) flags += "OUTLIER_EXCLUDED"
        }
        val seq = keptIdx.map { values[it] }
        val slowsEveryStep = seq.size >= DRIFT_MONOTONE_MIN_KEPT && (1 until seq.size).all { seq[it] < seq[it - 1] }
        val firstLast = seq.size >= DRIFT_FIRST_LAST_MIN_KEPT && seq.first() > seq.last() &&
            Checked.mul(Checked.sub(seq.first(), seq.last()), 1000L) > Checked.mul(DRIFT_FIRST_LAST_PERMILLE, seq.first())
        if (slowsEveryStep || firstLast) {
            flags += "THERMAL_DRIFT"
            if (0 !in keptIdx) keptIdx = listOf(0) + keptIdx
            if (keptIdx.size == n) flags.remove("OUTLIER_EXCLUDED")
        }
        val kept = keptIdx.map { values[it] }
        val value = if (kept.size >= 2) lowerMedian(kept) else null
        val spread = if (value != null && value > 0L) Checked.permille(Checked.sub(kept.max(), kept.min()), value) else 0L
        return Stat(n, keptIdx, value, spread, flags)
    }

    /**
     * benchmark.md 9.4: the first matching row gives the base class, then the caps take the minimum. [capStart], [capVirtual] and
     * [capStream] are the `medium` caps; a THERMAL_DRIFT flag caps at `low`. A restart after a yield and swap growth are folded
     * into [capStart] and a `low` cap by `Derive`; iPadOS background continuation carries no field and is not modelled (ERRATA ERR-BENCH-4).
     */
    fun confidence(st: Stat, contentionPermille: Long, capStart: Boolean, capVirtual: Boolean, capStream: Boolean): Confidence {
        if (st.kept < 2) return Confidence.INSUFFICIENT
        val unstable = "UNSTABLE" in st.flags
        var c = when {
            st.n >= 5 && st.kept >= 4 && st.relSpreadPermille <= 50 && contentionPermille <= 50 && !unstable -> Confidence.HIGH
            st.kept >= 3 && st.relSpreadPermille <= 150 && contentionPermille <= 150 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        if (capStart || capVirtual || capStream) c = Confidence.min(c, Confidence.MEDIUM)
        if (st.drift) c = Confidence.min(c, Confidence.LOW)
        return c
    }
}
