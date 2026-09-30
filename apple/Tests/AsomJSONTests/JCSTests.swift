import XCTest
@testable import AsomJSON

private func b(_ s: String) -> [UInt8] { Array(s.utf8) }

private func hex(_ bytes: [UInt8]) -> String {
    bytes.map { String(format: "%02x", $0) }.joined()
}

private struct MissingRepoFile: Error { let path: String }

private func repoFile(_ relative: String, from file: StaticString = #filePath) throws -> String {
    var dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
    for _ in 0..<10 {
        let candidate = dir.appendingPathComponent(relative).path
        if FileManager.default.fileExists(atPath: candidate) { return candidate }
        dir = dir.deletingLastPathComponent()
    }
    throw MissingRepoFile(path: relative)
}

final class JCSTests: XCTestCase {
    private struct Unexpected: Error { let code: RejectCode }

    private func canonical(_ text: String) throws -> String {
        switch JCS.canonicalize(b(text)) {
        case let .success(out): return String(decoding: out, as: UTF8.self)
        case let .failure(c): throw Unexpected(code: c)
        }
    }

    /// M01-001 from conformance-examples/seed-vectors.json, an r0 seed: hand-made, self-oracled.
    func testSeedVectorM01_001_utf16KeyOrder() throws {
        let path = try repoFile("docs/design/mesh/conformance-examples/seed-vectors.json")
        guard case let .success(seeds) = StrictJSON.parse([UInt8](try Data(contentsOf: URL(fileURLWithPath: path)))) else {
            return XCTFail("seed-vectors.json is not strict JSON")
        }
        let m01 = try XCTUnwrap(seeds.member("M01"))
        let input = try XCTUnwrap(m01.member("input_json_escaped")?.stringValue)
        let expectHex = try XCTUnwrap(m01.member("expect_utf8_hex")?.stringValue)
        let wrongOrder = try XCTUnwrap(m01.member("trap_codepoint_order_WRONG")?.stringValue)
        guard case let .success(out) = JCS.canonicalize(b(input)) else { return XCTFail("canonicalize") }
        XCTAssertEqual(hex(out), expectHex)
        XCTAssertNotEqual(String(decoding: out, as: UTF8.self), wrongOrder, "code-point order trap")
        XCTAssertTrue(hex(out).contains("22f09f988022"), "U+1F600 key present")
        let positionOfAstral = try XCTUnwrap(hex(out).range(of: "22f09f988022"))
        let positionOfFullwidth = try XCTUnwrap(hex(out).range(of: "22efbca122"))
        XCTAssertLessThan(positionOfAstral.lowerBound, positionOfFullwidth.lowerBound, "U+1F600 sorts before U+FF21")
    }

    func testCanonicalisationTable() throws {
        let table: [(String, String, String)] = [
            ("sorts by name", "{\"b\":1,\"a\":2}", "{\"a\":2,\"b\":1}"),
            ("no whitespace", " { \"a\" : [ 1 , 2 ] } ", "{\"a\":[1,2]}"),
            ("array order kept", "[3,1,2]", "[3,1,2]"),
            ("nested sort", "{\"z\":{\"y\":1,\"x\":2},\"a\":[{\"d\":1,\"c\":2}]}", "{\"a\":[{\"c\":2,\"d\":1}],\"z\":{\"x\":2,\"y\":1}}"),
            ("slash unescaped", "\"a\\/b\"", "\"a/b\""),
            ("short escapes", "\"\\b\\t\\n\\f\\r\"", "\"\\b\\t\\n\\f\\r\""),
            ("other controls lowercase hex", "\"\\u0001\\u001F\\u0000\"", "\"\\u0001\\u001f\\u0000\""),
            ("U+007F literal", "\"\\u007f\"", "\"\u{7F}\""),
            ("non-ASCII literal", "\"\\u00e9\\u4e2d\"", "\"\u{e9}\u{4e2d}\""),
            ("U+2028 literal", "\"\\u2028\"", "\"\u{2028}\""),
            ("quote and backslash", "\"\\\"\\\\\"", "\"\\\"\\\\\""),
            ("NFD is kept", "\"e\\u0301\"", "\"e\u{301}\""),
            ("shortest integers", "[0,-1,9007199254740991,-9007199254740991]", "[0,-1,9007199254740991,-9007199254740991]"),
            ("empty containers", "{\"a\":{},\"b\":[]}", "{\"a\":{},\"b\":[]}"),
            ("literals", "[true,false,null]", "[true,false,null]"),
            ("shorter name first", "{\"ab\":1,\"a\":2}", "{\"a\":2,\"ab\":1}"),
            ("case sensitive order", "{\"a\":1,\"B\":2}", "{\"B\":2,\"a\":1}"),
            ("BMP above surrogates sorts after astral", "{\"\\uff21\":1,\"\\ud83d\\ude00\":2,\"\\ue000\":3}", "{\"\u{1F600}\":2,\"\u{e000}\":3,\"\u{ff21}\":1}"),
        ]
        var exercised = 0
        for (name, input, expected) in table {
            XCTAssertEqual(try canonical(input), expected, name)
            exercised += 1
        }
        XCTAssertEqual(exercised, table.count)
        XCTAssertGreaterThan(exercised, 0)
    }

    func testCanonicalFormIsAFixedPoint() throws {
        for text in ["{\"b\":[1,{\"z\":null,\"a\":\"\\u0001\"}],\"a\":-5}", "[\"\\ud83d\\ude00\",\"e\\u0301\"]"] {
            let once = try canonical(text)
            XCTAssertEqual(try canonical(once), once)
            XCTAssertTrue(JCS.isCanonical(b(once)))
            XCTAssertEqual(JCS.isCanonical(b(text)), text == once)
        }
    }

