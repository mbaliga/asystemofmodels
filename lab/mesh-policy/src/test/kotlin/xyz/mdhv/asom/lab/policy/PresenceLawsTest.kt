package xyz.mdhv.asom.lab.policy

import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * LP-0 (classification), LP-1 (what may reach the wire) and LP-2 (drain at once, 10-minute hold-down, PF explicit restart) as property tests over random event
 * sequences (LAB, oracle: self). The oracles below are written from the spec text with their own tables and bookkeeping; they read the FSM's state NAME only,
 * never its internals. Every law counts the cases that exercised it (R10).
 */
class PresenceLawsTest {
    // ---- the classification, spelled out from LAB_SPEC 6.5 / design 7.4, independent of PresenceLaw ----
    private val presenceNames = setOf(
        "SCREEN_INTERACTIVE", "INPUT_ACTIVITY", "KEYGUARD_DISMISSED", "FOREGROUND_APP", "HEAVY_FOREGROUND_PROCESS", "CONSOLE_USER", "LOGIN_STATE", "OTHER_PROCESS_CONTENTION",
    )
    private val conditionNames = setOf("POWER_SOURCE", "CHARGING", "BATTERY_LEVEL", "BATTERY_TEMPERATURE", "THERMAL_BAND", "MEMORY", "PATH", "SLEEP_IMMINENT")
    private val pfConsentNames = setOf("LEND_SCREEN_FRONTMOST", "INPUT_INSIDE_LEND_SCREEN")
    private val pfPresenceNames = setOf("LEND_SCREEN_LEFT_FOREGROUND", "SCENE_RESIGN_ACTIVE", "TERMINAL_FOCUS_LOST", "SCREEN_OFF", "INPUT_OUTSIDE_LEND_SCREEN")

    /** Independent classifier: the answer for (input, pf), or null when the input does not exist on that kind of node. */
    private fun kindOf(name: String, pf: Boolean): InputKind? = when {
        name in pfConsentNames || name in pfPresenceNames -> if (!pf) null else if (name in pfConsentNames) InputKind.CONSENT else InputKind.PRESENCE
        name == "SCREEN_INTERACTIVE" && pf -> InputKind.CONSENT
        name in presenceNames -> InputKind.PRESENCE
        name in conditionNames -> InputKind.CONDITION
        else -> error(name)
    }

    @Test
    fun lp0ClassifiesEveryInputForBothKindsOfNode() {
        var cases = 0
        for (pf in listOf(false, true)) for (input in HostInput.entries) {
            val want = kindOf(input.name, pf)
            if (want == null) {
                assertFailsWith<IllegalArgumentException>("$input exists only on a PF node") { PresenceLaw.classify(input, pf) }
            } else {
                assertEquals(want, PresenceLaw.classify(input, pf), "$input pf=$pf")
            }
            cases++
        }
        assertEquals(HostInput.entries.size * 2, cases)
        assertEquals(presenceNames + conditionNames + pfConsentNames + pfPresenceNames, HostInput.entries.map { it.name }.toSet(), "every input is named by the spec's lists")
        // The PF exception, stated case by case.
        assertEquals(InputKind.PRESENCE, PresenceLaw.classify(HostInput.SCREEN_INTERACTIVE, pf = false))
        assertEquals(InputKind.CONSENT, PresenceLaw.classify(HostInput.SCREEN_INTERACTIVE, pf = true))
        assertEquals(InputKind.PRESENCE, PresenceLaw.classify(HostInput.OTHER_PROCESS_CONTENTION, pf = true), "contention from other processes stays presence on a PF node (conservative)")
        println("LP-0 iterations: $cases (every input, both kinds of node)")
    }

    // ---- LP-2 ----

    private fun run(cfg: FsmConfig, script: List<Pair<Long, FsmEvent>>): List<FsmState> {
        val fsm = AvailabilityFsm(cfg)
        var s = FsmState()
        return script.map { (t, e) -> fsm.step(s, e, t).also { s = it } }
    }

    private fun cond(ok: Boolean) = FsmEvent.Input(HostInput.THERMAL_BAND, ok)

