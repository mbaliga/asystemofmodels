package xyz.mdhv.asom.lab.bench

import java.io.File
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

object Repo {
    val root: File = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set")).canonicalFile
    val benchExamples: File = File(root, "docs/design/mesh/bench-examples")
    val conformance: File = File(root, "lab/conformance")
}

fun parseFile(f: File): JValue = when (val r = StrictJson.parse(f.readBytes())) {
    is ParseResult.Ok -> r.value
    is ParseResult.Reject -> error("${f.name}: $r")
}

operator fun JValue.get(name: String): JValue = (this as JObject)[name] ?: error("no member $name")

fun JValue.str(): String = (this as JString).value

fun JValue.long(): Long = (this as JInt).value

fun JValue.list(): List<JValue> = (this as JArray).items

fun JValue.longOrNull(): Long? = if (this is JNull) null else long()

fun optMember(v: JValue, name: String): JValue? = (v as JObject)[name]

fun JValue.members(): List<Pair<String, JValue>> = (this as JObject).members

/** The design session's synthetic raw phone run (benchmark.md 12.7), mapped onto the r3 bench document (no `field`, no derived members). */
object ExampleDocs {
    const val DUMMY_PLAN_SHA: String = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    fun rawPhone(): JValue = parseFile(File(Repo.benchExamples, "raw-example-phone.json"))

    fun phoneDoc(planSha: String = DUMMY_PLAN_SHA): BenchDoc = fromRaw(rawPhone(), planSha)

    fun fromRaw(raw: JValue, planSha: String): BenchDoc {
        val h = raw["harness"]
        val e = h["engine"]
        val d = raw["device"]
        val m = raw["memory"]
        val r = raw["run"]
        val run = BRun(
            plan = r["plan"].str(), optInTiers = r["optInTiers"].list().map { it.str() }, startedAtMs = r["startedAtMs"].long(),
            endedAtMs = r["endedAtMs"].long(), dayUtc = BenchCodec.dayOf(r["startedAtMs"].long()), powerSource = r["powerSource"].str(),
            batteryStartPermille = r["batteryStartPermille"].longOrNull(), startThermal = r["startThermal"].str(),
            screenOn = (r["screenOn"] as? JBool)?.value, contentionBeforePermille = r["contentionBeforePermille"].long(),
            contentionAfterPermille = r["contentionAfterPermille"].long(), abort = null,
        )
        val tiers = raw["tiers"].members().map { (id, t0) ->
            val t: JValue = t0
            val tests = t["tests"].members().map { (name, v) ->
                val spec = TestSpec.parse(name)!!
                if (v is JObject) BTest(spec, optMember(v, "prefill")!!.list().map { it.long() }, optMember(v, "whole")!!.list().map { it.long() })
                else BTest(spec, v.list().map { it.long() }, null)
            }
            BTier(
                tier = id, startThermal = optMember(t, "startThermal")?.str() ?: run.startThermal,
                startThermalCode = ((optMember(t, "startThermalCode") as? JInt)?.value ?: 0L).toInt(), startedAtMs = t["startedAtMs"].long(),
                nCtx = t["nCtx"].long(), availBeforeLoadBytes = t["availBeforeLoadBytes"].long(), peakFootprintBytes = t["peakFootprintBytes"].long(),
                kvBytesPerToken = t["kvBytesPerToken"].long(), loadColdMicros = t["loadColdMicros"].long(), loadColdness = t["loadColdness"].str(),
                loadWarmMicros = t["loadWarmMicros"].list().map { it.long() }, tests = tests,
                numerics = BNumerics(t["nll"]["milliNatsPerToken"].long(), t["nll"]["refMilliNatsPerToken"].long()),
            )
        }.sortedBy { TIER_ORDER.indexOf(it.tier) }
        val s = raw["sustained"]
        val sustain = BSustain(
            tier = s["tier"].str(), windowMs = s["windowMs"].long(), capMs = s["capMs"].long(), endReason = s["endReason"].str(),
            headroomAtOnsetPermille = s["headroomAtOnsetPermille"].longOrNull(),
            windows = s["windows"].list().map { w -> val x = w.list().map { it.long() }; BWindow(x[0], x[1], x[2], x[3].toInt()) },
        )
        return BenchDoc(
            benchProtocol = raw["benchProtocol"].long().toInt(), benchSet = raw["benchSet"].str(),
            harness = BHarness(
                shell = h["shell"].str(), coreImpl = h["coreImpl"].str(), coreVersion = h["coreVersion"].str(), confVersion = h["confVersion"].str(),
                timingSource = h["timingSource"].str(), planSha256 = planSha,
                engine = BEngine(e["name"].str(), e["commit"].str(), e["backend"].str(), listOf(e["buildFlags"].str()), e["threads"].long(), e["gpuLayers"].long(), e["kvType"].str(), e["flashAttn"].str()),
            ),
            device = BDevice(
                platform = d["platform"].str(), form = d["form"].str(), maker = d["maker"].str(), model = d["model"].str(), soc = d["soc"].str(),
                osVersion = d["osVersion"].str(), osBuild = d["osBuild"].str(), gpu = d["gpu"].str(), gpuDriver = d["gpuDriver"].str(),
                memTotalBytes = d["memTotalBytes"].long(), unifiedMemory = (d["unifiedMemory"] as JBool).value, virtualized = (d["virtualized"] as JBool).value,
            ),
            memory = BMemory(m["availAtStartBytes"].long(), m["processLimitBytes"].longOrNull(), m["gpuWorkingSetBytes"].longOrNull(), m["limitSource"].str()),
            run = run, tiers = tiers, sustain = sustain,
        )
    }
}
