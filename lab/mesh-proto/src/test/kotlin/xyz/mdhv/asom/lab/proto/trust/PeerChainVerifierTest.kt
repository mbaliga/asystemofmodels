package xyz.mdhv.asom.lab.proto.trust

import java.time.Instant
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.manifest.TestOnlyKeys
import xyz.mdhv.asom.lab.policy.PeerStatus

/** `verifyPeerChain` properties that the vector files cannot state: never accepts a bad chain, never throws, fails closed, and the mode matrix. Evidence label: LAB, oracle: self. */
class PeerChainVerifierTest {
    companion object {
        val laws = LawCounters("verify-peer-chain")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(
            setOf("valid-chain-all-modes", "single-flip-rejected", "hostile-never-throws", "registry-throws-fails-closed", "mode-matrix", "production-keys", "alert-is-uniform", "truncation-rejected"),
        )
    }

    class Fixture(val nik: xyz.mdhv.asom.lab.manifest.EcKeyPair, val leafKey: xyz.mdhv.asom.lab.manifest.EcKeyPair, val node: ByteArray, val leaf: ByteArray, val now: Long) {
        val pin: Pin = (Pin.fromSpki(nik.spki, productionKeys = false) as PinImport.Ok).pin
        val chain: List<ByteArray> get() = listOf(leaf, node)

        companion object {
            fun fresh(rnd: SplittableRandom, now: Long = 1_790_000_000L): Fixture {
                val nik = Es256.generate()
                val leafKey = Es256.generate()
                val node = CertTemplates.nodeCertificate(nik.private, nik.spki, CertTemplatesTest.serial(rnd), now - 86_400)
                val leaf = CertTemplates.leafCertificate(nik.private, nik.spki, leafKey.spki, CertTemplatesTest.serial(rnd), now)
                return Fixture(nik, leafKey, node, leaf, now)
            }
        }
    }

    private fun registryOf(vararg rows: Pair<Pin, StatusLookup>, default: StatusLookup = StatusLookup.Absent) =
        PinStatusSource { p -> rows.firstOrNull { it.first.equalsConstantTime(p) }?.second ?: default }

    private fun paired(f: Fixture) = registryOf(f.pin to StatusLookup.Known(PeerStatus.PAIRED))

    @Test
    fun freshlyMintedChainsAreAcceptedInEveryMode() {
        val rnd = SplittableRandom(7)
        val iterations = 20
        repeat(iterations) {
            val f = Fixture.fresh(rnd, 1_790_000_000L + rnd.nextLong(0, 40_000_000))
            val now = Instant.ofEpochSecond(f.now)
            val reg = paired(f)
            val none = registryOf()
            fun ok(v: ChainVerdict) = assertTrue(v is ChainVerdict.Accepted && v.pin.equalsConstantTime(f.pin), "expected acceptance, got ${(v as? ChainVerdict.Rejected)?.code}")
            ok(PeerChainVerifier.verify(f.chain, ChainMode.ExpectPaired(f.pin), now, reg, { false }, productionKeys = true))
            ok(PeerChainVerifier.verify(f.chain, ChainMode.EstablishedServer, now, reg, { false }, productionKeys = true))
            ok(PeerChainVerifier.verify(f.chain, ChainMode.ExpectPairing(f.pin), now, none, { false }, productionKeys = true))
            ok(PeerChainVerifier.verify(f.chain, ChainMode.PairingServer, now, none, { true }, productionKeys = true))
            ok(PeerChainVerifier.verifyServer(f.chain, now, none, { true }, productionKeys = true))
            ok(PeerChainVerifier.verifyServer(f.chain, now, reg, { true }, productionKeys = true))
            laws.bump("valid-chain-all-modes")
        }
        println("iterations: $iterations")
    }

