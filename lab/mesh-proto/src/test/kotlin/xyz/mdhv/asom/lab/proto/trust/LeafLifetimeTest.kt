package xyz.mdhv.asom.lab.proto.trust

import java.time.Instant
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.policy.PeerStatus

/**
 * Builder action 4 of REVISION_4.md section 6 (trust.md R4-T-06): a leaf whose `notAfter - notBefore` exceeds 14 days + 1 hour is refused as `LEAF_LIFETIME`
 * (never `CLOCK_SKEW`), in every mode and before the clock and the registry are looked at. The bound is a literal here on purpose. A leaf whose validity runs
 * backwards is refused the same way (the conservative reading, ERRATA ERR-R4B-ST-3). Evidence label: LAB, oracle: self.
 */
class LeafLifetimeTest {
    companion object {
        val laws = LawCounters("leaf-lifetime")
        private const val CAP = 14L * 86_400 + 3_600
        private const val NOW = 1_790_000_000L

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("lifetime-boundary", "lifetime-before-clock", "lifetime-all-modes", "lifetime-before-registry", "lifetime-inverted", "honest-leaf-unchanged"))
    }

    private val rnd = SplittableRandom(23)
    private val nik = Es256.generate()
    private val leafKey = Es256.generate()
    private val node = CertTemplates.nodeCertificate(nik.private, nik.spki, CertTemplatesTest.serial(rnd), NOW - 86_400)
    private val pin = (Pin.fromSpki(nik.spki, productionKeys = false) as PinImport.Ok).pin

    private fun nodeName(): ByteArray = CertTemplates.nodeName(nik.spki)

    private fun sessionName(): ByteArray = Der.sequence(Der.set(Der.sequence(Der.oid(Oids.COMMON_NAME), Der.utf8String("asom-session ${xyz.mdhv.asom.lab.manifest.Spki.nodeTag(nik.spki)}"))))

    private fun ext(oid: String, critical: Boolean, value: ByteArray) =
        if (critical) Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(value)) else Der.sequence(Der.oid(oid), Der.octetString(value))

    /** A leaf of the template in every respect except its validity, which is exactly [notBefore] .. [notAfter]. */
    private fun leaf(notBefore: Long, notAfter: Long): ByteArray {
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(2)),
            Der.integerUnsigned(CertTemplatesTest.serial(rnd)),
            CertTemplates.ECDSA_SHA256_ALG_ID,
            nodeName(),
            Der.sequence(Der.time(notBefore), Der.time(notAfter)),
            sessionName(),
            leafKey.spki,
            Der.explicit(
                3,
                Der.sequence(
                    ext(Oids.BASIC_CONSTRAINTS, true, Der.sequence()),
                    ext(Oids.KEY_USAGE, true, Der.bitString(byteArrayOf(0x80.toByte()), 7)),
                    ext(Oids.EXT_KEY_USAGE, false, Der.sequence(Der.oid(Oids.SERVER_AUTH), Der.oid(Oids.CLIENT_AUTH))),
                    ext(Oids.AUTHORITY_KEY_ID, false, Der.sequence(Der.implicitPrimitive(0, CertTemplates.subjectKeyId(nik.spki)))),
                ),
            ),
        )
        return CertTemplates.assemble(tbs, Es256.sign(nik.private, tbs))
    }

    private fun verify(leaf: ByteArray, at: Long, mode: ChainMode = ChainMode.EstablishedServer, status: StatusLookup = StatusLookup.Known(PeerStatus.PAIRED)): ChainVerdict =
        PeerChainVerifier.verify(listOf(leaf, node), mode, Instant.ofEpochSecond(at), { status }, { true }, productionKeys = false)

    private fun code(v: ChainVerdict): String = when (v) {
        is ChainVerdict.Accepted -> "."
        is ChainVerdict.Rejected -> v.code.name
    }

    @Test
    fun theCapIsFourteenDaysAndOneHourInclusive() {
        val nb = NOW - 3_600
        assertEquals(".", code(verify(leaf(nb, nb + CAP), NOW)), "exactly 14 days + 1 hour is the template and is accepted")
        assertEquals("LEAF_LIFETIME", code(verify(leaf(nb, nb + CAP + 1), NOW)), "one second more is refused")
        assertEquals("LEAF_LIFETIME", code(verify(leaf(nb, nb + CAP + 3_600), NOW)))
        assertEquals("LEAF_LIFETIME", code(verify(leaf(nb, nb + 30L * 86_400), NOW)))
        assertEquals("LEAF_LIFETIME", code(verify(leaf(nb, nb + 365L * 86_400), NOW)))
        assertEquals("LEAF_LIFETIME", code(verify(leaf(nb, 2_524_608_000L + 10), NOW)), "a notAfter in GeneralizedTime range, years away")
        assertEquals(".", code(verify(leaf(nb, nb + 86_400), NOW)), "a shorter leaf is fine")
        laws.bump("lifetime-boundary", 7)
        laws.bump("honest-leaf-unchanged", 1)
    }

    @Test
    fun theTemplateLeafStillPassesEveryMode() {
        val f = PeerChainVerifierTest.Fixture.fresh(SplittableRandom(29), NOW)
        val now = Instant.ofEpochSecond(NOW)
        val none = PinStatusSource { StatusLookup.Absent }
        val paired = PinStatusSource { StatusLookup.Known(PeerStatus.PAIRED) }
        for (v in listOf(
            PeerChainVerifier.verify(f.chain, ChainMode.ExpectPaired(f.pin), now, paired, { false }, false),
            PeerChainVerifier.verify(f.chain, ChainMode.ExpectPairing(f.pin), now, none, { false }, false),
            PeerChainVerifier.verify(f.chain, ChainMode.EstablishedServer, now, paired, { false }, false),
            PeerChainVerifier.verify(f.chain, ChainMode.PairingServer, now, none, { true }, false),
        )) {
            assertTrue(v is ChainVerdict.Accepted, "a template leaf was refused: ${(v as? ChainVerdict.Rejected)?.code}")
            laws.bump("honest-leaf-unchanged")
        }
    }

    @Test
    fun theLifetimeIsJudgedBeforeTheClockSoItIsNeverReportedAsClockSkew() {
        val nb = NOW - 3_600
        val tooLong = leaf(nb, nb + CAP + 1)
        for (at in listOf(NOW, 0L, nb - 7_201, nb + CAP + 7_201, nb + 365L * 86_400)) {
            assertEquals("LEAF_LIFETIME", code(verify(tooLong, at)), "at $at")
            laws.bump("lifetime-before-clock")
        }
        assertEquals("CLOCK_SKEW", code(verify(leaf(nb, nb + CAP), nb - 7_201)), "an in-bounds leaf outside the window is still the clock")
        laws.bump("lifetime-before-clock")
    }

    @Test
    fun everyModeRefusesAnOverLongLeaf() {
        val nb = NOW - 3_600
        val tooLong = leaf(nb, nb + CAP + 1)
        val modes = listOf(ChainMode.ExpectPaired(pin), ChainMode.ExpectPairing(pin), ChainMode.EstablishedServer, ChainMode.PairingServer)
        for (m in modes) {
            assertEquals("LEAF_LIFETIME", code(verify(tooLong, NOW, m, StatusLookup.Absent)), "$m")
            laws.bump("lifetime-all-modes")
        }
        val server = PeerChainVerifier.verifyServer(listOf(tooLong, node), Instant.ofEpochSecond(NOW), { StatusLookup.Absent }, { true }, productionKeys = false)
        assertEquals("LEAF_LIFETIME", code(server))
        laws.bump("lifetime-all-modes")
    }

    @Test
    fun theLifetimeIsJudgedBeforeTheRegistryAndTheModeCheck() {
        val nb = NOW - 3_600
        val tooLong = leaf(nb, nb + CAP + 1)
        for (s in listOf(StatusLookup.Known(PeerStatus.REVOKED), StatusLookup.Known(PeerStatus.SUSPENDED), StatusLookup.Unreadable, StatusLookup.Absent)) {
            assertEquals("LEAF_LIFETIME", code(verify(tooLong, NOW, ChainMode.EstablishedServer, s)), "$s")
            laws.bump("lifetime-before-registry")
        }
        val wrongPin = Pin.ofHash(ByteArray(32) { 5 })
        assertEquals("LEAF_LIFETIME", code(verify(tooLong, NOW, ChainMode.ExpectPaired(wrongPin))))
        laws.bump("lifetime-before-registry")
    }

    @Test
    fun aValidityThatRunsBackwardsIsRefusedAsALifetimeFault() {
        val nb = NOW - 3_600
        assertEquals("LEAF_LIFETIME", code(verify(leaf(nb, nb - 1), nb - 3_000)), "notAfter one second before notBefore, inside both skew windows")
        assertEquals("LEAF_LIFETIME", code(verify(leaf(nb, nb - 86_400), nb - 3_000)))
        laws.bump("lifetime-inverted", 2)
    }
}
