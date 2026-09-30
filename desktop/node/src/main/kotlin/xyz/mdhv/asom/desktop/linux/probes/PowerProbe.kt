package xyz.mdhv.asom.desktop.linux.probes

import xyz.mdhv.asom.desktop.BatteryBand
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.PowerSource

/** One `/sys/class/power_supply/<name>` directory. */
data class PowerSupply(
    val name: String,
    val type: String?,
    val online: Int?,
    val status: String?,
    val capacity: Int?,
    val scope: String?,
)

/**
 * Power source and battery (linux.md 3.4). Enumerates by `type`, never by name (`BAT1` on the Deck is not portable).
 * `Mains` or `USB` with `online=1` is AC; peripheral batteries (`scope=Device`: mice, headsets) are ignored because
 * they say nothing about the machine's own power.
 */
class PowerProbe(private val fs: FileSource, private val root: String = "/sys/class/power_supply") {
    fun read(): PowerReading = reduce(supplies())

    fun supplies(): List<PowerSupply> = fs.list(root).map { name ->
        val d = "$root/$name"
        PowerSupply(
            name = name,
            type = fs.read("$d/type")?.trim(),
            online = fs.read("$d/online").asIntOrNull(),
            status = fs.read("$d/status")?.trim(),
            capacity = fs.read("$d/capacity").asIntOrNull()?.takeIf { it in 0..100 },
            scope = fs.read("$d/scope")?.trim(),
        )
    }

    companion object {
        private val AC_TYPES = setOf("Mains", "USB")

        /** Pure. */
        fun reduce(all: List<PowerSupply>): PowerReading {
            val own = all.filter { !it.scope.equals("Device", ignoreCase = true) }
            val batteries = own.filter { it.type == "Battery" }
            val acOnline = own.any { it.type in AC_TYPES && it.online == 1 }
            val hasBattery = batteries.isNotEmpty()
            val source = when {
                acOnline -> PowerSource.AC
                hasBattery -> PowerSource.BATTERY
                else -> PowerSource.AC // a machine with no battery is on mains ("ac"), per linux.md 3.4
            }
            val charging = batteries.any { it.status.equals("Charging", ignoreCase = true) }
            // The lowest capacity among the machine's own batteries: the conservative band.
            val percent = batteries.mapNotNull { it.capacity }.minOrNull()
            return PowerReading(
                source = source,
                charging = charging,
                hasBattery = hasBattery,
                batteryPercent = percent,
                batteryBand = percent?.let { BatteryBand.ofPercent(it) },
                saver = null,
            )
        }
    }
}
