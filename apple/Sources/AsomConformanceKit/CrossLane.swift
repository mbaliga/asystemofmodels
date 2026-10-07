import AsomBenchCore
import AsomDSSE
import AsomJSON
import AsomManifest
import Foundation

/// Cross-lane fixtures: documents made by this lane's signer (`ManifestSigner`, `DSSEEnvelope.seal`) in the shape of the lab's M02 and
/// M03 vectors, so that the JVM lane can verify them through its `lines` mode (`apple/ci/crosslane.py`). Every vector's expectation is
/// written here by hand as an intent (`Intent`) and the generator refuses to write a fixture this lane's own verifier disagrees with.
/// The private scalars are read from `lab/conformance/keys/TEST-ONLY-keys.json` at generation time and never copied into a source file.
/// ES256 signatures are not deterministic, so regenerating changes the signature bytes; the payloads are deterministic.
public enum CrossLane {
    public static let nowMs: Int64 = 1_790_676_060_000
    static let confFloor = "0.2.0"

    enum Intent {
        case ok
        case reject(String)
    }

    struct Entry {
        let id: String
        let description: String
        let document: [UInt8]
        let context: JValue
        let nowMs: Int64
        let intent: Intent
    }

    public struct Summary {
        public let m02: Int
        public let m03: Int
        public let signatures: Int
    }

    struct Key {
        let signer: ES256Signer
        let spki: [UInt8]
        let nodeId: String
        let exportFingerprint: String
    }

    static func hex(_ text: String) throws -> [UInt8] { try R3.hexBytes(text) }

    static func loadKey(_ keys: JValue, _ name: String) throws -> Key {
        guard let entry = keys.member(name), let d = entry.member("d_hex")?.stringValue, let spkiB64 = entry.member("spki_b64")?.stringValue,
              let spki = Base64Strict.decodeEither(spkiB64), let id = entry.member("nodeId")?.stringValue,
              let fp = entry.member("exportFingerprint")?.stringValue else { throw ConformanceError("key \(name) in TEST-ONLY-keys.json") }
        let signer = try ES256Signer(rawScalar: try hex(d))
        guard signer.spki == spki, NodeIdentity.nodeId(spki: spki) == id else { throw ConformanceError("key \(name): spki or nodeId does not match the scalar") }
        return Key(signer: signer, spki: spki, nodeId: id, exportFingerprint: fp)
    }

    static func str(_ s: String) -> JValue { .string(s) }

    static func meshContext(pinned: [UInt8], challenge: String, requiredTier: String = "A0", production: Bool = false, rollback: JValue? = nil) -> JValue {
        var members: [JMember] = [
            JMember(name: "mode", value: str("MESH")), JMember(name: "pinnedSpkiB64", value: str(Base64Strict.encode(pinned))),
            JMember(name: "expectedChallengeB64u", value: str(challenge)),
        ]
        if let rollback { members.append(JMember(name: "rollback", value: rollback)) }
        members.append(JMember(name: "requiredTier", value: str(requiredTier)))
        members.append(JMember(name: "confFloor", value: str(confFloor)))
        members.append(JMember(name: "knownBadConf", value: .array([])))
        members.append(JMember(name: "productionKeys", value: .bool(production)))
        return .object(members)
    }

    static func fileContext(fingerprint: String? = nil, method: String? = nil) -> JValue {
        var members: [JMember] = [JMember(name: "mode", value: str("FILE"))]
        if let fingerprint, let method {
            members.append(JMember(name: "comparedFingerprint", value: str(fingerprint)))
            members.append(JMember(name: "compareMethod", value: str(method)))
        }
        members.append(JMember(name: "requiredTier", value: str("A0")))
        members.append(JMember(name: "confFloor", value: str(confFloor)))
        members.append(JMember(name: "knownBadConf", value: .array([])))
        members.append(JMember(name: "productionKeys", value: .bool(false)))
        return .object(members)
    }

    /// The own-form body of M02-114 (every rate 10^9, every byte field 2^50): the one lab accept vector whose `results` both lanes
    /// agree on (F-1 shadows the drift flag in the others).
    static func baseOwnBody(labDir: String) throws -> JValue {
        for v in try R3.loadVectors(family: "M02", in: labDir) where v.id == "M02-114" {
            let container = try Conformance.parseValue(try R3.documentBytes(v.input))
            guard let b64 = container.member("dsse")?.member("payload")?.stringValue, let payload = Base64Strict.decodeEither(b64) else { break }
            guard let body = try Conformance.parseValue(payload).member("body") else { break }
            return body
        }
        throw ConformanceError("M02-114 is not in \(labDir)")
    }

