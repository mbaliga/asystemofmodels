package xyz.mdhv.asom.contract

import kotlin.test.Test
import kotlin.test.assertEquals

class AsomTest {

    @Test
    fun `default port is 11435`() {
        assertEquals(11435, Asom.DEFAULT_PORT)
    }

    @Test
    fun `bind host is loopback only`() {
        assertEquals("127.0.0.1", Asom.BIND_HOST)
    }
}
