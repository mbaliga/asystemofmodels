import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public enum DSSE {
    /// DSSE v1 pre-authentication encoding: "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body (lengths in ASCII decimal bytes).
    public static func pae(payloadType: String, payload: Data) -> Data {
        let t = Data(payloadType.utf8)
        var out = Data("DSSEv1 \(t.count) ".utf8); out.append(t)
        out.append(Data(" \(payload.count) ".utf8)); out.append(payload)
        return out
    }
    /// Accepts standard or URL-safe base64, padded or not (DSSE protocol note, F17).
    public static func b64(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }
    /// The fixed 26-byte DER prefix of a P-256 namedCurve SubjectPublicKeyInfo (trust.md T1); the rest is 04||X||Y.
    public static let p256SpkiPrefix: [UInt8] = [0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,
                                                 0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00]
    /// Strict pin-canonical parse: exactly prefix + 65-byte uncompressed point (91 bytes), else nil.
    public static func p256Key(spki: Data) -> P256.Signing.PublicKey? {
        let b = [UInt8](spki)
        guard b.count == 91, Array(b[0..<26]) == p256SpkiPrefix, b[26] == 0x04 else { return nil }
        return try? P256.Signing.PublicKey(x963Representation: Data(b[26...]))
    }
    /// ES256 over PAE with a 64-octet r||s signature (C2). Producers emit low-S; verifiers accept high-S.
    public static func verifyES256(spki: Data, payloadType: String, payload: Data, rawSig: Data) -> Bool {
        guard rawSig.count == 64, let key = p256Key(spki: spki),
              let sig = try? P256.Signing.ECDSASignature(rawRepresentation: rawSig) else { return false }
        return key.isValidSignature(sig, for: pae(payloadType: payloadType, payload: payload))
    }
}
