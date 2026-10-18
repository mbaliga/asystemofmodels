package xyz.mdhv.asom.lab.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.JsonRejectCode.DUPLICATE_KEY
import xyz.mdhv.asom.lab.json.JsonRejectCode.INVALID_UNICODE
import xyz.mdhv.asom.lab.json.JsonRejectCode.MALFORMED_JSON
import xyz.mdhv.asom.lab.json.JsonRejectCode.NON_INTEGER_NUMBER
import xyz.mdhv.asom.lab.json.JsonRejectCode.NUMBER_RANGE
import xyz.mdhv.asom.lab.json.JsonRejectCode.TRAILING_DATA

/** One accept boundary and one reject boundary for every row of the LAB_SPEC 4.2 table, and many more around them. */
class RejectTableTest {

    @Test
    fun row1MalformedJson() {
        val c = Cases("row 1")
        for (t in listOf(
            "", " ", "\t\r\n", "{", "}", "[", "]", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "{,}", "{1:2}", "{\"a\" 1}", "[1,]", "[,1]", "[1 2]",
            "tru", "nul", "fals", "True", "NULL", "'a'", "\"abc", "\"a\\", "\"a\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\uzzzz\"",
            "+1", ".5", "\u000c{}", "{\"a\":1]", "[1}", ":", ",", "{\"a\":1 \"b\":2}", "truefalse", "nullx", "[truex]",
        )) c.run { assertRejects(MALFORMED_JSON, t, "malformed '$t'") }
        c.run { assertRejects(MALFORMED_JSON, cat("\"", 0x01, "\""), "raw U+0001 in a string") }
        c.run { assertRejects(MALFORMED_JSON, cat("\"", 0x09, "\""), "raw tab in a string") }
        c.run { assertRejects(MALFORMED_JSON, cat("\"", 0x0A, "\""), "raw LF in a string") }
        c.run { assertRejects(MALFORMED_JSON, cat("\"", 0x1F, "\""), "raw U+001F in a string") }
        c.run { assertRejects(MALFORMED_JSON, cat(0x0B, "{}"), "vertical tab is not whitespace") }
        c.run { assertRejects(MALFORMED_JSON, cat(0xC2, 0xA0, "{}"), "NBSP is not whitespace") }
        c.run { assertRejects(MALFORMED_JSON, cat(0x00, "{}"), "NUL is not whitespace") }
        c.run { assertRejects(MALFORMED_JSON, cat(" ", 0xEF, 0xBB, 0xBF, "{}"), "BOM after whitespace is not whitespace") }
        c.run { assertRejects(MALFORMED_JSON, cat("[1,", 0xFF, "]"), "invalid byte in a structural position is a syntax error (row 1 before row 2)") }
        for (t in listOf(
            "null", "true", "false", "0", "-1", "\"\"", "[]", "{}", "[null]", "{\"a\":null}", " \t\r\n{} \t\r\n", "\r\n[1,\t2]\n", "{\"a\" : [ 1 , 2 ] }",
            "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000\\u001f\\u00e9\\uD83D\\ude00\"",
        )) c.run { assertAccepts(t, "well-formed '$t'") }
        c.requireNonVacuous(50)
    }

    @Test
    fun row1ByteOrderMark() {
        val c = Cases("BOM")
        val bom = raw(0xEF, 0xBB, 0xBF)
        c.run { assertRejects(MALFORMED_JSON, cat(bom, "{}"), "BOM then object") }
        c.run { assertRejects(MALFORMED_JSON, cat(bom, "null"), "BOM then null") }
        c.run { assertRejects(MALFORMED_JSON, bom, "BOM alone") }
        c.run { assertRejects(MALFORMED_JSON, cat(bom, bom, "{}"), "two BOMs") }
        c.run { assertRejects(MALFORMED_JSON, cat(bom, "{\"a\":1,\"a\":2}"), "BOM outranks a duplicate key") }
        c.run { assertRejects(MALFORMED_JSON, cat(bom, "{} x"), "BOM outranks trailing data") }
        c.run { assertRejects(MALFORMED_JSON, cat(bom, "\"\\ud800\""), "BOM outranks a lone surrogate") }
        c.run { assertRejects(MALFORMED_JSON, cat(bom, 0xFF), "BOM outranks invalid UTF-8") }
        // U+FEFF inside a string is ordinary content, not a BOM.
        c.run { assertEquals(JString("\uFEFF"), assertAccepts(cat("\"", bom, "\""), "U+FEFF inside a string")) }
        // Two bytes of a BOM are not a BOM.
        c.run { assertRejects(MALFORMED_JSON, raw(0xEF, 0xBB), "truncated BOM") }
        c.requireNonVacuous(9)
    }

