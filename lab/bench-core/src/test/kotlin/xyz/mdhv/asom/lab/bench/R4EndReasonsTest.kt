package xyz.mdhv.asom.lab.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Design revision 4, benchmark.md R4-B-01: the closed abort list gains BATTERY_LOW, POWER_SAVER and DEVICE_BUSY. BATTERY_LOW and POWER_SAVER are
 * hard ceilings (HARD_CEILING, confidence capped at medium); DEVICE_BUSY is not. R4-B-02: iPadOS uses the iOS row and, among several hard
 * ceilings, thermal comes first, then battery, then the rest. SIMULATED where an executor trace is used - NOT DEVICE EVIDENCE.
 */
class R4EndReasonsTest {
    private val cool = ThermalReading(true, 0, 300, 300)
    private val live = Presence(foreground = true, screenOn = true, batterySaver = false, lowPowerMode = false)
    private val lowPower = live.copy(lowPowerMode = true)

    private fun ceiling(platform: String, form: String = "phone", t: ThermalReading = cool, level: Int? = 700, presence: Presence = live, source: String = "ac", gpuHeldMs: Long = 0L): Ceiling? =
        Ceilings.evaluate(platform, form, t, PowerReading(source, level, true), presence, gpuHeldMs)

    private fun name(c: Ceiling?): String = when (c) {
        is Ceiling.Hard -> "hard ${c.reason}"
        is Ceiling.Soft -> "soft ${c.reason}"
        null -> "none"
    }

    @Test
    fun aLowBatteryLevelAndAPowerSaverSwitchHaveTheirOwnHardCeilingNames() {
        val c = Counter("r4 ceiling names")
        fun expect(want: String, platform: String, form: String = "phone", t: ThermalReading = cool, level: Int? = 700, presence: Presence = live) {
            assertEquals(want, name(ceiling(platform, form, t, level, presence)), "$platform $form code=${t.code} level=$level lpm=${presence.lowPowerMode}")
            c.hit()
        }
        expect("hard BATTERY_LOW", "android", level = 199)
        expect("none", "android", level = 200)
        expect("hard BATTERY_LOW", "ios", level = 199)
        expect("hard BATTERY_LOW", "ipados", "tablet", level = 199)
        expect("none", "ios", level = 200)
        expect("none", "ipados", "tablet", level = 200)
        expect("hard POWER_SAVER", "ios", presence = lowPower)
        expect("hard POWER_SAVER", "ipados", "tablet", presence = lowPower)
        c.requireNonVacuous(8)
    }

    @Test
    fun amongSeveralHardCeilingsThermalComesFirstThenBatteryThenTheRest() {
        val c = Counter("r4 ceiling order")
        fun expect(want: String, platform: String, form: String = "phone", t: ThermalReading = cool, level: Int? = 700, presence: Presence = live) {
            assertEquals(want, name(ceiling(platform, form, t, level, presence)))
            c.hit()
        }
        val severe = ThermalReading(true, 3, 990, 300)
        val hotBattery = ThermalReading(true, 0, 300, 445)
        expect("hard THERMAL_HARD", "android", t = severe, level = 150)
        expect("hard THERMAL_HARD", "ios", t = severe, level = 150, presence = lowPower)
        expect("hard THERMAL_HARD", "ipados", "tablet", t = severe, presence = lowPower)
        expect("hard BATTERY_TEMP", "android", t = hotBattery, level = 150)
        expect("hard BATTERY_LOW", "ios", level = 150, presence = lowPower)
        expect("hard BATTERY_LOW", "ipados", "tablet", level = 150, presence = lowPower)
        // hard before soft
        expect("hard BATTERY_LOW", "android", t = ThermalReading(true, 2, 960, 300), level = 150)
        c.requireNonVacuous(7)
    }

    @Test
    fun theBatterySaverSwitchIsNotARuntimeCeilingOnAndroid() {
        assertNull(ceiling("android", presence = live.copy(batterySaver = true)), "11.3 lists no battery-saver ceiling for Android; it is a preflight gate")
        assertNull(ceiling("android", presence = lowPower), "Low Power Mode is an Apple switch")
    }

