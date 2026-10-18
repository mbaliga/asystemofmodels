import Foundation
import XCTest
@testable import HelperProtocol

final class JSONTests: XCTestCase {
    private func parses(_ s: String) -> Bool { (try? StrictJSON.parse(Array(s.utf8))) != nil }

    func testProfileAccepts() {
        for s in ["{}", "[]", "0", "-1", "9007199254740991", "-9007199254740991", "\"a\"", "null", "true", "false",
                  "{\"a\":[1,2,{\"b\":null}]}", " {\t} ", "\"\\u0000\"", "\"\\ud83d\\ude00\"", "\"\u{1F600}\""] {
            XCTAssertTrue(parses(s), s)
        }
    }

    func testProfileRejects() {
        for s in ["", "01", "-0", "1.0", "1e3", "1E3", "+1", "9007199254740992", "12345678901234567", "{\"a\":1,\"a\":2}", "\"\\ud800\"",
                  "\"\\udc00\"", "\"\\ud83d\\u0041\"", "\"\\x\"", "\"a\u{01}b\"", "{'a':1}", "[1,]", "{\"a\":1,}", "tru", "nul", "1 2", "\"a",
                  "{\"a\" 1}", "[", "{", "\"\\u12\"", "\"\\u\u{0660}\u{0660}\u{0664}\u{0661}\""] {
            XCTAssertFalse(parses(s), s)
        }
    }

    func testDepthBoundary() {
        // the top-level value is depth 0, so 33 nested arrays put the innermost at depth 32 (legal) and 34 at depth 33 (refused)
        XCTAssertTrue(parses(String(repeating: "[", count: 33) + String(repeating: "]", count: 33)))
        XCTAssertFalse(parses(String(repeating: "[", count: 34) + String(repeating: "]", count: 34)))
    }

    func testInvalidUTF8IsRejected() {
        let bad: [[UInt8]] = [
            [0x22, 0xFF, 0x22], [0x22, 0xC0, 0xAF, 0x22], [0x22, 0xED, 0xA0, 0x80, 0x22], [0x22, 0xE2, 0x82, 0x22],
            [0x22, 0xF4, 0x90, 0x80, 0x80, 0x22], [0x22, 0x80, 0x22], [0x22, 0xE0, 0x80, 0x80, 0x22], [0x22, 0xF0, 0x80, 0x80, 0x80, 0x22],
            [0x22, 0xC2], [0x22, 0xF5, 0x80, 0x80, 0x80, 0x22],
        ]
        for b in bad { XCTAssertNil(try? StrictJSON.parse(b), "\(b)") }
        // well-formed edge cases
        XCTAssertNotNil(try? StrictJSON.parse([0x22, 0xC2, 0x80, 0x22]))
        XCTAssertNotNil(try? StrictJSON.parse([0x22, 0xF4, 0x8F, 0xBF, 0xBF, 0x22]))
        XCTAssertNotNil(try? StrictJSON.parse([0x22, 0xED, 0x9F, 0xBF, 0x22]))
    }

    func testCanonicallyEquivalentNamesAreNotDuplicates() throws {
        let v = try StrictJSON.parse(Array("{\"\u{00E9}\":1,\"e\u{0301}\":2}".utf8))
        guard case .object(let o) = v else { return XCTFail() }
        XCTAssertEqual(o.members.count, 2)
        XCTAssertNotNil(o["\u{00E9}"])
        if case .int(let n)? = o["e\u{0301}"] { XCTAssertEqual(n, 2) } else { XCTFail("member missing") }
    }

    func testStringEscaping() {
        var out = [UInt8]()
        appendJSONString("a\"b\\c\u{01}\u{1F}\u{7F}\u{E9}\u{2028}\u{1F600}", to: &out)
        XCTAssertEqual(String(decoding: out, as: UTF8.self), "\"a\\\"b\\\\c\\u0001\\u001f\u{7F}\u{E9}\u{2028}\u{1F600}\"")
    }
}