    @Test
    fun lp2APresenceEventWhileServingIsDrainingInTheSameStep() {
        val ac = FsmConfig(pf = false, graceMs = 30_000)
        val out = run(ac, listOf(0L to FsmEvent.Enable, 0L to cond(true), 1_000L to FsmEvent.Input(HostInput.SCREEN_INTERACTIVE)))
        assertEquals(Fsm.ARMED, out[0].fsm)
        assertEquals(Fsm.SERVING, out[1].fsm)
        assertEquals(Fsm.DRAINING, out[2].fsm, "DRAINING in the same step as the presence event")
    }

    @Test
    fun lp2ExactBoundaryMinusOneAndPlusZero() {
        val cfg = FsmConfig(graceMs = 30_000)
        val t = 5_000L
        val out = run(
            cfg,
            listOf(
                0L to FsmEvent.Enable, 0L to cond(true), t to FsmEvent.Input(HostInput.KEYGUARD_DISMISSED), (t + 1) to FsmEvent.Tick,
                (t + 599_999) to FsmEvent.Tick, (t + 600_000) to FsmEvent.Tick,
            ),
        )
        assertEquals(Fsm.DRAINING, out[2].fsm)
        assertEquals(Fsm.ARMED, out[3].fsm)
        assertEquals(Fsm.ARMED, out[4].fsm, "t + 599,999: not SERVING")
        assertEquals(Fsm.SERVING, out[5].fsm, "the first evaluation >= t + 600,000")
    }

    @Test
    fun lp2ASecondPresenceEventRestartsTheHoldDownFromTheSecondSignal() {
        val cfg = FsmConfig(graceMs = 30_000)
        val t1 = 1_000L
        val t2 = t1 + 599_999
        val out = run(
            cfg,
            listOf(
                0L to FsmEvent.Enable, 0L to cond(true), t1 to FsmEvent.Input(HostInput.INPUT_ACTIVITY), (t1 + 1) to FsmEvent.Tick,
                t2 to FsmEvent.Input(HostInput.INPUT_ACTIVITY), (t2 + 599_999) to FsmEvent.Tick, (t2 + 600_000) to FsmEvent.Tick,
            ),
        )
        assertEquals(Fsm.ARMED, out[4].fsm)
        assertEquals(Fsm.ARMED, out[5].fsm, "still not SERVING at t' + 599,999")
        assertEquals(Fsm.SERVING, out[6].fsm, "SERVING at the first evaluation >= t_last + 600,000 when conditions allow")
    }

    @Test
    fun aConditionDrainReturnsAsSoonAsTheBandDropsWithNoHoldDown() {
        val cfg = FsmConfig(graceMs = 30_000)
        val out = run(
            cfg,
            listOf(
                0L to FsmEvent.Enable, 0L to cond(true), 10_000L to cond(false), 10_001L to FsmEvent.Tick, 20_000L to cond(true),
            ),
        )
        assertEquals(Fsm.SERVING, out[1].fsm)
        assertEquals(Fsm.DRAINING, out[2].fsm, "heat drains at once")
        assertEquals(Fsm.ARMED, out[3].fsm)
        assertEquals(Fsm.SERVING, out[4].fsm, "back the moment the band drops: no hold-down for a condition")
    }

    @Test
    fun pfTouchInsideTheLendScreenStaysServingAndATouchOutsideDrainsAndNeedsStartLending() {
        val cfg = FsmConfig(pf = true, graceMs = 2_000)
        val out = run(
            cfg,
            listOf(
                0L to FsmEvent.Enable, 0L to FsmEvent.StartLending, 0L to cond(true), 1_000L to FsmEvent.Input(HostInput.LEND_SCREEN_FRONTMOST),
                2_000L to FsmEvent.Input(HostInput.SCREEN_INTERACTIVE), 3_000L to FsmEvent.Input(HostInput.INPUT_INSIDE_LEND_SCREEN),
                4_000L to FsmEvent.Input(HostInput.INPUT_OUTSIDE_LEND_SCREEN), 4_001L to FsmEvent.Tick, 6_001L to FsmEvent.Tick,
                700_000L to FsmEvent.Tick, 700_001L to FsmEvent.StartLending,
            ),
        )
        assertEquals(Fsm.SERVING, out[2].fsm)
        assertEquals(listOf(Fsm.SERVING, Fsm.SERVING, Fsm.SERVING), listOf(out[3].fsm, out[4].fsm, out[5].fsm), "consent, not presence: stays SERVING")
        assertEquals(Fsm.DRAINING, out[6].fsm)
        assertEquals(Fsm.ARMED, out[8].fsm, "the grace expired")
        assertEquals(Fsm.ARMED, out[9].fsm, "past the hold-down, but no SERVING without a new explicit Start lending")
        assertEquals(Fsm.SERVING, out[10].fsm)
    }

