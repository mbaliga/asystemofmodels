package xyz.mdhv.asom.lab.policy

import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.json.Jcs

class LiveStateTest {
    private val goodText = """{"availability":{"fsm":"SERVING"},"engine":{"backend":"vulkan","commit":"4f1c2ab","confVersion":"1.0.0","held":["${"a".repeat(64)}"]},"manifest":{"bodyDigest":"${"A".repeat(43)}","seq":17},"power":{"batteryBand":null,"charging":false,"source":"ac"},"queue":{"bucket":0},"sampledAgeMs":800,"seq":4711,"thermal":{"band":0,"governor":"RUN"},"v":1}"""

    private fun parse(text: String) = StateParser.parse(text.toByteArray())

    private fun reject(text: String): String = (parse(text) as StateParse.Reject).code

    @Test
    fun theSpecsExampleParsesAndReSerialisesToItself() {
        val ok = parse(goodText) as StateParse.Ok
        assertEquals(goodText, Jcs.serializeToString(StateParser.normalForm(ok.doc)), "the spec's asom.state/1 example is already in normal form")
        assertEquals(4711, ok.doc.seq)
        assertEquals(Fsm.SERVING, ok.doc.fsm)
        assertNull(ok.doc.batteryBand)
    }

    @Test
    fun aReceiverIgnoresUnknownMembersAndNeverStoresThem() {
        val extra = goodText.replace("""{"availability":""", """{"user":{"active":true},"inflight":3,"availability":""")
        val ok = parse(extra) as StateParse.Ok
        assertEquals(goodText, Jcs.serializeToString(StateParser.normalForm(ok.doc)), "the unknown members are gone from the normal form")
        assertEquals("PRESENCE_FIELD", ProducerStrict.check(extra.toByteArray()), "but a PRODUCER may not send them")
        assertEquals("UNKNOWN_MEMBER", ProducerStrict.check(goodText.replace("\"v\":1", "\"v\":1,\"zzz\":1").toByteArray()))
        assertNull(ProducerStrict.check(goodText.toByteArray()))
        assertEquals("PRESENCE_FIELD", ProducerStrict.check(goodText.replace("\"bucket\":0", "\"bucket\":0,\"estStartS\":4").toByteArray()), "a nested addition is found too")
    }

    @Test
    fun theRejectTable() {
        assertEquals("NON_INTEGER_NUMBER", reject(goodText.replace("\"seq\":4711", "\"seq\":4711.5")), "a float")
        assertEquals("NON_INTEGER_NUMBER", reject(goodText.replace("\"seq\":4711", "\"seq\":1e3")))
        assertEquals("MALFORMED_JSON", reject("{"))
        assertEquals("DUPLICATE_KEY", reject(goodText.replace("\"v\":1", "\"v\":1,\"v\":1")))
        assertEquals("WRONG_TYPE", reject("[]"))
        assertEquals("BAD_VERSION", reject(goodText.replace("\"v\":1", "\"v\":2")))
        assertEquals("MISSING_MEMBER", reject(goodText.replace("\"seq\":4711,", "")))
        assertEquals("MISSING_MEMBER", reject(goodText.replace("\"manifest\":{\"bodyDigest\":\"${"A".repeat(43)}\",\"seq\":17},", "")))
        assertEquals("WRONG_TYPE", reject(goodText.replace("\"charging\":false", "\"charging\":0")))
        assertEquals("UNKNOWN_ENUM", reject(goodText.replace("SERVING", "SLEEPING")))
        assertEquals("UNKNOWN_ENUM", reject(goodText.replace("\"vulkan\"", "\"webgpu\"")))
        assertEquals("UNKNOWN_ENUM", reject(goodText.replace("\"source\":\"ac\"", "\"source\":\"solar\"")))
        assertEquals("OUT_OF_RANGE", reject(goodText.replace("\"band\":0", "\"band\":3")))
        assertEquals("OUT_OF_RANGE", reject(goodText.replace("\"bucket\":0", "\"bucket\":-1")))
        assertEquals("OUT_OF_RANGE", reject(goodText.replace("\"seq\":4711", "\"seq\":0")))
        assertEquals("OUT_OF_RANGE", reject(goodText.replace("\"sampledAgeMs\":800", "\"sampledAgeMs\":-1")))
        assertEquals("OUT_OF_RANGE", reject(goodText.replace("4f1c2ab", "XYZ")))
        assertEquals("NUMBER_RANGE", reject(goodText.replace("\"seq\":4711", "\"seq\":9007199254740992")))
        assertEquals("OUT_OF_RANGE", reject(goodText.replace("a".repeat(64), "abc")))
    }

