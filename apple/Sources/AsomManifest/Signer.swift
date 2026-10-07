import AsomBenchCore
import AsomDSSE
import AsomJSON

/// The signer of LAB_SPEC.md section 4.7 (`signPresentation`). ES256 only: the DSSE container is produced by
/// `DSSEEnvelope.seal` (low-S, raw r||s, strict 91-byte SPKI, JCS integer profile), and every document is verified by
/// `ManifestVerifier` before it is returned, so a document this lane would reject is never handed out.
public enum ManifestSigner {
    public static let ownTtlMs: Int64 = 600_000
    public static let dayMs: Int64 = 86_400_000
    public static let challengeLength = 32

    public enum Failure: Error, Equatable, CustomStringConvertible {
        /// The challenge of an `own` presentation is missing or not 32 bytes.
        case challengeRequired
        /// `audience == file` was asked for without a per-export key to sign with (the caller never passes the node key for it).
        case missingExportKey
        /// The clock is before 1970; a day-truncated time would be negative.
        case clockInvalid
        /// A body the file projection refuses (the code is the verifier's).
        case projection(RejectCode)
        /// The self-check refused the document it had just made. Typed `MANIFEST_UNAVAILABLE` in the spec; nothing is sent.
        case manifestUnavailable(RejectCode)

        public var description: String {
            switch self {
            case .challengeRequired: return "MANIFEST_UNAVAILABLE: an own presentation needs a 32-byte challenge"
            case .missingExportKey: return "MANIFEST_UNAVAILABLE: a file export needs a per-export key"
            case .clockInvalid: return "MANIFEST_UNAVAILABLE: the clock is before 1970"
            case let .projection(code): return "MANIFEST_UNAVAILABLE: the file projection refused the body (\(code.rawValue))"
            case let .manifestUnavailable(code): return "MANIFEST_UNAVAILABLE: self-check reject \(code.rawValue)"
            }
        }
    }

    public struct Signed: Sendable {
        /// The container, in JCS form, ready to put on the wire or in a file.
        public let document: [UInt8]
        public let payload: [UInt8]
        public let audience: Audience
        /// The signer's SPKI (the node key for `own`, the per-export key for `file`).
        public let signerSpki: [UInt8]
        public let nodeId: String
        /// For `file`: the fingerprint of the export key, for the export screen (design 5.8). Nil for `own`.
        public let exportFingerprint: String?
        public let exportFingerprintDisplay: String?
    }

    /// What the self-check needs besides the document itself (the verifier's context for the same audience).
    public struct SelfCheck: Sendable {
        public var confFloor: String
        public var knownBadConf: Set<String>
        /// True by default: a production signer refuses to produce a document under a published TEST-ONLY key.
        public var productionKeys: Bool
        public var requiredTier: AttestationTier

        public init(confFloor: String, knownBadConf: Set<String> = [], productionKeys: Bool = true, requiredTier: AttestationTier = .a0) {
            self.confFloor = confFloor
            self.knownBadConf = knownBadConf
            self.productionKeys = productionKeys
            self.requiredTier = requiredTier
        }
    }

    /// `seq = max(stored + 1, nowMs / 1000)` (LAB_SPEC.md 4.7, own only). Writing it durably before the first signature is the caller's.
    public static func nextSeq(stored: Int64?, nowMs: Int64) throws -> Int64 {
        let clock = nowMs / 1000
        guard let stored else { return clock }
        return max(try Checked.add(stored, 1), clock)
    }

    /// Signs one presentation.
    ///
    /// - own: `bodyOwn` is the body as stored (it carries `seq`); `challenge` must be the 32 bytes the requester sent; signed by `nodeKey`.
    /// - file: `bodyOwn` is projected (`FileProjection`), `exportKey` signs it and is the caller's to discard afterwards. Nothing is
    ///   signed by the node key. Pass `ES256Signer.generateEphemeral()` in production; tests pass the lab's TEST-ONLY per-export keys.
    public static func signPresentation(
        bodyOwn: JValue, audience: Audience, challenge: [UInt8]?, nodeKey: ES256Signer?, exportKey: ES256Signer? = nil,
        nowMs: Int64, check: SelfCheck, projectionPolicy: ProjectionPolicy = .specLiteral
    ) throws -> Signed {
        guard nowMs >= 0 else { throw Failure.clockInvalid }
        let signer: ES256Signer
        let body: JValue
        let presentation: JValue
        let context: VerifyContext
        let exportFingerprint: String?
        switch audience {
        case .own:
            guard let challenge, challenge.count == challengeLength, let nodeKey else { throw Failure.challengeRequired }
            signer = nodeKey
            body = bodyOwn
            let (expires, overflow) = nowMs.addingReportingOverflow(ownTtlMs)
            guard !overflow else { throw Failure.clockInvalid }
            presentation = .object([
                JMember(name: "issuedAtMs", value: .int(nowMs)), JMember(name: "expiresAtMs", value: .int(expires)),
                JMember(name: "challenge", value: .string(Base64Strict.encodeURL(challenge))),
            ])
            var c = VerifyContext(mode: .mesh, pinnedSpki: signer.spki, expectedChallenge: challenge, confFloor: check.confFloor, nowMs: nowMs)
            c.knownBadConf = check.knownBadConf
            c.productionKeys = check.productionKeys
            c.requiredTier = check.requiredTier
            c.projectionPolicy = projectionPolicy
            context = c
            exportFingerprint = nil
        case .file:
            guard let exportKey else { throw Failure.missingExportKey }
            signer = exportKey
            do {
                body = try FileProjection.body(
                    ownObject: .object([JMember(name: "body", value: bodyOwn)]), exportNodeId: NodeIdentity.nodeId(spki: signer.spki), policy: projectionPolicy
                )
            } catch let f as FileProjection.Failure {
                throw Failure.projection(f.code)
            }
            presentation = .object([JMember(name: "issuedAtMs", value: .int(nowMs / dayMs * dayMs))])
            var c = VerifyContext(mode: .file, confFloor: check.confFloor, nowMs: nowMs)
            c.knownBadConf = check.knownBadConf
            c.productionKeys = check.productionKeys
            c.requiredTier = check.requiredTier
            c.projectionPolicy = projectionPolicy
            context = c
            exportFingerprint = NodeIdentity.exportFingerprint(spki: signer.spki)
        }

        let object = JValue.object([
            JMember(name: "schema", value: .string("asom.manifest/1")), JMember(name: "schemaMinor", value: .int(0)),
            JMember(name: "body", value: body), JMember(name: "presentation", value: presentation),
        ])
        let payload = try JCS.serialize(object)
        let document = try DSSEEnvelope.seal(payload: payload, signer: signer, includeSignerSpki: true)
        if case let .failure(failure) = ManifestVerifier.verify(document: document, context: context) {
            throw Failure.manifestUnavailable(failure.code)
        }
        return Signed(
            document: document, payload: payload, audience: audience, signerSpki: signer.spki, nodeId: NodeIdentity.nodeId(spki: signer.spki),
            exportFingerprint: exportFingerprint, exportFingerprintDisplay: audience == .file ? NodeIdentity.exportFingerprintDisplay(spki: signer.spki) : nil
        )
    }
}