    @Test
    fun aPfNodeReturnsOnlyAfterTheHoldDownAndAnExplicitStart() {
        val cfg = FsmConfig(pf = true, graceMs = 2_000)
        val out = run(
            cfg,
            listOf(
                0L to FsmEvent.Enable, 0L to FsmEvent.StartLending, 0L to cond(true), 1_000L to FsmEvent.Input(HostInput.LEND_SCREEN_LEFT_FOREGROUND), 1_001L to FsmEvent.Tick,
                2_000L to FsmEvent.StartLending, 601_000L to FsmEvent.Tick,
            ),
        )
        assertEquals(Fsm.ARMED, out[5].fsm, "Start lending inside the hold-down does not bring it back early")
        assertEquals(Fsm.SERVING, out[6].fsm)
    }

    private class Oracle(val pf: Boolean) {
        var lastPresenceAt: Long? = null
        var lastCondOk = false
        var iPresence = -1
        var iEnable = -1
        var iStart = -1
        fun allows(now: Long, i: Int): Boolean =
            lastCondOk && (lastPresenceAt?.let { now >= it + 600_000 } ?: true) && (!pf || iStart > maxOf(iPresence, iEnable))
    }

    private fun kindName(name: String, pf: Boolean) = kindOf(name, pf)

    @Test
    fun lp2HoldsOverRandomEventSequences() {
        var sequences = 0
        var steps = 0
        var presenceWhileServing = 0
        var boundaryBefore = 0
        var boundaryAt = 0
        var conditionReturns = 0
        var pfNeedsStart = 0
        val rng = SplittableRandom(20260930)
        val presenceInputs = presenceNames.toList()
        val pfOnly = (pfConsentNames + pfPresenceNames).toList()
        val condInputs = conditionNames.toList()
        for (pf in listOf(false, true)) repeat(1_500) {
            val cfg = FsmConfig(pf = pf, holdDownMs = 600_000, graceMs = listOf(0L, 2_000L, 30_000L)[rng.nextInt(3)])
            val fsm = AvailabilityFsm(cfg)
            var s = FsmState()
            val o = Oracle(pf)
            var now = 0L
            var prevFsm = Fsm.OFF
            var lastPresenceSeenAt: Long? = null
            sequences++
            for (i in 0 until 60) {
                val dt = when (rng.nextInt(8)) {
                    0 -> 0L
                    1 -> 1L
                    2 -> 1_000L
                    3 -> 5_000L
                    4 -> 31_000L
                    5 -> 599_999L
                    6 -> 600_000L
                    else -> 60_000L
                }
                now += dt
                val e: FsmEvent = when (rng.nextInt(12)) {
                    0 -> if (s.fsm == Fsm.OFF) FsmEvent.Enable else FsmEvent.Tick
                    1 -> if (rng.nextInt(6) == 0) FsmEvent.Disable else FsmEvent.Tick
                    2 -> FsmEvent.StartLending
                    3, 4, 5 -> FsmEvent.Input(HostInput.valueOf(if (pf && rng.nextBoolean()) (presenceInputs + pfOnly)[rng.nextInt(presenceInputs.size + pfOnly.size)] else presenceInputs[rng.nextInt(presenceInputs.size)]))
                    6, 7, 8 -> FsmEvent.Input(HostInput.valueOf(condInputs[rng.nextInt(condInputs.size)]), rng.nextInt(3) != 0)
                    9 -> FsmEvent.InflightChanged(rng.nextInt(3))
                    else -> FsmEvent.Tick
                }
                // Independent bookkeeping, from the event alone.
                val kind = (e as? FsmEvent.Input)?.let { kindName(it.input.name, pf) }
                val before = s.fsm
                s = fsm.step(s, e, now)
                steps++
                when {
                    e is FsmEvent.Enable && before == Fsm.OFF -> o.iEnable = i
                    e is FsmEvent.StartLending -> o.iStart = i
                    kind == InputKind.PRESENCE -> {
                        o.lastPresenceAt = now
                        o.iPresence = i
                        lastPresenceSeenAt = now
                    }
                    kind == InputKind.CONDITION -> o.lastCondOk = (e as FsmEvent.Input).conditionsOk!!
                }
                // I1: a presence signal while SERVING drains at once, in the same step.
                if (kind == InputKind.PRESENCE && before == Fsm.SERVING) {
                    assertEquals(Fsm.DRAINING, s.fsm, "presence while SERVING must be DRAINING in the same step (seq $sequences step $i)")
                    presenceWhileServing++
                }
                if (e is FsmEvent.Disable) assertEquals(Fsm.OFF, s.fsm)
                // I2: SERVING only when every condition, the hold-down and (PF) the explicit start allow it.
                if (s.fsm == Fsm.SERVING) {
                    assertTrue(o.lastCondOk, "SERVING with conditions failing (seq $sequences step $i)")
                    o.lastPresenceAt?.let { assertTrue(now >= it + 600_000, "SERVING at $now, only ${now - it} ms after the last presence signal (seq $sequences step $i)") }
                    if (pf) assertTrue(o.iStart > maxOf(o.iPresence, o.iEnable), "PF node SERVING without a new explicit Start lending (seq $sequences step $i)")
                }
                // I3: it never rests in ARMED when everything allows it.
                if (s.fsm == Fsm.ARMED) assertTrue(!o.allows(now, i), "ARMED although conditions, hold-down and start all allow SERVING (seq $sequences step $i)")
                // I5: DRAINING is entered only from SERVING.
                if (s.fsm == Fsm.DRAINING && before != Fsm.DRAINING) assertEquals(Fsm.SERVING, before, "DRAINING entered from $before")
                // Coverage of the boundaries.
                if (e is FsmEvent.Tick && lastPresenceSeenAt != null) {
                    val d = now - lastPresenceSeenAt!!
                    if (d == 599_999L && s.fsm != Fsm.SERVING) boundaryBefore++
                    if (d == 600_000L && o.allows(now, i) && s.fsm == Fsm.SERVING) boundaryAt++
                }
                if (kind == InputKind.CONDITION && s.fsm == Fsm.SERVING && before == Fsm.ARMED && lastPresenceSeenAt == null) conditionReturns++
                if (pf && s.explicitStartNeeded && o.lastCondOk && s.fsm == Fsm.ARMED) pfNeedsStart++
                prevFsm = s.fsm
            }
        }
        assertTrue(presenceWhileServing > 100, "presence while SERVING: $presenceWhileServing")
        assertTrue(boundaryBefore > 5 && boundaryAt > 5, "the 599,999 / 600,000 boundary must be hit: $boundaryBefore, $boundaryAt")
        assertTrue(pfNeedsStart > 50, "PF nodes waiting for Start lending: $pfNeedsStart")
        println("LP-2 iterations: $steps steps in $sequences random sequences; presence while SERVING: $presenceWhileServing; ticks at t+599,999 (not SERVING): $boundaryBefore; at t+600,000 (SERVING): $boundaryAt; PF waiting for Start lending: $pfNeedsStart")
        println("LP-0 (in sequence): every input classified by an independent table on each step")
    }

