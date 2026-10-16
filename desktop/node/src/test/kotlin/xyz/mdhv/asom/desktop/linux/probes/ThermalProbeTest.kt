package xyz.mdhv.asom.desktop.linux.probes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.linux.FakeClock
import xyz.mdhv.asom.desktop.linux.Fixtures
import xyz.mdhv.asom.desktop.linux.MapFileSource
import xyz.mdhv.asom.desktop.linux.Report

class ThermalProbeTest {
    @Test
    fun `deck oled fixture (SYNTHETIC) reads only the watched sensors and starts at band 0`() {
        val r = ThermalProbe(Fixtures.fs("deck-oled")).read()
        assertEquals(0, r.band)
        assertEquals(listOf("amdgpu edge", "nvme Composite", "k10temp Tctl"), r.watchedSensors, "Sensor 1 and Battery Temp are not watched")
        assertEquals(55000, r.hottestMilliC)
    }

    @Test
    fun `hosts with no sensors report band 0 with no readings and say so`() {
        val r = ThermalProbe(Fixtures.fs("ci-vm")).read()
        assertEquals(0, r.band)
        assertNull(r.hottestMilliC)
        assertNull(r.holdThresholdMilliC)
        assertTrue(r.watchedSensors.isEmpty())
    }

    @Test
    fun `deck lcd is inside the QUEUE margin and dell is comfortably band 0 (SYNTHETIC fixtures)`() {
        // amdgpu: threshold min(95000 - 5000 passive, 100000 - 15000 crit) = 85000; 76000 is 9000 below it -> band 1.
        val deck = ThermalProbe(Fixtures.fs("deck-lcd")).read()
        assertEquals(85_000, deck.holdThresholdMilliC)
        assertEquals(1, deck.band)
        assertEquals(0, ThermalProbe(Fixtures.fs("dell")).read().band)
    }

    private fun tree(temp: Int, crit: Int? = 100_000, passive: Int? = 95_000, critZone: Int? = 105_000): MapFileSource {
        val m = MapFileSource()
        m.files["/sys/class/hwmon/hwmon0/name"] = "amdgpu\n"
        m.files["/sys/class/hwmon/hwmon0/temp1_input"] = "$temp\n"
        m.files["/sys/class/hwmon/hwmon0/temp1_label"] = "edge\n"
        if (crit != null) m.files["/sys/class/hwmon/hwmon0/temp1_crit"] = "$crit\n"
        var i = 0
        if (passive != null) { m.files["/sys/class/thermal/thermal_zone0/trip_point_${i}_temp"] = "$passive\n"; m.files["/sys/class/thermal/thermal_zone0/trip_point_${i}_type"] = "passive\n"; i++ }
        if (critZone != null) { m.files["/sys/class/thermal/thermal_zone0/trip_point_${i}_temp"] = "$critZone\n"; m.files["/sys/class/thermal/thermal_zone0/trip_point_${i}_type"] = "critical\n" }
        return m
    }

    @Test
    fun `band boundaries are exactly threshold and threshold minus 10 C`() {
        val cases = listOf(84_999 to 1, 85_000 to 2, 75_000 to 1, 74_999 to 0, 100_000 to 2, 20_000 to 0)
        for ((temp, band) in cases) {
            assertEquals(band, ThermalProbe(tree(temp)).read().band, "temp $temp")
        }
        Report.line("thermal probe: ${cases.size} band boundary cases")
    }

