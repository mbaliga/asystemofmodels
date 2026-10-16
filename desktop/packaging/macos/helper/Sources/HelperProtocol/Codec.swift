// The JSON-Lines codec of helper-protocol/SCHEMA.md: decode order of section 3, canonical encoding of section 2.

public enum HValue: Equatable {
    case int(Int64)
    case bool(Bool)
    case text(String)
    case bytes([UInt8])
    case null
}

public struct Fields {
    public var items: [(name: String, value: HValue)]

    public init(_ items: [(name: String, value: HValue)] = []) {
        self.items = items
    }

    public subscript(name: String) -> HValue? {
        for i in items where bytesEqual(i.name, name) { return i.value }
        return nil
    }

    public func int(_ name: String) -> Int64? {
        if case .int(let v)? = self[name] { return v }
        return nil
    }

    public func bool(_ name: String) -> Bool? {
        if case .bool(let v)? = self[name] { return v }
        return nil
    }

    public func text(_ name: String) -> String? {
        if case .text(let v)? = self[name] { return v }
        return nil
    }

    public func bytes(_ name: String) -> [UInt8]? {
        if case .bytes(let v)? = self[name] { return v }
        return nil
    }
}

public struct Request {
    public let op: String
    public let id: Int64?
    public let fields: Fields

    public init(op: String, id: Int64? = nil, fields: Fields = Fields()) {
        self.op = op
        self.id = id
        self.fields = fields
    }
}

public enum Reply {
    case success(op: String, id: Int64?, fields: Fields)
    case failure(id: Int64?, code: String, message: String)
}

public struct Event {
    public let name: String
    public let fields: Fields

    public init(name: String, fields: Fields = Fields()) {
        self.name = name
        self.fields = fields
    }
}

public enum Codec {
    // ---- decoding ------------------------------------------------------------------------------------------------------

    public static func decodeRequest(_ line: [UInt8]) throws -> Request {
        let obj = try parseObject(line)
        let id = usableID(obj)
        guard let opv = obj["op"] else { throw Reject(.missingField, id: id) }
        guard case .string(let op) = opv else { throw Reject(.badField, id: id) }
        guard let params = ProtocolSpec.requestParams(op) else { throw Reject(.unknownOp, id: id) }
        let (_, fields) = try decodeMembers(obj, discriminator: "op", allowID: true, spec: params, bestID: id)
        return Request(op: op, id: id, fields: fields)
    }

    /// Decodes a response to the request named [op].
    public static func decodeReply(op: String, _ line: [UInt8]) throws -> Reply {
        let obj = try parseObject(line)
        let id = usableID(obj)
        guard let okv = obj["ok"] else { throw Reject(.missingField, id: id) }
        guard case .bool(let ok) = okv else { throw Reject(.badField, id: id) }
        if ok {
            guard let spec = ProtocolSpec.replyFields(op) else { throw Reject(.unknownOp, id: id) }
            let (_, fields) = try decodeMembers(obj, discriminator: "ok", allowID: true, spec: spec, bestID: id)
            return .success(op: op, id: id, fields: fields)
        }
        let (_, fields) = try decodeMembers(obj, discriminator: "ok", allowID: true, spec: ProtocolSpec.errorFields, bestID: id)
        return .failure(id: id, code: fields.text("code") ?? "", message: fields.text("message") ?? "")
    }

    public static func decodeEvent(_ line: [UInt8]) throws -> Event {
        let obj = try parseObject(line)
        guard let evv = obj["ev"] else { throw Reject(.missingField) }
        guard case .string(let name) = evv else { throw Reject(.badField) }
        guard let spec = ProtocolSpec.eventFields(name) else { throw Reject(.unknownOp) }
        let (_, fields) = try decodeMembers(obj, discriminator: "ev", allowID: false, spec: spec, bestID: nil)
        return Event(name: name, fields: fields)
    }

    static func parseObject(_ line: [UInt8]) throws -> JSONObject {
        if line.count > ProtocolSpec.maxLineBytes { throw Reject(.lineTooLong) }
        for c in line where c == 0x0A || c == 0x0D { throw Reject(.malformedJSON) }
        let value: JSONValue
        do {
            value = try StrictJSON.parse(line)
        } catch {
            throw Reject(.malformedJSON)
        }
        guard case .object(let obj) = value else { throw Reject(.notObject) }
        return obj
    }

    static func usableID(_ obj: JSONObject) -> Int64? {
        if case .int(let v)? = obj["id"], v >= 0, v <= ProtocolSpec.maxSafeInteger { return v }
        return nil
    }

    private static func decodeMembers(
        _ obj: JSONObject, discriminator: String, allowID: Bool, spec: [FieldSpec], bestID: Int64?
    ) throws -> (Int64?, Fields) {
        for m in obj.members {
            let known = bytesEqual(m.key, discriminator) || (allowID && bytesEqual(m.key, "id")) || spec.contains { bytesEqual($0.name, m.key) }
            if !known { throw Reject(.unknownField, id: bestID) }
        }
        for s in spec where obj[s.name] == nil { throw Reject(.missingField, id: bestID) }
        if allowID, let idv = obj["id"] {
            guard case .int(let v) = idv, v >= 0, v <= ProtocolSpec.maxSafeInteger else { throw Reject(.badField, id: bestID) }
        }
        var items: [(name: String, value: HValue)] = []
        for s in spec {
            guard let hv = convert(obj[s.name]!, s), validate(hv, s) else { throw Reject(.badField, id: bestID) }
            items.append((name: s.name, value: hv))
        }
        return (bestID, Fields(items))
    }

