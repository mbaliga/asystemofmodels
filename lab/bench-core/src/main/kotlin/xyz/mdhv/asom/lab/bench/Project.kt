package xyz.mdhv.asom.lab.bench

import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JValue

enum class Audience(val wire: String) {
    OWN("own"),
    FILE("file"),
}

/** One day in milliseconds; every FILE timestamp is a multiple of it (LAB_SPEC 4.1 P3, P8). */
const val DAY_MS: Long = 86_400_000L

/**
 * `project(derive(bench))`: the ONLY path from benchmark numbers to a manifest's `results` (benchmark.md 13.3, rule B2). Step 15a
 * of the verifier compares the JCS bytes of this list with the signed `results`.
 */
object Project {
    private val THERMAL_NAME = listOf("nominal", "light", "moderate", "severe", "critical")
    private val BATTERY_FORMS = setOf("phone", "tablet", "handheld", "laptop")
    const val BATCH_TOKENS: Long = 512L

    /**
     * Tiers that lack a prefill point (`pp@d0` with a value and a TTFT) or a decode point (`tg` with a value) cannot be projected and
     * are left out, and so is a tier in which any test has no value (fewer than 2 kept reps): a row must not be signed with that test
     * silently missing from its `low-runs` and `confidence-<class>` (ERRATA ERR-FX2-4).
     */
    fun projectable(t: TierDerived): Boolean =
        t.results.all { it.value != null } &&
            t.results.any { it.spec.isPrefill && it.spec.depth == 0L && it.ttftMicros != null } &&
            t.results.any { !it.spec.isPrefill }