    func testIsCanonicalRejectsAnythingNotByteIdentical() {
        let notCanonical = ["{ \"a\":1}", "{\"b\":1,\"a\":2}", "[1, 2]", "{\"a\":\"\\/\"}", "{\"a\":\"\\u00E9\"}", "{\"a\":\"\\u0001\"}x"]
        var exercised = 0
        for text in notCanonical {
            XCTAssertFalse(JCS.isCanonical(b(text)), text)
            exercised += 1
        }
        XCTAssertEqual(exercised, notCanonical.count)
        XCTAssertTrue(JCS.isCanonical(b("{\"a\":\"\\u0001\"}")))
        XCTAssertTrue(JCS.isCanonical(b("{\"a\":\"\u{e9}\"}")))
    }

    func testSerialiserRefusesWhatTheProfileForbids() {
        XCTAssertThrowsError(try JCS.serialize(.int(9_007_199_254_740_992))) { XCTAssertEqual($0 as? JCS.Failure, .integerOutOfRange) }
        XCTAssertThrowsError(try JCS.serialize(.int(-9_007_199_254_740_992)))
        XCTAssertThrowsError(try JCS.serialize(.object([JMember(name: "a", value: .null), JMember(name: "a", value: .null)]))) {
            XCTAssertEqual($0 as? JCS.Failure, .duplicateMember)
        }
        XCTAssertNoThrow(try JCS.serialize(.int(9_007_199_254_740_991)))
        XCTAssertEqual(try JCS.serialize(.object([JMember(name: "a", value: .null)])), b("{\"a\":null}"))
    }
}

final class Base64StrictTests: XCTestCase {
    func testAcceptTable() {
        let table: [(String, [UInt8])] = [
            ("", []), ("Zg==", b("f")), ("Zg", b("f")), ("Zm8=", b("fo")), ("Zm8", b("fo")), ("Zm9v", b("foo")),
            ("Zm9vYg==", b("foob")), ("Zm9vYmE=", b("fooba")), ("Zm9vYmFy", b("foobar")),
            ("+/8=", [0xFB, 0xFF]), ("-_8=", [0xFB, 0xFF]), ("-_8", [0xFB, 0xFF]), ("+/8", [0xFB, 0xFF]),
            ("AP5y", [0x00, 0xFE, 0x72]),
        ]
        var exercised = 0
        for (text, expected) in table {
            XCTAssertEqual(Base64Strict.decodeEither(text), expected, "accept \(text)")
            exercised += 1
        }
        XCTAssertEqual(exercised, table.count)
    }

    func testRejectTable() {
        let table: [(String, String)] = [
            ("mixed alphabets", "+_8="), ("mixed alphabets 2", "-/8"), ("space", "Zm 9v"), ("newline", "Zm9v\n"),
            ("leading space", " Zm9v"), ("tab", "Zg\t=="), ("padding in the middle", "Zg==Zg=="), ("padding before data", "=Zm9"),
            ("three pads", "Z==="), ("too much padding", "Zm9v="), ("too little padding", "Zg="), ("length 1 mod 4", "Zm9vY"),
            ("single char", "Z"), ("only padding", "===="), ("non-zero unused bits, 1 byte", "Zh=="), ("non-zero unused bits, 1 byte unpadded", "Zh"),
            ("non-zero unused bits, 2 bytes", "Zm9="), ("non-zero unused bits, 2 bytes unpadded", "Zm9"),
            ("non-ASCII", "Zm9\u{e9}"), ("illegal character", "Zm9!"), ("dot", "Zm.v"),
            ("non-canonical last char (M03-125 shape)", "1cu+UasO/G4TnL3axnFdU8EeNLZ8fXotsF6eSa4iE1g6R4O4s4Pad3n+L8oYklCzkVabTVR5fHE4qUn5vhm5qB=="),
        ]
        var exercised = 0
        for (name, text) in table {
            XCTAssertNil(Base64Strict.decodeEither(text), name)
            exercised += 1
        }
        XCTAssertEqual(exercised, table.count)
        XCTAssertNotNil(Base64Strict.decodeEither("1cu+UasO/G4TnL3axnFdU8EeNLZ8fXotsF6eSa4iE1g6R4O4s4Pad3n+L8oYklCzkVabTVR5fHE4qUn5vhm5qg=="), "the canonical twin of the last row")
    }

    func testEncodeIsInverseAndCanonical() {
        var exercised = 0
        var state: UInt64 = 0x9E3779B97F4A7C15
        for length in 0..<70 {
            var bytes: [UInt8] = []
            for _ in 0..<length {
                state = state &* 6364136223846793005 &+ 1442695040888963407
                bytes.append(UInt8(truncatingIfNeeded: state >> 33))
            }
            let std = Base64Strict.encode(bytes)
            let url = Base64Strict.encodeURL(bytes)
            XCTAssertEqual(Base64Strict.decodeEither(std), bytes)
            XCTAssertEqual(Base64Strict.decodeEither(url), bytes)
            XCTAssertFalse(url.contains("=") || url.contains("+") || url.contains("/"))
            XCTAssertEqual(std.count % 4, 0)
            exercised += 1
        }
        XCTAssertEqual(exercised, 70)
        XCTAssertEqual(Base64Strict.encode(b("foobar")), "Zm9vYmFy")
        XCTAssertEqual(Base64Strict.encode(b("fo")), "Zm8=")
        XCTAssertEqual(Base64Strict.encodeURL([0xFB, 0xFF]), "-_8")
    }
}
