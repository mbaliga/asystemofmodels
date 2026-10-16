// UNVERIFIED: written on Linux, never compiled, never run on a Mac. Registering from a secondary executable in Contents/MacOS is
// assumption AM03 (spike S-M2); on a hosted runner the result is recorded, non-gating (AM20).
#if os(macOS)
import Foundation
import HelperProtocol
import ServiceManagement

/// SMAppService registration of the node's LaunchAgent (mode A) or LaunchDaemon (mode B, M2). The plists live in the calling app's
/// Contents/Library/LaunchAgents and Contents/Library/LaunchDaemons (macos.md 3.2). Used by the CLI and companion, not by the running node.
enum Service {
    static let agentPlist = "xyz.mdhv.asom.node.plist"
    static let daemonPlist = "xyz.mdhv.asom.noded.plist"

    private static func service(_ kind: String) -> SMAppService {
        kind == "daemon" ? SMAppService.daemon(plistName: daemonPlist) : SMAppService.agent(plistName: agentPlist)
    }

    private static func name(_ status: SMAppService.Status) -> String {
        switch status {
        case .notRegistered: return "notRegistered"
        case .enabled: return "enabled"
        case .requiresApproval: return "requiresApproval"
        case .notFound: return "notFound"
        @unknown default: return "notFound"
        }
    }

    static func perform(_ action: String, kind: String) throws -> Fields {
        let s = service(kind)
        switch action {
        case "status":
            break
        case "register":
            do {
                try s.register()
            } catch {
                // `requiresApproval` is reported through an error by some OS versions: the status decides, not the throw.
                let status = s.status
                if status != .requiresApproval && status != .enabled { throw BackendFailure.failed("SMAppService register failed") }
            }
        case "unregister":
            let done = DispatchSemaphore(value: 0)
            var failure: Error?
            s.unregister { error in
                failure = error
                done.signal()
            }
            done.wait()
            if failure != nil && s.status != .notRegistered { throw BackendFailure.failed("SMAppService unregister failed") }
        default:
            throw BackendFailure.badRequest("unknown service action")
        }
        return Fields([(name: "status", value: .text(name(s.status)))])
    }
}

/// Backup exclusion of the node's container (C7; assumption AM18).
enum Backup {
    static func exclude(path: String) throws {
        var url = URL(fileURLWithPath: path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try url.setResourceValues(values)
        } catch {
            throw BackendFailure.failed("cannot exclude")
        }
    }
}
#endif
