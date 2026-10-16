package xyz.mdhv.asom.lab.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Track fix3-bench-policy. R4TB-01: when several governor conditions hold at the same moment the reported reason follows R4-B-02
 * (thermal, then battery, then the rest), not the order the checks happen to be written in. R4TB-07: a consent token is refused when the
 * clock reads earlier than the minting time. SIMULATED - NOT DEVICE EVIDENCE.
 */
class FixBenchPolicyTest {
    private fun trace(scenario: String) = Scenarios.run(BJ.parse(scenario))

    private fun at(ms: Long, kind: String, value: Long? = null): String =
        """{"atMs":$ms,"kind":"$kind"${value?.let { ""","value":$it""" } ?: ""}}"""

    private fun run(atMs: Long, vararg events: Pair<String, Long?>): Scenarios.TraceResult =
        trace("""{"preset":"phone","plan":"standard","injections":${events.joinToString(",", "[", "]") { at(atMs, it.first, it.second) }}}""")

    @Test
    fun aHardThermalCeilingOutranksTheConditionsOfTheRestInATierPhase() {
        val c = Counter("r4tb-01 tier phase")
        for (rest in listOf("MEMORY_PRESSURE" to null, "CHARGER_REMOVED" to null)) {
            val r = run(100_000, "THERMAL_CODE" to 3L, rest)
            assertEquals("aborted THERMAL_HARD", r.outcome, "THERMAL_CODE 3 with ${rest.first}")
            assertTrue(r.leaks.isEmpty(), "${r.leaks}")
            c.hit()
        }
        c.requireNonVacuous(2)
    }

    @Test
    fun aBatteryReadingOutranksTheConditionsOfTheRestAndFollowsThermal() {
        val c = Counter("r4tb-01 battery")
        assertEquals("aborted BATTERY_TEMP", run(100_000, "BATTERY_TEMP" to 445L, "CHARGER_REMOVED" to null).outcome); c.hit()
        assertEquals("aborted BATTERY_TEMP", run(100_000, "BATTERY_TEMP" to 445L, "MEMORY_PRESSURE" to null).outcome); c.hit()
        assertEquals("aborted BATTERY_LOW", run(100_000, "BATTERY_LEVEL" to 150L, "CHARGER_REMOVED" to null).outcome); c.hit()
        assertEquals("aborted THERMAL_HARD", run(100_000, "THERMAL_CODE" to 3L, "BATTERY_TEMP" to 445L, "CHARGER_REMOVED" to null).outcome); c.hit()
        c.requireNonVacuous(4)
    }

    @Test
    fun aPowerSaverSwitchOutranksTheRestToo() {
        val r = trace("""{"preset":"phone","plan":"standard","platform":"ios","shell":"ios-standalone","daemon":false,"injections":[{"atMs":100000,"kind":"POWER_SAVER"},{"atMs":100000,"kind":"MEMORY_PRESSURE"}]}""")
        assertEquals("aborted POWER_SAVER", r.outcome)
    }

    @Test
    fun theRestKeepsItsOwnOrderWhenNoThermalOrBatteryConditionHolds() {
        val c = Counter("r4tb-01 rest")
        assertEquals("aborted CHARGER_REMOVED", run(100_000, "CHARGER_REMOVED" to null, "MEMORY_PRESSURE" to null).outcome); c.hit()
        assertEquals("aborted MEMORY_PRESSURE", run(100_000, "MEMORY_PRESSURE" to null).outcome); c.hit()
        c.requireNonVacuous(2)
    }

    @Test
    fun aUserStopStillWinsOverEveryCeiling() {
        val r = run(100_000, "STOP" to null, "THERMAL_CODE" to 3L, "BATTERY_TEMP" to 445L)
        assertEquals("aborted USER_STOP", r.outcome)
    }

    @Test
    fun aHardThermalCeilingDuringTheHeatTestEndsThePhaseEvenWhenARestConditionHoldsToo() {
        val c = Counter("r4tb-01 sustain")
        for (rest in listOf("MEMORY_PRESSURE" to null, "CHARGER_REMOVED" to null)) {
            val r = run(330_000, "THERMAL_CODE" to 3L, rest)
            assertEquals("completed", r.outcome, "THERMAL_CODE 3 with ${rest.first} in the heat test keeps the run")
            val sustain = assertNotNull(r.doc?.sustain)
            assertEquals("THERMAL_HARD", sustain.endReason)
            assertTrue("HARD_CEILING" in Derive.derive(r.doc!!).sustain!!.flags)
            c.hit()
        }
        c.requireNonVacuous(2)
    }

    @Test
    fun aBatteryTemperatureDuringTheHeatTestStillAbortsWithTheBatteryReasonAndFlagsTheBlock() {
        val r = run(330_000, "BATTERY_TEMP" to 445L, "CHARGER_REMOVED" to null)
        assertEquals("aborted BATTERY_TEMP", r.outcome)
        val sustain = assertNotNull(r.doc?.sustain)
        assertEquals("BATTERY_TEMP", sustain.endReason)
        assertTrue("HARD_CEILING" in Derive.derive(r.doc!!).sustain!!.flags)
    }

    private fun sheet() = ConsentSheet.forPlan(RunPlans.STANDARD, 0L, emptyList(), false, false)

    @Test
    fun aTokenIsRefusedWhenTheClockReadsEarlierThanTheMintingTime() {
        val s = sheet()
        val t = s.confirm(s.textSha256, 3_600_000L)
        val e = runCatching { t.consume("standard", 1_000L) }.exceptionOrNull()
        assertTrue(e is ConsentError, "a token minted at 3600000 must not be spendable at 1000: $e")
        assertFalse(t.consumed)
        t.consume("standard", 3_600_000L)
        assertTrue(t.consumed)
    }

    @Test
    fun aTokenNearTheTopOfTheRangeIsNeitherWrappedNorSpendableAcrossAnOverflowingAge() {
        val s = sheet()
        val t = s.confirm(s.textSha256, Long.MAX_VALUE - 10L)
        t.consume("standard", Long.MAX_VALUE - 5L)
        assertTrue(t.consumed)
        val u = s.confirm(s.textSha256, Long.MAX_VALUE - 10L)
        assertTrue(runCatching { u.consume("standard", Long.MIN_VALUE) }.exceptionOrNull() is ConsentError)
        assertFalse(u.consumed)
    }

    @Test
    fun aTokenIsSpendableUpToButNotAtTheLifetime() {
        val s = sheet()
        s.confirm(s.textSha256, 1_000L).consume("standard", 1_000L + ConsentSheet.TOKEN_TTL_MS - 1)
        val e = runCatching { s.confirm(s.textSha256, 1_000L).consume("standard", 1_000L + ConsentSheet.TOKEN_TTL_MS) }.exceptionOrNull()
        assertTrue(e is ConsentError)
    }
}
