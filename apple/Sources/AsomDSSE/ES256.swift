import AsomJSON
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// ES256 (ECDSA P-256 with SHA-256) is the only algorithm (LAB_SPEC.md section 4.4).
public enum ES256 {
    /// Order of checks, as in verifier steps 7 and 8: the key (ALG_UNSUPPORTED), the signature length
    /// (SIGNATURE_ENCODING), 0 < r < n and 0 < s < n (SIGNATURE_INVALID), then the signature itself.
    /// High-S is accepted (M02-105).
    public static func verify(spki: [UInt8], message: [UInt8], signature: [UInt8]) -> Result<Void, RejectCode> {
        let key: P256.Signing.PublicKey
        switch SPKI.publicKey(spki) {
        case let .failure(code): return .failure(code)
        case let .success(k): key = k
        }
        guard signature.count == SignatureCodec.rawLength else { return .failure(.signatureEncoding) }
        let r = U256(bigEndian: Array(signature[0..<32]))
        let s = U256(bigEndian: Array(signature[32..<64]))
        guard !r.isZero, r < P256Curve.n, !s.isZero, s < P256Curve.n else { return .failure(.signatureInvalid) }
        guard let parsed = try? P256.Signing.ECDSASignature(rawRepresentation: signature),
              key.isValidSignature(parsed, for: message) else { return .failure(.signatureInvalid) }
        return .success(())
    }
}

/// A P-256 signing key that emits wire signatures: raw r||s, low-S.
public struct ES256Signer {
    private let key: P256.Signing.PrivateKey
    public let spki: [UInt8]

    public enum Failure: Error, Equatable { case invalidScalar }

    /// A fresh per-export key (LAB_SPEC.md section 4.7). The caller discards it after one signature.
    public static func generateEphemeral() -> ES256Signer { ES256Signer(P256.Signing.PrivateKey()) }

    /// From a 32-byte big-endian private scalar, for TEST-ONLY keys and tests.
    public init(rawScalar: [UInt8]) throws {
        guard rawScalar.count == 32, let k = try? P256.Signing.PrivateKey(rawRepresentation: rawScalar) else {
            throw Failure.invalidScalar
        }
        self.init(k)
    }

    private init(_ key: P256.Signing.PrivateKey) {
        self.key = key
        self.spki = SPKI.encode(key.publicKey)
    }

    public func sign(_ message: [UInt8]) -> [UInt8] {
        SignatureCodec.normaliseLowS(signUnnormalised(message))
    }

    /// What the library returns: high-S about half the time. Exposed so that tests can prove the normaliser runs.
    func signUnnormalised(_ message: [UInt8]) -> [UInt8] {
        // The API only throws on hardware-backed keys.
        Array((try! key.signature(for: message)).rawRepresentation)
    }
}
