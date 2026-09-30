/// `asom.text/1`, the plain-text report body (benchmark.md section 12; LAB_SPEC.md 4.8). ASCII 0x20-0x7E plus LF,
/// at most 72 columns, a fixed English template, integer arithmetic only.
public struct RenderOptions: Sendable {
    /// LAB_SPEC.md 4.8: the shell sets it when the mesh exists. Default false (question 5 says "not applicable").
    public var meshAvailable = false
    /// MLPERF_NOTE_ENABLED. While false the note never renders; it never renders on a default (Q1) run whatever the flag.
    public var mlperfNoteEnabled = false

    public init(meshAvailable: Bool = false, mlperfNoteEnabled: Bool = false) {
        self.meshAvailable = meshAvailable
        self.mlperfNoteEnabled = mlperfNoteEnabled
    }
}

public enum TextRenderer {
    public static let maxColumns = 72

    // MARK: - Formatting (integer arithmetic, benchmark.md 12.2 T3)

    /// Non-ASCII becomes '?', one per code point.
    public static func asciiOnly(_ s: String) -> String {
        var out = ""
        for scalar in s.unicodeScalars { out.unicodeScalars.append(scalar.value >= 0x20 && scalar.value < 0x7F ? scalar : "?") }
        return out
    }

    /// Speeds are floored to 0.1 token/s.
    public static func rate(_ mtps: Int64) -> String {
        let t = mtps / 100
        return "\(t / 10).\(t % 10)"
    }

    /// Capacity floors to 0.1 GB (10^9).
    public static func gb(_ bytes: Int64) -> String {
        let t = bytes / 100_000_000
        return "\(t / 10).\(t % 10) GB"
    }

    /// File sizes round half up to 0.1 GB.
    public static func gbFile(_ bytes: Int64) -> String {
        let t = (bytes + 50_000_000) / 100_000_000
        return "\(t / 10).\(t % 10) GB"
    }

    /// Durations: measured ones floor to 5 s, estimated ones to 10 s; estimated ones read "about".
    public static func duration(micros us: Int64, estimated: Bool) -> String {
        let s = us / 1_000_000
        var out: String
        if us < 10_000_000 {
            let t = us / 100_000
            out = "\(t / 10).\(t % 10) s"
        } else if s < 60 {
            out = "\(s) s"
        } else if s < 600 {
            let m = s / 60
            var r = s % 60
            r = estimated ? (r / 10) * 10 : (r / 5) * 5
            out = r == 0 ? "\(m) min" : "\(m) min \(r) s"
        } else {
            out = "\(s / 60) min"
        }
        return estimated ? "about " + out : out
    }

    /// Percentages are floored.
    public static func percent(permille: Int64) -> String { "\(permille / 10)%" }

    public static func modelLabel(_ pin: PinnedTier) -> String {
        "\(pin.displayName) \(pin.quant.hasPrefix("Q4") ? "4-bit" : "8-bit")"
    }

    static func capitalized(_ s: String) -> String {
        guard let first = s.first else { return s }
        return first.uppercased() + s.dropFirst()
    }

    static let verdictText = [
        "comfortable": "COMFORTABLE", "usable": "USABLE, NOT COMFORTABLE", "too-slow": "TOO SLOW FOR CHAT",
        "cannot-hold": "CANNOT HOLD IT", "not-measured": "NOT MEASURED",
    ]

    static let power = ["ac": "on charger", "battery": "on battery"]

    static let testWords = [
        "pp512@d0": "reading a 512-token prompt", "pp2048@d0": "reading a 2048-token prompt",
        "tg128@d0": "writing 128 tokens", "tg128@d2048": "writing 128 tokens 2k tokens into a chat",
        "tg128@d8192": "writing 128 tokens 8k tokens into a chat",
    ]

    static func roleLines(_ code: String) -> (String, [String]) {
        switch code {
        case "strong-provider":
            return ("STRONG PROVIDER", ["can serve 7-8B models to your other devices."])
        case "small-model-provider":
            return ("PROVIDER FOR SMALL MODELS", ["can serve 4B-class models to your", "other devices."])
        case "occasional-helper":
            return ("OCCASIONAL HELPER", ["can run small models for your", "other devices while charging; send 7-8B work to a stronger", "device when one is available."])
        case "requester-foreground-helper":
            return ("REQUESTER (HELPS ONLY WHILE OPEN)", ["iPhone and iPad", "apps cannot serve other devices in the background; an iPad can", "lend compute while the app is open."])
        default:
            return ("REQUESTER", ["best used to send work to your other devices."])
        }
    }

    // MARK: - Render

