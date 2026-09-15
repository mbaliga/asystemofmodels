package xyz.mdhv.asom.routing

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LatencyTrackerTest {

    @Test
    fun `first sample seeds the ewma`() {
        val t = LatencyTracker()
        assertNull(t.ewma("p"))
        t.record("p", 200)
        assertEquals(200.0, t.ewma("p"))
    }

    @Test
    fun `ewma blends with alpha 0_3`() {
        val t = LatencyTracker(alpha = 0.3)
        t.record("p", 100)
        t.record("p", 200)
        assertTrue(abs(t.ewma("p")!! - (0.3 * 200 + 0.7 * 100)) < 1e-9)
    }

    @Test
    fun `snapshot and preload round-trip for persistence`() {
        val t = LatencyTracker()
        t.record("a", 50)
        t.record("b", 500)
        val restored = LatencyTracker().apply { preload(t.snapshot()) }
        assertEquals(t.ewma("a"), restored.ewma("a"))
        assertEquals(t.ewma("b"), restored.ewma("b"))
    }

    @Test
    fun `no store wired is silent, not a crash`() {
        val t = LatencyTracker()
        t.record("p", 100)
        t.flush()
        assertEquals(100.0, t.ewma("p"))
    }

    @Test
    fun `the first sample writes through, later ones are debounced`() {
        var now = 1_000L
        val saved = mutableListOf<Map<String, Double>>()
        val t = LatencyTracker(store = { saved += it }, persistIntervalMs = 30_000, clock = { now })

        t.record("p", 100)
        assertEquals(listOf(mapOf("p" to 100.0)), saved)

        t.record("p", 200)
        assertEquals(1, saved.size)

        now += 30_000
        t.record("q", 400)
        assertEquals(2, saved.size)
        assertEquals(setOf("p", "q"), saved.last().keys)
    }

    @Test
    fun `flush writes through inside the debounce window`() {
        var now = 1_000L
        val saved = mutableListOf<Map<String, Double>>()
        val t = LatencyTracker(store = { saved += it }, persistIntervalMs = 30_000, clock = { now })

        t.record("a", 50)
        t.record("b", 60)
        assertEquals(1, saved.size)

        t.flush()
        assertEquals(mapOf("a" to 50.0, "b" to 60.0), saved.last())

        now += 29_999
        t.record("c", 70)
        assertEquals(2, saved.size, "flush must restart the debounce window")
    }

    @Test
    fun `a preloaded tracker persists the merged state on the next sample`() {
        var now = 1_000L
        val saved = mutableListOf<Map<String, Double>>()
        val t = LatencyTracker(store = { saved += it }, clock = { now })

        t.preload(mapOf("a" to 90.0))
        assertTrue(saved.isEmpty(), "preload must not write back what it just read")

        t.record("b", 500)
        assertEquals(mapOf("a" to 90.0, "b" to 500.0), saved.last())
    }
}
