package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/**
 * One attempt as the REQUESTER observed it (LAB_SPEC 6.6). Every field is either read from the requester's own monotonic clock, parsed by the
 * requester from the answer text, or a fact about the requester's own offer. There is no field for a peer-reported token count, timing or state:
 * the type cannot carry one, and a test proves that no peer-supplied number can raise a claim.
 */
data class Observation(
    val key: ClaimKey,
    val terminalDone: Boolean,
    val tBodyMs: Long,
    val tEndMs: Long,
    val outBytes: Long,
    val offerEstTokensIn: Long,
    val offerMaxTokens: Long,
    val netMs: Long,
    val concurrentOnPeer: Boolean,
    val acceptedFileSha256: String?,
    val heldBackend: String?,
    val heldCommit: String?,
    val claimCommit: String?,
)

data class Evaluated(val outTokEst: Long, val predictedMs: Long, val elapsedMs: Long, val ratio: Long, val discard: DiscardReason?)

data class TrackerBook(val states: Map<ClaimKey, TrackerState> = emptyMap(), val penalties: Map<String, PeerPenalty> = emptyMap())

data class TrackedRates(val prefill: Long, val decodeAtP: Long, val steady: Long)

/**
 * The claim tracker (LAB_SPEC 6.6, design 5.7) as pure reducers. Observation only LOWERS a claim: every rate this object returns is at most the claim.
 *
 * R3-OVERCLAIM-1: the padding bound "at most about 2x" of the design is NOT what the mechanism gives. An answer is capped only at
 * `maxTokens x bptCap / 1000` bytes, so filler inflates the apparent ratio by up to `maxTokens x bptCap / trueBytes` (6.8x in bytes and 5.4x in predicted time at the spec's own numbers),
 * limited by [RATIO_CAP]. That is the stated worst case and it is tested (`ClaimTrackerTest.paddingToTheCapInflatesTheRatioByFarMoreThanTwo`); what the tracker still guarantees is that placement
 * never exceeds the claim (`effRatio = min(1000, ...)`).
 */
object ClaimTracker {
    const val WIN = 20
    const val MIN_KEEP = 3
    const val MIN_STATE = 5
    const val CORR = 800L
    const val DISC_THRESHOLD = 600L
    const val RATIO_CAP = 5_000L
    const val SHORT_BYTES = 128L
    const val BUDGET_MIN_OBSERVATIONS = 4
    const val BUDGET_WINDOW = 20
    const val CLAMP_MIN_BYTES = 8L
    const val DISC_DEFAULT = 700L
    const val DISC_PENALTY = 400L
    const val INHERIT_OBSERVATIONS = 10
    const val NEW_CLAIM_INTERVAL_MS = 86_400_000L
    const val PENALTY_BASE_MS = 7 * 86_400_000L

    fun outTokEst(outBytes: Long, bpt: Bpt): Long = Sat.ceilDiv(Sat.mul(outBytes, 1_000), bpt.bptPermille)

