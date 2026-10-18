package xyz.mdhv.asom.desktop.linux.probes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.linux.Fixtures
import xyz.mdhv.asom.desktop.linux.LawCounter
import xyz.mdhv.asom.desktop.linux.Report

/** Non-vacuity for the probe families: every probe is run over every fixture host and the cases are counted. */
class FixtureFamiliesTest {
    @Test
    fun `the four fixture hosts exist and every one is labelled SYNTHETIC`() {
        assertEquals(listOf("deck-oled", "deck-lcd", "dell", "ci-vm").sorted(), Fixtures.hosts.sorted())
        for (h in Fixtures.hosts) {
            val label = Files.readString(Fixtures.dir(h).resolve("SYNTHETIC.txt"))
            assertTrue(label.startsWith("SYNTHETIC FIXTURE"), h)
            assertTrue("NOT captured from a device" in label, h)
        }
    }

    @Test
    fun `every probe family reads every fixture host, and each family counted more than zero cases`() {
        val laws = LawCounter(listOf("power", "thermal", "memory", "cpu-parse", "gpu-cards", "os-release"))
        for (h in Fixtures.hosts) {
            val fs = Fixtures.fs(h)
            PowerProbe(fs).read(); laws.hit("power")
            ThermalProbe(fs).read(); laws.hit("thermal")
            val mem = MemoryProbe(fs).read()
            assertTrue(mem.totalBytes != null && mem.availableBytes != null, "$h has meminfo")
            laws.hit("memory")
            assertTrue(CpuProbe.parseStat(fs.read("/proc/stat")!!) != null, h)
            assertTrue(CpuProbe.parseSelfTicks(fs.read("/proc/self/stat")!!) != null, h)
            laws.hit("cpu-parse")
            GpuProbe.cards(fs); laws.hit("gpu-cards")
            assertTrue(fs.read("/etc/os-release") != null, h)
            laws.hit("os-release")
        }
        assertEquals(4L, laws.count("power"))
        laws.assertAllExercised("probe-fixtures")
        Report.line("probe fixtures: ${Fixtures.hosts.size} SYNTHETIC hosts x 6 families")
    }
}
