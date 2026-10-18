package xyz.mdhv.asom.desktop.linux.probes

import xyz.mdhv.asom.desktop.GpuContentionPort
import xyz.mdhv.asom.desktop.GpuSample
import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.SystemMonotonicClock

enum class GpuVendor { AMD, NVIDIA, INTEL, OTHER }

data class DrmCard(val name: String, val vendor: GpuVendor, val pciSlot: String?, val hasBusyCounter: Boolean)

/** One DRM client seen in `/proc/<pid>/fdinfo/<fd>`. The same client can appear on several (duplicated) fds. */
data class DrmClient(val driver: String, val pdev: String?, val clientId: String, val gfxNs: Long)

/**
 * Other-busy GPU permille = amdgpu `gpu_busy_percent` (device-wide, [LF18]) minus this process's own busy, from
 * the `drm-engine-gfx` deltas of every fdinfo file under `/proc/self/fdinfo` ([LF19]). PRESENCE input (LP-0), never on the wire.
 *
 * Only amdgpu has a device-wide counter that can be reconciled with fdinfo. NVIDIA has no own-versus-other attribution
 * and Intel's is an unverified assumption, so for them [GpuProbe.find] returns null: the rule is off and `asom doctor`
 * says so (design 3.2 item 5). Unverified on SteamOS (LA04); compute-queue attribution is unverified too (a compute
 * engine's time is NOT subtracted, so a node computing on the compute queue would over-report others and drain itself,
 * the conservative direction: desktop/ERRATA.md ERR-GPU-1).
 */
class GpuProbe(
    private val fs: FileSource,
    private val card: DrmCard,
    private val clock: MonotonicClock = SystemMonotonicClock,
) : GpuContentionPort {
    private var prevOwn: Map<String, Long>? = null
    private var prevAtMs: Long? = null

    override fun sample(): GpuSample? {
        val busyPercent = fs.read("/sys/class/drm/${card.name}/device/gpu_busy_percent").asIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val device = busyPercent * 10
        val now = clock.nowMs()
        val cur = ownGfxNsByClient()
        val pOwn = prevOwn
        val pAt = prevAtMs
        prevOwn = cur
        prevAtMs = now
        if (pOwn == null || pAt == null || now <= pAt) return null
        val elapsedNs = (now - pAt) * 1_000_000L
        // A client first seen in this window counts zero: its start time is unknown, and under-subtracting own busy
        // only ever makes the node drain sooner.
        var deltaNs = 0L
        for ((id, ns) in cur) {
            val before = pOwn[id] ?: continue
            if (ns >= before) deltaNs += ns - before
        }
        val own = (deltaNs * 1000 / elapsedNs).coerceIn(0, 1000).toInt()
        return GpuSample(deviceBusyPermille = device, ownBusyPermille = own, otherBusyPermille = (device - own).coerceAtLeast(0))
    }

    private fun ownGfxNsByClient(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (fd in fs.list("/proc/self/fdinfo")) {
            val c = parseFdinfo(fs.read("/proc/self/fdinfo/$fd") ?: continue) ?: continue
            if (c.driver != "amdgpu" || (card.pciSlot != null && c.pdev != card.pciSlot)) continue
            out[c.pdev + "/" + c.clientId] = c.gfxNs // dedupe: duplicated fds show the same client
        }
        return out
    }

    companion object {
        /** First AMD card with a readable busy counter, else null (rule off). */
        fun find(fs: FileSource, clock: MonotonicClock = SystemMonotonicClock): GpuProbe? =
            cards(fs).firstOrNull { it.vendor == GpuVendor.AMD && it.hasBusyCounter }?.let { GpuProbe(fs, it, clock) }

        fun cards(fs: FileSource): List<DrmCard> =
            fs.list("/sys/class/drm").filter { Regex("card\\d+").matches(it) }.map { name ->
                val d = "/sys/class/drm/$name/device"
                val vendor = when (fs.read("$d/vendor")?.trim()?.lowercase()) {
                    "0x1002" -> GpuVendor.AMD
                    "0x10de" -> GpuVendor.NVIDIA
                    "0x8086" -> GpuVendor.INTEL
                    else -> GpuVendor.OTHER
                }
                val slot = fs.read("$d/uevent")?.lineSequence()?.firstOrNull { it.startsWith("PCI_SLOT_NAME=") }?.substringAfter('=')?.trim()
                DrmCard(name, vendor, slot, fs.read("$d/gpu_busy_percent").asIntOrNull() != null)
            }

        /** Pure. Null if the fd is not a DRM client (no `drm-driver` key). */
        fun parseFdinfo(text: String): DrmClient? {
            var driver: String? = null
            var pdev: String? = null
            var client: String? = null
            var gfx = 0L
            for (line in text.lineSequence()) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon)
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "drm-driver" -> driver = value
                    "drm-pdev" -> pdev = value
                    "drm-client-id" -> client = value
                    "drm-engine-gfx" -> gfx = value.substringBefore(' ').toLongOrNull()?.takeIf { it >= 0 } ?: 0L
                }
            }
            if (driver == null || client == null) return null
            return DrmClient(driver, pdev, client, gfx)
        }
    }
}
