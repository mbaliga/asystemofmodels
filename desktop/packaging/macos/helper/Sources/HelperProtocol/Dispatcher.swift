// Request dispatch over an abstract backend (helper-protocol/SCHEMA.md sections 3 to 5). All the protocol behaviour that does
// not need an Apple framework lives here so that it is tested on Linux: decode order, error mapping, id echo, the version
// check, the "at most one assertion" rule. The macOS backend (asom-mac-helper/Backend.swift) supplies the framework calls.

/// A backend failure, mapped to a response code. The message must be a fixed short reason: it never echoes request content.
public enum BackendFailure: Error {
    case unavailable(String)
    case failed(String)
    case badRequest(String)
}

public protocol HelperBackend: AnyObject {
    func hello() -> Fields
    func seCreate() throws -> Fields
    func seSign(blob: [UInt8], data: [UInt8]) throws -> Fields
    func seSelftest(blob: [UInt8]) throws -> Fields
    func powerGet() throws -> Fields
    func thermalGet() throws -> Fields
    func presenceGet() throws -> Fields
    func gpuGet() throws -> Fields
    func memGet() throws -> Fields
    /// Creates the one power assertion. The dispatcher never calls it twice without a release in between.
    func assertHold(reason: String) throws
    func assertRelease()
    func sleepAck(token: Int64) throws
    func svc(_ action: String, kind: String) throws -> Fields
    func backupExclude(path: String) throws
    func platformUUID() throws -> Fields
    func pathsGet() throws -> Fields
}

public final class Dispatcher {
    private let backend: HelperBackend
    public private(set) var assertionHeld = false

    public init(backend: HelperBackend) {
        self.backend = backend
    }

    /// Answers one request line with one response line (no trailing LF). Never throws: every failure is a response.
    public func handle(line: [UInt8]) -> [UInt8] {
        let request: Request
        do {
            request = try Codec.decodeRequest(line)
        } catch let r as Reject {
            let code = r.code == .unknownOp ? "UNKNOWN_OP" : "BAD_REQUEST"
            return failure(id: r.id, code: code, message: r.code.rawValue)
        } catch {
            return failure(id: nil, code: "FAILED", message: "internal error")
        }
        do {
            let reply = try reply(for: request)
            return try Codec.encode(reply)
        } catch let f as BackendFailure {
            return failure(for: f, id: request.id)
        } catch {
            return failure(id: request.id, code: "FAILED", message: "internal error")
        }
    }

    /// The answer to a line the reader had to discard because it exceeded `maxLineBytes` (the node sent something that is not ours).
    public func handleOverlongLine() -> [UInt8] {
        failure(id: nil, code: "BAD_REQUEST", message: RejectCode.lineTooLong.rawValue)
    }

    /// The end of stdin: releases the assertion (SCHEMA section 1).
    public func shutdown() {
        if assertionHeld {
            backend.assertRelease()
            assertionHeld = false
        }
    }

    private func reply(for r: Request) throws -> Reply {
        func ok(_ fields: Fields = Fields()) -> Reply { .success(op: r.op, id: r.id, fields: fields) }
        switch r.op {
        case "hello":
            guard r.fields.int("v") == ProtocolSpec.version else {
                return .failure(id: r.id, code: "UNSUPPORTED_VERSION", message: "protocol version 1 only")
            }
            return ok(backend.hello())
        case "se.create": return ok(try backend.seCreate())
        case "se.sign": return ok(try backend.seSign(blob: r.fields.bytes("blob") ?? [], data: r.fields.bytes("data") ?? []))
        case "se.selftest": return ok(try backend.seSelftest(blob: r.fields.bytes("blob") ?? []))
        case "power.get": return ok(try backend.powerGet())
        case "thermal.get": return ok(try backend.thermalGet())
        case "presence.get": return ok(try backend.presenceGet())
        case "gpu.get": return ok(try backend.gpuGet())
        case "mem.get": return ok(try backend.memGet())
        case "assert.hold":
            if !assertionHeld {
                try backend.assertHold(reason: r.fields.text("reason") ?? ProtocolSpec.holdReason)
                assertionHeld = true
            }
            return ok()
        case "assert.release":
            if assertionHeld {
                backend.assertRelease()
                assertionHeld = false
            }
            return ok()
        case "sleep.ack":
            try backend.sleepAck(token: r.fields.int("token") ?? -1)
            return ok()
        case "svc.status", "svc.register", "svc.unregister":
            return ok(try backend.svc(String(r.op.dropFirst(4)), kind: r.fields.text("kind") ?? ""))
        case "backup.exclude":
            try backend.backupExclude(path: r.fields.text("path") ?? "")
            return ok()
        case "platform.uuid": return ok(try backend.platformUUID())
        case "paths.get": return ok(try backend.pathsGet())
        default:
            return .failure(id: r.id, code: "UNKNOWN_OP", message: "UNKNOWN_OP")
        }
    }

    private func failure(for f: BackendFailure, id: Int64?) -> [UInt8] {
        switch f {
        case .unavailable(let m): return failure(id: id, code: "UNAVAILABLE", message: m)
        case .failed(let m): return failure(id: id, code: "FAILED", message: m)
        case .badRequest(let m): return failure(id: id, code: "BAD_REQUEST", message: m)
        }
    }

    /// An error response that is guaranteed to be encodable: a message that would not validate is replaced by a fixed one.
    private func failure(id: Int64?, code: String, message: String) -> [UInt8] {
        if let line = try? Codec.encode(Reply.failure(id: id, code: code, message: message)) { return line }
        if let line = try? Codec.encode(Reply.failure(id: id, code: code, message: "internal error")) { return line }
        return Array("{\"ok\":false,\"code\":\"FAILED\",\"message\":\"internal error\"}".utf8)
    }
}
