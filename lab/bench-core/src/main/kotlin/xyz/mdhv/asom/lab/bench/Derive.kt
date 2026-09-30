package xyz.mdhv.asom.lab.bench

/** The M04 result of one test. [value] is null when the result is insufficient. */
data class TestDerived(
    val spec: TestSpec,
    val samples: List<Long>,
    val stat: Stat,
    val confidence: Confidence,
    /** Kept rates in rep order (the values the percentiles are taken over). */
    val keptRates: List<Long>,
    val ttftSamples: List<Long>?,
    val ttftMicros: Long?,
    val keptTtft: List<Long>?,
) {
    val value: Long? get() = stat.value
    val name: String get() = spec.name
}

data class NumericsDerived(val milliNatsPerToken: Long, val refMilliNatsPerToken: Long?, val deviationPermille: Long?, val verdict: String)

data class TierDerived(
    val doc: BTier,
    val pin: TierPin,
    val results: List<TestDerived>,
    val numerics: NumericsDerived,
    val loadWarmMicros: Long,
    val flags: List<String> = emptyList(),
) {
    val tier: String get() = doc.tier
    fun test(name: String): TestDerived? = results.firstOrNull { it.name == name }
    val numericsFail: Boolean get() = numerics.verdict == "fail"
}

data class SustainDerived(
    val doc: BSustain,
    val windowRates: List<Long>,
    val peakMtps: Long,
    val plateauMtps: Long,
    val onsetMs: Long?,
    val stabilityPermille: Long,
    val durationMs: Long,
    val thermalCodeAtOnset: Int?,
    val headroomAtOnsetPermille: Long?,
    val confidence: Confidence,
    val flags: List<String>,
)

data class MaxHold(val weightBytes: Long, val kvRatioPermille: Long, val approxParamsQ4: Long, val largestLoadedTier: String)

/** `basis` is measured, estimated, cannot-hold or not-measured. [verdict] is comfortable, usable, too-slow, cannot-hold or not-measured. */
data class Q7b(val basis: String, val decodeMtps: Long?, val ttft512Micros: Long?, val verdict: String)

data class Answer2000(val tier: String, val promptTokens: Long, val genTokens: Long, val depthRatioPermille: Long, val micros: Long, val thermalModel: Boolean)

data class Throttle(val tier: String, val onsetMs: Long?, val stabilityPermille: Long, val testedMs: Long)

data class Role(val code: String, val t2PlateauMtps: Long?, val t3PlateauMtps: Long?)

/** The five answers of benchmark.md 12.4. Never signed and never stored: recomputed from the document by every viewer (design 6.5 B3). */
data class Answers(
    val usableMemoryBytes: Long,
    val safetyPermille: Long,
    val maxHold: MaxHold?,
    val q7b: Q7b,
    val answer2000: Answer2000?,
    val throttle: Throttle?,
    val role: Role,
    val overallConfidence: Confidence,
)

data class Derived(val doc: BenchDoc, val tiers: List<TierDerived>, val sustain: SustainDerived?, val answers: Answers) {
    fun tier(id: String): TierDerived? = tiers.firstOrNull { it.tier == id }
}

/** The pure window arithmetic of benchmark.md 6.3, shared by `derive` and the executor's end-condition check. */
class SustainShape(val rates: List<Long>, val smoothed: List<Long>, val peakIdx: Int, val peak: Long, val onsetIdx: Int?)

object SustainMath {
    fun analyze(w: List<BWindow>): SustainShape {
        val n = w.size
        val rates = w.map { Checked.rate(it.tokens, it.micros) }
        val sm = (0 until n).map { i -> Stats.lowerMedian(rates.subList(maxOf(0, i - 1), minOf(n, i + 2))) }
        val early = (0 until n).filter { w[it].tStartMs < Derive.PEAK_WINDOW_MS }
        val peakIdx = early.maxWithOrNull(compareBy<Int> { sm[it] }.thenByDescending { it })!!
        val peak = sm[peakIdx]
        var onsetIdx: Int? = null
        for (i in peakIdx + 1 until n - 2) {
            if ((i..i + 2).all { Checked.mul(sm[it], 1000L) < Checked.mul(Derive.ONSET_PERMILLE, peak) }) {
                onsetIdx = i
                break
            }
        }
        return SustainShape(rates, sm, peakIdx, peak, onsetIdx)
    }
}

