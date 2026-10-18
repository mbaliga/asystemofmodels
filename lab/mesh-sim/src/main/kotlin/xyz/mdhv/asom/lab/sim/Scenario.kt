package xyz.mdhv.asom.lab.sim

import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.router.DeviceClass
import xyz.mdhv.asom.lab.router.Tier

const val SIM_LABEL = "SIMULATED — NOT DEVICE EVIDENCE"

class ScenarioException(message: String) : Exception(message)

data class SimFile(val modelId: String, val fileSha256: String, val fileBytes: Long, val quant: String?, val rank: Int?, val ctx: Long)

/** What a node really does. The published claim is `truth x claimScalePermille / 1000` (SC06 sets 2000: a 2x liar). */
data class Truth(val prefillMilliTokPerSec: Long, val decodeMilliTokPerSec: Long, val ttft0Ms: Long, val steadyMilliTokPerSec: Long, val throttleOnsetMs: Long?)

data class PowerSpec(val source: String, val batteryPermille: Int?, val designMilliWh: Long?)

data class Span(val fromMs: Long, val toMs: Long)

data class SimNodeSpec(
    val id: String, val tier: Tier, val deviceClass: DeviceClass, val backend: String, val files: List<SimFile>, val truth: Map<String, Truth>, val claimScalePermille: Long,
    val power: PowerSpec, val presence: List<Span>,
)

data class LinkSpec(val a: String, val b: String, val path: String, val rttMedianMs: Long, val rttSigmaPermille: Long, val kbps: Long, val metered: Boolean, val dropsPerHour: Long)

data class CloudSpec(val provider: String, val ttftMedianMs: Long, val decodeMilliTokPerSec: Long, val err429Permille: Long, val err5xxPermille: Long)

data class AppSpec(
    val pkg: String, val meshAllowed: Boolean, val cloudBanned: Boolean, val deviceOnly: Boolean, val policy: String, val arrivalsPerHour: Long, val promptTokensMedian: Long,
    val outTokensMedian: Long, val sigmaPermille: Long, val streamPermille: Long, val allowMeshOnMetered: Boolean, val neverCloudWhenDevicesCanAnswer: Boolean, val maxTokensCap: Long?,
)

data class FaultSpec(val atMs: Long, val kind: String, val node: String?, val phase: String?, val durationMs: Long?, val valuePermille: Long?)

data class Expect(val lawViolations: Long, val minPlacementPermille: Map<String, Long>, val minBatteryReductionPermille: Long?, val maxShareOverHindsightPermille: Map<String, Long>)

data class Scenario(
    val id: String, val seed: Long, val durationMs: Long, val label: String, val catalogue: String, val nodes: List<SimNodeSpec>, val links: List<LinkSpec>, val cloud: List<CloudSpec>, val apps: List<AppSpec>,
    val faults: List<FaultSpec>, val expect: Expect,
) {
    val self: SimNodeSpec get() = nodes.single { it.tier == Tier.SELF }
    val peers: List<SimNodeSpec> get() = nodes.filter { it.tier == Tier.PEER }
}

/** The closed list of fault kinds of LAB_SPEC 6.9. Every one is modelled (ERRATA lists the semantics). */
val FAULT_KINDS = listOf(
    "peer-vanish", "session-drop", "network-change", "thermal-spike", "charger-unplug", "battery-floor", "presence", "claim-lie", "claim-stale", "decline-storm", "state-delay", "state-drop",
    "clock-skew", "duplicate-attempt", "overlay-only-high-rtt", "metered-underlay", "ledger-full",
)

/** Scenario files are integers only (LAB_SPEC 6.9): the strict `:json` parser refuses a fraction, an exponent or a duplicate name. */
object ScenarioLoader {
    private fun bad(m: String): Nothing = throw ScenarioException(m)

    private fun JObject.member(k: String): JValue = this[k] ?: bad("missing member '$k'")

    private fun JObject.obj(k: String): JObject = member(k) as? JObject ?: bad("'$k' is not an object")

    private fun JObject.arr(k: String): List<JValue> = (member(k) as? JArray)?.items ?: bad("'$k' is not an array")

    private fun JObject.str(k: String): String = (member(k) as? JString)?.value ?: bad("'$k' is not a string")

    private fun JObject.long(k: String): Long = (member(k) as? JInt)?.value ?: bad("'$k' is not an integer")

    private fun JObject.bool(k: String): Boolean = (member(k) as? JBool)?.value ?: bad("'$k' is not a boolean")

    private fun JObject.nlong(k: String): Long? = when (val v = this[k]) {
        null, JNull -> null
        is JInt -> v.value
        else -> bad("'$k' is not an integer or null")
    }

