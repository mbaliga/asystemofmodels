import Foundation
import XCTest
@testable import HelperProtocol

/// A backend whose behaviour a test scripts.
final class ScriptedBackend: HelperBackend {
    var holds = 0
    var releases = 0
    var failWith: BackendFailure?
    var badResult = false

    private func check() throws { if let f = failWith { throw f } }

    func hello() -> Fields { FixtureBackend().hello() }
    func seCreate() throws -> Fields { try check(); return try FixtureBackend().seCreate() }
    func seSign(blob: [UInt8], data: [UInt8]) throws -> Fields {
        try check()
        if badResult { return Fields([(name: "sig", value: .bytes([1, 2, 3]))]) }
        return try FixtureBackend().seSign(blob: FixtureBackend.blob, data: data)
    }
    func seSelftest(blob: [UInt8]) throws -> Fields { try check(); return Fields([(name: "verified", value: .bool(true))]) }
    func powerGet() throws -> Fields { try check(); return try FixtureBackend().powerGet() }
    func thermalGet() throws -> Fields { try check(); return try FixtureBackend().thermalGet() }
    func presenceGet() throws -> Fields { try check(); return try FixtureBackend().presenceGet() }
    func gpuGet() throws -> Fields { try check(); return try FixtureBackend().gpuGet() }
    func memGet() throws -> Fields { try check(); return try FixtureBackend().memGet() }
    func assertHold(reason: String) throws { try check(); holds += 1 }
    func assertRelease() { releases += 1 }
    func sleepAck(token: Int64) throws { try check() }
    func svc(_ action: String, kind: String) throws -> Fields { try check(); return try FixtureBackend().svc(action, kind: kind) }
    func backupExclude(path: String) throws { try check() }
    func platformUUID() throws -> Fields { try check(); return try FixtureBackend().platformUUID() }
    func pathsGet() throws -> Fields { try check(); return try FixtureBackend().pathsGet() }
}

final class DispatcherTests: XCTestCase {
    private func ask(_ d: Dispatcher, _ s: String) -> String { String(decoding: d.handle(line: Array(s.utf8)), as: UTF8.self) }
    private let hold = "{\"op\":\"assert.hold\",\"reason\":\"asom: lending compute to your paired devices\"}"

    func testAtMostOneAssertion() {
        let b = ScriptedBackend()
        let d = Dispatcher(backend: b)
        XCTAssertEqual(ask(d, hold), "{\"ok\":true}")
        XCTAssertEqual(ask(d, hold), "{\"ok\":true}")
        XCTAssertEqual(b.holds, 1, "a second hold must not create a second assertion")
        XCTAssertTrue(d.assertionHeld)
        XCTAssertEqual(ask(d, "{\"op\":\"assert.release\"}"), "{\"ok\":true}")
        XCTAssertEqual(ask(d, "{\"op\":\"assert.release\"}"), "{\"ok\":true}")
        XCTAssertEqual(b.releases, 1, "releasing with nothing held releases nothing")
        XCTAssertFalse(d.assertionHeld)
        XCTAssertEqual(ask(d, hold), "{\"ok\":true}")
        XCTAssertEqual(b.holds, 2)
    }

    func testShutdownReleasesAHeldAssertionOnlyOnce() {
        let b = ScriptedBackend()
        let d = Dispatcher(backend: b)
        _ = ask(d, hold)
        d.shutdown()
        d.shutdown()
        XCTAssertEqual(b.releases, 1)
        let idle = ScriptedBackend()
        Dispatcher(backend: idle).shutdown()
        XCTAssertEqual(idle.releases, 0)
    }

    func testAFailedHoldLeavesNothingHeld() {
        let b = ScriptedBackend()
        b.failWith = .failed("IOPMAssertionCreate failed")
        let d = Dispatcher(backend: b)
        XCTAssertEqual(ask(d, hold), "{\"ok\":false,\"code\":\"FAILED\",\"message\":\"IOPMAssertionCreate failed\"}")
        XCTAssertFalse(d.assertionHeld)
        d.shutdown()
        XCTAssertEqual(b.releases, 0)
    }

