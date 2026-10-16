package xyz.mdhv.asom.lab.manifest

import xyz.mdhv.asom.lab.bench.Audience
import xyz.mdhv.asom.lab.bench.BenchCodec
import xyz.mdhv.asom.lab.bench.BenchDoc
import xyz.mdhv.asom.lab.bench.BenchSetDef
import xyz.mdhv.asom.lab.bench.BenchSets
import xyz.mdhv.asom.lab.bench.DAY_MS
import xyz.mdhv.asom.lab.bench.Project
import xyz.mdhv.asom.lab.bench.Rd
import xyz.mdhv.asom.lab.bench.RdContext
import xyz.mdhv.asom.lab.bench.SchemaViolation
import xyz.mdhv.asom.lab.bench.StrRule
import xyz.mdhv.asom.lab.bench.findForbiddenMember
import xyz.mdhv.asom.lab.bench.ja
import xyz.mdhv.asom.lab.bench.jb
import xyz.mdhv.asom.lab.bench.ji
import xyz.mdhv.asom.lab.bench.jiOrNull
import xyz.mdhv.asom.lab.bench.jo
import xyz.mdhv.asom.lab.bench.js
import xyz.mdhv.asom.lab.bench.jsList
import xyz.mdhv.asom.lab.bench.scope
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JValue

data class Subject(val nodeId: String, val keyAlg: String, val keyStorage: String)

data class ProducerHarness(val id: String, val version: String, val methodologyId: String, val confVersion: String)

data class ProducerEngine(val name: String, val commit: String, val buildFlags: List<String>)

data class Producer(val app: String, val appVersion: String, val harness: ProducerHarness, val engine: ProducerEngine)

data class PlatformIds(val brand: String?, val device: String?, val manufacturer: String?, val model: String?, val product: String?)

data class Os(val family: String, val version: String, val securityPatch: String?)

data class Cluster(val cores: Long, val maxKHz: Long)

data class Accelerator(val kind: String, val vendor: String, val name: String, val apis: List<String>, val dedicatedBytes: Long?)

data class Device(
    val cls: String,
    val vendor: String,
    val model: String,
    val platformIds: PlatformIds?,
    val os: Os,
    val socVendor: String,
    val socName: String,
    val logicalCores: Long,
    val clusters: List<Cluster>,
    val memoryTotalBytes: Long,
    val accelerators: List<Accelerator>,
    val battery: Boolean,
    val batteryDesignMilliWh: Long?,
    val batteryDesignPresent: Boolean,
    val cooling: String,
    val stateSource: String,
)

data class Pct(val p10: Long?, val p50: Long, val p90: Long?)

data class Settings(val threads: Long, val gpuLayers: Long, val ctxTokens: Long, val batchTokens: Long)

data class Runs(val planned: Long, val completed: Long, val discarded: Long)

data class Conditions(val charging: Boolean, val batteryStartPermille: Long?, val thermalStart: String, val socStartMilliC: Long?, val screenOn: Boolean?)

data class PrefillPoint(val promptTokens: Long, val milliTokPerSec: Pct, val ttftMicros: Pct)

data class DecodePoint(val contextTokens: Long, val genTokens: Long, val milliTokPerSec: Pct)

data class CurvePoint(val tMs: Long, val milliTokPerSec: Long, val socMilliC: Long?, val powerMilliW: Long?)

data class Sustained(val durationMs: Long, val intervalMs: Long, val steadyMilliTokPerSec: Long, val throttleOnsetMs: Long?, val curve: List<CurvePoint>)

data class MemoryRow(val availableBeforeLoadBytes: Long, val peakProcessBytes: Long, val kvCacheBytes: Long?)

data class PowerRow(val method: String, val avgMilliW: Long?)

data class ResultRow(
    val modelId: String,
    val fileSha256: String,
    val fileBytes: Long,
    val quant: String,
    val backend: String,
    val settings: Settings,
    val measuredAtMs: Long,
    val runs: Runs,
    val conditions: Conditions,
    val prefill: List<PrefillPoint>,
    val decode: List<DecodePoint>,
    val sustained: Sustained?,
    val memory: MemoryRow,
    val power: PowerRow,
    val flags: List<String>,
)

