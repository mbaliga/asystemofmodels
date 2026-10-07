package xyz.mdhv.asom.lab.bench

import java.time.Instant
import java.time.ZoneOffset
import xyz.mdhv.asom.lab.json.JValue

/**
 * The `asom.bench/1` measurement document as it travels inside a signed manifest (`body.bench`): the ACTIVE lane only, raw samples
 * and probe readings, and nothing derived. Everything the text and the manifest's `results` say is computed from it by
 * [Derive] (M04). The r0 schema (benchmark.md 13.2) required `field`, `custom`, `derived` and `render`, which P7 forbids; the
 * patch list B1..B8 that resolves this is ERRATA `ERR-BENCH-1`.
 */
data class BEngine(
    val name: String,
    val commit: String,
    val backend: String,
    val buildFlags: List<String>,
    val threads: Long,
    val gpuLayers: Long,
    val kvType: String,
    val flashAttn: String,
)

data class BHarness(
    val shell: String,
    val coreImpl: String,
    val coreVersion: String,
    val confVersion: String,
    val timingSource: String,
    val planSha256: String,
    val engine: BEngine,
)

data class BDevice(
    val platform: String,
    val form: String,
    val maker: String,
    val model: String,
    val soc: String,
    val osVersion: String,
    val osBuild: String?,
    val gpu: String?,
    val gpuDriver: String?,
    val memTotalBytes: Long,
    val unifiedMemory: Boolean,
    val virtualized: Boolean,
)

data class BMemory(val availAtStartBytes: Long, val processLimitBytes: Long?, val gpuWorkingSetBytes: Long?, val limitSource: String)

data class BAbort(val reason: String, val atMs: Long)

data class BRun(
    val plan: String,
    val optInTiers: List<String>,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val dayUtc: String,
    val powerSource: String,
    val batteryStartPermille: Long?,
    val startThermal: String,
    val screenOn: Boolean?,
    val contentionBeforePermille: Long,
    val contentionAfterPermille: Long,
    val abort: BAbort?,
)

/** `pp<P>@d<D>` or `tg<N>@d<D>`. `tg128xK` (batched decode) is extended-only and deferred (design B31), so it is not decodable. */
data class TestSpec(val kind: String, val tokens: Long, val depth: Long) {
    val name: String get() = "$kind$tokens@d$depth"
    val isPrefill: Boolean get() = kind == "pp"

    companion object {
        private val rx = Regex("(pp|tg)([0-9]{1,7})@d([0-9]{1,8})")

        fun parse(s: String): TestSpec? {
            val m = rx.matchEntire(s) ?: return null
            val tokens = m.groupValues[2].toLong()
            val depth = m.groupValues[3].toLong()
            if (tokens < 1 || tokens > 1_000_000L || depth > 10_000_000L) return null
            return TestSpec(m.groupValues[1], tokens, depth)
        }
    }
}

/** [samples] are per-rep spans in microseconds; [wholeSamples] (tokenize + prefill + first argmax, the TTFT spans) exist only for `pp@d0`. */
data class BTest(val spec: TestSpec, val samples: List<Long>, val wholeSamples: List<Long>?)

data class BNumerics(val milliNatsPerToken: Long, val refMilliNatsPerToken: Long?)

data class BTier(
    val tier: String,
    val startThermal: String,
    val startThermalCode: Int,
    val startedAtMs: Long,
    val nCtx: Long,
    val availBeforeLoadBytes: Long,
    val peakFootprintBytes: Long,
    val kvBytesPerToken: Long,
    val loadColdMicros: Long,
    val loadColdness: String,
    val loadWarmMicros: List<Long>,
    val tests: List<BTest>,
    val numerics: BNumerics,
    /** How many times this tier's phase restarted after a yield (each restart caps confidence at medium). */
    val restarts: Int = 0,
    /** Swap growth during the tier in bytes, or null when unknown; more than 256 MiB sets SWAPPED and caps confidence at low. */
    val swapDeltaBytes: Long? = null,
) {
    fun test(name: String): BTest? = tests.firstOrNull { it.spec.name == name }
}

/** One sustain window: `[tStartMs, decodeTokens, decodeMicros, maxThermalCode]`. */
data class BWindow(val tStartMs: Long, val tokens: Long, val micros: Long, val maxThermalCode: Int)

data class BSustain(
    val tier: String,
    val windowMs: Long,
    val capMs: Long,
    val endReason: String,
    val headroomAtOnsetPermille: Long?,
    val windows: List<BWindow>,
)

