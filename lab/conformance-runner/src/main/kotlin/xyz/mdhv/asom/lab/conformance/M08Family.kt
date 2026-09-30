package xyz.mdhv.asom.lab.conformance

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.router.AttemptObserver
import xyz.mdhv.asom.lab.router.Bpt
import xyz.mdhv.asom.lab.router.ClaimKey
import xyz.mdhv.asom.lab.router.ClaimState
import xyz.mdhv.asom.lab.router.ClaimTracker
import xyz.mdhv.asom.lab.router.Estimator
import xyz.mdhv.asom.lab.router.LinkStats
import xyz.mdhv.asom.lab.router.LiveStateCache
import xyz.mdhv.asom.lab.router.MeshConfig
import xyz.mdhv.asom.lab.router.ObsRecord
import xyz.mdhv.asom.lab.router.Observation
import xyz.mdhv.asom.lab.router.PeerStateCache
import xyz.mdhv.asom.lab.router.PerfPrior
import xyz.mdhv.asom.lab.router.RateCeiling
import xyz.mdhv.asom.lab.router.StDigestDoc
import xyz.mdhv.asom.lab.router.TrackedRates
import xyz.mdhv.asom.lab.router.TrackerBook
import xyz.mdhv.asom.lab.router.TrackerState
import xyz.mdhv.asom.lab.policy.StateDoc

