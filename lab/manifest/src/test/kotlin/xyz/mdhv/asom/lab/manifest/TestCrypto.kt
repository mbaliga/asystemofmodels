package xyz.mdhv.asom.lab.manifest

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/** Test-only P-256 arithmetic and RFC 6979 deterministic ECDSA, so that the generated vectors are byte-reproducible. The production signer uses the JDK. */
object P256Math {
    private val p = P256.P
    private val a = p.subtract(BigInteger.valueOf(3))
    val G: Pair<BigInteger, BigInteger> = Pair(
        BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
        BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16),
    )

    private fun inv(x: BigInteger): BigInteger = x.modInverse(p)

    private fun add(p1: Pair<BigInteger, BigInteger>?, p2: Pair<BigInteger, BigInteger>?): Pair<BigInteger, BigInteger>? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        val (x1, y1) = p1
        val (x2, y2) = p2
        val lam: BigInteger
        if (x1 == x2) {
            if (y1.add(y2).mod(p).signum() == 0) return null
            lam = x1.multiply(x1).multiply(BigInteger.valueOf(3)).add(a).multiply(inv(y1.shiftLeft(1))).mod(p)
        } else {
            lam = y2.subtract(y1).multiply(inv(x2.subtract(x1).mod(p))).mod(p)
        }
        val x3 = lam.multiply(lam).subtract(x1).subtract(x2).mod(p)
        val y3 = lam.multiply(x1.subtract(x3)).subtract(y1).mod(p)
        return Pair(x3, y3)
    }

    fun mul(k: BigInteger, pt: Pair<BigInteger, BigInteger> = G): Pair<BigInteger, BigInteger>? {
        var result: Pair<BigInteger, BigInteger>? = null
        var addend: Pair<BigInteger, BigInteger>? = pt
        var kk = k
        while (kk.signum() > 0) {
            if (kk.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            kk = kk.shiftRight(1)
        }
        return result
    }

    fun spkiOf(d: BigInteger): ByteArray {
        val (x, y) = mul(d)!!
        return Spki.PREFIX + byteArrayOf(4) + Es256.fixed32(x) + Es256.fixed32(y)
    }
}

object Rfc6979 {
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun bits2octets(h: ByteArray): ByteArray = Es256.fixed32(BigInteger(1, h).mod(P256.N))

    fun k(d: BigInteger, msg: ByteArray): BigInteger {
        val h1 = MessageDigest.getInstance("SHA-256").digest(msg)
        val x = Es256.fixed32(d)
        val hh = bits2octets(h1)
        var v = ByteArray(32) { 1 }
        var kk = ByteArray(32)
        kk = hmac(kk, v + byteArrayOf(0) + x + hh)
        v = hmac(kk, v)
        kk = hmac(kk, v + byteArrayOf(1) + x + hh)
        v = hmac(kk, v)
        while (true) {
            v = hmac(kk, v)
            val cand = BigInteger(1, v)
            if (cand.signum() > 0 && cand < P256.N) return cand
            kk = hmac(kk, v + byteArrayOf(0))
            v = hmac(kk, v)
        }
    }

    /** Raw r||s (64 bytes) of ECDSA-SHA256 with the RFC 6979 nonce; [lowS] normalises s to at most n/2. */
    fun sign(dHex: String, msg: ByteArray, lowS: Boolean = true): ByteArray {
        val d = BigInteger(dHex, 16)
        val n = P256.N
        val e = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(msg))
        val k = k(d, msg)
        val r = P256Math.mul(k)!!.first.mod(n)
        var s = k.modInverse(n).multiply(e.add(r.multiply(d))).mod(n)
        if (lowS && s > P256.HALF_N) s = n.subtract(s)
        return Es256.fixed32(r) + Es256.fixed32(s)
    }
}
