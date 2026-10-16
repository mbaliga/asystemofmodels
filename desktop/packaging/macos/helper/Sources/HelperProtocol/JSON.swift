// The strict JSON profile of helper-protocol/SCHEMA.md section 2, on raw bytes. No Foundation: Foundation's JSONSerialization
// accepts duplicates, floats and lone surrogates, and String equality in Swift is canonical equivalence (U+212A equals "K"),
// so every comparison here is over UTF-8 bytes.

public indirect enum JSONValue {
    case object(JSONObject)
    case array([JSONValue])
    case string(String)
    case int(Int64)
    case bool(Bool)
    case null
}

public struct JSONObject {
    public private(set) var members: [(key: String, value: JSONValue)] = []

    public init() {}

    mutating func append(_ key: String, _ value: JSONValue) {
        members.append((key: key, value: value))
    }

    /// Lookup by exact UTF-8 bytes.
    public subscript(key: String) -> JSONValue? {
        for m in members where bytesEqual(m.key, key) { return m.value }
        return nil
    }
}

public struct JSONParseError: Error, Equatable {
    public let message: String
    public let offset: Int
}

@inline(__always)
func bytesEqual(_ a: String, _ b: String) -> Bool {
    a.utf8.elementsEqual(b.utf8)
}

public enum StrictJSON {
    public static let maxDepth = 32
    public static let maxSafeInteger: Int64 = 9_007_199_254_740_991

    public static func parse(_ bytes: [UInt8]) throws -> JSONValue {
        var p = Parser(bytes)
        p.skipWhitespace()
        let v = try p.value(0)
        p.skipWhitespace()
        if p.pos != bytes.count { throw p.fail("trailing content") }
        return v
    }

    private struct Parser {
        let b: [UInt8]
        var pos = 0

        init(_ bytes: [UInt8]) { b = bytes }

        func fail(_ message: String) -> JSONParseError { JSONParseError(message: message, offset: pos) }

        mutating func skipWhitespace() {
            while pos < b.count, b[pos] == 0x20 || b[pos] == 0x09 || b[pos] == 0x0A || b[pos] == 0x0D { pos += 1 }
        }

        static func isDigit(_ c: UInt8) -> Bool { c >= 0x30 && c <= 0x39 }

        mutating func value(_ depth: Int) throws -> JSONValue {
            if depth > StrictJSON.maxDepth { throw fail("nesting too deep") }
            guard pos < b.count else { throw fail("unexpected end") }
            switch b[pos] {
            case 0x7B: return try object(depth)
            case 0x5B: return try array(depth)
            case 0x22: return .string(try string())
            case 0x74: try literal("true"); return .bool(true)
            case 0x66: try literal("false"); return .bool(false)
            case 0x6E: try literal("null"); return .null
            case 0x2D, 0x30...0x39: return try number()
            default: throw fail("unexpected character")
            }
        }

        mutating func literal(_ word: StaticString) throws {
            let n = word.utf8CodeUnitCount
            guard pos + n <= b.count else { throw fail("bad literal") }
            let p = word.utf8Start
            for i in 0..<n where b[pos + i] != p[i] { throw fail("bad literal") }
            pos += n
        }

        mutating func number() throws -> JSONValue {
            var negative = false
            if b[pos] == 0x2D { negative = true; pos += 1 }
            guard pos < b.count, Parser.isDigit(b[pos]) else { throw fail("bad number") }
            let digitsStart = pos
            if b[pos] == 0x30 {
                pos += 1
                if pos < b.count, Parser.isDigit(b[pos]) { throw fail("leading zero") }
            } else {
                while pos < b.count, Parser.isDigit(b[pos]) { pos += 1 }
            }
            if pos < b.count, b[pos] == 0x2E || b[pos] == 0x65 || b[pos] == 0x45 { throw fail("fraction or exponent (integers only)") }
            let digits = pos - digitsStart
            if negative && digits == 1 && b[digitsStart] == 0x30 { throw fail("negative zero") }
            if digits > 16 { throw fail("integer out of range") }
            var n: Int64 = 0
            for i in digitsStart..<pos { n = n * 10 + Int64(b[i] - 0x30) }
            if n > StrictJSON.maxSafeInteger { throw fail("integer out of range") }
            return .int(negative ? -n : n)
        }

        mutating func hex4() throws -> UInt32 {
            guard pos + 4 <= b.count else { throw fail("bad unicode escape") }
            var v: UInt32 = 0
            for _ in 0..<4 {
                let c = b[pos]
                let d: UInt32
                switch c {
                case 0x30...0x39: d = UInt32(c - 0x30)
                case 0x41...0x46: d = UInt32(c - 0x41 + 10)
                case 0x61...0x66: d = UInt32(c - 0x61 + 10)
                default: throw fail("bad unicode escape")
                }
                v = v * 16 + d
                pos += 1
            }
            return v
        }

