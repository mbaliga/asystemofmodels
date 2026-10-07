import AsomBenchCore
import AsomJSON

/// The r3 claim tracker of LAB_SPEC.md section 6.6: what a requester learns about a peer's claimed speed from its own observations.
/// Written from the spec text and the M08 vector files; the JVM implementation (`lab/mesh-router`) and the runner's M08 adapter were not
/// opened. It is a second implementation, cross-lane evidence, and NOT independent: the vectors fixed several of its readings (apple/ERRATA.md
/// E-33, ERR-FX-M08-1, ERR-FX2-ASC10).
/// Integers only, floor division unless `ceilDiv`, every multiplication checked.
public enum TrackerConstants {
    public static let win = 20
    public static let minKeep = 3
    public static let minState = 5
    public static let corr: Int64 = 800
    public static let disc: Int64 = 600
    public static let ratioCap: Int64 = 5000
    public static let shortBytes: Int64 = 128
    /// The discard budget applies once this many observations exist (spec: "once >= 4 candidate observations exist for k").
    public static let budgetMinObservations = 4
    /// Observations with fewer bytes cannot set the clamp of a tripped budget (spec: "outBytes >= 8").
    public static let clampMinBytes: Int64 = 8
    public static let inheritedClearObservations = 10
    public static let defaultDiscPermille: Int64 = 700
        public static let claimBodyIntervalMs: Int64 = 86_400_000
    /// The first penalty of a peer: "for >= 7 days, doubling on repeat".
    public static let penaltyBaseMs: Int64 = 604_800_000
    public static let repeatedDiscPermille: Int64 = 400
    public static let bytesPerTokenDefault: Int64 = 4000
    public static let bytesPerTokenCapDefault: Int64 = 8000
}

public struct DecodePoint: Sendable, Equatable {
    public let contextTokens: Int64
    public let milliTokPerSec: Int64

    public init(contextTokens: Int64, milliTokPerSec: Int64) {
        self.contextTokens = contextTokens
        self.milliTokPerSec = milliTokPerSec
    }
}

/// The claim row of a verified manifest for one (peer, file, backend): the prior of the estimator.
public struct ClaimRow: Sendable, Equatable {
    public var decodeAt: [DecodePoint]
    public var prefillMilliTokPerSec: Int64
    public var ttft0Ms: Int64
    public var steadyMilliTokPerSec: Int64
    public var throttleOnsetMs: Int64?

    public init(decodeAt: [DecodePoint], prefillMilliTokPerSec: Int64, ttft0Ms: Int64, steadyMilliTokPerSec: Int64, throttleOnsetMs: Int64?) {
        self.decodeAt = decodeAt
        self.prefillMilliTokPerSec = prefillMilliTokPerSec
        self.ttft0Ms = ttft0Ms
        self.steadyMilliTokPerSec = steadyMilliTokPerSec
        self.throttleOnsetMs = throttleOnsetMs
    }

    /// Piecewise linear over the points (ascending context), clamped at the ends. Between two points the value is the floor of the
    /// linear interpolation computed on the non-negative numerator (the spec says "piecewise linear" and fixes no rounding: E-34).
    public func decodeRate(atContext p: Int64) throws -> Int64 {
        guard let first = decodeAt.first, let last = decodeAt.last else { throw BenchArithmeticError(operation: "empty decode curve") }
        guard zip(decodeAt, decodeAt.dropFirst()).allSatisfy({ $0.contextTokens < $1.contextTokens }) else {
            throw BenchArithmeticError(operation: "decode curve is not strictly ascending")
        }
        if p <= first.contextTokens { return first.milliTokPerSec }
        if p >= last.contextTokens { return last.milliTokPerSec }
        for k in 1..<decodeAt.count where p <= decodeAt[k].contextTokens {
            let lo = decodeAt[k - 1], hi = decodeAt[k]
            let span = hi.contextTokens - lo.contextTokens
            let weighted = try Checked.add(try Checked.mul(lo.milliTokPerSec, hi.contextTokens - p), try Checked.mul(hi.milliTokPerSec, p - lo.contextTokens))
            return weighted / span
        }
        return last.milliTokPerSec
    }
}

