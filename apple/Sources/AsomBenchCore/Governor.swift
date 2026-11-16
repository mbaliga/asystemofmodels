/// The governor state machine (benchmark.md section 11.4, with design B9: an abort goes ABORTING to FINALIZING with the
/// partial results, so the final state of every run is DONE).
public enum GovernorState: String, Sendable, CaseIterable {
    case idle = "IDLE"
    case preflight = "PREFLIGHT"
    case awaitConsent = "AWAIT_CONSENT"
    case preparing = "PREPARING"
    case cooling = "COOLING"
    case running = "RUNNING"
    case yielded = "YIELDED"
    case finalizing = "FINALIZING"
    case aborting = "ABORTING"
    case done = "DONE"
}

public struct GovernorTransitionRefused: Error, Equatable, Sendable, CustomStringConvertible {
    public let from: GovernorState
    public let to: GovernorState
    public var description: String { "governor transition \(from.rawValue)>\(to.rawValue) is not an edge" }
}

public struct Governor: Sendable {
    public private(set) var state: GovernorState = .idle

    public init() {}

    /// Every allowed edge. `any --hard ceiling | Stop | backgrounded | charger removed | wall cap--> ABORTING` covers the
    /// states that are doing something: not IDLE (nothing started), not FINALIZING or DONE (the run is over), not ABORTING itself.
    static let edgeTable: [GovernorState: Set<GovernorState>] = [
        .idle: [.preflight],
        .preflight: [.idle, .awaitConsent, .aborting],
        .awaitConsent: [.idle, .preparing, .aborting],
        .preparing: [.cooling, .aborting],
        .cooling: [.running, .finalizing, .aborting],
        .running: [.cooling, .yielded, .finalizing, .aborting],
        .yielded: [.cooling, .finalizing, .aborting],
        .finalizing: [.done],
        .aborting: [.finalizing],
        .done: [],
    ]

    public static func isEdge(_ from: GovernorState, _ to: GovernorState) -> Bool {
        edgeTable[from]?.contains(to) ?? false
    }

    /// Every allowed edge as `FROM>TO`, sources and targets in declaration order of `GovernorState`.
    public static var edges: [String] {
        var out: [String] = []
        for from in GovernorState.allCases {
            for to in GovernorState.allCases where isEdge(from, to) { out.append("\(from.rawValue)>\(to.rawValue)") }
        }
        return out
    }

    public mutating func to(_ next: GovernorState) throws {
        guard Self.isEdge(state, next) else { throw GovernorTransitionRefused(from: state, to: next) }
        state = next
    }
}
