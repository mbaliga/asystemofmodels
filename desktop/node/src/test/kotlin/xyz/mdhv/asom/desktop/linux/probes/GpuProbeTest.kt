package xyz.mdhv.asom.desktop.linux.probes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.mdhv.asom.desktop.linux.FakeClock
import xyz.mdhv.asom.desktop.linux.Fixtures
import xyz.mdhv.asom.desktop.linux.MapFileSource

class GpuProbeTest {
    @Test
    fun `only an AMD card with a busy counter gives an attributable probe (SYNTHETIC fixtures)`() {
        assertNotNull(GpuProbe.find(Fixtures.fs("deck-oled")))
        assertNotNull(GpuProbe.find(Fixtures.fs("deck-lcd")))
        assertNull(GpuProbe.find(Fixtures.fs("dell")), "Intel iGPU and NVIDIA dGPU: no own-versus-other attribution, rule off")
        assertNull(GpuProbe.find(Fixtures.fs("ci-vm")))
        val cards = GpuProbe.cards(Fixtures.fs("dell"))
        assertEquals(listOf(GpuVendor.INTEL, GpuVendor.NVIDIA), cards.map { it.vendor })
        assertEquals(listOf("card0"), GpuProbe.cards(Fixtures.fs("deck-oled")).map { it.name }, "card0-eDP-1 is a connector, not a card")
    }

    @Test
    fun `fdinfo parsing finds DRM clients and ignores other descriptors`() {
        assertNull(GpuProbe.parseFdinfo("pos:\t0\nflags:\t02000000\n"))
        val c = GpuProbe.parseFdinfo("drm-driver:\tamdgpu\ndrm-pdev:\t0000:04:00.0\ndrm-client-id:\t12\ndrm-engine-gfx:\t1500000000 ns\n")!!
        assertEquals(DrmClient("amdgpu", "0000:04:00.0", "12", 1_500_000_000L), c)
        assertEquals(0L, GpuProbe.parseFdinfo("drm-driver: amdgpu\ndrm-client-id: 1\ndrm-engine-gfx: -5 ns\n")!!.gfxNs, "a negative counter is not trusted")
        assertNull(GpuProbe.parseFdinfo("drm-driver: amdgpu\n"), "no client id")
    }

    private fun tree(busyPercent: Int, gfxNs: Map<Int, Long>, pdev: String = "0000:04:00.0"): MapFileSource {
        val m = MapFileSource.ofFixture("deck-oled")
        m.files.keys.filter { it.startsWith("/proc/self/fdinfo/") }.forEach { m.files.remove(it) }
        m.files["/sys/class/drm/card0/device/gpu_busy_percent"] = "$busyPercent\n"
        for ((cid, ns) in gfxNs) {
            // every client is reachable through TWO descriptors (a duplicated fd): it must count once
            for (fd in listOf(cid * 10, cid * 10 + 1)) {
                m.files["/proc/self/fdinfo/$fd"] = "drm-driver:\tamdgpu\ndrm-pdev:\t$pdev\ndrm-client-id:\t$cid\ndrm-engine-gfx:\t$ns ns\n"
            }
        }
        return m
    }

    @Test
    fun `other busy is device busy minus own fdinfo delta, duplicated descriptors counted once`() {
        val fs = tree(80, mapOf(12 to 1_000_000_000L, 13 to 0L))
        val clock = FakeClock(5_000)
        val p = GpuProbe.find(fs, clock)!!
        assertNull(p.sample(), "first sample: no delta yet")
        // 1 s later: client 12 used 250 ms of GFX time -> own 250 permille; device 80 percent = 800 permille
        clock.now += 1_000
        fs.files.putAll(tree(80, mapOf(12 to 1_250_000_000L, 13 to 0L)).files.filterKeys { it.startsWith("/proc/self/fdinfo/") })
        val s = p.sample()!!
        assertEquals(800, s.deviceBusyPermille)
        assertEquals(250, s.ownBusyPermille, "if the duplicated fd were counted twice this would be 500")
        assertEquals(550, s.otherBusyPermille)
    }

    @Test
    fun `a client first seen in the window counts zero, so own busy is never over-subtracted`() {
        val fs = tree(60, mapOf(12 to 1_000_000_000L))
        val clock = FakeClock(0)
        val p = GpuProbe.find(fs, clock)!!
        p.sample()
        clock.now += 1_000
        fs.files.putAll(tree(60, mapOf(12 to 1_000_000_000L, 99 to 900_000_000L)).files.filterKeys { it.startsWith("/proc/self/fdinfo/") })
        val s = p.sample()!!
        assertEquals(0, s.ownBusyPermille)
        assertEquals(600, s.otherBusyPermille)
    }

    @Test
    fun `clients on another PCI device are not ours to subtract, and own busy cannot exceed device busy`() {
        val fs = tree(10, mapOf(12 to 0L), pdev = "0000:99:00.0")
        val clock = FakeClock(0)
        val p = GpuProbe.find(fs, clock)!!
        p.sample()
        clock.now += 1_000
        fs.files.putAll(tree(10, mapOf(12 to 900_000_000L), pdev = "0000:99:00.0").files.filterKeys { it.startsWith("/proc/self/fdinfo/") })
        assertEquals(0, p.sample()!!.ownBusyPermille)
        // same device, our own delta larger than the device counter says: other clamps at 0, not negative
        val fs2 = tree(10, mapOf(12 to 0L))
        val clock2 = FakeClock(0)
        val p2 = GpuProbe.find(fs2, clock2)!!
        p2.sample()
        clock2.now += 1_000
        fs2.files.putAll(tree(10, mapOf(12 to 900_000_000L)).files.filterKeys { it.startsWith("/proc/self/fdinfo/") })
        val s2 = p2.sample()!!
        assertEquals(900, s2.ownBusyPermille)
        assertEquals(0, s2.otherBusyPermille)
    }

    @Test
    fun `an unreadable or out of range busy counter is a blind sample, not zero`() {
        val fs = tree(50, mapOf(12 to 0L))
        val p = GpuProbe.find(fs, FakeClock(0))!!
        fs.files["/sys/class/drm/card0/device/gpu_busy_percent"] = "150\n"
        assertNull(p.sample())
        fs.files.remove("/sys/class/drm/card0/device/gpu_busy_percent")
        assertNull(p.sample())
    }
}
