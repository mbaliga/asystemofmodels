package xyz.mdhv.asom.server.driver

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.mdhv.asom.catalogue.AuthSpec
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.catalogue.ProviderKind

class OpenAICompatDriverTest {

    private lateinit var mock: MockWebServer
    private val driver = OpenAICompatDriver()

    private fun provider() = ProviderEntry(
        id = "mock",
        displayName = "Mock",
        kind = ProviderKind.OPENAI_COMPAT,
        baseUrl = mock.url("/v1").toString(),
        auth = AuthSpec("bearer"),
        trainsOnData = false,
        programmaticAllowed = true,
    )

    private fun body(extra: String = "") = Json.parseToJsonElement(
        """{"model":"m1","messages":[{"role":"user","content":"hi"}]$extra}""",
    ).jsonObject

    @BeforeEach
    fun setUp() {
        mock = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        mock.shutdown()
    }

    @Test
    fun `non-streaming chat posts verbatim body with bearer auth and parses usage`() = runBlocking {
        mock.enqueue(
            MockResponse().setBody(
                """{"id":"x","object":"chat.completion","created":1,"model":"m1",
                   "choices":[{"index":0,"message":{"role":"assistant","content":"hey"},"finish_reason":"stop"}],
                   "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""",
            ),
        )
        val requestBody = body(""","temperature":0.7,"some_exotic_field":{"x":1}""")
        val outcome = driver.chat(provider(), "sk-test", requestBody, stream = false)

        val recorded = mock.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        // §5.9 pass-through: body forwarded VERBATIM, exotic fields intact.
        assertEquals(requestBody.toString(), recorded.body.readUtf8())

        val json = assertIs<DriverOutcome.Json>(outcome)
        assertEquals(7, json.usage?.promptTokens)
        assertEquals(3, json.usage?.completionTokens)
    }

    @Test
    fun `429 and 5xx are retryable, 4xx is fatal`() = runBlocking {
        mock.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate"}"""))
        val rateLimited = assertIs<DriverOutcome.Error>(driver.chat(provider(), "k", body(), false))
        assertTrue(rateLimited.retryable)
        assertEquals(429, rateLimited.status)

        mock.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"down"}"""))
        assertTrue(assertIs<DriverOutcome.Error>(driver.chat(provider(), "k", body(), false)).retryable)

        mock.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad"}"""))
        val bad = assertIs<DriverOutcome.Error>(driver.chat(provider(), "k", body(), false))
        assertFalse(bad.retryable)
        assertTrue("bad" in bad.bodyText)
    }

    @Test
    fun `streaming is byte-level pass-through`() = runBlocking {
        val sse = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hel\"}}]}\n\n" +
            "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":2,\"total_tokens\":4}}\n\n" +
            "data: [DONE]\n\n"
        mock.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        val outcome = driver.chat(provider(), "k", body(), stream = true)
        val stream = assertIs<DriverOutcome.Stream>(outcome)
        val forwarded = stream.events.toList().joinToString("") { it.toString(Charsets.UTF_8) }
        // Byte-identical forwarding (§5.9).
        assertEquals(sse, forwarded)
    }

    @Test
    fun `embeddings hits the embeddings path`() = runBlocking {
        mock.enqueue(
            MockResponse().setBody(
                """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.5]}],
                   "model":"m1","usage":{"prompt_tokens":2,"total_tokens":2}}""",
            ),
        )
        val outcome = driver.embeddings(
            provider(), "k",
            Json.parseToJsonElement("""{"model":"m1","input":"x"}""").jsonObject,
        )
        assertEquals("/v1/embeddings", mock.takeRequest().path)
        val json = assertIs<DriverOutcome.Json>(outcome)
        assertEquals(
            "list",
            json.body["object"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
