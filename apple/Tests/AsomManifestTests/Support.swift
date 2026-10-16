@testable import AsomBenchCore
import AsomJSON
import Foundation
import XCTest

struct MissingRepoFile: Error, CustomStringConvertible {
    let description: String
}

func repoPath(_ relative: String, from file: StaticString = #filePath) -> String? {
    var dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
    for _ in 0..<10 {
        let candidate = dir.appendingPathComponent(relative).path
        if FileManager.default.fileExists(atPath: candidate) { return candidate }
        dir = dir.deletingLastPathComponent()
    }
    return nil
}

/// The lab's vector data: ASOM_CONFORMANCE_DIR if set, else lab/conformance found upward from this file.
func labFile(_ relative: String, file: StaticString = #filePath) throws -> JValue {
    var path: String?
    if let dir = ProcessInfo.processInfo.environment["ASOM_CONFORMANCE_DIR"], !dir.isEmpty { path = dir + "/" + relative }
    if path == nil || !FileManager.default.fileExists(atPath: path!) { path = repoPath("lab/conformance/" + relative, from: file) }
    guard let path, let data = FileManager.default.contents(atPath: path) else { throw MissingRepoFile(description: "lab/conformance/\(relative)") }
    switch StrictJSON.parse([UInt8](data)) {
    case let .success(v): return v
    case let .failure(c): throw MissingRepoFile(description: "\(relative): \(c)")
    }
}

func labVector(_ relative: String, _ id: String) throws -> JValue {
    let f = try labFile(relative)
    guard let v = f.member("vectors")?.elements?.first(where: { $0.member("id")?.stringValue == id }) else {
        throw MissingRepoFile(description: "vector \(id) in \(relative)")
    }
    return v
}

/// The design session's worked example, r3 form (M05-201's document): T1 to T3 and a heat test on T3.
func designExample() throws -> JValue {
    guard let doc = try labVector("bench/M05-body.json", "M05-201").member("input")?.member("benchDoc") else { throw MissingRepoFile(description: "benchDoc") }
    return doc
}

enum Step {
    case key(String)
    case at(Int)
}

extension JValue {
    func setting(_ path: [Step], to value: JValue) -> JValue {
        guard let first = path.first else { return value }
        let rest = Array(path.dropFirst())
        switch (first, self) {
        case let (.key(name), .object(members)):
            var found = false
            var out = members.map { m -> JMember in
                if codeUnitsEqual(m.name, name) { found = true; return JMember(name: m.name, value: m.value.setting(rest, to: value)) }
                return m
            }
            if !found { out.append(JMember(name: name, value: JValue.null.setting(rest, to: value))) }
            return .object(out)
        case let (.at(index), .array(items)):
            var out = items
            out[index] = items[index].setting(rest, to: value)
            return .array(out)
        default:
            fatalError("path does not fit the value")
        }
    }

    func removing(_ path: [Step]) -> JValue {
        guard let first = path.first else { return self }
        let rest = Array(path.dropFirst())
        switch (first, self) {
        case let (.key(name), .object(members)):
            if rest.isEmpty { return .object(members.filter { !codeUnitsEqual($0.name, name) }) }
            return .object(members.map { codeUnitsEqual($0.name, name) ? JMember(name: $0.name, value: $0.value.removing(rest)) : $0 })
        case let (.at(index), .array(items)):
            var out = items
            if rest.isEmpty { out.remove(at: index) } else { out[index] = items[index].removing(rest) }
            return .array(out)
        default:
            fatalError("path does not fit the value")
        }
    }

    func value(at path: [Step]) -> JValue? {
        var cur: JValue? = self
        for step in path {
            switch (step, cur) {
            case let (.key(name), .some(v)): cur = v.member(name)
            case let (.at(i), .some(.array(items))): cur = i < items.count ? items[i] : nil
            default: return nil
            }
        }
        return cur
    }
}

func decodeBench(_ v: JValue, file: Bool = false, tolerant: Bool = false) -> Result<BenchDocument, SchemaViolation> {
    do { return .success(try BenchDocument.decode(v, fileForm: file, state: DecodeState(tolerateUnknown: tolerant))) } catch let e as SchemaViolation { return .failure(e) } catch {
        return .failure(SchemaViolation("?", "\(error)"))
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
