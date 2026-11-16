package xyz.mdhv.asom.lab.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Track fix4-bench-docs, A-R4X-5 (LAB_SPEC R4-L-18): the tracker's view refuses a `disc` outside 0..1000, as the Swift lane does. */
class TrackerDiscRangeTest {
    private val claim = PerfPrior(listOf(512 to 20_000L), 100_000, 0, 20_000, null, null, 100_000, 6_000_000_000, emptySet())

    @Test
    fun aDiscOutsideZeroToOneThousandIsRefusedAndTheEdgesAreAccepted() {
        var refused = 0
        var accepted = 0
        for (bad in listOf(-1L, -5L, 1_001L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("trackedRate disc=$bad") { ClaimTracker.trackedRate(1_000, null, null, bad) }
            assertFailsWith<IllegalArgumentException>("tracked disc=$bad") { ClaimTracker.tracked(claim, 500, null, null, bad) }
            refused += 2
        }
        for (edge in listOf(0L, 400L, 700L, 1_000L)) {
            val rate = ClaimTracker.trackedRate(1_000, null, null, edge)
            assertEquals(1_000L * edge / 1_000, rate, "disc=$edge")
            ClaimTracker.tracked(claim, 500, null, null, edge)
            accepted += 2
        }
        assertEquals(10, refused, "non-vacuity: refusals")
        assertEquals(8, accepted, "non-vacuity: accepted edges")
    }
}