public struct LinkEstimate: Sendable, Equatable {
    public var rttMs: Int64
    public var kbps: Int64
    /// Carried as the spec's `LinkStats` has it; the tracker's prediction does not read it (ERRATA E-38).
    public var sessionWarm: Bool

    public init(rttMs: Int64, kbps: Int64, sessionWarm: Bool) {
        self.rttMs = rttMs
        self.kbps = kbps
        self.sessionWarm = sessionWarm
    }
}

public struct BytesPerToken: Sendable, Equatable {
    public var permille: Int64
    public var capPermille: Int64

    public init(permille: Int64 = TrackerConstants.bytesPerTokenDefault, capPermille: Int64 = TrackerConstants.bytesPerTokenCapDefault) {
        self.permille = permille
        self.capPermille = capPermille
    }
}

public enum Discard: String, Sendable, CaseIterable {
    case incomplete = "INCOMPLETE"
    case short = "SHORT"
    case overlong = "OVERLONG"
    case concurrent = "CONCURRENT"
    case settings = "SETTINGS"
}

public struct ContentChunk: Sendable, Equatable {
    public var tMs: Int64
    /// UTF-8 bytes of the answer text parsed from this chunk (never SSE framing, JSON syntax, role or finish fields).
    public var bytes: Int64

    public init(tMs: Int64, bytes: Int64) {
        self.tMs = tMs
        self.bytes = bytes
    }
}

public struct InferEnd: Sendable, Equatable {
    public var tMs: Int64
    /// The frame payload as received. Only `terminal` is read; every other member is ignored and never stored.
    public var payload: String

    public init(tMs: Int64, payload: String) {
        self.tMs = tMs
        self.payload = payload
    }

    /// True only for a payload that is one strict JSON object whose `terminal` is the string `done`.
    public var terminalIsDone: Bool {
        guard case let .success(value) = StrictJSON.parse(Array(payload.utf8)), value.isObject, let terminal = value.member("terminal")?.stringValue else { return false }
        return codeUnitsEqual(terminal, "done")
    }
}

/// What one attempt that sent `INFER_BODY` looks like to the requester (LAB_SPEC.md 6.6, "per attempt").
public struct AttemptInput: Sendable {
    public var tBodyMs: Int64
    public var chunks: [ContentChunk]
    /// Nil when the stream was lost: no `INFER_END` arrived.
    public var end: InferEnd?
    public var promptTokens: Int64
    public var promptBytes: Int64
    public var maxTokens: Int64
    public var link: LinkEstimate
    public var claim: ClaimRow
    public var bpt: BytesPerToken
    public var concurrent: Bool
    /// `INFER_ACCEPT.fileSha256` against the claim key's.
    public var acceptedFileSha256: String?
    public var claimFileSha256: String
    /// The latest engine backend and commit this requester holds for the peer, against the claim row's.
    public var heldBackend: String?
    public var claimBackend: String?
    public var heldCommit: String?
    public var claimCommit: String?

    public init(
        tBodyMs: Int64, chunks: [ContentChunk], end: InferEnd?, promptTokens: Int64, promptBytes: Int64, maxTokens: Int64, link: LinkEstimate,
        claim: ClaimRow, bpt: BytesPerToken = BytesPerToken(), concurrent: Bool = false, acceptedFileSha256: String? = nil, claimFileSha256: String = "",
        heldBackend: String? = nil, claimBackend: String? = nil, heldCommit: String? = nil, claimCommit: String? = nil
    ) {
        self.tBodyMs = tBodyMs
        self.chunks = chunks
        self.end = end
        self.promptTokens = promptTokens
        self.promptBytes = promptBytes
        self.maxTokens = maxTokens
        self.link = link
        self.claim = claim
        self.bpt = bpt
        self.concurrent = concurrent
        self.acceptedFileSha256 = acceptedFileSha256
        self.claimFileSha256 = claimFileSha256
        self.heldBackend = heldBackend
        self.claimBackend = claimBackend
        self.heldCommit = heldCommit
        self.claimCommit = claimCommit
    }
}

