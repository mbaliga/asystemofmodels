package xyz.mdhv.asom.desktop.mac.thermal

import xyz.mdhv.asom.desktop.ThermalPort
import xyz.mdhv.asom.desktop.ThermalReading
import xyz.mdhv.asom.desktop.mac.helper.HelperClient

/**
 * `ProcessInfo.thermalState` to the seam's band (macos.md 2.1, PROVISIONAL for `asom.state/1`): `nominal` is band 0, `fair` band 1,
 * `serious` and `critical` band 2. The governor runs at band 0, queues at band 1 and holds at band 2.
 *
 * The four-level signal is coarse and comes from the operating system already smoothed, so no hysteresis is added here, and
 * it is recorded as coarse: there is no temperature (`hottestMilliC` is null), so no status line shows a degree that was not
 * measured. A state that cannot be read, or that this code does not know, is band 2: unknown heat is the unsafe answer
 * (mac ERRATA MAC-THERM-1). On a Mac, `thermalState` exists on every machine [FM39], so benchmark plans that need a thermal
 * signal are satisfied.
 */
class MacThermalPort(private val client: HelperClient) : ThermalPort {
    @Volatile
    var lastState: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    override fun read(): ThermalReading {
        val band = try {
            val state = client.thermal()
            lastState = state
            lastError = null
            bandOf(state)
        } catch (e: Exception) {
            lastError = e.message ?: e::class.simpleName
            HOLD_BAND
        }
        return ThermalReading(band, null, null, WATCHED)
    }

    companion object {
        const val HOLD_BAND = 2
        val WATCHED = listOf("ProcessInfo.thermalState (coarse, four levels)")

        fun bandOf(state: String): Int = when (state) {
            "nominal" -> 0
            "fair" -> 1
            "serious", "critical" -> HOLD_BAND
            else -> HOLD_BAND
        }
    }
}
