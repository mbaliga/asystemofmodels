package xyz.mdhv.asom.desktop.win.thermal

import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.SystemMonotonicClock
import xyz.mdhv.asom.desktop.ThermalPort
import xyz.mdhv.asom.desktop.ThermalReading
import xyz.mdhv.asom.desktop.win.api.PdhCounterSet

/**
 * Thermal band from `\Thermal Zone Information(*)\Temperature` (Kelvin) (windows.md 6, AW06 unverified until spike S-W5).
 * Windows has no general thermal-state API for applications and the ACPI zones are often absent or static on consumer PCs.
 *
 * - No counter at all: band 0, no watched sensors, [hasSignal] false. A node in that state reports "no thermal source";
 *   the benchmark `standard` and `sustained` plans must refuse to start (`NO_THERMAL_SIGNAL`, B14) and only `quick` may run.
 *   The throughput-decline detector (B7) that the spec names as the fallback is not part of this track.
 * - Counters present: hold (band 2) at or above a threshold, queue (band 1) within 10 C below it. Windows exposes no trip
 *   points here, so the threshold is `NodeConfig.thermalNoTripHoldMilliC` (85 C, PROVISIONAL, as ERR-THERM-2). Raising is
 *   immediate; lowering needs 3 C under the threshold for the dwell (ERR-THERM-1). An implausible value (below -100 C or
 *   above 250 C) is ignored; a dead sensor never lowers the band.
 * - [hasSignal] becomes false when every zone has reported the same value for [STATIC_READS] consecutive reads: a stuck
 *   sensor cannot say the machine is cool. A static value still counts toward the band (a stuck HOT value keeps HOLD).
 */
class ThermalZoneProbe(
    private val counters: PdhCounterSet?,
    private val config: NodeConfig = NodeConfig(),
    private val clock: MonotonicClock = SystemMonotonicClock,
) : ThermalPort {
    private var band = 0
    private var lowerSince: Long? = null
    private var lastSignature: List<Int>? = null
    private var sameReads = 0

    @Volatile
    var hasSignal: Boolean = counters != null
        private set

    override fun read(): ThermalReading {
        val c = counters ?: return ThermalReading(0, null, null, emptyList())
        val zones = try {
            c.collect()
        } catch (_: Exception) {
            null
        }?.mapNotNull { z ->
            val milliC = kelvinToMilliC(z.value) ?: return@mapNotNull null
            z.instance to milliC
        }.orEmpty()
        if (zones.isEmpty()) {
            // Nothing readable this tick: the band stays as it was, and that is not a signal.
            hasSignal = false
            return ThermalReading(band, null, config.thermalNoTripHoldMilliC, emptyList())
        }
        trackStatic(zones.map { it.second })
        val hottest = zones.maxOf { it.second }
        val threshold = config.thermalNoTripHoldMilliC
        val margin = hottest - threshold
        val next = bandForMargin(margin)
        val lowered = bandForMargin(margin + config.thermalHysteresisMilliC)
        val now = clock.nowMs()
        when {
            next > band -> { band = next; lowerSince = null }
            lowered < band -> {
                val since = lowerSince ?: now.also { lowerSince = it }
                if (now - since >= config.thermalDwellMs) { band = lowered; lowerSince = null }
            }
            else -> lowerSince = null
        }
        return ThermalReading(band, hottest, threshold, zones.map { it.first })
    }

    private fun trackStatic(values: List<Int>) {
        if (values == lastSignature) sameReads++ else { sameReads = 0; lastSignature = values }
        hasSignal = sameReads < STATIC_READS
    }

    private fun bandForMargin(margin: Int): Int = when {
        margin >= 0 -> 2
        margin >= -QUEUE_MARGIN_MILLI_C -> 1
        else -> 0
    }

    companion object {
        const val COUNTER_PATH = "\\Thermal Zone Information(*)\\Temperature"
        const val STATIC_READS = 30
        const val QUEUE_MARGIN_MILLI_C = 10_000
        private const val MIN_PLAUSIBLE = -100_000
        private const val MAX_PLAUSIBLE = 250_000

        fun kelvinToMilliC(kelvin: Double): Int? {
            if (kelvin.isNaN() || kelvin <= 0.0) return null
            val milli = Math.round((kelvin - 273.15) * 1000.0).toInt()
            return milli.takeIf { it in MIN_PLAUSIBLE..MAX_PLAUSIBLE }
        }
    }
}
