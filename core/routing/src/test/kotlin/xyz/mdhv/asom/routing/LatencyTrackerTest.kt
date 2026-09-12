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
}
