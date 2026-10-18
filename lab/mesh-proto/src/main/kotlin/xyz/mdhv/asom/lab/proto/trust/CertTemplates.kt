package xyz.mdhv.asom.lab.proto.trust

import java.security.MessageDigest
import java.security.PrivateKey
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.manifest.SigCodec
import xyz.mdhv.asom.lab.manifest.Spki

/**
 * The two FIXED certificate templates of trust.md 2.4, as DER (no BouncyCastle). Both are ECDSA P-256 with SHA-256 only.
 *
 * - Node certificate: self-signed, `CN=asom-node <nodeTag>`, valid from creation minus one hour to `99991231235959Z`, basicConstraints
 *   `CA:TRUE, pathLen:0` (critical), keyUsage `keyCertSign` (critical), subjectKeyIdentifier.
 * - Session leaf: issued by the node key, `CN=asom-session <nodeTag>`, from now minus one hour to now plus 14 days, basicConstraints
 *   `CA:FALSE` (critical), keyUsage `digitalSignature` (critical), extKeyUsage `serverAuth, clientAuth`, authorityKeyIdentifier = the node SKI.
 *
 * The `*Tbs` functions are deterministic in their inputs. A signature is not (the JDK randomises ECDSA), so a golden vector pins the TBS
 * bytes and one signature, and a test checks the assembled certificate against it.
 */
object CertTemplates {
    const val NODE_NOT_AFTER_EPOCH_SEC = 253402300799L // 9999-12-31T23:59:59Z
    const val LEAF_LIFETIME_SEC = 14L * 24 * 3600
    const val BACKDATE_SEC = 3600L

    val ECDSA_SHA256_ALG_ID: ByteArray = Der.sequence(Der.oid(Oids.ECDSA_SHA256))

    /** RFC 7093 method 1 with SHA-256: the leftmost 160 bits of the hash of the 65-byte public point. No SHA-1 anywhere. */
    fun subjectKeyId(spki: ByteArray): ByteArray {
        require(Spki.strict(spki) != null) { "not a strict P-256 SPKI" }
        return MessageDigest.getInstance("SHA-256").digest(spki.copyOfRange(26, 91)).copyOf(20)
    }

    private fun name(prefix: String, spki: ByteArray): ByteArray =
        Der.sequence(Der.set(Der.sequence(Der.oid(Oids.COMMON_NAME), Der.utf8String("$prefix ${Spki.nodeTag(spki)}"))))

    fun nodeName(nikSpki: ByteArray): ByteArray = name("asom-node", nikSpki)

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(value)) else Der.sequence(Der.oid(oid), Der.octetString(value))

    private fun serialInteger(serial: ByteArray): ByteArray {
        require(serial.size == 16 && serial[0].toInt() and 0x80 == 0 && serial.any { it != 0.toByte() }) { "a serial is 16 bytes, high bit cleared, not zero" }
        return Der.integerUnsigned(serial)
    }

    private fun tbs(serial: ByteArray, issuer: ByteArray, notBefore: Long, notAfter: ByteArray, subject: ByteArray, spki: ByteArray, extensions: List<ByteArray>): ByteArray =
        Der.sequence(
            Der.explicit(0, Der.integer(2)),
            serialInteger(serial),
            ECDSA_SHA256_ALG_ID,
            issuer,
            Der.sequence(Der.time(notBefore), notAfter),
            subject,
            spki,
            Der.explicit(3, Der.sequence(*extensions.toTypedArray())),
        )

    fun nodeTbs(nikSpki: ByteArray, serial: ByteArray, createdEpochSec: Long): ByteArray {
        require(Spki.strict(nikSpki) != null) { "not a strict P-256 SPKI" }
        val n = nodeName(nikSpki)
        val keyCertSign = Der.bitString(byteArrayOf(0x04), 2)
        return tbs(
            serial, n, createdEpochSec - BACKDATE_SEC, Der.generalizedTime(NODE_NOT_AFTER_EPOCH_SEC), n, nikSpki,
            listOf(
                extension(Oids.BASIC_CONSTRAINTS, true, Der.sequence(Der.boolean(true), Der.integer(0))),
                extension(Oids.KEY_USAGE, true, keyCertSign),
                extension(Oids.SUBJECT_KEY_ID, false, Der.octetString(subjectKeyId(nikSpki))),
            ),
        )
    }

    fun leafTbs(nikSpki: ByteArray, leafSpki: ByteArray, serial: ByteArray, nowEpochSec: Long): ByteArray {
        require(Spki.strict(nikSpki) != null && Spki.strict(leafSpki) != null) { "not a strict P-256 SPKI" }
        val digitalSignature = Der.bitString(byteArrayOf(0x80.toByte()), 7)
        return tbs(
            serial, nodeName(nikSpki), nowEpochSec - BACKDATE_SEC, Der.time(nowEpochSec + LEAF_LIFETIME_SEC), name("asom-session", nikSpki), leafSpki,
            listOf(
                extension(Oids.BASIC_CONSTRAINTS, true, Der.sequence()),
                extension(Oids.KEY_USAGE, true, digitalSignature),
                extension(Oids.EXT_KEY_USAGE, false, Der.sequence(Der.oid(Oids.SERVER_AUTH), Der.oid(Oids.CLIENT_AUTH))),
                extension(Oids.AUTHORITY_KEY_ID, false, Der.sequence(Der.implicitPrimitive(0, subjectKeyId(nikSpki)))),
            ),
        )
    }

    /** `Certificate ::= SEQUENCE { tbs, ecdsa-with-SHA256, BIT STRING (DER ECDSA-Sig-Value) }` from a raw 64-byte `r||s`. */
    fun assemble(tbs: ByteArray, signatureRaw: ByteArray): ByteArray {
        val der = (SigCodec.rawToDer(signatureRaw) as? SigCodec.Result.Ok)?.bytes ?: error("signature is not 64 bytes")
        return Der.sequence(tbs, ECDSA_SHA256_ALG_ID, Der.bitString(der))
    }

    private fun sign(key: PrivateKey, tbs: ByteArray): ByteArray = Es256.sign(key, tbs)

    fun nodeCertificate(nikPrivate: PrivateKey, nikSpki: ByteArray, serial: ByteArray, createdEpochSec: Long): ByteArray {
        val t = nodeTbs(nikSpki, serial, createdEpochSec)
        return assemble(t, sign(nikPrivate, t))
    }

    fun leafCertificate(nikPrivate: PrivateKey, nikSpki: ByteArray, leafSpki: ByteArray, serial: ByteArray, nowEpochSec: Long): ByteArray {
        val t = leafTbs(nikSpki, leafSpki, serial, nowEpochSec)
        return assemble(t, sign(nikPrivate, t))
    }
}
