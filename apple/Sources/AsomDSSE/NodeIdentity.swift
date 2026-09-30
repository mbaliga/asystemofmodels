import AsomJSON
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// Pins, node ids, tags and fingerprints (LAB_SPEC.md section 4.5).
public enum NodeIdentity {
    /// The nodeIds of the published TEST-ONLY keys. A production verifier refuses them (TEST_ONLY_KEY).
    public static let testOnlyNodeIds: Set<String> = [
        "vaw93hb8yBZTX2LebYgzri1pfOnBlRIILoBLiyK_eN4",
        "idgZ8sjS2Fz_gsDBjfMRF3rH08Z6lwdee3U3OoPlVGE",
    ]

    /// SHA-256 of the SPKI DER.
    public static func pin(spki: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: spki)) }

    /// base64url(pin), no padding: 43 characters.
    public static func nodeId(spki: [UInt8]) -> String { Base64Strict.encodeURL(pin(spki: spki)) }

    /// First 16 characters of lowercase base32 of the pin (80 bits). For ledger rows and UI; never for authorisation.
    public static func nodeTag(spki: [UInt8]) -> String {
        String(base32(pin(spki: spki)).prefix(16)).lowercased()
    }

    /// Node display fingerprint (pairing, Peers tab): nodeTag uppercased in groups of four, XWWD-3XQW-7TEB-MU27.
    public static func displayFingerprint(spki: [UInt8]) -> String {
        group(Array(nodeTag(spki: spki).uppercased()), sizes: [4, 4, 4, 4])
    }

    /// Export fingerprint (FILE context): base32 of the first 16 bytes of the pin, 26 characters, no separators.
    public static func exportFingerprint(spki: [UInt8]) -> String {
        base32(Array(pin(spki: spki).prefix(16)))
    }

    /// The export fingerprint in groups of 5-5-4-4-4-4, XWWD3-XQW7T-EBMU-27ML-PG3C-BTVY.
    public static func exportFingerprintDisplay(spki: [UInt8]) -> String {
        group(Array(exportFingerprint(spki: spki)), sizes: [5, 5, 4, 4, 4, 4])
    }

    /// Uppercase (ASCII only), delete '-' and ' '. No other normalisation: a non-ASCII character survives
    /// and therefore never matches, where Unicode uppercasing could turn one into a base32 letter.
    public static func normalizeTypedFingerprint(_ text: String) -> [UInt8] {
        var out: [UInt8] = []
        for c in text.utf8 where c != 0x2D && c != 0x20 {
            out.append(c >= 0x61 && c <= 0x7A ? c - 0x20 : c)
        }
        return out
    }

    /// Constant-time comparison of what the user typed or scanned with the export fingerprint of `spki`.
    public static func fingerprintMatches(_ typed: String, spki: [UInt8]) -> Bool {
        let a = normalizeTypedFingerprint(typed)
        let b = Array(exportFingerprint(spki: spki).utf8)
        var diff = UInt8(truncatingIfNeeded: a.count ^ b.count)
        for k in 0..<b.count { diff |= (k < a.count ? a[k] : 0) ^ b[k] }
        return diff == 0
    }

    private static let base32Alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

    /// RFC 4648 base32, uppercase, no padding.
    static func base32(_ bytes: [UInt8]) -> String {
        var out = ""
        var buffer: UInt32 = 0
        var bits = 0
        for byte in bytes {
            buffer = buffer << 8 | UInt32(byte)
            bits += 8
            while bits >= 5 {
                out.append(base32Alphabet[Int(buffer >> UInt32(bits - 5) & 0x1F)])
                bits -= 5
            }
            buffer &= (1 << UInt32(bits)) - 1
        }
        if bits > 0 { out.append(base32Alphabet[Int(buffer << UInt32(5 - bits) & 0x1F)]) }
        return out
    }

    private static func group(_ chars: [Character], sizes: [Int]) -> String {
        var parts: [String] = []
        var start = 0
        for size in sizes {
            parts.append(String(chars[start..<(start + size)]))
            start += size
        }
        return parts.joined(separator: "-")
    }
}
