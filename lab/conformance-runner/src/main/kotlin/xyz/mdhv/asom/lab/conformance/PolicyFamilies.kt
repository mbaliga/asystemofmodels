package xyz.mdhv.asom.lab.conformance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.policy.AvailabilityFsm
import xyz.mdhv.asom.lab.policy.BatteryBand
import xyz.mdhv.asom.lab.policy.DeclineCode
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.FsmConfig
import xyz.mdhv.asom.lab.policy.FsmEvent
import xyz.mdhv.asom.lab.policy.FsmState
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.HostInput
import xyz.mdhv.asom.lab.policy.InputKind
import xyz.mdhv.asom.lab.policy.LenderDecisionTable
import xyz.mdhv.asom.lab.policy.LenderLimits
import xyz.mdhv.asom.lab.policy.LenderLocalView
import xyz.mdhv.asom.lab.policy.LenderReply
import xyz.mdhv.asom.lab.policy.LenderSituation
import xyz.mdhv.asom.lab.policy.LenderWire
import xyz.mdhv.asom.lab.policy.OfferView
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.policy.PresenceLaw
import xyz.mdhv.asom.lab.policy.PresenceSignals
import xyz.mdhv.asom.lab.policy.ProducerStrict
import xyz.mdhv.asom.lab.policy.StDigest
import xyz.mdhv.asom.lab.policy.StalenessInput
import xyz.mdhv.asom.lab.policy.Staleness
import xyz.mdhv.asom.lab.policy.StateBuilder
import xyz.mdhv.asom.lab.policy.StateDoc
import xyz.mdhv.asom.lab.policy.StateParse
import xyz.mdhv.asom.lab.policy.StateParser

private fun JsonObject.nullableStr(key: String): String? = strOrNull(key)

