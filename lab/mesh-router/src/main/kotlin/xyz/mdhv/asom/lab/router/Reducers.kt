package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.Staleness
import xyz.mdhv.asom.lab.policy.StalenessInput
import xyz.mdhv.asom.lab.policy.StateDoc

/** The `st` digest that rides on `HELLO_ACK`, `INFER_ACCEPT`, `INFER_DECLINE` and `INFER_END`: `{seq, fsm, tb, gov, qb}`. */
data class StDigestDoc(val seq: Long, val fsm: Fsm, val tb: Int, val gov: Governor, val qb: Int)

object StDigestParser {
    /** Null when the digest is malformed (it is then ignored, never stored); unknown members are ignored. */
    fun parse(bytes: ByteArray): StDigestDoc? {
        val o = (StrictJson.parse(bytes) as? ParseResult.Ok)?.value as? JObject ?: return null
        fun int(k: String): Long? = (o[k] as? JInt)?.value
        fun str(k: String): String? = (o[k] as? JString)?.value
        val seq = int("seq") ?: return null
        val tb = int("tb") ?: return null
        val qb = int("qb") ?: return null
        val fsm = Fsm.entries.firstOrNull { it.name == str("fsm") } ?: return null
        val gov = Governor.entries.firstOrNull { it.name == str("gov") } ?: return null
        if (seq < 1 || tb !in 0..2 || qb !in 0..2) return null
        return StDigestDoc(seq, fsm, tb.toInt(), gov, qb.toInt())
    }
}

/** What the requester holds for one peer's live state (LAB_SPEC 6.5). Every field changes only through [LiveStateCache]. */
data class PeerStateCache(
    val doc: StateDoc? = null,
    val rxMonoMs: Long? = null,
    val highSeq: Long? = null,
    val regressed: Boolean = false,
    val goawaySinceState: Boolean = false,
    val sessionOpen: Boolean = false,
    val fullRxMonoMs: Long? = null,
    val fullSampledAgeMs: Long = 0,
    val fullSeq: Long? = null,
    val goawaySinceFullState: Boolean = false,
)

/**
 * The live-state reducer (`LiveStateCache.onState` / `onPiggyback`). Classification itself is `:mesh-policy`'s [Staleness]; this object only keeps
 * the requester's own record of what arrived when, on the requester's monotonic clock. A repeat of the highest `seq` seen is ignored (it is not fresher
 * and not a reset, ERRATA ERR-LP-4); a lower `seq` is stored but marks the state regressed (EXPIRED) until a state at or above the highest arrives.
 * `seq` is per (sender, session), so a new session forgets the highest `seq`.
 *
 * ERR-FX-RT-5: a GOAWAY and a regressed `seq` outlive a re-dial (the state they expired is still the state held) and are cleared only by a state or digest
 * that arrives afterwards. ERR-FX-RT-6: a digest carries only `{seq, fsm, tb, gov, qb}`, so the other fields (power, held, backend) keep the receive time
 * of the last FULL state ([powerFreshness]); an equal-`seq` full state that follows a digest of that `seq` refreshes exactly those fields.
 */
object LiveStateCache {
    fun onSessionOpen(c: PeerStateCache): PeerStateCache = c.copy(sessionOpen = true, highSeq = null)

    fun onSessionClose(c: PeerStateCache): PeerStateCache = c.copy(sessionOpen = false)

    fun onGoaway(c: PeerStateCache): PeerStateCache = c.copy(goawaySinceState = true, goawaySinceFullState = true)

    fun onState(c: PeerStateCache, doc: StateDoc, rxMonoMs: Long): PeerStateCache {
        val high = c.highSeq
        if (high != null && doc.seq == high) {
            val held = c.doc
            if (held == null || doc.seq <= (c.fullSeq ?: held.seq)) return c
            val merged = doc.copy(seq = held.seq, sampledAgeMs = held.sampledAgeMs, fsm = held.fsm, thermalBand = held.thermalBand, governor = held.governor, queueBucket = held.queueBucket)
            return c.copy(doc = merged, fullRxMonoMs = rxMonoMs, fullSampledAgeMs = doc.sampledAgeMs, fullSeq = doc.seq, goawaySinceFullState = false)
        }
        return c.copy(
            doc = doc, rxMonoMs = rxMonoMs, highSeq = if (high == null) doc.seq else maxOf(high, doc.seq),
            regressed = high != null && doc.seq < high, goawaySinceState = false,
            fullRxMonoMs = rxMonoMs, fullSampledAgeMs = doc.sampledAgeMs, fullSeq = doc.seq, goawaySinceFullState = false,
        )
    }

    /** A digest updates the fast fields of the state already held; without a base state it is ignored. */
    fun onPiggyback(c: PeerStateCache, d: StDigestDoc, rxMonoMs: Long): PeerStateCache {
        val base = c.doc ?: return c
        val high = c.highSeq
        if (high != null && d.seq == high) return c
        val doc = base.copy(seq = d.seq, sampledAgeMs = 0, fsm = d.fsm, thermalBand = d.tb, governor = d.gov, queueBucket = d.qb)
        return c.copy(
            doc = doc, rxMonoMs = rxMonoMs, highSeq = if (high == null) d.seq else maxOf(high, d.seq),
            regressed = high != null && d.seq < high, goawaySinceState = false,
        )
    }