/** M04: `derive(bench)`. Floor division, conservative medians, MAD outliers, confidence classes and caps, all in checked integer arithmetic. */
object Derive {
    const val SWAP_LIMIT_BYTES: Long = 268_435_456L
    const val KV_CTX: Long = 4096L
    const val OVERHEAD_BYTES: Long = 314_572_800L
    const val Q4_MILLIBYTES_PER_PARAM: Long = 610L
    const val Q_PROMPT: Long = 512L
    const val Q_GEN: Long = 2000L
    const val NUMERICS_PASS_PERMILLE: Long = 20L
    const val NUMERICS_WARN_PERMILLE: Long = 50L
    const val PEAK_WINDOW_MS: Long = 120_000L
    const val SETTLE_MS: Long = 60_000L
    const val PLATEAU_WINDOWS: Int = 8
    const val ONSET_PERMILLE: Long = 900L
    val SAFETY_PERMILLE: Map<String, Long> = mapOf("phone" to 750L, "tablet" to 750L, "handheld" to 800L, "laptop" to 850L, "desktop" to 900L, "server" to 900L)

    /** Throws [BenchArithmeticException] on an overflow, which the verifier maps to `INCONSISTENT`. */
    fun derive(doc: BenchDoc, sets: List<BenchSetDef> = BenchSets.defaults()): Derived {
        val set = BenchSets.find(doc.benchSet, sets) ?: error("unknown bench set ${doc.benchSet}")
        val run = doc.run
        val contention = maxOf(run.contentionBeforePermille, run.contentionAfterPermille)
        val tiers = doc.tiers.map { t ->
            val pin = set.pin(t.tier)!!
            val swapped = (t.swapDeltaBytes ?: 0L) > SWAP_LIMIT_BYTES
            val tests = t.tests.map { deriveTest(it, doc, t, contention, swapped) }
            TierDerived(t, pin, tests, numerics(t.numerics), Stats.upperMedian(t.loadWarmMicros), if (swapped) listOf("SWAPPED") else emptyList())
        }
        val sustain = doc.sustain?.let { deriveSustain(it, doc) }
        return Derived(doc, tiers, sustain, answers(doc, set, tiers, sustain))
    }

    /** The conditions that cap a test's confidence (benchmark.md 9.4). */
    data class TestContext(
        val warmStart: Boolean = false,
        val virtualized: Boolean = false,
        val streamTiming: Boolean = false,
        val contentionPermille: Long = 0L,
        val restarted: Boolean = false,
        val swapped: Boolean = false,
    )

    private fun deriveTest(t: BTest, doc: BenchDoc, tier: BTier, contention: Long, swapped: Boolean): TestDerived = deriveTest(
        t.spec, t.samples, t.wholeSamples,
        TestContext(tier.startThermal != "cool", doc.device.virtualized, doc.harness.timingSource == "stream", contention, tier.restarts > 0, swapped),
    )

    /** One test: the rates, the conservative median, the outlier rule, the drift rule and the confidence class. */
    fun deriveTest(spec: TestSpec, samples: List<Long>, whole: List<Long>?, ctx: TestContext): TestDerived {
        val rates = samples.map { Checked.rate(spec.tokens, it) }
        val st = Stats.stat(rates)
        val base = Stats.confidence(st, ctx.contentionPermille, capStart = ctx.warmStart || ctx.restarted, capVirtual = ctx.virtualized, capStream = ctx.streamTiming)
        val conf = if (ctx.swapped) Confidence.min(base, Confidence.LOW) else base
        val keptRates = st.keptIdx.map { rates[it] }
        var ttft: Long? = null
        var keptTtft: List<Long>? = null
        if (whole != null && st.value != null) {
            keptTtft = st.keptIdx.map { whole[it] }
            ttft = Stats.upperMedian(keptTtft)
        }
        return TestDerived(spec, samples, st, conf, keptRates, whole, ttft, keptTtft)
    }

    /** The sustained phase of a bare window list (used by the standalone M04 sustain vectors). */
    fun deriveSustainStandalone(s: BSustain, startThermal: String): SustainDerived = deriveSustainCore(s, startThermal)

    private fun numerics(n: BNumerics): NumericsDerived {
        val ref = n.refMilliNatsPerToken ?: return NumericsDerived(n.milliNatsPerToken, null, null, "not-run")
        val dev = Checked.permille(Checked.absDiff(n.milliNatsPerToken, ref), ref)
        val verdict = if (dev <= NUMERICS_PASS_PERMILLE) "pass" else if (dev <= NUMERICS_WARN_PERMILLE) "warn" else "fail"
        return NumericsDerived(n.milliNatsPerToken, ref, dev, verdict)
    }

