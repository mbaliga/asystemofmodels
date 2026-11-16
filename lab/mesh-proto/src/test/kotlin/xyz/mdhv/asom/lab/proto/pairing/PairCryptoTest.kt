package xyz.mdhv.asom.lab.proto.pairing

import java.math.BigInteger
import java.security.MessageDigest
import java.util.SplittableRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.trust.Pin

/** Proof, SAS and transcript of trust.md 4.5 against the worked vector typed from the spec and against a second formulation. Evidence label: LAB, oracle: self. */
class PairCryptoTest {
    companion object {
        val laws = LawCounters("pair-crypto")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("worked-vector", "random-vs-reference", "pin-swap", "nonce-swap", "sas-truncation", "proof-binds-pin-s", "proof-compare-whole"))

        private fun sha(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            parts.forEach { md.update(it) }
            return md.digest()
        }

        fun pin(label: String): Pin = Pin.ofHash(sha(label.toByteArray()))
    }

    private val pinD = pin("asom-vector/spki/D")
    private val pinS = pin("asom-vector/spki/S")
    private val secret = ByteArray(32) { it.toByte() }
    private val nonceS = ByteArray(32) { 0xA5.toByte() }
    private val nonceD = ByteArray(32) { 0x5A.toByte() }

    @Test
    fun theWorkedVectorOfTrustMd45() {
        assertEquals("jZcpQhuRMg6yNp81ynXIGjfGCcZeStuotMWTOtRFPgs", pinD.nodeId)
        assertEquals("AyXFqjFWNe-eItKRiUlURLOfmG2TQ2Q00gp5wPPnSH0", pinS.nodeId)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", Base64Strict.encodeUrlNoPad(secret))
        assertEquals("becQGXmCCTwCLN4BK4hj0qa3AW-AT9DgDikj7RA_baM", Base64Strict.encodeUrlNoPad(PairCrypto.proof(secret, pinD, pinS, nonceS)))
        assertEquals("865 412", PairCrypto.sas(pinD, pinS, nonceS, nonceD))
        assertEquals("a7cfd74766ca9d6eab6393ad20c52a1e103e394678101b503f1e48c6f473ffb1", Hex.encode(PairCrypto.transcript(pinD, pinS, nonceS, nonceD)))
        assertEquals("r3iMlWpfvBBdchUUFGHUoCSNIi283ymNyEqeqNjoAC8", Base64Strict.encodeUrlNoPad(PairCrypto.proof(secret, pinS, pinD, nonceS)))
        laws.bump("worked-vector")
    }

    @Test
    fun pinSwapAndNonceSwapAreRejected() {
        val good = PairCrypto.proof(secret, pinD, pinS, nonceS)
        assertTrue(PairCrypto.proofValid(secret, pinD, pinS, nonceS, good))
        assertFalse(PairCrypto.proofValid(secret, pinD, pinS, nonceS, PairCrypto.proof(secret, pinS, pinD, nonceS)), "pin-swap")
        laws.bump("pin-swap")
        assertFalse(PairCrypto.proofValid(secret, pinD, pinS, nonceS, PairCrypto.proof(secret, pinD, pinS, nonceD)), "nonce-swap")
        laws.bump("nonce-swap")
        assertFalse(PairCrypto.proofValid(secret, pinD, pin("asom-vector/spki/S2"), nonceS, good), "the proof binds pin_S, so a relay cannot reuse it")
        laws.bump("proof-binds-pin-s")
    }

    @Test
    fun proofComparisonIsOverTheWholeValue() {
        val good = PairCrypto.proof(secret, pinD, pinS, nonceS)
        for (len in 0 until 32) assertFalse(PairCrypto.proofValid(secret, pinD, pinS, nonceS, good.copyOf(len)), "a $len-byte prefix of the proof")
        assertFalse(PairCrypto.proofValid(secret, pinD, pinS, nonceS, good + byteArrayOf(0)))
        for (i in good.indices) {
            val m = good.copyOf()
            m[i] = (m[i].toInt() xor 1).toByte()
            assertFalse(PairCrypto.proofValid(secret, pinD, pinS, nonceS, m))
        }
        assertTrue(PairCrypto.transcriptMatches(good, good))
        assertFalse(PairCrypto.transcriptMatches(good, good.copyOf(31)))
        laws.bump("proof-compare-whole", 3)
    }

    /** A second formulation of 4.5 (BigInteger arithmetic, one concatenation) over seeded random inputs. */
    @Test
    fun randomCasesAgreeWithAReferenceFormulation() {
        val rnd = SplittableRandom(4)
        val iterations = 200
        fun bytes() = ByteArray(32) { rnd.nextInt(256).toByte() }
        repeat(iterations) {
            val pd = Pin.ofHash(bytes())
            val ps = Pin.ofHash(bytes())
            val sec = bytes()
            val ns = bytes()
            val nd = bytes()
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sec, "HmacSHA256")) }
            val refProof = mac.doFinal("asom-pair-v1/proof".toByteArray() + byteArrayOf(0) + pd.bytes() + ps.bytes() + ns)
            assertContentEquals(refProof, PairCrypto.proof(sec, pd, ps, ns))
            val h = sha("asom-pair-v1/sas".toByteArray() + byteArrayOf(0) + pd.bytes() + ps.bytes() + ns + nd)
            val refSas = BigInteger(1, h.copyOfRange(0, 4)).mod(BigInteger.valueOf(1_000_000)).toInt()
            assertEquals(refSas, PairCrypto.sasNumber(pd, ps, ns, nd))
            val digits = String.format(java.util.Locale.ROOT, "%06d", refSas)
            assertEquals(digits.substring(0, 3) + " " + digits.substring(3), PairCrypto.sas(pd, ps, ns, nd))
            assertContentEquals(sha("asom-pair-v1/transcript".toByteArray() + byteArrayOf(0) + pd.bytes() + ps.bytes() + ns + nd), PairCrypto.transcript(pd, ps, ns, nd))
            laws.bump("random-vs-reference")
        }
        println("iterations: $iterations")
    }

    /** The SAS is the first four bytes, big-endian, modulo 10^6: an off-by-one in the slice or the width changes it. */
    @Test
    fun theSasUsesExactlyTheFirstFourBytes() {
        val h = sha("asom-pair-v1/sas".toByteArray() + byteArrayOf(0) + pinD.bytes() + pinS.bytes() + nonceS + nonceD)
        val first4 = (BigInteger(1, h.copyOfRange(0, 4)).mod(BigInteger.valueOf(1_000_000))).toInt()
        val shifted = (BigInteger(1, h.copyOfRange(1, 5)).mod(BigInteger.valueOf(1_000_000))).toInt()
        val first5 = (BigInteger(1, h.copyOfRange(0, 5)).mod(BigInteger.valueOf(1_000_000))).toInt()
        val first3 = (BigInteger(1, h.copyOfRange(0, 3)).mod(BigInteger.valueOf(1_000_000))).toInt()
        val got = PairCrypto.sasNumber(pinD, pinS, nonceS, nonceD)
        assertEquals(865412, got)
        assertEquals(first4, got)
        assertTrue(got != shifted && got != first5 && got != first3, "the worked vector distinguishes every off-by-one slice")
        laws.bump("sas-truncation")
    }

    @Test
    fun badLengthsAreProgrammingErrors() {
        assertTrue(runCatching { PairCrypto.proof(ByteArray(31), pinD, pinS, nonceS) }.isFailure)
        assertTrue(runCatching { PairCrypto.sas(pinD, pinS, nonceS, ByteArray(31)) }.isFailure)
        assertTrue(runCatching { Pin.ofHash(ByteArray(31)) }.isFailure)
    }
}