    /// JSON value to message value. A base64 string becomes bytes (nil if it is not canonical base64).
    private static func convert(_ v: JSONValue, _ s: FieldSpec) -> HValue? {
        switch v {
        case .null: return .null
        case .int(let n): return .int(n)
        case .bool(let b): return .bool(b)
        case .string(let t):
            if case .bytes = s.type { return StrictBase64.decode(t).map { .bytes($0) } }
            return .text(t)
        case .object, .array: return nil
        }
    }

    // ---- validation (shared by decode and encode) ----------------------------------------------------------------------

    static func validate(_ v: HValue, _ s: FieldSpec) -> Bool {
        switch v {
        case .null:
            return s.nullable
        case .int(let n):
            if case .int(let lo, let hi) = s.type { return n >= lo && n <= hi }
            return false
        case .bool:
            if case .bool = s.type { return true }
            return false
        case .bytes(let b):
            if case .bytes(let lo, let hi) = s.type { return b.count >= lo && b.count <= hi }
            return false
        case .text(let t):
            switch s.type {
            case .oneOf(let options): return options.contains { bytesEqual($0, t) }
            case .text(let lo, let hi, let rule):
                let n = t.utf8.count
                return n >= lo && n <= hi && textRule(rule, t)
            default: return false
            }
        }
    }

    private static func textRule(_ rule: TextRule, _ t: String) -> Bool {
        let u = Array(t.utf8)
        func noControl() -> Bool { !u.contains { $0 < 0x20 || $0 == 0x7F } }
        func digits(_ i: inout Int) -> Bool {
            let start = i
            while i < u.count, u[i] >= 0x30, u[i] <= 0x39 { i += 1 }
            return i > start
        }
        switch rule {
        case .noControl: return noControl()
        case .absolutePath: return u.first == 0x2F && noControl()
        case .printableASCII: return !u.contains { $0 < 0x20 || $0 > 0x7E }
        case .exact(let s): return u.elementsEqual(s.utf8)
        case .semver:
            var i = 0
            for k in 0..<3 {
                if !digits(&i) { return false }
                if k < 2 {
                    if i >= u.count || u[i] != 0x2E { return false }
                    i += 1
                }
            }
            if i == u.count { return true }
            if u[i] != 0x2D { return false }
            i += 1
            if i == u.count { return false }
            while i < u.count {
                let c = u[i]
                let ok = (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || c == 0x2E || c == 0x2D
                if !ok { return false }
                i += 1
            }
            return true
        case .macosVersion:
            var i = 0
            var parts = 0
            while true {
                if !digits(&i) { return false }
                parts += 1
                if i == u.count { break }
                if u[i] != 0x2E { return false }
                i += 1
            }
            return parts >= 2 && parts <= 3
        }
    }

    // ---- encoding ------------------------------------------------------------------------------------------------------

    public static func encode(_ r: Request) throws -> [UInt8] {
        guard let spec = ProtocolSpec.requestParams(r.op) else { throw Reject(.unknownOp) }
        var out: [UInt8] = Array("{\"op\":".utf8)
        appendJSONString(r.op, to: &out)
        try appendIDAndFields(r.id, r.fields, spec, to: &out)
        return out
    }

    public static func encode(_ reply: Reply) throws -> [UInt8] {
        switch reply {
        case .success(let op, let id, let fields):
            guard let spec = ProtocolSpec.replyFields(op) else { throw Reject(.unknownOp) }
            var out: [UInt8] = Array("{\"ok\":true".utf8)
            try appendIDAndFields(id, fields, spec, to: &out)
            return out
        case .failure(let id, let code, let message):
            var out: [UInt8] = Array("{\"ok\":false".utf8)
            let fields = Fields([(name: "code", value: .text(code)), (name: "message", value: .text(message))])
            try appendIDAndFields(id, fields, ProtocolSpec.errorFields, to: &out)
            return out
        }
    }

    public static func encode(_ e: Event) throws -> [UInt8] {
        guard let spec = ProtocolSpec.eventFields(e.name) else { throw Reject(.unknownOp) }
        var out: [UInt8] = Array("{\"ev\":".utf8)
        appendJSONString(e.name, to: &out)
        try appendIDAndFields(nil, e.fields, spec, to: &out)
        return out
    }

    private static func appendIDAndFields(_ id: Int64?, _ fields: Fields, _ spec: [FieldSpec], to out: inout [UInt8]) throws {
        if let id = id {
            guard id >= 0, id <= ProtocolSpec.maxSafeInteger else { throw Reject(.badField) }
            out.append(contentsOf: Array(",\"id\":\(id)".utf8))
        }
        for f in fields.items where !spec.contains(where: { bytesEqual($0.name, f.name) }) { throw Reject(.unknownField) }
        for s in spec {
            guard let v = fields[s.name] else { throw Reject(.missingField) }
            guard validate(v, s) else { throw Reject(.badField) }
            out.append(0x2C)
            appendJSONString(s.name, to: &out)
            out.append(0x3A)
            switch v {
            case .int(let n): out.append(contentsOf: Array(String(n).utf8))
            case .bool(let b): out.append(contentsOf: Array((b ? "true" : "false").utf8))
            case .null: out.append(contentsOf: Array("null".utf8))
            case .text(let t): appendJSONString(t, to: &out)
            case .bytes(let b): appendJSONString(StrictBase64.encode(b), to: &out)
            }
        }
        out.append(0x7D)
    }
}
