@testable import AsomBenchCore
import AsomJSON
import XCTest

/// The typed decoder of `asom.bench/1` (LAB_SPEC.md 4.1 P6 to P9, benchmark.md 13.2, design B4) and the shape predicates.
final class DecoderTests: XCTestCase {
    private func rejects(_ v: JValue, file: Bool = false, tolerant: Bool = false, _ message: String, line: UInt = #line) {
        if case .success = decodeBench(v, file: file, tolerant: tolerant) { XCTFail("accepted: \(message)", line: line) }
    }

    func testTheWorkedExampleDecodes() throws {
        XCTAssertNoThrow(try decodeBench(try designExample()).get())
    }

    func testForbiddenMemberNamesAtAnyDepth() throws {
        let base = try designExample()
        for name in ["derived", "render", "textSha256", "field", "custom"] {
            rejects(base.setting([.key(name)], to: .int(1)), "top-level \(name)")
            rejects(base.setting([.key("tiers"), .at(0), .key("tests"), .at(0), .key(name)], to: .int(1)), "deep \(name)")
            // Also where tolerant decoding would otherwise let an unknown member through.
            rejects(base.setting([.key("harness"), .key("engine"), .key(name)], to: .null), tolerant: true, "tolerated-minor \(name)")
        }
    }

    func testProtocolEnergyAndSchemaConstants() throws {
        let base = try designExample()
        rejects(base.setting([.key("benchProtocol")], to: .int(2)), "benchProtocol 2")
        rejects(base.setting([.key("schema")], to: .string("asom.bench/2")), "schema 2")
        rejects(base.setting([.key("energy")], to: .object([])), "energy must be null")
        rejects(base.removing([.key("energy")]), "energy is required")
    }

    func testUnknownMembersAreInvalidAtTheKnownMinorAndCountedAbove() throws {
        let base = try designExample().setting([.key("device"), .key("npuOffloadPermille")], to: .int(5))
        rejects(base, "unknown member at the known minor")
        let state = DecodeState(tolerateUnknown: true)
        XCTAssertNoThrow(try BenchDocument.decode(base, fileForm: false, state: state))
        XCTAssertEqual(state.unknownMembers, 1)
    }

    func testBenchSetPins() throws {
        let base = try designExample()
        rejects(base.setting([.key("benchSet")], to: .string("llama-instruct-1")), "L1 has no pins")
        rejects(base.setting([.key("benchSet")], to: .string("qwen3-dense-2")), "unknown set")
        rejects(base.setting([.key("tiers"), .at(0), .key("sha256")], to: .string(String(repeating: "0", count: 64))), "sha256 differs from the pin")
        rejects(base.setting([.key("tiers"), .at(0), .key("tier")], to: .string("T9")), "unknown tier")
    }

    func testTiersMustBeUniqueAndInTierOrder() throws {
        let base = try designExample()
        let tiers = try XCTUnwrap(base.value(at: [.key("tiers")])?.elements)
        rejects(base.setting([.key("tiers")], to: .array([tiers[1], tiers[0], tiers[2]])), "out of order")
        rejects(base.setting([.key("tiers")], to: .array([tiers[0], tiers[0]])), "duplicate")
    }

    func testDisplayStringHygieneCountsCodePoints() throws {
        let base = try designExample()
        func model(_ s: String) -> JValue { base.setting([.key("device"), .key("model")], to: .string(s)) }
        let astral = "\u{1F600}"
        XCTAssertNoThrow(try decodeBench(model(String(repeating: astral, count: 96))).get())
        rejects(model(String(repeating: astral, count: 97)), "97 code points")
        rejects(model(""), "empty")
        for bad in ["\u{0}", "\u{1F}", "\u{7F}", "\u{9F}", "\u{61C}", "\u{200E}", "\u{200F}", "\u{2028}", "\u{2029}", "\u{202A}", "\u{202E}", "\u{2066}", "\u{2069}", "\u{FEFF}"] {
            rejects(model("a" + bad + "b"), "U+\(String(bad.unicodeScalars.first!.value, radix: 16))")
        }
        XCTAssertNoThrow(try decodeBench(model("\u{A0}ok\u{2065}\u{202F}")).get(), "neighbours of the blocked ranges are allowed")
    }

