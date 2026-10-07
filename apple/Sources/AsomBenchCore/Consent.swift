import AsomDSSE

/// The inputs from which the core generates the consent sheet (benchmark.md section 11.1: the `{...}` fields are filled from preflight).
public struct ConsentInputs: Sendable, Equatable {
    public var plan: String
    public var downloadBytes: Int64
    public var tiers: [String]
    public var t3OptInOffered: Bool
    public var sustainedToday: Bool

    public init(plan: String, downloadBytes: Int64, tiers: [String], t3OptInOffered: Bool, sustainedToday: Bool) {
        self.plan = plan
        self.downloadBytes = downloadBytes
        self.tiers = tiers
        self.t3OptInOffered = t3OptInOffered
        self.sustainedToday = sustainedToday
    }
}

/// The consent sheet text. For the `standard` sheet with a download and the 8B opt-in the wording is the spec's (11.1); every
/// other wording (the other plans, no download, the "run today" tick) is this lane's own, because the spec gives no text for
/// it (ERRATA E-31). The sheet is ASCII with LF line ends and a final LF.
public enum ConsentSheet {
    public static func text(_ inputs: ConsentInputs) -> String {
        let heat = ["standard", "sustained", "battery", "extended"].contains(inputs.plan)
        var lines = ["Run the \(inputs.plan) device test?"]
        lines.append("- Takes about \(minutes(inputs.plan)) minutes. You can stop at any time.")
        if heat { lines.append("- Makes the device warm and uses power. Keep it on its charger.") }
        if inputs.downloadBytes > 0 {
            lines.append("- Downloads \(formatBytes(inputs.downloadBytes)) of test models over Wi-Fi first (\(inputs.tiers.joined(separator: ", "))).")
        }
        if inputs.t3OptInOffered {
            let t3 = BenchSet.pin(tier: "T3", in: BenchSet.q1)?.bytes ?? 0
            lines.append("  [ ] Also test 8B models (+\(formatBytes(t3))). Without this, 8B speed is estimated.")
        }
        if inputs.sustainedToday, heat {
            lines.append("- A heat test already ran on this device today.")
            lines.append("  [ ] Run the heat test again today")
        }
        lines.append("- Nothing is uploaded. Results stay on this device unless you share them.")
        lines.append("- If the device gets too hot, the test stops by itself.")
        lines.append("[Not now]   [Start]")
        return lines.joined(separator: "\n") + "\n"
    }

    /// True when `text(inputs)` is the spec's own wording (the standard sheet with a download and no "run today" tick), so that its
    /// hash is something another implementation can reproduce from the spec alone.
    public static func wordingIsSpecified(_ inputs: ConsentInputs) -> Bool {
        inputs.plan == "standard" && inputs.downloadBytes > 0 && !inputs.sustainedToday
    }

    public static func textSha256(_ text: String) -> [UInt8] {
        NodeIdentity.sha256(Array(text.utf8))
    }

    /// Decimal units, as the pin table's sizes are: `4.3 GB` (one decimal, half up) from 10^9 bytes, else whole MB.
    static func formatBytes(_ bytes: Int64) -> String {
        if bytes >= 1_000_000_000 {
            let tenths = (bytes + 50_000_000) / 100_000_000
            return "\(tenths / 10).\(tenths % 10) GB"
        }
        return "\((bytes + 500_000) / 1_000_000) MB"
    }

    private static func minutes(_ plan: String) -> String {
        switch plan {
        case "standard": return "20-30"
        case "extended": return "90"
        case "sustained": return "20"
        case "battery": return "12"
        default: return "5"
        }
    }
}

public struct ConsentRefused: Error, Equatable, Sendable, CustomStringConvertible {
    public let reason: String
    public var description: String { "CONSENT_REFUSED: \(reason)" }
}

/// The only way to mint a token is `confirm` with the SHA-256 of the text that was displayed (B5). A token lives 5 minutes
/// from minting, covers exactly one plan and exactly one run.
public struct ConsentToken: Sendable, Equatable {
    let id: Int
    public let plan: String
    public let mintedAtMs: Int64
}

public struct ConsentGate: Sendable {
    public static let lifetimeMs: Int64 = 300_000

    private var nextId = 0
    private var consumed: Set<Int> = []

    public init() {}

    public mutating func confirm(sheetFor inputs: ConsentInputs, shownSha256: [UInt8], nowMs: Int64) -> Result<ConsentToken, ConsentRefused> {
        let want = ConsentSheet.textSha256(ConsentSheet.text(inputs))
        guard Self.constantTimeEqual(want, shownSha256) else { return .failure(ConsentRefused(reason: "hash is not the hash of the sheet shown")) }
        nextId += 1
        return .success(ConsentToken(id: nextId, plan: inputs.plan, mintedAtMs: nowMs))
    }

    /// Spends the token for one run of `plan`. A token that is expired, already spent, or minted for another plan is refused.
    public mutating func consume(_ token: ConsentToken, plan: String, nowMs: Int64) -> Result<Void, ConsentRefused> {
        guard !consumed.contains(token.id) else { return .failure(ConsentRefused(reason: "token already used")) }
        guard codeUnitsEqualPlan(token.plan, plan) else { return .failure(ConsentRefused(reason: "token covers another plan")) }
        let (age, overflow) = nowMs.subtractingReportingOverflow(token.mintedAtMs)
        guard !overflow, age >= 0, age < Self.lifetimeMs else { return .failure(ConsentRefused(reason: "token expired")) }
        consumed.insert(token.id)
        return .success(())
    }

    private func codeUnitsEqualPlan(_ a: String, _ b: String) -> Bool { a.utf16.elementsEqual(b.utf16) }

    static func constantTimeEqual(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        var diff = UInt8(truncatingIfNeeded: a.count ^ b.count)
        for k in 0..<min(a.count, b.count) { diff |= a[k] ^ b[k] }
        return diff == 0
    }
}