    /** Evaluates one observation against the claim row. `predicted = E1(warm) + E5(claim, P) + E7(claim, outTokEst - 1, busy 0, queue 0, hot false)`. */
    fun evaluate(o: Observation, claim: PerfPrior, bpt: Bpt): Evaluated {
        require(claim.prefillMilliTokPerSec > 0 && claim.steadyMilliTokPerSec > 0) { "the claim row has positive rates" }
        val elapsed = maxOf(1L, o.tEndMs - o.tBodyMs)
        val est = outTokEst(o.outBytes, bpt)
        val e5 = Estimator.prefillMs(o.offerEstTokensIn, claim.prefillMilliTokPerSec, claim.ttft0Ms)
        val dec = Estimator.decodeAtCtx(claim.decodeAt, o.offerEstTokensIn)
        require(dec > 0) { "the claimed decode rate is positive" }
        val e7 = Estimator.thermalAwareDecode(est - 1, dec, claim.steadyMilliTokPerSec, claim.throttleOnsetMs, 0, 0, e5, false)
        val predicted = Sat.add(o.netMs, e5, e7)
        val ratio = minOf(RATIO_CAP, Sat.floorDiv(Sat.mul(predicted, 1_000), elapsed))
        val settingsDiffer = (o.acceptedFileSha256 != null && o.acceptedFileSha256 != o.key.fileSha256) ||
            (o.heldBackend != null && o.heldBackend != o.key.backend) ||
            (o.claimCommit != null && o.heldCommit != null && o.heldCommit != o.claimCommit)
        val discard = when {
            !o.terminalDone -> DiscardReason.INCOMPLETE
            o.outBytes < SHORT_BYTES -> DiscardReason.SHORT
            o.outBytes > Sat.floorDiv(Sat.mul(o.offerMaxTokens, bpt.bptCapPermille), 1_000) -> DiscardReason.OVERLONG
            o.concurrentOnPeer -> DiscardReason.CONCURRENT
            settingsDiffer -> DiscardReason.SETTINGS
            else -> null
        }
        return Evaluated(est, predicted, elapsed, ratio, discard)
    }

    private fun sameFile(a: ClaimKey, b: ClaimKey): Boolean = a.nodeId == b.nodeId && a.fileSha256 == b.fileSha256

    private fun badness(s: ClaimState): Int = when (s) {
        ClaimState.DISCREPANT -> 3
        ClaimState.WEAK -> 2
        ClaimState.UNVERIFIED -> 1
        else -> 0
    }

    /**
     * The tracker state of [key], which LAB_SPEC 6.6 keys on `(peerNodeId, fileSha256)`: the backend only picks the claim row. The snapshot's [ClaimKey] still
     * carries the backend, so every entry of the same peer and file is one state here (ERR-FX-RT-1): the worst state among them wins, the memory mark and the
     * strikes of any of them apply, and a peer cannot shed DISCREPANT or an out-of-memory exclusion by reporting another `engine.backend`.
     */
    fun stateAt(tracker: Map<ClaimKey, TrackerState>, key: ClaimKey): TrackerState? {
        val own = tracker[key]
        val siblings = tracker.entries.filter { it.key != key && sameFile(it.key, key) }.sortedBy { it.key.backend }.map { it.value }
        if (siblings.isEmpty()) return own
        val all = listOfNotNull(own) + siblings
        val worst = all.maxByOrNull { badness(stateOf(it)) }!!
        return worst.copy(memoryDiscrepant = all.any { it.memoryDiscrepant }, strikes = all.maxOf { it.strikes })
    }

    /** The cap counters of the same peer and file are one counter, for the same reason as [stateAt]. */
    fun capAt(caps: Map<ClaimKey, CapCounter>, key: ClaimKey): CapCounter {
        var wouldWin = 0
        var won = 0
        for ((k, c) in caps) {
            if (sameFile(k, key)) {
                wouldWin += c.wouldWin
                won += c.won
            }
        }
        return CapCounter(wouldWin, won)
    }

    private fun slotOf(states: Map<ClaimKey, TrackerState>, key: ClaimKey): ClaimKey =
        if (key in states) key else states.keys.filter { sameFile(it, key) }.minByOrNull { it.backend } ?: key

    private fun discrepantFiles(states: Map<ClaimKey, TrackerState>, nodeId: String): Int =
        states.keys.filter { it.nodeId == nodeId }.groupBy { it.fileSha256 }.count { (_, keys) -> stateOf(stateAt(states, keys.first())) == ClaimState.DISCREPANT }

    private fun sorted(ts: TrackerState): List<Long> = ts.ratios.sorted()

    /** `best = x[(3n)/4]`, the upper quartile by nearest rank; null when there is no kept ratio. */
    fun best(ts: TrackerState): Long? = sorted(ts).let { x -> if (x.isEmpty()) null else x[(3 * x.size) / 4] }

