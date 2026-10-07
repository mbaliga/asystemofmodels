package xyz.mdhv.asom.lab.bench

import java.security.MessageDigest
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs

/** A vector input that does not have the shape its kind requires (a defect of the vector, not of the implementation). */
class VectorShapeError(message: String) : Exception(message)

/** What the benchmark core did with an M04 vector's input. [laws] names the cases that were exercised (non-vacuity, LAB_SPEC R10). */
sealed interface M04Observation {
    val laws: List<String>

    class Ok(val value: JValue, override val laws: List<String>) : M04Observation

    class Reject(val code: String, val detail: JValue?, override val laws: List<String>) : M04Observation
}

/**
 * The semantics of every M04 vector kind, in one place, so that the vector generator and the conformance runner cannot drift apart: kinds
 * `test`, `sustain`, `percentile`, `doc`, `trace`, `plan`, `pins`, `consent`, `fsm` and `ceilings`. Evidence label: LAB, oracle: self.
 */
object M04Vectors {
    private fun JValue.m(name: String): JValue? = (this as? JObject)?.get(name)

    private fun JValue.req(name: String): JValue = m(name) ?: throw VectorShapeError("missing '$name'")

    private fun JValue.s(): String = (this as? JString)?.value ?: throw VectorShapeError("not a string")

    private fun JValue.l(): Long = (this as? JInt)?.value ?: throw VectorShapeError("not an integer")

    private fun JValue.b(): Boolean = (this as? JBool)?.value ?: throw VectorShapeError("not a boolean")

    private fun JValue.arr(): List<JValue> = (this as? JArray)?.items ?: throw VectorShapeError("not an array")

    private fun JValue.longs(): List<Long> = arr().map { it.l() }

    private fun JValue.strs(): List<String> = arr().map { it.s() }

    private fun JValue.lOrNull(): Long? = if (this is JNull) null else l()

    private fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun strList(l: List<String>): JValue = jsList(l)

    fun observe(input: JValue): M04Observation = try {
        when (val kind = input.req("kind").s()) {
            "test" -> test(input)
            "sustain" -> sustain(input)
            "percentile" -> M04Observation.Ok(jo("value" to ji(Stats.nearestRank(input.req("values").longs(), input.req("p").l()))), listOf("percentile"))
            "doc" -> doc(input)
            "trace" -> trace(input)
            "plan" -> plan(input)
            "pins" -> pins()
            "consent" -> consent(input)
            "fsm" -> fsm()
            "ceilings" -> ceilings(input)
            else -> throw VectorShapeError("unknown M04 kind '$kind'")
        }
    } catch (e: BenchArithmeticException) {
        M04Observation.Reject("INCONSISTENT", null, listOf("checked-overflow"))
    }

    private fun test(input: JValue): M04Observation {
        val spec = TestSpec.parse(input.req("spec").s()) ?: throw VectorShapeError("bad test name")
        val c = input.m("ctx")
        val ctx = Derive.TestContext(
            warmStart = c?.m("warmStart")?.b() ?: false, virtualized = c?.m("virtualized")?.b() ?: false, streamTiming = c?.m("streamTiming")?.b() ?: false,
            contentionPermille = c?.m("contentionPermille")?.l() ?: 0L, restarted = c?.m("restarted")?.b() ?: false, swapped = c?.m("swapped")?.b() ?: false,
        )
        val r = Derive.deriveTest(spec, input.req("samples").longs(), input.m("whole")?.longs(), ctx)
        val laws = mutableListOf("test-stat")
        if ("THERMAL_DRIFT" in r.stat.flags) laws += "drift-flagged"
        if ("OUTLIER_EXCLUDED" in r.stat.flags) laws += "outlier-excluded"
        if ("UNSTABLE" in r.stat.flags) laws += "unstable"
        if (r.confidence == Confidence.INSUFFICIENT) laws += "insufficient"
        val m = mutableListOf<Pair<String, JValue>>(
            "value" to jiOrNull(r.value), "kept" to ji(r.stat.kept), "keptIdx" to ja(r.stat.keptIdx.map { ji(it) }),
            "relSpreadPermille" to ji(r.stat.relSpreadPermille), "confidence" to js(r.confidence.wire), "flags" to strList(r.stat.flags),
        )
        r.ttftMicros?.let { m += "ttftMicros" to ji(it) }
        return M04Observation.Ok(jo(m), laws)
    }

