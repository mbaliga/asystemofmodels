/// Strict JSON tokenizer for the integer profile (LAB_SPEC.md section 4.2). Hand-written on purpose:
/// Foundation's JSONSerialization does not reject duplicate names, keeps no number lexemes and is not
/// specified to reject trailing data, so it must never parse a signed document.
///
/// Error priority follows the table order of section 4.2. A structural error (not one JSON value) stops the
/// parse at once. The other classes are recorded while the parse goes on, and the lowest-numbered class wins.
/// Depth beyond 16 is recorded and the scan goes on, iteratively, so that rows 2 to 6 still outrank row 7 wherever
/// they sit (ERR-JSON-1; ERR-FX-CV7) and a deep document cannot exhaust the stack.
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

private enum Stop: Error { case structural }

private struct Parser {
    let b: [UInt8]
    var i = 0
    var soft: (rank: Int, code: RejectCode)?
    var tooDeep = false

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
        } catch {
            return .failure(.malformedJSON)
        }
        if tooDeep { note(Rank.depth, .malformedJSON) }
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
        default: return try parseScalar()
        }
    }

    /// A string, a literal, or a number. A letter run and a number run are each ONE token, taken maximally: `truex` and `Infinityx` are
    /// not a value followed by data, and `1-2`, `-` and `[-NaN]` are number lexemes outside the profile (ERR-JSON-3; ERR-FX-CV8).
    mutating func parseScalar() throws -> JValue {
        guard i < b.count else { throw Stop.structural }
        let c = b[i]
        switch c {
        case 0x22: return .string(try parseString())
        case 0x2D:
            if i + 1 < b.count, isLetter(b[i + 1]) { return try parseWord(skip: 1) }
            return try parseNumber()
        case 0x30...0x39: return try parseNumber()
        default:
            if isLetter(c) { return try parseWord(skip: 0) }
            throw Stop.structural
        }
    }

    func isLetter(_ c: UInt8) -> Bool { (c >= 0x61 && c <= 0x7A) || (c >= 0x41 && c <= 0x5A) }

    mutating func parseWord(skip: Int) throws -> JValue {
        let start = i
        i += skip
        let from = i
        while i < b.count, isLetter(b[i]) { i += 1 }
        let word = Array(b[from..<i])
        if skip == 0 {
            if word == Array("true".utf8) { return .bool(true) }
            if word == Array("false".utf8) { return .bool(false) }
            if word == Array("null".utf8) { return .null }
        }
        if word == Array("NaN".utf8) || word == Array("Infinity".utf8) {
            note(Rank.nonInteger, .nonIntegerNumber)
            return .null
        }
        i = start
        throw Stop.structural
    }

    mutating func parseObject(depth: Int) throws -> JValue {
        if depth > StrictJSON.maxDepth { return try skimDeep() }
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
        if depth > StrictJSON.maxDepth { return try skimDeep() }
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

    /// The whole lexeme (a maximal run of digits, signs, dots and e/E, starting at the current byte) is judged as one number.
    mutating func parseNumber() throws -> JValue {
        let start = i
        while i < b.count, isDigit(b[i]) || b[i] == 0x2D || b[i] == 0x2B || b[i] == 0x2E || b[i] == 0x65 || b[i] == 0x45 { i += 1 }
        let negative = b[start] == 0x2D
        let digits = b[(start + (negative ? 1 : 0))..<i]
        let integerForm = !digits.isEmpty && digits.allSatisfy(isDigit) && (digits.count == 1 || digits.first != 0x30)
        if !integerForm || (negative && digits.count == 1 && digits.first == 0x30) {
            note(Rank.nonInteger, .nonIntegerNumber)
            return .null
        }
        if digits.count > 16 { note(Rank.range, .numberRange); return .null }
        var magnitude: Int64 = 0
        for d in digits { magnitude = magnitude * 10 + Int64(d - 0x30) }
        if magnitude > StrictJSON.maxSafeInteger { note(Rank.range, .numberRange); return .null }
        return .int(negative ? -magnitude : magnitude)
    }

    /// Entered at the opening bracket of a container nested deeper than the limit. Records the depth fault, then scans to the matching
    /// close without recursion, applying every check of the recursive path (strings, numbers, duplicate names), and returns a placeholder.
    mutating func skimDeep() throws -> JValue {
        tooDeep = true
        var stack: [(isObject: Bool, seen: Set<[UInt16]>)] = []
        func readKey(_ p: inout Parser) throws {
            p.skipWhitespace()
            guard p.i < p.b.count, p.b[p.i] == 0x22 else { throw Stop.structural }
            let name = try p.parseString()
            if !stack[stack.count - 1].seen.insert(Array(name.utf16)).inserted { p.note(Rank.duplicate, .duplicateKey) }
            p.skipWhitespace()
            guard p.i < p.b.count, p.b[p.i] == 0x3A else { throw Stop.structural }
            p.i += 1
        }
        while true {
            skipWhitespace()
            guard i < b.count else { throw Stop.structural }
            var completed = true
            switch b[i] {
            case 0x7B:
                i += 1
                skipWhitespace()
                if i < b.count, b[i] == 0x7D {
                    i += 1
                } else {
                    stack.append((true, []))
                    try readKey(&self)
                    completed = false
                }
            case 0x5B:
                i += 1
                skipWhitespace()
                if i < b.count, b[i] == 0x5D {
                    i += 1
                } else {
                    stack.append((false, []))
                    completed = false
                }
            default:
                _ = try parseScalar()
            }
            if !completed { continue }
            closing: while true {
                guard let top = stack.last else { return .null }
                skipWhitespace()
                guard i < b.count else { throw Stop.structural }
                let c = b[i]
                if top.isObject {
                    if c == 0x2C { i += 1; try readKey(&self); break closing }
                    if c == 0x7D { i += 1; stack.removeLast(); continue closing }
                } else {
                    if c == 0x2C { i += 1; break closing }
                    if c == 0x5D { i += 1; stack.removeLast(); continue closing }
                }
                throw Stop.structural
            }
        }
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