public struct AttemptResult: Sendable, Equatable {
    public let outBytes: Int64
    public let outTokEst: Int64
    public let predictedMs: Int64
    public let elapsedMs: Int64
    public let ratio: Int64
    public let discard: Discard?
}

public enum ClaimState: String, Sendable {
    case unverified = "UNVERIFIED"
    case corroborated = "CORROBORATED"
    case weak = "WEAK"
    case discrepant = "DISCREPANT"
}

public struct RecentObservation: Sendable, Equatable {
    public var kept: Bool
    public var ratio: Int64
    public var outBytes: Int64

    public init(kept: Bool, ratio: Int64, outBytes: Int64) {
        self.kept = kept
        self.ratio = ratio
        self.outBytes = outBytes
    }
}

/// The reference cap on a prior (`capRef`), as the three rates the tracker clamps. Never the claim itself.
public struct CapRef: Sendable, Equatable {
    public var prefillMilliTokPerSec: Int64
    public var decodeMilliTokPerSec: Int64
    public var steadyMilliTokPerSec: Int64

    public init(prefillMilliTokPerSec: Int64, decodeMilliTokPerSec: Int64, steadyMilliTokPerSec: Int64) {
        self.prefillMilliTokPerSec = prefillMilliTokPerSec
        self.decodeMilliTokPerSec = decodeMilliTokPerSec
        self.steadyMilliTokPerSec = steadyMilliTokPerSec
    }

    /// `min(signedReferenceP90 * 12 / 10 [only if signed by the compiled-in reference key], classCeiling)`.
    public static func rate(signedReferenceP90: Int64?, classCeiling: Int64) throws -> Int64 {
        guard let p90 = signedReferenceP90 else { return classCeiling }
        return min(try Checked.mul(p90, 12) / 10, classCeiling)
    }
}

public struct TrackedRates: Sendable, Equatable {
    public let prefill: Int64
    public let decodeAtP: Int64
    public let steady: Int64
}

public struct TrackerView: Sendable, Equatable {
    public let n: Int
    /// The window in time order (the last WIN kept ratios).
    public let ratios: [Int64]
    public let best: Int64?
    public let state: ClaimState
    public let strikes: Int
    public let budgetTripped: Bool
    public let minRatio: Int64
    public let tracked: TrackedRates
}

public enum AttemptEvaluator {
    public static func ceilDiv(_ a: Int64, _ b: Int64) throws -> Int64 {
        guard b > 0 else { throw BenchArithmeticError(operation: "division by \(b)") }
        guard a >= 0 else { throw BenchArithmeticError(operation: "ceilDiv of a negative number") }
        return a / b + (a % b == 0 ? 0 : 1)
    }

    /// E1 on the warm path only: `rtt + ceilDiv(B * 8, kbps)`. `tBody` is taken after the handshake, so the cold-path term of E1 would raise the ratio
    /// in the peer's favour (R3-CLOSURE-5 item 11, ERRATA E-38); `link.sessionWarm` is therefore not read here.
    static func netMs(_ link: LinkEstimate, bytes: Int64) throws -> Int64 {
        try Checked.add(link.rttMs, try ceilDiv(try Checked.mul(bytes, 8), link.kbps))
    }

    /// `thermalAwareDecode` with the claim row only: busy 0, queue 0, hot false.
    static func decodeMs(m: Int64, dec: Int64, steady: Int64, onsetMs: Int64?, prefillMs: Int64) throws -> Int64 {
        if m <= 0 { return 0 }
        let micro: Int64 = 1_000_000
        let already = prefillMs
        if let onset = onsetMs, already >= onset { return try ceilDiv(try Checked.mul(m, micro), min(dec, steady)) }
        guard let onset = onsetMs else { return try ceilDiv(try Checked.mul(m, micro), dec) }
        let coolMs = onset - already
        let tokCool = try Checked.mul(coolMs, dec) / micro
        if m <= tokCool { return try ceilDiv(try Checked.mul(m, micro), dec) }
        return try Checked.add(coolMs, try ceilDiv(try Checked.mul(m - tokCool, micro), steady))
    }

