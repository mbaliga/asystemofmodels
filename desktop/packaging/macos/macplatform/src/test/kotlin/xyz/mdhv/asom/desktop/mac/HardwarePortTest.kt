package xyz.mdhv.asom.desktop.mac

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.mac.net.Eligibility
import xyz.mdhv.asom.desktop.mac.net.HardwarePort
import xyz.mdhv.asom.desktop.mac.net.HardwarePorts
import xyz.mdhv.asom.desktop.mac.net.InterfaceEligibility
import xyz.mdhv.asom.desktop.mac.net.InterfaceKind
import xyz.mdhv.asom.desktop.mac.net.InterfaceLister
import xyz.mdhv.asom.desktop.mac.net.InterfaceSnapshot
import xyz.mdhv.asom.desktop.mac.net.ListenBinding
import xyz.mdhv.asom.desktop.mac.net.PortClassifyingLister
import xyz.mdhv.asom.desktop.mac.net.Reresolution

/** B-LA-2: a BSD name `enN` does not say Wi-Fi or Ethernet (an iPhone USB tether, a Bluetooth PAN and a bridge are all `enN`); the system's port list does. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HardwarePortTest {
    private val laws = LawCounter(listOf("port-classify", "port-parse", "lan-refuses-port", "lan-accepts-port", "reresolve-port", "lister"))

    @AfterAll
    fun report() = laws.assertAllExercised("hardware-port")

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)
    private fun snap(name: String, port: HardwarePort, vararg addrs: String) = InterfaceSnapshot(name, true, addrs.map(::ip), port)

    @Test
    fun `a USB-tethered iPhone, a Bluetooth PAN, a cellular port and an unknown port are refused as a LAN even when confirmed`() {
        val ifs = listOf(
            snap("en5", HardwarePort.OTHER, "172.20.10.2"),
            snap("en6", HardwarePort.OTHER, "192.168.44.2"),
            snap("en7", HardwarePort.CELLULAR, "10.20.30.40"),
            snap("en8", HardwarePort.UNKNOWN, "192.168.1.9"),
        )
        for (n in listOf("en5", "en6", "en7", "en8")) {
            assertIs<Eligibility.Refuse>(InterfaceEligibility.resolveLan(ifs, n, true), n)
            laws.hit("lan-refuses-port")
        }
        assertTrue((InterfaceEligibility.resolveLan(ifs, "en7", true) as Eligibility.Refuse).reason.contains("cellular"))
        for ((p, a) in listOf(HardwarePort.WIFI to "192.168.1.5", HardwarePort.ETHERNET to "10.0.0.7")) {
            assertIs<Eligibility.Bind>(InterfaceEligibility.resolveLan(listOf(snap("en0", p, a)), "en0", true), "$p")
            laws.hit("lan-accepts-port")
        }
    }

    @Test
    fun `a bound LAN interface whose port turns out not to be Wi-Fi or Ethernet is lost on re-resolve`() {
        val lan = ListenBinding(InterfaceKind.LAN_CANDIDATE, "en0", ip("192.168.1.5"))
        assertIs<Reresolution.Same>(InterfaceEligibility.reresolve(lan, listOf(snap("en0", HardwarePort.WIFI, "192.168.1.5")), true))
        for (p in listOf(HardwarePort.OTHER, HardwarePort.CELLULAR, HardwarePort.UNKNOWN)) {
            assertIs<Reresolution.Lost>(InterfaceEligibility.reresolve(lan, listOf(snap("en0", p, "192.168.1.5")), true), "$p")
            laws.hit("reresolve-port")
        }
    }

    @Test
    fun `port names are classified, tethers first, and an unrecognised or localised name is OTHER`() {
        val table = mapOf(
            "Wi-Fi" to HardwarePort.WIFI, "AirPort" to HardwarePort.WIFI, "WLAN" to HardwarePort.WIFI,
            "Ethernet" to HardwarePort.ETHERNET, "Thunderbolt Ethernet Slot 1" to HardwarePort.ETHERNET, "USB 10/100/1000 LAN" to HardwarePort.ETHERNET,
            "iPhone USB" to HardwarePort.OTHER, "iPad USB" to HardwarePort.OTHER, "iPhone USB Ethernet" to HardwarePort.OTHER,
            "Bluetooth PAN" to HardwarePort.OTHER, "Thunderbolt Bridge" to HardwarePort.OTHER,
            "Cellular" to HardwarePort.CELLULAR, "USB LTE Modem" to HardwarePort.CELLULAR, "WWAN" to HardwarePort.CELLULAR,
            "Drahtlos" to HardwarePort.OTHER, "" to HardwarePort.OTHER, "FireWire" to HardwarePort.OTHER,
        )
        for ((n, p) in table) {
            assertEquals(p, HardwarePorts.classify(n), n)
            laws.hit("port-classify")
        }
    }

    private val listing = """
        Hardware Port: Ethernet Adapter (en4)
        Device: en4
        Ethernet Address: aa:bb:cc:dd:ee:01

        Hardware Port: Wi-Fi
        Device: en0
        Ethernet Address: aa:bb:cc:dd:ee:02

        Hardware Port: iPhone USB
        Device: en5
        Ethernet Address: aa:bb:cc:dd:ee:03

        Hardware Port: Thunderbolt Bridge
        Device: bridge0
        Ethernet Address: N/A

        VLAN Configurations
        ===================
    """.trimIndent()

    @Test
    fun `networksetup output is parsed into a device to port map`() {
        val m = HardwarePorts.parse(listing)
        assertEquals(mapOf("en4" to HardwarePort.ETHERNET, "en0" to HardwarePort.WIFI, "en5" to HardwarePort.OTHER, "bridge0" to HardwarePort.OTHER), m)
        assertEquals(emptyMap(), HardwarePorts.parse(""))
        assertEquals(emptyMap(), HardwarePorts.parse("Device: en0\nHardware Port: Wi-Fi\n"), "a device line with no port line before it names nothing")
        laws.hit("port-parse", 3)
    }

    @Test
    fun `the lister adds ports, and a missing listing leaves every port unknown so nothing is bound`() {
        val base = InterfaceLister {
            listOf(
                InterfaceSnapshot("en0", true, listOf(ip("192.168.1.5"))),
                InterfaceSnapshot("en5", true, listOf(ip("172.20.10.2"))),
                InterfaceSnapshot("en9", true, listOf(ip("192.168.7.7"))),
            )
        }
        val got = PortClassifyingLister(base) { listing }.list().associate { it.name to it.port }
        assertEquals(mapOf("en0" to HardwarePort.WIFI, "en5" to HardwarePort.OTHER, "en9" to HardwarePort.UNKNOWN), got)
        laws.hit("lister")
        for (none in listOf<() -> String?>({ null }, { error("networksetup missing") })) {
            val l = PortClassifyingLister(base, none).list()
            assertTrue(l.all { it.port == HardwarePort.UNKNOWN })
            assertIs<Eligibility.Refuse>(InterfaceEligibility.resolveLan(l, "en0", true))
            laws.hit("lister")
        }
        val withPorts = PortClassifyingLister(base) { listing }.list()
        assertIs<Eligibility.Bind>(InterfaceEligibility.resolveLan(withPorts, "en0", true))
        assertIs<Eligibility.Refuse>(InterfaceEligibility.resolveLan(withPorts, "en5", true))
    }
}
