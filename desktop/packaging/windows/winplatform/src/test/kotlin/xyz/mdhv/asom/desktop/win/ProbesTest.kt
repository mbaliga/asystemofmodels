package xyz.mdhv.asom.desktop.win

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.win.api.CounterValue
import xyz.mdhv.asom.desktop.win.fakes.FakeClock
import xyz.mdhv.asom.desktop.win.fakes.FakePdh
import xyz.mdhv.asom.desktop.win.fakes.LawCounter
import xyz.mdhv.asom.desktop.win.fakes.offsetOf
import xyz.mdhv.asom.desktop.win.gpu.GpuEngineProbe
import xyz.mdhv.asom.desktop.win.jna.PdhItemLayout
import xyz.mdhv.asom.desktop.win.jna.WtsInfoExLayout
import xyz.mdhv.asom.desktop.win.thermal.ThermalZoneProbe

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProbesTest {
    private val laws = LawCounter(
        listOf(
            "gpu-aggregate", "gpu-null-when-absent", "gpu-first-sample-null", "gpu-own-excluded", "gpu-busiest-engine",
            "thermal-no-source", "thermal-bands", "thermal-hysteresis", "thermal-implausible-ignored", "thermal-static-not-a-signal",
            "wts-parse", "layout-crosscheck",
        ),
    )

    private val me = 4242L
    private fun gpu(pid: Long, engine: Int, type: String, v: Double, luid: String = "0x00000000_0x0000E0A4", phys: Int = 0) =
        CounterValue("pid_${pid}_luid_${luid}_phys_${phys}_eng_${engine}_engtype_$type", v)

    // ---- GPU engine probe -----------------------------------------------------------------------------------------

    @Test
    fun `other-process GPU load is total minus own on the busiest engine, in permille`() {
        val s = GpuEngineProbe.aggregate(
            listOf(gpu(me, 0, "3D", 20.0), gpu(1111, 0, "3D", 35.5), gpu(2222, 0, "3D", 10.0), gpu(1111, 1, "VideoDecode", 5.0)),
            me,
        )!!
        assertEquals(655, s.deviceBusyPermille, "device: 20 + 35.5 + 10 = 65.5 %")
        assertEquals(200, s.ownBusyPermille)
        assertEquals(455, s.otherBusyPermille)
        laws.hit("gpu-aggregate")
        laws.hit("gpu-own-excluded")
    }

    @Test
    fun `the busiest engine of any adapter decides, engines are keyed by luid, phys, engine and type`() {
        val s = GpuEngineProbe.aggregate(
            listOf(
                gpu(1, 0, "3D", 30.0), gpu(2, 0, "3D", 30.0),                       // adapter A engine 3D: 60 %
                gpu(3, 0, "3D", 90.0, luid = "0x00000000_0x0000BEEF"),               // adapter B engine 3D: 90 %
                gpu(me, 0, "Compute_0", 50.0, luid = "0x00000000_0x0000BEEF"),       // own compute engine, separate key
            ),
            me,
        )!!
        assertEquals(900, s.otherBusyPermille)
        assertEquals(500, s.ownBusyPermille)
        laws.hit("gpu-busiest-engine")
    }

    @Test
    fun `skew above 100 percent is capped, own never exceeds total, and junk instances are ignored`() {
        val s = GpuEngineProbe.aggregate(listOf(gpu(1, 0, "3D", 80.0), gpu(2, 0, "3D", 70.0), CounterValue("garbage", 50.0), gpu(3, 5, "3D", Double.NaN), gpu(4, 6, "3D", -1.0)), me)!!
        assertEquals(1000, s.deviceBusyPermille)
        assertEquals(0, s.ownBusyPermille)
        assertEquals(1000, s.otherBusyPermille)
        assertNull(GpuEngineProbe.aggregate(listOf(CounterValue("garbage", 1.0)), me), "nothing parseable is null, not zero")
        assertNull(GpuEngineProbe.aggregate(emptyList(), me), "an empty list is null, not zero")
    }

    @Test
    fun `the probe is null when the counters are missing, and its first collection is null`() {
        assertNull(GpuEngineProbe.create(FakePdh(emptyMap()), me))
        laws.hit("gpu-null-when-absent")
        assertNull(GpuEngineProbe.create(object : xyz.mdhv.asom.desktop.win.api.PdhApi { override fun open(englishCounterPath: String) = error("boom") }, me))
        val pdh = FakePdh(mapOf(GpuEngineProbe.COUNTER_PATH to listOf(null, listOf(gpu(9, 0, "3D", 10.0)))))
        val probe = GpuEngineProbe.create(pdh, me)!!
        assertNull(probe.sample(), "a rate counter needs two collections")
        laws.hit("gpu-first-sample-null")
        assertEquals(100, probe.sample()!!.otherBusyPermille)
    }

    // ---- thermal --------------------------------------------------------------------------------------------------

    private fun kelvin(c: Double) = 273.15 + c
    private fun zones(vararg c: Double) = c.mapIndexed { i, t -> CounterValue("\\_TZ.TZ0$i", kelvin(t)) }

    private fun thermal(script: List<List<CounterValue>?>, clock: FakeClock = FakeClock(), cfg: NodeConfig = NodeConfig()): ThermalZoneProbe {
        val set = FakePdh(mapOf(ThermalZoneProbe.COUNTER_PATH to script)).open(ThermalZoneProbe.COUNTER_PATH)
        return ThermalZoneProbe(set, cfg, clock)
    }

    @Test
    fun `no counter means band 0, no sensors and no thermal signal`() {
        val p = ThermalZoneProbe(null)
        val r = p.read()
        assertEquals(0, r.band)
        assertTrue(r.watchedSensors.isEmpty())
        assertNull(r.hottestMilliC)
        assertTrue(!p.hasSignal)
        laws.hit("thermal-no-source")
        // counter exists but returns nothing valid
        val q = thermal(listOf(null))
        assertEquals(emptyList(), q.read().watchedSensors)
        assertTrue(!q.hasSignal)
    }

    @Test
    fun `hold at the threshold, queue within ten degrees below, run otherwise`() {
        val cfg = NodeConfig()
        val hold = cfg.thermalNoTripHoldMilliC
        fun band(c: Double) = thermal(listOf(zones(c))).read().band
        assertEquals(0, band(60.0))
        assertEquals(0, band(74.9))
        assertEquals(1, band(75.0))
        assertEquals(1, band(84.9))
        assertEquals(2, band(85.0))
        assertEquals(2, band(99.0))
        assertEquals(85_000, hold)
        val r = thermal(listOf(zones(50.0, 91.0))).read()
        assertEquals(2, r.band, "the hottest zone decides")
        assertEquals(91_000, r.hottestMilliC)
        assertEquals(85_000, r.holdThresholdMilliC)
        laws.hit("thermal-bands")
    }

    @Test
    fun `raising is immediate, lowering needs three degrees under and the dwell`() {
        val clock = FakeClock(0)
        val p = thermal(listOf(zones(90.0), zones(83.0), zones(81.9), zones(81.9), zones(81.9), zones(70.0)), clock)
        assertEquals(2, p.read().band)                                  // 90 C
        clock.now += 1_000
        assertEquals(2, p.read().band)                                  // 83 C: below the threshold but not 3 C under
        clock.now += 1_000
        assertEquals(2, p.read().band)                                  // 81.9 C: 3.1 C under, dwell starts
        clock.now += 9_000
        assertEquals(2, p.read().band)                                  // dwell 9 s < 10 s
        clock.now += 1_000
        assertEquals(1, p.read().band)                                  // dwell reached: lowered one step (81.9 is within 10 C -> queue)
        laws.hit("thermal-hysteresis")
    }

    @Test
    fun `implausible values are ignored and a stuck sensor is not a signal, though a stuck hot value still holds`() {
        val implausible = thermal(listOf(listOf(CounterValue("z", 0.0), CounterValue("y", -5.0), CounterValue("x", 900.0))))
        val r = implausible.read()
        assertEquals(0, r.band)
        assertTrue(r.watchedSensors.isEmpty(), "an implausible value is ignored, not read as cold")
        laws.hit("thermal-implausible-ignored")
        val stuck = thermal(listOf(zones(91.0)))
        var last = stuck.read()
        repeat(ThermalZoneProbe.STATIC_READS + 1) { last = stuck.read() }
        assertTrue(!stuck.hasSignal)
        assertEquals(2, last.band, "a stuck hot value keeps HOLD")
        laws.hit("thermal-static-not-a-signal")
        val moving = thermal((0..40).map { zones(50.0 + it * 0.1) })
        repeat(40) { moving.read() }
        assertTrue(moving.hasSignal)
        assertNotNull(ThermalZoneProbe.kelvinToMilliC(300.0))
        assertNull(ThermalZoneProbe.kelvinToMilliC(0.0))
        assertNull(ThermalZoneProbe.kelvinToMilliC(Double.NaN))
    }

    // ---- WTS parser and layout cross-checks --------------------------------------------------------------------------

    @Structure.FieldOrder("SessionId", "SessionState", "SessionFlags", "WinStationName", "UserName", "DomainName", "LogonTime", "ConnectTime", "DisconnectTime", "LastInputTime", "CurrentTime")
    class Level1 : Structure() {
        @JvmField var SessionId: Int = 0
        @JvmField var SessionState: Int = 0
        @JvmField var SessionFlags: Int = 0
        @JvmField var WinStationName = ShortArray(33)
        @JvmField var UserName = ShortArray(21)
        @JvmField var DomainName = ShortArray(18)
        @JvmField var LogonTime: Long = 0
        @JvmField var ConnectTime: Long = 0
        @JvmField var DisconnectTime: Long = 0
        @JvmField var LastInputTime: Long = 0
        @JvmField var CurrentTime: Long = 0
    }

    @Structure.FieldOrder("Level", "Data")
    class InfoEx : Structure() {
        @JvmField var Level: Int = 0
        @JvmField var Data: Level1 = Level1()
    }

    @Test
    fun `the WTSINFOEXW offsets in the parser equal the offsets of the C layout computed by JNA`() {
        assumeJna()
        val s = InfoEx()
        val d = s.offsetOf("Data")
        assertEquals(WtsInfoExLayout.LEVEL, s.offsetOf("Level"))
        assertEquals(WtsInfoExLayout.SESSION_ID, d + s.Data.offsetOf("SessionId"))
        assertEquals(WtsInfoExLayout.SESSION_STATE, d + s.Data.offsetOf("SessionState"))
        assertEquals(WtsInfoExLayout.SESSION_FLAGS, d + s.Data.offsetOf("SessionFlags"))
        assertEquals(WtsInfoExLayout.USER_NAME, d + s.Data.offsetOf("UserName"))
        assertEquals(WtsInfoExLayout.LAST_INPUT_TIME, d + s.Data.offsetOf("LastInputTime"))
        assertEquals(WtsInfoExLayout.CURRENT_TIME, d + s.Data.offsetOf("CurrentTime"))
        assertTrue(WtsInfoExLayout.MIN_SIZE <= s.size())
        laws.hit("layout-crosscheck")
    }

    @Structure.FieldOrder("szName", "CStatus", "value")
    class PdhItem : Structure() {
        @JvmField var szName: Pointer? = null
        @JvmField var CStatus: Int = 0
        @JvmField var value: Double = 0.0
    }

    @Test
    fun `the PDH item layout constants equal the offsets of the C layout computed by JNA`() {
        assumeJna()
        val s = PdhItem()
        assertEquals(PdhItemLayout.NAME_OFFSET, s.offsetOf("szName").toLong())
        assertEquals(PdhItemLayout.STATUS_OFFSET, s.offsetOf("CStatus").toLong())
        assertEquals(PdhItemLayout.VALUE_OFFSET, s.offsetOf("value").toLong())
        assertEquals(PdhItemLayout.ITEM_SIZE, s.size())
        laws.hit("layout-crosscheck")
    }

    private fun assumeJna() = org.junit.jupiter.api.Assumptions.assumeTrue(Native.POINTER_SIZE == 8, "64-bit JNA layout")

    private fun wtsBuffer(user: String, flags: Int, last: Long, now: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(240).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putInt(WtsInfoExLayout.LEVEL, 1)
        b.putInt(WtsInfoExLayout.SESSION_ID, 3)
        b.putInt(WtsInfoExLayout.SESSION_STATE, 0)
        b.putInt(WtsInfoExLayout.SESSION_FLAGS, flags)
        user.forEachIndexed { i, c -> b.putChar(WtsInfoExLayout.USER_NAME + 2 * i, c) }
        b.putLong(WtsInfoExLayout.LAST_INPUT_TIME, last)
        b.putLong(WtsInfoExLayout.CURRENT_TIME, now)
        return b.array()
    }

    @Test
    fun `WTSINFOEX parsing yields user, lock flag and idle milliseconds`() {
        val s = WtsInfoExLayout.parse(wtsBuffer("alice", 0, 1_000_000_000L, 1_000_000_000L + 6_000_000_000L))!!
        assertEquals("alice", s.userName)
        assertEquals(3, s.sessionId)
        assertEquals(true, s.lockFlagLocked)
        assertEquals(600_000L, s.idleMs, "6e9 hundred-nanosecond ticks = 600 s")
        assertEquals(false, WtsInfoExLayout.parse(wtsBuffer("bob", 1, 5, 10_005))!!.lockFlagLocked)
        assertNull(WtsInfoExLayout.parse(wtsBuffer("bob", 0xFFFF, 5, 10_005))!!.lockFlagLocked)
        assertNull(WtsInfoExLayout.parse(wtsBuffer("bob", 1, 0, 10_005))!!.idleMs, "no last input time is unknown, not zero")
        assertNull(WtsInfoExLayout.parse(wtsBuffer("bob", 1, 100, 50))!!.idleMs, "a clock that runs backwards is unknown")
        assertNull(WtsInfoExLayout.parse(ByteArray(100)))
        assertNull(WtsInfoExLayout.parse(wtsBuffer("x", 1, 1, 2).also { it[0] = 2 }), "only Level 1 is understood")
        assertEquals("", WtsInfoExLayout.parse(wtsBuffer("", 1, 1, 2))!!.userName)
        laws.hit("wts-parse")
    }

    @AfterAll
    fun nonVacuity() = laws.assertAllExercised("probes")
}