    static func setting(_ v: JValue, _ path: [String], to value: JValue) -> JValue {
        guard let first = path.first, case let .object(members) = v else { return value }
        let rest = Array(path.dropFirst())
        return .object(members.map { codeUnitsEqual($0.name, first) ? JMember(name: $0.name, value: setting($0.value, rest, to: value)) : $0 })
    }

    static func payload(body: JValue, presentation: JValue) throws -> [UInt8] {
        try JCS.serialize(.object([
            JMember(name: "schema", value: str("asom.manifest/1")), JMember(name: "schemaMinor", value: .int(0)),
            JMember(name: "body", value: body), JMember(name: "presentation", value: presentation),
        ]))
    }

    static func ownPresentation(now: Int64, challenge: String) -> JValue {
        .object([JMember(name: "issuedAtMs", value: .int(now)), JMember(name: "expiresAtMs", value: .int(now + ManifestSigner.ownTtlMs)),
                 JMember(name: "challenge", value: str(challenge))])
    }

    public static func generate(labDir: String, into dir: String) throws -> Summary {
        let keys = try Conformance.loadJSON(labDir + "/keys/TEST-ONLY-keys.json")
        let key1 = try loadKey(keys, "key1"), key2 = try loadKey(keys, "key2"), key3 = try loadKey(keys, "key3"), key4 = try loadKey(keys, "key4")
        guard let challengeText = keys.member("challenge1_b64u")?.stringValue, let challenge = Base64Strict.decodeURLNoPad(challengeText) else {
            throw ConformanceError("challenge1_b64u")
        }
        let base = try baseOwnBody(labDir: labDir)
        let check = ManifestSigner.SelfCheck(confFloor: confFloor, productionKeys: false)
        var accepts: [Entry] = [], rejects: [Entry] = []
        var signatures = 0

        func own(_ key: Key = key1, now: Int64 = nowMs, body: JValue = base) throws -> ManifestSigner.Signed {
            signatures += 1
            return try ManifestSigner.signPresentation(bodyOwn: body, audience: .own, challenge: challenge, nodeKey: key.signer, nowMs: now, check: check)
        }
        func file(_ key: Key, body: JValue = base) throws -> ManifestSigner.Signed {
            signatures += 1
            return try ManifestSigner.signPresentation(bodyOwn: body, audience: .file, challenge: nil, nodeKey: nil, exportKey: key.signer, nowMs: nowMs, check: check)
        }
        let mesh1 = meshContext(pinned: key1.spki, challenge: challengeText)

        // M02: accepted documents.
        let first = try own()
        accepts.append(Entry(id: "M02-901", description: "Swift signer, MESH, key1, challenge1, own audience, signPresentation: accepted, PINNED.", document: first.document, context: mesh1, nowMs: nowMs, intent: .ok))
        let twinDoc = try twin(first.document)
        accepts.append(Entry(id: "M02-902", description: "The high-S twin of M02-901 made from the Swift signature (s replaced by n - s): accepted.", document: twinDoc, context: mesh1, nowMs: nowMs, intent: .ok))
        let noSpki = try DSSEEnvelope.seal(payload: first.payload, signer: key1.signer, includeSignerSpki: false)
        signatures += 1
        accepts.append(Entry(id: "M02-903", description: "The same payload sealed by the Swift signer without signer.spki (a MESH sender may omit it): accepted.", document: noSpki, context: mesh1, nowMs: nowMs, intent: .ok))
        for k in 0..<16 {
            let again = try own()
            accepts.append(Entry(id: String(format: "M02-%03d", 910 + k), description: "Swift signer, fresh signature \(k + 1) of 16 over the same body (ECDSA nonces differ; every one must verify): accepted.", document: again.document, context: mesh1, nowMs: nowMs, intent: .ok))
        }
        // ERR-FX-CV3: only A0 is proven, so requiredTier A1 is TIER_INSUFFICIENT. The accept entry keeps its purpose (a strongbox claim in the body is a label, not a gate) at requiredTier A0.
        let tierA1 = meshContext(pinned: key1.spki, challenge: challengeText, requiredTier: "A0")
        accepts.append(Entry(id: "M02-930", description: "Swift signer, requiredTier A0 with a strongbox-claiming body (labelled A1, a label and never a gate): accepted.", document: try own().document, context: tierA1, nowMs: nowMs, intent: .ok))
        let fileDoc3 = try file(key3)
        accepts.append(Entry(id: "M02-940", description: "Swift signer, FILE, per-export key3, fingerprint not compared: SIGNER_UNVERIFIED.", document: fileDoc3.document, context: fileContext(), nowMs: nowMs, intent: .ok))
        accepts.append(Entry(id: "M02-941", description: "Swift signer, FILE, key3, the export fingerprint typed exactly: PINNED_BY_FINGERPRINT(typed).", document: fileDoc3.document, context: fileContext(fingerprint: key3.exportFingerprint, method: "typed"), nowMs: nowMs, intent: .ok))
        let fileDoc4 = try file(key4)
        let scanned = key4.exportFingerprint.lowercased().enumerated().map { ($0.offset > 0 && $0.offset % 4 == 0 ? " " : "") + String($0.element) }.joined()
        accepts.append(Entry(id: "M02-942", description: "Swift signer, FILE, key4, the fingerprint scanned (lowercase, spaces, no hyphens): PINNED_BY_FINGERPRINT(qr).", document: fileDoc4.document, context: fileContext(fingerprint: scanned, method: "qr"), nowMs: nowMs, intent: .ok))

        // M03: rejected documents.
        let body2 = setting(base, ["subject", "nodeId"], to: str(key2.nodeId))
        let signedByKey2 = try signPresentationForKey2(body2, key2: key2, challenge: challenge, check: check)
        signatures += 1
        rejects.append(Entry(id: "M03-901", description: "Swift signer: a complete, valid presentation by key2 while key1 is pinned: the keyid is not the pinned node.", document: signedByKey2, context: mesh1, nowMs: nowMs, intent: .reject("KEY_NOT_PINNED")))
        rejects.append(Entry(id: "M03-902", description: "Swift signer: nowMs equals expiresAtMs: EXPIRED.", document: first.document, context: mesh1, nowMs: nowMs + ManifestSigner.ownTtlMs, intent: .reject("EXPIRED")))
        let wrongChallenge = Base64Strict.encodeURL([UInt8](repeating: 0x42, count: 32))
        rejects.append(Entry(id: "M03-903", description: "Swift signer: the requester expected another challenge: NONCE_MISMATCH.", document: first.document, context: meshContext(pinned: key1.spki, challenge: wrongChallenge), nowMs: nowMs, intent: .reject("NONCE_MISMATCH")))
        rejects.append(Entry(id: "M03-904", description: "Swift signer: a production verifier refuses a TEST-ONLY key.", document: first.document, context: meshContext(pinned: key1.spki, challenge: challengeText, production: true), nowMs: nowMs, intent: .reject("TEST_ONLY_KEY")))
        rejects.append(Entry(id: "M03-905", description: "Swift signer, FILE, key3: a typed fingerprint that is not the export fingerprint: FINGERPRINT_MISMATCH.", document: fileDoc3.document, context: fileContext(fingerprint: key4.exportFingerprint, method: "typed"), nowMs: nowMs, intent: .reject("FINGERPRINT_MISMATCH")))
        rejects.append(Entry(id: "M03-906", description: "Swift signer: one byte of the signature changed after signing: SIGNATURE_INVALID.", document: try tamperSignature(first.document), context: mesh1, nowMs: nowMs, intent: .reject("SIGNATURE_INVALID")))
        rejects.append(Entry(id: "M03-907", description: "Swift signer: the payload changed (seq 17 to 18) after signing: SIGNATURE_INVALID.", document: try tamperPayload(first.payload, document: first.document), context: mesh1, nowMs: nowMs, intent: .reject("SIGNATURE_INVALID")))
        rejects.append(Entry(id: "M03-908", description: "Swift signer: issuedAtMs is more than 300 s ahead of the verifier's clock: NOT_YET_VALID.", document: first.document, context: mesh1, nowMs: nowMs - 300_001, intent: .reject("NOT_YET_VALID")))
        rejects.append(Entry(id: "M03-909", description: "Swift signer: requiredTier A2 and the body claims strongbox (A1): TIER_INSUFFICIENT.", document: first.document, context: meshContext(pinned: key1.spki, challenge: challengeText, requiredTier: "A2"), nowMs: nowMs, intent: .reject("TIER_INSUFFICIENT")))
        let loose = try prettyPayload(first.payload)
        rejects.append(Entry(id: "M03-910", description: "Swift signer: a validly signed payload that is not in JCS form (indented): NON_CANONICAL.", document: try DSSEEnvelope.seal(payload: loose, signer: key1.signer), context: mesh1, nowMs: nowMs, intent: .reject("NON_CANONICAL")))
        signatures += 1
        let strangerBody = setting(base, ["subject", "nodeId"], to: str(key2.nodeId))
        rejects.append(Entry(id: "M03-911", description: "Swift signer: body.subject.nodeId names key2 but key1 signed: SUBJECT_KEY_MISMATCH.", document: try DSSEEnvelope.seal(payload: try payload(body: strangerBody, presentation: ownPresentation(now: nowMs, challenge: challengeText)), signer: key1.signer), context: mesh1, nowMs: nowMs, intent: .reject("SUBJECT_KEY_MISMATCH")))
        signatures += 1
        let digest = Base64Strict.encodeURL([UInt8](repeating: 0, count: 32))
        let nodeKey1 = key1.nodeId + "|own"
        rejects.append(Entry(id: "M03-912", description: "Swift signer: the rollback store holds seq 18 for this node, the document has seq 17: ROLLBACK.", document: first.document, context: meshContext(pinned: key1.spki, challenge: challengeText, rollback: .object([JMember(name: nodeKey1, value: .object([JMember(name: "seq", value: .int(18)), JMember(name: "bodyDigest", value: str(digest))]))])), nowMs: nowMs, intent: .reject("ROLLBACK")))
        rejects.append(Entry(id: "M03-913", description: "Swift signer: the store holds seq 17 with another body digest: EQUIVOCATION.", document: first.document, context: meshContext(pinned: key1.spki, challenge: challengeText, rollback: .object([JMember(name: nodeKey1, value: .object([JMember(name: "seq", value: .int(17)), JMember(name: "bodyDigest", value: str(digest))]))])), nowMs: nowMs, intent: .reject("EQUIVOCATION")))
        rejects.append(Entry(id: "M03-914", description: "Swift signer: a FILE document presented in a MESH context with no challenge to match: NONCE_MISMATCH.", document: fileDoc3.document, context: meshContext(pinned: key3.spki, challenge: challengeText), nowMs: nowMs, intent: .reject("NONCE_MISMATCH")))

        try write(family: "M02", entries: accepts, specRefs: ["LAB_SPEC.md 4.6", "LAB_SPEC.md 4.7"], to: dir + "/manifest/M02-swift-signed.json", labDir: labDir)
        try write(family: "M03", entries: rejects, specRefs: ["LAB_SPEC.md 4.6", "LAB_SPEC.md 4.7"], to: dir + "/manifest/M03-swift-signed.json", labDir: labDir)
        try "0.2.0\n".write(toFile: dir + "/VERSION", atomically: true, encoding: .utf8)
        return Summary(m02: accepts.count, m03: rejects.count, signatures: signatures)
    }

