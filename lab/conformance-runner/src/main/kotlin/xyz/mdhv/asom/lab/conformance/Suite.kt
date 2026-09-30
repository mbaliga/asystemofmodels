package xyz.mdhv.asom.lab.conformance

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The checker for a family, or null when this build has no module that can run it (LAB_SPEC 3.3). */
fun checkerFor(family: String): FamilyChecker? = when (family) {
    "W00" -> W00Checker()
    "W01" -> W01Checker()
    "W01b" -> W01bChecker()
    "W02" -> W02Checker()
    "W03" -> W03Checker()
    "R04" -> R04Checker()
    "M01" -> M01Checker()
    "M02" -> ManifestChecker("M02")
    "M03" -> ManifestChecker("M03")
    "M04" -> M04Checker()
    "M05" -> M05Checker()
    "M06" -> M06Checker()
    else -> null
}

class FamilyResult(
    val family: String,
    val checker: FamilyChecker?,
    val results: List<Pair<Vector, Outcome>>,
) {
    val implemented: Boolean get() = checker != null
    private val normative get() = results.filter { it.first.normative }
    private val proposed get() = results.filter { it.first.proposed }

    val normativeCount: Int get() = normative.size
    val pass: Int get() = normative.count { it.second is Outcome.Pass }
    val fail: Int get() = normative.count { it.second is Outcome.Fail }
    val proposedSkipped: Int get() = proposed.count { it.second is Outcome.Skipped }
    val proposedRun: Int get() = proposed.count { it.second !is Outcome.Skipped }
    val proposedPass: Int get() = proposed.count { it.second is Outcome.Pass }
    val proposedFail: Int get() = proposed.count { it.second is Outcome.Fail }
    val illustrative: Int get() = results.count { it.first.status == "illustrative" }

    /** Non-vacuity problems: an empty family, or a required law with no cases (LAB_SPEC R10). */
    fun vacuity(): List<String> {
        val c = checker ?: return emptyList()
        val out = mutableListOf<String>()
        if (normativeCount == 0) out += "family $family has zero normative vectors"
        c.requiredLaws.filter { (c.laws[it] ?: 0) == 0 }.forEach { out += "family $family: law '$it' exercised zero cases" }
        val present = results.map { it.first.id }.toSet()
        c.requiredIds.filter { it !in present }.forEach { out += "family $family: required vector id $it is missing" }
        return out
    }

    fun oracleTags(): String =
        results.map { it.first.oracle }.groupingBy { it }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    fun line(): String {
        if (!implemented) return "family $family: not-implemented"
        val proposedLane = if (proposedRun > 0) ", proposed-lane $proposedRun run: $proposedPass pass, $proposedFail fail" else ""
        return "family $family: $normativeCount vectors, $pass pass, $fail fail, $proposedSkipped proposed-skipped$proposedLane, oracle: ${oracleTags()}"
    }

    fun lawLines(): List<String> = checker?.laws?.map { "  law $family/${it.key}: ${it.value} cases" }.orEmpty()
}

class SuiteReport(val loaded: Loaded, val families: List<FamilyResult>) {
    val ok: Boolean
        get() = loaded.problems.isEmpty() &&
            families.all { it.vacuity().isEmpty() && it.fail == 0 } &&
            IndexCheck.problems().isEmpty()

    fun render(): String = buildString {
        families.forEach { f ->
            appendLine(f.line())
            f.lawLines().forEach { appendLine(it) }
            f.vacuity().forEach { appendLine("  VACUOUS: $it") }
            f.results.filter { it.second is Outcome.Fail }.forEach { (v, o) ->
                appendLine("  FAIL ${v.id}: ${(o as Outcome.Fail).why}")
            }
            f.results.filter { it.first.proposed && it.second is Outcome.Fail }.take(3).forEach { (v, o) ->
                appendLine("  proposed-lane finding ${v.id}: ${(o as Outcome.Fail).why}")
            }
        }
        loaded.problems.forEach { appendLine("ENVELOPE PROBLEM: $it") }
        IndexCheck.problems().forEach { appendLine("INDEX PROBLEM: $it") }
    }
}

object Suite {
    fun run(families: List<String> = ALL_FAMILIES): SuiteReport {
        val loaded = VectorLoader.loadAll()
        val results = families.map { fam ->
            val checker = checkerFor(fam)
            val vectors = loaded.forFamily(fam)
            val rs = if (checker == null) emptyList() else vectors.map { it to checker.check(it) }
            FamilyResult(fam, checker, rs)
        }
        return SuiteReport(loaded, results)
    }
}

/** `INDEX.json` detects an incomplete checkout; it authenticates nothing (LAB_SPEC 3.2). */
object IndexCheck {
    fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    fun problems(): List<String> {
        val root = Repo.conformance
        val index = File(root, "INDEX.json")
        if (!index.isFile) return listOf("INDEX.json is missing")
        val entries = (parseJson(index.readUtf8()) as? JsonArray) ?: return listOf("INDEX.json is not an array")
        val out = mutableListOf<String>()
        val paths = entries.map { (it as JsonObject).str("path") }
        if (paths != paths.sorted()) out += "INDEX.json is not sorted by path"
        if (paths.size != paths.toSet().size) out += "INDEX.json has duplicate paths"
        for (e in entries) {
            val o = e as JsonObject
            val f = File(root, o.str("path"))
            if (!f.isFile) out += "${o.str("path")} is listed but missing"
            else if (sha256(f) != o.str("sha256")) out += "${o.str("path")} sha256 differs from INDEX.json"
            if (o.strOrNull("family") == null || o.strOrNull("status") == null) out += "${o.str("path")} lacks family or status"
        }
        val listed = paths.toSet()
        for (f in VectorLoader.vectorFilePaths(root)) {
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel !in listed) out += "$rel is a vector file but is not listed in INDEX.json"
        }
        val keys = File(root, "keys/TEST-ONLY-keys.json")
        if (!keys.isFile) out += "keys/TEST-ONLY-keys.json is missing"
        else if (!(parseJson(keys.readUtf8()) as JsonObject).bool("TEST_ONLY")) out += "keys/TEST-ONLY-keys.json is not marked TEST_ONLY"
        return out
    }
}
