package xyz.mdhv.asom.lab.policy

enum class Freshness { FRESH, WARM, STALE, EXPIRED }

/**
 * Everything staleness reads, all on the REQUESTER's monotonic clock. The peer's wall clock (`HELLO.ts`) has no field here, so a wrong peer clock cannot
 * change any class (W07 skew vectors at +-10 minutes).
 */
data class StalenessInput(
    val nowMonoMs: Long,
    val stateRxMonoMs: Long,
    val sampledAgeMs: Long,
    val sessionClosed: Boolean,
    val seq: Long,
    val lastSeq: Long?,
    val goawaySinceState: Boolean,
)

object Staleness {
    const val FRESH_MAX = 5_000L
    const val WARM_MAX = 30_000L
    const val STALE_MAX = 300_000L
    const val SAMPLED_AGE_CAP = 60_000L
    const val CLOSED_SESSION_MAX = 30_000L

    /** `ageMs = (nowMono - rxMono) + min(sampledAgeMs, 60,000)`. */
    fun ageMs(i: StalenessInput): Long = (i.nowMonoMs - i.stateRxMonoMs) + minOf(i.sampledAgeMs, SAMPLED_AGE_CAP)

    /**
     * FRESH <= 5,000 < WARM <= 30,000 < STALE <= 300,000 < EXPIRED; also EXPIRED if the session is closed and the state is older than 30,000, if `seq` went backwards
     * (strictly less than the last one seen: an equal `seq` is a repeat, not a reset, ERRATA ERR-LP-4), or if a `GOAWAY` arrived since.
     *
     * [peerWallTsMs] (the peer's own timestamp, e.g. `HELLO.ts`) is accepted so that a caller cannot be tempted to combine clocks, and it is deliberately unused:
     * the W07 skew vectors and the property test call this with the peer clock moved by +-10 minutes and require the same class.
     */
    fun classify(i: StalenessInput, peerWallTsMs: Long? = null): Freshness {
        val age = ageMs(i)
        if (i.goawaySinceState) return Freshness.EXPIRED
        if (i.lastSeq != null && i.seq < i.lastSeq) return Freshness.EXPIRED
        if (i.sessionClosed && age > CLOSED_SESSION_MAX) return Freshness.EXPIRED
        return when {
            age <= FRESH_MAX -> Freshness.FRESH
            age <= WARM_MAX -> Freshness.WARM
            age <= STALE_MAX -> Freshness.STALE
            else -> Freshness.EXPIRED
        }
    }

    /** The fast fields a router may read from a state, after the pessimistic substitution that STALE requires. EXPIRED state yields nothing (probe-only). */
    data class Fast(val thermalBand: Int, val queueBucket: Int, val batteryBand: BatteryBand?)

    /**
     * LAB_SPEC 6.5, literally: for STALE, `thermalBand = max(last, 1) if last >= 1` (which leaves it unchanged, ERRATA ERR-LP-5: the rule as written is a no-op),
     * `queueBucket = min(2, last + 1) if last >= 1`, `batteryBand` one band lower if on battery. FRESH and WARM pass the received values; EXPIRED returns null.
     */
    fun fastFields(f: Freshness, d: StateDoc): Fast? = when (f) {
        Freshness.FRESH, Freshness.WARM -> Fast(d.thermalBand, d.queueBucket, d.batteryBand)
        Freshness.EXPIRED -> null
        Freshness.STALE -> Fast(
            thermalBand = if (d.thermalBand >= 1) maxOf(d.thermalBand, 1) else d.thermalBand,
            queueBucket = if (d.queueBucket >= 1) minOf(2, d.queueBucket + 1) else d.queueBucket,
            batteryBand = if (d.powerSource == "battery") d.batteryBand?.lower() else d.batteryBand,
        )
    }

    /** EXPIRED makes the candidate probe-only: fast-field filters are skipped and the `INFER_OFFER` itself is the probe. */
    fun probeOnly(f: Freshness): Boolean = f == Freshness.EXPIRED
}
