import AsomBenchCore
import AsomJSON
import AsomRouterCore

/// The M08 vector file (lab/conformance/router/M08-claim-tracker.json) through `AsomRouterCore`. The vector inputs name no claim key, so the
/// adapter reads them as the vectors do (ERRATA E-36): an `acceptedSha`, `heldBackend` or `heldCommit` that is `null` equals the claim
/// row's value, and a non-null `acceptedSha` or `heldBackend` is a value that differs from it.
extension R3 {
    static let sentinelFileSha256 = String(repeating: "0", count: 64)
    static let sentinelBackend = "claim-row-backend"

    static func claimRow(_ v: JValue?) throws -> ClaimRow {
        guard let v, let prefill = v.member("prefillMilliTokPerSec")?.intValue, let steady = v.member("steadyMilliTokPerSec")?.intValue,
              let ttft0 = v.member("ttft0Ms")?.intValue, let curve = v.member("decodeAt")?.elements else { throw ConformanceError("claim row") }
        let points = try curve.map { p -> DecodePoint in
            guard let pair = p.elements, pair.count == 2, let c = pair[0].intValue, let r = pair[1].intValue else { throw ConformanceError("decodeAt point") }
            return DecodePoint(contextTokens: c, milliTokPerSec: r)
        }
        return ClaimRow(decodeAt: points, prefillMilliTokPerSec: prefill, ttft0Ms: ttft0, steadyMilliTokPerSec: steady, throttleOnsetMs: v.member("throttleOnsetMs")?.intValue)
    }

    static func linkEstimate(_ v: JValue?) throws -> LinkEstimate {
        guard let v, let rtt = v.member("rttMs")?.intValue, let kbps = v.member("kbps")?.intValue else { throw ConformanceError("link") }
        var warm = true
        if case let .bool(b)? = v.member("sessionWarm") { warm = b }
        return LinkEstimate(rttMs: rtt, kbps: kbps, sessionWarm: warm)
    }

    static func bytesPerToken(_ v: JValue?) -> BytesPerToken {
        let pair = (v?.elements ?? []).compactMap { $0.intValue }
        return pair.count == 2 ? BytesPerToken(permille: pair[0], capPermille: pair[1]) : BytesPerToken()
    }

    static func chunks(_ v: JValue?) throws -> [ContentChunk] {
        var out: [ContentChunk] = []
        for c in v?.elements ?? [] {
            if let t = c.member("t")?.intValue, let bytes = c.member("bytes")?.intValue {
                out.append(ContentChunk(tMs: t, bytes: bytes))
            } else if let from = c.member("tFrom")?.intValue, let to = c.member("tTo")?.intValue, let count = c.member("count")?.intValue,
                      let each = c.member("bytesEach")?.intValue, count > 0 {
                // `count` chunks of `bytesEach` bytes, the first at tFrom and the last at tTo, evenly spaced (floor).
                for k in 0..<Int(count) { out.append(ContentChunk(tMs: count == 1 ? from : from + (to - from) * Int64(k) / (count - 1), bytes: each)) }
            } else {
                throw ConformanceError("chunk shape")
            }
        }
        return out
    }

    static func capRef(_ v: JValue?) -> CapRef? {
        guard let v, v.isObject, let p = v.member("prefillMilliTokPerSec")?.intValue, let d = v.member("decodeMilliTokPerSec")?.intValue,
              let s = v.member("steadyMilliTokPerSec")?.intValue else { return nil }
        return CapRef(prefillMilliTokPerSec: p, decodeMilliTokPerSec: d, steadyMilliTokPerSec: s)
    }

    static func viewJSON(_ view: TrackerView) -> JValue {
        .object([
            "n": .int(Int64(view.n)), "ratios": .ints(view.ratios), "best": .nullable(view.best), "state": .string(view.state.rawValue),
            "strikes": .int(Int64(view.strikes)), "budgetTripped": .bool(view.budgetTripped), "minRatio": .int(view.minRatio),
            "tracked": .object(["prefill": .int(view.tracked.prefill), "decodeAtP": .int(view.tracked.decodeAtP), "steady": .int(view.tracked.steady)]),
        ])
    }

    static let doneEnd = "{\"attemptId\":\"a\",\"status\":200,\"terminal\":\"done\"}"

