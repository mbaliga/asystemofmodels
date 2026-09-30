import Foundation
import X509
import SwiftASN1

let dir = URL(fileURLWithPath: CommandLine.arguments[1])
func load(_ n: String) throws -> [UInt8] { [UInt8](try Data(contentsOf: dir.appendingPathComponent(n))) }
let nik = try Certificate(derEncoded: try load("nik.der"))
let leaf = try Certificate(derEncoded: try load("leaf.der"))
print("nik subject:", nik.subject, "sigAlg:", nik.signatureAlgorithm)
print("leaf issuer:", leaf.issuer, "version:", leaf.version)
print("leaf signed by nik key:", nik.publicKey.isValidSignature(leaf.signature, for: leaf))
print("leaf self-signed (must be false):", leaf.publicKey.isValidSignature(leaf.signature, for: leaf))
print("nik self-signed:", nik.publicKey.isValidSignature(nik.signature, for: nik))
// Pin input: SPKI DER. Compare the library's SPKI encoding with openssl's extraction of the same key.
var ser = DER.Serializer()
try ser.serialize(leaf.publicKey)
let spki = ser.serializedBytes
let ossl = try load("leaf-spki.der")
print("SPKI bytes:", spki.count, "equal to openssl extraction:", spki == ossl)
print("tbsCertificateBytes available:", leaf.tbsCertificateBytes.count, "bytes")
// Tamper: flip one TBS byte in the DER and re-parse; the signature must no longer verify.
var der = try load("leaf.der"); der[40] ^= 0x01
if let t = try? Certificate(derEncoded: der) {
    print("tampered leaf verifies (must be false):", nik.publicKey.isValidSignature(t.signature, for: t))
} else { print("tampered leaf rejected at parse") }
