package xyz.mdhv.asom.lab.proto.trust

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import xyz.mdhv.asom.lab.json.Hex

/** Known answers for the DER writer (computed by hand from X.690) and the strictness of the reader. Evidence label: LAB, oracle: self. */
class DerTest {
    private fun hex(b: ByteArray) = Hex.encode(b)

    @Test
    fun lengthsAreMinimal() {
        assertEquals("7f", hex(Der.length(127)))
        assertEquals("8180", hex(Der.length(128)))
        assertEquals("81ff", hex(Der.length(255)))
        assertEquals("820100", hex(Der.length(256)))
        assertEquals("82ffff", hex(Der.length(65535)))
    }

    @Test
    fun objectIdentifiers() {
        assertEquals("06082a8648ce3d040302", hex(Der.oid(Oids.ECDSA_SHA256)))
        assertEquals("0603550403", hex(Der.oid(Oids.COMMON_NAME)))
        assertEquals("06082b06010505070301", hex(Der.oid(Oids.SERVER_AUTH)))
        assertEquals("06072a8648ce3d0201", hex(Der.oid("1.2.840.10045.2.1")))
        assertEquals("0603551d13", hex(Der.oid(Oids.BASIC_CONSTRAINTS)))
        for (o in listOf(Oids.ECDSA_SHA256, Oids.ECDSA_SHA1, Oids.COMMON_NAME, Oids.CLIENT_AUTH, "2.5.29.37.0", "1.2.3.4.5", "2.999.1")) {
            assertEquals(o, DerReader.oidString(DerReader.single(Der.oid(o))), "round trip of $o")
        }
    }

    @Test
    fun integersAndBooleans() {
        assertEquals("020100", hex(Der.integer(0)))
        assertEquals("020102", hex(Der.integer(2)))
        assertEquals("02017f", hex(Der.integerUnsigned(byteArrayOf(0x7f))))
        assertEquals("02020080", hex(Der.integerUnsigned(byteArrayOf(0x80.toByte()))))
        assertEquals("020100", hex(Der.integerUnsigned(byteArrayOf(0, 0, 0))))
        assertEquals("02017f", hex(Der.integerUnsigned(byteArrayOf(0, 0, 0x7f))))
        assertEquals("0101ff", hex(Der.boolean(true)))
        assertEquals("010100", hex(Der.boolean(false)))
    }

    @Test
    fun times() {
        assertEquals("170d3236303932313134313332305a", hex(Der.time(1790000000)))
        assertEquals("180f39393939313233313233353935395a", hex(Der.generalizedTime(CertTemplates.NODE_NOT_AFTER_EPOCH_SEC)))
        assertEquals("180f32303530303130313030303030305a", hex(Der.time(2524608000)))
        assertEquals("170d3439313233313233353935395a", hex(Der.time(2524607999)))
        for (t in listOf(1790000000L, 2524607999L, 2524608000L, CertTemplates.NODE_NOT_AFTER_EPOCH_SEC, 1_000_000_000L)) {
            assertEquals(t, DerReader.time(DerReader.single(Der.time(t))), "round trip of $t")
        }
        assertEquals(Instant.ofEpochSecond(1790000000).toString(), "2026-09-21T14:13:20Z")
    }

    @Test
    fun readerRefusesNonStrictEncodings() {
        fun refused(vararg b: Int) = assertFailsWith<DerException> { DerReader.single(ByteArray(b.size) { b[it].toByte() }) }
        refused(0x30, 0x81, 0x05, 1, 2, 3, 4, 5) // 81 05: long form for a value below 128
        refused(0x30, 0x82, 0x00, 0x80) // non-minimal two-octet length
        refused(0x30, 0x83, 0x00, 0xff, 0xff) // non-minimal three-octet length
        refused(0x30, 0x80, 0x00, 0x00) // indefinite length
        refused(0x1f, 0x01, 0x00) // multi-byte tag
        refused(0x30, 0x05, 1, 2) // overruns
        refused(0x30, 0x00, 0x00) // trailing byte
        refused(0x30) // missing length
        refused() // empty
        assertContentEquals(byteArrayOf(0x30, 0x00), DerReader.single(byteArrayOf(0x30, 0x00)).whole)
    }

    @Test
    fun readerRefusesBadScalars() {
        assertFailsWith<DerException> { DerReader.boolean(DerReader.single(byteArrayOf(1, 1, 0x01))) }
        assertFailsWith<DerException> { DerReader.smallInteger(DerReader.single(byteArrayOf(2, 2, 0, 1))) }
        assertFailsWith<DerException> { DerReader.smallInteger(DerReader.single(byteArrayOf(2, 1, 0x80.toByte()))) }
        assertFailsWith<DerException> { DerReader.oidString(DerReader.single(byteArrayOf(6, 2, 0x2a, 0x86.toByte()))) }
        assertFailsWith<DerException> { DerReader.oidString(DerReader.single(byteArrayOf(6, 3, 0x2a, 0x80.toByte(), 0x01))) }
        assertFailsWith<DerException> { DerReader.time(DerReader.single(Der.tlv(Der.UTC_TIME, "260921141320+0100".toByteArray()))) }
        assertFailsWith<DerException> { DerReader.time(DerReader.single(Der.tlv(Der.GENERALIZED_TIME, "20260921141320Z".toByteArray()))) }
        assertFailsWith<DerException> { DerReader.time(DerReader.single(Der.tlv(Der.UTC_TIME, "261321141320Z".toByteArray()))) }
        assertFailsWith<DerException> { DerReader.time(DerReader.single(Der.tlv(Der.UTC_TIME, "260231141320Z".toByteArray()))) }
    }
}
