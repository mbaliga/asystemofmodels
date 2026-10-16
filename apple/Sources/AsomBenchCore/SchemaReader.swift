import AsomJSON

/// A typed-decoder failure. Every violation maps to SCHEMA_INVALID; the path is for diagnostics only.
public struct SchemaViolation: Error, CustomStringConvertible {
    public let path: String
    public let reason: String
    public var description: String { "\(path): \(reason)" }
    public init(_ path: String, _ reason: String) {
        self.path = path
        self.reason = reason
    }
}

/// Shared by every object read in one decode. `tolerateUnknown` is true only above the schema minor this decoder knows
/// (LAB_SPEC.md section 4.1 P10, manifest.md section 4.3); at the known minor an unknown member is a violation.
public final class DecodeState {
    public let tolerateUnknown: Bool
    public private(set) var unknownMembers = 0

    public init(tolerateUnknown: Bool) { self.tolerateUnknown = tolerateUnknown }

    func noteUnknown(_ count: Int) { unknownMembers += count }
}

/// The fixed shapes of section 4 and the schema in lab/conformance/manifest/schema, as predicates written by hand.
public enum SchemaRules {
    public static let maxBytes: Int64 = 1_125_899_906_842_624
    public static let epochLow: Int64 = 1_577_836_800_000
    public static let epochHigh: Int64 = 4_102_444_800_000
    public static let dayMs: Int64 = 86_400_000

    public static func isID(_ s: String) -> Bool {
        let b = Array(s.utf8)
        guard (1...64).contains(b.count) else { return false }
        for (k, c) in b.enumerated() {
            let lowerOrDigit = (c >= 0x61 && c <= 0x7A) || (c >= 0x30 && c <= 0x39)
            if k == 0 { if !lowerOrDigit { return false } } else if !(lowerOrDigit || c == 0x2E || c == 0x5F || c == 0x2B || c == 0x2D) { return false }
        }
        return true
    }

    public static func isMethodID(_ s: String) -> Bool {
        let parts = s.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        let head = Array(parts[0].utf8), tail = Array(parts[1].utf8)
        guard (1...48).contains(head.count), (1...4).contains(tail.count) else { return false }
        for (k, c) in head.enumerated() {
            let lowerOrDigit = (c >= 0x61 && c <= 0x7A) || (c >= 0x30 && c <= 0x39)
            if k == 0 { if !lowerOrDigit { return false } } else if !(lowerOrDigit || c == 0x2E || c == 0x2D) { return false }
        }
        return tail.allSatisfy { $0 >= 0x30 && $0 <= 0x39 }
    }

    public static func isSemver(_ s: String) -> Bool { semverParts(s) != nil }

    public static func semverParts(_ s: String) -> [Int]? {
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        var out: [Int] = []
        for p in parts {
            let b = Array(p.utf8)
            guard (1...5).contains(b.count), b.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
            if b.count > 1 && b[0] == 0x30 { return nil }
            out.append(Int(String(p))!)
        }
        return out
    }

    /// Compares two semvers of the strict shape. nil when either is malformed.
    public static func compareSemver(_ a: String, _ b: String) -> Int? {
        guard let x = semverParts(a), let y = semverParts(b) else { return nil }
        for k in 0..<3 where x[k] != y[k] { return x[k] < y[k] ? -1 : 1 }
        return 0
    }

    public static func isB64u43(_ s: String) -> Bool {
        let b = Array(s.utf8)
        return b.count == 43 && b.allSatisfy {
            ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x2D || $0 == 0x5F
        }
    }