data class Presentation(val issuedAtMs: Long, val expiresAtMs: Long?, val challenge: String?, val challengeBytes: ByteArray?)

data class Body(
    val audience: Audience,
    val seq: Long?,
    val subject: Subject,
    val producer: Producer,
    val device: Device,
    val bench: BenchDoc,
    val results: List<ResultRow>,
    /** The `results` array exactly as parsed from the verified bytes; step 15a compares its JCS bytes. */
    val resultsJson: JValue,
    val bodyJson: JValue,
)

data class ManifestObj(val schemaMinor: Int, val body: Body, val presentation: Presentation, val unknownFields: Int)

object ManifestDecoder {
    /** The highest `schemaMinor` this decoder knows (LAB_SPEC 4.1 P10). Unknown members are tolerated only above it. */
    const val KNOWN_MINOR = 0
    const val BYTES_MAX = 1L shl 50
    const val RATE_MAX = 1_000_000_000L
    const val TTFT_MAX = 3_600_000_000L
    const val EPOCH_LO = 1_577_836_800_000L
    const val EPOCH_HI = 4_102_444_800_000L

    private val osFamilies = setOf("android", "ios", "ipados", "macos", "linux", "windows", "ubuntu-touch")
    private val keyStorages = setOf("strongbox", "tee", "secure-enclave", "tpm", "os-keystore", "file", "unknown", "ephemeral")
    private val powerMethods = setOf("battery-current", "battery-level", "rapl", "pmic", "unavailable")

    /** Decodes and validates `asom.manifest/1` (typed, hand written; LAB_SPEC 4.6 step 11). Throws [SchemaViolation]. */
    fun decode(o: JValue, sets: List<BenchSetDef> = BenchSets.defaults()): ManifestObj {
        val root = o as? JObject ?: throw SchemaViolation("$", "not an object")
        val minor = (root["schemaMinor"] as? xyz.mdhv.asom.lab.json.JInt)?.value ?: throw SchemaViolation("$.schemaMinor", "missing or not an integer")
        if (minor !in 0..1000) throw SchemaViolation("$.schemaMinor", "outside 0..1000")
        val ctx = RdContext(tolerateUnknown = minor > KNOWN_MINOR)
        val bodyValue = root["body"] ?: throw SchemaViolation("$.body", "missing")
        findForbiddenMember(bodyValue, "$.body")?.let { throw SchemaViolation(it, "member forbidden inside a signed body") }
        val top = Rd.root(o, "$", ctx)
        var body: Body? = null
        var pres: Presentation? = null
        top.scope {
            if (str("schema", StrRule.ANY_SHORT) != "asom.manifest/1") throw SchemaViolation("$.schema", "not asom.manifest/1")
            int("schemaMinor", 0, 1000)
            body = decodeBody(obj("body"), bodyValue, ctx, sets)
            pres = decodePresentation(this, body!!.audience, ctx)
        }
        return ManifestObj(minor.toInt(), body!!, pres!!, ctx.unknown)
    }

    private fun decodePresentation(top: Rd, audience: Audience, ctx: RdContext): Presentation {
        if (audience == Audience.FILE) {
            // a FILE presentation is exactly { issuedAtMs }: no expiry, no challenge, whatever the minor (P3)
            val p = Rd.root(top.raw("presentation"), "$.presentation", RdContext(false))
            return p.scope {
                val t = int("issuedAtMs", EPOCH_LO, EPOCH_HI - 1)
                if (t % DAY_MS != 0L) throw SchemaViolation("$.presentation.issuedAtMs", "not a multiple of 86,400,000")
                Presentation(t, null, null, null)
            }
        }
        return top.obj("presentation").scope {
            val issued = int("issuedAtMs", EPOCH_LO, EPOCH_HI - 1)
            val expires = int("expiresAtMs", EPOCH_LO, EPOCH_HI - 1)
            val challenge = strOrNull("challenge", StrRule.B64U32)
            val bytes = challenge?.let {
                (xyz.mdhv.asom.lab.json.Base64Strict.decodeUrlNoPad(it) as? xyz.mdhv.asom.lab.json.B64Result.Ok)?.bytes?.takeIf { b -> b.size == 32 }
                    ?: throw SchemaViolation("$path.challenge", "not the base64url of 32 bytes")
            }
            Presentation(issued, expires, challenge, bytes)
        }
    }

