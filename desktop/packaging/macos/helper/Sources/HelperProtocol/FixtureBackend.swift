// The fixture machine of helper-protocol/SCHEMA.md section 8. It backs the `exchange` vectors and the tests; the shipped
// helper never uses it. Deterministic, no state except the assertion counters the tests read.

public final class FixtureBackend: HelperBackend {
    public static let blob: [UInt8] = Array("ASOM-FIXTURE-SE-BLOB-V1".utf8)
    public static let spki: [UInt8] = {
        let prefix: [UInt8] = [
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
        ]
        return prefix + [0x04] + (1...64).map { UInt8($0) }
    }()
    public static let signature: [UInt8] = [UInt8](repeating: 0x11, count: 32) + [UInt8](repeating: 0x22, count: 32)
    /// SHA-256 of the ASCII text `fixture-platform-uuid`.
    public static let platformDigest: [UInt8] = [
        0xCA, 0x19, 0xA8, 0x92, 0x30, 0x74, 0x5D, 0x88, 0x65, 0x9D, 0xBF, 0xCE, 0x58, 0xAB, 0x17, 0xAB,
        0x79, 0xCD, 0xE4, 0x64, 0xA8, 0xE8, 0xCE, 0x74, 0x14, 0x1D, 0x0E, 0x77, 0x2F, 0xA6, 0x43, 0x73,
    ]
    public static let outstandingSleepToken: Int64 = 7

    public private(set) var holds = 0
    public private(set) var releases = 0

    public init() {}

    public func hello() -> Fields {
        Fields([
            (name: "helper", value: .text("0.1.0")),
            (name: "macos", value: .text("27.0.1")),
            (name: "arch", value: .text("arm64")),
            (name: "se", value: .bool(true)),
            (name: "model", value: .text("Mac14,3")),
        ])
    }

    public func seCreate() throws -> Fields {
        Fields([(name: "blob", value: .bytes(FixtureBackend.blob)), (name: "spki", value: .bytes(FixtureBackend.spki))])
    }

    public func seSign(blob: [UInt8], data: [UInt8]) throws -> Fields {
        guard blob == FixtureBackend.blob else { throw BackendFailure.failed("key blob not usable") }
        return Fields([(name: "sig", value: .bytes(FixtureBackend.signature))])
    }

    public func seSelftest(blob: [UInt8]) throws -> Fields {
        guard blob == FixtureBackend.blob else { throw BackendFailure.failed("key blob not usable") }
        return Fields([(name: "verified", value: .bool(true))])
    }

    public func powerGet() throws -> Fields {
        Fields([
            (name: "source", value: .text("ac")), (name: "charging", value: .bool(true)),
            (name: "batteryPermille", value: .int(870)), (name: "lowPower", value: .bool(false)),
        ])
    }

    public func thermalGet() throws -> Fields { Fields([(name: "state", value: .text("nominal"))]) }

    public func presenceGet() throws -> Fields {
        Fields([
            (name: "hidIdleMs", value: .int(725_000)), (name: "screenLocked", value: .bool(false)),
            (name: "consoleUserIsSelf", value: .bool(true)),
        ])
    }

    public func gpuGet() throws -> Fields { Fields([(name: "deviceUtilPermille", value: .int(137))]) }

    public func memGet() throws -> Fields {
        Fields([
            (name: "physicalBytes", value: .int(17_179_869_184)),
            (name: "gpuRecommendedMaxWorkingSetBytes", value: .int(11_453_251_584)),
        ])
    }

    public func assertHold(reason: String) throws { holds += 1 }

    public func assertRelease() { releases += 1 }

    public func sleepAck(token: Int64) throws {
        guard token == FixtureBackend.outstandingSleepToken else { throw BackendFailure.badRequest("unknown token") }
    }

    public func svc(_ action: String, kind: String) throws -> Fields {
        let status: String
        switch (action, kind) {
        case ("status", "agent"): status = "notRegistered"
        case ("status", "daemon"): status = "notFound"
        case ("register", "agent"): status = "requiresApproval"
        case ("register", "daemon"): status = "notFound"
        case ("unregister", _): status = "notRegistered"
        default: throw BackendFailure.badRequest("unknown service action")
        }
        return Fields([(name: "status", value: .text(status))])
    }

    public func backupExclude(path: String) throws {
        guard path.utf8.starts(with: "/tmp/".utf8) else { throw BackendFailure.failed("cannot exclude") }
    }

    public func platformUUID() throws -> Fields { Fields([(name: "digest", value: .bytes(FixtureBackend.platformDigest))]) }

    public func pathsGet() throws -> Fields { Fields([(name: "userTempDir", value: .text("/var/folders/zz/fixture/T/"))]) }
}