    public static func isSha256Hex(_ s: String) -> Bool {
        let b = Array(s.utf8)
        return b.count == 64 && b.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) }
    }

    public static func isCommit40(_ s: String) -> Bool {
        let b = Array(s.utf8)
        return b.count == 40 && b.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) }
    }

    public static func isCommit7to40(_ s: String) -> Bool {
        let b = Array(s.utf8)
        return (7...40).contains(b.count) && b.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) }
    }

    public static func isDate(_ s: String) -> Bool {
        let b = Array(s.utf8)
        guard b.count == 10, b[4] == 0x2D, b[7] == 0x2D else { return false }
        func digit(_ i: Int) -> Int? { b[i] >= 0x30 && b[i] <= 0x39 ? Int(b[i] - 0x30) : nil }
        guard digit(0) == 2, digit(1) == 0, let d2 = digit(2), d2 >= 2, digit(3) != nil,
              let m1 = digit(5), let m2 = digit(6), let e1 = digit(8), let e2 = digit(9) else { return false }
        let month = m1 * 10 + m2
        let day = e1 * 10 + e2
        return (1...12).contains(month) && (1...31).contains(day)
    }

    public static func isQuant(_ s: String) -> Bool {
        let b = Array(s.utf8)
        return (1...24).contains(b.count) && b.allSatisfy {
            ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x5F || $0 == 0x2E || $0 == 0x2D
        }
    }

    public static func isBenchSetName(_ s: String) -> Bool {
        let b = Array(s.utf8)
        return (1...40).contains(b.count) && b.allSatisfy { ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x2D }
    }

    /// Display text (manifest.md section 4.2, r3: lengths counted in code points, design section 5.2): 1 to 96 code
    /// points, none of U+0000-U+001F, U+007F-U+009F, U+061C, U+200E, U+200F, U+2028, U+2029, U+202A-U+202E,
    /// U+2066-U+2069, U+FEFF.
    public static func isText(_ s: String) -> Bool {
        var count = 0
        for scalar in s.unicodeScalars {
            count += 1
            if count > 96 { return false }
            switch scalar.value {
            case 0x00...0x1F, 0x7F...0x9F, 0x061C, 0x200E, 0x200F, 0x2028, 0x2029, 0x202A...0x202E, 0x2066...0x2069, 0xFEFF:
                return false
            default:
                continue
            }
        }
        return count >= 1
    }

    /// P7: these member names are forbidden at any depth.
    public static let forbiddenNames: Set<String> = ["derived", "render", "textSha256", "field", "custom"]

    public static func containsForbiddenName(_ value: JValue) -> Bool {
        switch value {
        case let .object(members):
            for m in members {
                if forbiddenNames.contains(where: { codeUnitsEqual($0, m.name) }) { return true }
                if containsForbiddenName(m.value) { return true }
            }
            return false
        case let .array(items):
            return items.contains { containsForbiddenName($0) }
        default:
            return false
        }
    }
}

/// One JSON object being decoded. Members are looked up by name (UTF-16 code-unit comparison), and `finish()` decides
/// what to do with the ones nobody asked for.
public final class ObjectReader {
    public let path: String
    private let members: [JMember]
    private let state: DecodeState
    private var used = Set<Int>()

    public init(_ value: JValue, _ path: String, _ state: DecodeState) throws {
        guard case let .object(m) = value else { throw SchemaViolation(path, "not an object") }
        members = m
        self.path = path
        self.state = state
    }

    private func find(_ name: String) -> (Int, JValue)? {
        for (k, m) in members.enumerated() where codeUnitsEqual(m.name, name) {
            used.insert(k)
            return (k, m.value)
        }
        return nil
    }

    public func has(_ name: String) -> Bool { members.contains { codeUnitsEqual($0.name, name) } }

    public func required(_ name: String) throws -> JValue {
        guard let (_, v) = find(name) else { throw SchemaViolation("\(path).\(name)", "missing") }
        return v
    }

    public func optional(_ name: String) -> JValue? { find(name)?.1 }

    public func object(_ name: String) throws -> ObjectReader {
        try ObjectReader(try required(name), "\(path).\(name)", state)
    }

    public func nullableObject(_ name: String) throws -> ObjectReader? {
        let v = try required(name)
        if v.isNull { return nil }
        return try ObjectReader(v, "\(path).\(name)", state)
    }

