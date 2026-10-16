package xyz.mdhv.asom.lab.manifest

import java.time.Instant
import java.time.ZoneOffset
import xyz.mdhv.asom.lab.bench.ja
import xyz.mdhv.asom.lab.bench.jb
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.bench.jiOrNull
import xyz.mdhv.asom.lab.bench.jo
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs

/** Engine commits and harness versions that a release ships; anything else is emitted as `custom` (design 5.9). */
class ReleaseAllowList(val engineCommits: Set<String> = emptySet(), val harnessVersions: Set<String> = emptySet())

/** The catalogue-backed coarse vendor and model lists; a name that is not on them is emitted as `other` (design 5.9). */
class CoarseDevices(val vendors: Set<String> = emptySet(), val models: Set<String> = emptySet())

/**
 * The public, anonymised derivative (`manifest.md` 12 as amended by design 5.9): an unsigned allow-list projection of a VERIFIED
 * payload. It carries no key, id, seq, challenge, evidence, exact timestamp, OS patch level, platform id, OS build or GPU driver, is
 * quantised to 2 significant digits, and dates are month-granular. Results whose file is not in the held catalogue are dropped; so are
 * results without a sustained block (the public schema cannot carry them). Returns null when nothing is left to send.
 */
object PublicDerivative {
    val RAM_CLASSES_GIB: List<Long> = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 2048)
    const val CUSTOM = "custom"
    const val OTHER = "other"

    /** Two significant digits, half up; values below 100 are unchanged. Law: q2(q2(x)) == q2(x). */
    fun q2(x: Long): Long {
        if (x < 100) return x
        var p = 1L
        var t = x
        while (t >= 100) {
            t /= 10
            p *= 10
        }
        return (x + p / 2) / p * p
    }

    fun ramClassGib(bytes: Long): Long {
        val gib = 1L shl 30
        for (c in RAM_CLASSES_GIB) if (bytes <= c * gib) return c
        return RAM_CLASSES_GIB.last()
    }

    fun osMajor(version: String): Long = Regex("^[0-9]+").find(version)?.value?.toLongOrNull()?.coerceAtMost(1000) ?: 0L

    private fun month(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    fun from(
        verified: ManifestObj,
        catalogueSha256: Set<String>,
        allow: ReleaseAllowList = ReleaseAllowList(),
        coarse: CoarseDevices = CoarseDevices(),
        selected: Set<Int>? = null,
    ): JObject? {
        val b = verified.body
        val d = b.device
        val rows = b.results.withIndex().filter { (i, r) -> (selected == null || i in selected) && r.fileSha256 in catalogueSha256 && r.sustained != null }.map { (_, r) ->
            val su = r.sustained!!
            val c = su.curve
            val n = c.size
            val idx = if (n > 12) (0 until 12).map { k -> (k * (n - 1) + 5) / 11 }.distinct().sorted() else (0 until n).toList()
            val pf = r.prefill[0]
            val dc = r.decode[0]
            jo(
                "modelId" to js(r.modelId), "quant" to js(r.quant), "fileSha256" to js(r.fileSha256), "backend" to js(r.backend),
                "measuredMonth" to js(month(r.measuredAtMs)), "runsCompleted" to ji(r.runs.completed), "charging" to jb(r.conditions.charging),
                "prefillPromptTokens" to ji(pf.promptTokens), "prefillMilliTokPerSec" to ji(q2(pf.milliTokPerSec.p50)),
                "ttftMillis" to ji(q2((pf.ttftMicros.p50 + 500) / 1000)), "decodeContextTokens" to ji(dc.contextTokens),
                "decodeMilliTokPerSec" to ji(q2(dc.milliTokPerSec.p50)), "steadyMilliTokPerSec" to ji(q2(su.steadyMilliTokPerSec)),
                "throttleOnsetSec" to jiOrNull(su.throttleOnsetMs?.let { q2((it + 500) / 1000) }),
                "curve" to ja(idx.map { j -> ja(ji(q2((c[j].tMs + 500) / 1000)), ji(q2(c[j].milliTokPerSec))) }),
                "peakProcessMB" to ji(q2((r.memory.peakProcessBytes + 500_000) / 1_000_000)), "powerMethod" to js(r.power.method),
                "powerMilliW" to jiOrNull(r.power.avgMilliW?.let { q2(it) }),
            )
        }
        if (rows.isEmpty()) return null
        val h = b.producer.harness
        val e = b.producer.engine
        return jo(
            "schema" to js("asom.bench-public/1"),
            "device" to jo(
                "class" to js(d.cls), "vendor" to js(if (d.vendor in coarse.vendors) d.vendor else OTHER), "model" to js(if (d.model in coarse.models) d.model else OTHER),
                "socName" to js(d.socName), "ramClassGiB" to ji(ramClassGib(d.memoryTotalBytes)), "osFamily" to js(d.os.family),
                "osMajor" to ji(osMajor(d.os.version)), "cooling" to js(d.cooling),
            ),
            "harness" to jo(
                "version" to js(if (h.version in allow.harnessVersions) h.version else CUSTOM),
                "methodologyId" to js(h.methodologyId),
                "confVersion" to js(if (h.confVersion in allow.harnessVersions) h.confVersion else CUSTOM),
            ),
            "engine" to jo("name" to js(e.name), "commit" to js(if (e.commit in allow.engineCommits) e.commit else CUSTOM)),
            "results" to ja(rows),
        )
    }

    fun jcs(v: JValue): ByteArray = Jcs.serialize(v)

    /** Member names and value shapes that must never appear anywhere in the output (law LM-2). */
    val FORBIDDEN_NAMES: List<String> = listOf(
        "nodeId", "spki", "seq", "challenge", "issuedAtMs", "expiresAtMs", "measuredAtMs", "platformIds", "securityPatch", "evidence", "keyStorage",
        "audience", "signer", "keyid", "field", "custom", "osBuild", "gpuDriver", "fingerprint", "settings", "threads", "gpuLayers", "batchTokens",
    )
}
