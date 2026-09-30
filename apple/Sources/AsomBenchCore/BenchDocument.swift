import AsomJSON

/// The `asom.bench/1` measurement document, active lane only (LAB_SPEC.md section 5, 4.1 P6 to P8; benchmark.md 13.2).
/// Raw samples only: nothing derived travels in the document.
public struct BenchDocument: Sendable {
    public struct Engine: Sendable {
        public let name: String
        public let commit: String
        public let backend: String
        public let buildFlags: [String]
        public let threads: Int64
        public let gpuLayers: Int64
        public let kvType: String
        public let flashAttn: String
    }

    public struct Harness: Sendable {
        public let shell: String
        public let coreImpl: String
        public let coreVersion: String
        public let confVersion: String
        public let timingSource: String
        public let planSha256: String
        public let engine: Engine
    }

    public struct Device: Sendable {
        public let platform: String
        public let form: String
        public let maker: String
        public let model: String
        public let soc: String
        public let osVersion: String
        public let osBuild: String?
        public let gpu: String?
        public let gpuDriver: String?
        public let memTotalBytes: Int64
        public let unifiedMemory: Bool
        public let virtualized: Bool
    }

    public struct Memory: Sendable {
        public let availAtStartBytes: Int64
        public let processLimitBytes: Int64?
        public let gpuWorkingSetBytes: Int64?
        public let limitSource: String
    }

    public struct Abort: Sendable {
        public let reason: String
        public let atMs: Int64
    }

    public struct Run: Sendable {
        public let plan: String
        public let optInTiers: [String]
        public let startedAtMs: Int64
        public let endedAtMs: Int64
        public let dayUtc: String
        public let powerSource: String
        public let batteryStartPermille: Int64?
        public let startThermal: String
        public let screenOn: Bool?
        public let contentionBeforePermille: Int64
        public let contentionAfterPermille: Int64
        public let abort: Abort?
    }

    public struct Test: Sendable {
        public enum Kind: Sendable { case prefill, decode }
        public let name: String
        public let kind: Kind
        public let tokens: Int64
        public let depth: Int64
        public let samples: [Int64]
        public let wholeSamples: [Int64]?
    }

    public struct Numerics: Sendable {
        public let milliNatsPerToken: Int64
        public let refMilliNatsPerToken: Int64?
    }

    public struct Tier: Sendable {
        public let tier: String
        public let sha256: String
        public let bytes: Int64
        public let quant: String
        public let startThermal: String
        public let startThermalCode: Int64
        public let startedAtMs: Int64
        public let nCtx: Int64
        public let availBeforeLoadBytes: Int64
        public let peakFootprintBytes: Int64
        public let kvBytesPerToken: Int64
        public let loadColdMicros: Int64
        public let loadColdness: String
        public let loadWarmMicros: [Int64]
        public let tests: [Test]
        public let numerics: Numerics
        public let restarts: Int64
        public let swapDeltaBytes: Int64?
    }

    public struct Window: Sendable {
        public let tStartMs: Int64
        public let tokens: Int64
        public let micros: Int64
        public let thermalCode: Int64

        public init(tStartMs: Int64, tokens: Int64, micros: Int64, thermalCode: Int64) {
            self.tStartMs = tStartMs
            self.tokens = tokens
            self.micros = micros
            self.thermalCode = thermalCode
        }
    }

    public struct Sustain: Sendable {
        public let tier: String
        public let windowMs: Int64
        public let capMs: Int64
        public let endReason: String
        public let headroomAtOnsetPermille: Int64?
        public let windows: [Window]

        public init(tier: String, windowMs: Int64, capMs: Int64, endReason: String, headroomAtOnsetPermille: Int64?, windows: [Window]) {
            self.tier = tier
            self.windowMs = windowMs
            self.capMs = capMs
            self.endReason = endReason
            self.headroomAtOnsetPermille = headroomAtOnsetPermille
            self.windows = windows
        }
    }

    public let benchSet: String
    public let harness: Harness
    public let device: Device
    public let memory: Memory
    public let run: Run
    public let tiers: [Tier]
    public let sustain: Sustain?

