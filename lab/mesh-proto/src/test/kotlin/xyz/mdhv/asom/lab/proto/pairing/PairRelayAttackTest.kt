package xyz.mdhv.asom.lab.proto.pairing

import java.security.MessageDigest
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.StatusLookup

/**
 * The identity relay of review finding PPW-1, run against the real machines. Attacker A holds the real QR secret and gets S to scan A's own QR. A dials the real D
 * as "S" (key A1) and hands the real S a second ceremony as "D" (key A2). Under R0 A reads nonce_S from S's hello and grinds nonce_D until S's code equals the one D
 * expects, so both people see the same code and both approve: D pairs with A1 and S pairs with A2. Under R3 A has only a commitment to nonce_S when it must choose
 * nonce_D, and the person types S's code on D. Evidence label: LAB, oracle: self.
 */
class PairRelayAttackTest {
    companion object {
        val laws = LawCounters("pair-relay")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("r0-relay-pairs-d-with-attacker", "r3-relay-attempts", "r3-relay-d-approvals-zero", "r3-late-opening-refused"))

        private fun pin(label: String): Pin = Pin.ofHash(MessageDigest.getInstance("SHA-256").digest(label.toByteArray()))
    }

    private val pinD = pin("asom-vector/spki/D")
    private val pinS = pin("asom-vector/spki/S")
    private val pinA1 = pin("asom-vector/spki/attacker-1")
    private val pinA2 = pin("asom-vector/spki/attacker-2")
    private val secretD = ByteArray(32) { it.toByte() }
    private val secretA = ByteArray(32) { (it + 100).toByte() }

    private fun counter(n: Long): ByteArray = ByteArray(32).also { for (k in 0..7) it[k] = (n ushr (8 * k)).toByte() }

    private class Sides(val s: SFsm, val d: DFsm)

    private fun sides(profile: PairProfile) = Sides(SFsm(PairFsmConfig(pinS, profile = profile)), DFsm(PairFsmConfig(pinD, profile = profile)))

    private fun sScanned(f: SFsm, rS: ByteArray): Pair<SState, PairEffect.SendHello> {
        val qr = QrPayload(pinA2, listOf(Endpoints.parse("192.168.1.66:11436")!!), secretA, 1_790_000_120, "Dell tower")
        var st: SState = f.step(SState.Idle, SEvent.Scanned(QrParse.Ok(qr))).state
        st = f.step(st, SEvent.UserConfirmConnect(true, 1_000)).state
        val step = f.step(st, SEvent.DialResult(pinA2, rS, 2_000))
        return step.state to step.effects.filterIsInstance<PairEffect.SendHello>().single()
    }

    private fun dConsumedByAttacker(f: DFsm, nA1: ByteArray, nonceD: ByteArray): DState {
        var st = f.step(DState.Closed, DEvent.UserOpenWindow(secretD, 1_000)).state
        val proof = PairCrypto.proof(secretD, pinD, pinA1, nA1)
        st = f.step(st, DEvent.HelloReceived(pinA1, nA1, proof, StatusLookup.Absent, nonceD, 2_000)).state
        return f.step(st, DEvent.ChallengeSent).state
    }

    @Test
    fun underR0AttackerGrindsNonceDAndBothPeopleSeeTheSameCodeWhileDPairsWithTheAttacker() {
        val k = sides(PairProfile.R0_COMPAT)
        val rS = counter(1_000_001)
        val (sSent, hello) = sScanned(k.s, rS)
        val nA1 = counter(2_000_002)
        val nonceDReal = counter(3_000_003)
        val dState = dConsumedByAttacker(k.d, nA1, nonceDReal)
        val shownOnD = PairCrypto.sasNumber(pinD, pinA1, nA1, nonceDReal)

        var n = 0L
        while (PairCrypto.sasNumber(pinA2, pinS, hello.nonceS, counter(n)) != shownOnD) {
            n++
            assertTrue(n < 40_000_000L, "the grind should find a match in about a million tries")
        }
        val shownOnS = (k.s.step(sSent, SEvent.ChallengeReceived(counter(n), 3_000)).effects.single() as PairEffect.ShowConsent).sas
        assertEquals((PairCrypto.sas(pinD, pinA1, nA1, nonceDReal)), shownOnS, "S shows exactly the code D shows")

        val afterLocal = k.d.step(dState, DEvent.LocalDecision(true))
        val afterBoth = k.d.step(afterLocal.state, DEvent.RemoteDecision(true))
        assertEquals("COMMITTING", afterBoth.state.kind)
        assertEquals("WritePairedRow", afterBoth.effects.single().describe())
        assertEquals(pinA1, (afterBoth.state as DState.Committing).pinS, "D would write a row for the attacker's key")
        laws.bump("r0-relay-pairs-d-with-attacker")
    }

    @Test
    fun underR3NoChoiceOfNonceDLetsTheCodeFromSOpenDsSheet() {
        val k = sides(PairProfile.R3)
        val rnd = SplittableRandom(99)
        var approvals = 0
        repeat(300) { round ->
            val rS = ByteArray(32) { rnd.nextInt(256).toByte() }
            val (sSent, hello) = sScanned(k.s, rS)
            val attackerOpening = ByteArray(32) { rnd.nextInt(256).toByte() }
            val commitmentA1 = PairCrypto.commitNonce(attackerOpening)
            val nonceDReal = ByteArray(32) { rnd.nextInt(256).toByte() }
            var dState = dConsumedByAttacker(k.d, commitmentA1, nonceDReal)

            val nonceA2 = counter(round.toLong())
            val shownOnS = (k.s.step(sSent, SEvent.ChallengeReceived(nonceA2, 3_000)).effects.single() as PairEffect.ShowConsent).sas
            assertNotEquals(hello.nonceS.toList(), rS.toList(), "A saw only the commitment")
            dState = k.d.step(dState, DEvent.RemoteDecision(true, attackerOpening)).state
            repeat(3) {
                val s = dState
                if (s is DState.AwaitDecisions) {
                    val step = k.d.step(s, DEvent.LocalDecision(true, shownOnS))
                    dState = step.state
                    if (step.effects.any { e -> e is PairEffect.WritePairedRow }) approvals++
                }
            }
            laws.bump("r3-relay-attempts")
        }
        assertEquals(0, approvals, "the code S shows must not open D's sheet for the attacker's key")
        laws.bump("r3-relay-d-approvals-zero")
    }

    @Test
    fun underR3AnAttackerThatEvenKnowsTheCodeCannotChooseItsOpeningAfterNonceD() {
        val k = sides(PairProfile.R3)
        val rS = counter(555)
        val (sSent, _) = sScanned(k.s, rS)
        val nonceDReal = counter(777)
        val shownOnS = (k.s.step(sSent, SEvent.ChallengeReceived(counter(888), 3_000)).effects.single() as PairEffect.ShowConsent).sas
        val wanted = shownOnS.replace(" ", "").toInt()
        val committed = counter(1)
        val dState = dConsumedByAttacker(k.d, PairCrypto.commitNonce(committed), nonceDReal)
        var n = 10L
        while (PairCrypto.sasNumber(pinD, pinA1, counter(n), nonceDReal) != wanted) {
            n++
            assertTrue(n < 40_000_000L)
        }
        val chosenLater = counter(n)
        assertTrue(!chosenLater.contentEquals(committed))
        val step = k.d.step(dState, DEvent.RemoteDecision(true, chosenLater))
        assertEquals("CLOSED", step.state.kind, "an opening chosen after nonce_D is not the committed nonce")
        assertEquals("Abort(PROTOCOL,PROTOCOL_ERROR)", step.effects.single().describe())
        laws.bump("r3-late-opening-refused")
    }
}