    // ---- LP-1 ----

    private fun randomView(rng: SplittableRandom, presence: PresenceSignals = randomPresence(rng)): LenderLocalView = LenderLocalView(
        fsm = Fsm.entries[rng.nextInt(4)], powerSource = StateSchema.POWER_SOURCES[rng.nextInt(3)], charging = rng.nextBoolean(),
        batteryBand = if (rng.nextInt(4) == 0) null else BatteryBand.entries[rng.nextInt(4)], batteryPercentExact = rng.nextInt(101), thermalBand = rng.nextInt(3),
        governor = Governor.entries[rng.nextInt(3)], backend = StateSchema.BACKENDS[rng.nextInt(6)], commit = "4f1c2ab", confVersion = "1.0.0",
        held = List(rng.nextInt(3)) { "%064x".format(rng.nextLong()).padEnd(64, '0').take(64) }, localQueued = rng.nextInt(3), peerQueued = rng.nextInt(3),
        loadedModels = listOf("qwen3-8b-q4"), freeMemoryBytes = rng.nextLong(0, 1L shl 34), manifestSeq = if (rng.nextBoolean()) rng.nextLong(1, 100) else null,
        manifestDigest = "A".repeat(43), presence = presence,
    ).let { if (it.manifestSeq == null) it.copy(manifestDigest = null) else it }

