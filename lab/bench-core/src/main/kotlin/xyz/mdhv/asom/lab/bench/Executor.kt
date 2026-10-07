package xyz.mdhv.asom.lab.bench

/** Opaque stand-ins for the pinned prompt and numerics texts. The lab has no tokenizer and no engine (LAB_SPEC 5: no engine code); the real texts ship with the core (ERRATA ERR-BENCH-8). */
object PinnedTexts {
    fun prompt(tokens: Int): ByteArray = "asom-bench/prompt/$tokens".toByteArray(Charsets.US_ASCII)

    val NUMERICS: ByteArray = "asom-bench/nll1024".toByteArray(Charsets.US_ASCII)
    const val NUMERICS_TOKENS: Int = 1024
}

data class SessionOptions(val optInTiers: List<String> = emptyList(), val allowVirtual: Boolean = false, val sustainedToday: Boolean = false)

sealed interface RunOutcome {
    /** [doc] carries the raw measurements; every statistic and sentence is derived from it later. */
    data class Completed(val doc: BenchDoc) : RunOutcome

    /** A governor abort or a third yield: the finished tests are kept and the document records `run.abort`. */
    data class Aborted(val reason: String, val partial: BenchDoc?) : RunOutcome

    /** The preflight gates failed: nothing ran (state back to IDLE). */
    data class Refused(val reasons: List<PreflightReason>) : RunOutcome
}

/**
 * The run-plan interpreter (benchmark.md 5.3, 5.4): preflight, cool-down, per tier load / warm-up / d0 tests / numerics / depth tests,
 * then the sustained phase, all under the governor. Every duration is the host's monotonic clock around one engine call.
 */