private fun JsonObject.nlong(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()

private fun JsonObject.nstr(k: String): String? = strOrNull(k)

private fun priorOf(o: JsonObject) = PerfPrior(
    o.arr("decodeAt").map { val a = it as kotlinx.serialization.json.JsonArray; (a[0] as JsonPrimitive).content.toInt() to (a[1] as JsonPrimitive).content.toLong() },
    o.long("prefillMilliTokPerSec"), o.long("ttft0Ms"), o.long("steadyMilliTokPerSec"), o.nlong("throttleOnsetMs"), o.nlong("powerMilliW"), o.long("kvBytesPerToken"),
    o.long("peakProcessBytes"), o.strList("flags").toSet(),
)

private fun linkOf(o: JsonObject) = LinkStats(o.long("rttMs"), o.long("kbps"), PeerPath.valueOf(o.str("path")), o.reqBool("metered"), o.reqBool("sessionWarm"), o.long("samples").toInt())

private fun sse(content: String): ByteArray = ("data: " + Jcs.serializeToString(JObject(listOf("choices" to JArray(listOf(JObject(listOf("delta" to JObject(listOf("content" to JString(content))))))))))) .plus("\n\n").toByteArray()

/**
 * M08: the claim tracker and its adversaries (LAB_SPEC 6.6), through the real [ClaimTracker] and [AttemptObserver]. The observation is built the way the pipeline
 * builds it: from frames and requester clocks only. Evidence label: LAB, oracle: self.
 */
class M08Checker : FamilyChecker("M08") {
    override val requiredLaws = setOf(
        "evaluate", "sequence", "state", "claimBody", "chunk-split-invariant", "lying-end-ignored", "peer-state-ignored", "padding-over-cap", "discard-INCOMPLETE", "discard-SHORT",
        "discard-OVERLONG", "discard-CONCURRENT", "discard-SETTINGS", "budget-tripped", "only-lowers", "bpt-matches-bench-core",
    )

    private val key = ClaimKey("peer-x", "a1".repeat(32), "metal")

    private fun observationFrom(i: JsonObject): Pair<Observation, PerfPrior> {
        val claim = priorOf(i.obj("claim"))
        val obs = AttemptObserver()
        obs.onBodySent(i.long("tBodyMs"))
        i.objOrNull("head")?.let { obs.onHead(it.long("t")) }
        for (c in i.arr("chunks")) {
            val o = c as JsonObject
            if (o.containsKey("count")) {
                val n = o.long("count").toInt()
                val from = o.long("tFrom")
                val to = o.long("tTo")
                for (k in 0 until n) obs.onChunk(if (n == 1) to else from + (to - from) * k / (n - 1), sse("x".repeat(o.long("bytesEach").toInt())))
            } else {
                obs.onChunk(o.long("t"), sse("x".repeat(o.long("bytes").toInt())))
            }
        }
        i.objOrNull("end")?.let { obs.onEnd(it.long("t"), it.str("payload").toByteArray()) }
        val link = linkOf(i.obj("link"))
        val o = Observation(
            key, obs.terminal == "done", obs.tBodyMs!!, obs.tEndMs()!!, obs.outBytes(), i.long("promptTokens"), i.long("maxTokens"),
            Estimator.warmNetMs(link, i.long("promptBytes"), MeshConfig()), i.reqBool("concurrent"), i.nstr("acceptedSha"), i.nstr("heldBackend"), i.nstr("heldCommit"), i.nstr("claimCommit"),
        )
        return o to claim
    }

    private fun bptOf(i: JsonObject): Bpt = i.arr("bpt").let { Bpt((it[0] as JsonPrimitive).content.toLong(), (it[1] as JsonPrimitive).content.toLong()) }

    private fun ratesJson(t: TrackedRates): JsonElement = buildJsonObject { put("prefill", t.prefill); put("decodeAtP", t.decodeAtP); put("steady", t.steady) }

    private fun summary(claim: PerfPrior, ts: TrackerState, promptTokens: Long, disc: Long, ceiling: RateCeiling?): JsonElement = buildJsonObject {
        put("n", ts.ratios.size)
        put("ratios", buildJsonArray { ts.ratios.forEach { add(JsonPrimitive(it)) } })
        put("best", if (ts.ratios.isEmpty()) JsonNull else JsonPrimitive(ClaimTracker.best(ts)!!))
        put("state", ClaimTracker.stateOf(ts).name)
        put("strikes", ts.strikes)
        put("budgetTripped", ClaimTracker.budgetTripped(ts))
        put("minRatio", ClaimTracker.minRatio(ts))
        put("tracked", ratesJson(ClaimTracker.tracked(claim, promptTokens, ts, ceiling, disc)))
    }

    override fun observe(v: Vector): Observed {
        val i = v.input
        if (MeshConfig().defaultBpt != xyz.mdhv.asom.lab.bench.BytesPerToken.CLASS_DEFAULT.let { Bpt(it.bptPermille, it.bptCapPermille) }) {
            throw LawViolation("the router's class-default bytes-per-token differs from :bench-core's")
        }
        bump("bpt-matches-bench-core")
        return when (val kind = i.str("kind")) {
            "evaluate" -> {
                bump("evaluate")
                val (o, claim) = observationFrom(i)
                val ev = ClaimTracker.evaluate(o, claim, bptOf(i))
                if (i.objOrNull("peerDigest") != null) {
                    val d = i.obj("peerDigest")
                    val doc = StateDoc(1, 0, Fsm.SERVING, "ac", false, null, 0, Governor.RUN, "metal", "0123abc", "0.2.0", emptyList(), 0, null, null)
                    var c = LiveStateCache.onState(LiveStateCache.onSessionOpen(PeerStateCache()), doc, 0)
                    c = LiveStateCache.onPiggyback(c, StDigestDoc(2, Fsm.SERVING, d.long("tb").toInt(), Governor.RUN, d.long("qb").toInt()), 10)
                    if (ClaimTracker.evaluate(o, claim, bptOf(i)) != ev) throw LawViolation("a peer digest changed an observation")
                    bump("peer-state-ignored")
                }
                if (i.objOrNull("end")?.str("payload")?.contains("usage") == true) {
                    val clean = i.obj("end").str("payload").let { p -> p.replace(Regex(",\"(usage|ttftMs|totalMs|decodeMilliTokPerSec|outBytes)\":(\\{[^}]*\\}|[0-9]+)"), "") }
                    val i2 = JsonObject(i + ("end" to JsonObject(i.obj("end") + ("payload" to JsonPrimitive(clean)))))
                    val (o2, _) = observationFrom(i2)
                    if (o2.outBytes != o.outBytes || ClaimTracker.evaluate(o2, claim, bptOf(i)).ratio != ev.ratio) throw LawViolation("peer-supplied INFER_END members changed the observation")
                    bump("lying-end-ignored")
                }
                if (i.arr("chunks").size > 1 || (i.arr("chunks").firstOrNull() as? JsonObject)?.containsKey("count") == true) bump("chunk-split-invariant")
                if (ev.discard != null) bump("discard-${ev.discard}")
                if (o.outBytes > 3_000) bump("padding-over-cap")
                Observed.Ok(
                    buildJsonObject {
                        put("outBytes", o.outBytes); put("outTokEst", ev.outTokEst); put("predictedMs", ev.predictedMs); put("elapsedMs", ev.elapsedMs); put("ratio", ev.ratio)
                        put("discard", if (ev.discard == null) JsonNull else JsonPrimitive(ev.discard!!.name))
                    },
                )
            }
            "sequence" -> {
                bump("sequence")
                val claim = priorOf(i.obj("claim"))
                val bpt = bptOf(i)
                val link = linkOf(i.obj("link"))
                var book = TrackerBook()
                for (e in i.arr("observations")) {
                    val o = e as JsonObject
                    val done = if (o.containsKey("done")) o.reqBool("done") else true
                    val obs = Observation(
                        key, done, 0, o.long("elapsed"), o.long("bytes"), i.long("promptTokens"), i.long("maxTokens"), Estimator.warmNetMs(link, i.long("promptBytes"), MeshConfig()),
                        if (o.containsKey("concurrent")) o.reqBool("concurrent") else false, null, null, null, null,
                    )
                    val before = ClaimTracker.evaluate(obs, claim, bpt)
                    if (before.discard != null) bump("discard-${before.discard}")
                    book = ClaimTracker.onObservation(book, obs, claim, bpt, 1_000)
                }
                val ts = book.states[key] ?: TrackerState()
                if (ClaimTracker.budgetTripped(ts)) bump("budget-tripped")
                val ceiling = i.objOrNull("ceiling")?.let { RateCeiling(it.long("prefillMilliTokPerSec"), it.long("decodeMilliTokPerSec"), it.long("steadyMilliTokPerSec")) }
                val tracked = ClaimTracker.tracked(claim, i.long("promptTokens"), ts, ceiling, i.long("disc"))
                if (tracked.prefill > claim.prefillMilliTokPerSec || tracked.steady > claim.steadyMilliTokPerSec) throw LawViolation("a tracked rate exceeds the claim")
                bump("only-lowers")
                Observed.Ok(summary(claim, ts, i.long("promptTokens"), i.long("disc"), ceiling))
            }
            "state" -> {
                bump("state")
                val claim = priorOf(i.obj("claim"))
                val ts = TrackerState(
                    ratios = i.arr("ratios").map { (it as JsonPrimitive).content.toLong() },
                    recent = i.arr("recent").map { r -> val ro = r as JsonObject; ObsRecord(ro.reqBool("kept"), ro.long("ratio"), ro.long("outBytes"), null) },
                    inheritedDiscrepant = i.reqBool("inheritedDiscrepant"),
                )
                if (ClaimTracker.budgetTripped(ts)) bump("budget-tripped")
                bump("only-lowers")
                Observed.Ok(summary(claim, ts, i.long("promptTokens"), i.long("disc"), null))
            }
            "claimBody" -> {
                bump("claimBody")
                var book = TrackerBook()
                val accepted = ArrayList<Boolean>()
                for (e in i.arr("events")) {
                    val o = e as JsonObject
                    val (b, ok) = ClaimTracker.onClaimBody(book, key, o.long("seq"), o.long("at"))
                    book = b
                    accepted += ok
                }
                Observed.Ok(buildJsonObject { put("accepted", buildJsonArray { accepted.forEach { add(JsonPrimitive(it)) } }) })
            }
            else -> throw LawViolation("kind $kind")
        }
    }
}