    private fun randomPresence(rng: SplittableRandom) = PresenceSignals(
        screenInteractive = rng.nextBoolean(), inputIdleMs = rng.nextLong(0, 4_000_000_000L), keyguardDismissed = rng.nextBoolean(),
        foregroundApp = listOf(null, "com.sentinel.game", "org.example.terminal")[rng.nextInt(3)], heavyForegroundProcess = rng.nextBoolean(),
        consoleUser = listOf(null, "sentinel-user-4711")[rng.nextInt(2)], loginState = listOf(null, "locked", "active")[rng.nextInt(3)], otherProcessContentionPermille = rng.nextInt(1001),
    )

    private fun flatten(v: xyz.mdhv.asom.lab.json.JValue, path: String = ""): Map<String, String> = when (v) {
        is xyz.mdhv.asom.lab.json.JObject -> v.members.flatMap { flatten(it.second, if (path.isEmpty()) it.first else "$path.${it.first}").entries }.associate { it.key to it.value }
        else -> mapOf(path to xyz.mdhv.asom.lab.json.Jcs.serializeToString(v))
    }

    @Test
    fun lp1ChangingOnlyPresenceInputsChangesNoByteOfState() {
        val rng = SplittableRandom(4711)
        var cases = 0
        var presenceDiffers = 0
        repeat(2_000) {
            val base = randomView(rng)
            val other = base.copy(presence = randomPresence(rng))
            if (other.presence != base.presence) presenceDiffers++
            assertEquals(StateBuilder.jcs(StateBuilder.build(base, 7, 800)), StateBuilder.jcs(StateBuilder.build(other, 7, 800)), "presence leaked into STATE")
            assertEquals(StDigest.build(base, 7), StDigest.build(other, 7), "presence leaked into st")
            cases++
        }
        assertTrue(presenceDiffers > 1_900)
        println("LP-1 iterations: $cases pairs of views that differ only in presence inputs give byte-identical STATE and st ($presenceDiffers had different presence)")
    }

    @Test
    fun lp1TheOnlyWireFieldsThatMoveWithLocalUseAreFsmAndTheQueueBucket() {
        val rng = SplittableRandom(99)
        var cases = 0
        val moved = HashSet<String>()
        repeat(2_000) {
            val base = randomView(rng)
            // Change presence AND the three fields LP-1 allows to depend on it: the FSM state, the queue (local use), and (via the decision) codes.
            val other = base.copy(presence = randomPresence(rng), fsm = Fsm.entries[rng.nextInt(4)], localQueued = rng.nextInt(3))
            val a = flatten(StateBuilder.build(base, 7, 800))
            val b = flatten(StateBuilder.build(other, 7, 800))
            val diff = (a.keys + b.keys).filter { a[it] != b[it] }.toSet()
            assertTrue(diff.all { it == "availability.fsm" || it == "queue.bucket" }, "fields moved with local use beyond the allowed two: $diff")
            moved += diff
            val da = StDigest.build(base, 7)
            val db = StDigest.build(other, 7)
            val stDiff = flatten(da).let { x -> flatten(db).let { y -> (x.keys + y.keys).filter { k -> x[k] != y[k] } } }
            assertTrue(stDiff.all { it == "fsm" || it == "qb" }, "st fields moved: $stDiff")
            cases++
        }
        assertEquals(setOf("availability.fsm", "queue.bucket"), moved, "both allowed fields must actually have moved in the run")
        println("LP-1 iterations (allowed-diff form): $cases; fields that moved: $moved")
    }