    fun budgetTripped(ts: TrackerState): Boolean =
        ts.recent.size >= BUDGET_MIN_OBSERVATIONS && ts.recent.count { !it.kept } * 2 > ts.recent.size

    /** The lowest ratio among the recent observations of at least 8 bytes (discarded ones included), or 0 when none qualifies. */
    fun minRatio(ts: TrackerState): Long = ts.recent.filter { it.outBytes >= CLAMP_MIN_BYTES }.minOfOrNull { it.ratio } ?: 0L

    fun stateOf(ts: TrackerState?): ClaimState {
        if (ts == null) return ClaimState.UNVERIFIED
        if (ts.inheritedDiscrepant) return ClaimState.DISCREPANT
        val n = ts.ratios.size
        val base = if (n < MIN_STATE) {
            ClaimState.UNVERIFIED
        } else {
            val b = best(ts)!!
            when {
                b >= CORR -> ClaimState.CORROBORATED
                b >= DISC_THRESHOLD -> ClaimState.WEAK
                else -> ClaimState.DISCREPANT
            }
        }
        return if (budgetTripped(ts) && base != ClaimState.DISCREPANT) ClaimState.WEAK else base
    }

    /** One rate of the claim after tracking (LAB_SPEC 6.6 "use for placement"). Never above [claimRate]. */
    fun trackedRate(claimRate: Long, ts: TrackerState?, capRefRate: Long?, disc: Long): Long {
        val x = if (ts == null) emptyList() else sorted(ts)
        val n = x.size
        var rate = if (n >= MIN_KEEP) {
            Sat.floorDiv(Sat.mul(claimRate, minOf(1_000L, x[(n - 1) / 2])), 1_000)
        } else {
            val prior = Sat.floorDiv(Sat.mul(minOf(claimRate, capRefRate ?: claimRate), disc), 1_000)
            val kept = x.fold(0L) { acc, xi -> Sat.add(acc, Sat.floorDiv(Sat.mul(claimRate, minOf(1_000L, xi)), 1_000)) }
            Sat.floorDiv(Sat.add(Sat.mul(2, prior), kept), (2 + n).toLong())
        }
        if (ts != null && budgetTripped(ts)) rate = minOf(rate, Sat.floorDiv(Sat.mul(claimRate, minRatio(ts)), 1_000))
        return minOf(rate, claimRate)
    }

    /** `capRef = min(signedReferenceP90 x 12/10 [only when signed], classCeiling)`; null (no term) when neither is present. */
    fun capRef(signed: RateCeiling?, ceiling: RateCeiling?): RateCeiling? {
        val s = signed?.let {
            RateCeiling(
                Sat.floorDiv(Sat.mul(it.prefillMilliTokPerSec, 12), 10), Sat.floorDiv(Sat.mul(it.decodeMilliTokPerSec, 12), 10),
                Sat.floorDiv(Sat.mul(it.steadyMilliTokPerSec, 12), 10),
            )
        }
        if (s == null) return ceiling
        if (ceiling == null) return s
        return RateCeiling(
            minOf(s.prefillMilliTokPerSec, ceiling.prefillMilliTokPerSec), minOf(s.decodeMilliTokPerSec, ceiling.decodeMilliTokPerSec),
            minOf(s.steadyMilliTokPerSec, ceiling.steadyMilliTokPerSec),
        )
    }

    fun tracked(claim: PerfPrior, contextTokens: Long, ts: TrackerState?, ceiling: RateCeiling?, disc: Long): TrackedRates {
        val claimDecode = Estimator.decodeAtCtx(claim.decodeAt, contextTokens)
        return TrackedRates(
            prefill = trackedRate(claim.prefillMilliTokPerSec, ts, ceiling?.prefillMilliTokPerSec, disc),
            decodeAtP = trackedRate(claimDecode, ts, ceiling?.decodeMilliTokPerSec, disc),
            steady = trackedRate(claim.steadyMilliTokPerSec, ts, ceiling?.steadyMilliTokPerSec, disc),
        )
    }