    public static let platforms = ["android", "ios", "ipados", "macos", "linux", "windows", "ubuntu-touch"]
    public static let forms = ["phone", "tablet", "handheld", "laptop", "desktop", "server"]
    public static let thermalClasses = ["cool", "warm", "hot"]
    public static let thermalNames = ["nominal", "light", "moderate", "severe", "critical"]
    public static let abortReasons = ["THERMAL_HARD", "BATTERY_TEMP", "USER_STOP", "BACKGROUNDED", "CHARGER_REMOVED",
                                      "MEMORY_PRESSURE", "WALL_CAP", "PROBE_LOST", "DEVICE_BUSY"]
    public static let endReasons = ["PLATEAU", "TIME_CAP", "THERMAL_SOFT", "THERMAL_HARD", "BATTERY_TEMP", "USER_STOP",
                                    "BACKGROUNDED", "CHARGER_REMOVED", "MEMORY_PRESSURE", "WALL_CAP", "YIELDED",
                                    "DEVICE_BUSY", "PROBE_LOST"]

    /// Decodes and validates. `fileForm` adds the P8 rules of the FILE audience (day-truncated times, no build,
    /// driver, battery level or screen state). The pre-pass for forbidden names (P7) runs on the whole value.
    public static func decode(_ value: JValue, fileForm: Bool, state: DecodeState) throws -> BenchDocument {
        if SchemaRules.containsForbiddenName(value) { throw SchemaViolation("bench", "a forbidden member name (P7)") }
        let root = try ObjectReader(value, "bench", state)
        try root.constant("schema", "asom.bench/1")
        try root.constantInt("benchProtocol", 1)
        let setId = try root.string("benchSet", SchemaRules.isBenchSetName)
        guard let pins = BenchSet.pins(forSet: setId) else { throw SchemaViolation("bench.benchSet", "no pins for bench set \(setId)") }
        let harness = try decodeHarness(try root.object("harness"))
        let device = try decodeDevice(try root.object("device"), fileForm: fileForm)
        let memory = try decodeMemory(try root.object("memory"))
        let run = try decodeRun(try root.object("run"), fileForm: fileForm)
        var tiers: [Tier] = []
        var lastIndex = -1
        for (k, item) in try root.array("tiers", count: 0...6).enumerated() {
            let tier = try decodeTier(item, "bench.tiers[\(k)]", pins: pins, fileForm: fileForm, state: state)
            let index = BenchSet.tierOrder.firstIndex(of: tier.tier)!
            guard index > lastIndex else { throw SchemaViolation("bench.tiers[\(k)]", "tiers must be unique and in tier order") }
            lastIndex = index
            tiers.append(tier)
        }
        var sustain: Sustain?
        let sustainValue = try root.required("sustain")
        if !sustainValue.isNull {
            let s = try decodeSustain(sustainValue, state: state)
            guard tiers.contains(where: { $0.tier == s.tier }) else { throw SchemaViolation("bench.sustain.tier", "not a measured tier") }
            sustain = s
        }
        guard try root.required("energy").isNull else { throw SchemaViolation("bench.energy", "energy is deferred and must be null") }
        try root.finish()
        return BenchDocument(benchSet: setId, harness: harness, device: device, memory: memory, run: run, tiers: tiers, sustain: sustain)
    }

