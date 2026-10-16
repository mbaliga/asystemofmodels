// Strict base64 (SCHEMA section 2): RFC 4648 section 4 alphabet, padding required, canonical (no stray trailing bits), no whitespace.

public enum StrictBase64 {
    private static let alphabet: [UInt8] = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".utf8)

    public static func encode(_ data: [UInt8]) -> String {
        var out = [UInt8]()
        out.reserveCapacity((data.count + 2) / 3 * 4)
        var i = 0
        while i + 2 < data.count {
            let n = (UInt32(data[i]) << 16) | (UInt32(data[i + 1]) << 8) | UInt32(data[i + 2])
            out.append(alphabet[Int((n >> 18) & 63)])
            out.append(alphabet[Int((n >> 12) & 63)])
            out.append(alphabet[Int((n >> 6) & 63)])
            out.append(alphabet[Int(n & 63)])
            i += 3
        }
        let rest = data.count - i
        if rest == 1 {
            let n = UInt32(data[i]) << 16
            out.append(alphabet[Int((n >> 18) & 63)])
            out.append(alphabet[Int((n >> 12) & 63)])
            out.append(0x3D)
            out.append(0x3D)
        } else if rest == 2 {
            let n = (UInt32(data[i]) << 16) | (UInt32(data[i + 1]) << 8)
            out.append(alphabet[Int((n >> 18) & 63)])
            out.append(alphabet[Int((n >> 12) & 63)])
            out.append(alphabet[Int((n >> 6) & 63)])
            out.append(0x3D)
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Nil unless [text] is canonical strict base64.
    public static func decode(_ text: String) -> [UInt8]? {
        let s = Array(text.utf8)
        if s.count % 4 != 0 { return nil }
        var out = [UInt8]()
        out.reserveCapacity(s.count / 4 * 3)
        var i = 0
        while i < s.count {
            var vals = [UInt32](repeating: 0, count: 4)
            var pad = 0
            for j in 0..<4 {
                let c = s[i + j]
                if c == 0x3D {
                    // '=' only in the last two positions of the last group, and once seen only '=' may follow
                    if i + 4 != s.count || j < 2 { return nil }
                    pad += 1
                } else {
                    if pad > 0 { return nil }
                    guard let v = value(c) else { return nil }
                    vals[j] = v
                }
            }
            let n = (vals[0] << 18) | (vals[1] << 12) | (vals[2] << 6) | vals[3]
            out.append(UInt8((n >> 16) & 0xFF))
            if pad < 2 { out.append(UInt8((n >> 8) & 0xFF)) }
            if pad < 1 { out.append(UInt8(n & 0xFF)) }
            i += 4
        }
        return encode(out).utf8.elementsEqual(text.utf8) ? out : nil
    }

    private static func value(_ c: UInt8) -> UInt32? {
        switch c {
        case 0x41...0x5A: return UInt32(c - 0x41)
        case 0x61...0x7A: return UInt32(c - 0x61 + 26)
        case 0x30...0x39: return UInt32(c - 0x30 + 52)
        case 0x2B: return 62
        case 0x2F: return 63
        default: return nil
        }
    }
}
