package xyz.mdhv.asom.lab.json

import java.util.Base64
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class Base64StrictTest {
    private fun either(s: String) = Base64Strict.decodeEither(s)

    private fun ok(s: String, want: ByteArray, why: String = "") {
        val r = either(s)
        if (r !is B64Result.Ok) fail("$why: '$s' should decode but got $r")
        assertContentEquals(want, r.bytes, "$why: '$s'")
    }

    private fun bad(s: String, why: String = "") {
        val r = either(s)
        if (r !is B64Result.Reject) fail("$why: '$s' should be rejected but decoded to ${Hex.encode((r as B64Result.Ok).bytes)}")
        assertEquals("ENCODING", r.code)
    }

    @Test
    fun acceptsBothAlphabetsPaddedAndUnpadded() {
        val c = Cases("b64 accept")
        c.run { ok("", ByteArray(0), "empty") }
        c.run { ok("QQ==", raw(0x41)) }
        c.run { ok("QQ", raw(0x41)) }
        c.run { ok("QUI=", raw(0x41, 0x42)) }
        c.run { ok("QUI", raw(0x41, 0x42)) }
        c.run { ok("QUJD", raw(0x41, 0x42, 0x43)) }
        c.run { ok("+/+/", raw(0xFB, 0xFF, 0xBF), "standard alphabet") }
        c.run { ok("-_-_", raw(0xFB, 0xFF, 0xBF), "URL-safe alphabet") }
        c.run { ok("+/8=", raw(0xFB, 0xFF)) }
        c.run { ok("+/8", raw(0xFB, 0xFF)) }
        c.run { ok("-_8=", raw(0xFB, 0xFF)) }
        c.run { ok("-_8", raw(0xFB, 0xFF)) }
        c.run { ok("AAAA", raw(0, 0, 0)) }
        c.run { ok("AA==", raw(0)) }
        c.run { ok("/w==", raw(0xFF)) }
        c.run { ok("_w", raw(0xFF)) }
        c.run { ok("gA==", raw(0x80), "the last char of a 2-char tail may use its top two bits") }
        c.requireNonVacuous(17)
    }

    @Test
    fun rejectsEverythingTheSpecNames() {
        val c = Cases("b64 reject")
        val rejects = mapOf(
            "one char" to "Q", "1 mod 4" to "QUJDR", "1 mod 4 padded-looking" to "QUJDR===",
            "non-zero unused bits, 2-char tail" to "QR==", "the same unpadded" to "QR", "bits 0x0F set" to "QP==", "bits 0x01 only" to "QB==",
            "non-zero unused bits, 3-char tail" to "QUJ=", "the same unpadded 3" to "QUJ", "bits 0x02 set" to "QUK=",
            "pad too short" to "QQ=", "pad too long" to "QQ===", "pad on a full quantum" to "QUJD=", "pad after a full quantum" to "QUJD====",
            "pad in the middle" to "QQ==QQ==", "pad then data" to "QQ=Q", "leading pad" to "=QQ", "only pad" to "====", "single pad" to "=",
            "three pads" to "Q===", "two pads on a 3-tail" to "QUJ==",
            "leading space" to " QQ==", "trailing space" to "QQ== ", "inner space" to "Q Q==", "LF" to "QQ\n==", "trailing LF" to "QQ==\n", "CR LF wrap" to "QUJD\r\nQUJD", "tab" to "QUJD\t",
            "non-ASCII" to "QUJÉ", "full-width letter" to "QUJＡ", "NUL" to "QUJD\u0000", "dot" to "QUJ.", "star" to "QU*D", "bang" to "QUJD!", "comma" to "QU,D", "equals inside" to "QU=D",
            "mixed + and -" to "+-", "mixed + and _" to "+_", "mixed - and /" to "-/", "mixed late" to "QUJD+QUJD-QUJD", "mixed / and _" to "QUJ/_/==",
            "high surrogate" to "QUJ\uD800",
        )
        for ((why, s) in rejects) c.run { bad(s, why) }
        c.requireNonVacuous(40)
    }

    @Test
    fun urlNoPadForIds() {
        val c = Cases("b64url")
        fun urlOk(s: String, want: ByteArray) {
            val r = Base64Strict.decodeUrlNoPad(s)
            assertIs<B64Result.Ok>(r, s)
            assertContentEquals(want, r.bytes)
        }
        fun urlBad(s: String) = assertIs<B64Result.Reject>(Base64Strict.decodeUrlNoPad(s), "'$s' must be rejected")
        c.run { urlOk("", ByteArray(0)) }
        c.run { urlOk("QQ", raw(0x41)) }
        c.run { urlOk("QUI", raw(0x41, 0x42)) }
        c.run { urlOk("-_-_", raw(0xFB, 0xFF, 0xBF)) }
        c.run { urlOk("_w", raw(0xFF)) }
        c.run { urlBad("QQ==") }
        c.run { urlBad("QQ=") }
        c.run { urlBad("+/8") }
        c.run { urlBad("/w") }
        c.run { urlBad("QR") }
        c.run { urlBad("QUJ") }
        c.run { urlBad("Q") }
        c.run { urlBad("QU JD") }
        c.run { urlBad("=") }
        c.run { assertEquals("vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4".length, 43) }
        val id = "vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4"
        val decoded = Base64Strict.decodeUrlNoPad(id) as B64Result.Ok
        c.run { assertEquals(32, decoded.bytes.size, "a 43-character id is a 32-byte pin") }
        c.run { assertEquals(id, Base64Strict.encodeUrlNoPad(decoded.bytes)) }
        c.requireNonVacuous(17)
    }

    @Test
    fun encodersAgreeWithTheJdkAndEveryDecodedSpellingReturnsTheBytes() {
        val rnd = Random(53)
        val c = Cases("b64 roundtrip")
        for (len in 0..70) {
            val bytes = ByteArray(len).also { rnd.nextBytes(it) }
            val std = Base64.getEncoder().encodeToString(bytes)
            val stdNoPad = Base64.getEncoder().withoutPadding().encodeToString(bytes)
            val url = Base64.getUrlEncoder().encodeToString(bytes)
            val urlNoPad = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            c.run { assertEquals(std, Base64Strict.encodeStandard(bytes)) }
            c.run { assertEquals(urlNoPad, Base64Strict.encodeUrlNoPad(bytes)) }
            for (s in listOf(std, stdNoPad, url, urlNoPad)) c.run { ok(s, bytes, "len $len") }
            c.run { assertContentEquals(bytes, (Base64Strict.decodeUrlNoPad(urlNoPad) as B64Result.Ok).bytes) }
        }
        c.requireNonVacuous(400)
    }

    /**
     * The independent oracle: a regular expression for the shape, then the JDK decoder as a BUILDING BLOCK for the bits and a
     * re-encode to prove that the unused bits were zero. The strict decoder must accept exactly what the oracle accepts.
     */
    private fun oracle(s: String): ByteArray? {
        val m = Regex("^([A-Za-z0-9+/]*|[A-Za-z0-9_-]*)(={0,2})$").matchEntire(s) ?: return null
        val core = m.groupValues[1]
        val pad = m.groupValues[2]
        if (pad.isNotEmpty() && s.length % 4 != 0) return null
        if (core.length % 4 == 1) return null
        val urlSafe = core.any { it == '-' || it == '_' }
        val bytes = if (urlSafe) Base64.getUrlDecoder().decode(core) else Base64.getDecoder().decode(core)
        val back = if (urlSafe) Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) else Base64.getEncoder().withoutPadding().encodeToString(bytes)
        return if (back == core) bytes else null
    }

    @Test
    fun exhaustiveAgreementWithTheOracleOnEveryShortStringOverAnAlphabetOfTroublemakers() {
        val alphabet = listOf('A', 'Q', 'R', 'w', '8', '+', '/', '-', '_', '=', ' ')
        val c = Cases("b64 exhaustive")
        var accepted = 0
        var rejected = 0
        fun visit(prefix: String, left: Int) {
            val expected = oracle(prefix)
            val got = either(prefix)
            when {
                expected == null && got is B64Result.Reject -> rejected++
                expected != null && got is B64Result.Ok && expected.contentEquals(got.bytes) -> accepted++
                else -> fail("'$prefix': oracle=${expected?.let { Hex.encode(it) }} strict=$got")
            }
            c.run { }
            if (left > 0) for (ch in alphabet) visit(prefix + ch, left - 1)
        }
        visit("", 5)
        assertTrue(accepted > 1000 && rejected > 1000, "both verdicts must be well exercised: accepted=$accepted rejected=$rejected")
        c.requireNonVacuous(100_000)
    }

    /** The point of the hand-written decoder: strings a JDK decoder accepts that the spec forbids. */
    @Test
    fun leniencyOfTheJdkDecodersIsClosed() {
        val nonCanonical = listOf(
            "QR==", "QR", "QUJ=", "QP==", "QB==", "QUK=", "gB==", "/x==",
            "Q Q==", "QQ==\n", "QUJD\r\nQUJD", "QQ==QQ==", "QUJD!", "QU*D", "QUJD\t", " QQ==", "QUJD-QUJD", "+-", "QUJ.", "QQ==!!", "QUJD\u0000",
        )
        var jdkAccepts = 0
        val jdkDecoders = listOf(Base64.getDecoder(), Base64.getUrlDecoder(), Base64.getMimeDecoder())
        for (s in nonCanonical) {
            assertTrue(oracle(s) == null, "'$s' is non-canonical by the oracle")
            bad(s, "strict must reject '$s'")
            if (jdkDecoders.any { d -> runCatching { d.decode(s.toByteArray(Charsets.ISO_8859_1)) }.isSuccess }) jdkAccepts++
        }
        assertTrue(jdkAccepts >= 10, "at least 10 of the non-canonical strings must be accepted by some JDK decoder, or this test proves nothing: $jdkAccepts")
        assertTrue(runCatching { Base64.getDecoder().decode("QR==") }.isSuccess, "the JDK basic decoder ignores non-zero unused bits")
        assertTrue(runCatching { Base64.getMimeDecoder().decode("Q Q==") }.isSuccess, "the JDK MIME decoder skips characters outside the alphabet")
        bad("QR==")
        bad("Q Q==")
    }

    @Test
    fun hexHelperIsStrict() {
        assertEquals("00ff10", Hex.encode(raw(0, 255, 16)))
        assertContentEquals(raw(0, 255, 16), Hex.decode("00ff10"))
        assertFailsWith<IllegalArgumentException> { Hex.decode("0") }
        assertFailsWith<IllegalArgumentException> { Hex.decode("0G") }
        assertFailsWith<IllegalArgumentException> { Hex.decode("FF") }
    }
}
