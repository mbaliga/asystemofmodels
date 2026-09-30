/// Strict JSON tokenizer for the integer profile (LAB_SPEC.md section 4.2). Hand-written on purpose:
/// Foundation's JSONSerialization does not reject duplicate names, keeps no number lexemes and is not
/// specified to reject trailing data, so it must never parse a signed document.
///
/// Error priority follows the table order of section 4.2. A structural error (not one JSON value) stops the
/// parse at once. The other classes are recorded while the parse goes on, and the lowest-numbered class wins.
public enum StrictJSON {
    public static let maxDepth = 16
    public static let maxSafeInteger: Int64 = 9_007_199_254_740_991

    public static func parse(_ bytes: [UInt8]) -> Result<JValue, RejectCode> {
        var parser = Parser(bytes)
        return parser.run()
    }
}

private enum Rank {
    static let invalidUnicode = 2
    static let nonInteger = 4
    static let range = 5
    static let duplicate = 6
    static let depth = 7
    static let trailing = 8
}

private enum Stop: Error { case structural, depth }

private struct Parser {
    let b: [UInt8]
    var i = 0
    var soft: (rank: Int, code: RejectCode)?

    init(_ bytes: [UInt8]) { b = bytes }

    mutating func note(_ rank: Int, _ code: RejectCode) {
        if let current = soft, current.rank <= rank { return }
        soft = (rank, code)
    }

    mutating func run() -> Result<JValue, RejectCode> {
        if b.count >= 3, b[0] == 0xEF, b[1] == 0xBB, b[2] == 0xBF { return .failure(.malformedJSON) }
        let value: JValue
        do {
            value = try parseValue(depth: 1)
        } catch Stop.depth {
            note(Rank.depth, .malformedJSON)
            return .failure(soft!.code)
        } catch {
            return .failure(.malformedJSON)
        }
        skipWhitespace()
        if i < b.count {
            note(Rank.trailing, .trailingData)
            if !remainderIsValidUTF8() { note(Rank.invalidUnicode, .invalidUnicode) }
        }
        if let soft { return .failure(soft.code) }
        return .success(value)
    }

    /// Row 2 of the table (invalid UTF-8) outranks row 8 (trailing data): the check is on the bytes of the whole input,
    /// so invalid UTF-8 after the value is INVALID_UNICODE. The data after the value is not otherwise parsed.
    func remainderIsValidUTF8() -> Bool {
        var k = i
        while k < b.count {
            if b[k] < 0x80 { k += 1; continue }
            guard let (_, length) = decodeUTF8(at: k) else { return false }
            k += length
        }
        return true
    }

    mutating func skipWhitespace() {
        while i < b.count, b[i] == 0x20 || b[i] == 0x09 || b[i] == 0x0A || b[i] == 0x0D { i += 1 }
    }

    mutating func expect(_ literal: StaticString) throws {
        let n = literal.utf8CodeUnitCount
        guard i + n <= b.count else { throw Stop.structural }
        let p = literal.utf8Start
        for k in 0..<n where b[i + k] != p[k] { throw Stop.structural }
        i += n
    }

    mutating func parseValue(depth: Int) throws -> JValue {
        skipWhitespace()
        guard i < b.count else { throw Stop.structural }
        switch b[i] {
        case 0x7B: return try parseObject(depth: depth)
        case 0x5B: return try parseArray(depth: depth)
        case 0x22: return .string(try parseString())
        case 0x74: try expect("true"); return .bool(true)
        case 0x66: try expect("false"); return .bool(false)
        case 0x6E: try expect("null"); return .null
        case 0x4E: try expect("NaN"); note(Rank.nonInteger, .nonIntegerNumber); return .null
        case 0x49: try expect("Infinity"); note(Rank.nonInteger, .nonIntegerNumber); return .null
        case 0x2D, 0x30...0x39: return try parseNumber()
        default: throw Stop.structural
        }
    }

    mutating func parseObject(depth: Int) throws -> JValue {
        if depth > StrictJSON.maxDepth { throw Stop.depth }
        i += 1
        var members: [JMember] = []
        var seen = Set<[UInt16]>()
        skipWhitespace()
        if i < b.count, b[i] == 0x7D { i += 1; return .object(members) }
        while true {
            skipWhitespace()
            guard i < b.count, b[i] == 0x22 else { throw Stop.structural }
            let name = try parseString()
            if !seen.insert(Array(name.utf16)).inserted { note(Rank.duplicate, .duplicateKey) }
            skipWhitespace()
            guard i < b.count, b[i] == 0x3A else { throw Stop.structural }
            i += 1
            let value = try parseValue(depth: depth + 1)
            members.append(JMember(name: name, value: value))
            skipWhitespace()
            guard i < b.count else { throw Stop.structural }
            if b[i] == 0x2C { i += 1; continue }
            if b[i] == 0x7D { i += 1; return .object(members) }
            throw Stop.structural
        }
    }

    mutating func parseArray(depth: Int) throws -> JValue {
        if depth > StrictJSON.maxDepth { throw Stop.depth }
        i += 1
        var elements: [JValue] = []
        skipWhitespace()
        if i < b.count, b[i] == 0x5D { i += 1; return .array(elements) }
        while true {
            elements.append(try parseValue(depth: depth + 1))
            skipWhitespace()
            guard i < b.count else { throw Stop.structural }
            if b[i] == 0x2C { i += 1; continue }
            if b[i] == 0x5D { i += 1; return .array(elements) }
            throw Stop.structural
        }
    }