    private fun decodeBody(r: Rd, bodyValue: JValue, ctx: RdContext, sets: List<BenchSetDef>): Body = r.scope {
        val audience = Audience.entries.first { it.wire == enum("audience", setOf("own", "file")) }
        val file = audience == Audience.FILE
        val seq = if (file) {
            if (has("seq")) throw SchemaViolation("$path.seq", "forbidden for the file audience")
            null
        } else {
            int("seq", 1, xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE)
        }
        val subject = obj("subject").scope {
            Subject(str("nodeId", StrRule.B64U32), enum("keyAlg", setOf("ES256")), enum("keyStorage", keyStorages))
        }
        if (file && subject.keyStorage != "ephemeral") throw SchemaViolation("$path.subject.keyStorage", "a file is signed by a per-export key: keyStorage must be ephemeral")
        val producer = decodeProducer(obj("producer"))
        val device = decodeDevice(obj("device"), file)
        val benchRd = (bodyValue as JObject)["bench"] ?: throw SchemaViolation("$path.bench", "missing")
        raw("bench")
        val bench = BenchCodec.decode(benchRd, ctx, sets, "$path.bench")
        if (file && !Project.isFileForm(bench)) throw SchemaViolation("$path.bench", "not in the file form (day-granular times; no OS build, GPU driver, battery level or screen state)")
        val resultsValue = raw("results")
        val rows = arrObjs("results", 0, 64).map { decodeResult(it, file) }
        Body(audience, seq, subject, producer, device, bench, rows, resultsValue, bodyValue)
    }

    private fun decodeProducer(r: Rd): Producer = r.scope {
        Producer(
            app = str("app", StrRule.ID), appVersion = str("appVersion", StrRule.SEMVER),
            harness = obj("harness").scope {
                ProducerHarness(str("id", StrRule.ID), str("version", StrRule.SEMVER), str("methodologyId", StrRule.METHOD_ID), str("confVersion", StrRule.SEMVER))
            },
            engine = obj("engine").scope {
                ProducerEngine(str("name", StrRule.ID), str("commit", StrRule.COMMIT), arrStrs("buildFlags", 0, 16, StrRule.TEXT, unique = true))
            },
        )
    }

    private fun decodeDevice(r: Rd, file: Boolean): Device = r.scope {
        val cls = str("class", StrRule.ID)
        val vendor = str("vendor", StrRule.TEXT)
        val model = str("model", StrRule.TEXT)
        if (file && has("platformIds")) throw SchemaViolation("$path.platformIds", "forbidden for the file audience")
        val platformIds = optObj("platformIds")?.scope {
            PlatformIds(
                optStr("brand", StrRule.TEXT), optStr("device", StrRule.TEXT), optStr("manufacturer", StrRule.TEXT), optStr("model", StrRule.TEXT),
                optStr("product", StrRule.TEXT),
            )
        }
        val os = obj("os").scope {
            val fam = enum("family", osFamilies)
            val ver = str("version", StrRule.TEXT)
            if (file && has("securityPatch")) throw SchemaViolation("$path.securityPatch", "forbidden for the file audience")
            val patch = optStr("securityPatch", StrRule.DATE)?.also { if (!xyz.mdhv.asom.lab.bench.Rules.isRealDate(it)) throw SchemaViolation("$path.securityPatch", "not a calendar date") }
            Os(fam, ver, patch)
        }
        var socVendor = ""
        var socName = ""
        var cores = 0L
        var clusters = emptyList<Cluster>()
        obj("soc").scope {
            socVendor = str("vendor", StrRule.TEXT)
            socName = str("name", StrRule.TEXT)
            obj("cpu").scope {
                cores = int("logicalCores", 1, 1024)
                clusters = arrObjs("clusters", 0, 8).map { c -> c.scope { Cluster(int("cores", 1, 1024), int("maxKHz", 0, Long.MAX_VALUE.coerceAtMost(xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE))) } }
            }
        }
        val mem = obj("memory").scope { int("totalBytes", 0, BYTES_MAX) }
        val acc = arrObjs("accelerators", 0, 8).map { a ->
            a.scope {
                Accelerator(str("kind", StrRule.ID), str("vendor", StrRule.TEXT), str("name", StrRule.TEXT), arrStrs("apis", 0, 8, StrRule.ID, unique = true), intOrNull("dedicatedBytes", 0, BYTES_MAX))
            }
        }
        var battery = false
        var design: Long? = null
        var designPresent = false
        obj("power").scope {
            battery = bool("battery")
            designPresent = has("batteryDesignMilliWh")
            design = if (designPresent) intOrNull("batteryDesignMilliWh", 0, BYTES_MAX) else null
        }
        var cooling = ""
        var stateSource = ""
        obj("thermal").scope {
            cooling = str("cooling", StrRule.ID)
            stateSource = str("stateSource", StrRule.ID)
        }
        Device(cls, vendor, model, platformIds, os, socVendor, socName, cores, clusters, mem, acc, battery, design, designPresent, cooling, stateSource)
    }