    static func signPresentationForKey2(_ body: JValue, key2: Key, challenge: [UInt8], check: ManifestSigner.SelfCheck) throws -> [UInt8] {
        try ManifestSigner.signPresentation(bodyOwn: body, audience: .own, challenge: challenge, nodeKey: key2.signer, nowMs: nowMs, check: check).document
    }

    // MARK: Tampering

    static func containerParts(_ document: [UInt8]) throws -> (container: JValue, payload: String, sig: String) {
        let c = try Conformance.parseValue(document)
        guard let p = c.member("dsse")?.member("payload")?.stringValue, let s = c.member("dsse")?.member("signatures")?.elements?.first?.member("sig")?.stringValue else {
            throw ConformanceError("container shape")
        }
        return (c, p, s)
    }

    static func replaceDsse(_ document: [UInt8], payload: String? = nil, sig: String? = nil) throws -> [UInt8] {
        let (container, p, s) = try containerParts(document)
        guard case let .object(top) = container, case let .object(dsse)? = container.member("dsse"), let sigs = container.member("dsse")?.member("signatures")?.elements,
              case let .object(sigMembers)? = sigs.first else { throw ConformanceError("container shape") }
        let newSigs: JValue = .array([.object(sigMembers.map { $0.name == "sig" ? JMember(name: "sig", value: str(sig ?? s)) : $0 })])
        let newDsse: JValue = .object(dsse.map { m in
            switch m.name {
            case "payload": return JMember(name: "payload", value: str(payload ?? p))
            case "signatures": return JMember(name: "signatures", value: newSigs)
            default: return m
            }
        })
        return try JCS.serialize(.object(top.map { $0.name == "dsse" ? JMember(name: "dsse", value: newDsse) : $0 }))
    }