class BenchSession(
    private val host: BenchHost,
    private val plan: RunPlan,
    private val set: BenchSetDef,
    private val consent: ConsentToken,
    private val opts: SessionOptions = SessionOptions(),
) {
    val trace: MutableList<String> = mutableListOf()
    private var startMicros: Long = 0L
    private val gov = Governor(host, plan, trace) { startMicros }
    private val nCtx = 4096

    /** User Stop: the same path as a hard ceiling. */
    fun stop() {
        gov.stopRequested = true
    }

    private class Finish(val reason: String) : RuntimeException(null, null, false, false)

    /** The engine could not load the tier (out of memory): a typed failure, the tier is left out and the run goes on. */
    private class TierSkipped(message: String) : RuntimeException(message, null, false, false)

    private class TierBuilder(val pin: TierPin, val startThermal: String, val startCode: Int, val startedAtMs: Long, val restarts: Int) {
        var availBeforeLoad = 0L
        var peakFootprint = 0L
        var kvBytesPerToken = 0L
        var loadCold = 0L
        var coldness = "unknown"
        var loadWarm: List<Long> = emptyList()
        val tests = mutableListOf<BTest>()
        var numerics: BNumerics? = null
        var swapBefore: Long? = null
        var swapAfter: Long? = null

        fun snapshot(): BTier? {
            val n = numerics ?: return null
            val swap = if (swapBefore != null && swapAfter != null) maxOf(0L, swapAfter!! - swapBefore!!) else null
            return BTier(pin.tier, startThermal, startCode, startedAtMs, 4096, availBeforeLoad, peakFootprint, kvBytesPerToken, loadCold, coldness, loadWarm, tests.toList(), n, restarts, swap)
        }
    }

    private fun now(): Long = host.monotonicMicros()

    fun run(): RunOutcome {
        startMicros = now()
        val startEpoch = host.epochMillis()
        val p = host.probes
        val dev = p.device()
        val harness = host.harness()
        gov.to(GState.PREFLIGHT)
        val spins = (1..5).map { p.contentionSpinMicros() }
        val baseline = spins.min()
        val best = p.bestSpinMicros() ?: baseline
        val contentionBefore = if (baseline > best) Checked.permille(baseline - best, best) else 0L
        trace += "SPIN baseline=$baseline contentionBeforePermille=$contentionBefore"
        val pre = Preflight.check(host, plan, set, opts.optInTiers, opts.allowVirtual, contentionBefore, harness.shell)
        if (!pre.ok) {
            trace += "PREFLIGHT refused ${pre.reasons.joinToString(",")}"
            gov.to(GState.IDLE)
            return RunOutcome.Refused(pre.reasons)
        }
        trace += "PREFLIGHT ok start=${pre.startClass} tiers=${pre.tiers.joinToString(",") { it.tier }}"
        gov.to(GState.AWAIT_CONSENT)
        try {
            consent.consume(plan.plan, host.epochMillis())
        } catch (e: ConsentError) {
            gov.to(GState.IDLE)
            trace += "CONSENT refused ${e.message}"
            throw e
        }
        trace += "CONSENT confirmed"
        gov.to(GState.PREPARING)
        val mem0 = p.memory()
        val power0 = p.power()
        val presence0 = p.presence()
        host.coexistence.armBenchmarking()
        host.progress.acquireWakeLock()
        if (dev.form == "phone" || dev.form == "tablet") host.progress.dimBrightness()

        val tiersDone = mutableListOf<BTier>()
        var sustain: BSustain? = null
        var abort: BAbort? = null
        var current: TierBuilder? = null
        var sustainDraft: BSustain? = null
        try {
            for (pin in pre.tiers) {
                var restarts = 0
                while (true) {
                    try {
                        val builder = tierBuilder(pin, restarts)
                        current = builder
                        runTier(builder, headline = pin == pre.tiers.last(), form = dev.form)
                        tiersDone += builder.snapshot()!!
                        current = null
                        break
                    } catch (y: GovSignal.Yield) {
                        current = null
                        cleanupModel()
                        handleYield()
                        restarts++
                    } catch (e: TierSkipped) {
                        trace += "TIER_SKIPPED ${pin.tier}: ${e.message}"
                        current = null
                        cleanupModel()
                        break
                    }
                }
            }
            val sustainOutcome = runSustain(tiersDone, dev.form)
            sustain = sustainOutcome
            enter(GState.FINALIZING)
        } catch (a: GovSignal.Abort) {
            current?.snapshot()?.let { tiersDone += it }
            abort = BAbort(a.reason, gov.elapsedMs())
            sustainDraft = pendingSustain
            trace += "ABORT ${a.reason} at ${gov.elapsedMs()} ms"
            gov.to(GState.ABORTING)
            cleanup()
            gov.to(GState.FINALIZING)
        } catch (f: Finish) {
            abort = BAbort(f.reason, gov.elapsedMs())
            trace += "FINISH ${f.reason} at ${gov.elapsedMs()} ms"
        }
        cleanup()
        if (sustain == null) sustain = sustainDraft ?: pendingSustain
        val spinAfter = p.contentionSpinMicros()
        val contentionAfter = if (spinAfter > baseline) Checked.permille(spinAfter - baseline, baseline) else 0L
        trace += "SPIN after=$spinAfter contentionAfterPermille=$contentionAfter"
        val endEpoch = host.epochMillis()
        val doc = BenchDoc(
            benchProtocol = 1, benchSet = set.id,
            harness = BHarness(harness.shell, harness.coreImpl, harness.coreVersion, harness.confVersion, harness.timingSource, plan.sha256B64u(), harness.engine),
            device = BDevice(dev.platform, dev.form, dev.maker, dev.model, dev.soc, dev.osVersion, dev.osBuild, dev.gpu, dev.gpuDriver, dev.memTotalBytes, dev.unifiedMemory, dev.virtualized),
            memory = BMemory(mem0.availBytes, mem0.processLimitBytes, mem0.gpuWorkingSetBytes, mem0.limitSource),
            run = BRun(
                plan = plan.plan, optInTiers = opts.optInTiers.filter { it in BenchEnums.tiers }.distinct().sortedBy { TIER_ORDER.indexOf(it) },
                startedAtMs = startEpoch, endedAtMs = maxOf(endEpoch, startEpoch), dayUtc = BenchCodec.dayOf(startEpoch), powerSource = power0.source,
                batteryStartPermille = power0.levelPermille?.toLong(), startThermal = if (pre.startClass == "hot") "warm" else pre.startClass,
                screenOn = presence0.screenOn, contentionBeforePermille = contentionBefore, contentionAfterPermille = contentionAfter, abort = abort,
            ),
            tiers = tiersDone.sortedBy { TIER_ORDER.indexOf(it.tier) }, sustain = sustain,
        )
        gov.to(GState.DONE)
        return if (abort != null) RunOutcome.Aborted(abort.reason, doc) else RunOutcome.Completed(doc)
    }

    private var pendingSustain: BSustain? = null

    private fun enter(next: GState) {
        if (gov.state != next) gov.to(next)
    }

    private fun tierBuilder(pin: TierPin, restarts: Int): TierBuilder {
        cool(pin.tier, plan.coolDown.let { if (plan.isMobile(host.probes.device().form)) it.maxWaitMsMobile else it.maxWaitMsDesktop })
        val t = host.probes.thermal()
        val cls = StartClass.of(t, 0L).let { if (it == "hot") "warm" else it }
        gov.to(GState.RUNNING, "tier ${pin.tier}")
        return TierBuilder(pin, cls, t.code, host.epochMillis(), restarts)
    }

    /** COOLING: poll every `pollMs` until the device is COOL or the maximum wait passes (benchmark.md 6.5). */
    private fun cool(label: String, maxWaitMs: Long) {
        enter(GState.COOLING)
        var waited = 0L
        while (true) {
            val t = host.probes.thermal()
            val cls = StartClass.of(t, 0L)
            if (cls == "cool" || waited >= maxWaitMs) {
                trace += "COOLDOWN $label class=$cls waitedMs=$waited"
                return
            }
            gov.check(Phase.PREFLIGHT)
            if (host.coexistence.isDaemon && host.coexistence.requestPending()) {
                host.coexistence.requestServed()
                trace += "REQUEST served while cooling"
            }
            host.sleepMillis(plan.coolDown.pollMs)
            waited += plan.coolDown.pollMs
        }
    }

    private fun handleYield() {
        gov.to(GState.YIELDED)
        host.coexistence.requestServed()
        trace += "YIELD #${gov.yields} at ${gov.elapsedMs()} ms"
        if (gov.yields >= Governor.YIELD_LIMIT) {
            gov.to(GState.FINALIZING)
            cleanup()
            throw Finish("DEVICE_BUSY")
        }
        host.sleepMillis(30_000L)
        gov.to(GState.COOLING)
    }

    private var loaded: BenchModel? = null
    private var loadedTier: String? = null

    private fun cleanup() {
        val m = loaded
        if (m != null) trace += "UNLOAD $loadedTier"
        loaded = null
        runCatching { m?.cancel() }
        runCatching { m?.close() }
        host.progress.releaseWakeLock()
        host.progress.restoreBrightness()
        host.coexistence.disarm()
    }

    private fun load(pin: TierPin): Pair<BenchModel, Long> {
        val file = host.models.verified(pin) ?: error("model for ${pin.tier} is missing")
        val t = now()
        val m = try {
            host.engine.load(file, nCtx)
        } catch (e: EngineOom) {
            throw TierSkipped("out of memory loading ${pin.tier}")
        }
        return m to (now() - t)
    }

    private fun runTier(b: TierBuilder, headline: Boolean, form: String) {
        val pin = b.pin
        val mem = host.probes.memory()
        b.availBeforeLoad = mem.availBytes
        b.swapBefore = mem.swapUsedBytes
        b.coldness = host.models.evictCache(pin)
        var (model, cold) = load(pin)
        loaded = model
        loadedTier = pin.tier
        b.loadCold = cold
        b.kvBytesPerToken = model.kvBytesPerToken
        val warm = mutableListOf<Long>()
        repeat(3) {
            model.close()
            val (m2, us) = load(pin)
            model = m2
            loaded = m2
            warm += us
        }
        b.loadWarm = warm
        trace += "LOAD ${pin.tier} coldUs=$cold warmUs=${warm.joinToString("/")}"
        repeat(plan.warmupReps) {
            model.prefill(model.tokenize(PinnedTexts.prompt(512)))
            model.clearKv()
            repeat(32) { model.decodeGreedy(intArrayOf(0)) }
            model.clearKv()
        }
        trace += "WARMUP ${pin.tier}"
        b.peakFootprint = maxOf(b.peakFootprint, host.probes.memory().footprintBytes)
        val names = plan.testsFor(headline, form).filter { it != "load" && it != "nll1024" }.map { TestSpec.parse(it) ?: error("plan test $it") }
        for (spec in names.filter { it.depth == 0L }) b.tests += runTest(model, spec, plan.reps, b)
        val toks = model.tokenize(PinnedTexts.NUMERICS)
        gov.check(Phase.TIER)
        val nll = model.nllMicroNats(toks)
        val milli = nll / PinnedTexts.NUMERICS_TOKENS / 1000L
        b.numerics = BNumerics(milli, host.models.numericsReference(host.engine.info().commit, pin))
        trace += "NLL ${pin.tier} milliNatsPerToken=$milli"
        for (spec in names.filter { it.depth > 0L }) {
            model.clearKv()
            model.prefill(IntArray(spec.depth.toInt()))
            b.tests += runTest(model, spec, plan.depthReps, b)
        }
        b.swapAfter = host.probes.memory().swapUsedBytes
        model.close()
        loaded = null
        trace += "UNLOAD ${pin.tier}"
    }

    private fun runTest(model: BenchModel, spec: TestSpec, reps: Int, b: TierBuilder): BTest {
        val samples = mutableListOf<Long>()
        val whole = mutableListOf<Long>()
        val paired = spec.isPrefill && spec.depth == 0L
        require(!spec.isPrefill || spec.depth == 0L) { "prefill at depth is not supported" }
        repeat(reps) {
            gov.check(Phase.TIER)
            if (paired) {
                val w0 = now()
                val toks = model.tokenize(PinnedTexts.prompt(spec.tokens.toInt()))
                val t0 = now()
                model.prefill(toks)
                val t1 = now()
                samples += t1 - t0
                whole += t1 - w0
                model.clearKv()
            } else {
                val t0 = now()
                repeat(spec.tokens.toInt()) { model.decodeGreedy(intArrayOf(0)) }
                val t1 = now()
                samples += t1 - t0
                model.truncateKv(0, spec.depth.toInt())
            }
            host.sleepMillis(plan.interRepMs)
        }
        b.peakFootprint = maxOf(b.peakFootprint, host.probes.memory().footprintBytes)
        trace += "TEST ${b.pin.tier} ${spec.name} reps=$reps"
        return BTest(spec, samples, if (paired) whole else null)
    }

    private fun runSustain(done: List<BTier>, form: String): BSustain? {
        val cfg = plan.sustain ?: return null
        val candidates = done.filter { t ->
            val tg = t.test("tg128@d0") ?: return@filter false
            val v = Stats.stat(tg.samples.map { Checked.rate(128, it) }).value
            val ref = t.numerics.refMilliNatsPerToken
            val fail = ref != null && Checked.permille(Checked.absDiff(t.numerics.milliNatsPerToken, ref), ref) > Derive.NUMERICS_WARN_PERMILLE
            v != null && v >= 2000L && !fail
        }
        val tier = candidates.maxByOrNull { TIER_ORDER.indexOf(it.tier) }
        if (tier == null) {
            trace += "SUSTAIN skipped (no tier reaches 2000 mtps)"
            return null
        }
        val pin = set.pin(tier.tier)!!
        val capMs = if (plan.isMobile(form)) cfg.capMsMobile else cfg.capMsDesktop
        cool("sustain", plan.coolDown.maxWaitMsBeforeSustain)
        gov.to(GState.RUNNING, "sustain ${tier.tier}")
        val (model, _) = load(pin)
        loaded = model
        loadedTier = pin.tier
        trace += "LOAD ${pin.tier} sustain"
        val windows = mutableListOf<BWindow>()
        val headrooms = mutableListOf<Long?>()
        val t0 = now()
        var endReason: String
        var idx = 0
        var base = t0
        val windowMicros = cfg.windowMs * 1000L
        fun draft(reason: String) = BSustain(pin.tier, cfg.windowMs, capMs, reason, onsetHeadroom(windows, headrooms), windows.toList())
        try {
            while (true) {
                val winEnd = base + (idx + 1) * windowMicros
                var tokens = 0L
                var decMicros = 0L
                var maxCode = 0
                var lastPoll = now()
                try {
                    while (now() < winEnd) {
                        model.clearKv()
                        model.prefill(IntArray(cfg.promptTokens.toInt()))
                        var k = 0L
                        while (k < cfg.chunkTokens && now() < winEnd) {
                            if (now() - lastPoll >= 1_000_000L || tokens == 0L) {
                                gov.check(Phase.SUSTAIN)
                                maxCode = maxOf(maxCode, host.probes.thermal().code)
                                lastPoll = now()
                            }
                            val a = now()
                            model.decodeGreedy(intArrayOf(0))
                            decMicros += now() - a
                            tokens++
                            k++
                        }
                    }
                } catch (e: GovSignal) {
                    if (tokens > 0L) {
                        windows += BWindow(idx * cfg.windowMs, tokens, maxOf(decMicros, 1L), maxCode)
                        headrooms += host.probes.thermal().headroom10sPermille?.toLong()
                    }
                    throw e
                }
                if (tokens == 0L) {
                    // The window is dropped (the document needs 1 or more tokens in a window). While there is no window yet the grid restarts here, so the first kept window still starts at t = 0.
                    if (windows.isEmpty()) {
                        base = now()
                        idx = 0
                    } else {
                        idx++
                    }
                    if ((now() - t0) / 1000L >= capMs) {
                        endReason = "TIME_CAP"
                        break
                    }
                    continue
                }
                windows += BWindow(idx * cfg.windowMs, tokens, maxOf(decMicros, 1L), maxCode)
                headrooms += host.probes.thermal().headroom10sPermille?.toLong()
                idx++
                val elapsed = (now() - t0) / 1000L
                if (plateauReached(windows)) {
                    endReason = "PLATEAU"
                    break
                }
                if (elapsed >= capMs) {
                    endReason = "TIME_CAP"
                    break
                }
            }
        } catch (e: GovSignal.SustainEnd) {
            endReason = e.endReason
        } catch (e: GovSignal.Yield) {
            pendingSustain = if (windows.isEmpty()) null else draft("YIELDED")
            cleanupModel(model)
            handleYield()
            trace += "SUSTAIN yielded windows=${windows.size}"
            return pendingSustain
        } catch (e: GovSignal.Abort) {
            pendingSustain = if (windows.isEmpty()) null else draft(if (e.reason in BenchEnums.endReasons) e.reason else "USER_STOP")
            cleanupModel(model)
            throw e
        }
        cleanupModel(model)
        if (windows.isEmpty()) {
            trace += "SUSTAIN skipped (no window produced a token)"
            return null
        }
        trace += "SUSTAIN end reason=$endReason windows=${windows.size}"
        return draft(endReason)
    }

    private fun cleanupModel(m: BenchModel? = loaded) {
        if (m != null && loaded != null) trace += "UNLOAD $loadedTier"
        runCatching { m?.close() }
        loaded = null
    }

    private fun onsetHeadroom(windows: List<BWindow>, headrooms: List<Long?>): Long? {
        if (windows.isEmpty()) return null
        val i = SustainMath.analyze(windows).onsetIdx ?: return null
        return headrooms[i]
    }

    /** benchmark.md 6.4 PLATEAU: an onset exists and the 12 most recent smoothed windows starting at least 60 s after it lie within +-50 permille of their lower median. */
    private fun plateauReached(windows: List<BWindow>): Boolean {
        val shape = SustainMath.analyze(windows)
        val onset = shape.onsetIdx ?: return false
        val onsetMs = windows[onset].tStartMs
        val settled = windows.indices.filter { windows[it].tStartMs >= onsetMs + Derive.SETTLE_MS }.map { shape.smoothed[it] }
        if (settled.size < 12) return false
        val last = settled.takeLast(12)
        val med = Stats.lowerMedian(last)
        return last.all { Checked.mul(Checked.absDiff(it, med), 1000L) <= Checked.mul(50L, med) }
    }
}
