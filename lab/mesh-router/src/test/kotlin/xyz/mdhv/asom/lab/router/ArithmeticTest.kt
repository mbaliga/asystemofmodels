package xyz.mdhv.asom.lab.router

import java.math.BigInteger
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RL20: within the bounds of LAB_SPEC 6.4 (P <= 2^20, N <= 2^15, rates in 1..10^9, bytes <= 2^40, times <= 2^40) no intermediate reaches 2^53 - 1, so the
 * integer estimator equals exact arithmetic. The oracle is BigInteger written from the formulas. Outside the bounds the result saturates and never wraps.
 */
class ArithmeticTest {
    private val big = { x: Long -> BigInteger.valueOf(x) }
    private val million = BigInteger.valueOf(1_000_000)
    private val limit = BigInteger.valueOf(Sat.MAX)

    private fun ceil(a: BigInteger, b: BigInteger): BigInteger = a.add(b).subtract(BigInteger.ONE).divide(b)

    private fun bigDecode(m: Long, dec: Long, steady: Long, onset: Long?, busy: Long, queue: Long, prefill: Long, hot: Boolean): BigInteger {
        if (m <= 0) return BigInteger.ZERO
        val already = big(busy).add(big(queue)).add(big(prefill))
        if (hot || (onset != null && already >= big(onset))) return ceil(big(m).multiply(million), big(minOf(dec, steady)))
        if (onset == null) return ceil(big(m).multiply(million), big(dec))
        val cool = big(onset).subtract(already)
        val tokCool = cool.multiply(big(dec)).divide(million)
        if (big(m) <= tokCool) return ceil(big(m).multiply(million), big(dec))
        return cool.add(ceil(big(m).subtract(tokCool).multiply(million), big(steady)))
    }

    @Test
    fun rl20_estimatorEqualsExactArithmeticWithinTheBounds() {
        val r = SplittableRandom(20)
        fun rate(): Long = if (r.nextInt(4) == 0) (if (r.nextBoolean()) 1L else 1_000_000_000L) else 1 + (r.nextLong() ushr 1) % 1_000_000_000L
        fun time(): Long = (r.nextLong() ushr 1) % (1L shl 40)
        var cases = 0
        var deepest = BigInteger.ZERO
        repeat(20_000) {
            val p = 1L + r.nextInt(1 shl 20)
            val n = 1L + r.nextInt(1 shl 15)
            val pre = rate()
            val dec = rate()
            val steady = rate()
            val ttft0 = time() % (1L shl 30)
            val onset = if (r.nextBoolean()) null else time()
            val busy = time() % (1L shl 30)
            val queue = time() % (1L shl 30)
            val hot = r.nextInt(5) == 0
            val prefillMs = Estimator.prefillMs(p, pre, ttft0)
            assertEquals(ceil(big(p).multiply(million), big(pre)).add(big(ttft0)), big(prefillMs))
            val decodeMs = Estimator.thermalAwareDecode(n - 1, dec, steady, onset, busy, queue, prefillMs, hot)
            val want = bigDecode(n - 1, dec, steady, onset, busy, queue, prefillMs, hot)
            assertEquals(want, big(decodeMs), "E7 m=${n - 1} dec=$dec steady=$steady onset=$onset busy=$busy queue=$queue prefill=$prefillMs hot=$hot")
            assertTrue(want < limit && big(prefillMs) < limit)
            deepest = deepest.max(big(p).multiply(million))
            val bytes = time()
            val kbps = rate()
            val link = LinkStats(time() % 10_000, kbps, xyz.mdhv.asom.lab.ledger.PeerPath.LAN, false, r.nextBoolean(), 3)
            val net = Estimator.netMs(link, bytes, MeshConfig())
            val wantNet = big(link.rttMs).add(ceil(big(bytes).multiply(BigInteger.valueOf(8)), big(kbps))).add(if (link.sessionWarm) BigInteger.ZERO else big(link.rttMs).multiply(BigInteger.valueOf(3)).add(big(40)))
            assertEquals(wantNet, big(net))
            val fileBytes = time()
            val bpm = rate()
            assertEquals(ceil(big(fileBytes), big(bpm)), big(Estimator.loadMs(fileBytes, bpm)))
            val power = 1 + (r.nextLong() ushr 1) % 1_000_000
            val active = time() % 1_000_000_000L
            val energy = Estimator.energyMilliJ(power, active)
            assertEquals(big(power).multiply(big(active)).divide(big(1000)), big(energy))
            val design = 1 + (r.nextLong() ushr 1) % 1_000_000
            assertEquals(ceil(big(energy).multiply(big(1000)), big(design).multiply(big(3600))), big(Estimator.batteryUsedPermille(energy, design)))
            cases++
        }
        println("RL20 iterations: $cases violations: 0 (largest product P x 10^6 = $deepest)")
        assertTrue(cases >= Laws.FLOOR)
    }

