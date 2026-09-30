package xyz.mdhv.asom.desktop.linux.probes

data class MemoryReading(
    val totalBytes: Long?,
    val availableBytes: Long?,
    /** PSI `full avg10` in hundredths of a percent (500 = 5%); null where /proc/pressure/memory is absent. */
    val psiFullAvg10Centi: Int?,
)

/**
 * `/proc/meminfo` `MemAvailable` and `/proc/pressure/memory` `full avg10` (linux.md 3.4). Drain rule (the governor's):
 * PSI memory `full avg10` above 5% for 10 s. The RAM guard (v2 P2) admits a model only if
 * `modelBytes + kvBudget <= MemAvailable - max(1.5 GiB, 10% of RAM)`; that arithmetic lives here as [RamGuard].
 */
class MemoryProbe(private val fs: FileSource) {
    fun read(): MemoryReading {
        val info = parseMeminfo(fs.read("/proc/meminfo") ?: "")
        val psi = fs.read("/proc/pressure/memory")?.let { parsePsiFullAvg10Centi(it) }
        return MemoryReading(info["MemTotal"], info["MemAvailable"], psi)
    }

    companion object {
        /** Values in bytes (the kernel prints kB). Unparseable lines are skipped. */
        fun parseMeminfo(text: String): Map<String, Long> {
            val out = HashMap<String, Long>()
            for (line in text.lineSequence()) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val parts = line.substring(colon + 1).trim().split(Regex("\\s+"))
                val n = parts.firstOrNull()?.toLongOrNull() ?: continue
                if (n < 0) continue
                val unit = parts.getOrNull(1)
                val bytes = when (unit) {
                    "kB" -> n * 1024
                    null -> n
                    else -> continue
                }
                out[line.substring(0, colon).trim()] = bytes
            }
            return out
        }

        /** The `full` line's `avg10=` field; the `some` line is ignored. */
        fun parsePsiFullAvg10Centi(text: String): Int? = parsePsi(text, "full")

        fun parsePsi(text: String, kind: String): Int? {
            for (line in text.lineSequence()) {
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.firstOrNull() != kind) continue
                val avg = fields.firstOrNull { it.startsWith("avg10=") } ?: return null
                return parseCenti(avg.removePrefix("avg10="))
            }
            return null
        }
    }
}

object RamGuard {
    const val MIN_RESERVE_BYTES = 1_610_612_736L // 1.5 GiB

    /** Bytes a model plus its KV cache may occupy, or null if MemAvailable or MemTotal is unknown (then nothing is admitted). */
    fun headroomBytes(m: MemoryReading): Long? {
        val avail = m.availableBytes ?: return null
        val total = m.totalBytes ?: return null
        return avail - maxOf(MIN_RESERVE_BYTES, total / 10)
    }

    fun admits(m: MemoryReading, modelBytes: Long, kvBudgetBytes: Long): Boolean {
        val room = headroomBytes(m) ?: return false
        return modelBytes >= 0 && kvBudgetBytes >= 0 && modelBytes + kvBudgetBytes <= room
    }
}
