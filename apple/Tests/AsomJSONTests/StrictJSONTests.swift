import XCTest
@testable import AsomJSON

private func b(_ s: String) -> [UInt8] { Array(s.utf8) }

final class StrictJSONTests: XCTestCase {
    private func code(_ input: [UInt8]) -> String? {
        if case let .failure(c) = StrictJSON.parse(input) { return c.rawValue }
        return nil
    }

    func testRejectTable() {
        let table: [(String, [UInt8], String)] = [
            ("bom", [0xEF, 0xBB, 0xBF] + b("{}"), "MALFORMED_JSON"),
            ("empty", [], "MALFORMED_JSON"),
            ("whitespace only", b(" \n\t\r"), "MALFORMED_JSON"),
            ("nbsp is not whitespace", [0xC2, 0xA0] + b("{}"), "MALFORMED_JSON"),
            ("form feed is not whitespace", b("\u{0C}{}"), "MALFORMED_JSON"),
            ("open object", b("{"), "MALFORMED_JSON"),
            ("trailing comma array", b("[1,]"), "MALFORMED_JSON"),
            ("trailing comma object", b("{\"a\":1,}"), "MALFORMED_JSON"),
            ("unquoted name", b("{a:1}"), "MALFORMED_JSON"),
            ("single quotes", b("'x'"), "MALFORMED_JSON"),
            ("plus sign", b("+1"), "MALFORMED_JSON"),
            ("bare fraction", b(".5"), "MALFORMED_JSON"),
            ("truncated literal", b("tru"), "MALFORMED_JSON"),
            ("wrong literal", b("nul1"), "MALFORMED_JSON"),
            ("unterminated string", b("\"abc"), "MALFORMED_JSON"),
            ("raw control in string", b("\"a\u{01}b\""), "MALFORMED_JSON"),
            ("raw newline in string", b("\"a\nb\""), "MALFORMED_JSON"),
            ("bad escape", b("\"\\x\""), "MALFORMED_JSON"),
            ("short unicode escape", b("\"\\u12\""), "MALFORMED_JSON"),
            ("missing colon", b("{\"a\" 1}"), "MALFORMED_JSON"),
            ("lone minus", b("-]"), "MALFORMED_JSON"),
            ("stray byte outside a string", [0x7B, 0xFF, 0x7D], "MALFORMED_JSON"),
            ("overlong 2-byte", [0x22, 0xC0, 0x80, 0x22], "INVALID_UNICODE"),
            ("overlong 3-byte", [0x22, 0xE0, 0x80, 0x80, 0x22], "INVALID_UNICODE"),
            ("utf-8 encoded surrogate", [0x22, 0xED, 0xA0, 0x80, 0x22], "INVALID_UNICODE"),
            ("above U+10FFFF", [0x22, 0xF4, 0x90, 0x80, 0x80, 0x22], "INVALID_UNICODE"),
            ("F5 lead byte", [0x22, 0xF5, 0x80, 0x80, 0x80, 0x22], "INVALID_UNICODE"),
            ("truncated 3-byte", [0x22, 0xE2, 0x82, 0x22], "INVALID_UNICODE"),
            ("lone continuation byte", [0x22, 0x80, 0x22], "INVALID_UNICODE"),
            ("lone high surrogate escape", b("\"\\ud800\""), "INVALID_UNICODE"),
            ("lone low surrogate escape", b("\"\\udc00\""), "INVALID_UNICODE"),
            ("high surrogate then BMP escape", b("\"\\ud800\\u0041\""), "INVALID_UNICODE"),
            ("high surrogate then text", b("\"\\ud83dx\""), "INVALID_UNICODE"),
            ("reversed pair", b("\"\\ude00\\ud83d\""), "INVALID_UNICODE"),
            ("lone surrogate in a member name", b("{\"\\ud800\":1}"), "INVALID_UNICODE"),
            ("fraction", b("1.5"), "NON_INTEGER_NUMBER"),
            ("exponent", b("1e3"), "NON_INTEGER_NUMBER"),
            ("upper exponent", b("1E3"), "NON_INTEGER_NUMBER"),
            ("signed exponent", b("[1e+3]"), "NON_INTEGER_NUMBER"),
            ("negative zero", b("-0"), "NON_INTEGER_NUMBER"),
            ("negative zero fraction", b("-0.0"), "NON_INTEGER_NUMBER"),
            ("NaN", b("NaN"), "NON_INTEGER_NUMBER"),
            ("Infinity", b("Infinity"), "NON_INTEGER_NUMBER"),
            ("negative Infinity", b("-Infinity"), "NON_INTEGER_NUMBER"),
            ("leading zero", b("01"), "NON_INTEGER_NUMBER"),
            ("trailing dot", b("1."), "NON_INTEGER_NUMBER"),
            ("huge exponent", b("1e400"), "NON_INTEGER_NUMBER"),
            ("fraction in array", b("[1.0]"), "NON_INTEGER_NUMBER"),
            ("fraction in object", b("{\"a\":12.5}"), "NON_INTEGER_NUMBER"),
            ("2^53", b("9007199254740992"), "NUMBER_RANGE"),
            ("-(2^53)", b("-9007199254740992"), "NUMBER_RANGE"),
            ("20 digits", b("99999999999999999999"), "NUMBER_RANGE"),
            ("17 digits", b("10000000000000000"), "NUMBER_RANGE"),
            ("duplicate", b("{\"a\":1,\"a\":2}"), "DUPLICATE_KEY"),
            ("duplicate after unescaping", b("{\"a\":1,\"\\u0061\":2}"), "DUPLICATE_KEY"),
            ("nested duplicate", b("{\"x\":{\"b\":1,\"b\":1}}"), "DUPLICATE_KEY"),
            ("duplicate in array element", b("[{\"k\":1,\"k\":1}]"), "DUPLICATE_KEY"),
            ("duplicate astral names", b("{\"\\ud83d\\ude00\":1,\"\u{1F600}\":2}"), "DUPLICATE_KEY"),
            ("depth 17 arrays", b(String(repeating: "[", count: 17) + String(repeating: "]", count: 17)), "MALFORMED_JSON"),
            ("depth 17 objects", b(String(repeating: "{\"a\":", count: 17) + "1" + String(repeating: "}", count: 17)), "MALFORMED_JSON"),
            ("depth 1000 does not overflow", b(String(repeating: "[", count: 1000)), "MALFORMED_JSON"),
            ("trailing garbage", b("{} x"), "TRAILING_DATA"),
            ("two values", b("1 2"), "TRAILING_DATA"),
            ("two objects", b("{}{}"), "TRAILING_DATA"),
            ("trailing comma after value", b("[] ,"), "TRAILING_DATA"),
            ("trailing NUL", b("{}") + [0x00], "TRAILING_DATA"),
            ("non-integer outranks duplicate", b("{\"a\":1.5,\"a\":2}"), "NON_INTEGER_NUMBER"),
            ("non-integer outranks trailing", b("1.5 x"), "NON_INTEGER_NUMBER"),
            ("invalid unicode outranks trailing", b("\"\\ud800\" x"), "INVALID_UNICODE"),
            ("range outranks duplicate", b("{\"a\":9007199254740992,\"a\":1}"), "NUMBER_RANGE"),
            ("duplicate outranks trailing", b("{\"a\":1,\"a\":1} x"), "DUPLICATE_KEY"),
            ("structural outranks everything", b("[1.5,"), "MALFORMED_JSON"),
            // Half I0b, found by the lane diff (M01-149): row 2 checks the bytes of the whole input, so it outranks row 8.
            ("invalid utf-8 after the value outranks trailing data", b("{} ") + [0xFF], "INVALID_UNICODE"),
            ("truncated sequence after the value", b("[] ") + [0xE2, 0x82], "INVALID_UNICODE"),
            ("overlong after the value", b("1 ") + [0xC0, 0x80], "INVALID_UNICODE"),
            ("valid non-ASCII after the value is only trailing data", b("{} \u{E9}"), "TRAILING_DATA"),
            ("a fraction in the trailing data is never parsed", b("{} 1.5"), "TRAILING_DATA"),
            ("invalid utf-8 after the value outranks a fraction before it", b("1.5 ") + [0xFF], "INVALID_UNICODE"),
        ]
        var exercised = 0
        var perCode: [String: Int] = [:]
        for (name, input, expected) in table {
            XCTAssertEqual(code(input), expected, name)
            exercised += 1
            perCode[expected, default: 0] += 1
        }
        XCTAssertEqual(exercised, table.count)
        for expected in ["MALFORMED_JSON", "INVALID_UNICODE", "NON_INTEGER_NUMBER", "NUMBER_RANGE", "DUPLICATE_KEY", "TRAILING_DATA"] {
            XCTAssertGreaterThan(perCode[expected] ?? 0, 0, "family \(expected) is vacuous")
        }
    }

