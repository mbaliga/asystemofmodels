package xyz.mdhv.asom.lab.proto.trust

import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue

/** What the implementation did with one vector: an accepted value, or a typed refusal. */
sealed interface VectorOutcome {
    class Ok(val value: JValue) : VectorOutcome
    class Reject(val code: String) : VectorOutcome
}

/** An outcome and the names of the laws the vector exercised (each counted only after the evaluator's own assertions held). */
class VectorEval(val outcome: VectorOutcome, val laws: List<String>)

/** A vector disagreed with an invariant the evaluator itself checks. The runner turns it into a failed vector. */
class VectorLawViolation(message: String) : Exception(message)

/** A vector file is malformed for the evaluator (a missing member, a wrong type). */
class VectorShapeException(message: String) : Exception(message)

fun jobj(vararg members: Pair<String, JValue>): JObject = JObject(members.toList())

fun jstr(s: String): JValue = JString(s)

fun jlist(items: List<String>): JValue = JArray(items.map { JString(it) })

object V {
    fun str(o: JObject, key: String): String = (o[key] as? JString)?.value ?: throw VectorShapeException("member '$key' is missing or not a string")

    fun strOrNull(o: JObject, key: String): String? = (o[key] as? JString)?.value

    fun long(o: JObject, key: String): Long = (o[key] as? JInt)?.value ?: throw VectorShapeException("member '$key' is missing or not an integer")

    fun bool(o: JObject, key: String): Boolean = (o[key] as? JBool)?.value ?: throw VectorShapeException("member '$key' is missing or not a boolean")

    fun obj(o: JObject, key: String): JObject = o[key] as? JObject ?: throw VectorShapeException("member '$key' is missing or not an object")

    fun arr(o: JObject, key: String): List<JValue> = (o[key] as? JArray)?.items ?: throw VectorShapeException("member '$key' is missing or not an array")

    fun strings(o: JObject, key: String): List<String> = arr(o, key).map { (it as? JString)?.value ?: throw VectorShapeException("'$key' holds a non-string") }

    fun hex(o: JObject, key: String): ByteArray = try {
        Hex.decode(str(o, key))
    } catch (e: IllegalArgumentException) {
        throw VectorShapeException("member '$key' is not lower-case hex")
    }
}
