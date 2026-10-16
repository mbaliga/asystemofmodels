package xyz.mdhv.asom.desktop.mac.gpu

import xyz.mdhv.asom.desktop.GpuContentionPort
import xyz.mdhv.asom.desktop.GpuSample
import xyz.mdhv.asom.desktop.mac.helper.HelperClient
import xyz.mdhv.asom.desktop.mac.helper.HelperUnavailable

/** The fraction of the sampling window in which the engine had a Metal command buffer in flight, in permille. */
fun interface OwnGpuBusy {
    fun permille(): Int
}

/** No engine runs in this wave (`NoopEngine`), so the node's own GPU load is truly zero. */
object NoEngineBusy : OwnGpuBusy {
    override fun permille(): Int = 0
}

/**
 * GPU contention on macOS (macos.md 2.1, assumption AM12): the `IOAccelerator` device utilisation from the helper, minus the
 * node's own in-flight fraction, is the "other busy" permille. There is no per-process GPU accounting without root, so this
 * is an ESTIMATE that can over-report (another process's video decode counts) and so drain earlier than needed, which is the
 * conservative direction; it can also be late to yield to a GPU-heavy app (mac ERRATA MAC-GPU-1, risk RM15).
 * The rule thresholds (200 permille for 60 s eligible, 400 permille for 10 s drain) are `NodeConfig`'s. A tick whose counter
 * cannot be read is `null`, never zero.
 */
class MacGpuProbe(
    private val client: HelperClient,
    private val own: OwnGpuBusy = NoEngineBusy,
) : GpuContentionPort {
    override fun sample(): GpuSample? = try {
        val device = client.gpuUtilPermille().coerceIn(0, 1000)
        val mine = own.permille().coerceIn(0, 1000)
        GpuSample(device, mine, (device - mine).coerceAtLeast(0))
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * True when the counter exists, false when the helper says it does not (the rule is then off and `asom doctor` says so),
         * null when that could not be decided (a helper that is not reachable).
         */
        fun counterExists(client: HelperClient): Boolean? = try {
            client.gpuUtilPermille()
            true
        } catch (_: HelperUnavailable) {
            false
        } catch (_: Exception) {
            null
        }
    }
}
