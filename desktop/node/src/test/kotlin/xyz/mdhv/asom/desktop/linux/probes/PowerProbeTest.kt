package xyz.mdhv.asom.desktop.linux.probes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.BatteryBand
import xyz.mdhv.asom.desktop.PowerSource
import xyz.mdhv.asom.desktop.linux.Fixtures
import xyz.mdhv.asom.desktop.linux.MapFileSource
import xyz.mdhv.asom.desktop.linux.Report

class PowerProbeTest {
    private fun read(host: String) = PowerProbe(Fixtures.fs(host)).read()

    @Test
    fun `deck oled docked on AC charging (SYNTHETIC fixture), peripheral battery ignored`() {
        val r = read("deck-oled")
        assertEquals(PowerSource.AC, r.source)
        assertTrue(r.charging && r.hasBattery)
        assertEquals(62, r.batteryPercent, "the 15 percent controller battery (scope=Device) must not count")
        assertEquals(BatteryBand.B50_79, r.batteryBand)
    }

    @Test
    fun `deck lcd on battery, discharging (SYNTHETIC fixture)`() {
        val r = read("deck-lcd")
        assertEquals(PowerSource.BATTERY, r.source)
        assertFalse(r.charging)
        assertEquals(BatteryBand.B20_49, r.batteryBand)
    }

    @Test
    fun `machines with no battery are on ac (dell and ci-vm, SYNTHETIC fixtures)`() {
        for (h in listOf("dell", "ci-vm")) {
            val r = read(h)
            assertEquals(PowerSource.AC, r.source, h)
            assertFalse(r.hasBattery, h)
            assertNull(r.batteryBand, h)
        }
    }

    private fun supply(name: String, type: String?, online: Int? = null, status: String? = null, cap: Int? = null, scope: String? = null) =
        PowerSupply(name, type, online, status, cap, scope)

    @Test
    fun `enumeration is by type never by name`() {
        // a battery with a strange name and a mains adapter named like a battery
        val r = PowerProbe.reduce(listOf(supply("BAT_of_doom", "Mains", online = 1), supply("AC", "Battery", status = "Discharging", cap = 30)))
        assertEquals(PowerSource.AC, r.source, "type Mains online=1 is AC whatever the name")
        assertEquals(30, r.batteryPercent)
    }

    @Test
    fun `USB online is AC, wireless and UPS are not, offline mains with a battery is battery`() {
        assertEquals(PowerSource.AC, PowerProbe.reduce(listOf(supply("ucsi", "USB", online = 1), supply("b", "Battery", cap = 50))).source)
        assertEquals(PowerSource.BATTERY, PowerProbe.reduce(listOf(supply("w", "Wireless", online = 1), supply("b", "Battery", cap = 50))).source)
        assertEquals(PowerSource.BATTERY, PowerProbe.reduce(listOf(supply("u", "UPS", online = 1), supply("b", "Battery", cap = 50))).source)
        assertEquals(PowerSource.BATTERY, PowerProbe.reduce(listOf(supply("m", "Mains", online = 0), supply("b", "Battery", cap = 50))).source)
        assertEquals(PowerSource.BATTERY, PowerProbe.reduce(listOf(supply("m", "Mains", online = null), supply("b", "Battery", cap = 50))).source, "unknown online is not AC")
    }

    @Test
    fun `charging only when status says Charging, the lowest of several batteries wins, bands split at 80 50 20`() {
        assertTrue(PowerProbe.reduce(listOf(supply("b", "Battery", status = "Charging", cap = 10))).charging)
        assertFalse(PowerProbe.reduce(listOf(supply("b", "Battery", status = "Full", cap = 100))).charging)
        assertFalse(PowerProbe.reduce(listOf(supply("b", "Battery", status = "Not charging", cap = 100))).charging)
        assertEquals(20, PowerProbe.reduce(listOf(supply("b0", "Battery", cap = 90), supply("b1", "Battery", cap = 20))).batteryPercent)
        val bands = listOf(100 to BatteryBand.GE80, 80 to BatteryBand.GE80, 79 to BatteryBand.B50_79, 50 to BatteryBand.B50_79, 49 to BatteryBand.B20_49, 20 to BatteryBand.B20_49, 19 to BatteryBand.LT20, 0 to BatteryBand.LT20)
        for ((p, b) in bands) assertEquals(b, BatteryBand.ofPercent(p), "$p")
        Report.line("power probe: ${bands.size} band boundary cases")
    }

    @Test
    fun `out of range capacity is dropped rather than trusted`() {
        val fs = MapFileSource(mapOf("/sys/class/power_supply/BAT0/type" to "Battery\n", "/sys/class/power_supply/BAT0/capacity" to "250\n"))
        val r = PowerProbe(fs).read()
        assertNull(r.batteryPercent)
        assertNull(r.batteryBand)
    }
}