    public static func evaluate(_ a: AttemptInput) throws -> AttemptResult {
        var outBytes: Int64 = 0
        var lastChunk = a.tBodyMs
        for c in a.chunks {
            outBytes = try Checked.add(outBytes, c.bytes)
            lastChunk = max(lastChunk, c.tMs)
        }
        let tEnd = max(a.end?.tMs ?? a.tBodyMs, lastChunk)
        let elapsed = max(1, try Checked.add(tEnd, -a.tBodyMs))
        let outTokEst = try ceilDiv(try Checked.mul(outBytes, 1000), a.bpt.permille)

        let netMs = try Self.netMs(a.link, bytes: a.promptBytes)
        let prefillMs = try Checked.add(try ceilDiv(try Checked.mul(a.promptTokens, 1_000_000), a.claim.prefillMilliTokPerSec), a.claim.ttft0Ms)
        let dec = try a.claim.decodeRate(atContext: a.promptTokens)
        let decode = try decodeMs(m: outTokEst - 1, dec: dec, steady: a.claim.steadyMilliTokPerSec, onsetMs: a.claim.throttleOnsetMs, prefillMs: prefillMs)
        let predicted = try Checked.add(try Checked.add(netMs, prefillMs), decode)
        let ratio = min(TrackerConstants.ratioCap, try Checked.mul(predicted, 1000) / elapsed)

        // The first match wins.
        var discard: Discard?
        if a.end == nil || a.end?.terminalIsDone != true {
            discard = .incomplete
        } else if outBytes < TrackerConstants.shortBytes {
            discard = .short
        } else if outBytes > (try Checked.mul(a.maxTokens, a.bpt.capPermille) / 1000) {
            discard = .overlong
        } else if a.concurrent {
            discard = .concurrent
        } else if settingsDiffer(a) {
            discard = .settings
        }
        return AttemptResult(outBytes: outBytes, outTokEst: outTokEst, predictedMs: predicted, elapsedMs: elapsed, ratio: ratio, discard: discard)
    }

    static func settingsDiffer(_ a: AttemptInput) -> Bool {
        func differs(_ held: String?, _ claim: String?) -> Bool {
            guard let held else { return false }
            guard let claim else { return true }
            return !codeUnitsEqual(held, claim)
        }
        if let accepted = a.acceptedFileSha256, !codeUnitsEqual(accepted, a.claimFileSha256) { return true }
        return differs(a.heldBackend, a.claimBackend) || differs(a.heldCommit, a.claimCommit)
    }
}

/// The state of one key k = (peer, file): the window of kept ratios, the recent observations for the discard budget, the strikes.
public struct ClaimTracker: Sendable, Equatable {
    public private(set) var window: [Int64] = []
    public private(set) var recent: [RecentObservation] = []
    public private(set) var strikes = 0
    public private(set) var inheritedDiscrepant = false

    public init() {}

    public init(window: [Int64], recent: [RecentObservation], strikes: Int, inheritedDiscrepant: Bool) {
        self.window = Array(window.suffix(TrackerConstants.win))
        self.recent = Array(recent.suffix(TrackerConstants.win))
        self.strikes = strikes
        self.inheritedDiscrepant = inheritedDiscrepant
    }

    /// Feeds one finished attempt. A discarded attempt never enters the window; an OVERLONG one adds a strike.
    public mutating func onObservation(_ r: AttemptResult) {
        recent.append(RecentObservation(kept: r.discard == nil, ratio: r.ratio, outBytes: r.outBytes))
        if recent.count > TrackerConstants.win { recent.removeFirst(recent.count - TrackerConstants.win) }
        if r.discard == .overlong { strikes += 1 }
        guard r.discard == nil else { return }
        window.append(r.ratio)
        if window.count > TrackerConstants.win { window.removeFirst(window.count - TrackerConstants.win) }
        if inheritedDiscrepant, let best = Self.best(window), window.count >= TrackerConstants.inheritedClearObservations, best >= TrackerConstants.corr {
            inheritedDiscrepant = false
        }
    }

