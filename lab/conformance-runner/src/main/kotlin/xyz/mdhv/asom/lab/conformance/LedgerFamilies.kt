package xyz.mdhv.asom.lab.conformance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.lab.ledger.CrashInjectingSink
import xyz.mdhv.asom.lab.ledger.FailMode
import xyz.mdhv.asom.lab.ledger.FrameKind
import xyz.mdhv.asom.lab.ledger.FrameSpec
import xyz.mdhv.asom.lab.ledger.IfaceKind
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.MemorySink
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.ServedRecord
import xyz.mdhv.asom.lab.ledger.SessionMode
import xyz.mdhv.asom.lab.ledger.laws.LedgerLaws
import xyz.mdhv.asom.lab.ledger.sim.AttemptPlan
import xyz.mdhv.asom.lab.ledger.sim.Built
import xyz.mdhv.asom.lab.ledger.sim.CloseStep
import xyz.mdhv.asom.lab.ledger.sim.DialStep
import xyz.mdhv.asom.lab.ledger.sim.FinishStep
import xyz.mdhv.asom.lab.ledger.sim.FrameStep
import xyz.mdhv.asom.lab.ledger.sim.InboundRefusedStep
import xyz.mdhv.asom.lab.ledger.sim.RequestStep
import xyz.mdhv.asom.lab.ledger.sim.Side
import xyz.mdhv.asom.lab.ledger.sim.SimConfig
import xyz.mdhv.asom.lab.ledger.sim.SimWorld
import xyz.mdhv.asom.lab.ledger.sim.Step
import xyz.mdhv.asom.lab.policy.Dest
import xyz.mdhv.asom.lab.policy.DestinationSets
import xyz.mdhv.asom.lab.policy.Eligibility
import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.PeerEligibility
import xyz.mdhv.asom.lab.policy.PeerRegistryView
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.policy.PolicyInputs
import xyz.mdhv.asom.lab.policy.Quiescence
import xyz.mdhv.asom.lab.policy.QuiescenceInputs
import xyz.mdhv.asom.lab.policy.Role

/**
 * L01: destination sets `P`, peer eligibility and the quiescence law (LAB_SPEC 6.2, 6.3 F1-F3, design 8.6; `:mesh-policy`).
 * Evidence label: LAB, oracle: self.
 */
class L01Checker : FamilyChecker("L01") {
    override val requiredLaws = setOf(
        "destination-set", "p-empty", "p-fallback", "app-default", "eligibility-eligible", "eligibility-F1_ELIGIBILITY", "eligibility-F2_NOT_PAIRED",
        "eligibility-F3_NO_SCOPE", "quiescence-requester", "quiescence-lender", "inbound", "session-close",
    )

    private fun dests(a: JsonArray): Set<Dest> = a.map { Dest.valueOf((it as JsonPrimitive).content) }.toSet()

