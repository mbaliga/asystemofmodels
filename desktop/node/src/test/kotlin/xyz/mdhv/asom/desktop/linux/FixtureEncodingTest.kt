package xyz.mdhv.asom.desktop.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureEncodingTest {
    private val real = "hid-0003:28DE:1205.0001-battery"

    @Test
    fun `the code under test sees the real colon name while the repository stores it encoded`() {
        val fs = Fixtures.fs("deck-oled")
        val names = fs.list("/sys/class/power_supply")
        assertTrue(real in names, "expected the decoded name in $names")
        assertTrue(names.none { COLON_ENCODED in it }, "an encoded name leaked into the code under test: $names")
        assertEquals("Device", fs.read("/sys/class/power_supply/$real/scope")?.trim())
    }

    @Test
    fun `no fixture file name on disk contains a colon (Windows cannot store one)`() {
        for (host in Fixtures.hosts) {
            Fixtures.dir(host).toFile().walkTopDown().forEach {
                assertTrue(':' !in it.name, "a colon in a checked-in fixture name would break every Windows checkout: ${it.path}")
            }
        }
    }

    @Test
    fun `a map-backed copy of the fixture also uses the real name`() {
        val m = MapFileSource.ofFixture("deck-oled")
        assertTrue(m.list("/sys/class/power_supply").contains(real))
    }
}
