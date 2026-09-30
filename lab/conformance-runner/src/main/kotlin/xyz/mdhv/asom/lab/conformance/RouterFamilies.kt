package xyz.mdhv.asom.lab.conformance

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.router.MeshPlan
import xyz.mdhv.asom.lab.router.MeshPlanException
import xyz.mdhv.asom.lab.router.MeshRouter
import xyz.mdhv.asom.lab.router.PlannedAttempt
import xyz.mdhv.asom.lab.router.Tier

internal val R01_ROWS = listOf(
    "F1_ELIGIBILITY", "F2_NOT_PAIRED", "F3_NO_SCOPE", "F4_MODEL", "F5_CONTEXT", "F6_MEMORY", "F7_BODY", "F8_CLAIM", "F9_AVAILABILITY", "F10_THERMAL", "F11_POWER",
    "F13_METERED", "F14_BREAKER", "F15_DECLINE_BACKOFF", "F16_EMBED_IDENTITY",
)

internal fun attemptId8(a: PlannedAttempt): String = "${a.tier.name}:${a.nodeId}/${a.file!!.fileSha256.take(8)}"

private fun exclusionsJson(ex: List<xyz.mdhv.asom.lab.router.Exclusion>): JsonElement = buildJsonArray {
    ex.sortedWith(compareBy({ it.nodeId }, { it.fileSha256.take(8) })).forEach { e ->
        add(buildJsonArray { add(JsonPrimitive(e.nodeId)); add(JsonPrimitive(e.fileSha256.take(8))); add(JsonPrimitive(e.code)) })
    }
}

/**
 * R01: every hard-filter row of LAB_SPEC 6.3 through the real [MeshRouter.plan], with the first failing row recorded, the EXPIRED exceptions and the error
 * precedence of design 7.6 (no new code). Evidence label: LAB, oracle: self.
 */
class R01Checker : FamilyChecker("R01") {
    override val requiredLaws = R01_ROWS.map { "row:$it" }.toSet() + setOf("survivors", "errors", "expired-skips-fast-filters")

    override fun observe(v: Vector): Observed {
        val (q, s) = RouterWorld.world(v.input)
        val plan: MeshPlan = try {
            MeshRouter().plan(q, s)
        } catch (e: MeshPlanException) {
            bump("errors")
            e.excluded.forEach { bump("row:${it.code}") }
            return Observed.Reject(e.code, buildJsonObject { put("excluded", exclusionsJson(e.excluded)) })
        }
        plan.excluded.forEach { bump("row:${it.code}") }
        val sov = plan.attempts.filter { it.tier != Tier.CLOUD }
        if (sov.any { it.freshness == Freshness.EXPIRED }) bump("expired-skips-fast-filters")
        bump("survivors")
        return Observed.Ok(
            buildJsonObject {
                put("excluded", exclusionsJson(plan.excluded))
                put("survivors", buildJsonArray { sov.map { attemptId8(it) }.sorted().forEach { add(JsonPrimitive(it)) } })
            },
        )
    }
}

/**
 * R02: E0..E12 and S1..S6 exactly (LAB_SPEC 6.4, including the spec's own worked example R02-r3-001) through the real router. Evidence label: LAB, oracle: self.
 */
class R02Checker : FamilyChecker("R02") {
    override val requiredLaws = setOf("estimate-self", "estimate-peer", "usable-false", "usable-true", "S2-battery", "S3-heat", "S6-stale", "S6-expired", "S5-rank", "saturated")

    override fun observe(v: Vector): Observed {
        val (q, s) = RouterWorld.world(v.input)
        val plan = try {
            MeshRouter().plan(q, s)
        } catch (e: MeshPlanException) {
            return Observed.Reject(e.code)
        }
        val out = buildJsonObject {
            put(
                "attempts",
                buildJsonObject {
                    for (a in plan.attempts.filter { it.tier != Tier.CLOUD }.sortedBy { attemptId8(it) }) {
                        val e = a.estimate!!
                        val sc = a.score!!
                        bump(if (a.tier == Tier.SELF) "estimate-self" else "estimate-peer")
                        bump(if (a.usable) "usable-true" else "usable-false")
                        if (sc.s2Battery > 0) bump("S2-battery")
                        if (sc.s3Heat > 0) bump("S3-heat")
                        if (a.freshness == Freshness.STALE && sc.s6Uncertainty > 0) bump("S6-stale")
                        if (a.freshness == Freshness.EXPIRED && sc.s6Uncertainty > 0) bump("S6-expired")
                        if (sc.s5Quality >= 5_000 && a.file!!.catalogueRank != null) bump("S5-rank")
                        if (e.totalMs == xyz.mdhv.asom.lab.router.Sat.MAX) bump("saturated")
                        put(
                            attemptId8(a),
                            buildJsonObject {
                                put(
                                    "estimate",
                                    buildJsonObject {
                                        put("outTokens", e.outTokens); put("netMs", e.netMs); put("loadMs", e.loadMs); put("queueMs", e.queueMs); put("preEff", e.preEff)
                                        put("prefillMs", e.prefillMs); put("decEff", e.decEff); put("decodeMs", e.decodeMs); put("ttftMs", e.ttftMs); put("totalMs", e.totalMs)
                                        put("energyMilliJ", e.energyMilliJ); put("batteryUsedPermille", e.batteryUsedPermille)
                                    },
                                )
                                put(
                                    "terms",
                                    buildJsonObject {
                                        put("S1", sc.s1Time); put("S2", sc.s2Battery); put("S3", sc.s3Heat); put("S4", sc.s4Locality); put("S5", sc.s5Quality)
                                        put("S6", sc.s6Uncertainty); put("S", sc.total)
                                    },
                                )
                                put("usable", a.usable)
                            },
                        )
                    }
                },
            )
        }
        return Observed.Ok(out)
    }
}