        mutating func string() throws -> String {
            pos += 1
            var out = String.UnicodeScalarView()
            while true {
                guard pos < b.count else { throw fail("unterminated string") }
                let c = b[pos]
                if c == 0x22 { pos += 1; return String(out) }
                if c < 0x20 { throw fail("raw control character in string") }
                if c == 0x5C {
                    pos += 1
                    guard pos < b.count else { throw fail("unterminated escape") }
                    let e = b[pos]
                    pos += 1
                    switch e {
                    case 0x22: out.append("\"")
                    case 0x5C: out.append("\\")
                    case 0x2F: out.append("/")
                    case 0x62: out.append("\u{08}")
                    case 0x66: out.append("\u{0C}")
                    case 0x6E: out.append("\n")
                    case 0x72: out.append("\r")
                    case 0x74: out.append("\t")
                    case 0x75:
                        let u = try hex4()
                        if u >= 0xD800 && u <= 0xDBFF {
                            guard pos + 2 <= b.count, b[pos] == 0x5C, b[pos + 1] == 0x75 else { throw fail("lone surrogate") }
                            pos += 2
                            let low = try hex4()
                            guard low >= 0xDC00 && low <= 0xDFFF else { throw fail("lone surrogate") }
                            let cp = 0x10000 + ((u - 0xD800) << 10) + (low - 0xDC00)
                            out.append(Unicode.Scalar(cp)!)
                        } else if u >= 0xDC00 && u <= 0xDFFF {
                            throw fail("lone surrogate")
                        } else {
                            out.append(Unicode.Scalar(u)!)
                        }
                    default:
                        pos -= 1
                        throw fail("bad escape")
                    }
                    continue
                }
                if c < 0x80 { out.append(Unicode.Scalar(c)); pos += 1; continue }
                out.append(try multibyte())
            }
        }

        /// One well-formed UTF-8 sequence of two to four bytes (Unicode table 3-7): no overlong forms, no surrogates, nothing above U+10FFFF.
        mutating func multibyte() throws -> Unicode.Scalar {
            let lead = b[pos]
            let need: Int
            var lo: UInt8 = 0x80
            var hi: UInt8 = 0xBF
            var cp: UInt32
            switch lead {
            case 0xC2...0xDF: need = 1; cp = UInt32(lead & 0x1F)
            case 0xE0: need = 2; lo = 0xA0; cp = UInt32(lead & 0x0F)
            case 0xE1...0xEC, 0xEE...0xEF: need = 2; cp = UInt32(lead & 0x0F)
            case 0xED: need = 2; hi = 0x9F; cp = UInt32(lead & 0x0F)
            case 0xF0: need = 3; lo = 0x90; cp = UInt32(lead & 0x07)
            case 0xF1...0xF3: need = 3; cp = UInt32(lead & 0x07)
            case 0xF4: need = 3; hi = 0x8F; cp = UInt32(lead & 0x07)
            default: throw fail("invalid UTF-8")
            }
            guard pos + need < b.count else { throw fail("invalid UTF-8") }
            for i in 1...need {
                let cb = b[pos + i]
                let l = i == 1 ? lo : 0x80
                let h = i == 1 ? hi : 0xBF
                guard cb >= l && cb <= h else { throw fail("invalid UTF-8") }
                cp = (cp << 6) | UInt32(cb & 0x3F)
            }
            pos += need + 1
            guard let s = Unicode.Scalar(cp) else { throw fail("invalid UTF-8") }
            return s
        }

        mutating func object(_ depth: Int) throws -> JSONValue {
            pos += 1
            var obj = JSONObject()
            var seen = Set<[UInt8]>()
            skipWhitespace()
            if pos < b.count, b[pos] == 0x7D { pos += 1; return .object(obj) }
            while true {
                skipWhitespace()
                guard pos < b.count, b[pos] == 0x22 else { throw fail("object name expected") }
                let key = try string()
                if !seen.insert(Array(key.utf8)).inserted { throw fail("duplicate object name") }
                skipWhitespace()
                guard pos < b.count, b[pos] == 0x3A else { throw fail("':' expected") }
                pos += 1
                skipWhitespace()
                obj.append(key, try value(depth + 1))
                skipWhitespace()
                guard pos < b.count else { throw fail("unterminated object") }
                let c = b[pos]
                pos += 1
                if c == 0x2C { continue }
                if c == 0x7D { return .object(obj) }
                pos -= 1
                throw fail("',' or '}' expected")
            }
        }

        mutating func array(_ depth: Int) throws -> JSONValue {
            pos += 1
            var items: [JSONValue] = []
            skipWhitespace()
            if pos < b.count, b[pos] == 0x5D { pos += 1; return .array(items) }
            while true {
                skipWhitespace()
                items.append(try value(depth + 1))
                skipWhitespace()
                guard pos < b.count else { throw fail("unterminated array") }
                let c = b[pos]
                pos += 1
                if c == 0x2C { continue }
                if c == 0x5D { return .array(items) }
                pos -= 1
                throw fail("',' or ']' expected")
            }
        }
    }
}

/// Canonical string output: `"` and `\` escaped, every character below U+0020 as `\u00xx` (lower-case hex), all else raw UTF-8.
func appendJSONString(_ s: String, to out: inout [UInt8]) {
    out.append(0x22)
    let hex = Array("0123456789abcdef".utf8)
    for u in s.unicodeScalars {
        switch u.value {
        case 0x22: out.append(0x5C); out.append(0x22)
        case 0x5C: out.append(0x5C); out.append(0x5C)
        case 0..<0x20:
            out.append(contentsOf: [0x5C, 0x75, 0x30, 0x30, hex[Int(u.value >> 4)], hex[Int(u.value & 0xF)]])
        default: out.append(contentsOf: Array(String(u).utf8))
        }
    }
    out.append(0x22)
}
