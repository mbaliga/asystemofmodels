package xyz.mdhv.asom.lab.router

import kotlin.test.fail
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.routing.Router

sealed interface PlanRes {
    class Ok(val plan: MeshPlan) : PlanRes

    class Err(val code: String) : PlanRes
}

fun tryPlan(q: MeshQuery, s: MeshSnapshot): PlanRes = try {
    PlanRes.Ok(MeshRouter().plan(q, s))
} catch (e: MeshPlanException) {
    PlanRes.Err(e.code)
}

/** The unmodified v1 router built by the test itself, straight from the frozen inputs (not through [CloudAdapter]). */
fun v1(q: MeshQuery, s: MeshSnapshot): PlanRes {
    var t = s.wallNowMs
    val cool = CooldownRegistry(clock = { t })
    for ((id, until) in s.cloud.coolingUntilWallMs.toSortedMap()) if (until > s.wallNowMs) { t = until - 30_000; cool.recordFailure(id) }
    t = s.wallNowMs
    val router = Router({ s.cloud.catalogue }, { it in s.cloud.keysPresent }, LatencyTracker().apply { preload(s.cloud.ewmaMs) }, cool, s.config.defaultPolicy, false)
    return try {
        PlanRes.Ok(MeshPlan(router.plan(q.v1).map { PlannedAttempt(Tier.CLOUD, null, null, it, null, null, true, false, "") }, emptyList(), CapDelta()))
    } catch (e: AsomException) {
        PlanRes.Err(e.code.name)
    }
}

fun MeshQuery.resolvedPolicy(cfg: MeshConfig): Policy = Policy.fromWire(v1.model) ?: v1.policyHeader ?: cfg.defaultPolicy

fun PlannedAttempt.id(): String = if (tier == Tier.CLOUD) "cloud:${cloud!!.provider.id}/${cloud.modelId}" else "${tier.name}:$nodeId/${file!!.fileSha256}"

sealed interface Outcome {
    data object NotApplicable : Outcome

    data object Held : Outcome

    class Violated(val why: String) : Outcome
}

class LawResult(val name: String, val iterations: Int, val violations: List<String>)

/**
 * Runs a law over worlds from seeds 1..20 (LAB_SPEC 6.8). A world whose antecedent does not hold counts nothing; a law fails when it finds a violation or
 * when fewer than [floor] worlds exercised it (non-vacuity, R10). Prints `<name> iterations: <n> violations: <v>`.
 */
object Laws {
    const val FLOOR = 100

    fun run(name: String, perSeed: Int = 80, calm: Boolean = false, floor: Int = FLOOR, body: (WorldGen, GenWorld) -> Outcome): LawResult {
        var iterations = 0
        val violations = ArrayList<String>()
        for (seed in 1L..20L) {
            val gen = WorldGen(seed * 7919 + name.hashCode())
            repeat(perSeed) {
                val w = gen.world(calm)
                when (val o = body(gen, w)) {
                    Outcome.NotApplicable -> Unit
                    Outcome.Held -> iterations++
                    is Outcome.Violated -> {
                        iterations++
                        if (violations.size < 5) violations += "seed $seed: ${o.why}"
                    }
                }
            }
        }
        val r = LawResult(name, iterations, violations)
        println("$name iterations: ${r.iterations} violations: ${r.violations.size}")
        if (violations.isNotEmpty()) fail("$name violated: ${violations.joinToString(" | ")}")
        if (iterations < floor) fail("$name is vacuous: $iterations iterations < $floor")
        return r
    }

    fun check(cond: Boolean, why: () -> String): Outcome = if (cond) Outcome.Held else Outcome.Violated(why())
}
