package xyz.mdhv.asom.desktop

/** A GPU port for a counter that has not yet been shown to be absent: every sample is blind, so a strict governor cannot serve on it. */
object BlindGpuPort : GpuContentionPort {
    override fun sample(): GpuSample? = null
}

/**
 * Decides over time whether a host has a GPU own-versus-other counter (HC-R3). One failed probe proves nothing (the counter
 * provider can load after the service starts, an IOAccelerator entry can be briefly empty), so the counter is taken to be
 * ABSENT, and the GPU presence rule off, only after [absentAfterMisses] consecutive misses, and even then the question is
 * asked again every [recheckEveryCalls] calls: an absent verdict is never permanent. Until it is decided the caller hands
 * the governor a [BlindGpuPort], which keeps a strict start from serving.
 */
class GpuCounterExistence(private val absentAfterMisses: Int = 3, private val recheckEveryCalls: Int = 30) {
    private var misses = 0
    private var callsSinceVerdict = 0

    val absent: Boolean get() = misses >= absentAfterMisses

    /** True when the host should be asked now; while the verdict is ABSENT it is true once every [recheckEveryCalls] calls. */
    fun shouldProbe(): Boolean {
        if (!absent) return true
        if (++callsSinceVerdict < recheckEveryCalls) return false
        callsSinceVerdict = 0
        return true
    }

    fun found() {
        misses = 0
        callsSinceVerdict = 0
    }

    fun missed() {
        if (misses < absentAfterMisses) misses++
    }
}
