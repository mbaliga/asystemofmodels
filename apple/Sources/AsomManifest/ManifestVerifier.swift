import AsomBenchCore
import AsomDSSE
import AsomJSON

public enum AttestationTier: Int, Comparable, Sendable {
    case a0 = 0, a1, a2

    public static func < (a: AttestationTier, b: AttestationTier) -> Bool { a.rawValue < b.rawValue }

    public var name: String { ["A0", "A1", "A2"][rawValue] }

    public static func named(_ s: String) -> AttestationTier? { [.a0, .a1, .a2].first { $0.name == s } }
}

public struct RollbackEntry: Sendable, Equatable {
    public let seq: Int64
    public let bodyDigest: String

    public init(seq: Int64, bodyDigest: String) {
        self.seq = seq
        self.bodyDigest = bodyDigest
    }
}

/// The inputs of `verifyManifest` (LAB_SPEC.md section 4.6).
public struct VerifyContext: Sendable {
    public var mode: VerifyMode
    /// MESH: the SPKI the TLS session authenticated for this peer (never looked up by keyid).
    public var pinnedSpki: [UInt8]?
    /// MESH: the 32 bytes this requester sent in MANIFEST_REQ.
    public var expectedChallenge: [UInt8]?
    /// FILE: what the user typed or scanned, or nil if they did not compare.
    public var comparedFingerprint: String?
    public var compareMethod: CompareMethod?
    /// MESH only, keyed "<peer nodeId>|own".
    public var rollback: [String: RollbackEntry]
    public var requiredTier: AttestationTier
    public var confFloor: String
    public var knownBadConf: Set<String>
    public var productionKeys: Bool
    public var nowMs: Int64
    public var projectionPolicy: ProjectionPolicy = .specLiteral

    public init(
        mode: VerifyMode, pinnedSpki: [UInt8]? = nil, expectedChallenge: [UInt8]? = nil, comparedFingerprint: String? = nil,
        compareMethod: CompareMethod? = nil, rollback: [String: RollbackEntry] = [:], requiredTier: AttestationTier = .a0,
        confFloor: String, knownBadConf: Set<String> = [], productionKeys: Bool = false, nowMs: Int64
    ) {
        self.mode = mode
        self.pinnedSpki = pinnedSpki
        self.expectedChallenge = expectedChallenge
        self.comparedFingerprint = comparedFingerprint
        self.compareMethod = compareMethod
        self.rollback = rollback
        self.requiredTier = requiredTier
        self.confFloor = confFloor
        self.knownBadConf = knownBadConf
        self.productionKeys = productionKeys
        self.nowMs = nowMs
    }
}

public struct Verified: Sendable {
    public let payloadBytes: [UInt8]
    public let object: JValue
    public let manifest: Manifest
    public let bodyDigest: String
    public let pin: PinState
    public let tier: AttestationTier
    public let unknownFields: Int
    public let signerSpki: [UInt8]
    public let nodeId: String
}

/// What a viewer may show for a report rejected at steps 13 to 16 (display rule, LAB_SPEC.md 4.6).
public struct Displayable: Sendable {
    public let manifest: Manifest
    public let pin: PinState
    public let signerSpki: [UInt8]
    public let nodeId: String
}

public struct VerifyFailure: Error, Sendable {
    public let code: RejectCode
    /// Non-nil only for a reject at steps 13 to 16.
    public let displayable: Displayable?
}

public enum ManifestVerifier {
    public static let maxSkewMs: Int64 = 300_000
    public static let maxTtlMs: Int64 = 600_000
    public static let hardwareStorages: Set<String> = ["strongbox", "tee", "secure-enclave", "tpm"]

