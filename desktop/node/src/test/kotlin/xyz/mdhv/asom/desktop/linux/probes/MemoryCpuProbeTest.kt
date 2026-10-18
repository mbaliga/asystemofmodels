package xyz.mdhv.asom.desktop.linux.probes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.desktop.linux.Fixtures
import xyz.mdhv.asom.desktop.linux.MapFileSource
import xyz.mdhv.asom.desktop.linux.Report

class MemoryCpuProbeTest {
    @Test
    fun `meminfo and PSI parse on the fixtures (SYNTHETIC)`() {
        val deck = MemoryProbe(Fixtures.fs("deck-oled")).read()
        assertEquals(15726300L * 1024, deck.totalBytes)
        assertEquals(9812344L * 1024, deck.availableBytes)
        assertEquals(0, deck.psiFullAvg10Centi)
        val vm = MemoryProbe(Fixtures.fs("ci-vm")).read()
        assertNull(vm.psiFullAvg10Centi, "no pressure file: PSI unknown, not zero")
        assertEquals(7200000L * 1024, vm.availableBytes)
    }

    @Test
    fun `decimal centi parsing needs no floating point`() {
        val ok = listOf("0.00" to 0, "0.01" to 1, "5.00" to 500, "12.3" to 1230, "100" to 10000, "99.999" to 9999, "0" to 0)
        for ((s, v) in ok) assertEquals(v, parseCenti(s), s)
        for (bad in listOf("", "x", "1.x", "-1.00", "1e2", ".", "1..2")) assertNull(parseCenti(bad), bad)
        assertEquals(500, MemoryProbe.parsePsiFullAvg10Centi("some avg10=9.00 avg60=0 avg300=0 total=1\nfull avg10=5.00 avg60=0 avg300=0 total=1\n"), "the full line, not some")
        assertNull(MemoryProbe.parsePsiFullAvg10Centi("some avg10=9.00 avg60=0 avg300=0 total=1\n"))
        Report.line("memory probe: ${ok.size} decimal cases")
    }

    @Test
    fun `meminfo skips malformed lines and unknown units`() {
        val m = MemoryProbe.parseMeminfo("MemTotal: 100 kB\nBogus line\nMemAvailable: x kB\nHugePages_Total: 7\nWeird: 5 MB\n")
        assertEquals(mapOf("MemTotal" to 102400L, "HugePages_Total" to 7L), m)
    }

    @Test
    fun `RAM guard admits exactly up to available minus the larger of 1_5 GiB and 10 percent of RAM`() {
        val gib = 1L shl 30
        // 16 GiB machine: reserve = max(1.5 GiB, 1.6 GiB) = 1.6 GiB (10 percent, integer division)
        val m = MemoryReading(totalBytes = 16 * gib, availableBytes = 10 * gib, psiFullAvg10Centi = null)
        val room = 10 * gib - (16 * gib) / 10
        assertEquals(room, RamGuard.headroomBytes(m))
        assertTrue(RamGuard.admits(m, room - 100, 100))
        assertFalse(RamGuard.admits(m, room - 100, 101))
        // 8 GiB machine: reserve = max(1.5 GiB, 0.8 GiB) = 1.5 GiB
        val small = MemoryReading(8 * gib, 5 * gib, null)
        assertEquals(5 * gib - RamGuard.MIN_RESERVE_BYTES, RamGuard.headroomBytes(small))
        // unknown memory admits nothing
        assertFalse(RamGuard.admits(MemoryReading(null, null, null), 0, 0))
        assertFalse(RamGuard.admits(m, -1, 0))
        // more than available admits nothing (headroom negative)
        assertFalse(RamGuard.admits(MemoryReading(16 * gib, gib, null), 0, 0))
    }