    @Test
    fun aPresenceInputNeverAppearsInAnySerialisedOutput() {
        val rng = SplittableRandom(31337)
        val sentinels = listOf("com.sentinel.game", "sentinel-user-4711", "4242424242", "org.example.terminal")
        val allowedNames = StateSchema.MEMBERS.values.flatten().toSet() + setOf("attemptId", "code", "retryAfterMs", "fileSha256", "servedModel", "fsm", "gov", "qb", "seq", "tb")
        var outputs = 0
        var withSentinelInput = 0
        repeat(1_000) {
            val v = randomView(rng, PresenceSignals(true, 4_242_424_242L, true, "com.sentinel.game", true, "sentinel-user-4711", "locked", 987))
            withSentinelInput++
            val offer = OfferView("AAAAAAAAAAAAAAAAAAAAAA", "qwen3-8b", "chat", 1_200, 512, 60_000, true)
            val sit = LenderSituation(
                PeerStatus.PAIRED, true, false, true, LenderLimits(), servingConditionsOk = rng.nextBoolean(), presenceActive = true, presenceHoldRemainingMs = rng.nextLong(0, 700_000),
                predictedThermalHold = rng.nextBoolean(), estStartMs = rng.nextLong(0, 90_000), inflight = rng.nextInt(2), requestsThisMinute = rng.nextInt(40),
            )
            val decision = LenderDecisionTable.decide(offer, sit)
            val texts = listOf(
                StateBuilder.jcs(StateBuilder.build(v, 7, 800)), xyz.mdhv.asom.lab.json.Jcs.serializeToString(StDigest.build(v, 7)),
                xyz.mdhv.asom.lab.json.Jcs.serializeToString(LenderWire.of(offer, decision)),
            )
            for (t in texts) {
                outputs++
                sentinels.forEach { assertTrue(!t.contains(it), "presence value '$it' appears in $t") }
                val names = ProducerStrict.memberNames((xyz.mdhv.asom.lab.json.StrictJson.parse(t.toByteArray()) as xyz.mdhv.asom.lab.json.ParseResult.Ok).value)
                assertTrue(names.all { it in allowedNames }, "member outside the allow-list in $t: ${names - allowedNames}")
                assertTrue(names.none { it in StateSchema.PRESENCE_NAMES }, "a presence-named member in $t")
            }
        }
        assertEquals(3_000, outputs)
        assertEquals(1_000, withSentinelInput)
        println("LP-1 iterations (no presence value or name in any output): $outputs serialised outputs (STATE, st, accept/decline/error)")
    }

    @Test
    fun theOnlyAvailabilityStatesOnTheWireAreTheFourTheSpecAllowsAndNoCauseEverAppears() {
        assertEquals(listOf("OFF", "ARMED", "SERVING", "DRAINING"), Fsm.entries.map { it.name })
        val rng = SplittableRandom(77)
        val causes = listOf("PRESENCE", "CONDITION", "USER", "sleeping", "game", "heat", "unplugged")
        var outputs = 0
        repeat(300) {
            val fsm = AvailabilityFsm(FsmConfig(pf = rng.nextBoolean(), graceMs = 2_000))
            var s = FsmState()
            var now = 0L
            repeat(40) {
                now += rng.nextLong(0, 700_000)
                val e: FsmEvent = when (rng.nextInt(5)) {
                    0 -> if (s.fsm == Fsm.OFF) FsmEvent.Enable else FsmEvent.Tick
                    1 -> FsmEvent.Input(HostInput.HEAVY_FOREGROUND_PROCESS)
                    2 -> FsmEvent.Input(HostInput.THERMAL_BAND, rng.nextBoolean())
                    3 -> FsmEvent.StartLending
                    else -> FsmEvent.Tick
                }
                s = fsm.step(s, e, now)
                val text = StateBuilder.jcs(StateBuilder.build(randomView(rng).copy(fsm = s.fsm), 3, 0))
                outputs++
                causes.forEach { assertTrue(!text.contains(it), "the cause '$it' reached the wire: $text") }
                val parsed = StateParser.parse(text.toByteArray()) as StateParse.Ok
                assertEquals(s.fsm, parsed.doc.fsm)
            }
        }
        println("LP-1 iterations (only the four FSM names, no cause): $outputs states serialised")
    }
}