    func testAcceptTable() {
        let sixteenArrays = String(repeating: "[", count: 16) + String(repeating: "]", count: 16)
        let sixteenObjects = String(repeating: "{\"a\":", count: 16) + "1" + String(repeating: "}", count: 16)
        let table: [(String, [UInt8])] = [
            ("zero", b("0")), ("negative", b("-1")), ("max", b("9007199254740991")), ("min", b("-9007199254740991")),
            ("whitespace all four", b(" \t\r\n{ \"a\" :\n[ 1 , 2 ] }\t")),
            ("true", b("true")), ("false", b("false")), ("null", b("null")),
            ("nul escape", b("\"\\u0000\"")), ("all escapes", b("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\"")),
            ("upper-case hex", b("\"\\uD83D\\uDE00\"")), ("astral literal", b("\"\u{1F600}\"")),
            ("depth 16 arrays", b(sixteenArrays)), ("depth 16 objects", b(sixteenObjects)),
            ("normalisation-equivalent names are distinct", b("{\"\\u00e9\":1,\"e\\u0301\":2}")),
            ("names differing only in case", b("{\"a\":1,\"A\":2}")),
            ("scalar U+10FFFF", [0x22, 0xF4, 0x8F, 0xBF, 0xBF, 0x22]),
            ("empty object and array", b("[{},[]]")),
        ]
        var exercised = 0
        for (name, input) in table {
            if case let .failure(c) = StrictJSON.parse(input) { XCTFail("\(name) rejected \(c)") }
            exercised += 1
        }
        XCTAssertEqual(exercised, table.count)
        XCTAssertGreaterThan(exercised, 0)
    }

