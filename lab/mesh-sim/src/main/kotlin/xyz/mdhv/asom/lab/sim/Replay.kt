package xyz.mdhv.asom.lab.sim

import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.router.MeshConfig
import xyz.mdhv.asom.lab.router.MeshPlan
import xyz.mdhv.asom.lab.router.MeshPlanException
import xyz.mdhv.asom.lab.router.MeshRouter
import xyz.mdhv.asom.lab.router.PlannedAttempt
import xyz.mdhv.asom.lab.router.Tier

/** How one decision is written to `decisions.jsonl`; the simulator and the replay use the same function so that a diff can only come from a different plan. */
object DecisionFormat {
    fun key(a: PlannedAttempt): String = when (a.tier) {
        Tier.CLOUD -> "cloud:${a.cloud!!.provider.id}/${a.cloud!!.modelId}"
        else -> "${a.tier.name}:${a.nodeId}/${a.file!!.fileSha256.take(8)}"
    }

    fun attempts(p: MeshPlan?): List<String> =
        p?.attempts?.map { "${key(it)}|${it.reason}|total=${it.estimate?.totalMs}|score=${it.score?.total}|claim=${it.claimState}|fresh=${it.freshness}" }.orEmpty()

    fun excluded(p: MeshPlan?): List<String> = p?.excluded?.map { "${it.nodeId}:${it.code}" }.orEmpty()
}

fun DecisionRec.line(): String = Jcs.serializeToString(
    JObject(
        listOf(
            "t" to JInt(t), "seq" to JInt(seq), "kind" to JString("decision"), "request" to JString(requestId), "attempts" to JArray(attempts.map { JString(it) }),
            "excluded" to JArray(excluded.map { JString(it) }), "error" to (error?.let { JString(it) } ?: JNull),
        ),
    ),
)

/**
 * Replay mode (LAB_SPEC 6.9, law RL21): the recorded `events.jsonl` is fed back, in order, into a FRESH [RequesterModel]; at every `request` event the pure `plan()` runs
 * over the model as it stands and the result is compared, line by line, with the recorded `decisions.jsonl`. Nothing but the event log is read.
 */
object Replay {
    class Result(val decisions: Int, val diff: List<String>) {
        val empty: Boolean get() = diff.isEmpty()
    }

    private val LABEL_LINE = Jcs.serializeToString(JObject(listOf("label" to JString(SIM_LABEL))))

    fun parseEvents(lines: List<String>): List<SimEvent> {
        require(lines.isNotEmpty() && lines.first() == LABEL_LINE) { "events.jsonl must start with the label line" }
        return lines.drop(1).filter { it.isNotEmpty() }.map { l ->
            val r = StrictJson.parse(l.toByteArray(Charsets.UTF_8))
            EventCodec.fromJson((r as ParseResult.Ok).value as JObject)
        }
    }

    fun run(sc: Scenario, catalogue: Catalogue, eventLines: List<String>, decisionLines: List<String>, cfg: MeshConfig = MeshConfig()): Result {
        require(decisionLines.isNotEmpty() && decisionLines.first() == LABEL_LINE) { "decisions.jsonl must start with the label line" }
        val model = RequesterModel(sc, catalogue, cfg)
        val recomputed = ArrayList<String>()
        for (e in parseEvents(eventLines)) {
            model.apply(e)
            if (e.kind != "request") continue
            var plan: MeshPlan? = null
            var err: String? = null
            try {
                plan = MeshRouter().plan(model.query(e), model.snapshot(e.t))
            } catch (x: MeshPlanException) {
                err = x.code
            }
            recomputed += DecisionRec(e.str("id"), e.t, e.seq, DecisionFormat.attempts(plan), err, DecisionFormat.excluded(plan)).line()
        }
        val recorded = decisionLines.drop(1).filter { it.isNotEmpty() }
        val diff = ArrayList<String>()
        if (recorded.size != recomputed.size) diff += "decision count: recorded ${recorded.size}, replayed ${recomputed.size}"
        for (i in 0 until minOf(recorded.size, recomputed.size)) {
            if (recorded[i] != recomputed[i]) diff += "decision $i differs:\n  recorded: ${recorded[i]}\n  replayed: ${recomputed[i]}"
        }
        return Result(recomputed.size, diff)
    }

    fun run(sc: Scenario, catalogue: Catalogue, result: SimResult, cfg: MeshConfig = MeshConfig()): Result = run(sc, catalogue, result.eventsJsonl(), result.decisionsJsonl(), cfg)
}