    fun results(d: Derived, audience: Audience): List<JValue> {
        val doc = d.doc
        val run = doc.run
        val eng = doc.harness.engine
        val charging = run.powerSource == "ac" && doc.device.form in BATTERY_FORMS
        val contention = maxOf(run.contentionBeforePermille, run.contentionAfterPermille)
        return d.tiers.filter { projectable(it) }.map { t ->
            val prefill = mutableListOf<JValue>()
            val decode = mutableListOf<JValue>()
            var worst: Pair<Int, Int>? = null
            val flags = sortedSetOf<String>()
            val confs = mutableListOf<Confidence>()
            for (r in t.results) {
                val value = r.value ?: continue
                val pct = jo(
                    "p10" to ji(Stats.nearestRank(r.keptRates, 100)), "p50" to ji(value), "p90" to ji(Stats.nearestRank(r.keptRates, 900)),
                )
                if (r.spec.isPrefill && r.spec.depth == 0L) {
                    val kt = r.keptTtft ?: continue
                    prefill += jo(
                        "promptTokens" to ji(r.spec.tokens), "milliTokPerSec" to pct,
                        "ttftMicros" to jo("p10" to ji(Stats.nearestRank(kt, 100)), "p50" to ji(r.ttftMicros!!), "p90" to ji(Stats.nearestRank(kt, 900))),
                    )
                } else if (!r.spec.isPrefill) {
                    decode += jo("contextTokens" to ji(r.spec.depth), "genTokens" to ji(r.spec.tokens), "milliTokPerSec" to pct)
                }
                val discarded = r.samples.size - r.stat.kept
                if (worst == null || discarded > worst.second) worst = r.samples.size to discarded
                if (r.stat.kept < 4) flags += "low-runs"
                if ("UNSTABLE" in r.stat.flags) flags += "unstable"
                if (r.stat.drift) flags += "thermal-drift"
                confs += r.confidence
            }
            var sustained: JValue = JNull
            val su = d.sustain
            if (su != null && su.doc.tier == t.tier) {
                confs += su.confidence
                if (su.doc.windows.size >= 2) {
                    sustained = jo(
                        "durationMs" to ji(su.durationMs), "intervalMs" to ji(su.doc.windowMs), "steadyMilliTokPerSec" to ji(su.plateauMtps),
                        "throttleOnsetMs" to jiOrNull(su.onsetMs),
                        "curve" to ja(su.doc.windows.mapIndexed { i, w -> ja(ji(w.tStartMs), ji(su.windowRates[i]), JNull, JNull) }),
                    )
                }
                if (su.onsetMs != null) flags += "thermal-throttled"
            }
            if (charging) flags += "charging"
            if (contention > 50) flags += "background-load"
            if (t.doc.startThermal != "cool") flags += "warm-start"
            if (t.doc.restarts > 0) flags += "restarted"
            if ("SWAPPED" in t.flags) flags += "swapped"
            if (t.numerics.verdict != "pass") flags += "numerics-" + t.numerics.verdict
            flags += "confidence-" + Confidence.minOf(confs).wire
            val conditions = mutableListOf<Pair<String, JValue>>("charging" to jb(charging))
            if (audience == Audience.OWN) conditions += "batteryStartPermille" to jiOrNull(run.batteryStartPermille)
            conditions += "thermalStart" to js(THERMAL_NAME[t.doc.startThermalCode])
            if (audience == Audience.OWN) {
                conditions += "socStartMilliC" to JNull
                conditions += "screenOn" to jbOrNull(run.screenOn)
            }
            jo(
                "modelId" to js(t.pin.modelId), "fileSha256" to js(t.pin.sha256!!), "fileBytes" to ji(t.pin.bytes!!), "quant" to js(t.pin.quant),
                "backend" to js(eng.backend),
                "settings" to jo("threads" to ji(eng.threads), "gpuLayers" to ji(eng.gpuLayers), "ctxTokens" to ji(t.doc.nCtx), "batchTokens" to ji(BATCH_TOKENS)),
                "measuredAtMs" to ji(t.doc.startedAtMs),
                "runs" to jo("planned" to ji(worst!!.first), "completed" to ji(worst.first), "discarded" to ji(worst.second)),
                "conditions" to jo(conditions),
                "prefill" to ja(prefill), "decode" to ja(decode), "sustained" to sustained,
                "memory" to jo(
                    "availableBeforeLoadBytes" to ji(t.doc.availBeforeLoadBytes), "peakProcessBytes" to ji(t.doc.peakFootprintBytes),
                    "kvCacheBytes" to ji(Checked.u53(Checked.mul(t.doc.kvBytesPerToken, t.doc.nCtx))),
                ),
                "power" to jo("method" to js("unavailable"), "avgMilliW" to JNull),
                "flags" to jsList(flags.toList()),
            )
        }
    }

    /**
     * The FILE-audience bench projection (ERRATA ERR-CLOSURE-1). An exported file must carry no stable device identifier and no
     * fine-grained timing: timestamps go to the day; the battery level, screen state, OS build and GPU driver are dropped. The
     * FILE `results` are then `project(derive(projectBenchFile(bench)), FILE)`.
     */
    fun projectBenchFile(doc: BenchDoc): BenchDoc = doc.copy(
        device = doc.device.copy(osBuild = null, gpuDriver = null),
        run = doc.run.copy(
            startedAtMs = doc.run.startedAtMs / DAY_MS * DAY_MS, endedAtMs = doc.run.endedAtMs / DAY_MS * DAY_MS,
            batteryStartPermille = null, screenOn = null,
        ),
        tiers = doc.tiers.map { it.copy(startedAtMs = it.startedAtMs / DAY_MS * DAY_MS) },
    )

    /** True when every day-granular field of a FILE bench is a multiple of [DAY_MS] and the dropped fields are null. */
    fun isFileForm(doc: BenchDoc): Boolean =
        doc.device.osBuild == null && doc.device.gpuDriver == null && doc.run.batteryStartPermille == null && doc.run.screenOn == null &&
            doc.run.startedAtMs % DAY_MS == 0L && doc.run.endedAtMs % DAY_MS == 0L && doc.tiers.all { it.startedAtMs % DAY_MS == 0L }
}
