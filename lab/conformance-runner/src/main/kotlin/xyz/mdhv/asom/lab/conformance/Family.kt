package xyz.mdhv.asom.lab.conformance

import java.util.TreeMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What the implementation under test did with a vector's input. */
sealed interface Observed {
    data class Ok(val value: JsonElement) : Observed
    data class Reject(val code: String, val detail: JsonObject? = null) : Observed
}

sealed interface Outcome {
    data object Pass : Outcome
    data class Fail(val why: String) : Outcome
    data class Skipped(val why: String) : Outcome
}

/**
 * One vector family. [observe] runs the real implementation and enforces the family's laws (throwing
 * [LawViolation]); [check] compares the observation with the vector's expectation. [laws] counts the cases
 * that exercised each named law; a required law with a zero count fails the family (LAB_SPEC R10).
 */
abstract class FamilyChecker(val family: String) {
    val laws: MutableMap<String, Int> = TreeMap()

    /** Laws that must have exercised at least one case once the whole family has run. */
    abstract val requiredLaws: Set<String>

    /** Vector ids that must be present in the loaded files; a missing id fails the family (LAB_SPEC 8.1 L0.2: "the runner fails on a missing id"). */
    open val requiredIds: Set<String> get() = emptySet()

    abstract fun observe(v: Vector): Observed

    protected fun bump(law: String, by: Int = 1) {
        laws[law] = (laws[law] ?: 0) + by
    }

    fun check(v: Vector): Outcome {
        // Only normative vectors count towards non-vacuity: a proposed case cannot mask an unexercised law.
        val before = if (v.normative) null else LinkedHashMap(laws)
        try {
            return checkCounted(v)
        } finally {
            if (before != null) {
                laws.clear()
                laws.putAll(before)
            }
        }
    }

    private fun checkCounted(v: Vector): Outcome {
        val obs = try {
            observe(v)
        } catch (e: NotRunnable) {
            return if (v.proposed) Outcome.Skipped(e.message ?: "not runnable") else Outcome.Fail("not runnable: ${e.message}")
        } catch (e: LawViolation) {
            return Outcome.Fail(e.message ?: "law violation")
        } catch (e: Exception) {
            return Outcome.Fail("${e::class.simpleName}: ${e.message}")
        }
        return compare(v, obs)
    }

    /** The lines-mode verdict: the implementation's own answer, never pass/fail. Null when the vector cannot run. */
    fun verdict(v: Vector): String? {
        val obs = try {
            observe(v)
        } catch (e: NotRunnable) {
            return null
        } catch (e: Exception) {
            return "${v.id} error ${e::class.simpleName}"
        }
        return when (obs) {
            is Observed.Ok -> "${v.id} ok"
            is Observed.Reject -> "${v.id} reject ${obs.code}"
        }
    }

    private fun compare(v: Vector, obs: Observed): Outcome = when (obs) {
        is Observed.Ok ->
            if (v.expectOk == null) Outcome.Fail("expected reject ${v.expectReject}, implementation accepted")
            else if (obs.value == v.expectOk) Outcome.Pass
            else Outcome.Fail("expected ${v.expectOk} but observed ${obs.value}")
        is Observed.Reject ->
            if (v.expectReject == null) Outcome.Fail("expected ok, implementation rejected with ${obs.code}")
            else if (obs.code != v.expectReject) Outcome.Fail("expected reject ${v.expectReject} but observed ${obs.code}")
            else {
                val want = v.detail
                if (want != null && obs.detail != want) Outcome.Fail("expected detail $want but observed ${obs.detail}")
                else Outcome.Pass
            }
    }
}
