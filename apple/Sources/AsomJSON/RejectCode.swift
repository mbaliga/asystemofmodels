/// A typed reject code from the manifest profile (LAB_SPEC.md section 4.6, manifest.md section 8.3).
/// A struct rather than an enum so that the later verifier targets can add codes in their own module.
public struct RejectCode: RawRepresentable, Hashable, Sendable, CustomStringConvertible, Error {
    public let rawValue: String

    public init(rawValue: String) { self.rawValue = rawValue }
    public init(_ rawValue: String) { self.rawValue = rawValue }

    public var description: String { rawValue }
}

extension RejectCode {
    public static let malformedJSON = RejectCode("MALFORMED_JSON")
    public static let invalidUnicode = RejectCode("INVALID_UNICODE")
    public static let nonIntegerNumber = RejectCode("NON_INTEGER_NUMBER")
    public static let numberRange = RejectCode("NUMBER_RANGE")
    public static let duplicateKey = RejectCode("DUPLICATE_KEY")
    public static let trailingData = RejectCode("TRAILING_DATA")
    public static let nonCanonical = RejectCode("NON_CANONICAL")
    public static let encoding = RejectCode("ENCODING")
}