    @Test
    fun `stat and self stat parse, including a comm with spaces and parentheses`() {
        val t = CpuProbe.parseStat("cpu  4705 150 3010 3105200 1200 0 300 0 0 0\ncpu0 1 2 3 4\n")!!
        assertEquals(8165L, t.busy, "user+nice+system+irq+softirq+steal; guest is inside user already")
        assertEquals(8165L + 3105200 + 1200, t.total)
        assertNull(CpuProbe.parseStat("cpu  1 2 3\n"))
        assertNull(CpuProbe.parseStat("cpu  a b c d e f g h\n"))
        assertNull(CpuProbe.parseStat("intr 1\n"))
        assertEquals(180L, CpuProbe.parseSelfTicks("4242 (asom-node) S 1 4242 4242 0 -1 4194304 12345 0 0 0 150 30 0 0 20 0 24 0 100000"))
        assertEquals(180L, CpuProbe.parseSelfTicks("4242 (evil ) S 9 9) S 1 4242 4242 0 -1 4194304 12345 0 0 0 150 30 0 0 20 0 24 0 100000"))
        assertNull(CpuProbe.parseSelfTicks("garbage"))
        assertNull(CpuProbe.parseSelfTicks("1 (x) S 1 2"))
    }

    private fun cpuTree(busy: Long, idle: Long, own: Long): MapFileSource {
        val m = MapFileSource()
        m.files["/proc/stat"] = "cpu  $busy 0 0 $idle 0 0 0 0 0 0\n"
        m.files["/proc/self/stat"] = "1 (n) S 1 1 1 0 -1 0 0 0 0 0 $own 0 0 0 20 0 1 0 1 1 1\n"
        return m
    }

    @Test
    fun `other busy is machine busy minus this process, in permille of all CPU time, first sample null`() {
        val fs = cpuTree(1000, 9000, 100)
        val p = CpuProbe(fs)
        assertNull(p.sample().cpuOtherPermille, "no previous sample")
        // +1000 ticks elapsed in total; +600 busy of which +100 is ours -> 500 permille from others
        fs.files["/proc/stat"] = "cpu  1600 0 0 9400 0 0 0 0 0 0\n"
        fs.files["/proc/self/stat"] = "1 (n) S 1 1 1 0 -1 0 0 0 0 0 200 0 0 0 20 0 1 0 1 1 1\n"
        assertEquals(500, p.sample().cpuOtherPermille)
        // we used more than the whole delta (clock skew between the two files): clamp at 0, never negative
        fs.files["/proc/stat"] = "cpu  1610 0 0 10390 0 0 0 0 0 0\n"
        fs.files["/proc/self/stat"] = "1 (n) S 1 1 1 0 -1 0 0 0 0 0 900 0 0 0 20 0 1 0 1 1 1\n"
        assertEquals(0, p.sample().cpuOtherPermille)
        // counters going backwards (a reset): unknown
        fs.files["/proc/stat"] = "cpu  10 0 0 10 0 0 0 0 0 0\n"
        assertNull(p.sample().cpuOtherPermille)
        // an idle machine
        fs.files["/proc/stat"] = "cpu  20 0 0 1010 0 0 0 0 0 0\n"
        fs.files["/proc/self/stat"] = "1 (n) S 1 1 1 0 -1 0 0 0 0 0 900 0 0 0 20 0 1 0 1 1 1\n"
        val idle = p.sample().cpuOtherPermille
        assertTrue(idle != null && idle in 0..20, "idle: $idle")
    }

    @Test
    fun `saturated machine caps at 1000 permille`() {
        val fs = cpuTree(0, 0, 0)
        val p = CpuProbe(fs)
        p.sample()
        fs.files["/proc/stat"] = "cpu  1000 0 0 0 0 0 0 0 0 0\n"
        assertEquals(1000, p.sample().cpuOtherPermille)
    }

    @Test
    fun `psi cpu some is read when present`() {
        val fs = cpuTree(1, 1, 1)
        fs.files["/proc/pressure/cpu"] = "some avg10=12.34 avg60=0.00 avg300=0.00 total=5\n"
        assertEquals(1234, CpuProbe(fs).sample().cpuPsiSomeAvg10Centi)
    }
}
