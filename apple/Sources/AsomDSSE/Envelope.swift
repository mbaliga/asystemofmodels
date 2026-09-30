import AsomJSON

public enum VerifyMode: Sendable { case mesh, file }

public enum CompareMethod: String, Sendable { case qr, typed }

public enum PinState: Equatable, Sendable {
    case pinned
    case signerUnverified
    case pinnedByFingerprint(CompareMethod)
}

/// The inputs of verifier steps 1 to 10 (LAB_SPEC.md section 4.6). The clock, the challenge, the rollback store
/// and the tier belong to the later steps, which live in AsomManifest (half I0b).
public struct DSSEContext: Sendable {
    public var mode: VerifyMode
    /// MESH: the SPKI the transport authenticated for this peer. Never looked up by keyid.
    public var pinnedSpki: [UInt8]?
    /// FILE: what the user typed or scanned, or nil if they did not compare.
    public var comparedFingerprint: String?
    public var compareMethod: CompareMethod?
    public var productionKeys: Bool

    public init(
        mode: VerifyMode,
        pinnedSpki: [UInt8]? = nil,
        comparedFingerprint: String? = nil,
        compareMethod: CompareMethod? = nil,
        productionKeys: Bool = false
    ) {
        self.mode = mode
        self.pinnedSpki = pinnedSpki
        self.comparedFingerprint = comparedFingerprint
        self.compareMethod = compareMethod
        self.productionKeys = productionKeys
    }
}

/// What steps 1 to 10 establish. Only `payload` (the verified bytes) may be read from here on.
public struct DSSEVerified: Sendable {
    public let payload: [UInt8]
    public let payloadType: String
    public let object: JValue
    /// The parsed container. Only `evidence` (step 15c) is read from it after the signature step.
    public let container: JValue
    public let signerSpki: [UInt8]
    public let nodeId: String
    public let pin: PinState
}

/// The signature layer alone, for comparing with the JCA column of manifest-vectors/crosscheck.out.
public enum SignatureLayer: Equatable, Sendable {
    case rejected(RejectCode)
    case valid
    case invalid
}

public enum DSSEEnvelope {
    public static let manifestPayloadType = "application/vnd.asom.manifest.v1+json"
    public static let maxDocumentBytes = 524_288
    /// manifest.md section 4.4: decoded payload at most 256 KiB (checked at step 6, ERRATA E-16).
    public static let maxPayloadBytes = 262_144

    /// Verifier steps 1 to 10. A `.success` means the DSSE layer holds, not that the manifest is valid.
    public static func verify(document: [UInt8], context: DSSEContext) -> Result<DSSEVerified, RejectCode> {
        if document.count > maxDocumentBytes { return .failure(.tooLarge) }
        let container: JValue
        switch StrictJSON.parse(document) {
        case let .failure(code): return .failure(code)
        case let .success(value): container = value
        }
        let fields: Fields
        switch parseContainer(container) {
        case let .failure(code): return .failure(code)
        case let .success(f): fields = f
        }

        switch classifyPayloadType(fields.payloadType) {
        case let .some(code): return .failure(code)
        case .none: break
        }
        guard fields.signatures.count == 1 else { return .failure(.signatureCount) }

        guard let payload = Base64Strict.decodeEither(fields.payloadB64),
              let signature = Base64Strict.decodeEither(fields.sigB64) else { return .failure(.encoding) }
        guard signature.count == SignatureCodec.rawLength else { return .failure(.signatureEncoding) }
        guard payload.count <= maxPayloadBytes else { return .failure(.tooLarge) }

        let spki: [UInt8]
        switch selectKey(fields: fields, container: container, context: context) {
        case let .failure(code): return .failure(code)
        case let .success(k): spki = k
        }
        let nodeId = NodeIdentity.nodeId(spki: spki)
        if let keyid = fields.keyid, !codeUnitsEqual(keyid, nodeId) { return .failure(.keyNotPinned) }
        if case let .failure(code) = SPKI.validate(spki) { return .failure(code) }
        if context.productionKeys, NodeIdentity.testOnlyNodeIds.contains(nodeId) { return .failure(.testOnlyKey) }

        var pin = PinState.pinned
        if context.mode == .file {
            if let compared = context.comparedFingerprint {
                guard NodeIdentity.fingerprintMatches(compared, spki: spki) else { return .failure(.fingerprintMismatch) }
                pin = .pinnedByFingerprint(context.compareMethod ?? .typed)
            } else {
                pin = .signerUnverified
            }
        }

        let message = PAE.encode(payloadType: fields.payloadType, payload: payload)
        if case let .failure(code) = ES256.verify(spki: spki, message: message, signature: signature) {
            return .failure(code)
        }

        let object: JValue
        switch StrictJSON.parse(payload) {
        case let .failure(code): return .failure(code)
        case let .success(value): object = value
        }
        guard let canonical = try? JCS.serialize(object), canonical == payload else { return .failure(.nonCanonical) }

        return .success(DSSEVerified(
            payload: payload, payloadType: fields.payloadType, object: object, container: container,
            signerSpki: spki, nodeId: nodeId, pin: pin
        ))
    }