    /// The elements of an array member, each as an object reader of its own.
    public func objects(_ name: String, count: ClosedRange<Int>) throws -> [ObjectReader] {
        try array(name, count: count).enumerated().map { try ObjectReader($0.element, "\(path).\(name)[\($0.offset)]", state) }
    }

    public func array(_ name: String, count: ClosedRange<Int>) throws -> [JValue] {
        guard case let .array(items) = try required(name) else { throw SchemaViolation("\(path).\(name)", "not an array") }
        guard count.contains(items.count) else { throw SchemaViolation("\(path).\(name)", "array length \(items.count) outside \(count)") }
        return items
    }

    public func int(_ name: String, _ range: ClosedRange<Int64>) throws -> Int64 {
        try Self.asInt(try required(name), range, "\(path).\(name)")
    }

    public func nullableInt(_ name: String, _ range: ClosedRange<Int64>) throws -> Int64? {
        let v = try required(name)
        if v.isNull { return nil }
        return try Self.asInt(v, range, "\(path).\(name)")
    }

    public func bool(_ name: String) throws -> Bool {
        guard case let .bool(b) = try required(name) else { throw SchemaViolation("\(path).\(name)", "not a boolean") }
        return b
    }

    public func nullableBool(_ name: String) throws -> Bool? {
        let v = try required(name)
        if v.isNull { return nil }
        guard case let .bool(b) = v else { throw SchemaViolation("\(path).\(name)", "not a boolean or null") }
        return b
    }

    public func string(_ name: String, _ ok: (String) -> Bool = { _ in true }) throws -> String {
        try Self.asString(try required(name), ok, "\(path).\(name)")
    }

    public func nullableString(_ name: String, _ ok: (String) -> Bool) throws -> String? {
        let v = try required(name)
        if v.isNull { return nil }
        return try Self.asString(v, ok, "\(path).\(name)")
    }

    public func text(_ name: String) throws -> String { try string(name, SchemaRules.isText) }
    public func nullableText(_ name: String) throws -> String? { try nullableString(name, SchemaRules.isText) }
    public func id(_ name: String) throws -> String { try string(name, SchemaRules.isID) }

    public func oneOf(_ name: String, _ allowed: [String]) throws -> String {
        let s = try string(name)
        guard allowed.contains(where: { codeUnitsEqual($0, s) }) else { throw SchemaViolation("\(path).\(name)", "value not in the closed enum") }
        return s
    }

    public func constant(_ name: String, _ expected: String) throws {
        let s = try string(name)
        guard codeUnitsEqual(s, expected) else { throw SchemaViolation("\(path).\(name)", "must be \(expected)") }
    }

    public func constantInt(_ name: String, _ expected: Int64) throws {
        guard try int(name, expected...expected) == expected else { throw SchemaViolation("\(path).\(name)", "must be \(expected)") }
    }

    public static func asInt(_ v: JValue, _ range: ClosedRange<Int64>, _ path: String) throws -> Int64 {
        guard case let .int(i) = v else { throw SchemaViolation(path, "not an integer") }
        guard range.contains(i) else { throw SchemaViolation(path, "\(i) outside \(range)") }
        return i
    }

    public static func asString(_ v: JValue, _ ok: (String) -> Bool, _ path: String) throws -> String {
        guard case let .string(s) = v else { throw SchemaViolation(path, "not a string") }
        guard ok(s) else { throw SchemaViolation(path, "string violates its shape rule") }
        return s
    }

    /// Members nobody asked for: tolerated and counted above the known minor, a violation at it.
    public func finish() throws {
        let unknown = members.count - used.count
        if unknown == 0 { return }
        guard state.tolerateUnknown else { throw SchemaViolation(path, "\(unknown) unknown member(s) at the known schema minor") }
        state.noteUnknown(unknown)
    }
}
