package xyz.mdhv.asom.client

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.Capabilities

/**
 * The SSE transport is plain JVM (no Context), so the stream's real behavior is
 * testable against a loopback server.
 */
class AsomChatStreamTest {

    private fun sse(vararg payloads: String): String =
        payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun chat(server: MockWebServer) = AsomChat(
        AsomEndpoint(port = server.port, version = "test", capabilities = Capabilities.v1(0)),
        token = "test-token",
    )

    private fun <T> withServer(block: (MockWebServer) -> T): T {
        val server = MockWebServer().also { it.start() }
        return try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `chunks are the SSE data payloads, DONE excluded`() = withServer { server ->
        server.enqueue(MockResponse().setBody(sse("""{"i":1}""", """{"i":2}""")))
        val stream = chat(server).stream("""{"model":"cheapest","messages":[]}""")
        runBlocking {
            assertEquals(listOf("""{"i":1}""", """{"i":2}"""), stream.chunks.toList())
        }
    }

    @Test
    fun `the returned stream can be collected more than once`() = withServer { server ->
        server.enqueue(MockResponse().setBody(sse("""{"i":1}""")))
        server.enqueue(MockResponse().setBody(sse("""{"i":2}""")))
        val stream = chat(server).stream("""{"model":"cheapest","messages":[]}""")
        runBlocking {
            assertEquals(listOf("""{"i":1}"""), stream.chunks.toList())
            assertEquals(listOf("""{"i":2}"""), stream.chunks.toList())
        }
    }

    @Test
    fun `echo headers are readable once chunks have arrived`() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setHeader(AsomHeaders.SERVED_BY, "openrouter/llama-3.3-70b")
                .setHeader(AsomHeaders.EGRESS, "cloud")
                .setBody(sse("""{"i":1}""")),
        )
        val stream = chat(server).stream("""{"model":"cheapest","messages":[]}""")
        runBlocking { stream.chunks.toList() }
        assertEquals("openrouter/llama-3.3-70b", stream.headers.servedBy)
        assertEquals("cloud", stream.headers.egress)
    }

    @Test
    fun `a daemon that dies mid-stream fails the collect without poisoning the stream`() = withServer { server ->
        val long = sse(*(1..16).map { """{"i":$it}""" }.toTypedArray())
        server.enqueue(MockResponse().setBody(long).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setBody(sse("""{"i":99}""")))
        val stream = chat(server).stream("""{"model":"cheapest","messages":[]}""")
        runBlocking {
            assertFailsWith<IOException> { stream.chunks.toList() }
            assertEquals(listOf("""{"i":99}"""), stream.chunks.toList())
        }
    }
}
