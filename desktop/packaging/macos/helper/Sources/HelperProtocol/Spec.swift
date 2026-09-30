// The field tables of helper-protocol/SCHEMA.md sections 4 to 6, as data. The Kotlin lane holds the same tables
// (macplatform/.../helper/ProtocolSpec.kt); the vectors pin them.

public enum RejectCode: String, CaseIterable, Sendable {
    case lineTooLong = "LINE_TOO_LONG"
    case malformedJSON = "MALFORMED_JSON"
    case notObject = "NOT_OBJECT"
    case unknownOp = "UNKNOWN_OP"
    case unknownField = "UNKNOWN_FIELD"
    case missingField = "MISSING_FIELD"
    case badField = "BAD_FIELD"
}

/// A line that failed to decode. `id` is the best-effort request id (present when the top-level object parsed and carried a valid one).
public struct Reject: Error, Equatable {
    public let code: RejectCode
    public let id: Int64?
    public init(_ code: RejectCode, id: Int64? = nil) {
        self.code = code
        self.id = id
    }
}

public enum TextRule: Sendable {
    case noControl
    case absolutePath
    case printableASCII
    case exact(String)
    case semver
    case macosVersion
}

public enum FieldType: Sendable {
    case int(min: Int64, max: Int64)
    case bool
    case oneOf([String])
    /// Base64 on the wire; the limits are on the decoded byte count.
    case bytes(min: Int, max: Int)
    /// The limits are on UTF-8 bytes.
    case text(min: Int, max: Int, rule: TextRule)
}

public struct FieldSpec: Sendable {
    public let name: String
    public let type: FieldType
    /// The member must still be present; the value `null` is allowed (int and bool fields only).
    public let nullable: Bool

    init(_ name: String, _ type: FieldType, nullable: Bool = false) {
        self.name = name
        self.type = type
        self.nullable = nullable
    }
}

public enum ProtocolSpec {
    public static let maxLineBytes = 131_072
    public static let maxSafeInteger: Int64 = 9_007_199_254_740_991
    public static let maxBlobBytes = 4_096
    public static let maxDataBytes = 65_536
    public static let spkiBytes = 91
    public static let signatureBytes = 64
    public static let digestBytes = 32
    public static let version: Int64 = 1
    public static let holdReason = "asom: lending compute to your paired devices"

    public static let errorCodes = ["BAD_REQUEST", "UNKNOWN_OP", "UNSUPPORTED_VERSION", "UNAVAILABLE", "FAILED"]
    public static let thermalStates = ["nominal", "fair", "serious", "critical"]
    public static let svcKinds = ["agent", "daemon"]
    public static let svcStatuses = ["notRegistered", "enabled", "requiresApproval", "notFound"]

    private static let token = FieldSpec("token", .int(min: 0, max: maxSafeInteger))
    private static let blob = FieldSpec("blob", .bytes(min: 1, max: maxBlobBytes))
    private static let svcKind = FieldSpec("kind", .oneOf(svcKinds))
    private static let svcStatus = FieldSpec("status", .oneOf(svcStatuses))
    private static let thermalState = FieldSpec("state", .oneOf(thermalStates))
    private static let powerFields = [
        FieldSpec("source", .oneOf(["ac", "battery"])),
        FieldSpec("charging", .bool),
        FieldSpec("batteryPermille", .int(min: 0, max: 1000), nullable: true),
        FieldSpec("lowPower", .bool),
    ]

    /// Request parameters by op, in canonical order. Nil for an unknown op.
    public static func requestParams(_ op: String) -> [FieldSpec]? {
        for (name, params) in requests where name.utf8.elementsEqual(op.utf8) { return params }
        return nil
    }

    public static func replyFields(_ op: String) -> [FieldSpec]? {
        for (name, fields) in replies where name.utf8.elementsEqual(op.utf8) { return fields }
        return nil
    }

    public static func eventFields(_ name: String) -> [FieldSpec]? {
        for (n, fields) in events where n.utf8.elementsEqual(name.utf8) { return fields }
        return nil
    }

    public static let errorFields = [
        FieldSpec("code", .oneOf(errorCodes)),
        FieldSpec("message", .text(min: 0, max: 200, rule: .noControl)),
    ]

    public static var requestOps: [String] { requests.map { $0.0 } }
    public static var eventNames: [String] { events.map { $0.0 } }

    private static let requests: [(String, [FieldSpec])] = [
        ("hello", [FieldSpec("v", .int(min: 0, max: maxSafeInteger))]),
        ("se.create", []),
        ("se.sign", [blob, FieldSpec("data", .bytes(min: 0, max: maxDataBytes))]),
        ("se.selftest", [blob]),
        ("power.get", []),
        ("thermal.get", []),
        ("presence.get", []),
        ("gpu.get", []),
        ("mem.get", []),
        ("assert.hold", [FieldSpec("reason", .text(min: 1, max: 128, rule: .exact(holdReason)))]),
        ("assert.release", []),
        ("sleep.ack", [token]),
        ("svc.status", [svcKind]),
        ("svc.register", [svcKind]),
        ("svc.unregister", [svcKind]),
        ("backup.exclude", [FieldSpec("path", .text(min: 1, max: 1024, rule: .absolutePath))]),
        ("platform.uuid", []),
        ("paths.get", []),
    ]

    private static let replies: [(String, [FieldSpec])] = [
        ("hello", [
            FieldSpec("helper", .text(min: 5, max: 32, rule: .semver)),
            FieldSpec("macos", .text(min: 3, max: 16, rule: .macosVersion)),
            FieldSpec("arch", .oneOf(["arm64", "x86_64"])),
            FieldSpec("se", .bool),
            FieldSpec("model", .text(min: 1, max: 64, rule: .printableASCII)),
        ]),
        ("se.create", [blob, FieldSpec("spki", .bytes(min: spkiBytes, max: spkiBytes))]),
        ("se.sign", [FieldSpec("sig", .bytes(min: signatureBytes, max: signatureBytes))]),
        ("se.selftest", [FieldSpec("verified", .bool)]),
        ("power.get", powerFields),
        ("thermal.get", [thermalState]),
        ("presence.get", [
            FieldSpec("hidIdleMs", .int(min: 0, max: maxSafeInteger), nullable: true),
            FieldSpec("screenLocked", .bool, nullable: true),
            FieldSpec("consoleUserIsSelf", .bool, nullable: true),
        ]),
        ("gpu.get", [FieldSpec("deviceUtilPermille", .int(min: 0, max: 1000))]),
        ("mem.get", [
            FieldSpec("physicalBytes", .int(min: 1, max: maxSafeInteger)),
            FieldSpec("gpuRecommendedMaxWorkingSetBytes", .int(min: 0, max: maxSafeInteger), nullable: true),
        ]),
        ("assert.hold", []),
        ("assert.release", []),
        ("sleep.ack", []),
        ("svc.status", [svcStatus]),
        ("svc.register", [svcStatus]),
        ("svc.unregister", [svcStatus]),
        ("backup.exclude", []),
        ("platform.uuid", [FieldSpec("digest", .bytes(min: digestBytes, max: digestBytes))]),
        ("paths.get", [FieldSpec("userTempDir", .text(min: 1, max: 1024, rule: .absolutePath))]),
    ]

    private static let events: [(String, [FieldSpec])] = [
        ("power", powerFields),
        ("thermal", [thermalState]),
        ("sleep.will", [token]),
        ("wake", []),
    ]
}
