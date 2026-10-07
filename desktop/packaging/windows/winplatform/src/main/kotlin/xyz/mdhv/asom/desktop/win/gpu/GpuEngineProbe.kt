package xyz.mdhv.asom.desktop.win.gpu

import xyz.mdhv.asom.desktop.GpuContentionPort
import xyz.mdhv.asom.desktop.GpuSample
import xyz.mdhv.asom.desktop.win.api.CounterValue
import xyz.mdhv.asom.desktop.win.api.PdhApi
import xyz.mdhv.asom.desktop.win.api.PdhCounterSet

/**
 * The GPU-contention input from the PDH counters `\GPU Engine(*)\Utilization Percentage` (windows.md 2.1, AW05 unverified
 * until spike S-W4). Instances are named `pid_<n>_luid_0x<hi>_0x<lo>_phys_<p>_eng_<e>_engtype_<type>`.
 *
 * Per engine: total = the sum over processes (capped at 100 %), own = this process's share, other = total - own. The device
 * figure is the BUSIEST engine of any adapter, and the same for own and other. That over-reports other-process load when
 * a second, idle-for-us engine is busy with someone else (a video-decode engine, say), which drains earlier than
 * needed: the conservative direction (windows ERRATA WIN-GPU-1). A node that computes on a compute engine is counted as
 * own; if the counters mis-attribute it, it over-reports others and drains itself (as ERR-GPU-1 on Linux).
 *
 * The counter is a rate: the first collection is null, an empty instance list is null, and null is never read as zero.
 */
class GpuEngineProbe private constructor(private val counters: PdhCounterSet, private val ownPid: Long) : GpuContentionPort, AutoCloseable {
    override fun sample(): GpuSample? = try {
        counters.collect()?.let { aggregate(it, ownPid) }
    } catch (_: Exception) {
        null
    }

    override fun close() = counters.close()

    companion object {
        const val COUNTER_PATH = "\\GPU Engine(*)\\Utilization Percentage"
        private val INSTANCE = Regex("^pid_(\\d+)_luid_0x([0-9a-fA-F]+)_0x([0-9a-fA-F]+)_phys_(\\d+)_eng_(\\d+)_engtype_(.+)$")

        /** Null when the counters do not exist on this machine (the rule is then off and `asom doctor` says so). */
        fun create(pdh: PdhApi, ownPid: Long = ProcessHandle.current().pid()): GpuEngineProbe? = try {
            pdh.open(COUNTER_PATH)?.let { GpuEngineProbe(it, ownPid) }
        } catch (_: Exception) {
            null
        }

        fun aggregate(values: List<CounterValue>, ownPid: Long): GpuSample? {
            val totals = HashMap<String, Double>()
            val owns = HashMap<String, Double>()
            var parsed = 0
            for (v in values) {
                val m = INSTANCE.matchEntire(v.instance) ?: continue
                if (v.value.isNaN() || v.value < 0.0) continue
                parsed++
                val engine = "${m.groupValues[2]}_${m.groupValues[3]}_${m.groupValues[4]}_${m.groupValues[5]}_${m.groupValues[6]}"
                totals.merge(engine, v.value, Double::plus)
                if (m.groupValues[1].toLongOrNull() == ownPid) owns.merge(engine, v.value, Double::plus)
            }
            if (parsed == 0) return null
            var device = 0
            var own = 0
            var other = 0
            for ((engine, total) in totals) {
                val t = total.coerceAtMost(100.0)
                val o = (owns[engine] ?: 0.0).coerceAtMost(t)
                device = maxOf(device, toPermille(t))
                own = maxOf(own, toPermille(o))
                other = maxOf(other, toPermille((t - o).coerceAtLeast(0.0)))
            }
            return GpuSample(deviceBusyPermille = device, ownBusyPermille = own, otherBusyPermille = other)
        }

        private fun toPermille(percent: Double): Int = Math.round(percent * 10.0).toInt().coerceIn(0, 1000)
    }
}
