package xyz.mdhv.asom.lab.json

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A-F2: past [StrictJson.MAX_DEPTH] the parser must not allocate a frame per level. The bound is on bytes allocated by the
 * parsing thread (so it does not depend on GC timing or on the heap the build gives the test JVM), and a wall-clock bound that
 * is generous for a shared CI runner.
 */
class DeepInputTest {
    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private class Measured(val result: ParseResult, val allocated: Long, val millis: Long)

    private fun measure(input: ByteArray): Measured {
        assertTrue(mx.isThreadAllocatedMemorySupported, "the JVM cannot report per-thread allocation, so the memory bound cannot be checked")
        mx.isThreadAllocatedMemoryEnabled = true
        StrictJson.parse("[1]".toByteArray())
        val before = mx.currentThreadAllocatedBytes
        val t0 = System.nanoTime()
        val r = StrictJson.parse(input)
        val ms = (System.nanoTime() - t0) / 1_000_000
        return Measured(r, mx.currentThreadAllocatedBytes - before, ms)
    }

    private fun checkBounded(c: Cases, what: String, input: ByteArray, code: JsonRejectCode, maxBytes: Long = MAX_ALLOCATED, maxMillis: Long = MAX_MILLIS) {
        c.run {
            val m = measure(input)
            val r = m.result
            assertTrue(r is ParseResult.Reject, "$what: expected a reject, got $r")
            assertEquals(code, (r as ParseResult.Reject).code, "$what: $r")
            assertTrue(m.allocated <= maxBytes, "$what: allocated ${m.allocated} bytes for ${input.size} input bytes, bound $maxBytes")
            assertTrue(m.millis <= maxMillis, "$what: took ${m.millis} ms, bound $maxMillis ms")
        }
    }

    @Test
    fun millionLevelsAreRejectedWithBoundedTimeAndMemory() {
        val c = Cases("deep input bound")
        val n = 1_000_000
        checkBounded(c, "1,000,000 open arrays", "[".repeat(n).toByteArray(), JsonRejectCode.MALFORMED_JSON)
        checkBounded(c, "1,000,000 closed arrays", (nested("[", "]", n)).toByteArray(), JsonRejectCode.MALFORMED_JSON)
        checkBounded(c, "1,000,000 closed arrays around 1.5", nested("[", "]", n, "1.5").toByteArray(), JsonRejectCode.NON_INTEGER_NUMBER)
        checkBounded(c, "1,000,000 closed arrays around a lone surrogate", nested("[", "]", n, "\"\\ud800\"").toByteArray(), JsonRejectCode.INVALID_UNICODE)
        checkBounded(c, "1,000,000 closed arrays around a big integer", nested("[", "]", n, "9007199254740992").toByteArray(), JsonRejectCode.NUMBER_RANGE)
        checkBounded(c, "1,000,000 closed arrays then trailing data", (nested("[", "]", n) + " x").toByteArray(), JsonRejectCode.MALFORMED_JSON)
        val objects = 250_000
        checkBounded(c, "250,000 open objects", "{\"a\":".repeat(objects).toByteArray(), JsonRejectCode.MALFORMED_JSON)
        checkBounded(c, "250,000 closed objects", ("{\"a\":".repeat(objects) + "1" + "}".repeat(objects)).toByteArray(), JsonRejectCode.MALFORMED_JSON)
        checkBounded(
            c, "250,000 objects with a duplicate name at the bottom",
            ("{\"a\":".repeat(objects) + "{\"b\":1,\"b\":2}" + "}".repeat(objects)).toByteArray(), JsonRejectCode.DUPLICATE_KEY,
        )
        checkBounded(
            c, "500,000 alternating containers",
            ("[{\"a\":".repeat(objects) + "1" + "}]".repeat(objects)).toByteArray(), JsonRejectCode.MALFORMED_JSON,
        )
        c.requireNonVacuous(10)
    }