    @Test
    fun everySingleBitFlipOfEitherCertificateIsRejected() {
        val f = Fixture.fresh(SplittableRandom(11))
        val now = Instant.ofEpochSecond(f.now)
        val reg = paired(f)
        var cases = 0
        for (which in 0..1) {
            val base = f.chain[which]
            for (i in base.indices) {
                for (mask in intArrayOf(0x01, 0x80)) {
                    val m = base.copyOf()
                    m[i] = (m[i].toInt() xor mask).toByte()
                    val chain = if (which == 0) listOf(m, f.node) else listOf(f.leaf, m)
                    val v = PeerChainVerifier.verify(chain, ChainMode.EstablishedServer, now, reg, { false }, productionKeys = true)
                    assertTrue(v is ChainVerdict.Rejected, "a flipped bit at byte $i (mask $mask) of certificate $which was accepted")
                    cases++
                }
            }
        }
        laws.bump("single-flip-rejected", cases)
        println("iterations: $cases")
    }

    @Test
    fun everyTruncationIsRejected() {
        val f = Fixture.fresh(SplittableRandom(12))
        val now = Instant.ofEpochSecond(f.now)
        var cases = 0
        for (which in 0..1) {
            val base = f.chain[which]
            for (len in 0 until base.size) {
                val m = base.copyOf(len)
                val chain = if (which == 0) listOf(m, f.node) else listOf(f.leaf, m)
                assertTrue(PeerChainVerifier.verify(chain, ChainMode.EstablishedServer, now, paired(f), { false }, true) is ChainVerdict.Rejected)
                cases++
            }
        }
        laws.bump("truncation-rejected", cases)
    }

    @Test
    fun hostileInputNeverThrowsAndNeverAccepts() {
        val rnd = SplittableRandom(13)
        val f = Fixture.fresh(rnd)
        val now = Instant.ofEpochSecond(f.now)
        val reg = paired(f)
        var cases = 0
        fun check(chain: List<ByteArray>) {
            val v = PeerChainVerifier.verify(chain, ChainMode.EstablishedServer, now, reg, { true }, true)
            val s = PeerChainVerifier.verifyServer(chain, now, reg, { true }, true)
            if (chain.size != 2 || !chain[0].contentEquals(f.leaf) || !chain[1].contentEquals(f.node)) {
                assertTrue(v is ChainVerdict.Rejected && s is ChainVerdict.Rejected, "a hostile chain was accepted")
            }
            cases++
        }
        repeat(1500) {
            val n = rnd.nextInt(0, 700)
            check(listOf(ByteArray(n) { rnd.nextInt(256).toByte() }, ByteArray(rnd.nextInt(0, 400)) { rnd.nextInt(256).toByte() }))
        }
        repeat(1500) {
            // Several random byte edits, insertions and deletions of a valid pair.
            fun mutate(b: ByteArray): ByteArray {
                var out = b.toMutableList()
                repeat(rnd.nextInt(1, 6)) {
                    when (rnd.nextInt(3)) {
                        0 -> if (out.isNotEmpty()) out[rnd.nextInt(out.size)] = rnd.nextInt(256).toByte()
                        1 -> out.add(rnd.nextInt(out.size + 1), rnd.nextInt(256).toByte())
                        else -> if (out.isNotEmpty()) out.removeAt(rnd.nextInt(out.size))
                    }
                }
                return out.toByteArray()
            }
            check(listOf(mutate(f.leaf), mutate(f.node)))
        }
        for (size in 0..5) check(List(size) { f.leaf })
        check(listOf(f.node, f.leaf))
        check(listOf(f.leaf, f.leaf))
        check(listOf(f.node, f.node))
        laws.bump("hostile-never-throws", cases)
        println("iterations: $cases")
    }

