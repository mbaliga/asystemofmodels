package xyz.mdhv.asom.server.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §8 redaction law on the one v1 path that stores raw bodies (§9 verbose mode). */
class VerboseRedactorTest {

    @Test
    fun `the served provider's exact key is struck out wherever it appears`() {
        val key = "or-v1-0123456789abcdef"
        val body = """{"error":{"message":"bad key $key"},"retry":true}"""
        val out = VerboseRedactor.redact(body, listOf(key))
        assertFalse(key in out)
        assertTrue(VerboseRedactor.REDACTED in out)
        assertTrue("\"retry\":true" in out, "redaction must not mangle the rest of the body")
    }

    @Test
    fun `key-shaped and bearer-shaped material is struck out even when unknown`() {
        val out = VerboseRedactor.redact(
            """{"a":"sk-proj-AbCdEf0123456789","b":"Bearer abcdef0123456789"}""",
            emptyList(),
        )
        assertFalse("sk-proj-AbCdEf0123456789" in out)
        assertFalse("abcdef0123456789" in out)
    }

    @Test
    fun `a credential-named JSON field is struck out whatever its value looks like`() {
        val out = VerboseRedactor.redact(
            """{"api_key":"hunter2","Authorization":"whatever","x-api-key":"q","keep":"visible"}""",
            emptyList(),
        )
        assertFalse("hunter2" in out)
        assertFalse("whatever" in out)
        assertTrue("\"keep\":\"visible\"" in out)
    }

    @Test
    fun `an escaped quote inside a credential value does not end the redaction early`() {
        val out = VerboseRedactor.redact("""{"api_key":"a\"b","next":"safe"}""", emptyList())
        assertFalse("a\\\"b" in out)
        assertTrue("\"next\":\"safe\"" in out)
    }

    @Test
    fun `a body at or under the ceiling is stored whole`() {
        val exact = "y".repeat(BodySink.MAX_BODY_CHARS)
        assertEquals(exact, VerboseRedactor.bound(exact))
    }

    @Test
    fun `a body over the ceiling is truncated and says so`() {
        val huge = "y".repeat(BodySink.MAX_BODY_CHARS + 500)
        val out = VerboseRedactor.bound(huge)
        assertTrue(out.length < BodySink.MAX_BODY_CHARS + 64)
        assertTrue("truncated 500 chars" in out)
    }
}