    private static func decodeHarness(_ o: ObjectReader) throws -> Harness {
        let shell = try o.oneOf("shell", ["android-daemon", "android-standalone", "ios-standalone", "desktop-daemon", "desktop-cli", "ios-app", "ubuntu-touch-app"])
        let coreImpl = try o.oneOf("coreImpl", ["jvm", "swift"])
        let coreVersion = try o.string("coreVersion", SchemaRules.isSemver)
        let confVersion = try o.string("confVersion", SchemaRules.isSemver)
        let timingSource = try o.oneOf("timingSource", ["host-monotonic", "stream"])
        let planSha256 = try o.string("planSha256", SchemaRules.isB64u43)
        let e = try o.object("engine")
        let engine = Engine(
            name: try e.id("name"),
            commit: try e.string("commit", SchemaRules.isCommit40),
            backend: try e.id("backend"),
            buildFlags: try decodeTextSet(e, "buildFlags", max: 16),
            threads: try e.int("threads", 0...1024),
            gpuLayers: try e.int("gpuLayers", 0...100_000),
            kvType: try e.id("kvType"),
            flashAttn: try e.oneOf("flashAttn", ["on", "off", "auto"])
        )
        try e.finish()
        try o.finish()
        return Harness(shell: shell, coreImpl: coreImpl, coreVersion: coreVersion, confVersion: confVersion,
                       timingSource: timingSource, planSha256: planSha256, engine: engine)
    }

    public static func decodeTextSet(_ o: ObjectReader, _ name: String, max: Int) throws -> [String] {
        var out: [String] = []
        for (k, item) in try o.array(name, count: 0...max).enumerated() {
            let s = try ObjectReader.asString(item, SchemaRules.isText, "\(o.path).\(name)[\(k)]")
            guard !out.contains(where: { codeUnitsEqual($0, s) }) else { throw SchemaViolation("\(o.path).\(name)", "duplicate item") }
            out.append(s)
        }
        return out
    }

    private static func decodeDevice(_ o: ObjectReader, fileForm: Bool) throws -> Device {
        let osBuild = try o.nullableText("osBuild")
        let gpuDriver = try o.nullableText("gpuDriver")
        if fileForm, osBuild != nil || gpuDriver != nil { throw SchemaViolation(o.path, "the file form carries no osBuild or gpuDriver (P8)") }
        let d = Device(
            platform: try o.oneOf("platform", platforms),
            form: try o.oneOf("form", forms),
            maker: try o.text("maker"),
            model: try o.text("model"),
            soc: try o.text("soc"),
            osVersion: try o.text("osVersion"),
            osBuild: osBuild,
            gpu: try o.nullableText("gpu"),
            gpuDriver: gpuDriver,
            memTotalBytes: try o.int("memTotalBytes", 0...SchemaRules.maxBytes),
            unifiedMemory: try o.bool("unifiedMemory"),
            virtualized: try o.bool("virtualized")
        )
        try o.finish()
        return d
    }

    private static func decodeMemory(_ o: ObjectReader) throws -> Memory {
        let m = Memory(
            availAtStartBytes: try o.int("availAtStartBytes", 0...SchemaRules.maxBytes),
            processLimitBytes: try o.nullableInt("processLimitBytes", 0...SchemaRules.maxBytes),
            gpuWorkingSetBytes: try o.nullableInt("gpuWorkingSetBytes", 0...SchemaRules.maxBytes),
            limitSource: try o.id("limitSource")
        )
        try o.finish()
        return m
    }

    private static func decodeRun(_ o: ObjectReader, fileForm: Bool) throws -> Run {
        let plan = try o.oneOf("plan", ["quick", "standard", "sustained", "battery", "extended", "ci"])
        var optIn: [String] = []
        for (k, item) in try o.array("optInTiers", count: 0...6).enumerated() {
            let s = try ObjectReader.asString(item, { BenchSet.tierOrder.contains($0) }, "\(o.path).optInTiers[\(k)]")
            guard !optIn.contains(s) else { throw SchemaViolation(o.path, "duplicate opt-in tier") }
            optIn.append(s)
        }
        let started = try o.int("startedAtMs", SchemaRules.epochLow...(SchemaRules.epochHigh - 1))
        let ended = try o.int("endedAtMs", SchemaRules.epochLow...(SchemaRules.epochHigh - 1))
        let day = try o.string("dayUtc", SchemaRules.isDate)
        let power = try o.oneOf("powerSource", ["ac", "battery"])
        let battery = try o.nullableInt("batteryStartPermille", 0...1000)
        let thermal = try o.oneOf("startThermal", thermalClasses)
        let screen = try o.nullableBool("screenOn")
        let before = try o.int("contentionBeforePermille", 0...1_000_000)
        let after = try o.int("contentionAfterPermille", 0...1_000_000)
        var abort: Abort?
        if let a = try o.nullableObject("abort") {
            abort = Abort(reason: try a.oneOf("reason", abortReasons), atMs: try a.int("atMs", 0...9_007_199_254_740_991))
            try a.finish()
        }
        if fileForm {
            guard battery == nil, screen == nil else { throw SchemaViolation(o.path, "the file form carries no battery level or screen state (P8)") }
            guard started % SchemaRules.dayMs == 0, ended % SchemaRules.dayMs == 0 else {
                throw SchemaViolation(o.path, "the file form truncates run times to the day (P8)")
            }
        }
        try o.finish()
        return Run(plan: plan, optInTiers: optIn, startedAtMs: started, endedAtMs: ended, dayUtc: day, powerSource: power,
                   batteryStartPermille: battery, startThermal: thermal, screenOn: screen, contentionBeforePermille: before,
                   contentionAfterPermille: after, abort: abort)
    }

