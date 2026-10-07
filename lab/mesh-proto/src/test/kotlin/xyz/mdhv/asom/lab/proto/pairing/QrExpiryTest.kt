package xyz.mdhv.asom.lab.proto.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.proto.trust.LawCounters

/** Design T8 (ERRATA ERR-FX2-4): S does not judge the QR expiry on its own clock. The example URI of trust.md 4.2 has `x=1790000120`. */
class QrExpiryTest {
    companion object {
        val laws = LawCounters("qr-expiry")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("enforce-unchanged", "none-accepts-skewed-clock", "none-keeps-syntax-checks", "none-keeps-every-other-check"))

        const val URI = "asom-pair:1?k=jZcpQhuRMg6yNp81ynXIGjfGCcZeStuotMWTOtRFPgs&a=192.168.1.40:11436,100.101.7.9:11436&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&x=1790000120&n=Dell%20tower"
    }

    private fun code(r: QrParse): String = if (r is QrParse.Ok) "OK" else (r as QrParse.Reject).code.name

    @Test
    fun theDefaultStillRefusesOnSClockSoTheFrozenVectorsHold() {
        assertEquals("EXPIRED", code(QrUri.parse(URI, 1_790_000_180)))
        assertEquals("EXPIRY_TOO_FAR", code(QrUri.parse(URI, 1_789_999_939)))
        assertEquals("OK", code(QrUri.parse(URI, 1_790_000_000)))
        laws.bump("enforce-unchanged", 3)
    }

    @Test
    fun withoutTheGateAPhoneThreeMinutesBehindOrAheadCanStillScan() {
        for (now in listOf(1_790_000_180L, 1_790_000_500L, 1_789_999_939L, 1_789_990_000L, 1_790_900_000L)) {
            val r = QrUri.parse(URI, now, expiry = QrExpiry.NONE)
            assertEquals("OK", code(r), "now=$now")
            assertEquals(1_790_000_120L, (r as QrParse.Ok).payload.expirySec)
            laws.bump("none-accepts-skewed-clock")
        }
    }

    @Test
    fun withoutTheGateXIsStillCheckedForSyntax() {
        for (x in listOf("", "0120", "12a", "1790000120000000", "-5", "1.5")) {
            val r = QrUri.parse(URI.replace("x=1790000120", "x=$x"), 1_790_000_000, expiry = QrExpiry.NONE)
            assertEquals("BAD_EXPIRY", code(r), "x='$x'")
            laws.bump("none-keeps-syntax-checks")
        }
        assertTrue(QrUri.parse(URI.replace("x=1790000120&", ""), 1_790_000_000, expiry = QrExpiry.NONE) is QrParse.Reject, "x is still required")
    }

    @Test
    fun withoutTheGateEveryOtherCheckStillRuns() {
        assertEquals("ADDRESS_NOT_ELIGIBLE", code(QrUri.parse(URI.replace("192.168.1.40", "8.8.8.8"), 1_790_000_000, expiry = QrExpiry.NONE)))
        assertEquals("BAD_PIN", code(QrUri.parse(URI.replace("k=jZcp", "k=jZc"), 1_790_000_000, expiry = QrExpiry.NONE)))
        assertEquals("UNKNOWN_FIELD", code(QrUri.parse("$URI&z=1", 1_790_000_000, expiry = QrExpiry.NONE)))
        laws.bump("none-keeps-every-other-check", 3)
    }
}