    static func observeM08(_ v: Vector) throws -> Observed {
        let i = v.input
        let kind = i.member("kind")?.stringValue ?? ""
        do {
            switch kind {
            case "evaluate":
                guard let p = i.member("promptTokens")?.intValue, let b = i.member("promptBytes")?.intValue, let maxTokens = i.member("maxTokens")?.intValue,
                      let tBody = i.member("tBodyMs")?.intValue else { throw ConformanceError("\(v.id): evaluate input") }
                var end: InferEnd?
                if let e = i.member("end"), e.isObject, let t = e.member("t")?.intValue { end = InferEnd(tMs: t, payload: e.member("payload")?.stringValue ?? "") }
                var concurrent = false
                if case let .bool(c)? = i.member("concurrent") { concurrent = c }
                let attempt = AttemptInput(
                    tBodyMs: tBody, chunks: try chunks(i.member("chunks")), end: end, promptTokens: p, promptBytes: b, maxTokens: maxTokens,
                    link: try linkEstimate(i.member("link")), claim: try claimRow(i.member("claim")), bpt: bytesPerToken(i.member("bpt")), concurrent: concurrent,
                    acceptedFileSha256: i.member("acceptedSha")?.stringValue, claimFileSha256: sentinelFileSha256,
                    heldBackend: i.member("heldBackend")?.stringValue, claimBackend: sentinelBackend,
                    heldCommit: i.member("heldCommit")?.stringValue, claimCommit: i.member("claimCommit")?.stringValue
                )
                let r = try AttemptEvaluator.evaluate(attempt)
                return .ok(.object([
                    "outBytes": .int(r.outBytes), "outTokEst": .int(r.outTokEst), "predictedMs": .int(r.predictedMs), "elapsedMs": .int(r.elapsedMs),
                    "ratio": .int(r.ratio), "discard": r.discard.map { .string($0.rawValue) } ?? .null,
                ]))
            case "sequence":
                guard let p = i.member("promptTokens")?.intValue, let b = i.member("promptBytes")?.intValue, let maxTokens = i.member("maxTokens")?.intValue,
                      let disc = i.member("disc")?.intValue else { throw ConformanceError("\(v.id): sequence input") }
                let claim = try claimRow(i.member("claim")), link = try linkEstimate(i.member("link")), bpt = bytesPerToken(i.member("bpt"))
                var tracker = ClaimTracker()
                for o in i.member("observations")?.elements ?? [] {
                    guard let bytes = o.member("bytes")?.intValue, let elapsed = o.member("elapsed")?.intValue else { throw ConformanceError("\(v.id): observation") }
                    var concurrent = false
                    if case let .bool(c)? = o.member("concurrent") { concurrent = c }
                    let attempt = AttemptInput(
                        tBodyMs: 0, chunks: [ContentChunk(tMs: elapsed, bytes: bytes)], end: InferEnd(tMs: elapsed, payload: doneEnd), promptTokens: p, promptBytes: b,
                        maxTokens: maxTokens, link: link, claim: claim, bpt: bpt, concurrent: concurrent, claimFileSha256: sentinelFileSha256
                    )
                    tracker.onObservation(try AttemptEvaluator.evaluate(attempt))
                }
                return .ok(viewJSON(try tracker.view(claim: claim, promptTokens: p, disc: disc, capRef: capRef(i.member("ceiling")))))
            case "state":
                guard let p = i.member("promptTokens")?.intValue, let disc = i.member("disc")?.intValue else { throw ConformanceError("\(v.id): state input") }
                var recent: [RecentObservation] = []
                for r in i.member("recent")?.elements ?? [] {
                    guard let ratio = r.member("ratio")?.intValue, let bytes = r.member("outBytes")?.intValue, case let .bool(kept)? = r.member("kept") else { throw ConformanceError("\(v.id): recent") }
                    recent.append(RecentObservation(kept: kept, ratio: ratio, outBytes: bytes))
                }
                var inherited = false
                if case let .bool(b)? = i.member("inheritedDiscrepant") { inherited = b }
                let tracker = ClaimTracker(window: intArray(i.member("ratios")), recent: recent, strikes: 0, inheritedDiscrepant: inherited)
                return .ok(viewJSON(try tracker.view(claim: try claimRow(i.member("claim")), promptTokens: p, disc: disc, capRef: capRef(i.member("ceiling")))))
            case "claimBody":
                var gate = ClaimBodyGate()
                var accepted: [JValue] = []
                for e in i.member("events")?.elements ?? [] {
                    guard let seq = e.member("seq")?.intValue, let at = e.member("at")?.intValue else { throw ConformanceError("\(v.id): event") }
                    accepted.append(.bool(gate.accept(seq: seq, atMs: at)))
                }
                return .ok(.object(["accepted": .array(accepted)]))
            default:
                return .notImplemented(kind)
            }
        } catch is BenchArithmeticError {
            return .reject(.inconsistent)
        }
    }
}