    @Test
    fun `raising is immediate, lowering needs 3 C of hysteresis AND a 10 s dwell`() {
        val fs = tree(86_000)
        val clock = FakeClock()
        val p = ThermalProbe(fs, hysteresisMilliC = 3_000, dwellMs = 10_000, clock = clock)
        assertEquals(2, p.read().band)
        // 83,000 is above threshold - hysteresis (82,000): still HOLD
        fs.files["/sys/class/hwmon/hwmon0/temp1_input"] = "83000\n"
        clock.now += 60_000
        assertEquals(2, p.read().band, "inside the hysteresis band")
        // 81,999 is below 82,000: the dwell starts
        fs.files["/sys/class/hwmon/hwmon0/temp1_input"] = "81999\n"
        clock.now += 1_000
        assertEquals(2, p.read().band, "dwell just started")
        clock.now += 9_999
        assertEquals(2, p.read().band, "9,999 ms below is not yet 10 s")
        clock.now += 1
        assertEquals(1, p.read().band, "10 s below: lowered (to QUEUE, 81,999 is within 10 C of 85,000)")
        // a spike resets the dwell and raises at once
        fs.files["/sys/class/hwmon/hwmon0/temp1_input"] = "90000\n"
        clock.now += 1
        assertEquals(2, p.read().band)
    }

    @Test
    fun `a dead sensor never lowers the band and an implausible reading is ignored`() {
        val fs = tree(90_000)
        val clock = FakeClock()
        val p = ThermalProbe(fs, clock = clock)
        assertEquals(2, p.read().band)
        fs.files.remove("/sys/class/hwmon/hwmon0/temp1_input")
        clock.now += 600_000
        assertEquals(2, p.read().band, "no reading: the band is unchanged")
        fs.files["/sys/class/hwmon/hwmon0/temp1_input"] = "-273150\n"
        assertEquals(2, p.read().band, "an implausible value is ignored, not read as cold")
    }

    @Test
    fun `no trip data at all falls back to the configured threshold and says so`() {
        val fs = tree(84_999, crit = null, passive = null, critZone = null)
        assertEquals(1, ThermalProbe(fs, noTripHoldMilliC = 85_000).read().band)
        val r = ThermalProbe(tree(85_000, null, null, null), noTripHoldMilliC = 85_000).read()
        assertEquals(2, r.band)
        assertEquals(85_000, r.holdThresholdMilliC)
    }

    @Test
    fun `each sensor uses its own crit, so a low nvme crit does not make a cool CPU look hot`() {
        val fs = tree(60_000)
        fs.files["/sys/class/hwmon/hwmon1/name"] = "nvme\n"
        fs.files["/sys/class/hwmon/hwmon1/temp1_input"] = "41850\n"
        fs.files["/sys/class/hwmon/hwmon1/temp1_label"] = "Composite\n"
        fs.files["/sys/class/hwmon/hwmon1/temp1_crit"] = "84850\n"
        val r = ThermalProbe(fs).read()
        assertEquals(0, r.band, "nvme threshold is 69,850 and it is at 41,850; the CPU threshold is 85,000 and it is at 60,000")
        assertEquals(60_000, r.hottestMilliC)
    }

    @Test
    fun `lowest passive trip across zones and the critical trip parse by type not position`() {
        val fs = MapFileSource()
        fs.files["/sys/class/thermal/thermal_zone0/trip_point_3_temp"] = "70000\n"
        fs.files["/sys/class/thermal/thermal_zone0/trip_point_3_type"] = "passive\n"
        fs.files["/sys/class/thermal/thermal_zone1/trip_point_0_temp"] = "60000\n"
        fs.files["/sys/class/thermal/thermal_zone1/trip_point_0_type"] = "passive\n"
        fs.files["/sys/class/thermal/thermal_zone1/trip_point_1_temp"] = "110000\n"
        fs.files["/sys/class/thermal/thermal_zone1/trip_point_1_type"] = "critical\n"
        fs.files["/sys/class/thermal/thermal_zone1/trip_point_2_temp"] = "0\n"
        fs.files["/sys/class/thermal/thermal_zone1/trip_point_2_type"] = "passive\n"
        val t = ThermalProbe(fs).trips()
        assertEquals(60_000, t.lowestPassiveMilliC)
        assertEquals(110_000, t.lowestCriticalMilliC)
    }
}
