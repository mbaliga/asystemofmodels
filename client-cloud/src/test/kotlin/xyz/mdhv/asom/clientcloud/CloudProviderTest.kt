package xyz.mdhv.asom.clientcloud

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Invariant §1.2: no cleartext exception beyond localhost, keys included. */
class CloudProviderTest {

    @Test
    fun `an http baseUrl is rejected at wiring time`() {
        val e = assertFailsWith<IllegalArgumentException> {
            CloudProvider("openrouter", "http://openrouter.ai/api/v1", listOf("llama-3.3-70b"))
        }
        assertTrue(e.message!!.contains("https"))
    }

    @Test
    fun `an https baseUrl is accepted`() {
        CloudProvider("openrouter", "https://openrouter.ai/api/v1", listOf("llama-3.3-70b"))
    }
}
