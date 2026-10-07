package xyz.mdhv.asom.lab.proto.integration

/**
 * The listener limits of trust.md 3.3 ("Limits (defaults, per peer)"): 8 unauthenticated connections in flight per listener, 10 handshakes per minute per source address.
 * [PER_SOURCE_IN_FLIGHT] and [TICKET_TTL_MS] are PROVISIONAL additions (ERRATA ERR-FX2-5): the spec's two numbers alone let one source hold every slot, and a ticket
 * the host never released would hold its slot for ever.
 */
object HandshakeLimits {
    const val MAX_IN_FLIGHT: Int = 8
    const val PER_SOURCE_PER_MINUTE: Int = 10
    const val WINDOW_MS: Long = 60_000L

    /** PROVISIONAL: half of [MAX_IN_FLIGHT], so no single source can take every handshake slot. */
    const val PER_SOURCE_IN_FLIGHT: Int = 4

    /** PROVISIONAL: twice the 5 s handshake timeout of trust.md 5.1. A ticket older than this was leaked by the host; its slot is reclaimed. */
    const val TICKET_TTL_MS: Long = 10_000L
}

sealed interface Admission {
    /** The connection may start its handshake. [release] must be called exactly once when the handshake ended, whatever the outcome (a second call, or one after a reclaim, does nothing). */
    class Admitted(private val onRelease: () -> Unit) : Admission {
        private var released = false

        @Synchronized
        fun release() {
            if (!released) {
                released = true
                onRelease()
            }
        }

        @Synchronized
        internal fun reclaim() {
            released = true
        }
    }

    /** The connection is closed before any TLS byte is read and counted in the one `INBOUND_REFUSED` row per 10 minutes (LAB_SPEC 7.6); it is never a session. */
    class Refused(val reason: Why) : Admission

    enum class Why { GLOBAL_IN_FLIGHT, PER_SOURCE_RATE, PER_SOURCE_IN_FLIGHT }
}

/**
 * Admission control for inbound connections, before the handshake (trust.md 5.1: "accept; under per-source and global limits"). It is a pure object: the host
 * calls [admit] with the source address (no port) as soon as it accepted a socket and calls `release` on the ticket when the handshake finished or failed.
 * The clock is injected. A refused connection does not count towards its source's window (so refusals cannot extend a lock-out); an admitted one counts
 * whatever its handshake does. The set of tracked sources is pruned at every call, so it cannot grow without bound.
 *
 * A source is keyed by [sourceKey]: an IPv4-mapped IPv6 address is its IPv4 address, and any other IPv6 address is its /64, so rotating the low 64 bits does not
 * make a new source. One source may hold at most [perSourceInFlight] tickets at once. A ticket older than [ticketTtlMs] is reclaimed (its release becomes a no-op).
 */
class HandshakeLimiter(
    private val clock: () -> Long,
    private val maxInFlight: Int = HandshakeLimits.MAX_IN_FLIGHT,
    private val perSourcePerWindow: Int = HandshakeLimits.PER_SOURCE_PER_MINUTE,
    private val windowMs: Long = HandshakeLimits.WINDOW_MS,
    private val perSourceInFlight: Int = HandshakeLimits.PER_SOURCE_IN_FLIGHT,
    private val ticketTtlMs: Long = HandshakeLimits.TICKET_TTL_MS,
) {
    private class Ticket(val source: String, val at: Long) {
        lateinit var admitted: Admission.Admitted
    }

    private val bySource = HashMap<String, ArrayDeque<Long>>()
    private val tickets = ArrayList<Ticket>()

    @get:Synchronized
    val inFlightNow: Int get() = tickets.size

    @get:Synchronized
    val trackedSources: Int get() = bySource.size

    @Synchronized
    fun admit(address: String): Admission {
        val now = clock()
        prune(now)
        if (tickets.size >= maxInFlight) return Admission.Refused(Admission.Why.GLOBAL_IN_FLIGHT)
        val source = sourceKey(address)
        if (tickets.count { it.source == source } >= perSourceInFlight) return Admission.Refused(Admission.Why.PER_SOURCE_IN_FLIGHT)
        val window = bySource.getOrPut(source) { ArrayDeque() }
        if (window.size >= perSourcePerWindow) return Admission.Refused(Admission.Why.PER_SOURCE_RATE)
        window.addLast(now)
        val t = Ticket(source, now)
        t.admitted = Admission.Admitted { release(t) }
        tickets += t
        return t.admitted
    }

    @Synchronized
    private fun release(t: Ticket) {
        check(tickets.remove(t)) { "a ticket was released more often than it was admitted" }
    }

    private fun prune(now: Long) {
        val expired = tickets.filter { now - it.at >= ticketTtlMs }
        for (t in expired) {
            tickets.remove(t)
            t.admitted.reclaim()
        }
        val it = bySource.entries.iterator()
        while (it.hasNext()) {
            val q = it.next().value
            while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
            if (q.isEmpty()) it.remove()
        }
    }

    companion object {
        /** The key of a source address: IPv4 as it is, an IPv4-mapped IPv6 address as its IPv4 form, any other IPv6 address as its /64. Anything else is kept as given (lower case). */
        fun sourceKey(address: String): String {
            val a = address.trim().substringBefore('%').lowercase()
            if (!a.contains(':')) return a
            val groups = parseV6(a) ?: return a
            if (groups.take(5).all { it == 0 } && groups[5] == 0xffff) return "${groups[6] shr 8}.${groups[6] and 0xff}.${groups[7] shr 8}.${groups[7] and 0xff}"
            return "v6:" + groups.take(4).joinToString(":") { it.toString(16) } + "/64"
        }

        private fun parseV6(a: String): List<Int>? {
            var text = a
            val dot = text.lastIndexOf('.')
            if (dot >= 0) {
                val colon = text.lastIndexOf(':')
                val parts = text.substring(colon + 1).split('.')
                if (parts.size != 4) return null
                val b = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p.length in 1..3 } ?: return null }
                text = text.substring(0, colon + 1) + "%x:%x".format((b[0] shl 8) or b[1], (b[2] shl 8) or b[3])
            }
            val halves = text.split("::")
            if (halves.size > 2) return null
            fun hextets(part: String): List<Int>? = if (part.isEmpty()) emptyList() else part.split(':').map { h -> h.takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null }
            val head = hextets(halves[0]) ?: return null
            if (halves.size == 1) return head.takeIf { it.size == 8 }
            val tail = hextets(halves[1]) ?: return null
            val fill = 8 - head.size - tail.size
            if (fill < 1) return null
            return head + List(fill) { 0 } + tail
        }
    }
}