    private fun JObject.nstr(k: String): String? = when (val v = this[k]) {
        null, JNull -> null
        is JString -> v.value
        else -> bad("'$k' is not a string or null")
    }

    private fun asObj(v: JValue): JObject = v as? JObject ?: bad("expected an object")

    fun parse(bytes: ByteArray, seedOverride: Long? = null): Scenario {
        val root = when (val r = StrictJson.parse(bytes)) {
            is ParseResult.Ok -> r.value as? JObject ?: bad("the scenario is not an object")
            is ParseResult.Reject -> bad("the scenario is not strict JSON: ${r.code}")
        }
        val label = root.str("label")
        if (label != SIM_LABEL) bad("the label must be '$SIM_LABEL'")
        val nodes = root.arr("nodes").map { n ->
            val o = asObj(n)
            val files = o.arr("files").map { f ->
                val fo = asObj(f)
                SimFile(fo.str("modelId"), fo.str("fileSha256"), fo.long("fileBytes"), fo.nstr("quant"), fo.nlong("rank")?.toInt(), fo.long("ctx"))
            }
            val truth = o.obj("truth").members.associate { (sha, t) ->
                val to = asObj(t)
                sha to Truth(to.long("prefillMilliTokPerSec"), to.long("decodeMilliTokPerSec"), to.long("ttft0Ms"), to.long("steadyMilliTokPerSec"), to.nlong("throttleOnsetMs"))
            }
            for (f in files) if (f.fileSha256 !in truth) bad("node ${o.str("id")} has no truth for ${f.fileSha256.take(8)}")
            val p = o.obj("power")
            SimNodeSpec(
                o.str("id"), Tier.valueOf(o.str("tier")), DeviceClass.valueOf(o.str("class")), o.str("backend"), files, truth, o.long("claimScalePermille"),
                PowerSpec(p.str("source"), p.nlong("batteryPermille")?.toInt(), p.nlong("designMilliWh")), o.arr("presence").map { s -> val so = asObj(s); Span(so.long("fromMs"), so.long("toMs")) },
            )
        }
        if (nodes.count { it.tier == Tier.SELF } != 1) bad("exactly one SELF node")
        val ids = nodes.map { it.id }
        if (ids.toSet().size != ids.size) bad("duplicate node id")
        val links = root.arr("links").map { l ->
            val o = asObj(l)
            LinkSpec(o.str("a"), o.str("b"), o.str("path"), o.long("rttMedianMs"), o.long("rttSigmaPermille"), o.long("kbps"), o.bool("metered"), o.long("dropsPerHour"))
        }
        val cloud = root.arr("cloud").map { c ->
            val o = asObj(c)
            val e = o.obj("errorPermille")
            CloudSpec(o.str("provider"), o.long("ttftMedianMs"), o.long("decodeMilliTokPerSec"), e.long("429"), e.long("5xx"))
        }
        val apps = root.arr("apps").map { a ->
            val o = asObj(a)
            AppSpec(
                o.str("pkg"), o.bool("meshAllowed"), o.bool("cloudBanned"), o.bool("deviceOnly"), o.str("policy"), o.long("arrivalsPerHour"), o.long("promptTokensMedian"), o.long("outTokensMedian"),
                o.long("sigmaPermille"), o.long("streamPermille"), o.this_bool("allowMeshOnMetered"), o.this_bool("neverCloudWhenDevicesCanAnswer"), o.nlong("maxTokensCap"),
            )
        }
        val faults = root.arr("faults").map { f ->
            val o = asObj(f)
            val kind = o.str("kind")
            if (kind !in FAULT_KINDS) bad("unknown fault kind '$kind'")
            FaultSpec(o.long("atMs"), kind, o.nstr("node"), o.nstr("phase"), o.nlong("durationMs"), o.nlong("valuePermille"))
        }
        val ex = root.obj("expect")
        fun perm(k: String): Map<String, Long> = ex.obj(k).members.associate { (n, v) -> n to ((v as? JInt)?.value ?: bad(k)) }
        val expect = Expect(ex.long("lawViolations"), perm("minPlacementPermille"), ex.nlong("minBatteryReductionPermille"), perm("maxShareOverHindsightPermille"))
        for (l in links) if (l.a !in ids || l.b !in ids) bad("a link names an unknown node")
        return Scenario(root.str("scenario"), seedOverride ?: root.long("seed"), root.long("durationMs"), label, root.str("catalogue"), nodes, links, cloud, apps, faults, expect)
    }

    private fun JObject.this_bool(k: String): Boolean = when (val v = this[k]) {
        null -> false
        is JBool -> v.value
        else -> bad("'$k' is not a boolean")
    }
}