    private fun sustain(input: JValue): M04Observation {
        val windows = input.req("windows").arr().map { w -> w.longs().let { BWindow(it[0], it[1], it[2], it[3].toInt()) } }
        val s = BSustain(
            input.req("tier").s(), input.req("windowMs").l(), input.req("capMs").l(), input.req("endReason").s(),
            input.m("headroomAtOnsetPermille")?.lOrNull(), windows,
        )
        val d = Derive.deriveSustainStandalone(s, input.req("startThermal").s())
        return M04Observation.Ok(sustainShape(d), listOf("sustain"))
    }

    private fun sustainShape(d: SustainDerived): JValue = jo(
        "peakMtps" to ji(d.peakMtps), "plateauMtps" to ji(d.plateauMtps), "onsetMs" to jiOrNull(d.onsetMs), "stabilityPermille" to ji(d.stabilityPermille),
        "durationMs" to ji(d.durationMs), "thermalCodeAtOnset" to jiOrNull(d.thermalCodeAtOnset?.toLong()), "headroomAtOnsetPermille" to jiOrNull(d.headroomAtOnsetPermille),
        "confidence" to js(d.confidence.wire), "flags" to strList(d.flags),
    )

    fun answersShape(d: Derived): JValue {
        val a = d.answers
        return jo(
            "usableMemoryBytes" to ji(a.usableMemoryBytes), "safetyPermille" to ji(a.safetyPermille),
            "maxHold" to (a.maxHold?.let { jo("weightBytes" to ji(it.weightBytes), "kvRatioPermille" to ji(it.kvRatioPermille), "approxParamsQ4" to ji(it.approxParamsQ4), "largestLoadedTier" to js(it.largestLoadedTier)) } ?: JNull),
            "q7b" to jo("basis" to js(a.q7b.basis), "decodeMtps" to jiOrNull(a.q7b.decodeMtps), "ttft512Micros" to jiOrNull(a.q7b.ttft512Micros), "verdict" to js(a.q7b.verdict)),
            "answer2000" to (a.answer2000?.let { jo("tier" to js(it.tier), "depthRatioPermille" to ji(it.depthRatioPermille), "micros" to ji(it.micros), "thermalModel" to jb(it.thermalModel)) } ?: JNull),
            "throttle" to (a.throttle?.let { jo("tier" to js(it.tier), "onsetMs" to jiOrNull(it.onsetMs), "stabilityPermille" to ji(it.stabilityPermille), "testedMs" to ji(it.testedMs)) } ?: JNull),
            "role" to jo("code" to js(a.role.code), "t2PlateauMtps" to jiOrNull(a.role.t2PlateauMtps), "t3PlateauMtps" to jiOrNull(a.role.t3PlateauMtps)),
            "overallConfidence" to js(a.overallConfidence.wire),
            "tiers" to ja(
                d.tiers.map { t ->
                    jo(
                        "tier" to js(t.tier), "numerics" to js(t.numerics.verdict), "loadWarmMicros" to ji(t.loadWarmMicros), "flags" to strList(t.flags),
                        "tests" to ja(t.results.map { r -> jo("test" to js(r.name), "value" to jiOrNull(r.value), "kept" to ji(r.stat.kept), "confidence" to js(r.confidence.wire), "flags" to strList(r.stat.flags)) }),
                    )
                },
            ),
            "sustain" to (d.sustain?.let { sustainShape(it) } ?: JNull),
        )
    }

    private fun doc(input: JValue): M04Observation {
        val doc = try {
            BenchCodec.decode(input.req("benchDoc"), RdContext(false))
        } catch (e: SchemaViolation) {
            return M04Observation.Reject("SCHEMA_INVALID", null, listOf("doc-reject"))
        }
        val d = Derive.derive(doc)
        val audience = if (input.m("audience")?.s() == "file") Audience.FILE else Audience.OWN
        val results = Project.results(d, audience)
        val laws = listOf("doc-derive", if (audience == Audience.FILE) "projection-file" else "projection-own")
        val out = jo(
            "answers" to answersShape(d), "resultCount" to ji(results.size), "resultsSha256" to js(sha256Hex(Jcs.serialize(ja(results)))),
            "textSha256" to js(sha256Hex(TextRender.render(d, RenderOptions(false)).toByteArray(Charsets.US_ASCII))),
            "textSha256Mesh" to js(sha256Hex(TextRender.render(d, RenderOptions(true)).toByteArray(Charsets.US_ASCII))),
        )
        return M04Observation.Ok(out, laws)
    }