    /// Container parse, base64 and PAE verify under `spki`, ignoring payloadType, signature count and key selection.
    /// This is what VerifyDsse.java measured. Documents the strict parser refuses are reported as `.rejected`.
    public static func signatureLayer(document: [UInt8], spki: [UInt8]) -> SignatureLayer {
        if document.count > maxDocumentBytes { return .rejected(.tooLarge) }
        let container: JValue
        switch StrictJSON.parse(document) {
        case let .failure(code): return .rejected(code)
        case let .success(value): container = value
        }
        let fields: Fields
        switch parseContainer(container) {
        case let .failure(code): return .rejected(code)
        case let .success(f): fields = f
        }
        guard let payload = Base64Strict.decodeEither(fields.payloadB64),
              let signature = Base64Strict.decodeEither(fields.sigB64) else { return .rejected(.encoding) }
        guard signature.count == SignatureCodec.rawLength else { return .invalid }
        let message = PAE.encode(payloadType: fields.payloadType, payload: payload)
        switch ES256.verify(spki: spki, message: message, signature: signature) {
        case .success: return .valid
        case let .failure(code): return code == .algUnsupported ? .rejected(code) : .invalid
        }
    }

    /// Producer: a container in JCS form, signed low-S over PAE(payloadType, payload). `payload` must already be the
    /// canonical bytes that will be verified. `signerSpki: false` omits `signer.spki` (MESH senders may).
    public static func seal(
        payload: [UInt8],
        payloadType: String = manifestPayloadType,
        signer: ES256Signer,
        includeSignerSpki: Bool = true
    ) throws -> [UInt8] {
        let signature = signer.sign(PAE.encode(payloadType: payloadType, payload: payload))
        var members: [JMember] = [
            JMember(name: "asomCapabilityManifest", value: .int(1)),
            JMember(name: "dsse", value: .object([
                JMember(name: "payload", value: .string(Base64Strict.encode(payload))),
                JMember(name: "payloadType", value: .string(payloadType)),
                JMember(name: "signatures", value: .array([.object([
                    JMember(name: "keyid", value: .string(NodeIdentity.nodeId(spki: signer.spki))),
                    JMember(name: "sig", value: .string(Base64Strict.encode(signature))),
                ])])),
            ])),
            JMember(name: "evidence", value: .array([])),
        ]
        if includeSignerSpki {
            members.append(JMember(name: "signer", value: .object([
                JMember(name: "spki", value: .string(Base64Strict.encode(signer.spki))),
            ])))
        }
        return try JCS.serialize(.object(members))
    }

    // MARK: - Internals

    private struct Fields {
        let payloadType: String
        let payloadB64: String
        let signatures: [JValue]
        let sigB64: String
        let keyid: String?
    }

    /// Step 3, plus the shape of signatures[0] that step 6 reads. A container that is not an object, or whose dsse
    /// members have the wrong type, is CONTAINER_INVALID; an absent or different version marker is
    /// CONTAINER_VERSION_UNKNOWN. See ERRATA.md E-04.
    private static func parseContainer(_ c: JValue) -> Result<Fields, RejectCode> {
        guard c.isObject else { return .failure(.containerInvalid) }
        guard c.member("asomCapabilityManifest") == .int(1) else { return .failure(.containerVersionUnknown) }
        guard let dsse = c.member("dsse"), dsse.isObject,
              let payloadType = dsse.member("payloadType")?.stringValue,
              let payloadB64 = dsse.member("payload")?.stringValue,
              let signatures = dsse.member("signatures")?.elements else { return .failure(.containerInvalid) }
        var sigB64 = ""
        var keyid: String?
        if let first = signatures.first {
            guard first.isObject, let sig = first.member("sig")?.stringValue else { return .failure(.containerInvalid) }
            sigB64 = sig
            if let k = first.member("keyid") {
                guard let s = k.stringValue else { return .failure(.containerInvalid) }
                keyid = s
            }
        }
        return .success(Fields(
            payloadType: payloadType, payloadB64: payloadB64, signatures: signatures, sigB64: sigB64, keyid: keyid
        ))
    }

    /// Step 4. "application/vnd.asom.manifest.v<N>+json" with a canonical decimal N > 1 is a newer major.
    private static func classifyPayloadType(_ type: String) -> RejectCode? {
        if codeUnitsEqual(type, manifestPayloadType) { return nil }
        let bytes = Array(type.utf8)
        let head = Array("application/vnd.asom.manifest.v".utf8)
        let tail = Array("+json".utf8)
        if bytes.count > head.count + tail.count, bytes.starts(with: head), Array(bytes.suffix(tail.count)) == tail {
            let digits = Array(bytes[head.count..<(bytes.count - tail.count)])
            if digits.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }), digits.first != 0x30, digits != [0x31] {
                return .schemaMajorUnknown
            }
        }
        return .payloadTypeUnsupported
    }

    /// Step 7 key selection. In FILE mode the key comes from the container and is only as trusted as the fingerprint
    /// the user compared. A missing signer.spki is KEY_NOT_PINNED.
    private static func selectKey(fields: Fields, container: JValue, context: DSSEContext) -> Result<[UInt8], RejectCode> {
        switch context.mode {
        case .mesh:
            guard let pinned = context.pinnedSpki else { return .failure(.keyNotPinned) }
            return .success(pinned)
        case .file:
            guard let member = container.member("signer")?.member("spki") else { return .failure(.keyNotPinned) }
            guard let text = member.stringValue else { return .failure(.containerInvalid) }
            guard let bytes = Base64Strict.decodeEither(text) else { return .failure(.encoding) }
            return .success(bytes)
        }
    }
}