    @Test
    fun theClosedReasonSetsCarryTheNewNames() {
        for (r in listOf("BATTERY_LOW", "POWER_SAVER", "DEVICE_BUSY")) {
            assertTrue(r in BenchEnums.abortReasons, "abortReasons lacks $r")
            assertTrue(r in BenchEnums.endReasons, "endReasons lacks $r")
        }
        for (r in listOf("THERMAL_HARD", "BATTERY_TEMP", "USER_STOP", "BACKGROUNDED", "CHARGER_REMOVED", "MEMORY_PRESSURE", "WALL_CAP", "PROBE_LOST")) assertTrue(r in BenchEnums.abortReasons, "abortReasons lost $r")
    }

    private fun windows(n: Int): List<BWindow> = (0 until n).map { BWindow(it * 15_000L, 100, 15_000_000L, 0) }

    @Test
    fun theTwoNewHardCeilingsSetHardCeilingAndCapConfidenceAtMediumAndDeviceBusyDoesNot() {
        val c = Counter("r4 sustain end reasons")
        for (reason in listOf("THERMAL_HARD", "BATTERY_TEMP", "BATTERY_LOW", "POWER_SAVER")) {
            val d = Derive.deriveSustainStandalone(BSustain("T3", 15_000, 600_000, reason, null, windows(40)), "cool")
            assertTrue("HARD_CEILING" in d.flags, "$reason must set HARD_CEILING: ${d.flags}")
            assertEquals(Confidence.MEDIUM, d.confidence, reason)
            c.hit()
        }
        for (reason in listOf("DEVICE_BUSY", "USER_STOP", "BACKGROUNDED", "CHARGER_REMOVED", "MEMORY_PRESSURE", "WALL_CAP", "PROBE_LOST", "TIME_CAP")) {
            val d = Derive.deriveSustainStandalone(BSustain("T3", 15_000, 600_000, reason, null, windows(40)), "cool")
            assertFalse("HARD_CEILING" in d.flags, "$reason must not set HARD_CEILING: ${d.flags}")
            c.hit()
        }
        c.requireNonVacuous(12)
    }

    @Test
    fun aDocumentThatNamesTheNewReasonsDecodesAndRoundTrips() {
        val c = Counter("r4 documents")
        val base = ExampleDocs.phoneDoc(RunPlans.STANDARD.sha256B64u())
        for (reason in listOf("BATTERY_LOW", "POWER_SAVER", "DEVICE_BUSY")) {
            val doc = base.copy(run = base.run.copy(abort = BAbort(reason, 400_000L)), sustain = base.sustain!!.copy(endReason = reason))
            val json = BenchCodec.encode(doc)
            val back = BenchCodec.decode(json, RdContext(false))
            assertEquals(reason, back.run.abort?.reason)
            assertEquals(reason, back.sustain?.endReason)
            val text = TextRender.render(Derive.derive(back), RenderOptions(false))
            assertTrue(text.contains("The run stopped early ($reason)"), text)
            assertEquals(reason != "DEVICE_BUSY", text.contains("Heat test: stopped early at a safety limit."), reason)
            c.hit()
        }
        c.requireNonVacuous(3)
    }

    private fun trace(scenario: String) = Scenarios.run(BJ.parse(scenario))

    @Test
    fun aBatteryLevelThatFallsUnder200PermilleMidRunAbortsWithBatteryLowAndTheDocumentSaysSo() {
        val r = trace("""{"preset":"phone","plan":"standard","injections":[{"atMs":100000,"kind":"BATTERY_LEVEL","value":150}]}""")
        assertEquals("aborted BATTERY_LOW", r.outcome)
        assertTrue(r.leaks.isEmpty(), "${r.leaks}")
        val doc = assertNotNull(r.doc)
        assertEquals("BATTERY_LOW", doc.run.abort?.reason)
        BenchCodec.decode(BenchCodec.encode(doc), RdContext(false))
    }

    @Test
    fun aBatteryLevelThatFallsDuringTheHeatTestKeepsTheWindowsFlagsThemAndCapsConfidence() {
        val r = trace("""{"preset":"phone","plan":"standard","injections":[{"atMs":330000,"kind":"BATTERY_LEVEL","value":150}]}""")
        assertEquals("aborted BATTERY_LOW", r.outcome)
        assertTrue(r.leaks.isEmpty(), "${r.leaks}")
        val sustain = assertNotNull(r.doc?.sustain, "the heat test windows are kept")
        assertEquals("BATTERY_LOW", sustain.endReason)
        val d = Derive.derive(r.doc!!)
        assertTrue("HARD_CEILING" in d.sustain!!.flags)
        assertTrue(d.sustain!!.confidence <= Confidence.MEDIUM)
    }
}
