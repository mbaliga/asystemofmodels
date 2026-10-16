package xyz.mdhv.asom.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import xyz.mdhv.asom.desktop.json.StrictJson
import xyz.mdhv.asom.desktop.json.StrictJsonException

class NodeConfigException(message: String) : IllegalArgumentException(message)

/**
 * The node's config (integers only in declared units, C1). Every threshold is PROVISIONAL (calibrated at D-v2,
 * NEEDS-OWNER-VALIDATION). The schema is closed: an unknown key is an error, so a typo cannot silently disable a rule.
 */
data class NodeConfig(
    /** Lending is OFF even when the node runs; only an explicit owner action turns it on. */
    val lending: Boolean = false,
    val overlayInterface: String? = null,
    val confirmedLans: List<String> = emptyList(),
    /** LD-2: Game Mode lending on a Deck, where nothing on the Deck's screen shows it. Off by default. */
    val gameModeLending: Boolean = false,
    val keepAwake: Boolean = true,
    val sampleIntervalMs: Long = 2_000,
    val cpuOtherDrainPermille: Int = 400,
    val cpuOtherDrainHoldMs: Long = 10_000,
    val gpuOtherDrainPermille: Int = 400,
    val gpuOtherDrainHoldMs: Long = 10_000,
    val eligiblePermille: Int = 200,
    val eligibleHoldMs: Long = 60_000,
    /** PSI memory `full avg10`, hundredths of a percent (500 = 5%). */
    val memoryPsiFullDrainCenti: Int = 500,
    val memoryPsiHoldMs: Long = 10_000,
    val thermalHysteresisMilliC: Int = 3_000,
    val thermalDwellMs: Long = 10_000,
    /** Used only when no trip point or crit temperature is readable at all; stated in `asom status`. */
    val thermalNoTripHoldMilliC: Int = 85_000,
    /** LP-2. Cannot be configured below the spec's 600,000 ms. */
    val presenceHoldDownMs: Long = MIN_HOLD_DOWN_MS,
    val graceMsDesktop: Long = 30_000,
    val graceMsDeck: Long = 2_000,
) {
    companion object {
        const val MIN_HOLD_DOWN_MS = 600_000L

        private val KEYS = setOf(
            "lending", "overlayInterface", "confirmedLans", "gameModeLending", "keepAwake", "sampleIntervalMs",
            "cpuOtherDrainPermille", "cpuOtherDrainHoldMs", "gpuOtherDrainPermille", "gpuOtherDrainHoldMs",
            "eligiblePermille", "eligibleHoldMs", "memoryPsiFullDrainCenti", "memoryPsiHoldMs", "thermalHysteresisMilliC",
            "thermalDwellMs", "thermalNoTripHoldMilliC", "presenceHoldDownMs", "graceMsDesktop", "graceMsDeck",
        )

        fun parse(text: String): NodeConfig {
            val root = try {
                StrictJson.parse(text)
            } catch (e: StrictJsonException) {
                throw NodeConfigException("config is not integer-profile JSON: ${e.message}")
            }
            val o = root as? JsonObject ?: throw NodeConfigException("config must be a JSON object")
            val unknown = o.keys - KEYS
            if (unknown.isNotEmpty()) throw NodeConfigException("unknown config key: ${unknown.sorted().first()}")
            val d = NodeConfig()
            val cfg = NodeConfig(
                lending = bool(o, "lending", d.lending),
                overlayInterface = optString(o, "overlayInterface"),
                confirmedLans = stringList(o, "confirmedLans"),
                gameModeLending = bool(o, "gameModeLending", d.gameModeLending),
                keepAwake = bool(o, "keepAwake", d.keepAwake),
                sampleIntervalMs = long(o, "sampleIntervalMs", d.sampleIntervalMs, 100, 3_600_000),
                cpuOtherDrainPermille = int(o, "cpuOtherDrainPermille", d.cpuOtherDrainPermille, 0, 1000),
                cpuOtherDrainHoldMs = long(o, "cpuOtherDrainHoldMs", d.cpuOtherDrainHoldMs, 0, 3_600_000),
                gpuOtherDrainPermille = int(o, "gpuOtherDrainPermille", d.gpuOtherDrainPermille, 0, 1000),
                gpuOtherDrainHoldMs = long(o, "gpuOtherDrainHoldMs", d.gpuOtherDrainHoldMs, 0, 3_600_000),
                eligiblePermille = int(o, "eligiblePermille", d.eligiblePermille, 0, 1000),
                eligibleHoldMs = long(o, "eligibleHoldMs", d.eligibleHoldMs, 0, 3_600_000),
                memoryPsiFullDrainCenti = int(o, "memoryPsiFullDrainCenti", d.memoryPsiFullDrainCenti, 0, 10_000),
                memoryPsiHoldMs = long(o, "memoryPsiHoldMs", d.memoryPsiHoldMs, 0, 3_600_000),
                thermalHysteresisMilliC = int(o, "thermalHysteresisMilliC", d.thermalHysteresisMilliC, 0, 50_000),
                thermalDwellMs = long(o, "thermalDwellMs", d.thermalDwellMs, 0, 3_600_000),
                thermalNoTripHoldMilliC = int(o, "thermalNoTripHoldMilliC", d.thermalNoTripHoldMilliC, 30_000, 150_000),
                presenceHoldDownMs = long(o, "presenceHoldDownMs", d.presenceHoldDownMs, MIN_HOLD_DOWN_MS, 86_400_000),
                graceMsDesktop = long(o, "graceMsDesktop", d.graceMsDesktop, 0, 300_000),
                graceMsDeck = long(o, "graceMsDeck", d.graceMsDeck, 0, 300_000),
            )
            if (cfg.eligiblePermille > cfg.cpuOtherDrainPermille || cfg.eligiblePermille > cfg.gpuOtherDrainPermille) {
                throw NodeConfigException("eligiblePermille must not exceed a drain threshold (no hysteresis band)")
            }
            return cfg
        }

        private fun prim(o: JsonObject, k: String): JsonPrimitive? = when (val v = o[k]) {
            null -> null
            is JsonPrimitive -> v
            else -> throw NodeConfigException("$k has the wrong type")
        }

        private fun bool(o: JsonObject, k: String, def: Boolean): Boolean {
            val p = prim(o, k) ?: return def
            if (p is JsonNull || p.isString) throw NodeConfigException("$k must be true or false")
            return p.booleanOrNull ?: throw NodeConfigException("$k must be true or false")
        }

        private fun optString(o: JsonObject, k: String): String? {
            val p = prim(o, k) ?: return null
            if (p is JsonNull) return null
            if (!p.isString) throw NodeConfigException("$k must be a string or null")
            return p.contentOrNull
        }

        private fun long(o: JsonObject, k: String, def: Long, lo: Long, hi: Long): Long {
            val p = prim(o, k) ?: return def
            if (p is JsonNull || p.isString) throw NodeConfigException("$k must be an integer")
            val n = p.longOrNull ?: throw NodeConfigException("$k must be an integer")
            if (n < lo || n > hi) throw NodeConfigException("$k out of range [$lo, $hi]")
            return n
        }

        private fun int(o: JsonObject, k: String, def: Int, lo: Int, hi: Int): Int = long(o, k, def.toLong(), lo.toLong(), hi.toLong()).toInt()

        private fun stringList(o: JsonObject, k: String): List<String> {
            val v: JsonElement = o[k] ?: return emptyList()
            val arr = v as? JsonArray ?: throw NodeConfigException("$k must be an array of strings")
            return arr.map { e ->
                val p = e as? JsonPrimitive
                if (p == null || p is JsonNull || !p.isString) throw NodeConfigException("$k must be an array of strings")
                p.content
            }
        }
    }
}