    mutating func parseNumber() throws -> JValue {
        var negative = false
        if b[i] == 0x2D { negative = true; i += 1 }
        guard i < b.count else { throw Stop.structural }
        if b[i] == 0x49 { try expect("Infinity"); note(Rank.nonInteger, .nonIntegerNumber); return .null }
        guard isDigit(b[i]) else { throw Stop.structural }
        let start = i
        while i < b.count, isDigit(b[i]) { i += 1 }
        let digits = b[start..<i]
        var offProfile = digits.count > 1 && digits.first == 0x30
        if i < b.count, b[i] == 0x2E || b[i] == 0x65 || b[i] == 0x45 {
            offProfile = true
            while i < b.count, isDigit(b[i]) || b[i] == 0x2E || b[i] == 0x65 || b[i] == 0x45 || b[i] == 0x2B || b[i] == 0x2D {
                i += 1
            }
        }
        if negative, digits.count == 1, digits.first == 0x30 { offProfile = true }
        if offProfile { note(Rank.nonInteger, .nonIntegerNumber); return .null }
        if digits.count > 16 { note(Rank.range, .numberRange); return .null }
        var magnitude: Int64 = 0
        for d in digits { magnitude = magnitude * 10 + Int64(d - 0x30) }
        if magnitude > StrictJSON.maxSafeInteger { note(Rank.range, .numberRange); return .null }
        return .int(negative ? -magnitude : magnitude)
    }

    func isDigit(_ c: UInt8) -> Bool { c >= 0x30 && c <= 0x39 }

    mutating func parseString() throws -> String {
        i += 1
        var out = String.UnicodeScalarView()
        while true {
            guard i < b.count else { throw Stop.structural }
            let c = b[i]
            if c == 0x22 { i += 1; return String(out) }
            if c < 0x20 { throw Stop.structural }
            if c == 0x5C { try parseEscape(into: &out); continue }
            if c < 0x80 { out.append(Unicode.Scalar(c)); i += 1; continue }
            if let (scalar, length) = decodeUTF8(at: i) {
                out.append(scalar)
                i += length
            } else {
                note(Rank.invalidUnicode, .invalidUnicode)
                out.append("\u{FFFD}")
                i += 1
            }
        }
    }

    mutating func parseEscape(into out: inout String.UnicodeScalarView) throws {
        i += 1
        guard i < b.count else { throw Stop.structural }
        let e = b[i]
        i += 1
        switch e {
        case 0x22: out.append("\"")
        case 0x5C: out.append("\\")
        case 0x2F: out.append("/")
        case 0x62: out.append("\u{8}")
        case 0x66: out.append("\u{C}")
        case 0x6E: out.append("\n")
        case 0x72: out.append("\r")
        case 0x74: out.append("\t")
        case 0x75:
            let unit = try parseHex4()
            if unit >= 0xD800 && unit <= 0xDBFF {
                if i + 1 < b.count, b[i] == 0x5C, b[i + 1] == 0x75 {
                    let save = i
                    i += 2
                    let low = try parseHex4()
                    if low >= 0xDC00 && low <= 0xDFFF {
                        let v = 0x10000 + ((UInt32(unit) - 0xD800) << 10) + (UInt32(low) - 0xDC00)
                        out.append(Unicode.Scalar(v)!)
                        return
                    }
                    i = save
                }
                note(Rank.invalidUnicode, .invalidUnicode)
                out.append("\u{FFFD}")
            } else if unit >= 0xDC00 && unit <= 0xDFFF {
                note(Rank.invalidUnicode, .invalidUnicode)
                out.append("\u{FFFD}")
            } else {
                out.append(Unicode.Scalar(UInt32(unit))!)
            }
        default: throw Stop.structural
        }
    }

    mutating func parseHex4() throws -> UInt16 {
        guard i + 4 <= b.count else { throw Stop.structural }
        var v: UInt16 = 0
        for k in 0..<4 {
            let c = b[i + k]
            let d: UInt16
            switch c {
            case 0x30...0x39: d = UInt16(c - 0x30)
            case 0x41...0x46: d = UInt16(c - 0x41 + 10)
            case 0x61...0x66: d = UInt16(c - 0x61 + 10)
            default: throw Stop.structural
            }
            v = v << 4 | d
        }
        i += 4
        return v
    }

    /// Strict UTF-8 (RFC 3629): no overlongs, no surrogate code points, nothing above U+10FFFF, no truncation.
    func decodeUTF8(at p: Int) -> (Unicode.Scalar, Int)? {
        let c = b[p]
        func cont(_ k: Int, _ lo: UInt8 = 0x80, _ hi: UInt8 = 0xBF) -> UInt32? {
            guard p + k < b.count, b[p + k] >= lo, b[p + k] <= hi else { return nil }
            return UInt32(b[p + k] & 0x3F)
        }
        switch c {
        case 0xC2...0xDF:
            guard let c1 = cont(1) else { return nil }
            return (Unicode.Scalar(UInt32(c & 0x1F) << 6 | c1)!, 2)
        case 0xE0...0xEF:
            let lo: UInt8 = c == 0xE0 ? 0xA0 : 0x80
            let hi: UInt8 = c == 0xED ? 0x9F : 0xBF
            guard let c1 = cont(1, lo, hi), let c2 = cont(2) else { return nil }
            return (Unicode.Scalar(UInt32(c & 0x0F) << 12 | c1 << 6 | c2)!, 3)
        case 0xF0...0xF4:
            let lo: UInt8 = c == 0xF0 ? 0x90 : 0x80
            let hi: UInt8 = c == 0xF4 ? 0x8F : 0xBF
            guard let c1 = cont(1, lo, hi), let c2 = cont(2), let c3 = cont(3) else { return nil }
            return (Unicode.Scalar(UInt32(c & 0x07) << 18 | c1 << 12 | c2 << 6 | c3)!, 4)
        default:
            return nil
        }
    }
}
