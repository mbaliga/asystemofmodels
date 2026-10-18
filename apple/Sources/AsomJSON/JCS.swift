/// RFC 8785 serialiser, integer profile (LAB_SPEC.md section 4.2).
public enum JCS {
    public enum Failure: Error, Equatable {
        case integerOutOfRange
        case duplicateMember
    }

    public static func serialize(_ value: JValue) throws -> [UInt8] {
        var out: [UInt8] = []
        try append(value, to: &out)
        return out
    }

    /// Parse strictly, then serialise. The canonical form of a document that the strict parser accepts.
    public static func canonicalize(_ bytes: [UInt8]) -> Result<[UInt8], RejectCode> {
        switch StrictJSON.parse(bytes) {
        case let .failure(code): return .failure(code)
        case let .success(value):
            guard let out = try? serialize(value) else { return .failure(.numberRange) }
            return .success(out)
        }
    }

    /// A signed payload must equal its own canonical form byte for byte (verifier step 10).
    public static func isCanonical(_ bytes: [UInt8]) -> Bool {
        if case let .success(out) = canonicalize(bytes) { return out == bytes }
        return false
    }

    private static func append(_ value: JValue, to out: inout [UInt8]) throws {
        switch value {
        case .null: out.append(contentsOf: Array("null".utf8))
        case let .bool(v): out.append(contentsOf: Array((v ? "true" : "false").utf8))
        case let .int(v):
            guard v >= -StrictJSON.maxSafeInteger, v <= StrictJSON.maxSafeInteger else { throw Failure.integerOutOfRange }
            out.append(contentsOf: Array(String(v).utf8))
        case let .string(s): appendString(s, to: &out)
        case let .array(items):
            out.append(0x5B)
            for (index, item) in items.enumerated() {
                if index > 0 { out.append(0x2C) }
                try append(item, to: &out)
            }
            out.append(0x5D)
        case let .object(members):
            let keyed = members.map { (units: Array($0.name.utf16), member: $0) }
            let sorted = keyed.sorted { $0.units.lexicographicallyPrecedes($1.units) }
            for k in 1..<max(sorted.count, 1) where sorted[k - 1].units == sorted[k].units { throw Failure.duplicateMember }
            out.append(0x7B)
            for (index, entry) in sorted.enumerated() {
                if index > 0 { out.append(0x2C) }
                appendString(entry.member.name, to: &out)
                out.append(0x3A)
                try append(entry.member.value, to: &out)
            }
            out.append(0x7D)
        }
    }

    private static let hexDigits = Array("0123456789abcdef".utf8)

    private static func appendString(_ s: String, to out: inout [UInt8]) {
        out.append(0x22)
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x22: out.append(contentsOf: [0x5C, 0x22])
            case 0x5C: out.append(contentsOf: [0x5C, 0x5C])
            case 0x08: out.append(contentsOf: [0x5C, 0x62])
            case 0x09: out.append(contentsOf: [0x5C, 0x74])
            case 0x0A: out.append(contentsOf: [0x5C, 0x6E])
            case 0x0C: out.append(contentsOf: [0x5C, 0x66])
            case 0x0D: out.append(contentsOf: [0x5C, 0x72])
            case 0x00...0x1F:
                let v = UInt8(scalar.value)
                out.append(contentsOf: [0x5C, 0x75, 0x30, 0x30, hexDigits[Int(v >> 4)], hexDigits[Int(v & 0xF)]])
            default:
                out.append(contentsOf: Array(String(scalar).utf8))
            }
        }
        out.append(0x22)
    }
}
