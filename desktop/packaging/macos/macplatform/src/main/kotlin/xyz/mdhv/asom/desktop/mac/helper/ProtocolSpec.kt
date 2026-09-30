package xyz.mdhv.asom.desktop.mac.helper

/*
 * The field tables of helper-protocol/SCHEMA.md sections 4 to 6, as data: the same tables the Swift helper holds
 * (helper/Sources/HelperProtocol/Spec.swift). The vectors pin both.
 */

enum class RejectCode(val wire: String) {
    LINE_TOO_LONG("LINE_TOO_LONG"),
    MALFORMED_JSON("MALFORMED_JSON"),
    NOT_OBJECT("NOT_OBJECT"),
    UNKNOWN_OP("UNKNOWN_OP"),
    UNKNOWN_FIELD("UNKNOWN_FIELD"),
    MISSING_FIELD("MISSING_FIELD"),
    BAD_FIELD("BAD_FIELD"),
}

/** A line that failed to decode. [id] is the best-effort request id (the top-level object parsed and carried a valid one). */
class Reject(val code: RejectCode, val id: Long? = null) : IllegalArgumentException(code.wire)

enum class TextRule { NO_CONTROL, ABSOLUTE_PATH, PRINTABLE_ASCII, EXACT, SEMVER, MACOS_VERSION }

sealed interface FieldType {
    data class IntRange(val min: Long, val max: Long) : FieldType
    data object Bool : FieldType
    data class OneOf(val options: List<String>) : FieldType

    /** Base64 on the wire; the limits are on the decoded byte count. */
    data class Bytes(val min: Int, val max: Int) : FieldType

    /** The limits are on UTF-8 bytes. [exact] is the required text when [rule] is [TextRule.EXACT]. */
    data class Text(val min: Int, val max: Int, val rule: TextRule, val exact: String? = null) : FieldType
}

class FieldSpec(val name: String, val type: FieldType, val nullable: Boolean = false)

object ProtocolSpec {
    const val VERSION = 1L
    const val MAX_LINE_BYTES = 131_072
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    const val MAX_BLOB_BYTES = 4_096
    const val MAX_DATA_BYTES = 65_536
    const val SPKI_BYTES = 91
    const val SIGNATURE_BYTES = 64
    const val DIGEST_BYTES = 32

    /** The assertion name `pmset -g assertions` shows (macos.md 2.1). Fixed: the protocol refuses any other reason. */
    const val HOLD_REASON = "asom: lending compute to your paired devices"

    val ERROR_CODES = listOf("BAD_REQUEST", "UNKNOWN_OP", "UNSUPPORTED_VERSION", "UNAVAILABLE", "FAILED")
    val THERMAL_STATES = listOf("nominal", "fair", "serious", "critical")
    val SVC_KINDS = listOf("agent", "daemon")
    val SVC_STATUSES = listOf("notRegistered", "enabled", "requiresApproval", "notFound")

    private val token = FieldSpec("token", FieldType.IntRange(0, MAX_SAFE_INTEGER))
    private val blob = FieldSpec("blob", FieldType.Bytes(1, MAX_BLOB_BYTES))
    private val svcKind = FieldSpec("kind", FieldType.OneOf(SVC_KINDS))
    private val svcStatus = FieldSpec("status", FieldType.OneOf(SVC_STATUSES))
    private val thermalState = FieldSpec("state", FieldType.OneOf(THERMAL_STATES))
    private val powerFields = listOf(
        FieldSpec("source", FieldType.OneOf(listOf("ac", "battery"))),
        FieldSpec("charging", FieldType.Bool),
        FieldSpec("batteryPermille", FieldType.IntRange(0, 1000), nullable = true),
        FieldSpec("lowPower", FieldType.Bool),
    )

