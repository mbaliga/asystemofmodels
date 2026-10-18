package xyz.mdhv.asom.lab.json

import kotlin.test.Test

/**
 * ERR-FX4-JS-2 (A-F5): R4-L-10.3 reads a number lexeme as the maximal run of `[0-9+-.eE]` starting at `-` or a digit, and lists
 * `-Infinity` as a word. The implemented reading, pinned here and in `xcheck_m01.py`, is: a `-` immediately followed by an ASCII
 * letter starts a letter run; `-NaN` and `-Infinity` are NON_INTEGER_NUMBER and every other `-<letters>` is MALFORMED_JSON.
 */
class NumberLexemeTest {
    @Test
    fun minusFollowedByALetterIsAWordNotANumber() {
        val c = Cases("minus then letter")
        for (t in listOf("-e", "-E5", "[-e1]", "-e1", "-x", "-true", "-nan", "-infinity", "-Infinityx", "-NaNx", "[-e]", "{\"a\":-E}", "-null", "-e-5")) {
            c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, t, "'-' then letters in $t") }
        }
        for (t in listOf("-NaN", "[-NaN]", "-Infinity", "{\"a\":-Infinity}", "[-NaN,1]")) {
            c.run { assertRejects(JsonRejectCode.NON_INTEGER_NUMBER, t, "the two words after '-' in $t") }
        }
        for (t in listOf("-", "[-]", "-.5", "-+1", "-1e", "-1.0", "-0", "-1e5", "-1-2", "[-1e]")) {
            c.run { assertRejects(JsonRejectCode.NON_INTEGER_NUMBER, t, "a '-' that starts a numeric run in $t") }
        }
        c.run { assertRejects(JsonRejectCode.MALFORMED_JSON, "[-NaN,-e]", "a syntax error outranks the non-integer -NaN") }
        c.requireNonVacuous(25)
    }
}