    @Test
    fun aRegistryThatThrowsOrALyingWindowProbeFailsClosed() {
        val f = Fixture.fresh(SplittableRandom(14))
        val now = Instant.ofEpochSecond(f.now)
        val boom = PinStatusSource { throw IllegalStateException("disk gone") }
        val v1 = PeerChainVerifier.verify(f.chain, ChainMode.EstablishedServer, now, boom, { true }, true)
        assertEquals(ChainReject.REGISTRY_UNREADABLE, (v1 as ChainVerdict.Rejected).code)
        val v2 = PeerChainVerifier.verifyServer(f.chain, now, boom, { true }, true)
        assertEquals(ChainReject.REGISTRY_UNREADABLE, (v2 as ChainVerdict.Rejected).code)
        val v3 = PeerChainVerifier.verify(f.chain, ChainMode.PairingServer, now, registryOf(), { throw IllegalStateException("probe") }, true)
        assertEquals(ChainReject.PAIRING_WINDOW_CLOSED, (v3 as ChainVerdict.Rejected).code)
        val v4 = PeerChainVerifier.verifyServer(f.chain, now, registryOf(), { throw IllegalStateException("probe") }, true)
        assertEquals(ChainReject.PIN_UNKNOWN, (v4 as ChainVerdict.Rejected).code)
        laws.bump("registry-throws-fails-closed", 4)
    }

    @Test
    fun everyRefusalIsTheSameAlert() {
        val f = Fixture.fresh(SplittableRandom(15))
        val now = Instant.ofEpochSecond(f.now)
        val refusals = listOf(
            PeerChainVerifier.verify(emptyList(), ChainMode.EstablishedServer, now, paired(f), { true }),
            PeerChainVerifier.verify(f.chain, ChainMode.EstablishedServer, now, registryOf(), { true }),
            PeerChainVerifier.verify(f.chain, ChainMode.EstablishedServer, now.plusSeconds(10L * 86_400 * 365), paired(f), { true }),
            PeerChainVerifier.verify(f.chain, ChainMode.ExpectPaired(Pin.ofHash(ByteArray(32))), now, paired(f), { true }),
        )
        refusals.forEach { assertEquals("certificate_unknown", (it as ChainVerdict.Rejected).alert) }
        laws.bump("alert-is-uniform", refusals.size)
    }

    @Test
    fun productionModeRefusesTheTestOnlyKeysAndIsTheDefault() {
        val k = TestOnlyKeys.key("key1")
        val rnd = SplittableRandom(16)
        val node = CertTemplates.nodeCertificate(k.keyPair().private, k.spki, CertTemplatesTest.serial(rnd), 1_790_000_000L - 86_400)
        val leafKey = Es256.generate()
        val leaf = CertTemplates.leafCertificate(k.keyPair().private, k.spki, leafKey.spki, CertTemplatesTest.serial(rnd), 1_790_000_000L)
        val now = Instant.ofEpochSecond(1_790_000_000L)
        val pin = (Pin.fromSpki(k.spki, false) as PinImport.Ok).pin
        val reg = registryOf(pin to StatusLookup.Known(PeerStatus.PAIRED))
        assertEquals(ChainReject.TEST_ONLY_KEY, (PeerChainVerifier.verify(listOf(leaf, node), ChainMode.EstablishedServer, now, reg, { false }) as ChainVerdict.Rejected).code)
        assertEquals(ChainReject.TEST_ONLY_KEY, (PeerChainVerifier.verifyServer(listOf(leaf, node), now, reg, { false }) as ChainVerdict.Rejected).code)
        assertNotNull(PeerChainVerifier.verify(listOf(leaf, node), ChainMode.EstablishedServer, now, reg, { false }, productionKeys = false) as? ChainVerdict.Accepted)
        laws.bump("production-keys", 3)
    }

