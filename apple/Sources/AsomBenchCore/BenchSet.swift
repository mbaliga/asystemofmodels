/// The compiled-in bench-set pin (benchmark.md section 4.2, "bench set 1"). Status: proposed until the owner confirms
/// each sha256 (LAB_SPEC.md section 5). The L1 set has no pins (BLOCKED(D18)) and is therefore not decodable.
public struct PinnedTier: Sendable, Equatable {
    public let tier: String
    public let modelId: String
    public let displayName: String
    public let quant: String
    public let bytes: Int64
    public let sha256: String
    public let kvBytesPerToken: Int64
}

public enum BenchSet {
    public static let q1Id = "qwen3-dense-1"

    public static let tierOrder = ["T0", "T1", "T2", "T3", "T4", "T5"]

    public static let q1: [PinnedTier] = [
        PinnedTier(tier: "T0", modelId: "qwen3-0.6b", displayName: "Qwen3-0.6B", quant: "Q8_0", bytes: 639_446_688,
                   sha256: "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", kvBytesPerToken: 114_688),
        PinnedTier(tier: "T1", modelId: "qwen3-1.7b", displayName: "Qwen3-1.7B", quant: "Q8_0", bytes: 1_834_426_016,
                   sha256: "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a", kvBytesPerToken: 114_688),
        PinnedTier(tier: "T2", modelId: "qwen3-4b", displayName: "Qwen3-4B", quant: "Q4_K_M", bytes: 2_497_280_256,
                   sha256: "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", kvBytesPerToken: 147_456),
        PinnedTier(tier: "T3", modelId: "qwen3-8b", displayName: "Qwen3-8B", quant: "Q4_K_M", bytes: 5_027_783_488,
                   sha256: "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785", kvBytesPerToken: 147_456),
        PinnedTier(tier: "T4", modelId: "qwen3-14b", displayName: "Qwen3-14B", quant: "Q4_K_M", bytes: 9_001_752_960,
                   sha256: "500a8806e85ee9c83f3ae08420295592451379b4f8cf2d0f41c15dffeb6b81f0", kvBytesPerToken: 163_840),
        PinnedTier(tier: "T5", modelId: "qwen3-32b", displayName: "Qwen3-32B", quant: "Q4_K_M", bytes: 19_762_149_024,
                   sha256: "efd971561896866f0e910cce52761ca77b1b138090c7f15fe284676d57d1f689", kvBytesPerToken: 262_144),
    ]

    /// nil for every set except Q1: L1 and any other name have no pins.
    public static func pins(forSet id: String) -> [PinnedTier]? {
        id == q1Id ? q1 : nil
    }

    public static func pin(tier: String, in pins: [PinnedTier]) -> PinnedTier? {
        pins.first { $0.tier == tier }
    }

    /// Safety factor by form (benchmark.md section 4.4), in permille.
    public static func safetyPermille(form: String) -> Int64? {
        switch form {
        case "phone", "tablet": return 750
        case "handheld": return 800
        case "laptop": return 850
        case "desktop", "server": return 900
        default: return nil
        }
    }

    /// 4096-token context and 300 MiB overhead of the `fits()` rule (benchmark.md section 4.4).
    public static let kvContextTokens: Int64 = 4096
    public static let overheadBytes: Int64 = 314_572_800

    public static func hasBattery(form: String) -> Bool {
        ["phone", "tablet", "handheld", "laptop"].contains(form)
    }

    /// The sets that are loaded without any ruling. L1 is never among them (LAB_SPEC.md section 5).
    public static let defaultKinds: [Kind] = [.q1]

    /// The spec names the L1 set but gives it no id string, and this lane invents none.
    public static func id(of kind: Kind) -> String? { kind == .q1 ? q1Id : nil }

    public static var defaultSetIds: [String] { defaultKinds.compactMap { id(of: $0) } }

    public enum Kind: Sendable, Equatable {
        case q1
        case l1
    }

    public enum Status: String, Sendable, Equatable {
        /// Every sha256 is a proposal until the owner confirms it ([A17]).
        case proposed = "PROPOSED"
        /// Named but not pinned: no hash, size or revision is known (BLOCKED(D18)).
        case unpinned = "UNPINNED"
    }

    public struct Table: Sendable, Equatable {
        public let kind: Kind
        public let status: Status
        public let pins: [PinnedTier]
    }

    public struct RulingRequired: Error, Equatable, Sendable, CustomStringConvertible {
        public var description: String { "the L1 set is loaded only under a D18 ruling flag" }
    }

    /// Q1 loads always. L1 loads only when the caller says the D18 ruling flag is set, and then has no pins at all.
    public static func load(_ kind: Kind, d18Ruling: Bool = false) throws -> Table {
        switch kind {
        case .q1:
            return Table(kind: .q1, status: .proposed, pins: q1)
        case .l1:
            guard d18Ruling else { throw RulingRequired() }
            return Table(kind: .l1, status: .unpinned, pins: [])
        }
    }
}
