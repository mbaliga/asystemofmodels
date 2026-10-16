import XCTest
@testable import HelperProtocol

final class LineSplitterTests: XCTestCase {
    private func lines(_ s: String, max: Int = 16) -> [LineSplitter.Item] {
        var sp = LineSplitter(maxLine: max)
        return sp.feed(Array(s.utf8))
    }

    func testSplitsOnLFAndKeepsEmptyLines() {
        XCTAssertEqual(lines("a\nbb\n\nc\n"), [.line(Array("a".utf8)), .line(Array("bb".utf8)), .line([]), .line(Array("c".utf8))])
    }

    func testAPartialLineAtEOFIsNeverReported() {
        var sp = LineSplitter(maxLine: 16)
        XCTAssertEqual(sp.feed(Array("abc".utf8)), [])
        XCTAssertEqual(sp.bufferedCount, 3)
        XCTAssertEqual(sp.feed(Array("def\n".utf8)), [.line(Array("abcdef".utf8))])
    }

    func testTheLimitIsExactlyMaxLineBytesNotCountingTheLF() {
        XCTAssertEqual(lines(String(repeating: "x", count: 16) + "\n"), [.line(Array(String(repeating: "x", count: 16).utf8))])
        XCTAssertEqual(lines(String(repeating: "x", count: 17) + "\n"), [.overlong])
    }

    func testAnOverlongLineIsDiscardedToItsLFAndTheNextLineSurvives() {
        var sp = LineSplitter(maxLine: 4)
        var out = sp.feed(Array("abcdefgh".utf8))
        XCTAssertEqual(out, [])
        XCTAssertEqual(sp.bufferedCount, 0, "memory stays bounded while discarding")
        out = sp.feed(Array("ijkl\nok\n".utf8))
        XCTAssertEqual(out, [.overlong, .line(Array("ok".utf8))])
    }

    func testChunkBoundariesDoNotMatter() {
        var sp = LineSplitter(maxLine: 8)
        var all = [LineSplitter.Item]()
        for b in "ab\ncdefghijk\nxy\n".utf8 { all += sp.feed([b]) }
        XCTAssertEqual(all, [.line(Array("ab".utf8)), .overlong, .line(Array("xy".utf8))])
    }

    func testTheProtocolLimitAnswersLikeTheExchangeVector() {
        let d = Dispatcher(backend: FixtureBackend())
        var sp = LineSplitter()
        let big = [UInt8](repeating: 0x41, count: ProtocolSpec.maxLineBytes + 1) + [0x0A]
        let items = sp.feed(big)
        XCTAssertEqual(items, [.overlong])
        XCTAssertEqual(String(decoding: d.handleOverlongLine(), as: UTF8.self), "{\"ok\":false,\"code\":\"BAD_REQUEST\",\"message\":\"LINE_TOO_LONG\"}")
        let exact = [UInt8](repeating: 0x41, count: ProtocolSpec.maxLineBytes) + [0x0A]
        var sp2 = LineSplitter()
        if case .line(let l)? = sp2.feed(exact).first { XCTAssertEqual(l.count, ProtocolSpec.maxLineBytes) } else { XCTFail("a line of exactly the limit must be delivered") }
    }
}
