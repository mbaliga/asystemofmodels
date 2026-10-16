package xyz.mdhv.asom.desktop.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.mdhv.asom.desktop.Report

class StrictJsonTest {
    private val rejects = listOf(
        "1.0" to "fraction", "1e3" to "exponent", "1E3" to "exponent", "-0" to "negative zero", "01" to "leading zero",
        "9007199254740992" to "beyond 2^53-1", "-9007199254740992" to "beyond -(2^53-1)", "99999999999999999999" to "huge",
        "{\"a\":1,\"a\":2}" to "duplicate name", "{\"a\":1,}" to "trailing comma", "[1,]" to "trailing comma", "" to "empty",
        "{} x" to "trailing content", "\"\\ud800\"" to "lone high surrogate", "\"\\udc00\"" to "lone low surrogate",
        "\"a\u0001b\"" to "raw control char", "NaN" to "NaN", "+1" to "plus sign", "{'a':1}" to "single quotes",
        "[".repeat(40) + "]".repeat(40) to "too deep", "\"\\x\"" to "bad escape",
    )
    private val accepts = listOf(
        "0", "-1", "9007199254740991", "-9007199254740991", "true", "null", "\"\\ud83d\\ude00\"", "{\"a\":[1,2,{\"b\":null}]}", " { } ",
        "[" .repeat(30) + "]".repeat(30),
    )

    @Test
    fun `integer profile rejects every non-integer form and accepts the integer forms`() {
        var rejected = 0
        var accepted = 0
        for ((text, why) in rejects) {
            assertFailsWith<StrictJsonException>("should reject ($why): $text") { StrictJson.parse(text) }
            rejected++
        }
        for (text in accepts) {
            StrictJson.parse(text)
            accepted++
        }
        Report.line("strict-json: $rejected reject vectors, $accepted accept vectors")
        assertTrue(rejected > 0 && accepted > 0, "non-vacuity")
    }

    @Test
    fun `the largest safe integers parse exactly`() {
        val o = StrictJson.parse("{\"hi\":9007199254740991,\"lo\":-9007199254740991}") as JsonObject
        assertEquals(9007199254740991L, (o["hi"] as JsonPrimitive).longOrNull)
        assertEquals(-9007199254740991L, (o["lo"] as JsonPrimitive).longOrNull)
    }
}