    func testBackendFailuresMapToCodesAndKeepTheId() {
        let b = ScriptedBackend()
        let d = Dispatcher(backend: b)
        b.failWith = .unavailable("no Secure Enclave")
        XCTAssertEqual(ask(d, "{\"op\":\"se.create\",\"id\":3}"), "{\"ok\":false,\"id\":3,\"code\":\"UNAVAILABLE\",\"message\":\"no Secure Enclave\"}")
        b.failWith = .badRequest("unknown token")
        XCTAssertEqual(ask(d, "{\"op\":\"sleep.ack\",\"token\":1}"), "{\"ok\":false,\"code\":\"BAD_REQUEST\",\"message\":\"unknown token\"}")
    }

    func testABackendMessageThatWouldNotValidateIsReplacedNotEchoed() {
        let b = ScriptedBackend()
        b.failWith = .failed("secret\nblob AAAA")
        let d = Dispatcher(backend: b)
        XCTAssertEqual(ask(d, "{\"op\":\"se.create\"}"), "{\"ok\":false,\"code\":\"FAILED\",\"message\":\"internal error\"}")
    }

    func testABackendResultThatBreaksTheSchemaIsAnInternalErrorNotAnInvalidLine() {
        let b = ScriptedBackend()
        b.badResult = true
        let d = Dispatcher(backend: b)
        XCTAssertEqual(ask(d, "{\"op\":\"se.sign\",\"blob\":\"AAEC\",\"data\":\"\"}"), "{\"ok\":false,\"code\":\"FAILED\",\"message\":\"internal error\"}")
    }

    func testVersionCheckHappensInTheDispatcherNotTheCodec() {
        let d = Dispatcher(backend: FixtureBackend())
        XCTAssertTrue(ask(d, "{\"op\":\"hello\",\"v\":1}").hasPrefix("{\"ok\":true,"))
        XCTAssertEqual(ask(d, "{\"op\":\"hello\",\"v\":2}"), "{\"ok\":false,\"code\":\"UNSUPPORTED_VERSION\",\"message\":\"protocol version 1 only\"}")
    }

    func testEveryRequestOpIsAnswered() {
        // non-vacuity: each op is handled by the dispatcher (none falls into the default branch)
        let d = Dispatcher(backend: FixtureBackend())
        let requests = [
            "{\"op\":\"hello\",\"v\":1}", "{\"op\":\"se.create\"}", "{\"op\":\"se.sign\",\"blob\":\"\(StrictBase64.encode(FixtureBackend.blob))\",\"data\":\"\"}",
            "{\"op\":\"se.selftest\",\"blob\":\"\(StrictBase64.encode(FixtureBackend.blob))\"}", "{\"op\":\"power.get\"}", "{\"op\":\"thermal.get\"}",
            "{\"op\":\"presence.get\"}", "{\"op\":\"gpu.get\"}", "{\"op\":\"mem.get\"}", hold, "{\"op\":\"assert.release\"}",
            "{\"op\":\"sleep.ack\",\"token\":7}", "{\"op\":\"svc.status\",\"kind\":\"agent\"}", "{\"op\":\"svc.register\",\"kind\":\"agent\"}",
            "{\"op\":\"svc.unregister\",\"kind\":\"agent\"}", "{\"op\":\"backup.exclude\",\"path\":\"/tmp/x\"}", "{\"op\":\"platform.uuid\"}", "{\"op\":\"paths.get\"}",
        ]
        XCTAssertEqual(requests.count, ProtocolSpec.requestOps.count)
        var answered = Set<String>()
        for r in requests {
            let response = ask(d, r)
            XCTAssertTrue(response.hasPrefix("{\"ok\":true"), "\(r) -> \(response)")
            answered.insert(try! Codec.decodeRequest(Array(r.utf8)).op)
        }
        XCTAssertEqual(answered, Set(ProtocolSpec.requestOps))
    }

    func testAnUnparseableLineNeverEchoesAnId() {
        let d = Dispatcher(backend: FixtureBackend())
        XCTAssertEqual(ask(d, "{\"op\":\"hello\",\"id\":5"), "{\"ok\":false,\"code\":\"BAD_REQUEST\",\"message\":\"MALFORMED_JSON\"}")
    }
}
