package xyz.mdhv.asom.lab.conformance

import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.proto.pairing.W04Vectors
import xyz.mdhv.asom.lab.proto.trust.VectorEval
import xyz.mdhv.asom.lab.proto.trust.VectorLawViolation
import xyz.mdhv.asom.lab.proto.trust.VectorOutcome
import xyz.mdhv.asom.lab.proto.trust.VectorShapeException
import xyz.mdhv.asom.lab.proto.trust.W05Vectors

/**
 * The vector-to-implementation glue of the trust families lives in `:mesh-proto` (`W05Vectors`, `W04Vectors`) so that the module's own tests and this
 * runner share it. This base only converts: the vector's JSON to the strict value model, the evaluator's outcome to an [Observed], and its law
 * names to this family's non-vacuity counters (a law counts only when the evaluator's own assertions held). Evidence label: LAB, oracle: self.
 */
abstract class ProtoTrustChecker(family: String, private val run: (JObject) -> VectorEval) : FamilyChecker(family) {
    override fun observe(v: Vector): Observed {
        val eval = try {
            run(v.input.toJValue() as JObject)
        } catch (e: VectorLawViolation) {
            throw LawViolation(e.message ?: "law violation")
        } catch (e: VectorShapeException) {
            throw LawViolation("vector shape: ${e.message}")
        }
        eval.laws.forEach { bump(it) }
        return when (val o = eval.outcome) {
            is VectorOutcome.Ok -> Observed.Ok(parseJson(Jcs.serializeToString(o.value)))
            is VectorOutcome.Reject -> Observed.Reject(o.code)
        }
    }
}

/** W05: fingerprints, strict SPKI pins, the two fixed certificate templates and `verifyPeerChain` (trust.md 2.3, 2.4, 3.2, 15; LAB_SPEC 7.4). */
class W05Checker : ProtoTrustChecker("W05", { W05Vectors.evaluate(it) }) {
    override val requiredLaws: Set<String> get() = W05Vectors.requiredLaws
}

/** W04: pairing (trust.md 4.2 to 4.7, 15; LAB_SPEC 7.3): QR grammar, proof, SAS, transcript, messages, the two state machines and the registry. */
class W04Checker : ProtoTrustChecker("W04", { W04Vectors.evaluate(it) }) {
    override val requiredLaws: Set<String> get() = W04Vectors.requiredLaws
}