    private fun trace(input: JValue): M04Observation {
        val r = Scenarios.run(input.req("scenario"))
        check(r.leaks.isEmpty()) { "resources still held after the run: ${r.leaks}" }
        val laws = mutableListOf("trace-no-leaks")
        when {
            r.outcome == "completed" -> laws += "trace-completed"
            r.outcome.startsWith("aborted") -> laws += "trace-aborted"
            r.outcome.startsWith("refused") -> laws += "trace-refused"
        }
        if (r.events.any { it.startsWith("YIELD") }) laws += "trace-yield"
        if (r.events.any { it.startsWith("SUSTAIN end reason=PLATEAU") }) laws += "trace-sustain-plateau"
        if (r.doc?.sustain?.endReason == "THERMAL_HARD") laws += "trace-hard-ceiling"
        val out = jo("outcome" to js(r.outcome), "docSha256" to jsOrNull(r.docSha256), "events" to strList(r.events))
        return M04Observation.Ok(out, laws)
    }

    private fun plan(input: JValue): M04Observation {
        val p = RunPlans.byId(input.req("plan").s()) ?: throw VectorShapeError("unknown plan")
        return M04Observation.Ok(jo("planSha256" to js(p.sha256B64u()), "jcsBytes" to ji(p.jcsBytes().size)), listOf("plan-sha"))
    }

    private fun pins(): M04Observation {
        val defaults = BenchSets.defaults()
        val l1Absent = defaults.none { it.id == BenchSets.L1_ID } && defaults.flatMap { it.tiers }.none { it.tier.startsWith("L1") }
        check(l1Absent) { "an L1 pin is among the defaults" }
        val throws = runCatching { BenchSets.withD18Ruling(false) }.isFailure
        val out = jo(
            "defaultSetIds" to strList(defaults.map { it.id }), "l1InDefaults" to jb(!l1Absent), "l1WithoutRulingThrows" to jb(throws),
            "l1HasPins" to jb(BenchSets.L1.tiers.any { it.sha256 != null }), "q1Status" to js(BenchSets.Q1.status.name),
            "q1" to ja(BenchSets.Q1.tiers.map { jo("tier" to js(it.tier), "bytes" to ji(it.bytes!!), "sha256" to js(it.sha256!!)) }),
        )
        return M04Observation.Ok(out, listOf("pins-defaults-no-l1"))
    }

    private fun consent(input: JValue): M04Observation {
        val plan = RunPlans.byId(input.req("plan").s()) ?: throw VectorShapeError("unknown plan")
        val sheet = ConsentSheet.forPlan(plan, input.req("downloadBytes").l(), input.req("tiers").strs(), input.req("t3OptInOffered").b(), input.req("sustainedToday").b())
        return try {
            val shown = if (input.req("confirm").s() == "match") sheet.textSha256 else sheet.textSha256.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val token = sheet.confirm(shown, input.req("mintNowMs").l())
            token.consume(input.m("consumePlan")?.s() ?: plan.plan, input.req("consumeNowMs").l())
            if (input.m("consumeTwice")?.b() == true) token.consume(plan.plan, input.req("consumeNowMs").l())
            M04Observation.Ok(jo("textSha256" to js(sheet.textSha256.joinToString("") { "%02x".format(it) }), "outcome" to js("consumed")), listOf("consent"))
        } catch (e: ConsentError) {
            M04Observation.Reject("CONSENT_REFUSED", jo("why" to js(e.message ?: "")), listOf("consent"))
        }
    }

    private fun fsm(): M04Observation {
        val edges = mutableListOf<String>()
        var illegal = 0
        for (a in GState.entries) for (b in GState.entries) if (b in Governor.EDGES.getValue(a)) edges += "$a>$b" else illegal++
        return M04Observation.Ok(jo("edges" to strList(edges)), List(edges.size) { "fsm-edges" } + List(illegal) { "fsm-illegal" })
    }

    private fun ceilings(input: JValue): M04Observation {
        val t = input.req("thermal")
        val p = input.req("power")
        val pr = input.req("presence")
        val c = Ceilings.evaluate(
            input.req("platform").s(), input.req("form").s(),
            ThermalReading(true, t.req("code").l().toInt(), t.m("headroom")?.lOrNull()?.toInt(), t.m("batteryTempDeciC")?.lOrNull()?.toInt()),
            PowerReading(p.req("source").s(), p.m("level")?.lOrNull()?.toInt(), true),
            Presence(pr.req("foreground").b(), pr.req("screenOn").b(), pr.req("batterySaver").b(), pr.req("lowPowerMode").b()), input.req("gpuBusyHeldMs").l(),
        )
        val text = when (c) {
            is Ceiling.Hard -> "hard ${c.reason}"
            is Ceiling.Soft -> "soft ${c.reason}"
            null -> "none"
        }
        return M04Observation.Ok(jo("ceiling" to js(text)), listOf("ceilings"))
    }
}
