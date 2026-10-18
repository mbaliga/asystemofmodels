#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation

/// ES256 over DSSE PAE, the asom manifest profile (design brief §5.1): one signature, 64-octet r||s,
/// producers emit low-S, verifiers accept high-S; strict 91-byte P-256 SPKI (trust T1).
public enum AsomDSSE {
    /// DER prefix of a namedCurve P-256 SubjectPublicKeyInfo (26 bytes); followed by 04||X||Y (65 bytes).
    public static let spkiPrefix: [UInt8] = [0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02,
                                             0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
                                             0x42, 0x00]
    /// P-256 group order n, big-endian.
    static let n: [UInt8] = hex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")

    public enum Failure: Error, Equatable { case spkiNotStrictP256, badKey, badSignatureEncoding }

    /// Accepts standard or URL-safe base64, with or without padding (DSSE protocol.md).
    public static func b64(_ s: String) -> [UInt8]? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t).map { [UInt8]($0) }
    }

    /// PAE(type, body) = "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body
    public static func pae(payloadType: String, payload: [UInt8]) -> [UInt8] {
        let t = Array(payloadType.utf8)
        return Array("DSSEv1 \(t.count) ".utf8) + t + Array(" \(payload.count) ".utf8) + payload
    }

    /// T1: exact 91-byte SPKI, uncompressed point only; never re-encoded.
    public static func publicKey(spkiDER: [UInt8]) throws -> P256.Signing.PublicKey {
        guard spkiDER.count == 91, Array(spkiDER[0..<26]) == spkiPrefix, spkiDER[26] == 0x04 else {
            throw Failure.spkiNotStrictP256
        }
        do { return try P256.Signing.PublicKey(x963Representation: spkiDER[26...]) } catch { throw Failure.badKey }
    }

    /// Verifies a 64-octet r||s ES256 signature over PAE. High-S is accepted (profile rule).
    public static func verify(spkiDER: [UInt8], payloadType: String, payload: [UInt8], sigRaw: [UInt8]) throws -> Bool {
        let key = try publicKey(spkiDER: spkiDER)
        guard sigRaw.count == 64 else { throw Failure.badSignatureEncoding }
        let sig: P256.Signing.ECDSASignature
        do { sig = try P256.Signing.ECDSASignature(rawRepresentation: sigRaw) } catch { throw Failure.badSignatureEncoding }
        return key.isValidSignature(sig, for: pae(payloadType: payloadType, payload: payload))
    }

    public static func isHighS(_ sigRaw: [UInt8]) -> Bool { compare(Array(sigRaw[32..<64]), halfN) > 0 }

    /// Producer rule: emit low-S. s' = n - s when s > n/2.
    public static func lowS(_ sigRaw: [UInt8]) -> [UInt8] {
        guard isHighS(sigRaw) else { return sigRaw }
        return Array(sigRaw[0..<32]) + sub(n, Array(sigRaw[32..<64]))
    }
    /// Test helper: the high-S twin of a signature (n - s, whatever s is).
    public static func flipS(_ sigRaw: [UInt8]) -> [UInt8] { Array(sigRaw[0..<32]) + sub(n, Array(sigRaw[32..<64])) }

    // --- 256-bit big-endian helpers (no dependency) ---
    static let halfN: [UInt8] = shr1(n)
    static func hex(_ s: String) -> [UInt8] {
        var out = [UInt8](); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2); out.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return out
    }
    static func compare(_ a: [UInt8], _ b: [UInt8]) -> Int {
        for k in 0..<32 where a[k] != b[k] { return a[k] < b[k] ? -1 : 1 }
        return 0
    }
    static func sub(_ a: [UInt8], _ b: [UInt8]) -> [UInt8] {   // a - b, a >= b
        var r = [UInt8](repeating: 0, count: 32); var borrow = 0
        for k in stride(from: 31, through: 0, by: -1) {
            var d = Int(a[k]) - Int(b[k]) - borrow
            borrow = d < 0 ? 1 : 0; if d < 0 { d += 256 }
            r[k] = UInt8(d)
        }
        return r
    }
    static func shr1(_ a: [UInt8]) -> [UInt8] {
        var r = [UInt8](repeating: 0, count: 32); var carry: UInt8 = 0
        for k in 0..<32 { r[k] = (a[k] >> 1) | (carry << 7); carry = a[k] & 1 }
        return r
    }
}
