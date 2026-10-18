import Foundation
import XCTest
@testable import HelperProtocol

/// Shared with the Kotlin lane: helper-protocol/vectors/*.jsonl. The two lanes print the same line per vector and
/// scripts/check-protocol-lanes.sh diffs them. Set ASOM_VECTOR_LINES_OUT to write this lane's lines to a file.
struct Vector {
    let id: String
    let kind: String
    let op: String?
    let line: [UInt8]
    let canonical: [UInt8]?
    let verdict: String
    let code: String
    // exchange
    let request: [UInt8]?
    let requestVerdict: String?
    let requestCode: String?
    let response: [UInt8]?
}

enum VectorLoader {
    static var directory: URL {
        if let d = ProcessInfo.processInfo.environment["ASOM_VECTORS_DIR"] { return URL(fileURLWithPath: d) }
        // .../helper/Tests/HelperTests/VectorTests.swift -> .../helper-protocol/vectors
        var u = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { u.deleteLastPathComponent() }
        return u.appendingPathComponent("helper-protocol/vectors")
    }

    static func expand(_ s: String, pad: Int?, zeros: Int?) -> String {
        var out = s
        if let p = pad { out = out.replacingOccurrences(of: "{PAD}", with: String(repeating: "A", count: p)) }
        if let z = zeros { out = out.replacingOccurrences(of: "{ZEROS}", with: StrictBase64.encode([UInt8](repeating: 0, count: z))) }
        return out
    }

    static func hex(_ s: String) -> [UInt8] {
        var out = [UInt8]()
        var it = s.makeIterator()
        while let a = it.next(), let b = it.next() { out.append(UInt8(String([a, b]), radix: 16)!) }
        return out
    }

    static func load(file: String) throws -> [Vector] {
        let text = try String(contentsOf: directory.appendingPathComponent(file), encoding: .utf8)
        var out = [Vector]()
        for raw in text.split(separator: "\n", omittingEmptySubsequences: true) {
            guard case .object(let o) = try StrictJSON.parse(Array(raw.utf8)) else { throw XCTSkip("bad vector line") }
            func str(_ k: String) -> String? { if case .string(let s)? = o[k] { return s }; return nil }
            func int(_ k: String) -> Int? { if case .int(let n)? = o[k] { return Int(n) }; return nil }
            let pad = int("pad"), zeros = int("zeros")
            let lineBytes: [UInt8]
            if let h = str("lineHex") { lineBytes = hex(h) } else { lineBytes = Array(expand(str("line") ?? "", pad: pad, zeros: zeros).utf8) }
            out.append(Vector(
                id: str("id")!, kind: str("kind")!, op: str("op"), line: lineBytes,
                canonical: str("canonical").map { Array(expand($0, pad: pad, zeros: zeros).utf8) },
                verdict: str("verdict")!, code: str("code")!,
                request: str("request").map { Array(expand($0, pad: pad, zeros: zeros).utf8) },
                requestVerdict: str("requestVerdict"), requestCode: str("requestCode"),
                response: str("response").map { Array(expand($0, pad: pad, zeros: zeros).utf8) }
            ))
        }
        return out
    }
}

final class VectorTests: XCTestCase {
    static let files = [
        "events-accept.jsonl", "events-reject.jsonl", "exchanges.jsonl", "requests-accept.jsonl", "requests-reject.jsonl",
        "responses-accept.jsonl", "responses-reject.jsonl",
    ]

    private func s(_ b: [UInt8]) -> String { String(decoding: b, as: UTF8.self) }