    @Test
    fun outsideTheBoundsResultsSaturateAndNeverWrap() {
        val max = 1L shl 62
        assertEquals(Sat.MAX, Sat.mul(max, max))
        assertEquals(Sat.MAX, Sat.add(Sat.MAX, Sat.MAX))
        assertEquals(Sat.MAX, Estimator.prefillMs(Sat.MAX, 1, Sat.MAX))
        assertEquals(Sat.MAX, Estimator.thermalAwareDecode(Sat.MAX, 1, 1, null, 0, 0, 0, false))
        assertEquals(0, Sat.sub0(3, 9))
        assertEquals(Sat.MAX, Sat.ceilDiv(max, 1))
    }

    @Test
    fun aPlanOverExtremeInBoundInputsNeverThrowsOrExceedsTheLimit() {
        var plans = 0
        for (seed in 1L..20L) {
            val gen = WorldGen(seed)
            repeat(30) {
                val w = gen.world()
                val extreme = w.withExtremes(gen)
                when (val res = tryPlan(extreme.q, extreme.s)) {
                    is PlanRes.Err -> Unit
                    is PlanRes.Ok -> res.plan.attempts.forEach { a ->
                        a.estimate?.let { e -> listOf(e.netMs, e.loadMs, e.queueMs, e.prefillMs, e.decodeMs, e.ttftMs, e.totalMs, e.energyMilliJ, e.batteryUsedPermille).forEach { v -> assertTrue(v in 0..Sat.MAX) } }
                        a.score?.let { s -> assertTrue(s.total in 0..Sat.MAX) }
                    }
                }
                plans++
            }
        }
        if (plans < Laws.FLOOR) fail("too few plans")
        println("RL20b iterations: $plans violations: 0")
    }

    private fun GenWorld.withExtremes(gen: WorldGen): GenWorld {
        val extremeRate = { if (gen.chance(50)) 1L else 1_000_000_000L }
        val peers = s.peers.map { n ->
            n.copy(
                priors = n.priors.mapValues { (_, p) -> p.copy(decodeAt = listOf(512 to extremeRate()), prefillMilliTokPerSec = extremeRate(), steadyMilliTokPerSec = extremeRate(), ttft0Ms = 1L shl 30) },
                files = n.files.map { it.copy(fileBytes = 1L shl 40, contextTokens = 1L shl 22) }, link = n.link?.copy(rttMs = 1L shl 30, kbps = extremeRate()), maxContextTokens = null,
            )
        }
        val self = s.self.copy(
            priors = s.self.priors.mapValues { (_, p) -> p.copy(decodeAt = listOf(512 to extremeRate()), prefillMilliTokPerSec = extremeRate(), steadyMilliTokPerSec = extremeRate()) },
            files = s.self.files.map { it.copy(fileBytes = 1L shl 40, contextTokens = 1L shl 22) },
        )
        val q2 = q.copy(promptTokens = 1 shl 20, promptBytes = 1L shl 40, maxTokensCap = 1 shl 15, deadlineMs = 1L shl 40)
        return GenWorld(q2, s.copy(peers = peers, self = self))
    }
}