final class Base64Tests: XCTestCase {
    func testRoundTripAtEveryLengthModulo() {
        for n in 0...40 {
            let data = (0..<n).map { UInt8(($0 * 37 + 11) & 0xFF) }
            let text = StrictBase64.encode(data)
            XCTAssertEqual(StrictBase64.decode(text), data, "length \(n)")
        }
    }

    func testKnownValues() {
        XCTAssertEqual(StrictBase64.encode(Array("hello".utf8)), "aGVsbG8=")
        XCTAssertEqual(StrictBase64.encode([]), "")
        XCTAssertEqual(StrictBase64.decode(""), [])
    }

    func testRejectsEverythingNonCanonical() {
        for s in ["AAE", "AAF=", "AA", "AA===", "A=A=", "aGVs bG8=", "aG\nVsbG8=", "-_-_", "!!!!", "AAEC\n", "=AAA", "AAA=AAAA", "====", "AB==", "AAB="] {
            XCTAssertNil(StrictBase64.decode(s), s)
        }
    }
}

final class CodecTests: XCTestCase {
    func testEncodeRefusesInvalidMessages() {
        // the encoder enforces the same rules as the decoder: a helper cannot emit a line the node would refuse
        let badSpki = Reply.success(op: "se.create", id: nil, fields: Fields([
            (name: "blob", value: .bytes([1])), (name: "spki", value: .bytes([UInt8](repeating: 0, count: 90))),
        ]))
        XCTAssertThrowsError(try Codec.encode(badSpki))
        let missing = Reply.success(op: "se.sign", id: nil, fields: Fields())
        XCTAssertThrowsError(try Codec.encode(missing))
        let extra = Reply.success(op: "assert.hold", id: nil, fields: Fields([(name: "x", value: .int(1))]))
        XCTAssertThrowsError(try Codec.encode(extra))
        let controlMessage = Reply.failure(id: nil, code: "FAILED", message: "a\nb")
        XCTAssertThrowsError(try Codec.encode(controlMessage))
        let badCode = Reply.failure(id: nil, code: "WHATEVER", message: "x")
        XCTAssertThrowsError(try Codec.encode(badCode))
        let nullNotAllowed = Reply.success(op: "thermal.get", id: nil, fields: Fields([(name: "state", value: .null)]))
        XCTAssertThrowsError(try Codec.encode(nullNotAllowed))
        let badID = Reply.success(op: "assert.release", id: -1, fields: Fields())
        XCTAssertThrowsError(try Codec.encode(badID))
    }

    func testRequestRoundTripThroughTheTypedForm() throws {
        let line = Array("{\"op\":\"se.sign\",\"id\":4,\"blob\":\"AAEC\",\"data\":\"aGVsbG8=\"}".utf8)
        let r = try Codec.decodeRequest(line)
        XCTAssertEqual(r.op, "se.sign")
        XCTAssertEqual(r.id, 4)
        XCTAssertEqual(r.fields.bytes("blob"), [0, 1, 2])
        XCTAssertEqual(r.fields.bytes("data"), Array("hello".utf8))
        XCTAssertEqual(try Codec.encode(r), line)
    }

    func testSpecTablesAreClosedAndConsistent() {
        XCTAssertEqual(ProtocolSpec.requestOps.count, 18)
        XCTAssertEqual(ProtocolSpec.eventNames.count, 4)
        for op in ProtocolSpec.requestOps { XCTAssertNotNil(ProtocolSpec.replyFields(op), "request op \(op) has no response table") }
        XCTAssertNil(ProtocolSpec.requestParams("HELLO"))
        // a Kelvin sign is canonically equivalent to "K" in Swift String comparison; the tables compare bytes
        XCTAssertNil(ProtocolSpec.requestParams("thermal.get\u{212A}"))
    }
}
