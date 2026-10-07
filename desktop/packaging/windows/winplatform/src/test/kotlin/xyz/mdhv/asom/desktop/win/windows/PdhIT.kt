package xyz.mdhv.asom.desktop.win.windows

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.win.fakes.FakeClock
import xyz.mdhv.asom.desktop.win.fakes.Report
import xyz.mdhv.asom.desktop.win.gpu.GpuEngineProbe
import xyz.mdhv.asom.desktop.win.jna.JnaPdh
import xyz.mdhv.asom.desktop.win.thermal.ThermalZoneProbe

/**
 * CI-ONLY (hosted Windows runner; SKIPPED elsewhere). The positive control is a counter that exists on every Windows
 * machine, `\Process(*)\ID Process`: it proves the array parsing (names, statuses, doubles) is right before the GPU and
 * thermal counters, which a VM may not have, are trusted or distrusted. Real GPU counters under load and real thermal
 * zones stay NEEDS-DEVICE-VALIDATION (S-W4, S-W5).
 */
@EnabledOnOs(OS.WINDOWS)
class PdhIT {
    @Test
    fun `the wildcard array parser reads instance names and values with this process's own id as the positive control`() {
        val set = JnaPdh().open("\\Process(*)\\ID Process")
        assertNotNull(set, "the Process counters exist on every Windows machine")
        set.use {
            it.collect() // rate counters need a first collection; this one is a plain gauge but the call is harmless
            val values = it.collect()
            assertNotNull(values)
            val me = ProcessHandle.current().pid().toDouble()
            Report.line("IT PdhIT: \\Process(*)\\ID Process returned ${values.size} instances; own pid $me found=${values.any { v -> v.value == me }}")
            assertTrue(values.size > 5, "instances: ${values.size}")
            assertTrue(values.any { v -> v.value == me }, "this JVM's pid must appear among the instance values")
            assertTrue(values.all { v -> v.instance.isNotEmpty() })
        }
    }

    @Test
    fun `the GPU engine and thermal counters either exist and read consistently, or are reported absent`() {
        val gpu = GpuEngineProbe.create(JnaPdh())
        Report.line("IT PdhIT: GPU Engine counters present=${gpu != null} (AW05; a hosted VM may have none)")
        if (gpu != null) {
            gpu.use {
                it.sample()
                Thread.sleep(1_100)
                val s = it.sample()
                Report.line("IT PdhIT: GPU sample after two collections = $s")
                if (s != null) assertTrue(s.otherBusyPermille in 0..1000 && s.deviceBusyPermille in 0..1000)
            }
        }
        val counters = JnaPdh().open(ThermalZoneProbe.COUNTER_PATH)
        Report.line("IT PdhIT: Thermal Zone counters present=${counters != null} (AW06)")
        val probe = ThermalZoneProbe(counters, NodeConfig(), FakeClock())
        val r = probe.read()
        Report.line("IT PdhIT: thermal reading band=${r.band} zones=${r.watchedSensors.size} hottestMilliC=${r.hottestMilliC} hasSignal=${probe.hasSignal}")
        assertTrue(r.band in 0..2)
    }
}
