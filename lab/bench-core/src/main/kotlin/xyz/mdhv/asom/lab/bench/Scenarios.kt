package xyz.mdhv.asom.lab.bench

import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import java.security.MessageDigest

/**
 * Executor-trace scenarios: a small JSON description of a fake host and a plan, run to a deterministic event log
 * (LAB_SPEC 5 executor traces; design 6.5 B23). SIMULATED - NOT DEVICE EVIDENCE.
 */
object Scenarios {
    private val SPEEDS: Map<String, TierSpeed> = mapOf(
        "T0" to TierSpeed(1_200_000, 350_000, 1_400, 22_000, 120, 114_688, 2_610, 900_000_000),
        "T1" to TierSpeed(2_400_000, 620_000, 3_850, 47_700, 130, 114_688, 2_318, 2_310_000_000),
        "T2" to TierSpeed(4_900_000, 1_210_000, 7_680, 69_000, 140, 147_456, 2_026, 3_120_000_000),
        "T3" to TierSpeed(9_800_000, 2_395_000, 16_700, 135_000, 150, 147_456, 1_893, 5_980_000_000),
        "T4" to TierSpeed(14_000_000, 4_100_000, 27_000, 210_000, 150, 163_840, 1_700, 9_900_000_000),
        "T5" to TierSpeed(30_000_000, 9_000_000, 60_000, 480_000, 150, 262_144, 1_500, 20_500_000_000),
    )

    private val REFS: Map<String, Long> = mapOf("T0" to 2_600L, "T1" to 2_310L, "T2" to 2_020L, "T3" to 1_880L, "T4" to 1_695L, "T5" to 1_495L)

    private val ENGINE = BEngine("llama.cpp", "0123456789abcdef0123456789abcdef01234567", "opencl", listOf("GGML_OPENCL=ON"), 6, 99, "f16", "auto")

    val HEAT_PROFILES: Map<String, List<HeatStep>> = mapOf(
        "flat" to listOf(HeatStep(0, 1000, 0, 400)),
        "throttle" to listOf(
            HeatStep(0, 1000, 0, 400), HeatStep(60_000, 1000, 1, 500), HeatStep(180_000, 1100, 1, 700), HeatStep(195_000, 1250, 2, 830),
            HeatStep(225_000, 1450, 2, 850), HeatStep(255_000, 1500, 2, 860),
        ),
        "hot" to listOf(HeatStep(0, 1000, 0, 400), HeatStep(60_000, 1100, 1, 500), HeatStep(120_000, 1300, 2, 900), HeatStep(200_000, 1600, 3, 990)),
        "soft" to listOf(HeatStep(0, 1000, 0, 400), HeatStep(60_000, 1000, 1, 500), HeatStep(150_000, 1200, 2, 960)),
    )

    fun preset(name: String, plan: String): FakeConfig {
        val android = name == "phone"
        val device = if (android) {
            DeviceInfo("android", "phone", "Example", "Phone X1 (synthetic)", "Example SoC 8", "16", "EXAMPLE.260901.001", "Example GPU", "example-512.0", 16_000_000_000L, true, false)
        } else {
            DeviceInfo("linux", "desktop", "Example", "Desktop D1 (synthetic)", "Example CPU 16", "6.8", "build-1", "Example GPU", "drv-1", 64_000_000_000L, false, false)
        }
        val harness = HarnessInfo(if (android) "android-daemon" else "desktop-daemon", "jvm", "1.0.0", "0.2.0", "host-monotonic", ENGINE)
        val memory = if (android) MemoryReading(9_600_000_000L, null, null, 300_000_000L, 0L, "android-availmem")
        else MemoryReading(48_000_000_000L, null, null, 300_000_000L, 0L, "linux-memavailable")
        val power = PowerReading("ac", if (android) 740 else null, hasBattery = android)
        return FakeConfig(
            device = device, harness = harness, memory = memory, power = power, tiers = SPEEDS,
            heat = HEAT_PROFILES.getValue("throttle"), numericsRefs = REFS, daemon = true,
        )
    }

    private fun JValue.member(name: String): JValue? = (this as JObject)[name]

    private fun JValue.long(): Long = (this as JInt).value

    private fun JValue.str(): String = (this as JString).value

