package xyz.mdhv.asom.lab.json

import kotlin.test.Test
import xyz.mdhv.asom.lab.json.JsonRejectCode.DUPLICATE_KEY
import xyz.mdhv.asom.lab.json.JsonRejectCode.INVALID_UNICODE
import xyz.mdhv.asom.lab.json.JsonRejectCode.MALFORMED_JSON
import xyz.mdhv.asom.lab.json.JsonRejectCode.NON_INTEGER_NUMBER
import xyz.mdhv.asom.lab.json.JsonRejectCode.NUMBER_RANGE
import xyz.mdhv.asom.lab.json.JsonRejectCode.TRAILING_DATA

/**
 * The spec says the checks run "in this order". Every unordered pair of table rows is combined in ONE document, in both
 * placements, and the answer must be the code of the earlier row wherever the two conditions sit.
 */
class CheckOrderTest {
    /** A poison for each row: where it goes in `[ <element> , <element> ] <tail>`. */
    private class Poison(val row: Int, val code: JsonRejectCode, val element: ByteArray? = null, val tail: ByteArray = ByteArray(0))

    private val poisons = listOf(
        Poison(1, MALFORMED_JSON, element = cat("tru")),
        Poison(2, INVALID_UNICODE, element = cat("\"", 0xFF, "\"")),
        Poison(3, INVALID_UNICODE, element = cat("\"\\ud800\"")),
        Poison(4, NON_INTEGER_NUMBER, element = cat("1.5")),
        Poison(5, NUMBER_RANGE, element = cat("9007199254740992")),
        Poison(6, DUPLICATE_KEY, element = cat("{\"a\":1,\"a\":2}")),
        Poison(7, MALFORMED_JSON, element = cat("[".repeat(17) + "]".repeat(17))),
        Poison(8, TRAILING_DATA, tail = cat(" x")),
    )

    private fun document(vararg ps: Poison): ByteArray {
        val elements = ps.mapNotNull { it.element }
        val body = ByteArrayBuilder()
        body.add("[")
        elements.forEachIndexed { k, e ->
            if (k > 0) body.add(",")
            body.add(e)
        }
        if (elements.isEmpty()) body.add("0")
        body.add("]")
        ps.forEach { body.add(it.tail) }
        return body.bytes()
    }

    private class ByteArrayBuilder {
        private val out = java.io.ByteArrayOutputStream()
        fun add(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        fun add(b: ByteArray) = out.write(b)
        fun bytes(): ByteArray = out.toByteArray()
    }

    @Test
    fun eachPoisonAloneGivesItsOwnRowsCode() {
        val c = Cases("single poisons")
        for (p in poisons) c.run { assertRejects(p.code, document(p), "row ${p.row} alone") }
        c.requireNonVacuous(8)
    }

    @Test
    fun aCleanControlDocumentIsAccepted() {
        assertAccepts("[0,1]")
    }

    @Test
    fun theEarlierRowWinsForEveryPairInEveryPlacement() {
        val c = Cases("pairs")
        for (x in poisons.indices) {
            for (y in x + 1 until poisons.size) {
                val earlier = poisons[x]
                val later = poisons[y]
                c.run { assertRejects(earlier.code, document(earlier, later), "rows ${earlier.row}+${later.row}") }
                c.run { assertRejects(earlier.code, document(later, earlier), "rows ${later.row}+${earlier.row} (swapped)") }
            }
        }
        c.requireNonVacuous(56)
    }

    @Test
    fun theEarliestRowWinsForEveryTripleAndForAllEightAtOnce() {
        val c = Cases("triples")
        for (x in poisons.indices) for (y in x + 1 until poisons.size) for (z in y + 1 until poisons.size) {
            c.run { assertRejects(poisons[x].code, document(poisons[z], poisons[y], poisons[x]), "rows ${poisons[x].row},${poisons[y].row},${poisons[z].row}") }
        }
        c.run { assertRejects(MALFORMED_JSON, document(*poisons.toTypedArray()), "all eight") }
        c.run { assertRejects(INVALID_UNICODE, document(*poisons.drop(1).toTypedArray()), "rows 2 to 8") }
        c.run { assertRejects(NON_INTEGER_NUMBER, document(*poisons.drop(3).toTypedArray()), "rows 4 to 8") }
        c.run { assertRejects(NUMBER_RANGE, document(*poisons.drop(4).toTypedArray()), "rows 5 to 8") }
        c.run { assertRejects(DUPLICATE_KEY, document(*poisons.drop(5).toTypedArray()), "rows 6 to 8") }
        c.run { assertRejects(MALFORMED_JSON, document(*poisons.drop(6).toTypedArray()), "rows 7 and 8: depth outranks trailing data") }
        c.requireNonVacuous(56)
    }

    @Test
    fun specificOrderingCasesFromTheSpecTable() {
        val c = Cases("named cases")
        c.run { assertRejects(NON_INTEGER_NUMBER, "{\"a\":1,\"a\":1.5}", "non-integer before duplicate") }
        c.run { assertRejects(NUMBER_RANGE, "{\"a\":1,\"a\":9007199254740992}", "range before duplicate") }
        c.run { assertRejects(DUPLICATE_KEY, "{\"a\":1,\"a\":1} 1", "duplicate before trailing data") }
        c.run { assertRejects(INVALID_UNICODE, "{\"a\":\"\\ud800\"} 1.5", "lone surrogate before the rest") }
        c.run { assertRejects(TRAILING_DATA, "{\"a\":1} 1.5", "a number in the trailing data is never parsed") }
        c.run { assertRejects(TRAILING_DATA, "{\"a\":1} {\"a\":1,\"a\":2}", "a duplicate in the trailing data is never parsed") }
        c.run { assertRejects(TRAILING_DATA, "{\"a\":1} \"\\ud800\"", "a lone surrogate escape in the trailing data is never parsed") }
        c.run { assertRejects(INVALID_UNICODE, cat("{} ", 0xFF), "invalid UTF-8 in the trailing data is row 2, before row 8") }
        c.run { assertRejects(MALFORMED_JSON, "[1.5,", "a truncated document is malformed, whatever it held") }
        c.run { assertRejects(MALFORMED_JSON, "[\"\\ud800\",", "row 1 before row 3") }
        c.run { assertRejects(MALFORMED_JSON, "[9007199254740992,", "row 1 before row 5") }
        c.run { assertRejects(MALFORMED_JSON, "{\"a\":1,\"a\":2,", "row 1 before row 6") }
        c.run { assertRejects(MALFORMED_JSON, "[[[[[[[[[[[[[[[[[1,", "row 1 (or 7) for a deep truncated document") }
        c.requireNonVacuous(13)
    }

    @Test
    fun theVerdictDoesNotDependOnPositionOrPadding() {
        val c = Cases("position independence")
        for (pad in listOf("", " ", "\r\n\t ", " ".repeat(10_000))) {
            c.run { assertRejects(DUPLICATE_KEY, "$pad{\"a\":1,$pad\"a\":2}$pad", "dup with padding") }
            c.run { assertRejects(NON_INTEGER_NUMBER, "$pad[$pad" + "1e2$pad]$pad", "non-integer with padding") }
        }
        for (n in listOf(0, 1, 10, 1000)) {
            val zeros = "0,".repeat(n)
            c.run { assertRejects(DUPLICATE_KEY, "[${zeros}{\"a\":1,\"a\":2}]", "a duplicate after $n elements") }
            c.run { assertRejects(NUMBER_RANGE, "[${zeros}9007199254740992]", "a range error after $n elements") }
        }
        c.requireNonVacuous(16)
    }
}