    static func twin(_ document: [UInt8]) throws -> [UInt8] {
        let (_, _, s) = try containerParts(document)
        guard let raw = Base64Strict.decodeEither(s) else { throw ConformanceError("sig") }
        return try replaceDsse(document, sig: Base64Strict.encode(SignatureCodec.flipS(raw)))
    }

    static func tamperSignature(_ document: [UInt8]) throws -> [UInt8] {
        let (_, _, s) = try containerParts(document)
        guard var raw = Base64Strict.decodeEither(s) else { throw ConformanceError("sig") }
        raw[63] ^= 0x01
        return try replaceDsse(document, sig: Base64Strict.encode(raw))
    }

    static func tamperPayload(_ payload: [UInt8], document: [UInt8]) throws -> [UInt8] {
        let before = Array("\"seq\":17".utf8), after = Array("\"seq\":18".utf8)
        guard let at = find(before, in: payload) else { throw ConformanceError("no seq 17 in the payload") }
        var changed = payload
        changed.replaceSubrange(at..<(at + before.count), with: after)
        return try replaceDsse(document, payload: Base64Strict.encode(changed))
    }

    static func find(_ needle: [UInt8], in hay: [UInt8]) -> Int? {
        guard !needle.isEmpty, hay.count >= needle.count else { return nil }
        for i in 0...(hay.count - needle.count) where Array(hay[i..<(i + needle.count)]) == needle { return i }
        return nil
    }