    private fun deriveSustain(s: BSustain, doc: BenchDoc): SustainDerived = deriveSustainCore(s, doc.run.startThermal)

    private fun deriveSustainCore(s: BSustain, startThermal: String): SustainDerived {
        val w = s.windows
        val n = w.size
        val shape = SustainMath.analyze(w)
        val rates = shape.rates
        val sm = shape.smoothed
        val peak = shape.peak
        val onsetIdx = shape.onsetIdx
        val flags = mutableListOf<String>()
        val tail: List<Long>
        val onsetMs: Long?
        if (onsetIdx == null) {
            tail = sm.takeLast(PLATEAU_WINDOWS)
            onsetMs = null
        } else {
            onsetMs = w[onsetIdx].tStartMs
            val settled = (0 until n).filter { w[it].tStartMs >= Checked.add(onsetMs, SETTLE_MS) }.map { sm[it] }
            if (settled.isNotEmpty()) {
                tail = settled.takeLast(PLATEAU_WINDOWS)
            } else {
                tail = sm.subList(onsetIdx, n)
                flags += "PLATEAU_NOT_REACHED"
            }
        }
        val plateau = Stats.lowerMedian(tail)
        val duration = Checked.add(w.last().tStartMs, s.windowMs)
        val stability = Checked.permille(plateau, peak)
        var conf = when {
            startThermal != "cool" -> Confidence.LOW
            s.endReason == "PLATEAU" || (s.endReason == "TIME_CAP" && duration >= 480_000L) -> Confidence.HIGH
            duration >= 300_000L -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        if (s.endReason == "THERMAL_HARD" || s.endReason == "BATTERY_TEMP") {
            flags += "HARD_CEILING"
            conf = Confidence.min(conf, Confidence.MEDIUM)
        }
        return SustainDerived(
            s, rates, peak, plateau, onsetMs, stability, duration, onsetIdx?.let { w[it].maxThermalCode },
            if (onsetIdx != null) s.headroomAtOnsetPermille else null, conf, flags,
        )
    }

    private fun fits(pin: TierPin, usable: Long): Boolean =
        Checked.add(Checked.add(pin.bytes!!, Checked.mul(pin.kvBytesPerToken!!, KV_CTX)), OVERHEAD_BYTES) <= usable

    private fun answers(doc: BenchDoc, set: BenchSetDef, tiers: List<TierDerived>, sustain: SustainDerived?): Answers {
        val form = doc.device.form
        val safety = SAFETY_PERMILLE.getValue(form)
        val limit = listOfNotNull(doc.memory.availAtStartBytes, doc.memory.processLimitBytes, doc.memory.gpuWorkingSetBytes).min()
        val usable = Checked.div(Checked.mul(limit, safety), 1000L)
        val okTiers = tiers.filter { !it.numericsFail }
        val by = tiers.associateBy { it.tier }

        val loaded = okTiers.maxByOrNull { TIER_ORDER.indexOf(it.tier) }
        val maxHold = loaded?.let { ld ->
            val kvRatio = Checked.div(Checked.mul(Checked.mul(ld.doc.kvBytesPerToken, KV_CTX), 1000L), ld.pin.bytes!!)
            val hold = Checked.div(Checked.mul(maxOf(Checked.sub(usable, OVERHEAD_BYTES), 0L), 1000L), Checked.add(1000L, kvRatio))
            MaxHold(hold, kvRatio, Checked.div(Checked.mul(hold, 1000L), Q4_MILLIBYTES_PER_PARAM), ld.tier)
        }

        val q7b = q7b(set, by, okTiers, usable)
        val h = okTiers.filter { it.value("tg128@d0") != null && it.ttft512() != null }.maxByOrNull { TIER_ORDER.indexOf(it.tier) }
        val a2000 = h?.let { answer2000(it, sustain) }
        val throttle = sustain?.let { Throttle(it.doc.tier, it.onsetMs, it.stabilityPermille, it.durationMs) }

        val stab = sustain?.stabilityPermille
        fun plateauOf(t: String): Long? {
            val td = by[t] ?: return null
            if (sustain != null && sustain.doc.tier == t) return sustain.plateauMtps
            val d0 = td.value("tg128@d0") ?: return null
            return if (stab == null) null else Checked.div(Checked.mul(d0, stab), 1000L)
        }
        val p3 = plateauOf("T3")
        val p2 = plateauOf("T2")
        val plat = doc.device.platform
        val code = when {
            plat == "ios" || plat == "ipados" -> "requester-foreground-helper"
            (form == "desktop" || form == "server") && p3 != null && p3 >= Editorial.ROLE_STRONG_MTPS -> "strong-provider"
            form in setOf("desktop", "server", "laptop", "handheld") && p2 != null && p2 >= Editorial.ROLE_STRONG_MTPS -> "small-model-provider"
            (form == "phone" || form == "tablet") && p2 != null && p2 >= Editorial.ROLE_HELPER_MTPS -> "occasional-helper"
            else -> "requester"
        }
        val confs = mutableListOf<Confidence>()
        if (h != null) confs += h.results.map { it.confidence } else if (loaded != null) confs += loaded.results.map { it.confidence }
        if (sustain != null) confs += sustain.confidence
        return Answers(usable, safety, maxHold, q7b, a2000, throttle, Role(code, p2, p3), Confidence.minOf(confs))
    }

    private fun TierDerived.value(test: String): Long? = test(test)?.value

    private fun TierDerived.ttft512(): Long? = test("pp512@d0")?.ttftMicros

    private fun q7b(set: BenchSetDef, by: Map<String, TierDerived>, ok: List<TierDerived>, usable: Long): Q7b {
        val t3 = by["T3"]
        val t3pin = set.pin("T3")!!
        val dec3 = t3?.value("tg128@d0")
        val ttft3 = t3?.ttft512()
        if (t3 != null && !t3.numericsFail && dec3 != null && ttft3 != null) return verdict("measured", dec3, ttft3)
        val t2 = by["T2"]
        val t2pin = set.pin("T2")!!
        val dec2 = t2?.value("tg128@d0")
        val ttft2 = t2?.ttft512()
        if (fits(t3pin, usable)) {
            if (t2 != null && !t2.numericsFail && dec2 != null && ttft2 != null) {
                val dec = Checked.div(Checked.mul(dec2, t2pin.bytes!!), t3pin.bytes!!)
                val ttft = Checked.div(Checked.mul(ttft2, t3pin.bytes), t2pin.bytes)
                return verdict("estimated", dec, ttft)
            }
            return Q7b("not-measured", null, null, "not-measured")
        }
        return Q7b("cannot-hold", null, null, "cannot-hold")
    }

    private fun verdict(basis: String, dec: Long, ttft: Long): Q7b {
        val v = when {
            dec >= Editorial.COMFORT_DECODE_MTPS && ttft <= Editorial.COMFORT_TTFT_US -> "comfortable"
            dec >= Editorial.USABLE_DECODE_MTPS && ttft <= Editorial.USABLE_TTFT_US -> "usable"
            else -> "too-slow"
        }
        return Q7b(basis, dec, ttft, v)
    }

    private fun answer2000(h: TierDerived, sustain: SustainDerived?): Answer2000 {
        val d0 = h.value("tg128@d0")!!
        val d2k = h.value("tg128@d2048")
        val depthRatio = if (d2k != null) Checked.permille(d2k, d0) else 1000L
        val ttft = h.ttft512()!!
        val effPeak: Long
        val effPlat: Long
        val onsetUs: Long?
        if (sustain != null && sustain.doc.tier == h.tier) {
            effPeak = Checked.div(Checked.mul(sustain.peakMtps, depthRatio), 1000L)
            effPlat = Checked.div(Checked.mul(sustain.plateauMtps, depthRatio), 1000L)
            onsetUs = sustain.onsetMs?.let { Checked.mul(it, 1000L) }
        } else {
            effPeak = Checked.div(Checked.mul(d0, depthRatio), 1000L)
            effPlat = effPeak
            onsetUs = null
        }
        val gen = if (onsetUs == null) {
            Checked.div(Checked.mul(Q_GEN, Checked.NANO), effPeak)
        } else {
            val byOnset = Checked.div(Checked.mul(effPeak, onsetUs), Checked.NANO)
            if (byOnset >= Q_GEN) Checked.div(Checked.mul(Q_GEN, Checked.NANO), effPeak)
            else Checked.add(onsetUs, Checked.div(Checked.mul(Checked.sub(Q_GEN, byOnset), Checked.NANO), effPlat))
        }
        return Answer2000(h.tier, Q_PROMPT, Q_GEN, depthRatio, Checked.add(ttft, gen), onsetUs != null)
    }
}