    func testIdentifierAndShapePredicates() {
        XCTAssertTrue(SchemaRules.isID("qwen3-8b"))
        XCTAssertTrue(SchemaRules.isID("a"))
        XCTAssertTrue(SchemaRules.isID("a.b_c+d-e"))
        XCTAssertFalse(SchemaRules.isID(""))
        XCTAssertFalse(SchemaRules.isID("-a"))
        XCTAssertFalse(SchemaRules.isID("A"))
        XCTAssertFalse(SchemaRules.isID(String(repeating: "a", count: 65)))
        XCTAssertTrue(SchemaRules.isID(String(repeating: "a", count: 64)))
        XCTAssertTrue(SchemaRules.isSemver("0.2.0"))
        XCTAssertFalse(SchemaRules.isSemver("01.2.0"))
        XCTAssertFalse(SchemaRules.isSemver("1.2"))
        XCTAssertFalse(SchemaRules.isSemver("1.2.3.4"))
        XCTAssertFalse(SchemaRules.isSemver("123456.0.0"))
        XCTAssertEqual(SchemaRules.compareSemver("0.1.0", "0.2.0"), -1)
        XCTAssertEqual(SchemaRules.compareSemver("0.10.0", "0.9.0"), 1)
        XCTAssertEqual(SchemaRules.compareSemver("1.0.0", "1.0.0"), 0)
        XCTAssertTrue(SchemaRules.isDate("2026-09-29"))
        XCTAssertFalse(SchemaRules.isDate("2026-13-01"))
        XCTAssertFalse(SchemaRules.isDate("2026-00-10"))
        XCTAssertFalse(SchemaRules.isDate("2010-01-01"))
        XCTAssertTrue(SchemaRules.isB64u43(String(repeating: "A", count: 43)))
        XCTAssertFalse(SchemaRules.isB64u43(String(repeating: "A", count: 44)))
        XCTAssertFalse(SchemaRules.isB64u43(String(repeating: "+", count: 43)))
        XCTAssertTrue(SchemaRules.isMethodID("asom-bench-method/1"))
        XCTAssertFalse(SchemaRules.isMethodID("asom-bench-method"))
        XCTAssertFalse(SchemaRules.isMethodID("asom-bench-method/12345"))
    }

    func testTestNameGrammar() {
        XCTAssertNotNil(BenchDocument.parseTestName("pp512@d0"))
        XCTAssertNotNil(BenchDocument.parseTestName("tg128@d32768"))
        XCTAssertNil(BenchDocument.parseTestName("pp512"))
        XCTAssertNil(BenchDocument.parseTestName("xx512@d0"))
        XCTAssertNil(BenchDocument.parseTestName("pp@d0"))
        XCTAssertNil(BenchDocument.parseTestName("pp12345678@d0"))
        XCTAssertNil(BenchDocument.parseTestName("pp512@d123456789"))
        XCTAssertNil(BenchDocument.parseTestName("pp512@d0 "))
        XCTAssertNil(BenchDocument.parseTestName("pp0@d0"))
    }

    func testSampleShapeAndBounds() throws {
        let base = try designExample()
        let p: [Step] = [.key("tiers"), .at(0), .key("tests"), .at(1)]
        rejects(base.setting(p + [.key("samples")], to: .ints([])), "no samples")
        rejects(base.setting(p + [.key("samples")], to: .ints([0, 1, 1])), "a zero sample")
        rejects(base.setting(p + [.key("samples")], to: .ints([Int64](repeating: 1, count: 17))), "17 samples")
        rejects(base.setting(p + [.key("samples")], to: .ints([86_400_000_001])), "beyond a day of microseconds")
        rejects(base.setting([.key("tiers"), .at(0), .key("tests"), .at(0), .key("wholeSamples")], to: .ints([1, 2])), "whole spans of another length")
        rejects(base.removing([.key("tiers"), .at(0), .key("tests"), .at(0), .key("wholeSamples")]), "a prefill test needs its whole spans")
        rejects(base.setting(p + [.key("wholeSamples")], to: .ints([1, 2, 3, 4, 5])), "a decode test has no whole spans")
        rejects(base.setting([.key("tiers"), .at(0), .key("tests"), .at(0), .key("test")], to: .string("pp512@d2048")), "prefill at depth")
    }

    func testSustainBlockShape() throws {
        let base = try designExample()
        rejects(base.setting([.key("sustain"), .key("tier")], to: .string("T1")).setting([.key("tiers")], to: .array([])), "sustain tier not measured")
        rejects(base.setting([.key("sustain"), .key("windows")], to: .array([])), "no windows")
        rejects(base.setting([.key("sustain"), .key("windows"), .at(0)], to: .ints([0, 1, 2])), "a 3-tuple window")
        rejects(base.setting([.key("sustain"), .key("windows"), .at(0)], to: .ints([0, 1, 2, 5])), "thermal code 5")
        rejects(base.setting([.key("sustain"), .key("endReason")], to: .string("BORED")), "unknown end reason")
    }