    @Test
    fun row2InvalidUtf8() {
        val c = Cases("row 2")
        val bad = listOf(
            "overlong NUL C0 80" to raw(0xC0, 0x80),
            "overlong slash C0 AF" to raw(0xC0, 0xAF),
            "overlong C1 BF" to raw(0xC1, 0xBF),
            "overlong 3-byte E0 80 80" to raw(0xE0, 0x80, 0x80),
            "overlong 3-byte E0 9F BF" to raw(0xE0, 0x9F, 0xBF),
            "overlong 4-byte F0 80 80 80" to raw(0xF0, 0x80, 0x80, 0x80),
            "overlong 4-byte F0 8F BF BF" to raw(0xF0, 0x8F, 0xBF, 0xBF),
            "encoded surrogate D800" to raw(0xED, 0xA0, 0x80),
            "encoded surrogate DBFF" to raw(0xED, 0xAF, 0xBF),
            "encoded surrogate DC00" to raw(0xED, 0xB0, 0x80),
            "encoded surrogate DFFF" to raw(0xED, 0xBF, 0xBF),
            "above U+10FFFF F4 90 80 80" to raw(0xF4, 0x90, 0x80, 0x80),
            "F5 lead" to raw(0xF5, 0x80, 0x80, 0x80),
            "F8 lead" to raw(0xF8, 0x88, 0x80, 0x80, 0x80),
            "FE" to raw(0xFE),
            "FF" to raw(0xFF),
            "stray continuation 80" to raw(0x80),
            "stray continuation BF" to raw(0xBF),
            "truncated 2-byte" to raw(0xC3),
            "truncated 3-byte E2 82" to raw(0xE2, 0x82),
            "truncated 4-byte F0 9F 98" to raw(0xF0, 0x9F, 0x98),
            "lead then ASCII" to raw(0xC3, 0x41),
            "3-byte with bad second E2 28 A1" to raw(0xE2, 0x28, 0xA1),
            "3-byte with bad third E2 82 28" to raw(0xE2, 0x82, 0x28),
            "4-byte with bad fourth F0 90 80 28" to raw(0xF0, 0x90, 0x80, 0x28),
        )
        for ((name, seq) in bad) {
            c.run { assertRejects(INVALID_UNICODE, cat("\"", seq, "\""), "$name in a string value") }
            c.run { assertRejects(INVALID_UNICODE, cat("{\"", seq, "\":1}"), "$name in a member name") }
            c.run { assertRejects(INVALID_UNICODE, cat("{} ", seq), "$name after the value beats trailing data") }
            c.run { assertEquals(0, Utf8Strict.firstInvalid(seq), "$name: the first bad byte is the first byte") }
            c.run { assertTrue(!Utf8Strict.isValid(seq), "$name is not valid UTF-8") }
        }
        val good = listOf(
            "U+007F" to raw(0x7F), "U+0080" to raw(0xC2, 0x80), "U+07FF" to raw(0xDF, 0xBF), "U+0800" to raw(0xE0, 0xA0, 0x80),
            "U+D7FF" to raw(0xED, 0x9F, 0xBF), "U+E000" to raw(0xEE, 0x80, 0x80), "U+FFFD" to raw(0xEF, 0xBF, 0xBD),
            "U+FFFE" to raw(0xEF, 0xBF, 0xBE), "U+FFFF" to raw(0xEF, 0xBF, 0xBF), "U+10000" to raw(0xF0, 0x90, 0x80, 0x80),
            "U+1F600" to raw(0xF0, 0x9F, 0x98, 0x80), "U+10FFFF" to raw(0xF4, 0x8F, 0xBF, 0xBF),
        )
        for ((name, seq) in good) {
            c.run { assertAccepts(cat("\"", seq, "\""), "$name is well formed") }
            c.run { assertTrue(Utf8Strict.isValid(seq), "$name is valid UTF-8") }
        }
        c.run { assertEquals(3, Utf8Strict.firstInvalid(cat("ab ", 0xFF))) }
        c.run { assertEquals(-1, Utf8Strict.firstInvalid(ByteArray(0))) }
        c.requireNonVacuous(150)
    }