    func testValuesAreDecodedFaithfully() throws {
        guard case let .success(v) = StrictJSON.parse(b("{\"a\":[1,-2,true,null,\"x\\u00e9\\ud83d\\ude00\"],\"b\":{}}")) else {
            return XCTFail("parse")
        }
        XCTAssertEqual(v.member("a")?.elements?.count, 5)
        XCTAssertEqual(v.member("a")?.elements?[1].intValue, -2)
        XCTAssertEqual(v.member("a")?.elements?[4].stringValue, "x\u{e9}\u{1F600}")
        XCTAssertTrue(v.member("b")?.isObject ?? false)
        XCTAssertNil(v.member("c"))
    }

    func testEqualityIsByCodeUnitsNotCanonicalEquivalence() {
        XCTAssertTrue("e\u{301}" == "\u{e9}", "premise: Swift String equality is canonical equivalence")
        XCTAssertNotEqual(JValue.string("e\u{301}"), JValue.string("\u{e9}"))
        XCTAssertNotEqual(JValue.object([JMember(name: "e\u{301}", value: .null)]), JValue.object([JMember(name: "\u{e9}", value: .null)]))
        XCTAssertNil(JValue.object([JMember(name: "\u{e9}", value: .int(1))]).member("e\u{301}"))
    }
}
