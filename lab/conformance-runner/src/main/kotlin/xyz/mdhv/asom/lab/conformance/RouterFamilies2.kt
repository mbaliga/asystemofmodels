package xyz.mdhv.asom.lab.conformance

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.catalogue.AuthSpec
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.Freshness
import xyz.mdhv.asom.lab.policy.StateDoc
import xyz.mdhv.asom.lab.router.Action
import xyz.mdhv.asom.lab.router.AttemptContext
import xyz.mdhv.asom.lab.router.AttemptEvent
import xyz.mdhv.asom.lab.router.AttemptPhase
import xyz.mdhv.asom.lab.router.AppOutputEwma
import xyz.mdhv.asom.lab.router.BreakerCurve
import xyz.mdhv.asom.lab.router.BreakerState
import xyz.mdhv.asom.lab.router.CapCounter
import xyz.mdhv.asom.lab.router.CapDelta
import xyz.mdhv.asom.lab.router.CapReducer
import xyz.mdhv.asom.lab.router.ClaimKey
import xyz.mdhv.asom.lab.router.ClaimState
import xyz.mdhv.asom.lab.router.CloudEntry
import xyz.mdhv.asom.lab.router.DeclineBackoff
import xyz.mdhv.asom.lab.router.Estimate
import xyz.mdhv.asom.lab.router.Ewma
import xyz.mdhv.asom.lab.router.Failover
import xyz.mdhv.asom.lab.router.FileKey
import xyz.mdhv.asom.lab.router.LinkReducer
import xyz.mdhv.asom.lab.router.LinkStats
import xyz.mdhv.asom.lab.router.LiveStateCache
import xyz.mdhv.asom.lab.router.Merge
import xyz.mdhv.asom.lab.router.MergeItem
import xyz.mdhv.asom.lab.router.PeerStateCache
import xyz.mdhv.asom.lab.router.PureBreaker
import xyz.mdhv.asom.lab.router.Scored
import xyz.mdhv.asom.lab.router.ScoreBreakdown
import xyz.mdhv.asom.lab.router.StDigestParser
import xyz.mdhv.asom.lab.router.Tier
import xyz.mdhv.asom.routing.Candidate
import xyz.mdhv.asom.routing.CooldownRegistry

