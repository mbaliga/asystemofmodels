package xyz.mdhv.asom.lab.json

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Bytes from pieces: a String is its UTF-8 bytes, a ByteArray is taken as is, an Int is one raw byte. */
fun cat(vararg parts: Any): ByteArray {
    val out = ByteArrayOutputStream()
    for (p in parts) {
        when (p) {
            is String -> out.write(p.toByteArray(Charsets.UTF_8))
            is ByteArray -> out.write(p)
            is Int -> out.write(p)
            else -> error("bad piece $p")
        }
    }
    return out.toByteArray()
}

fun raw(vararg unsigned: Int): ByteArray = ByteArray(unsigned.size) { unsigned[it].toByte() }

fun parseText(text: String): ParseResult = StrictJson.parse(text.toByteArray(Charsets.UTF_8))

fun assertRejects(code: JsonRejectCode, input: ByteArray, why: String = "") {
    when (val r = StrictJson.parse(input)) {
        is ParseResult.Reject -> assertEquals(code, r.code, "$why: expected $code but got $r for ${Hex.encode(input.copyOf(minOf(input.size, 64)))}")
        is ParseResult.Ok -> fail("$why: expected $code but the input was accepted: ${Hex.encode(input.copyOf(minOf(input.size, 64)))}")
    }
}

fun assertRejects(code: JsonRejectCode, text: String, why: String = "") = assertRejects(code, text.toByteArray(Charsets.UTF_8), why)

fun assertAccepts(input: ByteArray, why: String = ""): JValue {
    val r = StrictJson.parse(input)
    if (r !is ParseResult.Ok) fail("$why: expected accept but got $r for ${Hex.encode(input.copyOf(minOf(input.size, 64)))}")
    return r.value
}

fun assertAccepts(text: String, why: String = ""): JValue = assertAccepts(text.toByteArray(Charsets.UTF_8), why)

fun canonical(text: String): String = when (val r = Canonicalizer.canonicalize(text.toByteArray(Charsets.UTF_8))) {
    is Canonicalizer.Result.Canonical -> r.text
    is Canonicalizer.Result.Rejected -> fail("rejected ${r.code}: $text")
}

/** Every case a test runs is counted; a family that ran nothing fails (LAB_SPEC R10). */
class Cases(private val name: String) {
    var n = 0
        private set

    fun run(body: () -> Unit) {
        n++
        body()
    }

    fun requireNonVacuous(min: Int = 1) {
        assertTrue(n >= min, "non-vacuity: '$name' exercised $n cases, needed at least $min")
    }
}

fun repoRoot(): File = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set (run through Gradle)")).canonicalFile

fun nested(open: String, close: String, depth: Int, leaf: String = ""): String = open.repeat(depth) + leaf + close.repeat(depth)

/** The `{"a":{"a":...}}` shape with [depth] objects. */
fun nestedObjects(depth: Int, leaf: String = "1"): String = "{\"a\":".repeat(depth - 1) + "{\"a\":$leaf}" + "}".repeat(depth - 1)

inline fun <reified T : JValue> JValue.asA(): T {
    assertIs<T>(this)
    return this
}
