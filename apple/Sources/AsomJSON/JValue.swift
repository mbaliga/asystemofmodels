/// One member of a JSON object, in input order.
public struct JMember: Sendable {
    public let name: String
    public let value: JValue

    public init(name: String, value: JValue) {
        self.name = name
        self.value = value
    }
}

/// The JSON value space of the integer profile (LAB_SPEC.md section 4.2). There is no floating-point case.
///
/// Swift's `String ==` uses canonical equivalence, so "e\u{301}" == "\u{e9}". The profile compares names
/// and strings as UTF-16 code-unit sequences with no normalisation, so equality is written out by hand.
public enum JValue: Sendable {
    case object([JMember])
    case array([JValue])
    case string(String)
    case int(Int64)
    case bool(Bool)
    case null
}

extension JValue: Equatable {
    public static func == (lhs: JValue, rhs: JValue) -> Bool {
        switch (lhs, rhs) {
        case let (.object(a), .object(b)):
            guard a.count == b.count else { return false }
            for (x, y) in zip(a, b) where !(codeUnitsEqual(x.name, y.name) && x.value == y.value) { return false }
            return true
        case let (.array(a), .array(b)):
            guard a.count == b.count else { return false }
            for (x, y) in zip(a, b) where x != y { return false }
            return true
        case let (.string(a), .string(b)): return codeUnitsEqual(a, b)
        case let (.int(a), .int(b)): return a == b
        case let (.bool(a), .bool(b)): return a == b
        case (.null, .null): return true
        default: return false
        }
    }
}

public func codeUnitsEqual(_ a: String, _ b: String) -> Bool {
    a.utf16.elementsEqual(b.utf16)
}

extension JValue {
    /// First member with this name, compared as UTF-16 code units. Names are unique in parsed values.
    public func member(_ name: String) -> JValue? {
        guard case let .object(members) = self else { return nil }
        return members.first { codeUnitsEqual($0.name, name) }?.value
    }

    public var isObject: Bool { if case .object = self { return true } else { return false } }
    public var members: [JMember]? { if case let .object(m) = self { return m } else { return nil } }
    public var elements: [JValue]? { if case let .array(a) = self { return a } else { return nil } }
    public var stringValue: String? { if case let .string(s) = self { return s } else { return nil } }
    public var intValue: Int64? { if case let .int(v) = self { return v } else { return nil } }
    public var isNull: Bool { if case .null = self { return true } else { return false } }
}