    private static func decodeTier(_ value: JValue, _ path: String, pins: [PinnedTier], fileForm: Bool, state: DecodeState) throws -> Tier {
        let o = try ObjectReader(value, path, state)
        let tierName = try o.oneOf("tier", BenchSet.tierOrder)
        guard let pin = BenchSet.pin(tier: tierName, in: pins) else { throw SchemaViolation("\(path).tier", "no pin") }
        let sha = try o.string("sha256", SchemaRules.isSha256Hex)
        guard codeUnitsEqual(sha, pin.sha256) else { throw SchemaViolation("\(path).sha256", "differs from the compiled-in pin") }
        let started = try o.int("startedAtMs", SchemaRules.epochLow...(SchemaRules.epochHigh - 1))
        if fileForm, started % SchemaRules.dayMs != 0 { throw SchemaViolation("\(path).startedAtMs", "the file form truncates to the day (P8)") }
        var warm: [Int64] = []
        for (k, item) in try o.array("loadWarmMicros", count: 1...8).enumerated() {
            warm.append(try ObjectReader.asInt(item, 1...86_400_000_000, "\(path).loadWarmMicros[\(k)]"))
        }
        var tests: [Test] = []
        for (k, item) in try o.array("tests", count: 0...16).enumerated() {
            let t = try decodeTest(item, "\(path).tests[\(k)]", state: state)
            guard !tests.contains(where: { $0.name == t.name }) else { throw SchemaViolation("\(path).tests", "duplicate test \(t.name)") }
            tests.append(t)
        }
        let n = try ObjectReader(try o.required("numerics"), "\(path).numerics", state)
        let numerics = Numerics(
            milliNatsPerToken: try n.int("milliNatsPerToken", 0...9_007_199_254_740_991),
            refMilliNatsPerToken: try n.nullableInt("refMilliNatsPerToken", 1...9_007_199_254_740_991)
        )
        try n.finish()
        let tier = Tier(
            tier: tierName,
            sha256: sha,
            bytes: try o.int("bytes", 0...SchemaRules.maxBytes),
            quant: try o.string("quant", SchemaRules.isQuant),
            startThermal: try o.oneOf("startThermal", thermalClasses),
            startThermalCode: try o.int("startThermalCode", 0...4),
            startedAtMs: started,
            nCtx: try o.int("nCtx", 1...10_000_000),
            availBeforeLoadBytes: try o.int("availBeforeLoadBytes", 0...SchemaRules.maxBytes),
            peakFootprintBytes: try o.int("peakFootprintBytes", 0...SchemaRules.maxBytes),
            kvBytesPerToken: try o.int("kvBytesPerToken", 0...SchemaRules.maxBytes),
            loadColdMicros: try o.int("loadColdMicros", 1...86_400_000_000),
            loadColdness: try o.oneOf("loadColdness", ["evicted", "best-effort", "unknown"]),
            loadWarmMicros: warm,
            tests: tests,
            numerics: numerics,
            restarts: try o.int("restarts", 0...2),
            swapDeltaBytes: try o.nullableInt("swapDeltaBytes", 0...SchemaRules.maxBytes)
        )
        try o.finish()
        return tier
    }