    @Test
    fun row2InvalidUtf8LosesToSyntaxErrorsAndWinsOverEverythingElse() {
        val c = Cases("row 2 order")
        c.run { assertRejects(MALFORMED_JSON, cat("{\"a\":\"", 0xFF, "\""), "unterminated object with bad byte") }
        c.run { assertRejects(MALFORMED_JSON, cat("\"", 0xFF), "unterminated string with bad byte") }
        c.run { assertRejects(INVALID_UNICODE, cat("[\"", 0xFF, "\",\"\\ud800\"]"), "row 2 before row 3") }
        c.run { assertRejects(INVALID_UNICODE, cat("[\"", 0xFF, "\",1.5]"), "row 2 before row 4") }
        c.run { assertRejects(INVALID_UNICODE, cat("{\"a\":1,\"a\":\"", 0xFF, "\"}"), "row 2 before row 6") }
        c.requireNonVacuous(5)
    }

    @Test
    fun row3LoneSurrogateEscapes() {
        val c = Cases("row 3")
        for (t in listOf(
            "\"\\ud800\"", "\"\\udbff\"", "\"\\udc00\"", "\"\\udfff\"", "\"\\uD800\"", "\"\\ud800\\ud800\"", "\"\\udc00\\ud800\"",
            "\"\\udc00\\udc00\"", "\"\\ud800x\"", "\"\\ud800\\u0041\"", "\"\\ud800\\n\"", "\"x\\udc00\"", "\"\\ud800\\\\ude00\"",
            "\"\\ude00\\ud83d\"",
        )) c.run { assertRejects(INVALID_UNICODE, t, "lone surrogate in $t") }
        c.run { assertRejects(INVALID_UNICODE, "{\"\\ud800\":1}", "lone surrogate in a member name") }
        c.run { assertRejects(INVALID_UNICODE, "[[{\"a\":\"\\udfff\"}]]", "lone surrogate deep in the tree") }
        c.run { assertAccepts("\"\\ud83d\\ude00\"", "a valid pair") }
        c.run { assertAccepts("\"\\uD83D\\uDE00\"", "a valid pair in upper case") }
        c.run { assertAccepts("\"\\ud800\\udc00\"", "the first supplementary code point U+10000") }
        c.run { assertAccepts("\"\\udbff\\udfff\"", "the last code point U+10FFFF") }
        c.run { assertAccepts("\"\\ud7ff\\ue000\"", "the code points around the surrogate block") }
        c.run { assertEquals(JString("\uD83D\uDE00"), assertAccepts("\"\\ud83d\\ude00\"")) }
        c.run { assertEquals(JString("\uD83D\uDE00"), assertAccepts("\"\uD83D\uDE00\"")) }
        c.run { assertRejects(INVALID_UNICODE, "[\"\\ud800\",1.5]", "row 3 before row 4") }
        c.run { assertRejects(INVALID_UNICODE, "{\"a\":\"\\ud800\",\"a\":1}", "row 3 before row 6") }
        c.run { assertRejects(MALFORMED_JSON, "[\"\\ud800\",tru]", "row 1 before row 3") }
        c.requireNonVacuous(20)
    }