    private val requests: List<Pair<String, List<FieldSpec>>> = listOf(
        "hello" to listOf(FieldSpec("v", FieldType.IntRange(0, MAX_SAFE_INTEGER))),
        "se.create" to emptyList(),
        "se.sign" to listOf(blob, FieldSpec("data", FieldType.Bytes(0, MAX_DATA_BYTES))),
        "se.selftest" to listOf(blob),
        "power.get" to emptyList(),
        "thermal.get" to emptyList(),
        "presence.get" to emptyList(),
        "gpu.get" to emptyList(),
        "mem.get" to emptyList(),
        "assert.hold" to listOf(FieldSpec("reason", FieldType.Text(1, 128, TextRule.EXACT, HOLD_REASON))),
        "assert.release" to emptyList(),
        "sleep.ack" to listOf(token),
        "svc.status" to listOf(svcKind),
        "svc.register" to listOf(svcKind),
        "svc.unregister" to listOf(svcKind),
        "backup.exclude" to listOf(FieldSpec("path", FieldType.Text(1, 1024, TextRule.ABSOLUTE_PATH))),
        "platform.uuid" to emptyList(),
        "paths.get" to emptyList(),
    )

    private val replies: List<Pair<String, List<FieldSpec>>> = listOf(
        "hello" to listOf(
            FieldSpec("helper", FieldType.Text(5, 32, TextRule.SEMVER)),
            FieldSpec("macos", FieldType.Text(3, 16, TextRule.MACOS_VERSION)),
            FieldSpec("arch", FieldType.OneOf(listOf("arm64", "x86_64"))),
            FieldSpec("se", FieldType.Bool),
            FieldSpec("model", FieldType.Text(1, 64, TextRule.PRINTABLE_ASCII)),
        ),
        "se.create" to listOf(blob, FieldSpec("spki", FieldType.Bytes(SPKI_BYTES, SPKI_BYTES))),
        "se.sign" to listOf(FieldSpec("sig", FieldType.Bytes(SIGNATURE_BYTES, SIGNATURE_BYTES))),
        "se.selftest" to listOf(FieldSpec("verified", FieldType.Bool)),
        "power.get" to powerFields,
        "thermal.get" to listOf(thermalState),
        "presence.get" to listOf(
            FieldSpec("hidIdleMs", FieldType.IntRange(0, MAX_SAFE_INTEGER), nullable = true),
            FieldSpec("screenLocked", FieldType.Bool, nullable = true),
            FieldSpec("consoleUserIsSelf", FieldType.Bool, nullable = true),
        ),
        "gpu.get" to listOf(FieldSpec("deviceUtilPermille", FieldType.IntRange(0, 1000))),
        "mem.get" to listOf(
            FieldSpec("physicalBytes", FieldType.IntRange(1, MAX_SAFE_INTEGER)),
            FieldSpec("gpuRecommendedMaxWorkingSetBytes", FieldType.IntRange(0, MAX_SAFE_INTEGER), nullable = true),
        ),
        "assert.hold" to emptyList(),
        "assert.release" to emptyList(),
        "sleep.ack" to emptyList(),
        "svc.status" to listOf(svcStatus),
        "svc.register" to listOf(svcStatus),
        "svc.unregister" to listOf(svcStatus),
        "backup.exclude" to emptyList(),
        "platform.uuid" to listOf(FieldSpec("digest", FieldType.Bytes(DIGEST_BYTES, DIGEST_BYTES))),
        "paths.get" to listOf(FieldSpec("userTempDir", FieldType.Text(1, 1024, TextRule.ABSOLUTE_PATH))),
    )

    private val events: List<Pair<String, List<FieldSpec>>> = listOf(
        "power" to powerFields,
        "thermal" to listOf(thermalState),
        "sleep.will" to listOf(token),
        "wake" to emptyList(),
    )

    val errorFields: List<FieldSpec> = listOf(
        FieldSpec("code", FieldType.OneOf(ERROR_CODES)),
        FieldSpec("message", FieldType.Text(0, 200, TextRule.NO_CONTROL)),
    )

    val requestOps: List<String> get() = requests.map { it.first }
    val eventNames: List<String> get() = events.map { it.first }

    fun requestParams(op: String): List<FieldSpec>? = requests.firstOrNull { it.first == op }?.second
    fun replyFields(op: String): List<FieldSpec>? = replies.firstOrNull { it.first == op }?.second
    fun eventFields(name: String): List<FieldSpec>? = events.firstOrNull { it.first == name }?.second
}