    /** `disc = 700`, or 400 while a penalty is running or while at least two keys of this peer are DISCREPANT. */
    fun disc(nodeId: String, tracker: Map<ClaimKey, TrackerState>, penalties: Map<String, PeerPenalty>, wallNowMs: Long): Long {
        if ((penalties[nodeId]?.untilWallMs ?: 0L) > wallNowMs) return DISC_PENALTY
        return if (discrepantFiles(tracker, nodeId) >= 2) DISC_PENALTY else DISC_DEFAULT
    }

    private fun stateFor(book: TrackerBook, key: ClaimKey): TrackerState = stateAt(book.states, key) ?: TrackerState()

    private fun relatch(book: TrackerBook, nodeId: String, wallNowMs: Long): TrackerBook {
        val active = (book.penalties[nodeId]?.untilWallMs ?: 0L) > wallNowMs
        if (active) return book
        if (discrepantFiles(book.states, nodeId) < 2) return book
        val repeats = book.penalties[nodeId]?.repeats ?: 0
        val duration = Sat.mul(PENALTY_BASE_MS, 1L shl minOf(repeats, 20))
        return book.copy(penalties = book.penalties + (nodeId to PeerPenalty(Sat.add(wallNowMs, duration), repeats + 1)))
    }

    /** Clears an inherited DISCREPANT once ten new kept observations have `best >= CORR`. */
    private fun settleInheritance(ts: TrackerState): TrackerState {
        if (!ts.inheritedDiscrepant) return ts
        val b = best(ts)
        return if (ts.ratios.size >= INHERIT_OBSERVATIONS && b != null && b >= CORR) ts.copy(inheritedDiscrepant = false) else ts
    }

    fun onObservation(book: TrackerBook, o: Observation, claim: PerfPrior, bpt: Bpt, wallNowMs: Long): TrackerBook {
        val ev = evaluate(o, claim, bpt)
        val prev = stateFor(book, o.key)
        val kept = ev.discard == null
        var next = prev.copy(
            ratios = if (kept) (prev.ratios + ev.ratio).takeLast(WIN) else prev.ratios,
            recent = (prev.recent + ObsRecord(kept, ev.ratio, o.outBytes, ev.discard)).takeLast(BUDGET_WINDOW),
            strikes = prev.strikes + (if (ev.discard == DiscardReason.OVERLONG) 1 else 0),
        )
        next = settleInheritance(next)
        return relatch(book.copy(states = book.states + (slotOf(book.states, o.key) to next)), o.key.nodeId, wallNowMs)
    }

    /** A declined offer that reflects on the claim (`MODEL_NOT_OFFERED`, router.md 8.2 row 3): a candidate observation that is discarded. */
    fun onFailedAttempt(book: TrackerBook, key: ClaimKey, wallNowMs: Long): TrackerBook {
        val prev = stateFor(book, key)
        val next = prev.copy(recent = (prev.recent + ObsRecord(false, 0, 0, DiscardReason.INCOMPLETE)).takeLast(BUDGET_WINDOW))
        return relatch(book.copy(states = book.states + (slotOf(book.states, key) to next)), key.nodeId, wallNowMs)
    }

    /** A lender's `terminal = oom` marks the memory claim DISCREPANT (router.md 8.2 row 8); F8 then excludes the file on that peer. */
    fun onMemoryOom(book: TrackerBook, key: ClaimKey): TrackerBook = book.copy(states = book.states + (slotOf(book.states, key) to stateFor(book, key).copy(memoryDiscrepant = true)))

