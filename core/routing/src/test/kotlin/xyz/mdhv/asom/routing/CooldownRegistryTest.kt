package xyz.mdhv.asom.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CooldownRegistryTest {

    private var now = 1_000_000L
    private val registry = CooldownRegistry(clock = { now })

    @Test
    fun `initially closed`() {
        assertFalse(registry.isCooling("p"))
        assertNull(registry.coolingUntil("p"))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `first failure cools for 30s`() {
        registry.recordFailure("p")
        assertTrue(registry.isCooling("p"))
        assertEquals(now + 30_000, registry.coolingUntil("p"))

        now += 29_999
        assertTrue(registry.isCooling("p"))
        now += 2
        assertFalse(registry.isCooling("p"))
        assertNull(registry.coolingUntil("p"))
    }

    @Test
    fun `backoff doubles per consecutive failure and caps at 15min`() {
        val expected = listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)
        for (backoff in expected) {
            val at = now
            registry.recordFailure("p")
            assertEquals(at + backoff, registry.coolingUntil("p"), "expected ${backoff}ms backoff")
            now = registry.coolingUntil("p")!! + 1 // let the deadline pass; streak survives (§7 FSM)
        }
    }

    @Test
    fun `deep failure streak stays at the cap — shift overflow guarded`() {
        repeat(40) {
            registry.recordFailure("p")
            now = registry.coolingUntil("p")!! + 1
        }
        val at = now
        registry.recordFailure("p")
        assertEquals(at + 900_000, registry.coolingUntil("p"))
    }

    @Test
    fun `success closes the breaker and resets the streak`() {
        registry.recordFailure("p")
        registry.recordFailure("p")
        registry.recordSuccess("p")
        assertFalse(registry.isCooling("p"))

        // Streak reset: next failure is back to the 30s base.
        registry.recordFailure("p")
        assertEquals(now + 30_000, registry.coolingUntil("p"))
    }

    @Test
    fun `streak survives an expired deadline — next failure doubles`() {
        registry.recordFailure("p")
        now = registry.coolingUntil("p")!! + 10_000
        assertFalse(registry.isCooling("p"))

        val at = now
        registry.recordFailure("p")
        assertEquals(at + 60_000, registry.coolingUntil("p"))
    }

    @Test
    fun `providers cool independently and snapshot only lists active cooldowns`() {
        registry.recordFailure("a")
        registry.recordFailure("b")
        assertEquals(setOf("a", "b"), registry.snapshot().keys)

        now += 31_000
        assertTrue(registry.snapshot().isEmpty())
        assertFalse(registry.isCooling("a"))
    }
}