data class BenchDoc(
    val benchProtocol: Int,
    val benchSet: String,
    val harness: BHarness,
    val device: BDevice,
    val memory: BMemory,
    val run: BRun,
    val tiers: List<BTier>,
    val sustain: BSustain?,
) {
    fun tier(id: String): BTier? = tiers.firstOrNull { it.tier == id }
}

object BenchEnums {
    val shells = setOf("android-daemon", "android-standalone", "ios-standalone", "desktop-daemon", "desktop-cli", "ios-app", "ubuntu-touch-app")
    val coreImpls = setOf("jvm", "swift")
    val timingSources = setOf("host-monotonic", "stream")
    val flashAttn = setOf("on", "off", "auto")
    val platforms = setOf("android", "ios", "ipados", "macos", "linux", "windows", "ubuntu-touch")
    val forms = setOf("phone", "tablet", "handheld", "laptop", "desktop", "server")
    val plans = setOf("quick", "standard", "sustained", "battery", "extended", "ci")
    val powerSources = setOf("ac", "battery")
    val startThermals = setOf("cool", "warm", "hot")
    val loadColdness = setOf("evicted", "best-effort", "unknown")
    val abortReasons = setOf("THERMAL_HARD", "BATTERY_TEMP", "USER_STOP", "BACKGROUNDED", "CHARGER_REMOVED", "MEMORY_PRESSURE", "WALL_CAP", "PROBE_LOST", "DEVICE_BUSY")
    val endReasons = setOf("PLATEAU", "TIME_CAP", "THERMAL_SOFT", "THERMAL_HARD", "BATTERY_TEMP", "USER_STOP", "BACKGROUNDED", "CHARGER_REMOVED", "MEMORY_PRESSURE", "WALL_CAP", "YIELDED", "DEVICE_BUSY", "PROBE_LOST")
    val tiers = TIER_ORDER.toSet()
}

object BenchCodec {
    const val EPOCH_LO: Long = 1_577_836_800_000L
    const val EPOCH_HI: Long = 4_102_444_800_000L
    private const val BYTES_MAX: Long = 1L shl 50
    private const val SPAN_MAX: Long = 86_400_000_000L

