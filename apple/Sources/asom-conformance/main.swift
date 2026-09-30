import AsomConformanceKit
import AsomBenchCore
import AsomJSON
import Foundation

// asom-conformance [lines|check|sigcheck] [FAMILIES] [DIR]
//   lines     one "<id> ok" or "<id> reject <CODE>" per vector this lane runs (LAB_SPEC.md section 3.3)
//   check     compares each verdict and value with the vector's own expectation; non-zero exit on any mismatch
//   sigcheck  r0 only: "<id> sigValidUnderKey1=<bool>", the format of manifest-vectors/crosscheck.out
// DIR defaults to ASOM_CONFORMANCE_DIR, else lab/conformance if it exists, else docs/design/mesh/manifest-vectors.
// A directory whose VERSION is 0.2.0 (lab/conformance) is the r3 set: families M01 to M06. Anything else is the r0 set
// of half I0a (M02 and M03, signature layer only).

func note(_ text: String) {
    FileHandle.standardError.write(Data((text + "\n").utf8))
}

func run() -> Int32 {
    var args = Array(CommandLine.arguments.dropFirst())
    var command = "lines"
    if let first = args.first, ["lines", "check", "sigcheck", "debug-results", "debug-payload", "show"].contains(first) {
        command = first
        args.removeFirst()
    }
    var families: [String]?
    var explicit: String?
    for arg in args {
        if arg.contains("/") || arg == "." { explicit = arg } else { families = arg.split(separator: ",").map(String.init) }
    }
    let cwd = FileManager.default.currentDirectoryPath
    guard let dir = Conformance.resolveDirectory(explicit: explicit, environment: ProcessInfo.processInfo.environment, startingAt: cwd) else {
        note("no conformance directory found (set ASOM_CONFORMANCE_DIR)")
        return 2
    }
    // Diagnostic only (apple/ERRATA.md F-1): ASOM_DIAGNOSTIC_DRIFT_FLAG=1 makes the projection emit `thermal-drift`, as the JVM
    // lane does, so that the lane diff can show what remains once that one ambiguity is set aside. CI's gate diff never sets it.
    let policy: ProjectionPolicy = ProcessInfo.processInfo.environment["ASOM_DIAGNOSTIC_DRIFT_FLAG"] == "1" ? .diagnosticDriftFlag : .specLiteral
    if policy != .specLiteral { note("DIAGNOSTIC projection policy: thermal-drift row flag emitted (not the spec-literal reading)") }
    note("vectors: \(dir)")
    do {
        if R3.isR3Directory(dir) {
            if command == "debug-results" {
                guard args.count >= 2 else { note("debug-results ID [own|file]"); return 2 }
                print(try R3.debugResults(id: args[0], in: dir, audience: args[1] == "file" ? .file : .own, policy: policy))
                return 0
            }
            if command == "show" {
                guard args.count >= 1 else { note("show ID"); return 2 }
                let family = String(args[0].prefix { $0 != "-" })
                for row in try R3.run(families: [family], in: dir, policy: policy) where row.vector.id == args[0] {
                    switch row.observed {
                    case let .ok(v): print(String(decoding: (try? JCS.serialize(v)) ?? [], as: UTF8.self))
                    case let .reject(code): print("reject \(code.rawValue)")
                    case let .notImplemented(kind): print("not implemented: \(kind)")
                    }
                }
                return 0
            }
            if command == "debug-payload" {
                guard args.count >= 1 else { note("debug-payload ID"); return 2 }
                print(try R3.debugPayloadResults(id: args[0], in: dir, policy: policy))
                return 0
            }
            guard command != "sigcheck" else {
                note("sigcheck is the r0 signature-layer format; the r3 signature layer is covered by 'check'")
                return 2
            }
            let rows = try R3.run(families: families ?? R3.families, in: dir, policy: policy)
            for (kind, n) in R3.notImplementedSummary(rows).sorted(by: { $0.key < $1.key }) { note("not implemented in this lane: \(kind) (\(n) vectors)") }
            if command == "check" {
                let bad = R3.mismatches(rows)
                for m in bad { print("MISMATCH \(m.id): \(m.reason)") }
                let ran = rows.filter { if case .notImplemented = $0.observed { return false } else { return true } }.count
                print("checked \(ran), mismatches \(bad.count)")
                return ran == 0 || !bad.isEmpty ? 1 : 0
            }
            for line in R3.lines(rows) { print(line) }
            return 0
        }
        let r0 = families ?? ["M02", "M03"]
        switch command {
        case "sigcheck":
            for line in try Conformance.signatureLayerLines(in: dir) { print(line) }
        case "check":
            var checked = 0, bad = 0
            for family in r0 {
                guard let results = try Conformance.evaluate(family: family, in: dir) else {
                    note("\(family): not-implemented in half I0a")
                    continue
                }
                for r in results {
                    checked += 1
                    let problem = Conformance.disagreement(r)
                    if problem != nil { bad += 1 }
                    print("\(r.id) \(r.classification) \(problem.map { "MISMATCH: " + $0 } ?? "consistent")")
                }
            }
            print("checked \(checked), disagreements \(bad)")
            if checked == 0 || bad > 0 { return 1 }
        default:
            for line in try Conformance.lines(families: r0, in: dir, log: note) { print(line) }
        }
    } catch {
        note("error: \(error)")
        return 2
    }
    return 0
}

exit(run())