    /**
     * A peer's new claim body is accepted for routing at most once per 24 h (M08-022) and only with a higher `seq`. Acceptance restarts the window W
     * against the new claim, keeps the strikes and the discard record (a tripped discard budget is not a window, ERR-FX-RT-3), and inherits DISCREPANT until
     * ten new observations have `best >= CORR`.
     * A new claim body also clears the memory mark (router.md 8.2 row 8 via manifest.md 11.5: "until a manifest with a higher seq").
     */
    fun onClaimBody(book: TrackerBook, key: ClaimKey, claimSeq: Long, wallNowMs: Long): Pair<TrackerBook, Boolean> {
        val prev = stateFor(book, key)
        val old = prev.claimSeq
        if (old != null && claimSeq <= old) return book to false
        val at = prev.acceptedAtWallMs
        if (at != null && wallNowMs - at < NEW_CLAIM_INTERVAL_MS) return book to false
        val wasBad = old != null && (stateOf(prev) == ClaimState.DISCREPANT)
        val next = prev.copy(
            claimSeq = claimSeq, acceptedAtWallMs = wallNowMs, ratios = emptyList(),
            inheritedDiscrepant = wasBad || prev.inheritedDiscrepant, memoryDiscrepant = false,
        )
        return book.copy(states = book.states + (slotOf(book.states, key) to next)) to true
    }
}

/** Parses the answer text the requester itself receives: `choices[0].delta.content` (stream) or `choices[0].message.content` (whole body). */
class AnswerCollector {
    private val pending = java.io.ByteArrayOutputStream()
    var outBytes: Long = 0
        private set
    var events: Int = 0
        private set

    /** Bytes of an SSE stream in any chunking; a partial line waits for its newline. */
    fun feed(bytes: ByteArray) {
        for (b in bytes) {
            if (b == '\n'.code.toByte()) {
                line(pending.toByteArray())
                pending.reset()
            } else {
                pending.write(b.toInt())
            }
        }
    }

    fun finishStream() {
        if (pending.size() > 0) line(pending.toByteArray())
        pending.reset()
    }

    private fun line(raw: ByteArray) {
        val text = String(raw, Charsets.UTF_8).trimEnd('\r')
        if (!text.startsWith("data:")) return
        val payload = text.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return
        outBytes = Sat.add(outBytes, contentBytes(payload.toByteArray(Charsets.UTF_8), "delta"))
        events++
    }

    /** A non-streamed answer body. */
    fun wholeBody(body: ByteArray) {
        outBytes = Sat.add(outBytes, contentBytes(body, "message"))
    }

    private fun contentBytes(json: ByteArray, member: String): Long {
        val root = (StrictJson.parse(json) as? ParseResult.Ok)?.value as? JObject ?: return 0
        val choice = ((root["choices"] as? JArray)?.items?.firstOrNull() as? JObject) ?: return 0
        val inner = choice[member] as? JObject ?: return 0
        val content = (inner["content"] as? JString)?.value ?: return 0
        return content.toByteArray(Charsets.UTF_8).size.toLong()
    }
}

/**
 * Collects the requester-side facts of one attempt. `INFER_HEAD` is deliberately not read for anything (M08-014), and only `terminal` is read from
 * `INFER_END` (M08-013): every other member is ignored and never stored.
 */
class AttemptObserver {
    private val answer = AnswerCollector()
    var tBodyMs: Long? = null
        private set
    private var lastChunkMs: Long? = null
    private var endMs: Long? = null
    var terminal: String? = null
        private set

    fun onBodySent(tMs: Long) {
        tBodyMs = tMs
    }

    @Suppress("UNUSED_PARAMETER")
    fun onHead(tMs: Long) {
    }

    fun onChunk(tMs: Long, bytes: ByteArray) {
        answer.feed(bytes)
        lastChunkMs = tMs
    }

    fun onEnd(tMs: Long, payload: ByteArray) {
        endMs = tMs
        terminal = ((StrictJson.parse(payload) as? ParseResult.Ok)?.value as? JObject)?.get("terminal")?.let { (it as? JString)?.value }
    }

    /** The later of `INFER_END` received and the last content chunk received; null before anything ended. */
    fun tEndMs(): Long? = listOfNotNull(endMs, lastChunkMs).maxOrNull()

    fun outBytes(): Long {
        answer.finishStream()
        return answer.outBytes
    }
}