    fun dayOf(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** Decodes and validates. Throws [SchemaViolation]; the verifier maps it to `SCHEMA_INVALID`. */
    fun decode(v: JValue, ctx: RdContext, sets: List<BenchSetDef> = BenchSets.defaults(), path: String = "$"): BenchDoc {
        findForbiddenMember(v, path)?.let { throw SchemaViolation(it, "member forbidden inside a signed bench document") }
        val r = Rd.root(v, path, ctx)
        return r.scope {
            if (str("schema", StrRule.ANY_SHORT) != "asom.bench/1") throw SchemaViolation("$path.schema", "must be asom.bench/1")
            val proto = int("benchProtocol", 1, 1_000_000L)
            if (proto != 1L) throw SchemaViolation("$path.benchProtocol", "only benchProtocol 1 can be derived here")
            val setId = str("benchSet", StrRule.ID)
            val set = BenchSets.find(setId, sets) ?: throw SchemaViolation("$path.benchSet", "unknown or unavailable bench set '$setId'")
            if (set.status == PinStatus.UNPINNED) throw SchemaViolation("$path.benchSet", "bench set '$setId' has no pins (BLOCKED(D18))")
            val harness = decodeHarness(obj("harness"))
            val device = decodeDevice(obj("device"))
            val memory = decodeMemory(obj("memory"))
            val run = decodeRun(obj("run"))
            val tiers = arrObjs("tiers", 0, 6).map { decodeTier(it, set, harness.engine) }
            val idx = tiers.map { TIER_ORDER.indexOf(it.tier) }
            if (idx != idx.sorted() || idx.toSet().size != idx.size) throw SchemaViolation("$path.tiers", "tiers must be unique and in tier order")
            val sustain = objOrNull("sustain")?.let { decodeSustain(it, tiers) }
            requireNull("energy")
            BenchDoc(proto.toInt(), setId, harness, device, memory, run, tiers, sustain)
        }
    }

    private fun decodeHarness(r: Rd): BHarness = r.scope {
        val engine = obj("engine").scope {
            BEngine(
                name = str("name", StrRule.ID),
                commit = str("commit", StrRule.COMMIT).also { if (it.length != 40) throw SchemaViolation("$path.commit", "must be 40 hex digits") },
                backend = str("backend", StrRule.ID), buildFlags = arrStrs("buildFlags", 0, 16, StrRule.TEXT, unique = true),
                threads = int("threads", 0, 1024), gpuLayers = int("gpuLayers", 0, 100_000), kvType = str("kvType", StrRule.ID),
                flashAttn = enum("flashAttn", BenchEnums.flashAttn),
            )
        }
        BHarness(
            shell = enum("shell", BenchEnums.shells), coreImpl = enum("coreImpl", BenchEnums.coreImpls),
            coreVersion = str("coreVersion", StrRule.SEMVER), confVersion = str("confVersion", StrRule.SEMVER),
            timingSource = enum("timingSource", BenchEnums.timingSources), planSha256 = str("planSha256", StrRule.B64U32), engine = engine,
        )
    }

    private fun decodeDevice(r: Rd): BDevice = r.scope {
        BDevice(
            platform = enum("platform", BenchEnums.platforms), form = enum("form", BenchEnums.forms),
            maker = str("maker", StrRule.TEXT), model = str("model", StrRule.TEXT), soc = str("soc", StrRule.TEXT),
            osVersion = str("osVersion", StrRule.TEXT), osBuild = strOrNull("osBuild", StrRule.TEXT),
            gpu = strOrNull("gpu", StrRule.TEXT), gpuDriver = strOrNull("gpuDriver", StrRule.TEXT),
            memTotalBytes = int("memTotalBytes", 1, BYTES_MAX), unifiedMemory = bool("unifiedMemory"), virtualized = bool("virtualized"),
        )
    }

    private fun decodeMemory(r: Rd): BMemory = r.scope {
        BMemory(
            availAtStartBytes = int("availAtStartBytes", 0, BYTES_MAX), processLimitBytes = intOrNull("processLimitBytes", 1, BYTES_MAX),
            gpuWorkingSetBytes = intOrNull("gpuWorkingSetBytes", 1, BYTES_MAX), limitSource = str("limitSource", StrRule.ID),
        )
    }

    private fun decodeRun(r: Rd): BRun = r.scope {
        val started = int("startedAtMs", EPOCH_LO, EPOCH_HI - 1)
        val ended = int("endedAtMs", EPOCH_LO, EPOCH_HI - 1)
        if (ended < started) throw SchemaViolation("$path.endedAtMs", "before startedAtMs")
        val day = str("dayUtc", StrRule.DATE)
        if (day != dayOf(started)) throw SchemaViolation("$path.dayUtc", "is not the UTC date of startedAtMs")
        val opt = arrStrs("optInTiers", 0, 6, StrRule.ANY_SHORT, unique = true)
        if (opt.any { it !in BenchEnums.tiers }) throw SchemaViolation("$path.optInTiers", "unknown tier")
        BRun(
            plan = enum("plan", BenchEnums.plans), optInTiers = opt, startedAtMs = started, endedAtMs = ended, dayUtc = day,
            powerSource = enum("powerSource", BenchEnums.powerSources), batteryStartPermille = intOrNull("batteryStartPermille", 0, 1000),
            startThermal = enum("startThermal", BenchEnums.startThermals), screenOn = boolOrNull("screenOn"),
            contentionBeforePermille = int("contentionBeforePermille", 0, 1_000_000L),
            contentionAfterPermille = int("contentionAfterPermille", 0, 1_000_000L),
            abort = objOrNull("abort")?.scope { BAbort(enum("reason", BenchEnums.abortReasons), int("atMs", 0, 86_400_000L * 365)) },
        )
    }

    private fun decodeTier(r: Rd, set: BenchSetDef, engine: BEngine): BTier = r.scope {
        val id = enum("tier", BenchEnums.tiers)
        val pin = set.pin(id) ?: throw SchemaViolation("$path.tier", "tier $id is not in set ${set.id}")
        val sha = str("sha256", StrRule.SHA256_HEX)
        val bytes = int("bytes", 1, BYTES_MAX)
        val quant = str("quant", StrRule.QUANT)
        if (sha != pin.sha256 || bytes != pin.bytes || quant != pin.quant) throw SchemaViolation(path, "sha256, bytes or quant differ from the compiled-in pin")
        val tests = arrObjs("tests", 0, 16).map { decodeTest(it) }
        if (tests.map { it.spec.name }.toSet().size != tests.size) throw SchemaViolation("$path.tests", "duplicate test")
        if (tests.count { it.spec.isPrefill && it.spec.depth == 0L } > 4 || tests.count { !it.spec.isPrefill } > 4) {
            throw SchemaViolation("$path.tests", "at most 4 prefill points and 4 decode points can be carried by a manifest")
        }
        val numerics = obj("numerics").scope {
            BNumerics(int("milliNatsPerToken", 0, Checked.MAX_SAFE), intOrNull("refMilliNatsPerToken", 1, Checked.MAX_SAFE))
        }
        NumericsReferences.lookup(engine.commit, pin.sha256!!)?.let { pinned ->
            if (numerics.refMilliNatsPerToken != pinned) throw SchemaViolation("$path.numerics", "reference differs from the compiled-in one")
        }
        BTier(
            tier = id, startThermal = enum("startThermal", BenchEnums.startThermals), startThermalCode = int("startThermalCode", 0, 4).toInt(),
            startedAtMs = int("startedAtMs", EPOCH_LO, EPOCH_HI - 1), nCtx = int("nCtx", 1, 10_000_000L),
            availBeforeLoadBytes = int("availBeforeLoadBytes", 0, BYTES_MAX), peakFootprintBytes = int("peakFootprintBytes", 0, BYTES_MAX),
            kvBytesPerToken = int("kvBytesPerToken", 0, BYTES_MAX), loadColdMicros = int("loadColdMicros", 1, SPAN_MAX),
            loadColdness = enum("loadColdness", BenchEnums.loadColdness), loadWarmMicros = arrInts("loadWarmMicros", 1, 8, 1, SPAN_MAX),
            tests = tests, numerics = numerics, restarts = int("restarts", 0, 2).toInt(), swapDeltaBytes = intOrNull("swapDeltaBytes", 0, BYTES_MAX),
        )
    }

    private fun decodeTest(r: Rd): BTest = r.scope {
        val name = str("test", StrRule.ANY_SHORT)
        val spec = TestSpec.parse(name) ?: throw SchemaViolation("$path.test", "'$name' is not a supported pp<P>@d<D> or tg<N>@d<D> test")
        if (spec.isPrefill && spec.depth != 0L) throw SchemaViolation("$path.test", "prefill at depth cannot be carried by a manifest (benchmark.md 13.4 R5)")
        val samples = arrInts("samples", 1, 16, 1, SPAN_MAX)
        val whole = wholeSpans(spec, samples.size)
        BTest(spec, samples, whole)
    }

    private fun Rd.wholeSpans(spec: TestSpec, n: Int): List<Long>? {
        val paired = spec.isPrefill && spec.depth == 0L
        return if (paired) arrInts("wholeSamples", n, n, 1, SPAN_MAX) else if (has("wholeSamples")) throw SchemaViolation("$path.wholeSamples", "only pp@d0 carries whole spans") else null
    }

    private fun decodeSustain(r: Rd, tiers: List<BTier>): BSustain = r.scope {
        val tier = enum("tier", BenchEnums.tiers)
        if (tiers.none { it.tier == tier }) throw SchemaViolation("$path.tier", "the sustain tier is not among tiers")
        val windows = arrRaw("windows", 1, 240).mapIndexed { i, w ->
            val arr = (w as? xyz.mdhv.asom.lab.json.JArray)?.items ?: throw SchemaViolation("$path.windows[$i]", "not an array")
            if (arr.size != 4) throw SchemaViolation("$path.windows[$i]", "expected 4 integers")
            val n = arr.mapIndexed { k, x -> (x as? xyz.mdhv.asom.lab.json.JInt)?.value ?: throw SchemaViolation("$path.windows[$i][$k]", "not an integer") }
            if (n[0] !in 0..86_400_000L || n[1] !in 1..10_000_000L || n[2] !in 1..3_600_000_000L || n[3] !in 0..4L) {
                throw SchemaViolation("$path.windows[$i]", "value out of range")
            }
            BWindow(n[0], n[1], n[2], n[3].toInt())
        }
        if (windows[0].tStartMs != 0L) throw SchemaViolation("$path.windows", "must start at t = 0")
        for (i in 1 until windows.size) if (windows[i].tStartMs <= windows[i - 1].tStartMs) throw SchemaViolation("$path.windows", "tStartMs must strictly increase")
        BSustain(
            tier = tier, windowMs = int("windowMs", 100, 3_600_000L), capMs = int("capMs", 1, 86_400_000L),
            endReason = enum("endReason", BenchEnums.endReasons), headroomAtOnsetPermille = intOrNull("headroomAtOnsetPermille", 0, 10_000L),
            windows = windows,
        )
    }

    // ------------------------------------------------------------------------------------------ encode

    fun encode(doc: BenchDoc, sets: List<BenchSetDef> = BenchSets.defaults()): JValue {
        val set = BenchSets.find(doc.benchSet, sets) ?: error("unknown bench set ${doc.benchSet}")
        val h = doc.harness
        val e = h.engine
        val d = doc.device
        val m = doc.memory
        val r = doc.run
        return jo(
            "schema" to js("asom.bench/1"), "benchProtocol" to ji(doc.benchProtocol), "benchSet" to js(doc.benchSet),
            "harness" to jo(
                "shell" to js(h.shell), "coreImpl" to js(h.coreImpl), "coreVersion" to js(h.coreVersion), "confVersion" to js(h.confVersion),
                "timingSource" to js(h.timingSource), "planSha256" to js(h.planSha256),
                "engine" to jo(
                    "name" to js(e.name), "commit" to js(e.commit), "backend" to js(e.backend), "buildFlags" to jsList(e.buildFlags),
                    "threads" to ji(e.threads), "gpuLayers" to ji(e.gpuLayers), "kvType" to js(e.kvType), "flashAttn" to js(e.flashAttn),
                ),
            ),
            "device" to jo(
                "platform" to js(d.platform), "form" to js(d.form), "maker" to js(d.maker), "model" to js(d.model), "soc" to js(d.soc),
                "osVersion" to js(d.osVersion), "osBuild" to jsOrNull(d.osBuild), "gpu" to jsOrNull(d.gpu), "gpuDriver" to jsOrNull(d.gpuDriver),
                "memTotalBytes" to ji(d.memTotalBytes), "unifiedMemory" to jb(d.unifiedMemory), "virtualized" to jb(d.virtualized),
            ),
            "memory" to jo(
                "availAtStartBytes" to ji(m.availAtStartBytes), "processLimitBytes" to jiOrNull(m.processLimitBytes),
                "gpuWorkingSetBytes" to jiOrNull(m.gpuWorkingSetBytes), "limitSource" to js(m.limitSource),
            ),
            "run" to jo(
                "plan" to js(r.plan), "optInTiers" to jsList(r.optInTiers), "startedAtMs" to ji(r.startedAtMs), "endedAtMs" to ji(r.endedAtMs),
                "dayUtc" to js(r.dayUtc), "powerSource" to js(r.powerSource), "batteryStartPermille" to jiOrNull(r.batteryStartPermille),
                "startThermal" to js(r.startThermal), "screenOn" to jbOrNull(r.screenOn),
                "contentionBeforePermille" to ji(r.contentionBeforePermille), "contentionAfterPermille" to ji(r.contentionAfterPermille),
                "abort" to (r.abort?.let { jo("reason" to js(it.reason), "atMs" to ji(it.atMs)) } ?: xyz.mdhv.asom.lab.json.JNull),
            ),
            "tiers" to ja(doc.tiers.map { t ->
                val pin = set.pin(t.tier)!!
                jo(
                    "tier" to js(t.tier), "sha256" to js(pin.sha256!!), "bytes" to ji(pin.bytes!!), "quant" to js(pin.quant),
                    "startThermal" to js(t.startThermal), "startThermalCode" to ji(t.startThermalCode), "startedAtMs" to ji(t.startedAtMs),
                    "nCtx" to ji(t.nCtx), "availBeforeLoadBytes" to ji(t.availBeforeLoadBytes), "peakFootprintBytes" to ji(t.peakFootprintBytes),
                    "kvBytesPerToken" to ji(t.kvBytesPerToken), "loadColdMicros" to ji(t.loadColdMicros), "loadColdness" to js(t.loadColdness),
                    "loadWarmMicros" to jiList(t.loadWarmMicros),
                    "tests" to ja(t.tests.map { x ->
                        val members = mutableListOf<Pair<String, JValue>>("test" to js(x.spec.name), "samples" to jiList(x.samples))
                        x.wholeSamples?.let { members += "wholeSamples" to jiList(it) }
                        jo(members)
                    }),
                    "numerics" to jo("milliNatsPerToken" to ji(t.numerics.milliNatsPerToken), "refMilliNatsPerToken" to jiOrNull(t.numerics.refMilliNatsPerToken)),
                    "restarts" to ji(t.restarts), "swapDeltaBytes" to jiOrNull(t.swapDeltaBytes),
                )
            }),
            "sustain" to (doc.sustain?.let { s ->
                jo(
                    "tier" to js(s.tier), "windowMs" to ji(s.windowMs), "capMs" to ji(s.capMs), "endReason" to js(s.endReason),
                    "headroomAtOnsetPermille" to jiOrNull(s.headroomAtOnsetPermille),
                    "windows" to ja(s.windows.map { w -> ja(ji(w.tStartMs), ji(w.tokens), ji(w.micros), ji(w.maxThermalCode)) }),
                )
            } ?: xyz.mdhv.asom.lab.json.JNull),
            "energy" to xyz.mdhv.asom.lab.json.JNull,
        )
    }
}