    /// One lane line per vector, in file order.
    private func run(_ v: Vector, dispatcher: Dispatcher) -> String {
        switch v.kind {
        case "request":
            do {
                let r = try Codec.decodeRequest(v.line)
                XCTAssertEqual(v.verdict, "accept", "\(v.id) decoded but the vector says reject")
                let enc = try Codec.encode(r)
                XCTAssertEqual(s(enc), s(v.canonical ?? []), "\(v.id) canonical")
                return "\(v.id)\taccept\t-\t\(s(enc))"
            } catch let e as Reject {
                XCTAssertEqual(v.verdict, "reject", "\(v.id) rejected as \(e.code.rawValue) but the vector says accept")
                XCTAssertEqual(e.code.rawValue, v.code, "\(v.id) reject code")
                return "\(v.id)\treject\t\(e.code.rawValue)\t-"
            } catch { XCTFail("\(v.id): \(error)"); return "" }
        case "response":
            do {
                let r = try Codec.decodeReply(op: v.op!, v.line)
                XCTAssertEqual(v.verdict, "accept", "\(v.id) decoded but the vector says reject")
                let enc = try Codec.encode(r)
                XCTAssertEqual(s(enc), s(v.canonical ?? []), "\(v.id) canonical")
                return "\(v.id)\taccept\t-\t\(s(enc))"
            } catch let e as Reject {
                XCTAssertEqual(v.verdict, "reject", "\(v.id) rejected as \(e.code.rawValue) but the vector says accept")
                XCTAssertEqual(e.code.rawValue, v.code, "\(v.id) reject code")
                return "\(v.id)\treject\t\(e.code.rawValue)\t-"
            } catch { XCTFail("\(v.id): \(error)"); return "" }
        case "event":
            do {
                let r = try Codec.decodeEvent(v.line)
                XCTAssertEqual(v.verdict, "accept", "\(v.id) decoded but the vector says reject")
                let enc = try Codec.encode(r)
                XCTAssertEqual(s(enc), s(v.canonical ?? []), "\(v.id) canonical")
                return "\(v.id)\taccept\t-\t\(s(enc))"
            } catch let e as Reject {
                XCTAssertEqual(v.verdict, "reject", "\(v.id) rejected as \(e.code.rawValue) but the vector says accept")
                XCTAssertEqual(e.code.rawValue, v.code, "\(v.id) reject code")
                return "\(v.id)\treject\t\(e.code.rawValue)\t-"
            } catch { XCTFail("\(v.id): \(error)"); return "" }
        case "exchange":
            let req = v.request!, resp = v.response!
            // (a) the codec's verdict on the raw request
            var op: String? = nil
            do {
                let r = try Codec.decodeRequest(req)
                op = r.op
                XCTAssertEqual(v.requestVerdict, "accept", "\(v.id) request decoded but the vector says reject")
            } catch let e as Reject {
                XCTAssertEqual(v.requestVerdict, "reject", "\(v.id) request rejected as \(e.code.rawValue)")
                XCTAssertEqual(e.code.rawValue, v.requestCode, "\(v.id) request reject code")
            } catch { XCTFail("\(v.id): \(error)") }
            // (b) the recorded response is canonical and decodes (an error response does not depend on the op)
            var canonicalResponse = ""
            do {
                let reply = try Codec.decodeReply(op: op ?? "hello", resp)
                canonicalResponse = s(try Codec.encode(reply))
                XCTAssertEqual(canonicalResponse, s(resp), "\(v.id) recorded response is canonical")
            } catch { XCTFail("\(v.id) recorded response does not decode: \(error)") }
            // (c) the dispatcher over the fixture machine produces exactly those bytes
            XCTAssertEqual(s(dispatcher.handle(line: req)), s(resp), "\(v.id) dispatcher output")
            return "\(v.id)\texchange\t-\t\(canonicalResponse)"
        default:
            XCTFail("\(v.id): unknown kind \(v.kind)")
            return ""
        }
    }

    func testEveryVectorAgreesWithTheCodecAndTheDispatcher() throws {
        var lines = [String]()
        var perFile = [String: Int]()
        var perKindVerdict = [String: Int]()
        for f in VectorTests.files {
            let vectors = try VectorLoader.load(file: f)
            let dispatcher = Dispatcher(backend: FixtureBackend())
            for v in vectors {
                lines.append(run(v, dispatcher: dispatcher))
                perFile[f, default: 0] += 1
                perKindVerdict["\(v.kind)/\(v.verdict)", default: 0] += 1
            }
        }
        // non-vacuity: every family exercised at least one case, and every reject code and op was seen
        for f in VectorTests.files { XCTAssertGreaterThan(perFile[f] ?? 0, 0, "family \(f) exercised zero vectors") }
        for k in ["request/accept", "request/reject", "response/accept", "response/reject", "event/accept", "event/reject", "exchange/accept"] {
            XCTAssertGreaterThan(perKindVerdict[k] ?? 0, 0, "kind/verdict \(k) exercised zero vectors")
        }
        for code in RejectCode.allCases {
            XCTAssertTrue(lines.contains { $0.contains("\treject\t\(code.rawValue)\t") }, "reject code \(code.rawValue) has no vector")
        }
        for op in ProtocolSpec.requestOps {
            XCTAssertTrue(lines.contains { $0.contains("\taccept\t-\t{\"op\":\"\(op)\"") }, "op \(op) has no accepted request vector")
        }
        for name in ProtocolSpec.eventNames {
            XCTAssertTrue(lines.contains { $0.contains("\taccept\t-\t{\"ev\":\"\(name)\"") }, "event \(name) has no accepted vector")
        }
        print("helper-protocol vectors (Swift lane): \(lines.count) vectors: \(perFile.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " "))")
        if let out = ProcessInfo.processInfo.environment["ASOM_VECTOR_LINES_OUT"] {
            try (lines.joined(separator: "\n") + "\n").write(toFile: out, atomically: true, encoding: .utf8)
        }
    }

    func testTheFixtureDigestIsTheSha256OfTheFixtureText() throws {
        // FixtureBackend.platformDigest was transcribed by hand; the exchange vector holds it independently (generated by hashlib).
        let vectors = try VectorLoader.load(file: "exchanges.jsonl")
        let uuid = vectors.first { s($0.request ?? []) == "{\"op\":\"platform.uuid\"}" }
        XCTAssertNotNil(uuid)
        let reply = try Codec.decodeReply(op: "platform.uuid", uuid!.response!)
        guard case .success(_, _, let fields) = reply else { return XCTFail("not a success") }
        XCTAssertEqual(fields.bytes("digest"), FixtureBackend.platformDigest)
    }
}