    @Test
    fun row4NonIntegerNumbers() {
        val c = Cases("row 4")
        for (t in listOf(
            "1.5", "1.0", "0.0", "-0.5", "1e2", "1E2", "1e+2", "1e-2", "1E+2", "0e0", "1.5e3", "-0", "-0.0", "-0e0", "NaN", "Infinity", "-Infinity",
            "01", "-01", "00", "007", "1.", "1e", "1e+", "-", "--1", "1-2", "1+2", "1.2.3", "0.", "12e", "[1.5]", "{\"a\":1e2}", "[0,-0]", "[01]", "[-]",
            "[NaN]", "[Infinity,-Infinity]",
        )) c.run { assertRejects(NON_INTEGER_NUMBER, t, "non-integer '$t'") }
        for (t in listOf("0", "-1", "1", "10", "100", "1000000", "-10", "[0,1,-1]", "{\"a\":10}", "9007199254740991", "-9007199254740991", "9", "90")) {
            c.run { assertAccepts(t, "integer '$t'") }
        }
        c.run { assertEquals(JInt(-1), assertAccepts("-1")) }
        c.run { assertEquals(JInt(0), assertAccepts("0")) }
        c.run { assertRejects(NON_INTEGER_NUMBER, "[1.5,9007199254740992]", "row 4 before row 5") }
        c.run { assertRejects(NON_INTEGER_NUMBER, "[1.5,{\"a\":1,\"a\":2}]", "row 4 before row 6") }
        c.run { assertRejects(NON_INTEGER_NUMBER, "[9007199254740992,1.5]", "row 4 wins wherever it sits") }
        c.requireNonVacuous(50)
    }

    @Test
    fun row5NumberRange() {
        val c = Cases("row 5")
        val over = listOf(
            "9007199254740992", "-9007199254740992", "9007199254740993", "10000000000000000", "-10000000000000000",
            "99999999999999999999", "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809",
            "1" + "0".repeat(400), "-" + "9".repeat(400),
        )
        for (t in over) {
            c.run { assertRejects(NUMBER_RANGE, t, "out of range $t") }
            c.run { assertRejects(NUMBER_RANGE, "[$t]", "out of range in an array $t") }
            c.run { assertRejects(NUMBER_RANGE, "{\"a\":$t}", "out of range in an object $t") }
        }
        c.run { assertEquals(JInt(MAX_SAFE_INT), assertAccepts("9007199254740991")) }
        c.run { assertEquals(JInt(-MAX_SAFE_INT), assertAccepts("-9007199254740991")) }
        c.run { assertEquals(JInt(9007199254740990L), assertAccepts("9007199254740990")) }
        c.run { assertEquals(JInt(1000000000000000L), assertAccepts("1000000000000000")) }
        c.run { assertRejects(NUMBER_RANGE, "[9007199254740992,{\"a\":1,\"a\":2}]", "row 5 before row 6") }
        c.run { assertRejects(NUMBER_RANGE, "[9007199254740992] x", "row 5 before row 8") }
        c.run { assertRejects(NON_INTEGER_NUMBER, "[-0,9007199254740992]", "-0 is row 4 even next to a row 5 number") }
        c.requireNonVacuous(40)
    }

    @Test
    fun row6DuplicateKeys() {
        val c = Cases("row 6")
        for (t in listOf(
            "{\"a\":1,\"a\":2}", "{\"a\":1,\"a\":1}", "{\"a\":1,\"b\":2,\"a\":3}", "{\"\":1,\"\":2}", "{\"a\":1,\"\\u0061\":2}", "{\"\\u0061\":1,\"a\":2}",
            "{\"x\":{\"a\":1,\"a\":1}}", "[{\"a\":1,\"a\":2}]", "{\"o\":[{\"k\":null,\"k\":null}]}", "{\"\\ud83d\\ude00\":1,\"\uD83D\uDE00\":2}",
            "{\"a\\/b\":1,\"a/b\":2}", "{\"\\n\":1,\"\\u000a\":2}", "{\"é\":1,\"\\u00e9\":2}",
            "{\"a\":1,\"a\":{\"a\":1,\"a\":1}}",
        )) c.run { assertRejects(DUPLICATE_KEY, t, "duplicate in $t") }
        for (t in listOf(
            "{\"a\":1,\"A\":2}", "{\"a\":1,\"a \":2}", "{\"a\":{\"a\":1}}", "{\"a\":[{\"a\":1},{\"a\":2}]}", "[{\"a\":1},{\"a\":2}]", "{\"\":1,\" \":2}",
            "{\"e\\u0301\":1,\"\\u00e9\":2}", "{\"a\":1,\"b\":{\"a\":1,\"b\":2}}",
        )) c.run { assertAccepts(t, "no duplicate in $t") }
        c.run { assertRejects(DUPLICATE_KEY, "{\"a\":1,\"a\":2} ", "row 6 with harmless trailing whitespace") }
        c.run { assertRejects(DUPLICATE_KEY, "{\"a\":1,\"a\":2} x", "row 6 before row 8") }
        c.run { assertRejects(DUPLICATE_KEY, "[{\"a\":1,\"a\":2}]", "duplicate inside an array") }
        c.run { assertRejects(DUPLICATE_KEY, nestedObjects(16, "1").replace("{\"a\":1}", "{\"a\":1,\"a\":1}"), "duplicate at depth 16") }
        c.requireNonVacuous(20)
    }

