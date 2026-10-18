package xyz.mdhv.asom.lab.conformance

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A vector failed a law or an expectation. Carries the reason shown in the report. */
class LawViolation(message: String) : Exception(message)

/** A vector cannot run in this build (its module is not built yet). Skipped only when the vector is proposed. */
class NotRunnable(message: String) : Exception(message)

object Repo {
    val root: File by lazy {
        val p = System.getProperty("asom.repoRoot")
            ?: error("system property asom.repoRoot is not set (run through Gradle: ./gradlew -p lab ...)")
        File(p).canonicalFile
    }
    val conformance: File get() = File(root, "lab/conformance")
    val fixtureCatalogue: File get() = File(root, "fixtures/catalogue.v1.json")
}

fun File.readUtf8(): String = readText(Charsets.UTF_8)

fun File.writeUtf8(text: String) = writeText(text, Charsets.UTF_8)

fun parseJson(text: String): JsonElement = Json.parseToJsonElement(text)

fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw LawViolation("vector field '$key' is missing or not a string")

fun JsonObject.strOrNull(key: String): String? {
    val v = this[key] ?: return null
    if (v is JsonNull) return null
    return (v as? JsonPrimitive)?.content
}

fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
        ?: throw LawViolation("vector field '$key' is missing or not an integer")

fun JsonObject.obj(key: String): JsonObject =
    this[key] as? JsonObject ?: throw LawViolation("vector field '$key' is missing or not an object")

fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray =
    this[key] as? JsonArray ?: throw LawViolation("vector field '$key' is missing or not an array")

fun JsonObject.arrOrNull(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.strList(key: String): List<String> =
    arrOrNull(key)?.map { (it as JsonPrimitive).content } ?: emptyList()

fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default

fun jsonStrings(values: Iterable<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

fun jsonStringOrNull(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

fun jsonLongOrNull(value: Long?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

/** Deterministic pretty JSON: two-space indent, `\n` line ends, one trailing newline, UTF-8 (LAB_SPEC 2.6). */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val pretty = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

fun renderJson(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element) + "\n"

inline fun jsonObj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

fun JsonElement.asObject(): JsonObject = jsonObject

fun JsonElement.asArray(): JsonArray = jsonArray