    /// The r3 verifier, steps 1 to 19, in the normative order.
    public static func verify(document: [UInt8], context: VerifyContext) -> Result<Verified, VerifyFailure> {
        // Steps 1 to 10: container, key selection, signature, canonical payload.
        let dsse: DSSEVerified
        let dsseContext = DSSEContext(
            mode: context.mode, pinnedSpki: context.pinnedSpki, comparedFingerprint: context.comparedFingerprint,
            compareMethod: context.compareMethod, productionKeys: context.productionKeys
        )
        switch DSSEEnvelope.verify(document: document, context: dsseContext) {
        case let .failure(code): return fail(code)
        case let .success(v): dsse = v
        }

        // Step 11: schema and typed decoder.
        let manifest: Manifest
        switch ManifestDecoder.decode(dsse.object) {
        case let .failure(code): return fail(code)
        case let .success(m): manifest = m
        }

        // Step 12.
        guard codeUnitsEqual(manifest.subject.nodeId, dsse.nodeId) else { return fail(.subjectKeyMismatch) }

        let display = Displayable(manifest: manifest, pin: dsse.pin, signerSpki: dsse.signerSpki, nodeId: dsse.nodeId)
        func late(_ code: RejectCode) -> Result<Verified, VerifyFailure> { .failure(VerifyFailure(code: code, displayable: display)) }

        // Step 13.
        let (skewLimit, skewOverflow) = context.nowMs.addingReportingOverflow(maxSkewMs)
        guard skewOverflow || manifest.issuedAtMs <= skewLimit else { return late(.notYetValid) }
        if manifest.audience == .own {
            guard let expires = manifest.expiresAtMs else { return late(.schemaInvalid) }
            guard context.nowMs < expires else { return late(.expired) }
            let ttl = expires - manifest.issuedAtMs
            guard ttl > 0, ttl <= maxTtlMs else { return late(.ttlInvalid) }
        }

        // Step 14.
        if context.mode == .mesh {
            guard let text = manifest.challenge, let got = Base64Strict.decodeEither(text), let want = context.expectedChallenge,
                  constantTimeEqual(got, want) else { return late(.nonceMismatch) }
        }

        // Step 15.
        guard Consistency.violation(manifest) == nil else { return late(.inconsistent) }

        // Step 15a.
        if SchemaRules.compareSemver(manifest.bench.harness.confVersion, context.confFloor).map({ $0 < 0 }) ?? true {
            return late(.derivationMismatch)
        }
        guard !context.knownBadConf.contains(where: { codeUnitsEqual($0, manifest.bench.harness.confVersion) }) else {
            return late(.derivationMismatch)
        }
        do {
            let projected = try Projection.project(manifest.bench, audience: manifest.audience, policy: context.projectionPolicy)
            let want = try JCS.serialize(.array(projected))
            let have = try JCS.serialize(manifest.resultsValue)
            guard want == have else { return late(.derivationMismatch) }
        } catch is BenchArithmeticError {
            return late(.inconsistent)
        } catch {
            return late(.derivationMismatch)
        }

        // Step 15b.
        switch (context.mode, manifest.audience) {
        case (.mesh, .own), (.file, .file): break
        default: return late(.audienceMismatch)
        }

        // Step 15c: evidence is absent or an array of at most two objects. No evidence type is read (A2 is deferred).
        if let evidence = dsse.container.member("evidence") {
            guard let items = evidence.elements, items.count <= 2, items.allSatisfy({ $0.isObject }) else { return late(.containerInvalid) }
        }

        // Step 16.
        guard let bodyValue = dsse.object.member("body"), let bodyBytes = try? JCS.serialize(bodyValue) else { return late(.schemaInvalid) }
        let digest = Base64Strict.encodeURL(NodeIdentity.sha256(bodyBytes))
        if context.mode == .mesh, let seq = manifest.seq, let prev = context.rollback[dsse.nodeId + "|own"] {
            if seq < prev.seq { return late(.rollback) }
            if seq == prev.seq, !codeUnitsEqual(digest, prev.bodyDigest) { return late(.equivocation) }
        }

        // Steps 17 and 18.
        let tier: AttestationTier = hardwareStorages.contains(manifest.subject.keyStorage) ? .a1 : .a0
        guard tier >= context.requiredTier else { return .failure(VerifyFailure(code: .tierInsufficient, displayable: nil)) }

        return .success(Verified(
            payloadBytes: dsse.payload, object: dsse.object, manifest: manifest, bodyDigest: digest, pin: dsse.pin, tier: tier,
            unknownFields: manifest.unknownFields, signerSpki: dsse.signerSpki, nodeId: dsse.nodeId
        ))
    }

    private static func fail(_ code: RejectCode) -> Result<Verified, VerifyFailure> {
        .failure(VerifyFailure(code: code, displayable: nil))
    }

    static func constantTimeEqual(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        var diff = UInt8(truncatingIfNeeded: a.count ^ b.count)
        for k in 0..<min(a.count, b.count) { diff |= a[k] ^ b[k] }
        return diff == 0
    }
}

extension RejectCode {
    public static let subjectKeyMismatch = RejectCode("SUBJECT_KEY_MISMATCH")
    public static let notYetValid = RejectCode("NOT_YET_VALID")
    public static let expired = RejectCode("EXPIRED")
    public static let ttlInvalid = RejectCode("TTL_INVALID")
    public static let nonceMismatch = RejectCode("NONCE_MISMATCH")
    public static let inconsistent = RejectCode("INCONSISTENT")
    public static let derivationMismatch = RejectCode("DERIVATION_MISMATCH")
    public static let audienceMismatch = RejectCode("AUDIENCE_MISMATCH")
    public static let rollback = RejectCode("ROLLBACK")
    public static let equivocation = RejectCode("EQUIVOCATION")
    public static let tierInsufficient = RejectCode("TIER_INSUFFICIENT")
}