    @Test
    fun aSampledAgeAboveTheCapIsAcceptedAndClampedByTheStalenessFormula() {
        val ok = parse(goodText.replace("\"sampledAgeMs\":800", "\"sampledAgeMs\":90000")) as StateParse.Ok
        assertEquals(90_000, ok.doc.sampledAgeMs)
        assertEquals(60_000, Staleness.ageMs(StalenessInput(1_000, 1_000, 90_000, false, 1, null, false)))
        assertTrue(Jcs.serializeToString(StateParser.normalForm(ok.doc)).contains("\"sampledAgeMs\":60000"))
    }

    @Test
    fun stalenessBoundariesAreExact() {
        fun c(ageMs: Long, sampled: Long = 0, closed: Boolean = false, seq: Long = 5, last: Long? = null, goaway: Boolean = false) =
            Staleness.classify(StalenessInput(nowMonoMs = 10_000 + ageMs, stateRxMonoMs = 10_000, sampledAgeMs = sampled, sessionClosed = closed, seq = seq, lastSeq = last, goawaySinceState = goaway))
        assertEquals(Freshness.FRESH, c(0))
        assertEquals(Freshness.FRESH, c(5_000))
        assertEquals(Freshness.WARM, c(5_001))
        assertEquals(Freshness.WARM, c(30_000))
        assertEquals(Freshness.STALE, c(30_001))
        assertEquals(Freshness.STALE, c(300_000))
        assertEquals(Freshness.EXPIRED, c(300_001))
        assertEquals(Freshness.FRESH, c(4_000, sampled = 1_000), "the sender's sampled age adds to the requester's own age")
        assertEquals(Freshness.WARM, c(4_500, sampled = 1_000))
        assertEquals(Freshness.STALE, c(0, sampled = 60_000), "capped at 60,000, so never past STALE by itself")
        assertEquals(Freshness.STALE, c(0, sampled = 10_000_000))
        assertEquals(Freshness.WARM, c(30_000, closed = true), "a closed session's state is usable while it is at most 30,000 old")
        assertEquals(Freshness.EXPIRED, c(30_001, closed = true))
        assertEquals(Freshness.EXPIRED, c(0, seq = 4, last = 5), "seq went backwards")
        assertEquals(Freshness.FRESH, c(0, seq = 5, last = 5), "an equal seq is a repeat, not a reset")
        assertEquals(Freshness.FRESH, c(0, seq = 6, last = 5))
        assertEquals(Freshness.EXPIRED, c(0, goaway = true))
    }

