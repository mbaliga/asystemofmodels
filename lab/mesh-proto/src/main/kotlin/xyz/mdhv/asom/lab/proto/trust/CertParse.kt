package xyz.mdhv.asom.lab.proto.trust

/** The OIDs of the two fixed templates (trust.md 2.4). */
object Oids {
    const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    const val ECDSA_SHA1 = "1.2.840.10045.4.1"
    const val ECDSA_SHA384 = "1.2.840.10045.4.3.3"
    const val COMMON_NAME = "2.5.4.3"
    const val SUBJECT_KEY_ID = "2.5.29.14"
    const val KEY_USAGE = "2.5.29.15"
    const val BASIC_CONSTRAINTS = "2.5.29.19"
    const val AUTHORITY_KEY_ID = "2.5.29.35"
    const val EXT_KEY_USAGE = "2.5.29.37"
    const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    const val CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"
}

/** A key-usage bit position (RFC 5280 4.2.1.3). */
object KeyUsageBits {
    const val DIGITAL_SIGNATURE = 0
    const val KEY_CERT_SIGN = 5
}

class Extension(val oid: String, val critical: Boolean, val value: ByteArray)

/**
 * A certificate read with the strict reader. The shape is the one the two templates produce: version 3, no unique ids, one extensions
 * block. [tbs] is the signed byte range, [spki] the SubjectPublicKeyInfo element exactly as it appears (so a pin is a hash of the
 * bytes the peer actually sent).
 */
class ParsedCert(
    val der: ByteArray,
    val tbs: ByteArray,
    val signatureAlgorithm: ByteArray,
    val tbsSignatureAlgorithm: ByteArray,
    val signatureBits: ByteArray,
    val issuerDer: ByteArray,
    val subjectDer: ByteArray,
    val notBefore: Long,
    val notAfter: Long,
    val spki: ByteArray,
    val extensions: List<Extension>,
    val duplicateExtension: String?,
) {
    fun extension(oid: String): Extension? = extensions.firstOrNull { it.oid == oid }

    companion object {
        /** Throws [DerException] on anything that is not a well-formed certificate of this shape. */
        fun parse(der: ByteArray): ParsedCert {
            val cert = DerReader.expect(DerReader.single(der), Der.SEQUENCE, "Certificate")
            val top = DerReader.children(cert)
            if (top.size != 3) throw DerException("Certificate has ${top.size} elements")
            val tbsEl = DerReader.expect(top[0], Der.SEQUENCE, "TBSCertificate")
            val outerAlg = DerReader.expect(top[1], Der.SEQUENCE, "signatureAlgorithm")
            val sigEl = DerReader.expect(top[2], Der.BIT_STRING, "signatureValue")
            val sig = sigEl.content
            if (sig.isEmpty() || sig[0] != 0.toByte()) throw DerException("signature BIT STRING has unused bits")

            val f = DerReader.children(tbsEl)
            var i = 0
            fun next(what: String): Tlv = f.getOrNull(i++) ?: throw DerException("TBSCertificate is missing $what")
            val ver = DerReader.expect(next("version"), 0xA0, "version")
            val verInner = DerReader.children(ver)
            if (verInner.size != 1 || DerReader.smallInteger(verInner[0]) != 2L) throw DerException("not an X.509 v3 certificate")
            val serial = DerReader.expect(next("serialNumber"), Der.INTEGER, "serialNumber")
            if (serial.contentLength == 0 || serial.contentLength > 21 || serial.bytes[serial.contentStart].toInt() and 0x80 != 0) throw DerException("serialNumber")
            if (serial.contentLength > 1 && serial.bytes[serial.contentStart] == 0.toByte() && serial.bytes[serial.contentStart + 1].toInt() and 0x80 == 0) throw DerException("serialNumber is not minimal")
            val innerAlg = DerReader.expect(next("signature"), Der.SEQUENCE, "TBS signature algorithm")
            val issuer = DerReader.expect(next("issuer"), Der.SEQUENCE, "issuer")
            val validity = DerReader.children(DerReader.expect(next("validity"), Der.SEQUENCE, "validity"))
            if (validity.size != 2) throw DerException("validity has ${validity.size} elements")
            val notBefore = DerReader.time(validity[0])
            val notAfter = DerReader.time(validity[1])
            val subject = DerReader.expect(next("subject"), Der.SEQUENCE, "subject")
            val spki = DerReader.expect(next("subjectPublicKeyInfo"), Der.SEQUENCE, "subjectPublicKeyInfo")
            val extWrap = DerReader.expect(next("extensions"), 0xA3, "extensions")
            if (i != f.size) throw DerException("unexpected element after the extensions")
            val extSeqs = DerReader.children(extWrap)
            if (extSeqs.size != 1) throw DerException("extensions wrapper")
            val exts = ArrayList<Extension>()
            var dup: String? = null
            for (e in DerReader.children(DerReader.expect(extSeqs[0], Der.SEQUENCE, "Extensions"))) {
                val parts = DerReader.children(DerReader.expect(e, Der.SEQUENCE, "Extension"))
                if (parts.size !in 2..3) throw DerException("Extension has ${parts.size} elements")
                val oid = DerReader.oidString(parts[0])
                val critical = if (parts.size == 3) {
                    if (!DerReader.boolean(parts[1])) throw DerException("explicit DEFAULT FALSE criticality")
                    true
                } else false
                val value = DerReader.expect(parts.last(), Der.OCTET_STRING, "extnValue").content
                if (exts.any { it.oid == oid }) dup = oid
                exts += Extension(oid, critical, value)
            }
            if (exts.isEmpty()) throw DerException("empty Extensions")
            return ParsedCert(
                der = der, tbs = tbsEl.whole, signatureAlgorithm = outerAlg.whole, tbsSignatureAlgorithm = innerAlg.whole,
                signatureBits = sig.copyOfRange(1, sig.size), issuerDer = issuer.whole, subjectDer = subject.whole,
                notBefore = notBefore, notAfter = notAfter, spki = spki.whole, extensions = exts, duplicateExtension = dup,
            )
        }
    }
}