    func testFileFormRulesOfP8() throws {
        let file = Projection.fileBench(try designExample())
        XCTAssertNoThrow(try decodeBench(file, file: true).get())
        rejects(file.setting([.key("device"), .key("osBuild")], to: .string("X")), file: true, "osBuild in a file")
        rejects(file.setting([.key("device"), .key("gpuDriver")], to: .string("X")), file: true, "gpuDriver in a file")
        rejects(file.setting([.key("run"), .key("batteryStartPermille")], to: .int(500)), file: true, "battery in a file")
        rejects(file.setting([.key("run"), .key("screenOn")], to: .bool(true)), file: true, "screenOn in a file")
        rejects(file.setting([.key("run"), .key("startedAtMs")], to: .int(1_790_640_000_001)), file: true, "start not on a day boundary")
        rejects(file.setting([.key("run"), .key("endedAtMs")], to: .int(1_790_640_000_001)), file: true, "end not on a day boundary")
        rejects(file.setting([.key("tiers"), .at(0), .key("startedAtMs")], to: .int(1_790_640_000_001)), file: true, "tier start not on a day boundary")
    }

    func testTypesAreStrict() throws {
        let base = try designExample()
        rejects(base.setting([.key("device"), .key("virtualized")], to: .int(0)), "a number for a boolean")
        rejects(base.setting([.key("device"), .key("memTotalBytes")], to: .string("16")), "a string for a number")
        rejects(base.setting([.key("device"), .key("memTotalBytes")], to: .int(SchemaRules.maxBytes + 1)), "bytes over 2^50")
        rejects(base.setting([.key("run"), .key("startedAtMs")], to: .int(1_577_836_799_999)), "before 2020")
        rejects(base.setting([.key("run"), .key("plan")], to: .string("turbo")), "unknown plan")
        rejects(base.setting([.key("device"), .key("platform")], to: .string("beos")), "unknown platform")
        rejects(base.setting([.key("harness"), .key("engine"), .key("commit")], to: .string("0123456")), "a short commit in the bench document")
        rejects(base.setting([.key("harness"), .key("planSha256")], to: .string("short")), "plan hash shape")
    }

    /// The decoder and everything after it must never trap on hostile input (fixed seed, so a failure reproduces).
    func testSeededMutationsNeverTrap() throws {
        var rng = SplitMix64(seed: 0xA50)
        let base = try designExample()
        var accepted = 0, rejected = 0
        for _ in 0..<600 {
            let mutated = mutate(base, &rng)
            switch decodeBench(mutated, tolerant: rng.next() % 2 == 0) {
            case let .success(doc):
                accepted += 1
                do {
                    let derived = try Derivation.derive(doc)
                    _ = try Projection.results(doc: doc, derived: derived, audience: .own)
                    let text = TextRenderer.render(doc, derived)
                    XCTAssertTrue(text.utf8.allSatisfy { ($0 >= 0x20 && $0 < 0x7F) || $0 == 0x0A })
                } catch is BenchArithmeticError {
                } catch {
                    XCTFail("\(error)")
                }
            case .failure:
                rejected += 1
            }
        }
        XCTAssertGreaterThan(rejected, 50, "the mutations must actually break documents")
        XCTAssertGreaterThan(accepted, 5, "and some must stay valid so that derive and render are exercised")
    }
}

struct SplitMix64 {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}

/// Replaces one randomly chosen node of the tree with a hostile value or removes it.
func mutate(_ v: JValue, _ rng: inout SplitMix64) -> JValue {
    var paths: [[Step]] = []
    func walk(_ node: JValue, _ path: [Step]) {
        paths.append(path)
        switch node {
        case let .object(m): for x in m { walk(x.value, path + [.key(x.name)]) }
        case let .array(a): for (k, x) in a.enumerated() { walk(x, path + [.at(k)]) }
        default: break
        }
    }
    walk(v, [])
    let path = paths[Int(rng.next() % UInt64(paths.count))]
    if path.isEmpty { return v }
    let hostile: [JValue] = [.null, .int(0), .int(-1), .int(1), .int(Int64.max / 2), .int(9_007_199_254_740_991), .string(""), .string("x"), .bool(true), .array([]), .object([])]
    switch Int(rng.next() % 4) {
    case 0: return v.removing(path)
    case 1: return v.setting(path, to: hostile[Int(rng.next() % UInt64(hostile.count))])
    case 2:
        if let cur = v.value(at: path)?.intValue { return v.setting(path, to: .int(cur &+ Int64(rng.next() % 3) - 1)) }
        return v.setting(path, to: hostile[Int(rng.next() % UInt64(hostile.count))])
    default:
        if let cur = v.value(at: path)?.intValue { return v.setting(path, to: .int(cur * 2)) }
        return v.setting(path, to: hostile[Int(rng.next() % UInt64(hostile.count))])
    }
}