    @Test
    fun row7Depth() {
        val c = Cases("row 7")
        for (d in listOf(1, 2, 8, 15, 16)) {
            c.run { assertAccepts(nested("[", "]", d), "$d nested arrays") }
            c.run { assertAccepts(nestedObjects(d), "$d nested objects") }
            c.run { assertAccepts(nested("[", "]", d, "1"), "$d nested arrays around a scalar") }
        }
        for (d in listOf(17, 18, 32, 100, 5000)) {
            c.run { assertRejects(MALFORMED_JSON, nested("[", "]", d), "$d nested arrays") }
            c.run { assertRejects(MALFORMED_JSON, nestedObjects(d), "$d nested objects") }
            c.run { assertRejects(MALFORMED_JSON, nested("[", "]", d, "null"), "$d nested arrays around a scalar") }
        }
        c.run { assertAccepts("[{\"a\":".repeat(8) + "1" + "}]".repeat(8), "mixed nesting of exactly 16") }
        c.run { assertRejects(MALFORMED_JSON, "[{\"a\":".repeat(8) + "[1]" + "}]".repeat(8), "mixed nesting of 17") }
        c.run { assertRejects(MALFORMED_JSON, nested("[", "]", 17) + " x", "row 7 before row 8") }
        c.run { assertRejects(NUMBER_RANGE, "[" + nested("[", "]", 17) + ",9007199254740992]", "row 5 before row 7") }
        c.run { assertRejects(DUPLICATE_KEY, "[" + nested("[", "]", 17) + ",{\"a\":1,\"a\":1}]", "row 6 before row 7") }
        c.run { assertRejects(MALFORMED_JSON, "[".repeat(200_000), "a very deep unterminated input is malformed, and does not overflow the stack") }
        c.run { assertRejects(MALFORMED_JSON, "[".repeat(200_000) + "]".repeat(200_000), "a very deep closed input is rejected for depth, and does not overflow the stack") }
        c.requireNonVacuous(30)
    }

    @Test
    fun row8TrailingData() {
        val c = Cases("row 8")
        for (t in listOf("{} x", "1 2", "{}{}", "[] ,", "null null", "\"a\"\"b\"", "1x", "{}]", "[1]]", "0 0", "true false", "{}\u0000", "{}\u000b", "{}\u000c", "{}\u00a0", "{}\u2028", "{}\ufeff", "[] // c")) {
            c.run { assertRejects(TRAILING_DATA, t, "trailing data in '$t'") }
        }
        c.run { assertRejects(TRAILING_DATA, cat("{}", 0x00), "NUL after the value") }
        for (t in listOf("{}", "{} ", "{}\t", "{}\r", "{}\n", "{} \t\r\n \t\r\n", "  1  ", "\r\nnull\r\n")) c.run { assertAccepts(t, "only whitespace after '$t'") }
        c.requireNonVacuous(20)
    }

    @Test
    fun everyRowHasItsOwnCodeAndTheCodesAreExactlySix() {
        assertEquals(
            listOf("MALFORMED_JSON", "INVALID_UNICODE", "NON_INTEGER_NUMBER", "NUMBER_RANGE", "DUPLICATE_KEY", "TRAILING_DATA"),
            JsonRejectCode.entries.map { it.name },
        )
    }
}
