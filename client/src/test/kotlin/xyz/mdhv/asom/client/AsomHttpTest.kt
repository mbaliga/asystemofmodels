package xyz.mdhv.asom.client

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards against a revert to bare `OkHttpClient()`: its 10 s read timeout is
 * shorter than the 180 s the daemon itself waits upstream, so the app would
 * abandon completions the user is already being billed for.
 */
class AsomHttpTest {

    @Test
    fun `the default client outlives the daemon's own upstream budget`() {
        val client = asomDefaultHttpClient()
        assertTrue(client.readTimeoutMillis >= 180_000, "read timeout was ${client.readTimeoutMillis} ms")
        assertTrue(client.connectTimeoutMillis in 1..30_000)
        assertTrue(client.writeTimeoutMillis >= 30_000)
    }
}
