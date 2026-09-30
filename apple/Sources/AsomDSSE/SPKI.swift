import AsomJSON
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// The one public-key encoding on the wire: SubjectPublicKeyInfo DER, exactly 91 bytes (LAB_SPEC.md section 4.5).
public enum SPKI {
    /// SEQUENCE { SEQUENCE { id-ecPublicKey, prime256v1 }, BIT STRING } up to the 0x04 of the uncompressed point.
    public static let prefix: [UInt8] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ]
    public static let length = 91

    /// Any other length or prefix, a compressed point, a trailing byte, another curve, or a point off the curve
    /// is ALG_UNSUPPORTED. The curve equation is checked here, before the crypto library sees the key.
    public static func validate(_ spki: [UInt8]) -> Result<Void, RejectCode> {
        guard spki.count == length, Array(spki[0..<prefix.count]) == prefix, spki[prefix.count] == 0x04 else {
            return .failure(.algUnsupported)
        }
        let x = Array(spki[27..<59])
        let y = Array(spki[59..<91])
        guard P256Curve.isOnCurve(x: x, y: y) else { return .failure(.algUnsupported) }
        return .success(())
    }

    static func publicKey(_ spki: [UInt8]) -> Result<P256.Signing.PublicKey, RejectCode> {
        if case let .failure(code) = validate(spki) { return .failure(code) }
        guard let key = try? P256.Signing.PublicKey(x963Representation: Array(spki[prefix.count...])) else {
            return .failure(.algUnsupported)
        }
        return .success(key)
    }

    static func encode(_ key: P256.Signing.PublicKey) -> [UInt8] {
        prefix + Array(key.x963Representation)
    }
}