    private static func decodeTest(_ value: JValue, _ path: String, state: DecodeState) throws -> Test {
        let o = try ObjectReader(value, path, state)
        let name = try o.string("test")
        guard let (kind, tokens, depth) = parseTestName(name) else { throw SchemaViolation("\(path).test", "not a test name") }
        func samples(_ field: String) throws -> [Int64] {
            var out: [Int64] = []
            for (k, item) in try o.array(field, count: 1...16).enumerated() {
                out.append(try ObjectReader.asInt(item, 1...86_400_000_000, "\(path).\(field)[\(k)]"))
            }
            return out
        }
        let s = try samples("samples")
        var whole: [Int64]?
        if o.has("wholeSamples") { whole = try samples("wholeSamples") }
        switch kind {
        case .prefill:
            guard depth == 0 else { throw SchemaViolation(path, "prefill at depth cannot be carried (benchmark.md 13.4 R5)") }
            guard let w = whole, w.count == s.count else { throw SchemaViolation(path, "a prefill test carries one whole span per rep") }
        case .decode:
            guard whole == nil else { throw SchemaViolation(path, "a decode test has no whole spans") }
        }
        try o.finish()
        return Test(name: name, kind: kind, tokens: tokens, depth: depth, samples: s, wholeSamples: whole)
    }

    /// `^(pp|tg)[0-9]{1,7}@d[0-9]{1,8}$`
    public static func parseTestName(_ name: String) -> (Test.Kind, Int64, Int64)? {
        let b = Array(name.utf8)
        guard b.count >= 6, b[0] == 0x70 || b[0] == 0x74 else { return nil }
        let kind: Test.Kind
        if b[0] == 0x70, b[1] == 0x70 { kind = .prefill } else if b[0] == 0x74, b[1] == 0x67 { kind = .decode } else { return nil }
        var k = 2
        var tokens: Int64 = 0
        var digits = 0
        while k < b.count, b[k] >= 0x30, b[k] <= 0x39 { tokens = tokens * 10 + Int64(b[k] - 0x30); digits += 1; k += 1 }
        guard (1...7).contains(digits), k + 1 < b.count, b[k] == 0x40, b[k + 1] == 0x64 else { return nil }
        k += 2
        var depth: Int64 = 0
        var ddigits = 0
        while k < b.count, b[k] >= 0x30, b[k] <= 0x39 { depth = depth * 10 + Int64(b[k] - 0x30); ddigits += 1; k += 1 }
        guard (1...8).contains(ddigits), k == b.count, tokens >= 1 else { return nil }
        return (kind, tokens, depth)
    }

    private static func decodeSustain(_ value: JValue, state: DecodeState) throws -> Sustain {
        let o = try ObjectReader(value, "bench.sustain", state)
        var windows: [Window] = []
        for (k, item) in try o.array("windows", count: 1...240).enumerated() {
            guard case let .array(cells) = item, cells.count == 4 else { throw SchemaViolation("bench.sustain.windows[\(k)]", "not a 4-tuple") }
            let p = "bench.sustain.windows[\(k)]"
            windows.append(Window(
                tStartMs: try ObjectReader.asInt(cells[0], 0...86_400_000, p),
                tokens: try ObjectReader.asInt(cells[1], 1...10_000_000, p),
                micros: try ObjectReader.asInt(cells[2], 1...3_600_000_000, p),
                thermalCode: try ObjectReader.asInt(cells[3], 0...4, p)
            ))
        }
        let s = Sustain(
            tier: try o.oneOf("tier", BenchSet.tierOrder),
            windowMs: try o.int("windowMs", 100...3_600_000),
            capMs: try o.int("capMs", 1...86_400_000),
            endReason: try o.oneOf("endReason", endReasons),
            headroomAtOnsetPermille: try o.nullableInt("headroomAtOnsetPermille", 0...10_000),
            windows: windows
        )
        try o.finish()
        return s
    }
}
