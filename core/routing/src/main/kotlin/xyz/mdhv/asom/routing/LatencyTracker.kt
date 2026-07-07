package xyz.mdhv.asom.routing

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-provider latency EWMA (brief §7, `fastest`). Persisted by the Android
 * layer via [snapshot]/[preload]; pure in-memory here.
 */
class LatencyTracker(
    private val alpha: Double = 0.3,
) {
    private val values = ConcurrentHashMap<String, Double>()

    fun record(providerId: String, latencyMs: Long) {
        values.merge(providerId, latencyMs.toDouble()) { prev, sample ->
            alpha * sample + (1 - alpha) * prev
        }
    }

    fun ewma(providerId: String): Double? = values[providerId]

    fun snapshot(): Map<String, Double> = values.toMap()

    fun preload(persisted: Map<String, Double>) {
        values.putAll(persisted)
    }
}