    public static func render(_ doc: BenchDocument, _ derived: Derived, options: RenderOptions = RenderOptions()) -> String {
        var lines: [String] = []
        func add(_ s: String) { lines.append(s) }
        let a = derived.answers
        let dev = doc.device
        let run = doc.run
        let eng = doc.harness.engine

        add("ASOM DEVICE REPORT (asom.text/1)")
        add("Generated from this device's benchmark data. (measured) = timed on")
        add("this device. (estimated) = calculated from measured numbers.")
        add("")
        add("DEVICE (as reported by the device itself)")
        add(wrapDevice("  \(asciiOnly(dev.maker)) \(asciiOnly(dev.model)) - \(dev.platform) \(asciiOnly(dev.osVersion))"))
        add("  Chip: \(asciiOnly(dev.soc)) | Memory: \(gb(dev.memTotalBytes)) total, \(gb(doc.memory.availAtStartBytes)) free at start")
        add("  Engine: \(eng.name) \(eng.commit.prefix(8)), \(eng.backend) backend")
        add("  Tested: \(run.dayUtc), \(run.plan) test, \(power[run.powerSource]!), started \(run.startThermal)")
        add("  Overall confidence: \(a.overallConfidence.name.uppercased())")
        if dev.virtualized { add("  VIRTUAL MACHINE: results are capped at MEDIUM confidence.") }
        add("")
        add("ANSWERS")

        let t3Drifted = derived.tier("T3")?.result("tg128@d0")?.stat.flags.contains("THERMAL_DRIFT") ?? false
        let q = a.q7b
        add("1. Can it run a 7-8B model comfortably?")
        if q.basis == "measured" || q.basis == "estimated" {
            let est = q.basis == "estimated"
            let pin = BenchSet.q1.first { $0.tier == "T3" }!
            add("   \(verdictText[q.verdict]!) (\(q.basis) with \(modelLabel(pin))).")
            if t3Drifted && !est {
                add("   First-minute speed: about \(rate(q.decodeMtps!)) tokens/s, and it starts")
                add("   answering a 512-token prompt after \(duration(micros: q.ttft512Micros!, estimated: false)). We call a model")
                add("   comfortable at 10 tokens/s or more and under 2 s to start.")
            } else {
                add("   It writes about \(rate(q.decodeMtps!)) tokens/s and starts answering a 512-token")
                add("   prompt after \(duration(micros: q.ttft512Micros!, estimated: est)). We call a model comfortable at 10")
                add("   tokens/s or more and under 2 s to start.")
            }
        } else if q.basis == "cannot-hold" {
            add("   \(verdictText[q.verdict]!).")
        } else {
            add("   \(verdictText[q.verdict]!).")
        }

        add("2. What is the largest model it can hold?")
        if let mh = a.maxHold, let l = derived.tier(mh.largestLoadedTier) {
            add("   About \(gb(mh.weightBytes)) of model file (estimated), roughly a")
            add("   \(mh.approxParamsQ4 / 1_000_000_000)-billion-parameter model at 4-bit. Largest actually loaded:")
            add("   \(modelLabel(l.pin)), \(gbFile(l.pin.bytes)) file (measured).")
        } else {
            add("   Not measured (no model was loaded).")
        }

        add("3. How long will a 2000-token answer take?")
        if let ans = a.answer2000, let t = derived.tier(ans.tier) {
            add("   \(capitalized(duration(micros: ans.micros, estimated: true))) with \(modelLabel(t.pin)) for a 512-token")
            let heatTestUsed = ans.thermalModel && a.throttle?.onsetMs != nil
            add("   prompt, starting cool (estimated from the measured speeds" + (heatTestUsed ? " and" : ")."))
            if heatTestUsed { add("   heat test).") }
        } else {
            add("   Not measured (no model finished the speed tests).")
        }

        add("4. Will it slow down when it gets warm?")
        if let th = a.throttle, let t = derived.tier(th.tier) {
            if let onset = th.onsetMs {
                add("   Yes. After \(duration(micros: onset * 1000, estimated: false)) of continuous writing, speed fell to")
                add("   \(percent(permille: th.stabilityPermille)) of its starting speed and stayed there (measured for")
                add("   \(duration(micros: th.testedMs * 1000, estimated: false)), \(power[run.powerSource]!), with \(modelLabel(t.pin))).")
            } else {
                add("   No slowdown seen during \(duration(micros: th.testedMs * 1000, estimated: false)) of continuous writing")
                add("   (\(power[run.powerSource]!), started \(run.startThermal); measured).")
            }
        } else {
            add("   Not measured (the heat test was not run).")
        }

        add("5. What role suits it in a group of your devices?")
        if options.meshAvailable {
            let (name, body) = roleLines(a.role.code)
            add("   \(name): \(body[0])")
            for l in body.dropFirst() { add("   \(l)") }
            if a.role.estimatedBasis { add("   Basis: estimated from the measured speeds and heat test.") }
        } else {
            add("   Not applicable: this version does not share work between devices.")
        }

        add("")
        add("DETAILS (tokens per second, higher is better; start = seconds)")
        add("  model              size     read   write  write@2k  start@512")
        for t in derived.tiers {
            func cell(_ name: String, rateCell: Bool) -> String {
                guard let r = t.result(name), let v = r.stat.value else { return "-" }
                if rateCell { return rate(v) }
                guard let ttft = r.ttftMicros else { return "-" }
                return dropSuffix(duration(micros: ttft, estimated: false), " s", keep: "")
            }
            let label = pad(modelLabel(t.pin), 18)
            let size = pad(dropSuffix(gbFile(t.pin.bytes), " GB", keep: "GB"), 7)
            add("  \(label) \(size) \(padLeft(cell("pp512@d0", rateCell: true), 6))  \(padLeft(cell("tg128@d0", rateCell: true), 6))  \(padLeft(cell("tg128@d2048", rateCell: true), 8))  \(padLeft(cell("pp512@d0", rateCell: false), 9))")
        }

        add("")
        add("NOTES")
        var notes: [String] = []
        for t in derived.tiers {
            let label = modelLabel(t.pin)
            for r in t.tests {
                let words = testWords[r.test.name] ?? r.test.name
                if r.stat.flags.contains("OUTLIER_EXCLUDED") {
                    notes.append("\(label), \(words): \(r.stat.n - r.stat.kept) of \(r.stat.n) timings discarded as outliers.")
                }
                if r.stat.flags.contains("THERMAL_DRIFT") {
                    notes.append("\(label), \(words): speed drifted during the test.")
                }
                if r.stat.confidence <= .low {
                    notes.append("\(label), \(words): \(r.stat.confidence.name) confidence.")
                }
            }
            if let dev = t.deviationPermille {
                notes.append("\(label) output check: \(t.numerics) (\(dev / 10).\(dev % 10)% from reference).")
            } else {
                notes.append("\(label) output check: \(t.numerics).")
            }
        }
        if let s = derived.sustain {
            if s.flags.contains("PLATEAU_NOT_REACHED") { notes.append("Heat test: the slowed-down speed had not settled when the test ended.") }
            if s.flags.contains("HARD_CEILING") { notes.append("Heat test: stopped early at a safety limit.") }
        }
        if let abort = doc.run.abort {
            notes.append("The run stopped early (\(abort.reason)); only the finished tests are shown.")
        }
        notes.append("Battery use: not measured (needs an unplugged battery test).")
        for n in notes { lines.append(contentsOf: wrapNote(n)) }

        add("")
        add("WHAT THIS REPORT DOES NOT TELL YOU")
        for s in [
            "- This text proves nothing by itself. It can be checked only by",
            "  opening the signed report in a verifier. A valid signature shows",
            "  the report is unchanged since signing and which key signed it,",
            "  not that the test was honest.",
            "- Speeds are for the listed test models. Other models of the same size",
            "  usually behave alike, but not always (mixture-of-experts models",
            "  differ most).",
            "- Heat, battery level, other apps and long conversations change speed.",
            "- Nothing here measures how good the answers are.",
        ] { add(s) }
        return lines.joined(separator: "\n") + "\n"
    }

