import AsomBenchCore
import AsomJSON

/// The typed `asom.manifest/1` payload (manifest.md section 4.1 as amended by LAB_SPEC.md 4.1 P1-P10).
public struct Manifest: Sendable {
    public struct Subject: Sendable {
        public let nodeId: String
        public let keyStorage: String
    }

    public struct Harness: Sendable {
        public let id: String
        public let version: String
        public let methodologyId: String
        public let confVersion: String
    }

    public struct ProducerEngine: Sendable {
        public let name: String
        public let commit: String
        public let buildFlags: [String]
    }

    public struct Producer: Sendable {
        public let app: String
        public let appVersion: String
        public let harness: Harness
        public let engine: ProducerEngine
    }

    public struct OS: Sendable {
        public let family: String
        public let version: String
        public let securityPatch: String?
    }

    public struct SoC: Sendable {
        public let vendor: String
        public let name: String
        public let logicalCores: Int64
    }

    public struct Device: Sendable {
        public let deviceClass: String
        public let vendor: String
        public let model: String
        public let os: OS
        public let soc: SoC
        public let totalBytes: Int64
        public let cooling: String
        public let hasPlatformIds: Bool
    }

    public struct Rate: Sendable {
        public let p10: Int64?
        public let p50: Int64
        public let p90: Int64?
    }

    public struct Prefill: Sendable {
        public let promptTokens: Int64
        public let rate: Rate
        public let ttft: Rate
    }

    public struct Decode: Sendable {
        public let contextTokens: Int64
        public let genTokens: Int64
        public let rate: Rate
    }

    public struct CurvePoint: Sendable {
        public let tMs: Int64
        public let rate: Int64
    }

    public struct Sustained: Sendable {
        public let durationMs: Int64
        public let intervalMs: Int64
        public let steady: Int64
        public let throttleOnsetMs: Int64?
        public let curve: [CurvePoint]
    }

    public struct Row: Sendable {
        public let modelId: String
        public let fileSha256: String
        public let backend: String
        public let quant: String
        public let ctxTokens: Int64
        public let measuredAtMs: Int64
        public let planned: Int64
        public let completed: Int64
        public let discarded: Int64
        public let charging: Bool
        public let prefill: [Prefill]
        public let decode: [Decode]
        public let sustained: Sustained?
        public let availableBeforeLoadBytes: Int64
        public let peakProcessBytes: Int64
        public let powerMethod: String
        public let avgMilliW: Int64?
    }

    public let schemaMinor: Int64
    public let audience: Audience
    public let seq: Int64?
    public let subject: Subject
    public let producer: Producer
    public let device: Device
    public let rows: [Row]
    /// The raw `results` array, for the byte comparison of step 15a.
    public let resultsValue: JValue
    public let bench: BenchDocument
    public let issuedAtMs: Int64
    public let expiresAtMs: Int64?
    public let challenge: String?
    /// Members the typed decoder did not recognise, tolerated because `schemaMinor` is above the known minor.
    public let unknownFields: Int
}