    /// A new claim seq restarts W against the new claim and inherits DISCREPANT until 10 new observations have `best >= CORR`. Strikes survive,
    /// and so does the discard-budget record `recent`: only W restarts (LAB_SPEC 6.6, "a new claim seq restarts W but inherits DISCREPANT";
    /// lab ERR-FX-RT-3, M08-068/-069). Without that a peer clamped to WEAK by truncation would escape the clamp by publishing a new seq once
    /// per 24 h, because WEAK itself is not inherited. The record ages out through new observations (the last 20, more than half discarded).
    public mutating func onNewClaimSeq() {
        if Self.state(window: window, recent: recent, inheritedDiscrepant: inheritedDiscrepant).state == .discrepant { inheritedDiscrepant = true }
        window = []
    }

    static func best(_ ratios: [Int64]) -> Int64? {
        guard !ratios.isEmpty else { return nil }
        return ratios.sorted()[(3 * ratios.count) / 4]
    }

    static func budgetTripped(_ recent: [RecentObservation]) -> Bool {
        let last = recent.suffix(TrackerConstants.win)
        guard last.count >= TrackerConstants.budgetMinObservations else { return false }
        let discarded = last.filter { !$0.kept }.count
        return discarded * 2 > last.count
    }

    static func minRatio(_ recent: [RecentObservation]) -> Int64 {
        recent.suffix(TrackerConstants.win).filter { $0.outBytes >= TrackerConstants.clampMinBytes }.map(\.ratio).min() ?? 0
    }

    static func state(window: [Int64], recent: [RecentObservation], inheritedDiscrepant: Bool) -> (state: ClaimState, tripped: Bool) {
        let n = window.count
        let best = best(window)
        var state: ClaimState
        if n < TrackerConstants.minState {
            state = .unverified
        } else if let b = best, b >= TrackerConstants.corr {
            state = .corroborated
        } else if let b = best, b >= TrackerConstants.disc {
            state = .weak
        } else {
            state = .discrepant
        }
        let tripped = budgetTripped(recent)
        if tripped, state != .discrepant { state = .weak }
        if inheritedDiscrepant, !(n >= TrackerConstants.inheritedClearObservations && (best ?? 0) >= TrackerConstants.corr) { state = .discrepant }
        return (state, tripped)
    }

    /// The tracked rates for placement (never above the claim). `disc` is 700, or 400 when two or more keys of this peer are DISCREPANT.
    public func view(claim: ClaimRow, promptTokens: Int64, disc: Int64 = TrackerConstants.defaultDiscPermille, capRef: CapRef? = nil) throws -> TrackerView {
        guard (0...1000).contains(disc) else { throw BenchArithmeticError(operation: "disc \(disc) outside 0...1000") }
        let n = window.count
        let sorted = window.sorted()
        let (state, tripped) = Self.state(window: window, recent: recent, inheritedDiscrepant: inheritedDiscrepant)
        let minRatio = Self.minRatio(recent)

        func tracked(_ claimRate: Int64, _ cap: Int64?) throws -> Int64 {
            var rate: Int64
            if n >= TrackerConstants.minKeep {
                let eff = min(1000, sorted[(n - 1) / 2])
                rate = try Checked.mul(claimRate, eff) / 1000
            } else {
                let prior = try Checked.mul(min(claimRate, cap ?? claimRate), disc) / 1000
                var sum = try Checked.mul(2, prior)
                for x in window { sum = try Checked.add(sum, try Checked.mul(claimRate, min(1000, x)) / 1000) }
                rate = sum / Int64(2 + n)
            }
            if tripped { rate = min(rate, try Checked.mul(claimRate, minRatio) / 1000) }
            return min(rate, claimRate)
        }
        let rates = TrackedRates(
            prefill: try tracked(claim.prefillMilliTokPerSec, capRef?.prefillMilliTokPerSec),
            decodeAtP: try tracked(try claim.decodeRate(atContext: promptTokens), capRef?.decodeMilliTokPerSec),
            steady: try tracked(claim.steadyMilliTokPerSec, capRef?.steadyMilliTokPerSec)
        )
        return TrackerView(n: n, ratios: window, best: Self.best(window), state: state, strikes: strikes, budgetTripped: tripped, minRatio: minRatio, tracked: rates)
    }
}

