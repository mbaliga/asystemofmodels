package xyz.mdhv.asom.routing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Durable backing for the latency EWMA (§7: "persisted per provider").
 * Implemented by the Android layer; this module stays pure JVM, so the store
 * is injected rather than imported.
 *
 * [save] is called from the request path — implementations must hand the map
 * off (coroutine/executor) instead of blocking on I/O.
 */
fun interface LatencyStore {
    fun save(values: Map<String, Double>)

    companion object {
        /** Desktop/test default: the EWMA stays process-local. */
        val NONE: LatencyStore = LatencyStore { }
    }
}

/**
 * Per-provider latency EWMA (brief §7, `fastest`). State is restored with
 * [preload] at startup and written back through [store]; [flush] forces a
 * write on service stop. Writes are debounced to [persistIntervalMs] because
 * [record] runs on every routed request.
 */
class LatencyTracker(
    private val alpha: Double = 0.3,
    private val store: LatencyStore = LatencyStore.NONE,
    private val persistIntervalMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val values = ConcurrentHashMap<String, Double>()
    private val lastPersistMs = AtomicLong(NEVER)

    fun record(providerId: String, latencyMs: Long) {
        values.merge(providerId, latencyMs.toDouble()) { prev, sample ->
            alpha * sample + (1 - alpha) * prev
        }
        persistIfDue()
    }

    fun ewma(providerId: String): Double? = values[providerId]

    fun snapshot(): Map<String, Double> = values.toMap()

    fun preload(persisted: Map<String, Double>) {
        values.putAll(persisted)
    }

    /** Write-through regardless of the debounce (service stop, teardown). */
    fun flush() {
        lastPersistMs.set(clock())
        store.save(snapshot())
    }

    private fun persistIfDue() {
        val now = clock()
        val last = lastPersistMs.get()
        if (last != NEVER && now - last < persistIntervalMs) return
        if (!lastPersistMs.compareAndSet(last, now)) return
        store.save(snapshot())
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