/** BasicConstraints (RFC 5280 4.2.1.9) in the strict DER form. */
class BasicConstraints(val ca: Boolean, val pathLen: Long?) {
    companion object {
        fun parse(value: ByteArray): BasicConstraints {
            val seq = DerReader.expect(DerReader.single(value), Der.SEQUENCE, "BasicConstraints")
            val c = DerReader.children(seq)
            var i = 0
            var ca = false
            if (c.getOrNull(i)?.tag == Der.BOOLEAN) {
                if (!DerReader.boolean(c[i])) throw DerException("explicit DEFAULT FALSE cA")
                ca = true
                i++
            }
            var path: Long? = null
            if (c.getOrNull(i)?.tag == Der.INTEGER) path = DerReader.smallInteger(c[i++])
            if (i != c.size) throw DerException("BasicConstraints has extra elements")
            return BasicConstraints(ca, path)
        }
    }
}

/** KeyUsage (RFC 5280 4.2.1.3) as the set of set bit positions; the named-bit-list DER form (no trailing zero bits) only. */
object KeyUsageParser {
    fun parse(value: ByteArray): Set<Int> {
        val bs = DerReader.expect(DerReader.single(value), Der.BIT_STRING, "KeyUsage")
        val c = bs.content
        if (c.size < 2) throw DerException("KeyUsage is empty")
        val unused = c[0].toInt() and 0xFF
        if (unused > 7) throw DerException("KeyUsage unused bits")
        val data = c.copyOfRange(1, c.size)
        if (data.size > 2) throw DerException("KeyUsage is longer than 9 bits")
        val last = data.last().toInt() and 0xFF
        if (last == 0) throw DerException("KeyUsage has a trailing zero byte")
        if (last and ((1 shl unused) - 1) != 0) throw DerException("KeyUsage unused bits are not zero")
        if (unused != Integer.numberOfTrailingZeros(last)) throw DerException("KeyUsage is not a minimal named bit list")
        val bits = HashSet<Int>()
        for (k in 0 until data.size * 8 - unused) if (data[k / 8].toInt() and (0x80 shr (k % 8)) != 0) bits += k
        return bits
    }
}

object ExtKeyUsageParser {
    fun parse(value: ByteArray): List<String> {
        val seq = DerReader.expect(DerReader.single(value), Der.SEQUENCE, "ExtKeyUsage")
        val c = DerReader.children(seq)
        if (c.isEmpty()) throw DerException("empty ExtKeyUsage")
        return c.map { DerReader.oidString(it) }
    }
}

object KeyIdParsers {
    fun subjectKeyId(value: ByteArray): ByteArray {
        val o = DerReader.expect(DerReader.single(value), Der.OCTET_STRING, "SubjectKeyIdentifier")
        if (o.contentLength == 0) throw DerException("empty SubjectKeyIdentifier")
        return o.content
    }

    /** `AuthorityKeyIdentifier ::= SEQUENCE { keyIdentifier [0] IMPLICIT OCTET STRING }`, with nothing else in it. */
    fun authorityKeyId(value: ByteArray): ByteArray {
        val seq = DerReader.expect(DerReader.single(value), Der.SEQUENCE, "AuthorityKeyIdentifier")
        val c = DerReader.children(seq)
        if (c.size != 1) throw DerException("AuthorityKeyIdentifier must hold only keyIdentifier")
        DerReader.expect(c[0], 0x80, "keyIdentifier")
        if (c[0].contentLength == 0) throw DerException("empty keyIdentifier")
        return c[0].content
    }
}
