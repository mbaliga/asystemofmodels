package xyz.mdhv.asom.lab.proto.integration

/** The listener limits of trust.md 3.3 ("Limits (defaults, per peer)"): 8 unauthenticated connections in flight per listener, 10 handshakes per minute per source address. */
object HandshakeLimits {
    const val MAX_IN_FLIGHT: Int = 8
    const val PER_SOURCE_PER_MINUTE: Int = 10
    const val WINDOW_MS: Long = 60_000L
}

sealed interface Admission {
    /** The connection may start its handshake. [release] must be called exactly once when the handshake ended, whatever the outcome. */
    class Admitted(private val onRelease: () -> Unit) : Admission {
        private var released = false

        @Synchronized
        fun release() {
            if (!released) {
                released = true
                onRelease()
            }
        }
    }

    /** The connection is closed before any TLS byte is read and counted in the one `INBOUND_REFUSED` row per 10 minutes (LAB_SPEC 7.6); it is never a session. */
    class Refused(val reason: Why) : Admission

    enum class Why { GLOBAL_IN_FLIGHT, PER_SOURCE_RATE }
}

/**
 * Admission control for inbound connections, before the handshake (trust.md 5.1: "accept; under per-source and global limits"). It is a pure object: the host
 * calls [admit] with the source address (no port) as soon as it accepted a socket and calls `release` on the ticket when the handshake finished or failed.
 * The clock is injected. A refused connection does not count towards its source's window (so refusals cannot extend a lock-out); an admitted one counts
 * whatever its handshake does. The set of tracked sources is pruned at every call, so it cannot grow without bound.
 */
class HandshakeLimiter(
    private val clock: () -> Long,
    private val maxInFlight: Int = HandshakeLimits.MAX_IN_FLIGHT,
    private val perSourcePerWindow: Int = HandshakeLimits.PER_SOURCE_PER_MINUTE,
    private val windowMs: Long = HandshakeLimits.WINDOW_MS,
) {
    private val bySource = HashMap<String, ArrayDeque<Long>>()
    private var inFlight = 0

    @get:Synchronized
    val inFlightNow: Int get() = inFlight

    @get:Synchronized
    val trackedSources: Int get() = bySource.size

    @Synchronized
    fun admit(source: String): Admission {
        val now = clock()
        prune(now)
        if (inFlight >= maxInFlight) return Admission.Refused(Admission.Why.GLOBAL_IN_FLIGHT)
        val window = bySource.getOrPut(source) { ArrayDeque() }
        if (window.size >= perSourcePerWindow) return Admission.Refused(Admission.Why.PER_SOURCE_RATE)
        window.addLast(now)
        inFlight++
        return Admission.Admitted { release() }
    }

    @Synchronized
    private fun release() {
        check(inFlight > 0) { "a ticket was released more often than it was admitted" }
        inFlight--
    }

    private fun prune(now: Long) {
        val it = bySource.entries.iterator()
        while (it.hasNext()) {
            val q = it.next().value
            while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
            if (q.isEmpty()) it.remove()
        }
    }
}