    /** The class of the fields a digest does not carry, aged from the last full STATE (a cache built without one falls back to [freshness]). */
    fun powerFreshness(c: PeerStateCache, nowMonoMs: Long): Freshness {
        val doc = c.doc
        val rx = c.fullRxMonoMs ?: return freshness(c, nowMonoMs)
        if (doc == null || c.regressed) return Freshness.EXPIRED
        return Staleness.classify(StalenessInput(nowMonoMs, rx, c.fullSampledAgeMs, !c.sessionOpen, doc.seq, null, c.goawaySinceFullState))
    }

    fun freshness(c: PeerStateCache, nowMonoMs: Long): Freshness = classify(c.doc, c.rxMonoMs, c.sessionOpen, c.goawaySinceState, c.regressed, nowMonoMs)

    fun classify(doc: StateDoc?, rxMonoMs: Long?, sessionOpen: Boolean, goaway: Boolean, regressed: Boolean, nowMonoMs: Long): Freshness {
        if (doc == null || rxMonoMs == null || regressed) return Freshness.EXPIRED
        return Staleness.classify(StalenessInput(nowMonoMs, rxMonoMs, doc.sampledAgeMs, !sessionOpen, doc.seq, null, goaway))
    }
}

/** Integer EWMA `e <- (7e + x) / 8` (router.md 3.3, the `benchmark.md` 10.2 rule); the first sample is the value. */
object Ewma {
    fun update(prev: Long?, x: Long): Long = if (prev == null) x else Sat.floorDiv(Sat.add(Sat.mul(7, prev), x), 8)
}

object LinkReducer {
    /** Only transfers of at least 256 KiB say anything about bandwidth (router.md 3.3). */
    const val MIN_TRANSFER_BYTES = 262_144L

    fun onRtt(l: LinkStats, rttMs: Long): LinkStats = l.copy(rttMs = if (l.samples == 0) rttMs else Ewma.update(l.rttMs, rttMs), samples = l.samples + 1)

    /** `kbps` 0 means unmeasured (the path default is used). Bits per millisecond are kbit/s. */
    fun onTransfer(l: LinkStats, bytes: Long, ms: Long): LinkStats {
        if (bytes < MIN_TRANSFER_BYTES || ms <= 0) return l
        val x = Sat.floorDiv(Sat.mul(bytes, 8), ms)
        return l.copy(kbps = if (l.kbps <= 0) x else Ewma.update(l.kbps, x))
    }
}

object AppOutputEwma {
    fun onCompletion(m: Map<String, Int>, app: String, completionTokens: Int): Map<String, Int> {
        val x = maxOf(1, completionTokens).toLong()
        return m + (app to Ewma.update(m[app]?.toLong(), x).toInt())
    }
}

object CapReducer {
    /** Committed only when the plan executes (router.md 5.5): the caller applies the delta of the plan it actually started. */
    fun commit(caps: Map<ClaimKey, CapCounter>, d: CapDelta): Map<ClaimKey, CapCounter> {
        var out = caps
        for (e in d.entries) {
            val c = out[e.key] ?: CapCounter()
            out = out + (e.key to CapCounter(c.wouldWin + e.wouldWinInc, c.won + e.wonInc))
        }
        return out
    }
}

data class BreakerState(val consecutiveFailures: Int = 0, val coolingUntilMs: Long = 0)

data class BreakerCurve(val baseMs: Long = 30_000, val capMs: Long = 900_000) {
    companion object {
        /** The v1 `CooldownRegistry` curve: 30 s doubling to 15 min. */
        val PROVIDER = BreakerCurve(30_000, 900_000)

        /** Peer transport failures cap at 30 s, then a half-open offer-only probe (LAB_SPEC 6.4 `breaker`). */
        val PEER_TRANSPORT = BreakerCurve(30_000, 30_000)
    }
}

/** A pure breaker whose behaviour is pinned to the real `CooldownRegistry` (R06): the failure streak survives the deadline and a success resets it. */
object PureBreaker {
    fun recordFailure(s: BreakerState, nowMs: Long, curve: BreakerCurve = BreakerCurve.PROVIDER): BreakerState {
        val failures = s.consecutiveFailures + 1
        val backoff = if (failures - 1 >= 30) curve.capMs else minOf(curve.capMs, curve.baseMs shl (failures - 1))
        return BreakerState(failures, nowMs + backoff)
    }

    fun recordSuccess(): BreakerState = BreakerState()

    fun isCooling(s: BreakerState, nowMs: Long): Boolean = s.coolingUntilMs > nowMs

    fun coolingUntil(s: BreakerState, nowMs: Long): Long? = s.coolingUntilMs.takeIf { it > nowMs }

    /** After the deadline a peer that failed comes back, but only for an offer-only probe until a success closes the breaker. */
    fun halfOpen(s: BreakerState, nowMs: Long): Boolean = s.consecutiveFailures > 0 && s.coolingUntilMs <= nowMs
}

object DeclineBackoff {
    const val MIN_MS = 5_000L
    const val MAX_MS = 600_000L

    /** `INFER_DECLINE.retryAfterMs`, clamped to the range the spec allows; the requester never trusts a longer stay-away than 10 minutes. */
    fun until(nowMs: Long, retryAfterMs: Long): Long = Sat.add(nowMs, retryAfterMs.coerceIn(MIN_MS, MAX_MS))
}
