package xyz.mdhv.asom.lab.bench

import java.security.MessageDigest
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs

/** Which tiers a form runs: `auto` always, `optIn` only when the user ticked it on the consent sheet, `fallback` when `auto[0]` does not fit. */
data class TierPolicy(val auto: List<String>, val optIn: List<String> = emptyList(), val fallback: String? = null)

data class SustainCfg(val capMsMobile: Long, val capMsDesktop: Long, val windowMs: Long, val chunkTokens: Long, val promptTokens: Long)

data class CoolDownCfg(val pollMs: Long, val maxWaitMsMobile: Long, val maxWaitMsDesktop: Long, val maxWaitMsBeforeSustain: Long)

/**
 * A run plan: data compiled into the core (benchmark.md 5.2). A document records the plan id and the SHA-256 of the plan's JCS
 * bytes, so a subscriber knows exactly which protocol produced it. `standard` is the spec's own JSON; `quick` and `ci` are built from
 * the 5.1 table with the `standard` values where the table is silent (PROVISIONAL, ERRATA ERR-BENCH-5). `sustained`, `battery`
 * and `extended` are deferred (design B24, B31).
 */
data class RunPlan(
    val plan: String,
    val planVersion: Int,
    val reps: Int,
    val depthReps: Int,
    val warmupReps: Int,
    val interRepMs: Long,
    val tiers: Map<String, TierPolicy>,
    val headlineTests: List<String>,
    val headlineTestsDesktop: List<String>,
    val otherTests: List<String>,
    val sustain: SustainCfg?,
    val coolDown: CoolDownCfg,
    val chargerRequiredForms: List<String>,
    val minBatteryPermille: Long,
    val wallCapMs: Map<String, Long>,
) {
    fun toJson(): JValue = jo(
        "plan" to js(plan), "planVersion" to ji(planVersion), "reps" to ji(reps), "depthReps" to ji(depthReps), "warmupReps" to ji(warmupReps),
        "interRepMs" to ji(interRepMs),
        "tiers" to jo(tiers.entries.sortedBy { it.key }.map { (form, p) ->
            form to jo(
                buildList {
                    add("auto" to jsList(p.auto))
                    if (p.optIn.isNotEmpty()) add("optIn" to jsList(p.optIn))
                    if (p.fallback != null) add("fallback" to js(p.fallback))
                },
            )
        }),
        "headlineTests" to jsList(headlineTests), "headlineTestsDesktop" to jsList(headlineTestsDesktop), "otherTests" to jsList(otherTests),
        "sustain" to (sustain?.let {
            jo("capMsMobile" to ji(it.capMsMobile), "capMsDesktop" to ji(it.capMsDesktop), "windowMs" to ji(it.windowMs), "chunkTokens" to ji(it.chunkTokens), "promptTokens" to ji(it.promptTokens))
        } ?: JNull),
        "coolDown" to jo(
            "pollMs" to ji(coolDown.pollMs), "maxWaitMsMobile" to ji(coolDown.maxWaitMsMobile), "maxWaitMsDesktop" to ji(coolDown.maxWaitMsDesktop),
            "maxWaitMsBeforeSustain" to ji(coolDown.maxWaitMsBeforeSustain),
        ),
        "charger" to jo(chargerRequiredForms.sorted().map { it to js("required") }),
        "minBatteryPermille" to ji(minBatteryPermille),
        "wallCapMs" to jo(wallCapMs.entries.sortedBy { it.key }.map { it.key to ji(it.value) }),
    )

    fun jcsBytes(): ByteArray = Jcs.serialize(toJson())

    /** base64url(SHA-256(JCS(plan))), the `harness.planSha256` a document records. */
    fun sha256B64u(): String = Base64Strict.encodeUrlNoPad(MessageDigest.getInstance("SHA-256").digest(jcsBytes()))

    fun isMobile(form: String): Boolean = form == "phone" || form == "tablet" || form == "handheld"

    fun testsFor(headline: Boolean, form: String): List<String> = when {
        !headline -> otherTests
        form == "desktop" || form == "server" || form == "laptop" || form == "handheld" -> headlineTests + headlineTestsDesktop
        else -> headlineTests
    }
}

object RunPlans {
    private val allForms = listOf("phone", "tablet", "handheld", "laptop", "desktop", "server")
    private val desktopTiers = TierPolicy(listOf("T2", "T3", "T4"))

    val STANDARD: RunPlan = RunPlan(
        "standard", 1, reps = 5, depthReps = 3, warmupReps = 1, interRepMs = 500,
        tiers = mapOf(
            "phone" to TierPolicy(listOf("T1", "T2"), listOf("T3")), "tablet" to TierPolicy(listOf("T1", "T2"), listOf("T3")),
            "handheld" to desktopTiers, "laptop" to desktopTiers, "desktop" to desktopTiers, "server" to desktopTiers,
        ),
        headlineTests = listOf("load", "pp512@d0", "tg128@d0", "tg128@d2048", "nll1024"), headlineTestsDesktop = listOf("pp2048@d0"),
        otherTests = listOf("load", "pp512@d0", "tg128@d0", "nll1024"),
        sustain = SustainCfg(600_000L, 900_000L, 15_000L, 256L, 64L),
        coolDown = CoolDownCfg(5_000L, 180_000L, 120_000L, 600_000L),
        chargerRequiredForms = listOf("phone", "tablet", "handheld", "laptop"), minBatteryPermille = 500L,
        wallCapMs = mapOf("phone" to 2_100_000L, "tablet" to 2_100_000L, "handheld" to 2_400_000L, "laptop" to 2_700_000L, "desktop" to 2_700_000L, "server" to 2_700_000L),
    )

    val QUICK: RunPlan = RunPlan(
        "quick", 1, reps = 3, depthReps = 3, warmupReps = 1, interRepMs = 500,
        tiers = allForms.associateWith { if (it == "phone" || it == "tablet") TierPolicy(listOf("T1"), fallback = "T0") else TierPolicy(listOf("T1")) },
        headlineTests = listOf("load", "pp512@d0", "tg128@d0", "nll1024"), headlineTestsDesktop = emptyList(), otherTests = emptyList(),
        sustain = null, coolDown = CoolDownCfg(5_000L, 180_000L, 120_000L, 600_000L),
        chargerRequiredForms = emptyList(), minBatteryPermille = 300L, wallCapMs = emptyMap(),
    )

    val CI: RunPlan = RunPlan(
        "ci", 1, reps = 3, depthReps = 3, warmupReps = 1, interRepMs = 500,
        tiers = allForms.associateWith { TierPolicy(listOf("T0")) },
        headlineTests = listOf("pp512@d0", "tg128@d0", "nll1024"), headlineTestsDesktop = emptyList(), otherTests = emptyList(),
        sustain = null, coolDown = CoolDownCfg(5_000L, 180_000L, 120_000L, 600_000L),
        chargerRequiredForms = emptyList(), minBatteryPermille = 0L, wallCapMs = emptyMap(),
    )

    fun byId(id: String): RunPlan? = when (id) {
        "standard" -> STANDARD
        "quick" -> QUICK
        "ci" -> CI
        else -> null
    }

    val all: List<RunPlan> = listOf(QUICK, STANDARD, CI)
}
