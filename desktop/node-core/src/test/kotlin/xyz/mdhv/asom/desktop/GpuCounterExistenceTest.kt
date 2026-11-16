package xyz.mdhv.asom.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GpuCounterExistenceTest {
    private val laws = LawCounter(listOf("absent-needs-consecutive-misses", "absent-is-rechecked", "found-resets", "blind-port-is-blind"))

    @Test
    fun `absent only after the third consecutive miss, and a find in between resets the count`() {
        val e = GpuCounterExistence(absentAfterMisses = 3, recheckEveryCalls = 30)
        e.missed(); e.missed()
        assertFalse(e.absent)
        e.found()
        e.missed(); e.missed()
        assertFalse(e.absent, "a find reset the streak")
        laws.hit("found-resets")
        e.missed()
        assertTrue(e.absent)
        laws.hit("absent-needs-consecutive-misses")
    }

    @Test
    fun `an absent verdict is asked again every recheck interval and a miss keeps it absent`() {
        val e = GpuCounterExistence(absentAfterMisses = 2, recheckEveryCalls = 5)
        e.missed(); e.missed()
        val due = (1..15).map { e.shouldProbe() }
        assertEquals(listOf(4, 9, 14), due.withIndex().filter { it.value }.map { it.index })
        e.missed()
        assertTrue(e.absent)
        e.found()
        assertFalse(e.absent)
        assertTrue(e.shouldProbe())
        laws.hit("absent-is-rechecked")
    }

    @Test
    fun `the blind port never produces a sample`() {
        assertNull(BlindGpuPort.sample())
        laws.hit("blind-port-is-blind")
    }

    @AfterAll
    fun everyLawExercised() = laws.assertAllExercised("gpu-counter-existence")
}
