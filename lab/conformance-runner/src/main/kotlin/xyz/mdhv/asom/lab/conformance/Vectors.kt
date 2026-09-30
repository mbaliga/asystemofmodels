package xyz.mdhv.asom.lab.conformance

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Every family name LAB_SPEC 3.2 allows in the envelope's `family` field. */
val ALL_FAMILIES: List<String> = listOf(
    "W00", "W01", "W01b", "W02", "W03", "W04", "W05", "W06", "W07", "W07p", "W08",
    "M01", "M02", "M03", "M04", "M05", "M06", "M08",
    "R01", "R02", "R03", "R04", "R05", "R06",
    "L01", "L02",
)

/** The families this runner can execute in the L0.1 build. Every other family is reported `not-implemented`. */
val L01_FAMILIES: List<String> = listOf("W00", "W01", "W01b", "W02", "W03", "R04")

/** Directories of `lab/conformance` that hold vector envelopes (not scenarios, schemas, keys or history). */
val VECTOR_DIRS: List<String> = listOf("wire", "manifest", "router", "ledger", "json")

val STATUSES = setOf("normative", "proposed", "illustrative")
val ORIGINS = setOf("hand", "generated")
val ORACLES = setOf("self", "independent", "external")

class Vector(
    val file: String,
    val family: String,
    val id: String,
    val origin: String,
    val status: String,
    val oracle: String,
    val description: String,
    val input: JsonObject,
    val expectOk: JsonElement?,
    val expectReject: String?,
    /** Optional extra expectations for a reject vector (W02: `httpStatus`, `errorType`). */
    val detail: JsonObject?,
) {
    val normative: Boolean get() = status == "normative"
    val proposed: Boolean get() = status == "proposed"
}

class VectorFile(
    val relPath: String,
    val family: String,
    val confVersion: String,
    val specRefs: List<String>,
    val vectors: List<Vector>,
)

class Loaded(val files: List<VectorFile>, val problems: List<String>) {
    val all: List<Vector> get() = files.flatMap { it.vectors }
    fun forFamily(family: String): List<Vector> = all.filter { it.family == family }.sortedBy { it.id }
}

object VectorLoader {
    private val idPattern = Regex("^([A-Za-z0-9]+)-([0-9]{3}|[A-Za-z]{2,12})$")

    fun vectorFilePaths(root: File = Repo.conformance): List<File> =
        VECTOR_DIRS.flatMap { dir ->
            File(root, dir).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()
        }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }

    fun loadAll(root: File = Repo.conformance): Loaded {
        val version = File(root, "VERSION").takeIf { it.isFile }?.readUtf8()?.trim()
        val problems = mutableListOf<String>()
        if (version == null) problems += "lab/conformance/VERSION is missing"
        val files = vectorFilePaths(root).map { loadFile(root, it, version, problems) }
        val ids = HashSet<String>()
        for (v in files.flatMap { it.vectors }) {
            if (!ids.add(v.id)) problems += "duplicate vector id ${v.id} (second occurrence in ${v.file})"
        }
        return Loaded(files, problems)
    }

    private fun loadFile(root: File, f: File, version: String?, problems: MutableList<String>): VectorFile {
        val rel = f.relativeTo(root).invariantSeparatorsPath
        val doc = parseJson(f.readUtf8()) as? JsonObject
        if (doc == null) {
            problems += "$rel: not a JSON object"
            return VectorFile(rel, "", "", emptyList(), emptyList())
        }
        val family = doc.strOrNull("family") ?: ""
        val conf = doc.strOrNull("confVersion") ?: ""
        if (family !in ALL_FAMILIES) problems += "$rel: family '$family' is not one of the allowed families"
        if (version != null && conf != version) problems += "$rel: confVersion '$conf' != VERSION '$version'"
        val specRefs = doc.strList("specRefs")
        if (specRefs.isEmpty()) problems += "$rel: specRefs is empty"
        val vectors = mutableListOf<Vector>()
        val raw = doc["vectors"] as? kotlinx.serialization.json.JsonArray
        if (raw == null || raw.isEmpty()) problems += "$rel: no vectors"
        raw?.forEachIndexed { i, el ->
            val o = el as? JsonObject
            if (o == null) {
                problems += "$rel: vectors[$i] is not an object"
                return@forEachIndexed
            }
            val id = o.strOrNull("id") ?: "<vectors[$i]>"
            val m = idPattern.matchEntire(id)
            if (m == null) problems += "$rel: id '$id' does not match <family>-<3 digits | 2-12 letters>"
            else if (m.groupValues[1] != family) problems += "$rel: id '$id' does not carry the file's family '$family'"
            val origin = o.strOrNull("origin") ?: ""
            val status = o.strOrNull("status") ?: ""
            val oracle = o.strOrNull("oracle") ?: ""
            if (origin !in ORIGINS) problems += "$id: origin '$origin' invalid"
            if (status !in STATUSES) problems += "$id: status '$status' invalid"
            if (oracle !in ORACLES) problems += "$id: oracle '$oracle' invalid (every vector must carry an oracle tag)"
            val expect = o["expect"] as? JsonObject
            var ok: JsonElement? = null
            var reject: String? = null
            if (expect == null || expect.keys != setOf("ok") && expect.keys != setOf("reject")) {
                problems += "$id: expect must be exactly one of {\"ok\": ...} or {\"reject\": \"CODE\"}"
            } else if ("ok" in expect) {
                ok = expect["ok"]
            } else {
                val r = expect["reject"]
                if (r is JsonPrimitive && r !is JsonNull && r.isString) reject = r.content
                else problems += "$id: reject code must be a string"
            }
            val input = o["input"] as? JsonObject ?: JsonObject(emptyMap()).also { problems += "$id: input must be an object" }
            vectors += Vector(
                file = rel, family = family, id = id, origin = origin, status = status, oracle = oracle,
                description = o.strOrNull("description") ?: "", input = input,
                expectOk = ok, expectReject = reject, detail = o["expectDetail"] as? JsonObject,
            )
        }
        return VectorFile(rel, family, conf, specRefs, vectors)
    }
}