private fun JsonObject.nullableLong(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()

private fun presenceOf(o: JsonObject) = PresenceSignals(
    screenInteractive = o.bool("screenInteractive"), inputIdleMs = o.long("inputIdleMs"), keyguardDismissed = o.bool("keyguardDismissed"),
    foregroundApp = o.nullableStr("foregroundApp"), heavyForegroundProcess = o.bool("heavyForegroundProcess"), consoleUser = o.nullableStr("consoleUser"),
    loginState = o.nullableStr("loginState"), otherProcessContentionPermille = o.long("otherProcessContentionPermille").toInt(),
)

private fun viewOf(o: JsonObject) = LenderLocalView(
    fsm = Fsm.valueOf(o.str("fsm")), powerSource = o.str("powerSource"), charging = o.bool("charging"),
    batteryBand = o.nullableStr("batteryBand")?.let { BatteryBand.fromWire(it) ?: throw LawViolation("unknown battery band $it") },
    batteryPercentExact = o.nullableLong("batteryPercentExact")?.toInt(), thermalBand = o.long("thermalBand").toInt(), governor = Governor.valueOf(o.str("governor")),
    backend = o.str("backend"), commit = o.str("commit"), confVersion = o.str("confVersion"), held = o.strList("held"), localQueued = o.long("localQueued").toInt(),
    peerQueued = o.long("peerQueued").toInt(), loadedModels = o.strList("loadedModels"), freeMemoryBytes = o.long("freeMemoryBytes"),
    manifestSeq = o.nullableLong("manifestSeq"), manifestDigest = o.nullableStr("manifestDigest"), presence = presenceOf(o.obj("presence")),
)

private fun flatten(v: JValue, path: String = ""): Map<String, String> = when (v) {
    is JObject -> v.members.flatMap { flatten(it.second, if (path.isEmpty()) it.first else "$path.${it.first}").entries }.associate { it.key to it.value }
    else -> mapOf(path to Jcs.serializeToString(v))
}

/**
 * W07: live state `asom.state/1` (LAB_SPEC 6.5, 7.2): the producer-strict builder, the receiver's parse (accept and reject), the producer-strict check that
 * refuses presence fields, the `st` digest, staleness classes and the pessimistic substitution, and peer-clock-skew invariance. Evidence label: LAB, oracle: self.
 */
class W07Checker : FamilyChecker("W07") {
    override val requiredLaws = setOf(
        "state-build", "presence-not-on-wire", "state-parse-ok", "state-parse-reject", "unknown-members-ignored", "producer-conform", "producer-PRESENCE_FIELD",
        "producer-UNKNOWN_MEMBER", "st-digest", "staleness-FRESH", "staleness-WARM", "staleness-STALE", "staleness-EXPIRED", "fast-substitution", "skew",
    )

    private fun stalenessInput(i: JsonObject) = StalenessInput(
        nowMonoMs = i.long("nowMonoMs"), stateRxMonoMs = i.long("rxMonoMs"), sampledAgeMs = i.long("sampledAgeMs"), sessionClosed = i.bool("sessionClosed"),
        seq = i.long("seq"), lastSeq = i.nullableLong("lastSeq"), goawaySinceState = i.bool("goaway"),
    )

    private fun baseDoc(): StateDoc = (StateParser.parse(BASE.toByteArray()) as StateParse.Ok).doc

    override fun observe(v: Vector): Observed {
        val i = v.input
        return when (val kind = i.str("kind")) {
            "stateBuild" -> {
                val view = viewOf(i.obj("view"))
                val seq = i.long("seq")
                val age = i.long("sampledAgeMs")
                val doc = StateBuilder.build(view, seq, age)
                val text = Jcs.serializeToString(doc)
                // The presence inputs of the view must not matter: rebuild with all of them replaced and require the same bytes.
                val blank = view.copy(presence = PresenceSignals(false, 0, false, null, false, null, null, 0))
                if (Jcs.serializeToString(StateBuilder.build(blank, seq, age)) != text) throw LawViolation("a presence input reached the STATE payload (LP-1)")
                bump("state-build")
                bump("presence-not-on-wire")
                bump("st-digest")
                Observed.Ok(buildJsonObject { put("jcs", text); put("st", Jcs.serializeToString(StDigest.build(view, seq))) })
            }
            "stateParse" -> when (val r = StateParser.parse(i.str("text").toByteArray())) {
                is StateParse.Ok -> {
                    bump("state-parse-ok")
                    val normal = Jcs.serializeToString(StateParser.normalForm(r.doc))
                    if (i.bool("hasUnknownMembers")) bump("unknown-members-ignored")
                    Observed.Ok(buildJsonObject { put("normal", normal) })
                }
                is StateParse.Reject -> {
                    bump("state-parse-reject")
                    Observed.Reject(r.code)
                }
            }
            "producerCheck" -> {
                val code = ProducerStrict.check(i.str("text").toByteArray())
                if (code == null) {
                    bump("producer-conform")
                    Observed.Ok(buildJsonObject { put("conforms", true) })
                } else {
                    bump("producer-$code")
                    Observed.Reject(code)
                }
            }
            "staleness" -> {
                val si = stalenessInput(i)
                val cls = Staleness.classify(si)
                bump("staleness-${cls.name}")
                val s = i.objOrNull("state")
                val fast = s?.let {
                    val d = baseDoc().copy(
                        thermalBand = it.long("thermalBand").toInt(), queueBucket = it.long("queueBucket").toInt(),
                        batteryBand = it.nullableStr("batteryBand")?.let { b -> BatteryBand.fromWire(b) }, powerSource = it.str("powerSource"),
                    )
                    Staleness.fastFields(cls, d)
                }
                if (s != null) bump("fast-substitution")
                Observed.Ok(
                    buildJsonObject {
                        put("class", cls.name)
                        put("ageMs", Staleness.ageMs(si))
                        put("probeOnly", Staleness.probeOnly(cls))
                        if (s != null) {
                            put(
                                "fast",
                                if (fast == null) JsonNull else buildJsonObject {
                                    put("thermalBand", fast.thermalBand)
                                    put("queueBucket", fast.queueBucket)
                                    put("batteryBand", jsonStringOrNull(fast.batteryBand?.wire))
                                },
                            )
                        }
                    },
                )
            }
            "skew" -> {
                val si = stalenessInput(i)
                val base = Staleness.classify(si)
                for (skew in i.arr("peerSkewMs").map { (it as JsonPrimitive).content.toLong() }) {
                    val c = Staleness.classify(si, 1_790_000_000_000L + skew)
                    if (c != base) throw LawViolation("a peer clock skew of $skew ms changed the class from $base to $c")
                    bump("skew")
                }
                bump("staleness-${base.name}")
                Observed.Ok(buildJsonObject { put("class", base.name) })
            }
            else -> throw LawViolation("unknown W07 kind '$kind'")
        }
    }

    companion object {
        private val BASE =
            """{"availability":{"fsm":"SERVING"},"engine":{"backend":"vulkan","commit":"4f1c2ab","confVersion":"1.0.0","held":[]},"manifest":null,"power":{"batteryBand":null,"charging":false,"source":"ac"},"queue":{"bucket":0},"sampledAgeMs":0,"seq":1,"thermal":{"band":0,"governor":"RUN"},"v":1}"""
    }
}

/**
 * W07p: the presence laws LP-0..LP-2 and the lender decision table (LAB_SPEC 6.5, 7.2; `:mesh-policy`): FSM traces (including the PF exception), the
 * classification of inputs, the decision table's rows, and the wire projection (a presence input changes no wire field but `fsm`, the queue bucket and a decline
 * code with its `retryAfterMs`). Evidence label: LAB, oracle: self.
 */
class W07pChecker : FamilyChecker("W07p") {
    override val requiredLaws = setOf(
        "fsm-trace", "pf-trace", "classify-presence", "classify-condition", "classify-consent", "classify-pf-only-reject", "decision-table", "decision-presence-code",
        "wire-diff-empty", "wire-diff-fsm-queue", "presence-drain-same-step", "hold-down-boundary", "condition-no-hold-down",
    )

    override fun observe(v: Vector): Observed {
        val i = v.input
        return when (val kind = i.str("kind")) {
            "fsmTrace" -> fsmTrace(i)
            "classify" -> {
                val input = HostInput.valueOf(i.str("input"))
                val pf = i.bool("pf")
                try {
                    val k = PresenceLaw.classify(input, pf)
                    bump(
                        when (k) {
                            InputKind.PRESENCE -> "classify-presence"
                            InputKind.CONDITION -> "classify-condition"
                            InputKind.CONSENT -> "classify-consent"
                        },
                    )
                    Observed.Ok(JsonPrimitive(k.name.lowercase()))
                } catch (e: IllegalArgumentException) {
                    bump("classify-pf-only-reject")
                    Observed.Reject("PF_ONLY_INPUT")
                }
            }
            "lenderDecision" -> lenderDecision(i)
            "wireDiff" -> wireDiff(i)
            else -> throw LawViolation("unknown W07p kind '$kind'")
        }
    }

    private fun event(o: JsonObject): Pair<Long, FsmEvent> {
        val t = o.long("t")
        val e: FsmEvent = when (val name = o.str("e")) {
            "enable" -> FsmEvent.Enable
            "disable" -> FsmEvent.Disable
            "start" -> FsmEvent.StartLending
            "tick" -> FsmEvent.Tick
            "inflight" -> FsmEvent.InflightChanged(o.long("n").toInt())
            "input" -> FsmEvent.Input(HostInput.valueOf(o.str("input")), (o["ok"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull())
            else -> throw LawViolation("unknown FSM event '$name'")
        }
        return t to e
    }

    private fun fsmTrace(i: JsonObject): Observed {
        val pf = i.bool("pf")
        val fsm = AvailabilityFsm(FsmConfig(pf = pf, holdDownMs = i["holdDownMs"]?.let { (it as JsonPrimitive).content.toLong() } ?: 600_000, graceMs = i.long("graceMs")))
        var s = FsmState()
        val out = ArrayList<String>()
        var lastPresence: Long? = null
        for (el in i.arr("events")) {
            val o = el as JsonObject
            val (t, e) = event(o)
            val before = s.fsm
            s = fsm.step(s, e, t)
            out += s.fsm.name
            if (e is FsmEvent.Input && PresenceLaw.classify(e.input, pf) == InputKind.PRESENCE) {
                lastPresence = t
                if (before == Fsm.SERVING) {
                    if (s.fsm != Fsm.DRAINING) throw LawViolation("presence while SERVING did not drain in the same step")
                    bump("presence-drain-same-step")
                }
            }
            if (e is FsmEvent.Tick && lastPresence != null && (t - lastPresence == 599_999L || t - lastPresence == 600_000L)) bump("hold-down-boundary")
            if (e is FsmEvent.Input && PresenceLaw.classify(e.input, pf) == InputKind.CONDITION && e.conditionsOk == true && lastPresence == null && before == Fsm.ARMED && s.fsm == Fsm.SERVING) {
                bump("condition-no-hold-down")
            }
        }
        bump(if (pf) "pf-trace" else "fsm-trace")
        return Observed.Ok(buildJsonObject { put("fsm", jsonStrings(out)) })
    }

    private fun lenderDecision(i: JsonObject): Observed {
        val o = i.obj("offer")
        val offer = OfferView(o.str("attemptId"), o.str("model"), o.str("op"), o.long("promptBytes"), o.long("maxTokens"), o.long("deadlineMs"), o.bool("stream"))
        val s = i.obj("situation")
        val lim = s.objOrNull("limits")?.let { LenderLimits(it.long("maxBodyBytes"), it.long("maxTokens"), it.long("maxConcurrent").toInt(), it.long("rpm").toInt()) } ?: LenderLimits()
        val sit = LenderSituation(
            registryStatus = PeerStatus.valueOf(s.str("registryStatus")), inferScopeGranted = s.bool("inferScopeGranted"), attemptSeenWithin24h = s.bool("attemptSeenWithin24h"),
            modelAllowedAndLoadable = s.bool("modelAllowedAndLoadable"), limits = lim, servingConditionsOk = s.bool("servingConditionsOk"), presenceActive = s.bool("presenceActive"),
            presenceHoldRemainingMs = s.long("presenceHoldRemainingMs"), predictedThermalHold = s.bool("predictedThermalHold"), estStartMs = s.long("estStartMs"),
            inflight = s.long("inflight").toInt(), requestsThisMinute = s.long("requestsThisMinute").toInt(),
        )
        val d = LenderDecisionTable.decide(offer, sit)
        bump("decision-table")
        val wire = LenderWire.of(offer, d)
        val names = wire.members.map { it.first }
        if (names.any { it in setOf("queuePos", "estStartMs", "ttftMs", "totalMs", "usage") }) throw LawViolation("the decision's wire form carries a field that moves with local use (LP-1)")
        if (sit.presenceActive && d.row == "6") {
            val r = d.reply as? LenderReply.Decline ?: throw LawViolation("a presence cause did not decline")
            if (r.code != DeclineCode.PEER_UNAVAILABLE) throw LawViolation("a presence cause must decline PEER_UNAVAILABLE, got ${r.code}")
            bump("decision-presence-code")
        }
        return Observed.Ok(
            buildJsonObject {
                put("row", d.row)
                when (val r = d.reply) {
                    is LenderReply.Accept -> { put("reply", "accept"); put("retain", r.retain) }
                    is LenderReply.Decline -> { put("reply", "decline"); put("code", r.code.name); put("retryAfterMs", r.retryAfterMs) }
                    is LenderReply.Error -> { put("reply", "error"); put("code", r.code.name); put("close", r.closeConnection) }
                }
                put("wire", Jcs.serializeToString(wire))
            },
        )
    }

    private fun wireDiff(i: JsonObject): Observed {
        val view = viewOf(i.obj("view"))
        val vary = i.obj("vary")
        var other = view
        vary.objOrNull("presence")?.let { other = other.copy(presence = presenceOf(it)) }
        vary.strOrNull("fsm")?.let { other = other.copy(fsm = Fsm.valueOf(it)) }
        vary.nullableLong("localQueued")?.let { other = other.copy(localQueued = it.toInt()) }
        vary.nullableStr("governor")?.let { other = other.copy(governor = Governor.valueOf(it)) }
        val a = flatten(StateBuilder.build(view, 7, 800))
        val b = flatten(StateBuilder.build(other, 7, 800))
        val changed = (a.keys + b.keys).filter { a[it] != b[it] }.sorted()
        val onlyPresence = vary.keys.all { it == "presence" }
        if (onlyPresence && changed.isNotEmpty()) throw LawViolation("only presence inputs changed but the wire changed at $changed")
        bump(if (changed.isEmpty()) "wire-diff-empty" else "wire-diff-fsm-queue")
        return Observed.Ok(buildJsonObject { put("changed", jsonStrings(changed)) })
    }
}
