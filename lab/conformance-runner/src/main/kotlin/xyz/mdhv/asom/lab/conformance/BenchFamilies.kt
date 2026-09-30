package xyz.mdhv.asom.lab.conformance

import kotlinx.serialization.json.JsonObject
import xyz.mdhv.asom.lab.bench.M04Observation
import xyz.mdhv.asom.lab.bench.M04Vectors
import xyz.mdhv.asom.lab.bench.VectorShapeError
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs

/**
 * M04: the benchmark core (`:bench-core`): statistics, the sustained-phase maths, document derivation and projection, the run-plan interpreter
 * driven against a fake host (executor traces), the governor state machine, consent, and the bench-set pins (LAB_SPEC 5). The semantics of
 * every vector kind live in `M04Vectors` (in `:bench-core`), so the generator and this runner cannot drift apart. Evidence label: LAB;
 * SIMULATED - NOT DEVICE EVIDENCE for the trace vectors; oracle: self.
 */
class M04Checker : FamilyChecker("M04") {
    override val requiredLaws: Set<String> = setOf(
        "test-stat", "sustain", "percentile", "doc-derive", "doc-reject", "trace-completed", "trace-aborted", "trace-refused", "trace-yield", "trace-no-leaks", "plan-sha",
        "pins-defaults-no-l1", "consent", "fsm-edges", "fsm-illegal", "ceilings", "drift-flagged", "outlier-excluded", "unstable", "insufficient", "checked-overflow", "projection-own",
        "projection-file", "trace-sustain-plateau", "trace-hard-ceiling",
    )
    override val requiredIds: Set<String> = (1..10).map { "M04-%03d".format(it) }.toSet()

    private fun asJson(v: JValue) = parseJson(String(Jcs.serialize(v), Charsets.UTF_8))

    override fun observe(v: Vector): Observed {
        val o = try {
            M04Vectors.observe(v.input.toJValue())
        } catch (e: VectorShapeError) {
            throw LawViolation("malformed M04 vector: ${e.message}")
        } catch (e: IllegalStateException) {
            throw LawViolation(e.message ?: "law violation")
        }
        o.laws.forEach { bump(it) }
        return when (o) {
            is M04Observation.Ok -> Observed.Ok(asJson(o.value))
            is M04Observation.Reject -> Observed.Reject(o.code, o.detail?.let { asJson(it) as JsonObject })
        }
    }
}