    /** Builds the fake host and the session options from a scenario description. Unknown members are errors: a typo must not silently pass. */
    fun parse(v: JValue): Pair<FakeConfig, Pair<RunPlan, SessionOptions>> {
        val o = v as JObject
        val known = setOf("preset", "plan", "optIn", "injections", "heat", "daemon", "power", "virtualized", "memAvail", "thermalAvailable", "spins", "oomTiers", "modelsPresent", "shell", "allowVirtual", "sustainedToday", "startCode", "jitterPermille", "seed", "handheld", "batterySaver", "epochStartMs")
        val extra = o.members.map { it.first }.filter { it !in known }
        require(extra.isEmpty()) { "unknown scenario member(s) $extra" }
        val planId = v.member("plan")!!.str()
        val plan = RunPlans.byId(planId) ?: error("unknown plan $planId")
        var cfg = preset(v.member("preset")!!.str(), planId)
        v.member("heat")?.let { cfg = cfg.copy(heat = HEAT_PROFILES[it.str()] ?: error("unknown heat profile ${it.str()}")) }
        v.member("daemon")?.let { cfg = cfg.copy(daemon = (it as JBool).value) }
        v.member("shell")?.let { cfg = cfg.copy(harness = cfg.harness.copy(shell = it.str())) }
        v.member("virtualized")?.let { cfg = cfg.copy(device = cfg.device.copy(virtualized = (it as JBool).value)) }
        v.member("handheld")?.let { if ((it as JBool).value) cfg = cfg.copy(device = cfg.device.copy(form = "handheld")) }
        v.member("memAvail")?.let { cfg = cfg.copy(memory = cfg.memory.copy(availBytes = it.long())) }
        v.member("thermalAvailable")?.let { cfg = cfg.copy(thermalAvailable = (it as JBool).value) }
        v.member("jitterPermille")?.let { cfg = cfg.copy(jitterPermille = it.long().toInt()) }
        v.member("seed")?.let { cfg = cfg.copy(seed = it.long()) }
        v.member("spins")?.let { cfg = cfg.copy(spins = (it as JArray).items.map { x -> x.long() }) }
        v.member("oomTiers")?.let { cfg = cfg.copy(engineOomTiers = (it as JArray).items.map { x -> x.str() }.toSet()) }
        v.member("modelsPresent")?.let { cfg = cfg.copy(modelsPresent = (it as JArray).items.map { x -> x.str() }.toSet()) }
        v.member("power")?.let { p ->
            cfg = cfg.copy(power = PowerReading(p.member("source")!!.str(), p.member("level")?.let { l -> if (l is JNull) null else l.long().toInt() }, cfg.power.hasBattery))
        }
        v.member("batterySaver")?.let { cfg = cfg.copy(presence = cfg.presence.copy(batterySaver = (it as JBool).value)) }
        v.member("epochStartMs")?.let { cfg = cfg.copy(epochStartMs = it.long()) }
        v.member("startCode")?.let { cfg = cfg.copy(injections = cfg.injections + Injection(0, "THERMAL_CODE", it.long())) }
        v.member("injections")?.let { arr ->
            cfg = cfg.copy(injections = cfg.injections + (arr as JArray).items.map { i -> Injection(i.member("atMs")!!.long(), i.member("kind")!!.str(), i.member("value")?.long() ?: 0L) })
        }
        val optIn = (v.member("optIn") as? JArray)?.items?.map { it.str() } ?: emptyList()
        val opts = SessionOptions(optIn, (v.member("allowVirtual") as? JBool)?.value ?: false, (v.member("sustainedToday") as? JBool)?.value ?: false)
        return cfg to (plan to opts)
    }

    /** What a trace vector pins: the ordered event log, the outcome and the SHA-256 of the produced document's JCS bytes. */
    class TraceResult(val events: List<String>, val outcome: String, val docSha256: String?, val leaks: List<String>, val doc: BenchDoc?)

    fun run(v: JValue): TraceResult {
        val (cfg, pack) = parse(v)
        val (plan, opts) = pack
        val host = FakeHost(cfg)
        val set = BenchSets.Q1
        val sheet = ConsentSheet.forPlan(plan, 0L, emptyList(), false, false)
        val token = sheet.confirm(sheet.textSha256, host.epochMillis())
        val session = BenchSession(host, plan, set, token, opts)
        host.onStop = { session.stop() }
        val out = session.run()
        val doc = when (out) {
            is RunOutcome.Completed -> out.doc
            is RunOutcome.Aborted -> out.partial
            is RunOutcome.Refused -> null
        }
        val outcome = when (out) {
            is RunOutcome.Completed -> "completed"
            is RunOutcome.Aborted -> "aborted ${out.reason}"
            is RunOutcome.Refused -> "refused ${out.reasons.joinToString(",")}"
        }
        val sha = doc?.let { d ->
            val json = BenchCodec.encode(d)
            // the document must round-trip through the strict decoder before it is pinned
            BenchCodec.decode(json, RdContext(false))
            hex(MessageDigest.getInstance("SHA-256").digest(Jcs.serialize(json)))
        }
        return TraceResult(session.trace.toList(), outcome, sha, host.leaks(), doc)
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