    @Test
    fun deepInputsKeepTheVerdictOfTheLowerRows() {
        val c = Cases("deep verdicts")
        for (d in listOf(17, 18, 64, 5_000, 30_000, 200_000)) {
            c.run { assertRejects(JsonRejectCode.NON_INTEGER_NUMBER, nested("[", "]", d, "1.5"), "depth $d around 1.5") }
            c.run { assertRejects(JsonRejectCode.NON_INTEGER_NUMBER, nested("[", "]", d, "-0"), "depth $d around -0") }
            c.run { assertRejects(JsonRejectCode.NON_INTEGER_NUMBER, nested("[", "]", d, "NaN"), "depth $d around NaN") }
            c.run { assertRejects(JsonRejectCode.NON_INTEGER_NUMBER, nested("[", "]", d, "-Infinity"), "depth $d around -Infinity") }
            c.run { assertRejects(JsonRejectCode.NUMBER_RANGE, nested("[", "]", d, "9007199254740992"), "depth $d around 2^53") }
            c.run { assertRejects(JsonRejectCode.INVALID_UNICODE, nested("[", "]", d, "\"\\udc00\""), "depth $d around a lone surrogate") }
            c.run { assertRejects(JsonRejectCode.DUPLICATE_KEY, nested("[", "]", d, "{\"a\":1,\"a\":2}"), "depth $d around a duplicate") }
            c.run { assertRejects(JsonRejectCode.DUPLICATE_KEY, nested("[", "]", d, "{\"a\":1,\"b\":2,\"c\":3,\"a\":4}"), "depth $d, duplicate after other names") }
            c.run { assertRejects(JsonRejectCode.DUPLICATE_KEY, nested("[", "]", d, "{\"e\\u0301\":1,\"e\\u0301\":2}"), "depth $d, duplicate after unescape") }
            c.run { assertRejects(JsonRejectCode.DUPLICATE_KEY, nested("[", "]", d, "{\"\\u0061\":1,\"a\":2}"), "depth $d, escaped first name duplicates a plain one") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "{\"a\":1,\"A\":2}"), "depth $d, case-different names are not duplicates") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "{\"a\":{\"a\":1},\"b\":{\"a\":1}}"), "depth $d, same name in sibling objects") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "[{\"a\":1,\"b\":2},{\"a\":3,\"b\":4}]"), "depth $d, the names of a closed object are forgotten") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "{\"x\":{\"a\":1,\"b\":2},\"y\":{\"a\":3,\"b\":4}}"), "depth $d, sibling objects with the same names") }
            c.run { assertRejects(JsonRejectCode.DUPLICATE_KEY, nested("[", "]", d, "{\"x\":{\"a\":1,\"b\":2},\"y\":1,\"x\":2}"), "depth $d, a duplicate after a closed child") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "1") + " x", "depth $d then trailing data is still row 7") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "1").dropLast(1), "depth $d, one close missing") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "1").dropLast(1) + "}", "depth $d, mismatched close") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "[1,]"), "depth $d, trailing comma") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "1.5").dropLast(1) + "}", "depth $d, an array closed by }, with a fraction inside") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "[1.5}"), "depth $d, an inner array closed by }, with a fraction inside") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "{\"a\":1.5]"), "depth $d, an object closed by ], with a fraction inside") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "{\"a\"x1.5}"), "depth $d, a missing colon, with a fraction inside") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "[1.5,]"), "depth $d, a trailing comma, with a fraction inside") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "[1.5 2]"), "depth $d, a missing comma, with a fraction inside") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "{\"a\":1.5,}"), "depth $d, a trailing comma in an object, with a fraction inside") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "{\"a\" 1}"), "depth $d, missing colon") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "{1:1}"), "depth $d, non-string name") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "tru"), "depth $d, unknown literal") }
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, nested("[", "]", d, "\"a\nb\""), "depth $d, control character") }
        }
        c.run {
            val bytes = cat("[".repeat(40_000), "\"", 0xC3, "\"", "]".repeat(40_000))
            assertRejects(JsonRejectCode.INVALID_UNICODE, bytes, "depth 40,000 around a truncated UTF-8 sequence")
        }
        c.requireNonVacuous(100)
    }

    private companion object {
        const val MAX_ALLOCATED = 12L * 1024 * 1024
        const val MAX_MILLIS = 8_000L
    }
}
