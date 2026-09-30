import AsomBenchCore
import AsomDSSE
import AsomJSON

/// `asom.manifest-text/1` (LAB_SPEC.md 4.8, manifest.md 14.2 with the r3 wording): the header, a verification block
/// computed by the viewer (LM-6, never taken from the payload) and the `asom.text/1` body rendered from `body.bench`.
public enum ManifestText {
    public struct Viewed: Sendable {
        public let manifest: Manifest
        public let mode: VerifyMode
        public let pin: PinState
        public let signerSpki: [UInt8]
        /// Set when the report was rejected at steps 13 to 16 and is shown with the reject (display rule).
        public let rejected: RejectCode?

        public init(manifest: Manifest, mode: VerifyMode, pin: PinState, signerSpki: [UInt8], rejected: RejectCode? = nil) {
            self.manifest = manifest
            self.mode = mode
            self.pin = pin
            self.signerSpki = signerSpki
            self.rejected = rejected
        }
    }

    public static let notProven = [
        "- Not proven: that the measurements were honest or typical, that the device model is true,",
        "  or that the benchmark software was unmodified.",
    ]

    static let knownClasses = ["phone", "tablet", "handheld", "laptop", "desktop", "server", "sbc"]

    static func storageWords(_ storage: String) -> String {
        switch storage {
        case "strongbox": return "StrongBox secure element"
        case "tee": return "hardware-backed keystore (TEE)"
        case "secure-enclave": return "Secure Enclave"
        case "tpm": return "TPM"
        case "os-keystore": return "operating-system keystore (software)"
        case "file": return "key file (software)"
        case "ephemeral": return "per-export software key (discarded after signing)"
        default: return "unknown"
        }
    }

    /// The full report: header, verification block, body, and the newer-format line when items were not shown.
    public static func render(_ v: Viewed, options: RenderOptions = RenderOptions()) throws -> String {
        let m = v.manifest
        var lines: [String] = []
        func add(_ s: String) { lines.append(s) }
        let cls = knownClasses.contains(m.device.deviceClass) ? m.device.deviceClass : "other (\(m.device.deviceClass))"
        add("ASOM CAPABILITY REPORT")
        add("Device: \(TextRenderer.asciiOnly(m.device.model)) by \(TextRenderer.asciiOnly(m.device.vendor)) (\(cls))")
        switch v.mode {
        case .mesh:
            add("Report \(m.seq.map(String.init) ?? "-"), signed \(minute(m.issuedAtMs)) UTC, valid until \(minute(m.expiresAtMs ?? m.issuedAtMs)) UTC")
            add("Signer: node \(NodeIdentity.displayFingerprint(spki: v.signerSpki))")
        case .file:
            add("Report exported \(day(m.issuedAtMs)) (day only)")
            add("Signer: key \(NodeIdentity.exportFingerprintDisplay(spki: v.signerSpki))")
        }
        add("")
        add("VERIFICATION (checked by this viewer, not stated by the device)")
        add("- Signature: valid. The report has not changed since this key signed it.")
        switch v.pin {
        case .pinned: add("- Signer key: matches the key you paired with.")
        case let .pinnedByFingerprint(method): add("- Signer key: matches the fingerprint you compared (\(method == .qr ? "scanned" : "typed")).")
        case .signerUnverified: add("- Signer key: signed, but the signer is unverified: anyone could have made this key.")
        }
        add("- Key storage: \(storageWords(m.subject.keyStorage)) (self-reported, not attested).")
        if let code = v.rejected {
            add("- Freshness: not confirmed.")
            add("- REJECTED: \(code.rawValue). Do not rely on this report.")
        } else {
            add(v.mode == .mesh ? "- Freshness: signed for your request." : "- Freshness: not applicable: an exported file answers no request.")
        }
        for l in notProven { add(l) }
        add("")
        var out = lines.joined(separator: "\n") + "\n"
        out += try body(m, options: options)
        if m.unknownFields > 0 {
            out += "\nThis report has \(m.unknownFields) item\(m.unknownFields == 1 ? "" : "s") from a newer format that this viewer does not show.\n"
        }
        return out
    }

    /// An exported text (LAB_SPEC.md 4.8): only the `asom.text/1` body, never a verification block.
    public static func exportText(_ m: Manifest, options: RenderOptions = RenderOptions()) throws -> String {
        try body(m, options: options)
    }

    static func body(_ m: Manifest, options: RenderOptions) throws -> String {
        let derived = try Derivation.derive(m.bench)
        return TextRenderer.render(m.bench, derived, options: options)
    }

    /// YYYY-MM-DD HH:MM, truncated to the minute, UTC.
    static func minute(_ ms: Int64) -> String {
        let (y, mo, d) = PublicDerivative.civil(days: ms / 86_400_000)
        let secs = (ms % 86_400_000) / 1000
        return "\(pad4(y))-\(pad2(mo))-\(pad2(d)) \(pad2(secs / 3600)):\(pad2(secs % 3600 / 60))"
    }

    static func day(_ ms: Int64) -> String {
        let (y, mo, d) = PublicDerivative.civil(days: ms / 86_400_000)
        return "\(pad4(y))-\(pad2(mo))-\(pad2(d))"
    }

    private static func pad2(_ v: Int64) -> String { v < 10 ? "0\(v)" : "\(v)" }
    private static func pad4(_ v: Int64) -> String { String(repeating: "0", count: max(0, 4 - String(v).count)) + String(v) }
}