    override fun observe(v: Vector): Observed {
        val i = v.input
        return when (val kind = i.str("kind")) {
            "destinationSet" -> {
                val fb = i.arrOrNull("fallback")?.map { (it as JsonPrimitive).content }
                val d = DestinationSets.compute(
                    PolicyInputs(i.bool("meshGlobal"), i.bool("appMesh"), i.bool("cloudBan"), i.bool("deviceOnly"), i.bool("localOnly"), fb, i.bool("noTrain")),
                )
                bump("destination-set")
                if (d.dests.isEmpty()) bump("p-empty")
                if (d.cloudRestrictedTo != null) bump("p-fallback")
                Observed.Ok(
                    buildJsonObject {
                        put("P", jsonStrings(d.wire))
                        put("class", jsonStringOrNull(DestinationSets.namedClass(d.dests)))
                        put("cloudRestrictedTo", d.cloudRestrictedTo?.let { jsonStrings(it) } ?: JsonNull)
                    },
                )
            }
            "appDefault" -> {
                val ticked = (i["userTicked"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toBooleanStrict()
                bump("app-default")
                Observed.Ok(buildJsonObject { put("meshAllowed", DestinationSets.defaultAppMeshAllowed(i.bool("pairedBeforeSwitch"), i.bool("cloudBanned"), ticked)) })
            }
            "eligibility" -> {
                val e = PeerEligibility.evaluate(dests(i.arr("P")), PeerRegistryView(PeerStatus.valueOf(i.str("status")), i.bool("routeEnabled"), i.bool("inferGranted")))
                when (e) {
                    Eligibility.Eligible -> {
                        bump("eligibility-eligible")
                        Observed.Ok(buildJsonObject { put("eligible", true) })
                    }
                    is Eligibility.Excluded -> {
                        bump("eligibility-${e.code}")
                        Observed.Reject(e.code)
                    }
                }
            }
            "quiescence" -> {
                val role = Role.valueOf(i.str("role"))
                bump(if (role == Role.REQUESTER) "quiescence-requester" else "quiescence-lender")
                Observed.Ok(
                    buildJsonObject {
                        put("mayInitiate", Quiescence.mayInitiate(QuiescenceInputs(role, i.bool("pending"), i.bool("screen"), i.bool("userOp"), i.bool("finishing"))))
                    },
                )
            }
            "inbound" -> {
                bump("inbound")
                Observed.Ok(buildJsonObject { put("mayAccept", Quiescence.mayAcceptInbound(Fsm.valueOf(i.str("fsm")), i.bool("pairingWindow"), i.bool("peersTab"))) })
            }
            "sessionClose" -> {
                bump("session-close")
                Observed.Ok(buildJsonObject { put("close", Quiescence.sessionShouldClose(i.long("idleMs"), i.long("ageMs"), i.long("openStreams").toInt())) })
            }
            else -> throw LawViolation("unknown L01 kind '$kind'")
        }
    }
}

/**
 * L02: which frame or event produces which ledger rows (LAB_SPEC 7.6), driven through the real `:ledger-model` session and attempt classes over the
 * in-memory model transport. Besides pinning the rows, every vector enforces laws L-L1..L-L4, L-L9, L-L10, L-L13, L-L14, L-L15 and L-L16 over the run's trace.
 * Evidence label: LAB (a MODEL of TLS record accounting, not a TLS stack), oracle: self.
 */
class L02Checker : FamilyChecker("L02") {
    override val requiredLaws = setOf("rows", "L-L1", "L-L2", "L-L3", "L-L4", "L-L5", "L-L5b", "L-L9", "L-L10", "L-L13", "L-L14", "L-L15", "L-L16", "estimated-basis", "sessions-grouped")

    override fun observe(v: Vector): Observed {
        val i = v.input
        val failSpec = i.objOrNull("fail")
        val cfg = SimConfig(
            seed = i.long("seed"), measured = i.bool("measured", true),
            sinkFor = { name ->
                if (failSpec != null && failSpec.str("node") == name) {
                    CrashInjectingSink(MemorySink(), setOf(failSpec.long("at").toInt()), FailMode.valueOf(failSpec.strOrNull("mode") ?: "BEFORE_WRITE"), failSpec.bool("sticky"))
                } else {
                    MemorySink()
                }
            },
        )
        val w = SimWorld(cfg)
        w.run(i.arr("steps").map { step(it as JsonObject) })
        val t = w.trace.events
        fun law(r: xyz.mdhv.asom.lab.ledger.laws.LawResult, key: String = r.law) {
            if (r.violations.isNotEmpty()) throw LawViolation("${r.law}: ${r.violations.first()}")
            bump(key, r.cases)
        }
        law(LedgerLaws.l1(t)); law(LedgerLaws.l2(t)); law(LedgerLaws.l3(t)); law(LedgerLaws.l4(t)); law(LedgerLaws.l5(t)); law(LedgerLaws.l5b(t)); law(LedgerLaws.l9(t))
        law(LedgerLaws.l10(t)); law(LedgerLaws.l13(t)); law(LedgerLaws.l14(t))
        val l15 = LedgerLaws.l15(t)
        if (l15.mismatches > 0) throw LawViolation("L-L15: ${l15.result.violations.first()}")
        bump("L-L15", l15.measuredSessions)
        if (l15.estimatedSessions > 0) bump("estimated-basis", l15.estimatedSessions)
        if (failSpec == null) law(LedgerLaws.l16(t).result)
        val show = i.arrOrNull("show")?.map { (it as JsonPrimitive).content } ?: listOf("A", "B", "abort", "groups")
        bump("rows")
        return Observed.Ok(
            buildJsonObject {
                val only = i.arrOrNull("onlyKinds")?.map { (it as JsonPrimitive).content }?.toSet()
                for (node in listOf("A", "B")) if (node in show) put(node, rowsJson(w.rows(node), only))
                if ("abort" in show) put("abort", jsonStringOrNull(w.abortReason))
                if ("groups" in show) {
                    put("groups", buildJsonObject { for (node in listOf("A", "B")) put(node, w.rows(node).mapNotNull { it.sessionId }.toSet().size) })
                    bump("sessions-grouped")
                }
            },
        )
    }

    private fun rowsJson(rows: List<LabRouteRecord>, only: Set<String>?): JsonArray {
        val index = LinkedHashMap<String, Int>()
        rows.forEach { r -> r.sessionId?.let { index.getOrPut(it) { index.size + 1 } } }
        return buildJsonArray {
            for (r in rows) {
                val kind = r.meshKind?.wire ?: if (r.terminal == true) "terminal" else "request"
                if (only != null && kind !in only) continue
                add(
                    buildJsonObject {
                        put("k", kind)
                        put("p", jsonStringOrNull(r.phase?.wire))
                        put("c", jsonStringOrNull(r.meshCode))
                        put("o", r.bytesOut)
                        put("i", jsonLongOrNull(r.bytesIn))
                        put("st", r.status)
                        put("s", jsonLongOrNull(r.sessionId?.let { index.getValue(it).toLong() }))
                        if (r.meshKind == MeshKind.INFER_SENT || r.meshKind == MeshKind.INFER_SERVED) put("a", r.attemptId)
                        if (r.overheadBasis != null) put("ob", r.overheadBasis!!.wire)
                        if (r.terminal == true) {
                            put("egress", r.egress.wire)
                            put("servedClass", jsonStringOrNull(r.servedClass?.wire))
                            put("header", r.toEchoHeaders()[xyz.mdhv.asom.contract.AsomHeaders.EGRESS])
                        }
                    },
                )
            }
        }
    }

    private fun step(o: JsonObject): Step = when (val op = o.str("op")) {
        "dial" -> DialStep(
            mode = if (o.strOrNull("mode") == "pairing") SessionMode.PAIRING else SessionMode.ESTABLISHED, outcome = o.strOrNull("outcome") ?: "connected",
            source = o.strOrNull("source") ?: "qr", iface = IfaceKind.valueOf((o.strOrNull("iface") ?: "wifi").uppercase()), hostile = o.bool("hostile"),
        )
        "frame" -> {
            val served = o.objOrNull("served")?.let { ServedRecord(it.str("model"), it.long("status").toInt(), it.str("terminal"), it.long("tokensIn")) }
            val kind = FrameKind.valueOf(o.str("kind"))
            val frame = FrameSpec(kind, o.long("payload").toInt(), (o.strOrNull("stream") ?: "1").toInt(), o.strOrNull("attempt"), o.strOrNull("code"))
            FrameStep(Side.valueOf(o.str("from")), Built(frame, null), o.strOrNull("request"), served, o.strOrNull("model") ?: "qwen3-8b")
        }
        "finish" -> FinishStep(o.str("attempt"), o.long("status").toInt(), o.strOrNull("meshCode"), o["tokensOut"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() })
        "close" -> CloseStep(Side.valueOf(o.str("by")))
        "refused" -> InboundRefusedStep(o.long("count").toInt())
        "request" -> RequestStep(o.str("id"), o.str("model"), o.strList("attempts").map { plan(it) })
        else -> throw LawViolation("unknown L02 step '$op'")
    }

    private fun plan(s: String): AttemptPlan {
        val p = s.split(':')
        return when (p[0]) {
            "peer-decline" -> AttemptPlan.PeerDecline(p[1])
            "peer-served" -> AttemptPlan.PeerServed(p[1], p[2].toInt(), p[3].toInt())
            "peer-cancelled" -> AttemptPlan.PeerCancelled(p[1].toInt())
            "cloud-fail" -> AttemptPlan.CloudFail
            "cloud-ok" -> AttemptPlan.CloudOk
            "local-ok" -> AttemptPlan.LocalOk
            else -> throw LawViolation("unknown attempt plan '$s'")
        }
    }
}
