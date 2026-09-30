package xyz.mdhv.asom.clientcloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mdhv.asom.contract.Policy

/**
 * §10A.1: a body that works on `RemoteAsom` must work on `CloudOnly`, so the
 * §5.5 virtual selectors resolve here instead of being compared as literals.
 */
class CloudRoutingTest {

    private val openrouter = CloudProvider("openrouter", "https://openrouter.ai/api/v1", listOf("llama-3.3-70b"))
    private val groq = CloudProvider("groq", "https://api.groq.com/openai/v1", listOf("llama-3.1-8b"))

    private fun keyed(vararg providers: CloudProvider) = providers.map { CloudRouting.Keyed(it, "sk-test-${it.id}") }

    private fun body(model: String, extra: String = "") =
        """{"model":"$model","messages":[{"role":"user","content":"hi"}]$extra}"""

    private fun modelOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["model"]?.jsonPrimitive?.content

    private fun codeOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    @Test
    fun `every virtual selector resolves to the first keyed provider's first model`() {
        for (virtual in Policy.VIRTUAL_MODELS - Policy.LOCAL_ONLY.wire) {
            val route = CloudRouting.route(body(virtual), keyed(openrouter, groq))
            val upstream = assertIs<CloudRouting.Route.Upstream>(route, "selector '$virtual' was refused")
            assertEquals("openrouter", upstream.provider.id)
            assertEquals("llama-3.3-70b", upstream.model)
            assertEquals("llama-3.3-70b", modelOf(upstream.bodyJson))
        }
    }

    @Test
    fun `resolution skips keyed providers that declare no models`() {
        val unlisted = CloudProvider("unlisted", "https://example.test/v1")
        val route = CloudRouting.route(body("cheapest"), keyed(unlisted, groq))
        val upstream = assertIs<CloudRouting.Route.Upstream>(route)
        assertEquals("groq", upstream.provider.id)
        assertEquals("llama-3.1-8b", upstream.model)
    }

    @Test
    fun `a virtual selector is never forwarded upstream verbatim`() {
        val unlisted = CloudProvider("unlisted", "https://example.test/v1")
        val route = CloudRouting.route(body("cheapest"), keyed(unlisted))
        val refused = assertIs<CloudRouting.Route.Refused>(route)
        assertEquals(404, refused.response.status)
        assertEquals("MODEL_UNKNOWN", codeOf(refused.response.bodyJson))
    }

    @Test
    fun `local-only fails the way the daemon fails it`() {
        val refused = assertIs<CloudRouting.Route.Refused>(
            CloudRouting.route(body("local-only"), keyed(openrouter)),
        )
        assertEquals(501, refused.response.status)
        assertEquals("LOCAL_ENGINE_ABSENT", codeOf(refused.response.bodyJson))
    }

    @Test
    fun `local-only is refused even with no keys stored`() {
        val refused = assertIs<CloudRouting.Route.Refused>(CloudRouting.route(body("local-only"), emptyList()))
        assertEquals(501, refused.response.status)
    }

    @Test
    fun `rewriting the model leaves every other field verbatim`() {
        val route = CloudRouting.route(
            body("cheapest", ""","temperature":0.7,"some_exotic_field":{"x":1}"""),
            keyed(openrouter),
        )
        val upstream = assertIs<CloudRouting.Route.Upstream>(route)
        val obj = Json.parseToJsonElement(upstream.bodyJson).jsonObject
        assertEquals("0.7", obj["temperature"]?.jsonPrimitive?.content)
        assertTrue(obj.containsKey("some_exotic_field"))
        assertTrue(obj.containsKey("messages"))
    }

    @Test
    fun `a concrete model routes to a provider that serves it, body untouched`() {
        val request = body("llama-3.1-8b")
        val upstream = assertIs<CloudRouting.Route.Upstream>(CloudRouting.route(request, keyed(openrouter, groq)))
        assertEquals("groq", upstream.provider.id)
        assertEquals("llama-3.1-8b", upstream.model)
        assertEquals(request, upstream.bodyJson)
    }

    @Test
    fun `no stored key still yields NO_PROVIDER_KEY`() {
        val refused = assertIs<CloudRouting.Route.Refused>(CloudRouting.route(body("cheapest"), emptyList()))
        assertEquals(503, refused.response.status)
        assertEquals("NO_PROVIDER_KEY", codeOf(refused.response.bodyJson))
    }

    @Test
    fun `streaming flag is set without disturbing the resolved model`() {
        val upstream = assertIs<CloudRouting.Route.Upstream>(CloudRouting.route(body("auto"), keyed(openrouter)))
        val streamed = Json.parseToJsonElement(CloudRouting.withStreaming(upstream.bodyJson)).jsonObject
        assertEquals(true, streamed["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("llama-3.3-70b", streamed["model"]?.jsonPrimitive?.content)
    }
}
