package xyz.mdhv.asom.desktop.linux.probes

import xyz.mdhv.asom.desktop.PresencePort
import xyz.mdhv.asom.desktop.PresenceSample

/** Aggregate CPU times in clock ticks (USER_HZ); `busy` excludes idle and iowait, and guest time (already inside user). */
data class CpuTimes(val busy: Long, val total: Long)

/**
 * Other processes' CPU busy = `/proc/stat` minus this process (`/proc/self/stat`), in permille of ALL CPU capacity since
 * the previous sample (linux.md 3.4: "others' CPU busy > 400 permille for 10 s drains"). The first call has no previous
 * sample and reports null. This is a PRESENCE input (LP-0): it is never put on the wire.
 */
class CpuProbe(private val fs: FileSource) : PresencePort {
    private var prevTotal: CpuTimes? = null
    private var prevOwn: Long? = null

    override fun sample(): PresenceSample {
        val total = parseStat(fs.read("/proc/stat") ?: "")
        val own = parseSelfTicks(fs.read("/proc/self/stat") ?: "")
        val psi = fs.read("/proc/pressure/cpu")?.let { MemoryProbe.parsePsi(it, "some") }
        val pt = prevTotal
        val po = prevOwn
        prevTotal = total
        prevOwn = own
        val permille = if (total != null && own != null && pt != null && po != null) otherPermille(pt, total, po, own) else null
        return PresenceSample(permille, psi)
    }

    companion object {
        /** The aggregate `cpu ` line; null if absent or malformed. */
        fun parseStat(text: String): CpuTimes? {
            val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
            val f = line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: return null }
            if (f.size < 8) return null // user nice system idle iowait irq softirq steal
            val idle = f[3] + f[4]
            val busy = f[0] + f[1] + f[2] + f[5] + f[6] + f[7]
            return CpuTimes(busy, busy + idle)
        }

        /** utime + stime (fields 14 and 15) of `/proc/self/stat`; `comm` may contain spaces and parentheses, so split after the LAST ')'. */
        fun parseSelfTicks(text: String): Long? {
            val close = text.lastIndexOf(')')
            if (close < 0) return null
            val rest = text.substring(close + 1).trim().split(Regex("\\s+"))
            // rest[0] is field 3 (state); utime is field 14 -> index 11, stime field 15 -> index 12.
            val u = rest.getOrNull(11)?.toLongOrNull() ?: return null
            val s = rest.getOrNull(12)?.toLongOrNull() ?: return null
            return u + s
        }

        fun otherPermille(prev: CpuTimes, cur: CpuTimes, prevOwn: Long, curOwn: Long): Int? {
            val dTotal = cur.total - prev.total
            if (dTotal <= 0) return null
            val dBusy = cur.busy - prev.busy
            val dOwn = curOwn - prevOwn
            if (dBusy < 0 || dOwn < 0) return null
            val other = (dBusy - dOwn).coerceAtLeast(0)
            return (other * 1000 / dTotal).coerceIn(0, 1000).toInt()
        }
    }
}