    private fun rate(r: Rd, name: String, lo: Long = 1): Long = r.int(name, lo, RATE_MAX)

    private fun pct(r: Rd, name: String, max: Long, minValue: Long = 1): Pct = r.obj(name).scope {
        val p10 = optInt("p10", minValue, max)
        val p50 = int("p50", minValue, max)
        val p90 = optInt("p90", minValue, max)
        Pct(p10, p50, p90)
    }

    private fun decodeResult(r: Rd, file: Boolean): ResultRow = r.scope {
        val settings = obj("settings").scope { Settings(int("threads", 0, 1024), int("gpuLayers", 0, 100_000), int("ctxTokens", 1, 10_000_000), int("batchTokens", 1, 1_000_000)) }
        val measured = int("measuredAtMs", EPOCH_LO, EPOCH_HI - 1)
        if (file && measured % DAY_MS != 0L) throw SchemaViolation("$path.measuredAtMs", "not a multiple of 86,400,000 (file audience)")
        val runs = obj("runs").scope { Runs(int("planned", 1, 1000), int("completed", 0, 1000), int("discarded", 0, 1000)) }
        val cond = obj("conditions").scope {
            val charging = bool("charging")
            if (file) {
                for (forbidden in listOf("batteryStartPermille", "screenOn", "socStartMilliC")) {
                    if (has(forbidden)) throw SchemaViolation("$path.$forbidden", "forbidden for the file audience")
                }
                Conditions(charging, null, str("thermalStart", StrRule.ID), null, null)
            } else {
                Conditions(charging, intOrNull("batteryStartPermille", 0, 1000), str("thermalStart", StrRule.ID), intOrNull("socStartMilliC", -xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE, xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE), boolOrNull("screenOn"))
            }
        }
        val prefill = arrObjs("prefill", 1, 4).map { p ->
            p.scope { PrefillPoint(int("promptTokens", 1, 10_000_000), pct(this, "milliTokPerSec", RATE_MAX), pct(this, "ttftMicros", TTFT_MAX)) }
        }
        val decode = arrObjs("decode", 1, 4).map { p ->
            p.scope { DecodePoint(int("contextTokens", 0, 10_000_000), int("genTokens", 1, 1_000_000), pct(this, "milliTokPerSec", RATE_MAX)) }
        }
        val sustained = objOrNull("sustained")?.scope {
            val duration = int("durationMs", 1000, 86_400_000)
            val interval = int("intervalMs", 100, 3_600_000)
            val steady = rate(this, "steadyMilliTokPerSec")
            val onset = intOrNull("throttleOnsetMs", 0, xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE)
            val curve = arrRaw("curve", 2, 240).mapIndexed { i, v ->
                val t = (v as? JArray)?.items ?: throw SchemaViolation("$path.curve[$i]", "not an array")
                if (t.size != 4) throw SchemaViolation("$path.curve[$i]", "expected 4 members")
                fun nn(k: Int, lo: Long, hi: Long, nullable: Boolean): Long? = when (val x = t[k]) {
                    is xyz.mdhv.asom.lab.json.JInt -> x.value.also { if (it < lo || it > hi) throw SchemaViolation("$path.curve[$i][$k]", "out of range") }
                    is xyz.mdhv.asom.lab.json.JNull -> if (nullable) null else throw SchemaViolation("$path.curve[$i][$k]", "null")
                    else -> throw SchemaViolation("$path.curve[$i][$k]", "not an integer")
                }
                CurvePoint(nn(0, 0, xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE, false)!!, nn(1, 1, RATE_MAX, false)!!, nn(2, -xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE, xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE, true), nn(3, 0, xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE, true))
            }
            Sustained(duration, interval, steady, onset, curve)
        }
        val mem = obj("memory").scope { MemoryRow(int("availableBeforeLoadBytes", 0, BYTES_MAX), int("peakProcessBytes", 0, BYTES_MAX), if (has("kvCacheBytes")) intOrNull("kvCacheBytes", 0, BYTES_MAX) else null) }
        val power = obj("power").scope { PowerRow(enum("method", powerMethods), intOrNull("avgMilliW", 0, xyz.mdhv.asom.lab.bench.Checked.MAX_SAFE)) }
        ResultRow(
            modelId = str("modelId", StrRule.ID), fileSha256 = str("fileSha256", StrRule.SHA256_HEX), fileBytes = int("fileBytes", 0, BYTES_MAX),
            quant = str("quant", StrRule.QUANT), backend = str("backend", StrRule.ID), settings = settings, measuredAtMs = measured, runs = runs, conditions = cond,
            prefill = prefill, decode = decode, sustained = sustained, memory = mem, power = power, flags = arrStrs("flags", 0, 16, StrRule.ID, unique = true),
        )
    }