    static func dropSuffix(_ s: String, _ suffix: String, keep: String) -> String {
        s.hasSuffix(suffix) ? String(s.dropLast(suffix.count)) + keep : s
    }

    static func pad(_ s: String, _ width: Int) -> String { s.count >= width ? s : s + String(repeating: " ", count: width - s.count) }
    static func padLeft(_ s: String, _ width: Int) -> String { s.count >= width ? s : String(repeating: " ", count: width - s.count) + s }

    /// A long device line wraps at 72 columns like a note, continuation lines indented four spaces.
    static func wrapDevice(_ line: String) -> String {
        guard line.count > maxColumns else { return line }
        let words = line.dropFirst(2).split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        var out: [String] = []
        var current = "  " + words[0]
        for w in words.dropFirst() {
            if current.count + 1 + w.count > maxColumns {
                out.append(current)
                current = "    " + w
            } else {
                current += " " + w
            }
        }
        out.append(current)
        return out.joined(separator: "\n")
    }

    static func wrapNote(_ note: String) -> [String] {
        var out: [String] = []
        var line = "  -"
        for w in note.split(separator: " ", omittingEmptySubsequences: false) {
            if line.count + 1 + w.count > maxColumns {
                out.append(line)
                line = "    " + w
            } else {
                line += " " + w
            }
        }
        out.append(line)
        return out
    }
}