    @Test
    fun peerClockSkewOfTenMinutesEitherWayChangesNoClass() {
        val rng = SplittableRandom(3)
        var cases = 0
        val seen = HashSet<Freshness>()
        repeat(5_000) {
            val i = StalenessInput(
                nowMonoMs = rng.nextLong(0, 2_000_000), stateRxMonoMs = rng.nextLong(0, 1_000_000), sampledAgeMs = rng.nextLong(0, 100_000), sessionClosed = rng.nextBoolean(),
                seq = rng.nextLong(1, 10), lastSeq = if (rng.nextBoolean()) rng.nextLong(1, 10) else null, goawaySinceState = rng.nextInt(10) == 0,
            ).let { if (it.nowMonoMs < it.stateRxMonoMs) it.copy(nowMonoMs = it.stateRxMonoMs) else it }
            val base = Staleness.classify(i)
            for (skew in listOf(-600_000L, -1L, 0L, 1L, 600_000L)) {
                // The peer's wall-clock timestamp is not an input: the only way skew could matter is through a field that does not exist.
                val skewedWall = 1_790_000_000_000L + skew
                assertEquals(base, Staleness.classify(i, skewedWall), "skew $skewedWall")
                cases++
            }
            seen += base
        }
        assertEquals(Freshness.entries.toSet(), seen, "every class must occur")
        assertTrue(StalenessInput::class.java.declaredFields.none { "wall" in it.name.lowercase() || "ts" == it.name.lowercase() }, "staleness has no wall-clock field")
        println("W07 skew iterations: $cases (peer clock +-10 min never changes a class; every class occurred)")
    }

    private fun doc(tb: Int, qb: Int, band: BatteryBand?, source: String) =
        (parse(goodText) as StateParse.Ok).doc.copy(thermalBand = tb, queueBucket = qb, batteryBand = band, powerSource = source)

    @Test
    fun theStaleSubstitutionAppliedLiterally() {
        val stale = Freshness.STALE
        assertEquals(Staleness.Fast(0, 0, BatteryBand.GE80), Staleness.fastFields(stale, doc(0, 0, BatteryBand.GE80, "ac")))
        assertEquals(Staleness.Fast(1, 2, BatteryBand.GE80), Staleness.fastFields(stale, doc(1, 1, BatteryBand.GE80, "ac")), "queue: min(2, last + 1)")
        assertEquals(Staleness.Fast(2, 2, BatteryBand.GE80), Staleness.fastFields(stale, doc(2, 2, BatteryBand.GE80, "ac")))
        assertEquals(Staleness.Fast(1, 0, BatteryBand.B50_79), Staleness.fastFields(stale, doc(1, 0, BatteryBand.GE80, "battery")), "on battery: one band lower")
        assertEquals(Staleness.Fast(0, 0, BatteryBand.LT20), Staleness.fastFields(stale, doc(0, 0, BatteryBand.LT20, "battery")), "saturates at lt20")
        assertEquals(Staleness.Fast(0, 0, null), Staleness.fastFields(stale, doc(0, 0, null, "battery")))
        assertEquals(Staleness.Fast(2, 1, BatteryBand.B20_49), Staleness.fastFields(Freshness.WARM, doc(2, 1, BatteryBand.B20_49, "battery")), "WARM passes the received values")
        assertNull(Staleness.fastFields(Freshness.EXPIRED, doc(0, 0, null, "ac")))
        assertTrue(Staleness.probeOnly(Freshness.EXPIRED) && !Staleness.probeOnly(Freshness.STALE))
        // ERR-LP-5: for the thermal band, max(last, 1) if last >= 1 changes nothing.
        for (tb in 0..2) assertEquals(tb, Staleness.fastFields(stale, doc(tb, 0, null, "ac"))!!.thermalBand)
    }

    @Test
    fun theBatteryBandsLowerOneStepAndSaturate() {
        assertEquals(listOf(BatteryBand.B50_79, BatteryBand.B20_49, BatteryBand.LT20, BatteryBand.LT20), BatteryBand.entries.map { it.lower() })
        assertEquals(BatteryBand.B50_79, BatteryBand.fromWire("50-79"))
        assertNull(BatteryBand.fromWire("ge90"))
    }

    @Test
    fun queueBucketIsTheCappedSumOfLocalAndPeerQueues() {
        assertEquals(listOf(0, 1, 2, 2, 2), listOf(StateBuilder.queueBucket(0, 0), StateBuilder.queueBucket(1, 0), StateBuilder.queueBucket(1, 1), StateBuilder.queueBucket(0, 5), StateBuilder.queueBucket(4, 4)))
    }
}
