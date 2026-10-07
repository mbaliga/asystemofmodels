/// Strict base64 (LAB_SPEC.md section 4.2, "b64either"). Foundation's decoder is not used: it is lenient about
/// whitespace and unused trailing bits, and it cannot be told to refuse a mixed alphabet.
public enum Base64Strict {
    private static let standard = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".utf8)
    private static let urlSafe = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".utf8)

    /// Standard alphabet with padding. What producers emit.
    public static func encode(_ bytes: [UInt8]) -> String { encode(bytes, alphabet: standard, pad: true) }

    /// URL-safe alphabet without padding (ids, digests, challenges).
    public static func encodeURL(_ bytes: [UInt8]) -> String { encode(bytes, alphabet: urlSafe, pad: false) }

    /// Accepts the standard or the URL-safe alphabet, padded or unpadded. Returns nil (ENCODING) on a mixed
    /// alphabet, whitespace, misplaced or miscounted padding, a length of 1 mod 4, or non-zero unused bits.
    public static func decodeEither(_ text: String) -> [UInt8]? {
        let chars = Array(text.utf8)
        var padCount = 0
        while padCount < chars.count, chars[chars.count - 1 - padCount] == 0x3D { padCount += 1 }
        guard padCount <= 2 else { return nil }
        let body = chars[0..<(chars.count - padCount)]
        var sawStandard = false
        var sawURL = false
        var values: [UInt8] = []
        values.reserveCapacity(body.count)
        for c in body {
            switch c {
            case 0x41...0x5A: values.append(c - 0x41)
            case 0x61...0x7A: values.append(c - 0x61 + 26)
            case 0x30...0x39: values.append(c - 0x30 + 52)
            case 0x2B: sawStandard = true; values.append(62)
            case 0x2F: sawStandard = true; values.append(63)
            case 0x2D: sawURL = true; values.append(62)
            case 0x5F: sawURL = true; values.append(63)
            default: return nil
            }
        }
        if sawStandard && sawURL { return nil }
        if padCount > 0 && (values.count + padCount) % 4 != 0 { return nil }
        if values.count % 4 == 1 { return nil }
        var out: [UInt8] = []
        out.reserveCapacity(values.count * 3 / 4)
        var k = 0
        while k + 4 <= values.count {
            let n = UInt32(values[k]) << 18 | UInt32(values[k + 1]) << 12 | UInt32(values[k + 2]) << 6 | UInt32(values[k + 3])
            out.append(UInt8(n >> 16 & 0xFF)); out.append(UInt8(n >> 8 & 0xFF)); out.append(UInt8(n & 0xFF))
            k += 4
        }
        switch values.count - k {
        case 2:
            guard values[k + 1] & 0x0F == 0 else { return nil }
            out.append(values[k] << 2 | values[k + 1] >> 4)
        case 3:
            guard values[k + 2] & 0x03 == 0 else { return nil }
            out.append(values[k] << 2 | values[k + 1] >> 4)
            out.append(values[k + 1] << 4 | values[k + 2] >> 2)
        default:
            break
        }
        return out
    }

    /// `b64url` (ids, digests, challenges): URL-safe alphabet only, no padding.
    public static func decodeURLNoPad(_ text: String) -> [UInt8]? {
        for c in text.utf8 where c == 0x3D || c == 0x2B || c == 0x2F { return nil }
        return decodeEither(text)
    }

    private static func encode(_ bytes: [UInt8], alphabet: [UInt8], pad: Bool) -> String {
        var out: [UInt8] = []
        var k = 0
        while k + 3 <= bytes.count {
            let n = UInt32(bytes[k]) << 16 | UInt32(bytes[k + 1]) << 8 | UInt32(bytes[k + 2])
            for shift in [18, 12, 6, 0] { out.append(alphabet[Int(n >> UInt32(shift) & 0x3F)]) }
            k += 3
        }
        switch bytes.count - k {
        case 1:
            let n = UInt32(bytes[k]) << 16
            out.append(alphabet[Int(n >> 18 & 0x3F)]); out.append(alphabet[Int(n >> 12 & 0x3F)])
            if pad { out.append(0x3D); out.append(0x3D) }
        case 2:
            let n = UInt32(bytes[k]) << 16 | UInt32(bytes[k + 1]) << 8
            out.append(alphabet[Int(n >> 18 & 0x3F)]); out.append(alphabet[Int(n >> 12 & 0x3F)])
            out.append(alphabet[Int(n >> 6 & 0x3F)])
            if pad { out.append(0x3D) }
        default:
            break
        }
        return String(decoding: out, as: UTF8.self)
    }
}
