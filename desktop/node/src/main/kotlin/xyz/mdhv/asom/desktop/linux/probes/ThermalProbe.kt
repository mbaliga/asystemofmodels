package xyz.mdhv.asom.desktop.linux.probes

import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.SystemMonotonicClock
import xyz.mdhv.asom.desktop.ThermalPort
import xyz.mdhv.asom.desktop.ThermalReading

/** A watched sensor: hwmon chip `name` plus the `temp*_label`. Matches are exact and case-sensitive. */
data class HwmonTemp(val chip: String, val label: String?, val milliC: Int, val critMilliC: Int?) {
    val id: String get() = if (label == null) chip else "$chip $label"
}

data class ThermalTrips(val lowestPassiveMilliC: Int?, val lowestCriticalMilliC: Int?)

/**
 * Thermal band 0/1/2 (linux.md 3.4): band 2 (HOLD) when any watched sensor is at or above
 * `min(lowest passive trip - 5 C, crit - 15 C)`, band 1 (QUEUE) within 10 C below that, band 0 otherwise.
 * Raising the band is immediate; lowering needs the temperature to fall [hysteresisMilliC] under the threshold and stay
 * there for [dwellMs] (see desktop/ERRATA.md ERR-THERM-1 for why the dwell is not applied to raising).
 * Sensor labels vary by board: a machine whose hottest part has no watched sensor is not seen (stated in `asom doctor`).
 */
class ThermalProbe(
    private val fs: FileSource,
    private val hysteresisMilliC: Int = 3_000,
    private val dwellMs: Long = 10_000,
    private val noTripHoldMilliC: Int = 85_000,
    private val clock: MonotonicClock = SystemMonotonicClock,
) : ThermalPort {
    private var band = 0
    private var lowerSince: Long? = null

    override fun read(): ThermalReading {
        val temps = watched(hwmon())
        val trips = trips()
        // Each watched sensor has its own hold threshold; the band follows the sensor closest to (or past) its threshold.
        val margins = temps.map { it to it.milliC - holdThreshold(it, trips) }
        val worst = margins.maxByOrNull { it.second }
        if (worst != null) {
            val next = bandForMargin(worst.second)
            val lowered = bandForMargin(worst.second + hysteresisMilliC)
            val now = clock.nowMs()
            when {
                next > band -> { band = next; lowerSince = null }
                lowered < band -> {
                    val since = lowerSince ?: now.also { lowerSince = it }
                    if (now - since >= dwellMs) { band = lowered; lowerSince = null }
                }
                else -> lowerSince = null
            }
        } // no readable watched sensor: the band is left as it was (a dead sensor must not lower it)
        return ThermalReading(band, temps.maxOfOrNull { it.milliC }, worst?.let { holdThreshold(it.first, trips) }, temps.map { it.id })
    }

    fun hwmon(): List<HwmonTemp> {
        val out = ArrayList<HwmonTemp>()
        for (chipDir in fs.list(HWMON).filter { it.startsWith("hwmon") }) {
            val d = "$HWMON/$chipDir"
            val chip = fs.read("$d/name")?.trim() ?: continue
            for (f in fs.list(d)) {
                val m = INPUT.matchEntire(f) ?: continue
                val n = m.groupValues[1]
                val v = fs.read("$d/$f").asIntOrNull() ?: continue
                if (v < MIN_PLAUSIBLE || v > MAX_PLAUSIBLE) continue
                out += HwmonTemp(chip, fs.read("$d/temp${n}_label")?.trim(), v, fs.read("$d/temp${n}_crit").asIntOrNull())
            }
        }
        return out
    }

    fun trips(): ThermalTrips {
        var passive: Int? = null
        var critical: Int? = null
        for (z in fs.list(ZONES).filter { it.startsWith("thermal_zone") }) {
            val d = "$ZONES/$z"
            for (f in fs.list(d)) {
                val m = TRIP_TYPE.matchEntire(f) ?: continue
                val temp = fs.read("$d/trip_point_${m.groupValues[1]}_temp").asIntOrNull() ?: continue
                if (temp <= 0) continue
                when (fs.read("$d/$f")?.trim()) {
                    "passive" -> passive = minOf(passive ?: temp, temp)
                    "critical", "crit" -> critical = minOf(critical ?: temp, temp)
                }
            }
        }
        return ThermalTrips(passive, critical)
    }

    private fun watched(all: List<HwmonTemp>): List<HwmonTemp> = all.filter { t -> WATCHED.any { it == t.chip to t.label } }

    /**
     * `min(lowest passive trip - 5 C, crit - 15 C)`: `crit` is the sensor's own `temp*_crit` when it has one, else the lowest
     * `critical` trip of any thermal zone. With no trip data at all the configured fallback applies (ERR-THERM-2).
     */
    private fun holdThreshold(sensor: HwmonTemp, trips: ThermalTrips): Int {
        val candidates = ArrayList<Int>(2)
        trips.lowestPassiveMilliC?.let { candidates += it - 5_000 }
        (sensor.critMilliC ?: trips.lowestCriticalMilliC)?.let { candidates += it - 15_000 }
        return if (candidates.isEmpty()) noTripHoldMilliC else candidates.min()
    }

    private fun bandForMargin(marginMilliC: Int): Int = when {
        marginMilliC >= 0 -> 2
        marginMilliC >= -QUEUE_MARGIN_MILLI_C -> 1
        else -> 0
    }

    companion object {
        const val HWMON = "/sys/class/hwmon"
        const val ZONES = "/sys/class/thermal"
        const val QUEUE_MARGIN_MILLI_C = 10_000
        private const val MIN_PLAUSIBLE = -100_000
        private const val MAX_PLAUSIBLE = 250_000
        private val INPUT = Regex("temp(\\d+)_input")
        private val TRIP_TYPE = Regex("trip_point_(\\d+)_type")

        val WATCHED: List<Pair<String, String?>> = listOf(
            "amdgpu" to "edge", "k10temp" to "Tctl", "coretemp" to "Package id 0", "nvme" to "Composite",
        )
    }
}
