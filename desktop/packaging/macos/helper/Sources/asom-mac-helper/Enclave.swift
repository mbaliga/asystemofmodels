// UNVERIFIED: written on Linux, never compiled, never run on a Mac. The Secure Enclave path is assumption AM01 (spike S-M1, owner device).
#if os(macOS)
import CryptoKit
import Foundation

/// CryptoKit Secure Enclave P-256 (macos.md 3.5, 5). No access-control flags: the node identity key never requires user presence
/// (trust.md 2.5). The helper holds no key between requests: every signature arrives with its blob, which the node keeps in its
/// protected container, so the helper is not a signing oracle for another process that merely spawns it.
enum Enclave {
    static var isAvailable: Bool { SecureEnclave.isAvailable }

    /// (dataRepresentation blob, DER SubjectPublicKeyInfo).
    static func create() throws -> (blob: [UInt8], spki: [UInt8]) {
        let key = try SecureEnclave.P256.Signing.PrivateKey()
        return ([UInt8](key.dataRepresentation), [UInt8](key.publicKey.derRepresentation))
    }

    /// ECDSA over SHA-256(data), raw r||s (64 bytes).
    static func sign(blob: [UInt8], data: [UInt8]) throws -> [UInt8] {
        let key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: Data(blob))
        let signature = try key.signature(for: Data(data))
        return [UInt8](signature.rawRepresentation)
    }

    /// Signs a fixed string and verifies it with the key's own public half.
    static func selftest(blob: [UInt8]) throws -> Bool {
        let key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: Data(blob))
        let message = Data("asom-mac-helper selftest v1".utf8)
        let signature = try key.signature(for: message)
        return key.publicKey.isValidSignature(signature, for: message)
    }
}
#endif