    static func prettyPayload(_ payload: [UInt8]) throws -> [UInt8] {
        Array(PrettyJSON.render(try Conformance.parseValue(payload)).utf8)
    }

    // MARK: Output

    static func write(family: String, entries: [Entry], specRefs: [String], to path: String, labDir: String) throws {
        var vectors: [JValue] = []
        for e in entries {
            let input: JValue = .object([
                JMember(name: "document", value: str(String(decoding: e.document, as: UTF8.self))), JMember(name: "context", value: e.context),
                JMember(name: "nowMs", value: .int(e.nowMs)),
            ])
            let vector = R3.Vector(id: e.id, family: family, file: "", description: e.description, input: input, expect: .reject(""))
            let observed = R3.observe(vector)
            let expect: JValue
            switch (observed, e.intent) {
            case (.ok(let value), .ok): expect = .object([JMember(name: "ok", value: value)])
            case let (.reject(code), .reject(want)) where code.rawValue == want: expect = .object([JMember(name: "reject", value: str(want))])
            default: throw ConformanceError("\(e.id): this lane's verifier says \(observed), the intent was \(e.intent)")
            }
            vectors.append(.object([
                JMember(name: "id", value: str(e.id)), JMember(name: "origin", value: str("generated")), JMember(name: "status", value: str("normative")),
                JMember(name: "oracle", value: str("self")), JMember(name: "description", value: str(e.description)), JMember(name: "input", value: input),
                JMember(name: "expect", value: expect),
            ]))
        }
        let file: JValue = .object([
            JMember(name: "family", value: str(family)), JMember(name: "confVersion", value: str("0.2.0")),
            JMember(name: "specRefs", value: .array(specRefs.map(str))), JMember(name: "vectors", value: .array(vectors)),
        ])
        try FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
        try (PrettyJSON.render(file) + "\n").write(toFile: path, atomically: true, encoding: .utf8)
    }
}

/// Two-space indented JSON for files a human reviews. Not a canonical form: never used for anything that is signed or hashed.
enum PrettyJSON {
    static func render(_ v: JValue, indent: Int = 0) -> String {
        let pad = String(repeating: " ", count: indent + 2), close = String(repeating: " ", count: indent)
        switch v {
        case let .object(members):
            if members.isEmpty { return "{}" }
            return "{\n" + members.map { pad + leaf(.string($0.name)) + ": " + render($0.value, indent: indent + 2) }.joined(separator: ",\n") + "\n" + close + "}"
        case let .array(items):
            if items.isEmpty { return "[]" }
            if items.allSatisfy({ if case .string = $0 { return true } else { return false } }) && items.count <= 4 {
                return "[" + items.map(leaf).joined(separator: ", ") + "]"
            }
            return "[\n" + items.map { pad + render($0, indent: indent + 2) }.joined(separator: ",\n") + "\n" + close + "]"
        default:
            return leaf(v)
        }
    }

    static func leaf(_ v: JValue) -> String {
        String(decoding: (try? JCS.serialize(v)) ?? [], as: UTF8.self)
    }
}
