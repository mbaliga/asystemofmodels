package xyz.mdhv.asom.server.driver

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.catalogue.AuthSpec
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException

class AnthropicDriverTest {

    private lateinit var mock: MockWebServer
    private val driver = AnthropicDriver(clock = { 1_720_000_000_000 })

    private fun provider() = ProviderEntry(
        id = "anthropic",
        displayName = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = mock.url("").toString().trimEnd('/'),
        auth = AuthSpec("bearer"),
        trainsOnData = false,
        programmaticAllowed = true,
    )

    @BeforeEach
    fun setUp() {
        mock = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        mock.shutdown()
    }

    @Test
    fun `translates the text-chat subset and maps the response`() = runBlocking {
        mock.enqueue(
            MockResponse().setBody(
                """{"id":"msg_1","type":"message","role":"assistant",
                   "content":[{"type":"text","text":"bonjour"}],
                   "model":"claude-sonnet-4-5","stop_reason":"end_turn",
                   "usage":{"input_tokens":11,"output_tokens":5}}""",
            ),
        )
        val body = Json.parseToJsonElement(
            """{"model":"claude-sonnet-4-5",
                "messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"}],
                "temperature":0.5,"stop":"END","max_tokens":100}""",
        ).jsonObject

        val outcome = driver.chat(provider(), "sk-ant-test", body, stream = false)

        val recorded = mock.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val sent = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("be brief", sent["system"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, sent["messages"]?.jsonArray?.size) // system message extracted
        assertEquals(100, sent["max_tokens"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals("END", sent["stop_sequences"]?.jsonArray?.get(0)?.jsonPrimitive?.contentOrNull)
        assertNull(sent["stop"])

        val json = assertIs<DriverOutcome.Json>(outcome)
        val choice = json.body["choices"]!!.jsonArray[0].jsonObject
        assertEquals("bonjour", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull)
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.contentOrNull)
        assertEquals(11, json.usage?.promptTokens)
        assertEquals(5, json.usage?.completionTokens)
    }

    @Test
    fun `max_tokens defaults when absent`() = runBlocking {
        mock.enqueue(MockResponse().setBody("""{"id":"m","content":[],"usage":{"input_tokens":1,"output_tokens":1}}"""))
        driver.chat(
            provider(), "k",
            Json.parseToJsonElement("""{"model":"c","messages":[{"role":"user","content":"x"}]}""").jsonObject,
            stream = false,
        )
        val sent = Json.parseToJsonElement(mock.takeRequest().body.readUtf8()).jsonObject
        assertEquals(4096, sent["max_tokens"]?.jsonPrimitive?.contentOrNull?.toInt())
    }

    @Test
    fun `untranslatable fields fail loudly BEFORE any bytes leave`() = runBlocking {
        val body = Json.parseToJsonElement(
            """{"model":"c","messages":[{"role":"user","content":"x"}],"tools":[{"type":"function"}]}""",
        ).jsonObject
        val ex = assertFailsWith<AsomException> { driver.chat(provider(), "k", body, false) }
        assertEquals(AsomErrorCode.UNSUPPORTED_BY_DRIVER, ex.code)
        assertTrue("tools" in ex.message)
        assertEquals(0, mock.requestCount) // nothing egressed ✓
    }

    @Test
    fun `embeddings are unsupported by the anthropic driver`() = runBlocking {
        val ex = assertFailsWith<AsomException> {
            driver.embeddings(provider(), "k", Json.parseToJsonElement("""{"model":"c","input":"x"}""").jsonObject)
        }
        assertEquals(AsomErrorCode.UNSUPPORTED_BY_DRIVER, ex.code)
    }

    @Test
    fun `429 is retryable`() = runBlocking {
        mock.enqueue(MockResponse().setResponseCode(429).setBody("""{"type":"error"}"""))
        val err = assertIs<DriverOutcome.Error>(
            driver.chat(
                provider(), "k",
                Json.parseToJsonElement("""{"model":"c","messages":[{"role":"user","content":"x"}]}""").jsonObject,
                false,
            ),
        )
        assertTrue(err.retryable)
    }

    @Test
    fun `anthropic SSE re-maps to chat completion chunks with usage and DONE`() = runBlocking {
        val sse = buildString {
            append("event: message_start\n")
            append("""data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":9,"output_tokens":0}}}""")
            append("\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"salut "}}""")
            append("\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"monde"}}""")
            append("\n\n")
            append("event: message_delta\n")
            append("""data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":4}}""")
            append("\n\n")
            append("event: message_stop\n")
            append("""data: {"type":"message_stop"}""")
            append("\n\n")
        }
        mock.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        val body = Json.parseToJsonElement(
            """{"model":"claude-sonnet-4-5","stream":true,"messages":[{"role":"user","content":"x"}]}""",
        ).jsonObject
        val outcome = driver.chat(provider(), "k", body, stream = true)
        val events = assertIs<DriverOutcome.Stream>(outcome).events.toList()
            .joinToString("") { it.toString(Charsets.UTF_8) }
        val dataLines = events.lines().filter { it.startsWith("data: ") }

        assertEquals("data: [DONE]", dataLines.last())
        val chunks = dataLines.dropLast(1).map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
        chunks.forEach { assertEquals("chat.completion.chunk", it["object"]!!.jsonPrimitive.contentOrNull) }

        val text = chunks.joinToString("") {
            it["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
        }
        assertEquals("salut monde", text)

        val final = chunks.last()
        assertEquals(
            "length",
            final["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.contentOrNull,
        )
        val usage = final["usage"]!!.jsonObject
        assertEquals(9, usage["prompt_tokens"]!!.jsonPrimitive.contentOrNull!!.toInt())
        assertEquals(4, usage["completion_tokens"]!!.jsonPrimitive.contentOrNull!!.toInt())
    }
}