private fun JsonObject.nlong(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()

private fun jl(v: Long?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

private fun js(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

private fun stubProvider(id: String) = ProviderEntry(id, id, ProviderKind.OPENAI_COMPAT, "https://stub.invalid", AuthSpec("bearer"), false, true)

/** R03: the merge per policy (LAB_SPEC 6.7) and the cap on UNVERIFIED claims, through the real [Merge]. Evidence label: LAB, oracle: self. */
class R03Checker : FamilyChecker("R03") {
    override val requiredLaws = setOf("auto", "cheapest", "fastest", "best-reasoning", "local-only", "cap-win", "cap-swap", "cap-none", "probe-only", "never-cloud", "tie-break")

    override fun observe(v: Vector): Observed {
        val i = v.input
        val policy = Policy.fromWire(i.str("policy")) ?: throw LawViolation("policy")
        val sov = i.arr("sovereign").map { e ->
            val o = e as JsonObject
            val t = o.arr("terms").map { (it as JsonPrimitive).content.toLong() }
            val key = o.objOrNull("key")?.let { ClaimKey(it.str("nodeId"), it.str("fileSha256"), it.str("backend")) }
            val probe = o.reqBool("probe")
            Scored(
                Tier.valueOf(o.str("tier")), o.str("nodeId"), FileKey(o.str("modelId"), o.str("sha"), null, 0, o.nlong("rank")?.toInt()), Estimate.ZERO,
                ScoreBreakdown(t[0], t[1], t[2], t[3], t[4], t[5]), o.reqBool("usable"), probe, ClaimState.valueOf(o.str("claim")), if (probe) Freshness.EXPIRED else Freshness.FRESH, key,
            )
        }
        val cloud = i.arr("cloud").map { e ->
            val o = e as JsonObject
            CloudEntry(Candidate(stubProvider(o.str("provider")), o.str("model")), o.nlong("rank")?.toInt(), o.nlong("s1"))
        }
        val caps = i.arr("caps").associate { e ->
            val o = e as JsonObject
            ClaimKey(o.str("nodeId"), o.str("fileSha256"), o.str("backend")) to CapCounter(o.long("wouldWin").toInt(), o.long("won").toInt())
        }
        val never = i.reqBool("neverCloud")
        val r = Merge.order(policy, sov, cloud, never, caps)
        bump(policy.wire)
        if (never) bump("never-cloud")
        if (sov.any { it.probeOnly }) bump("probe-only")
        if (sov.size >= 2 && sov.zipWithNext().any { (a, b) -> a.total == b.total }) bump("tie-break")
        if (r.capDelta.entries.isEmpty()) bump("cap-none") else if (r.cappedSwap) bump("cap-swap") else bump("cap-win")
        val order = r.items.map { m ->
            when (m) {
                is MergeItem.Sov -> "${m.c.tier.name}:${m.c.nodeId}/${m.c.file.fileSha256.take(8)}"
                is MergeItem.Cloud -> "cloud:${m.e.candidate.provider.id}/${m.e.candidate.modelId}"
            }
        }
        return Observed.Ok(
            buildJsonObject {
                put("order", buildJsonArray { order.forEach { add(JsonPrimitive(it)) } })
                put(
                    "capDelta",
                    buildJsonArray {
                        r.capDelta.entries.forEach { e ->
                            add(
                                buildJsonObject {
                                    put("nodeId", e.key.nodeId); put("fileSha256", e.key.fileSha256); put("backend", e.key.backend)
                                    put("wouldWinInc", e.wouldWinInc); put("wonInc", e.wonInc); put("swapped", e.swapped)
                                },
                            )
                        }
                    },
                )
                put("cappedSwap", r.cappedSwap)
            },
        )
    }
}

/** R05: the retry-or-fail table of router.md 8.2 with the r3 codes through the real [Failover]. Evidence label: LAB, oracle: self. */
class R05Checker : FamilyChecker("R05") {
    override val requiredLaws = setOf(
        "action:NEXT", "action:FAIL_IN_BAND", "action:RETURN", "action:STOP", "action:CLIENT_CANCELLED", "action:V2_LOCAL", "illegal", "reeval", "breaker:TRANSPORT_FAILURE",
        "breaker:FAILURE", "breaker:EXCLUDE_UNTIL_SESSION_REESTABLISHED", "tracker:FAILED_OBSERVATION", "tracker:MEMORY_DISCREPANT", "backoff", "cancel-frame", "no-retry-after-head",
    )

    private fun event(o: JsonObject): AttemptEvent = when (val k = o.str("kind")) {
        "DialFailed" -> AttemptEvent.DialFailed
        "Decline" -> AttemptEvent.Decline(o.str("code"), o.long("retryAfterMs"))
        "ErrorFrame" -> AttemptEvent.ErrorFrame(o.str("code"))
        "OfferTimeout" -> AttemptEvent.OfferTimeout
        "AcceptedWorse" -> AttemptEvent.AcceptedWorse
        "HeadTimeout" -> AttemptEvent.HeadTimeout
        "ConnectionLost" -> AttemptEvent.ConnectionLost
        "Terminal" -> AttemptEvent.Terminal(o.str("terminal"))
        "ClientDisconnect" -> AttemptEvent.ClientDisconnect
        "DeadlinePassed" -> AttemptEvent.DeadlinePassed
        "SelfGovernorHold" -> AttemptEvent.SelfGovernorHold
        else -> throw LawViolation("event $k")
    }

    override fun observe(v: Vector): Observed {
        val i = v.input
        if (i.str("kind") == "reeval") {
            bump("reeval")
            return Observed.Ok(buildJsonObject { put("worse", Failover.acceptedIsWorse(i.long("acceptedScore"), i.long("runnerUpScore"))) })
        }
        val d = Failover.decide(AttemptPhase.valueOf(i.str("phase")), AttemptContext(i.reqBool("stream"), i.reqBool("headersCommitted")), event(i.obj("event")))
        if (d == null) {
            bump("illegal")
            return Observed.Reject("ILLEGAL_TRANSITION")
        }
        bump("action:${d.action}")
        if (d.breaker.name != "NONE") bump("breaker:${d.breaker}")
        if (d.tracker.name != "NONE") bump("tracker:${d.tracker}")
        if (d.backoffMs != null) bump("backoff")
        if (d.cancelFrame) bump("cancel-frame")
        if (d.action == Action.FAIL_IN_BAND) bump("no-retry-after-head")
        return Observed.Ok(
            buildJsonObject {
                put("action", d.action.name); put("cancelFrame", d.cancelFrame); put("rowStatus", d.rowStatus); put("breaker", d.breaker.name)
                put("backoffMs", jl(d.backoffMs)); put("tracker", d.tracker.name); put("sseReason", js(d.sseReason)); put("newAttemptId", d.newAttemptId)
                put("refreshRegistry", d.refreshRegistry); put("contentLeft", d.contentLeft)
            },
        )
    }
}

/** R06: the pure reducers: integer EWMA, cap counters, freshness classes, the `st` digest and the breaker pinned to the real `CooldownRegistry`. Evidence label: LAB, oracle: self. */
class R06Checker : FamilyChecker("R06") {
    override val requiredLaws = setOf(
        "ewma", "link", "appEwma", "cap", "capRun", "freshness-FRESH", "freshness-WARM", "freshness-STALE", "freshness-EXPIRED", "powerFreshness-FRESH", "powerFreshness-WARM", "powerFreshness-STALE", "powerFreshness-EXPIRED", "stParse", "declineBackoff", "breaker", "breaker-pinned",
    )

    private fun baseDoc(seq: Long, age: Long) = StateDoc(
        seq, age, Fsm.SERVING, "ac", false, null, 0, Governor.RUN, "metal", "0123abc", "0.2.0", emptyList(), 0, null, null,
    )

    override fun observe(v: Vector): Observed {
        val i = v.input
        return when (val kind = i.str("kind")) {
            "ewma" -> {
                bump("ewma")
                var e: Long? = null
                val out = i.arr("samples").map { s -> Ewma.update(e, (s as JsonPrimitive).content.toLong()).also { e = it } }
                Observed.Ok(buildJsonObject { put("values", buildJsonArray { out.forEach { add(JsonPrimitive(it)) } }) })
            }
            "link" -> {
                bump("link")
                val s = i.obj("start")
                var l = LinkStats(s.long("rttMs"), s.long("kbps"), xyz.mdhv.asom.lab.ledger.PeerPath.LAN, false, true, s.long("samples").toInt())
                for (e in i.arr("events")) {
                    val o = e as JsonObject
                    l = if (o.str("kind") == "rtt") LinkReducer.onRtt(l, o.long("ms")) else LinkReducer.onTransfer(l, o.long("bytes"), o.long("ms"))
                }
                Observed.Ok(buildJsonObject { put("rttMs", l.rttMs); put("kbps", l.kbps); put("samples", l.samples) })
            }
            "appEwma" -> {
                bump("appEwma")
                var m = i.obj("start").entries.associate { (k, x) -> k to (x as JsonPrimitive).content.toInt() }
                for (e in i.arr("events")) {
                    val o = e as JsonObject
                    m = AppOutputEwma.onCompletion(m, o.str("app"), o.long("tokens").toInt())
                }
                Observed.Ok(buildJsonObject { put("values", buildJsonObject { m.toSortedMap().forEach { (k, x) -> put(k, x) } }) })
            }
            "cap" -> {
                bump("cap")
                val s = i.obj("start")
                val key = ClaimKey("k", "s", "b")
                var caps = mapOf(key to CapCounter(s.long("wouldWin").toInt(), s.long("won").toInt()))
                for (d in i.arr("deltas")) {
                    val o = d as JsonObject
                    caps = CapReducer.commit(caps, CapDelta(listOf(CapDelta.Entry(key, o.long("wouldWinInc").toInt(), o.long("wonInc").toInt(), false))))
                }
                val c = caps.getValue(key)
                Observed.Ok(buildJsonObject { put("wouldWin", c.wouldWin); put("won", c.won) })
            }
            "capRun" -> {
                bump("capRun")
                val key = ClaimKey("p1", "a", "metal")
                fun sov(node: String, s1: Long, claim: ClaimState, k: ClaimKey?) = Scored(
                    Tier.PEER, node, FileKey("m", "a", null, 0, null), Estimate.ZERO, ScoreBreakdown(s1, 0, 0, 0, 0, 0), true, false, claim, Freshness.FRESH, k,
                )
                var caps = emptyMap<ClaimKey, CapCounter>()
                val wins = ArrayList<Boolean>()
                repeat(i.long("k").toInt()) {
                    val r = Merge.order(Policy.AUTO, listOf(sov("p1", 10, ClaimState.UNVERIFIED, key), sov("p2", 20, ClaimState.CORROBORATED, null)), emptyList(), false, caps)
                    wins += (r.items.first() as MergeItem.Sov).c.nodeId == "p1"
                    caps = CapReducer.commit(caps, r.capDelta)
                }
                val c = caps.getValue(key)
                Observed.Ok(buildJsonObject { put("wins", buildJsonArray { wins.forEach { add(JsonPrimitive(it)) } }); put("wouldWin", c.wouldWin); put("won", c.won) })
            }
            "freshness" -> {
                var c = PeerStateCache()
                for (e in i.arr("events")) {
                    val o = e as JsonObject
                    c = when (o.str("kind")) {
                        "session" -> LiveStateCache.onSessionOpen(c)
                        "sessionClose" -> LiveStateCache.onSessionClose(c)
                        "goaway" -> LiveStateCache.onGoaway(c)
                        "state" -> LiveStateCache.onState(c, baseDoc(o.long("seq"), o.long("sampledAgeMs")), o.long("rxMonoMs"))
                        "piggyback" -> LiveStateCache.onPiggyback(
                            c, xyz.mdhv.asom.lab.router.StDigestDoc(o.long("seq"), Fsm.valueOf(o.str("fsm")), o.long("tb").toInt(), Governor.valueOf(o.str("gov")), o.long("qb").toInt()), o.long("rxMonoMs"),
                        )
                        else -> throw LawViolation("event")
                    }
                }
                val cls = LiveStateCache.freshness(c, i.long("now"))
                bump("freshness-${cls.name}")
                Observed.Ok(
                    buildJsonObject {
                        put("cls", cls.name)
                        put("regressed", c.regressed)
                        if (i.bool("power")) {
                            val pc = LiveStateCache.powerFreshness(c, i.long("now"))
                            bump("powerFreshness-${pc.name}")
                            put("powerCls", pc.name)
                        }
                        if (i.bool("fields")) {
                            val d = c.doc!!
                            put("fsm", d.fsm.name); put("thermalBand", d.thermalBand); put("queueBucket", d.queueBucket); put("governor", d.governor.name); put("seq", d.seq)
                        }
                    },
                )
            }
            "stParse" -> {
                bump("stParse")
                val d = StDigestParser.parse(i.str("text").toByteArray()) ?: return Observed.Reject("REFUSED")
                Observed.Ok(buildJsonObject { put("seq", d.seq); put("fsm", d.fsm.name); put("tb", d.tb); put("gov", d.gov.name); put("qb", d.qb) })
            }
            "declineBackoff" -> {
                bump("declineBackoff")
                Observed.Ok(buildJsonObject { put("until", DeclineBackoff.until(i.long("now"), i.long("retryAfterMs"))) })
            }
            "breaker" -> {
                bump("breaker")
                val provider = i.str("curve") == "provider"
                val curve = if (provider) BreakerCurve.PROVIDER else BreakerCurve.PEER_TRANSPORT
                var s = BreakerState()
                var t = 0L
                val real = CooldownRegistry(clock = { t })
                for (e in i.arr("events")) {
                    val o = e as JsonObject
                    if (o.str("kind") == "fail") {
                        s = PureBreaker.recordFailure(s, o.long("at"), curve)
                        t = o.long("at")
                        if (provider) real.recordFailure("p")
                    } else {
                        s = PureBreaker.recordSuccess()
                        if (provider) real.recordSuccess("p")
                    }
                }
                val probes = i.arr("probes").map { (it as JsonPrimitive).content.toLong() }
                val out = probes.map { p ->
                    t = p
                    val cooling = PureBreaker.isCooling(s, p)
                    if (provider) {
                        if (real.isCooling("p") != cooling) throw LawViolation("the pure breaker disagrees with the real CooldownRegistry at $p: pure $cooling, real ${real.isCooling("p")}")
                        if (cooling && real.coolingUntil("p") != s.coolingUntilMs) throw LawViolation("the deadline differs from CooldownRegistry: ${s.coolingUntilMs} vs ${real.coolingUntil("p")}")
                        bump("breaker-pinned")
                    }
                    Triple(p, cooling, PureBreaker.halfOpen(s, p))
                }
                Observed.Ok(
                    buildJsonObject {
                        put("failures", s.consecutiveFailures)
                        put("coolingUntil", s.coolingUntilMs)
                        put(
                            "probes",
                            buildJsonArray { out.forEach { (p, c, h) -> add(buildJsonObject { put("at", p); put("cooling", c); put("halfOpen", h) }) } },
                        )
                    },
                )
            }
            else -> throw LawViolation("kind $kind")
        }
    }
}

