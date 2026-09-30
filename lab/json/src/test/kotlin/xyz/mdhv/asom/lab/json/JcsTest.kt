package xyz.mdhv.asom.lab.json

import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JcsTest {
    private fun obj(vararg m: Pair<String, JValue>) = JObject(m.toList())

    @Test
    fun m01001TheUtf16OrderTrapMatchesTheDesignSessionSeed() {
        val seed = File(repoRoot(), "docs/design/mesh/conformance-examples/seed-vectors.json")
        val doc = assertAccepts(seed.readBytes(), "the seed file is strict JSON") as JObject
        val m01 = doc["M01"] as JObject
        val input = (m01["input_json_escaped"] as JString).value
        val expectHex = (m01["expect_utf8_hex"] as JString).value
        val wrong = (m01["trap_codepoint_order_WRONG"] as JString).value
        val out = Canonicalizer.canonicalize(input.toByteArray(Charsets.UTF_8))
        val canon = out as Canonicalizer.Result.Canonical
        assertEquals(expectHex, Hex.encode(canon.bytes))
        assertNotEquals(wrong, canon.text, "the code-point-order output is the trap")
        val text = canon.text
        val order = listOf("\"a\"", "\"b\"", "\"ctl\"", "\"nfd\"", "\"url\"", "\"\u00e9\"", "\"\uD83D\uDE00\"", "\"\uFF21\"")
        val positions = order.map { text.indexOf(it + ":") }
        assertTrue(positions.all { it >= 0 } && positions == positions.sorted(), "key order in $text")
        assertTrue(text.indexOf("\uD83D\uDE00") < text.indexOf("\uFF21"), "U+1F600 sorts before U+FF21 by UTF-16 code units")
    }

    @Test
    fun keysSortByUtf16CodeUnitsNotCodePoints() {
        val c = Cases("key order")
        val keys = listOf("\uFF21", "\uD83D\uDE00", "\uE000", "\uD800\uDC00", "\uD7FF", "\uFFFF", "\uDBFF\uDFFF", "\u00e9", "a", "")
        val sorted = Jcs.serializeToString(JObject(keys.map { it to JInt(1) }))
        val expectedOrder = listOf("", "a", "\u00e9", "\uD7FF", "\uD800\uDC00", "\uD83D\uDE00", "\uDBFF\uDFFF", "\uE000", "\uFF21", "\uFFFF")
        assertEquals(
            "{" + expectedOrder.joinToString(",") { "\"$it\":1" } + "}",
            sorted,
        )
        c.run { assertEquals(expectedOrder, keys.sortedWith { x, y -> Jcs.compareUtf16Units(x, y) }) }
        val codePointOrder = keys.sortedWith { x, y -> cmp(x.codePoints().toArray().toList(), y.codePoints().toArray().toList()) }
        c.run { assertNotEquals(expectedOrder, codePointOrder, "the two orders must differ on this key set, or the test is vacuous") }
        c.run { assertTrue(Jcs.compareUtf16Units("\uD83D\uDE00", "\uFF21") < 0) }
        c.run { assertTrue(Jcs.compareUtf16Units("\uD800\uDC00", "\uE000") < 0) }
        c.run { assertTrue(Jcs.compareUtf16Units("\uFF21", "\uD83D\uDE00") > 0) }
        c.run { assertTrue(Jcs.compareUtf16Units("a", "ab") < 0) }
        c.run { assertTrue(Jcs.compareUtf16Units("ab", "a") > 0) }
        c.run { assertEquals(0, Jcs.compareUtf16Units("x", "x")) }
        c.run { assertTrue(Jcs.compareUtf16Units("\u007f", "\u0080") < 0) }
        c.run { assertTrue(Jcs.compareUtf16Units("\uFFFF", "\uFFFE") > 0, "unsigned: 0xFFFF is larger than 0xFFFE") }
        c.requireNonVacuous(9)
    }

    private fun cmp(a: List<Int>, b: List<Int>): Int {
        for (k in 0 until minOf(a.size, b.size)) if (a[k] != b[k]) return a[k].compareTo(b[k])
        return a.size.compareTo(b.size)
    }

    @Test
    fun comparatorAgreesWithUtf16BigEndianBytesOnRandomKeys() {
        val rnd = Random(20260930)
        val alphabet = listOf("a", "b", "Z", "\u00e9", "\u0301", "\u2028", "\uD7FF", "\uE000", "\uFF21", "\uFFFF", "\uD83D\uDE00", "\uD800\uDC00", "\uDBFF\uDFFF", "e")
        val c = Cases("comparator")
        repeat(5000) {
            val a = (0 until rnd.nextInt(5)).joinToString("") { alphabet[rnd.nextInt(alphabet.size)] }
            val b = (0 until rnd.nextInt(5)).joinToString("") { alphabet[rnd.nextInt(alphabet.size)] }
            val viaBytes = java.util.Arrays.compareUnsigned(a.toByteArray(Charsets.UTF_16BE), b.toByteArray(Charsets.UTF_16BE))
            c.run { assertEquals(Integer.signum(viaBytes), Integer.signum(Jcs.compareUtf16Units(a, b)), "'$a' vs '$b'") }
        }
        c.requireNonVacuous(5000)
    }

    @Test
    fun stringEscapingFollowsTheProfileExactly() {
        val c = Cases("escapes")
        val expected = mapOf(
            '"' to "\\\"", '\\' to "\\\\", '\b' to "\\b", '\t' to "\\t", '\n' to "\\n", '\u000C' to "\\f", '\r' to "\\r",
        )
        for (code in 0 until 0x20) {
            val ch = code.toChar()
            val want = expected[ch] ?: ("\\u00" + "0123456789abcdef"[code shr 4] + "0123456789abcdef"[code and 15])
            c.run { assertEquals("\"$want\"", Jcs.serializeToString(JString(ch.toString())), "U+%04X".format(code)) }
        }
        c.run { assertEquals("\"\\\"\"", Jcs.serializeToString(JString("\""))) }
        c.run { assertEquals("\"\\\\\"", Jcs.serializeToString(JString("\\"))) }
        for (literal in listOf("/", "\u007f", "\u0080", "\u00a0", "\u2028", "\u2029", "\u00e9", "e\u0301", "\uFEFF", "\uFFFF", "\uD83D\uDE00", " ", "<>&'")) {
            c.run { assertEquals("\"$literal\"", Jcs.serializeToString(JString(literal)), "literal U+%04X".format(literal[0].code)) }
        }
        c.run { assertContentEquals(raw(0x22, 0x2f, 0x22), Jcs.serialize(JString("/"))) }
        c.run { assertContentEquals(cat("\"", 0xF0, 0x9F, 0x98, 0x80, "\""), Jcs.serialize(JString("\uD83D\uDE00"))) }
        c.run { assertContentEquals(cat("\"", 0x65, 0xCC, 0x81, "\""), Jcs.serialize(JString("e\u0301")), "NFD is not normalised to NFC") }
        c.run { assertContentEquals(cat("\"", 0xC3, 0xA9, "\""), Jcs.serialize(JString("\u00e9")), "NFC is not normalised to NFD") }
        c.requireNonVacuous(50)
    }

    @Test
    fun integersAreInShortestDecimalForm() {
        val c = Cases("integers")
        for ((v, s) in listOf(0L to "0", 1L to "1", -1L to "-1", 10L to "10", 100L to "100", -100L to "-100", MAX_SAFE_INT to "9007199254740991", -MAX_SAFE_INT to "-9007199254740991")) {
            c.run { assertEquals(s, Jcs.serializeToString(JInt(v))) }
        }
        c.run { assertFailsWith<IllegalArgumentException> { JInt(MAX_SAFE_INT + 1) } }
        c.run { assertFailsWith<IllegalArgumentException> { JInt(-MAX_SAFE_INT - 1) } }
        c.run { assertFailsWith<IllegalArgumentException> { JInt(Long.MAX_VALUE) } }
        c.run { assertFailsWith<IllegalArgumentException> { JInt(Long.MIN_VALUE) } }
        c.requireNonVacuous(12)
    }

    @Test
    fun structureHasNoWhitespaceAndArraysKeepOrder() {
        val c = Cases("structure")
        c.run { assertEquals("{}", canonical(" { } ")) }
        c.run { assertEquals("[]", canonical("\r\n[\t]\n")) }
        c.run { assertEquals("[3,1,2]", canonical("[ 3 , 1 , 2 ]")) }
        c.run { assertEquals("{\"a\":[{\"a\":1,\"b\":2}],\"b\":{\"x\":null,\"y\":true,\"z\":false}}", canonical("{\"b\":{\"z\":false,\"y\":true,\"x\":null},\"a\":[{\"b\":2,\"a\":1}]}")) }
        c.run { assertEquals("{\"\":0,\"a\":0,\"aa\":0,\"ab\":0,\"b\":0}", canonical("{\"b\":0,\"aa\":0,\"\":0,\"ab\":0,\"a\":0}"), ) }
        c.run { assertEquals("\"/\"", canonical("\"\\/\""), "an escaped slash is written literally") }
        c.run { assertEquals("\"\uD83D\uDE00\"", canonical("\"\\ud83d\\ude00\""), "an escaped pair is written literally") }
        c.run { assertEquals("\"\u00e9\"", canonical("\"\\u00E9\"")) }
        c.run { assertEquals("\"\\u0000\"", canonical("\"\\u0000\"")) }
        c.run { assertEquals("\"\\u001f\"", canonical("\"\\u001F\""), "hex digits are lowercase") }
        c.run { assertEquals("[true,false,null,0,-1]", canonical("[true,false,null,0,-1]")) }
        c.requireNonVacuous(11)
    }

    @Test
    fun serialisingAnOverDeepValueIsRefused() {
        var v: JValue = JInt(1)
        repeat(16) { v = JArray(listOf(v)) }
        assertEquals("[".repeat(16) + "1" + "]".repeat(16), Jcs.serializeToString(v))
        v = JArray(listOf(v))
        assertFailsWith<IllegalArgumentException> { Jcs.serializeToString(v) }
        var o: JValue = JInt(1)
        repeat(17) { o = JObject(listOf("a" to o)) }
        assertFailsWith<IllegalArgumentException> { Jcs.serializeToString(o) }
    }

    @Test
    fun aValueCannotBeBuiltOutsideTheProfile() {
        assertFailsWith<IllegalArgumentException> { JString("\uD800") }
        assertFailsWith<IllegalArgumentException> { JString("\uDC00") }
        assertFailsWith<IllegalArgumentException> { JString("a\uD800b") }
        assertFailsWith<IllegalArgumentException> { JString("\uDC00\uD800") }
        JString("\uD800\uDC00")
        assertFailsWith<IllegalArgumentException> { JObject(listOf("\uD800" to JNull)) }
        assertFailsWith<IllegalArgumentException> { JObject(listOf("a" to JNull, "a" to JNull)) }
        assertFailsWith<IllegalArgumentException> { JObject(listOf("\u00e9" to JNull, "\u00e9" to JInt(1))) }
        JObject(listOf("\u00e9" to JNull, "e\u0301" to JNull))
    }

    @Test
    fun objectEqualityIgnoresMemberOrderButNotContent() {
        val a = obj("x" to JInt(1), "y" to JArray(listOf(JInt(2), JInt(3))))
        val b = obj("y" to JArray(listOf(JInt(2), JInt(3))), "x" to JInt(1))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, obj("x" to JInt(1), "y" to JArray(listOf(JInt(3), JInt(2)))))
        assertNotEquals(a, obj("x" to JInt(1)))
        assertEquals(listOf("x", "y"), a.members.map { it.first }, "members keep the order given")
        assertEquals(JInt(1), a["x"])
        assertEquals(null, a["nope"])
    }

    @Test
    fun canonicalOutputIsAlwaysWellFormedUtf8AndReparsesToTheSameValue() {
        val c = Cases("reparse")
        for (text in listOf("{\"\uD83D\uDE00\":\"\uD83D\uDE00\"}", "[\"e\u0301\",\"\u00e9\"]", "{\"\\u0000\":\"\\u001f\"}")) {
            val first = Canonicalizer.canonicalize(text.toByteArray(Charsets.UTF_8)) as Canonicalizer.Result.Canonical
            c.run { assertTrue(Utf8Strict.isValid(first.bytes)) }
            val again = Canonicalizer.canonicalize(first.bytes) as Canonicalizer.Result.Canonical
            c.run { assertEquals(first.value, again.value) }
            c.run { assertContentEquals(first.bytes, again.bytes) }
        }
        c.requireNonVacuous(9)
    }
}
