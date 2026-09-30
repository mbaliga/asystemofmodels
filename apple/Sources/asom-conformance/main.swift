import AsomConformanceKit
import Foundation

// asom-conformance [lines|check|sigcheck] [FAMILIES] [DIR]
//   lines     one "<id> ok" or "<id> reject <CODE>" per vector the DSSE layer settles (LAB_SPEC.md section 3.3)
//   check     compares each verdict with the vector's own expectation; non-zero exit on any disagreement
//   sigcheck  "<id> sigValidUnderKey1=<bool>", the format of manifest-vectors/crosscheck.out
// DIR defaults to ASOM_CONFORMANCE_DIR, else lab/conformance if it exists, else docs/design/mesh/manifest-vectors.

func note(_ text: String) {
    FileHandle.standardError.write(Data((text + "\n").utf8))
}

func run() -> Int32 {
    var args = Array(CommandLine.arguments.dropFirst())
    var command = "lines"
    if let first = args.first, ["lines", "check", "sigcheck"].contains(first) {
        command = first
        args.removeFirst()
    }
    var families = ["M02", "M03"]
    var explicit: String?
    for arg in args {
        if arg.contains("/") || arg == "." { explicit = arg } else { families = arg.split(separator: ",").map(String.init) }
    }
    let cwd = FileManager.default.currentDirectoryPath
    guard let dir = Conformance.resolveDirectory(explicit: explicit, environment: ProcessInfo.processInfo.environment, startingAt: cwd) else {
        note("no conformance directory found (set ASOM_CONFORMANCE_DIR)")
        return 2
    }
    note("vectors: \(dir)")
    do {
        switch command {
        case "sigcheck":
            for line in try Conformance.signatureLayerLines(in: dir) { print(line) }
        case "check":
            var checked = 0, bad = 0
            for family in families {
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
            for line in try Conformance.lines(families: families, in: dir, log: note) { print(line) }
        }
    } catch {
        note("error: \(error)")
        return 2
    }
    return 0
}

exit(run())