    // ------------------------------------------------------------------------------------------ encoders (producer side)

    fun producerJson(p: Producer): JValue = jo(
        "app" to js(p.app), "appVersion" to js(p.appVersion),
        "harness" to jo("id" to js(p.harness.id), "version" to js(p.harness.version), "methodologyId" to js(p.harness.methodologyId), "confVersion" to js(p.harness.confVersion)),
        "engine" to jo("name" to js(p.engine.name), "commit" to js(p.engine.commit), "buildFlags" to jsList(p.engine.buildFlags)),
    )

    fun deviceJson(d: Device): JValue {
        val m = mutableListOf<Pair<String, JValue>>(
            "class" to js(d.cls), "vendor" to js(d.vendor), "model" to js(d.model),
            "os" to jo(buildList {
                add("family" to js(d.os.family))
                add("version" to js(d.os.version))
                d.os.securityPatch?.let { add("securityPatch" to js(it)) }
            }),
            "soc" to jo(
                "vendor" to js(d.socVendor), "name" to js(d.socName),
                "cpu" to jo("logicalCores" to ji(d.logicalCores), "clusters" to ja(d.clusters.map { jo("cores" to ji(it.cores), "maxKHz" to ji(it.maxKHz)) })),
            ),
            "memory" to jo("totalBytes" to ji(d.memoryTotalBytes)),
            "accelerators" to ja(d.accelerators.map { jo("kind" to js(it.kind), "vendor" to js(it.vendor), "name" to js(it.name), "apis" to jsList(it.apis), "dedicatedBytes" to jiOrNull(it.dedicatedBytes)) }),
            "power" to jo(buildList { add("battery" to jb(d.battery)); if (d.batteryDesignPresent) add("batteryDesignMilliWh" to jiOrNull(d.batteryDesignMilliWh)) }),
            "thermal" to jo("cooling" to js(d.cooling), "stateSource" to js(d.stateSource)),
        )
        d.platformIds?.let { p ->
            m += "platformIds" to jo(buildList {
                p.brand?.let { add("brand" to js(it)) }
                p.device?.let { add("device" to js(it)) }
                p.manufacturer?.let { add("manufacturer" to js(it)) }
                p.model?.let { add("model" to js(it)) }
                p.product?.let { add("product" to js(it)) }
            })
        }
        return jo(m)
    }
}