/// "A peer's new claim body is accepted for routing at most once per 24 h", and never with a seq that is not higher.
public struct ClaimBodyGate: Sendable, Equatable {
    private var lastSeq: Int64?
    private var lastAcceptedAtMs: Int64?

    public init() {}

    public mutating func accept(seq: Int64, atMs: Int64) -> Bool {
        if let lastSeq, seq <= lastSeq { return false }
        if let last = lastAcceptedAtMs, atMs - last < TrackerConstants.claimBodyIntervalMs { return false }
        lastSeq = seq
        lastAcceptedAtMs = atMs
        return true
    }
}

/// The per-peer penalty of `disc`: "400 if >= 2 keys of this peer are DISCREPANT (for >= 7 days, doubling on repeat)". Wall-clock milliseconds.
public struct DiscPenalty: Sendable, Equatable {
    public let untilWallMs: Int64
    public let repeats: Int

    public init(untilWallMs: Int64, repeats: Int) {
        self.untilWallMs = untilWallMs
        self.repeats = repeats
    }

    /// The penalty is running while `now < untilWallMs`: at `untilWallMs` it has ended (M08-078).
    public func runs(atWallMs now: Int64) -> Bool { now < untilWallMs }
}

/// What a peer's files (keys k = (peer, file)) say together: the trackers by file and the one penalty latch of the peer.
public struct PeerClaimBook: Sendable, Equatable {
    public private(set) var files: [String: ClaimTracker]
    public private(set) var penalty: DiscPenalty?

    public init(files: [String: ClaimTracker] = [:], penalty: DiscPenalty? = nil) {
        self.files = files
        self.penalty = penalty
    }

    /// Files whose state is DISCREPANT (by ratios or by inheritance). Files, not observations.
    public var discrepantFileCount: Int {
        files.values.filter { ClaimTracker.state(window: $0.window, recent: $0.recent, inheritedDiscrepant: $0.inheritedDiscrepant).state == .discrepant }.count
    }

    /// Feeds one finished attempt of `file`. Once it is applied, two or more DISCREPANT files latch a penalty when none is running: 7 days
    /// from `atWallMs`, doubled for every earlier latch (7, 14, 28 ... days). A running penalty is not extended, the repeat count is never
    /// reset, and a duration or end that would overflow saturates at `Int64.max` (apple/ERRATA.md ERR-FX-M08-2).
    public mutating func onObservation(file: String, result: AttemptResult, atWallMs: Int64) throws {
        var tracker = files[file] ?? ClaimTracker()
        tracker.onObservation(result)
        files[file] = tracker
        guard discrepantFileCount >= 2 else { return }
        if let running = penalty, running.runs(atWallMs: atWallMs) { return }
        let repeats = (penalty?.repeats ?? 0) + 1
        var duration = TrackerConstants.penaltyBaseMs
        for _ in 1..<max(repeats, 1) {
            let (doubled, overflow) = duration.multipliedReportingOverflow(by: 2)
            if overflow { duration = Int64.max; break }
            duration = doubled
        }
        let (until, overflow) = atWallMs.addingReportingOverflow(duration)
        penalty = DiscPenalty(untilWallMs: overflow ? Int64.max : until, repeats: repeats)
    }

    /// A new claim seq of `file` (after `ClaimBodyGate` accepted it). A file the book has not seen stays unseen.
    public mutating func onNewClaimSeq(file: String) {
        guard var tracker = files[file] else { return }
        tracker.onNewClaimSeq()
        files[file] = tracker
    }

    /// `disc`: 400 while a penalty runs or while two or more files are DISCREPANT, else 700.
    public func discPermille(atWallMs now: Int64) -> Int64 {
        if let penalty, penalty.runs(atWallMs: now) { return TrackerConstants.repeatedDiscPermille }
        return discrepantFileCount >= 2 ? TrackerConstants.repeatedDiscPermille : TrackerConstants.defaultDiscPermille
    }
}