    /**
     * The mode matrix, written out as an explicit grid from trust.md 3.2 (not computed by the same logic as the code). Rows: registry state.
     * Columns: EXPECT_PAIRED, EXPECT_PAIRING, ESTABLISHED_SERVER, PAIRING_SERVER, server selection. `.` is acceptance.
     */
    @Test
    fun theModeMatrixMatchesTheSpecGrid() {
        val f = Fixture.fresh(SplittableRandom(17))
        val now = Instant.ofEpochSecond(f.now)
        val wrong = Pin.ofHash(ByteArray(32) { 9 })
        val states = listOf(
            "absent" to StatusLookup.Absent, "paired" to StatusLookup.Known(PeerStatus.PAIRED), "suspended" to StatusLookup.Known(PeerStatus.SUSPENDED),
            "revoked" to StatusLookup.Known(PeerStatus.REVOKED), "corrupt" to StatusLookup.Corrupt(9), "unreadable" to StatusLookup.Unreadable,
        )
        val windowOpen = mapOf(
            "absent" to listOf("PIN_UNKNOWN", ".", "PIN_UNKNOWN", ".", "."),
            "paired" to listOf(".", ".", ".", ".", "."),
            "suspended" to listOf("PIN_SUSPENDED", ".", "PIN_SUSPENDED", ".", "PIN_SUSPENDED"),
            "revoked" to listOf("PIN_REVOKED", "PIN_REVOKED", "PIN_REVOKED", "PIN_REVOKED", "PIN_REVOKED"),
            "corrupt" to List(5) { "REGISTRY_UNREADABLE" },
            "unreadable" to List(5) { "REGISTRY_UNREADABLE" },
        )
        val windowClosed = mapOf(
            "absent" to listOf("PIN_UNKNOWN", ".", "PIN_UNKNOWN", "PAIRING_WINDOW_CLOSED", "PIN_UNKNOWN"),
            "paired" to listOf(".", ".", ".", "PAIRING_WINDOW_CLOSED", "."),
            "suspended" to listOf("PIN_SUSPENDED", ".", "PIN_SUSPENDED", "PAIRING_WINDOW_CLOSED", "PIN_SUSPENDED"),
            "revoked" to listOf("PIN_REVOKED", "PIN_REVOKED", "PIN_REVOKED", "PAIRING_WINDOW_CLOSED", "PIN_REVOKED"),
            "corrupt" to listOf("REGISTRY_UNREADABLE", "REGISTRY_UNREADABLE", "REGISTRY_UNREADABLE", "PAIRING_WINDOW_CLOSED", "REGISTRY_UNREADABLE"),
            "unreadable" to listOf("REGISTRY_UNREADABLE", "REGISTRY_UNREADABLE", "REGISTRY_UNREADABLE", "PAIRING_WINDOW_CLOSED", "REGISTRY_UNREADABLE"),
        )
        var cases = 0
        for ((window, grid) in listOf(true to windowOpen, false to windowClosed)) {
            for ((name, status) in states) {
                val reg = PinStatusSource { status }
                val modes = listOf<(() -> ChainVerdict)>(
                    { PeerChainVerifier.verify(f.chain, ChainMode.ExpectPaired(f.pin), now, reg, { window }, true) },
                    { PeerChainVerifier.verify(f.chain, ChainMode.ExpectPairing(f.pin), now, reg, { window }, true) },
                    { PeerChainVerifier.verify(f.chain, ChainMode.EstablishedServer, now, reg, { window }, true) },
                    { PeerChainVerifier.verify(f.chain, ChainMode.PairingServer, now, reg, { window }, true) },
                    { PeerChainVerifier.verifyServer(f.chain, now, reg, { window }, true) },
                )
                for ((col, run) in modes.withIndex()) {
                    val got = when (val v = run()) { is ChainVerdict.Accepted -> "."; is ChainVerdict.Rejected -> v.code.name }
                    assertEquals(grid.getValue(name)[col], got, "window=$window status=$name mode column $col")
                    cases++
                }
            }
            // A pin that is not the expected one is refused first, whatever the registry says.
            for ((name, status) in states) {
                val reg = PinStatusSource { status }
                for (m in listOf(ChainMode.ExpectPaired(wrong), ChainMode.ExpectPairing(wrong))) {
                    val v = PeerChainVerifier.verify(f.chain, m, now, reg, { window }, true) as ChainVerdict.Rejected
                    assertEquals(ChainReject.PIN_MISMATCH, v.code, "mismatch with registry $name")
                    cases++
                }
            }
        }
        laws.bump("mode-matrix", cases)
        println("iterations: $cases")
    }
}
