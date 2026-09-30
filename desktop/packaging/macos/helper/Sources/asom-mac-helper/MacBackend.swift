// UNVERIFIED: written on Linux, never compiled (no macOS here).
#if os(macOS)
import Foundation
import HelperProtocol

/// The shipped backend: every request is a framework call. The helper reads no file and holds no key between requests
/// (macos.md 3.5 security rule): the Enclave blob arrives with each `se.sign`.
final class MacBackend: HelperBackend {
    static let helperVersion = "0.1.0"

    private let assertion = PowerAssertion()
    private let sleepWatcher: SleepWatcher
    private let powerWatcher: PowerSourceWatcher
    private var thermalObserver: NSObjectProtocol?
    private let emit: (Event) -> Void

    init(emit: @escaping (Event) -> Void) {
        self.emit = emit
        sleepWatcher = SleepWatcher(emit: emit)
        powerWatcher = PowerSourceWatcher(emit: emit)
    }

    /// Starts the IOKit run-loop threads and the thermal observer. Events are pushed from here on.
    func startEvents() {
        sleepWatcher.start()
        powerWatcher.start()
        thermalObserver = NotificationCenter.default.addObserver(
            forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: nil
        ) { [emit] _ in
            if let state = try? Probes.thermalState() {
                emit(Event(name: "thermal", fields: Fields([(name: "state", value: .text(state))])))
            }
        }
    }

    func hello() -> Fields {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        #if arch(arm64)
        let arch = "arm64"
        #else
        let arch = "x86_64"
        #endif
        return Fields([
            (name: "helper", value: .text(MacBackend.helperVersion)),
            (name: "macos", value: .text("\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)")),
            (name: "arch", value: .text(arch)),
            (name: "se", value: .bool(Enclave.isAvailable)),
            (name: "model", value: .text(Probes.modelIdentifier())),
        ])
    }

    private func requireEnclave() throws {
        guard Enclave.isAvailable else { throw BackendFailure.unavailable("no Secure Enclave") }
    }

    func seCreate() throws -> Fields {
        try requireEnclave()
        do {
            let (blob, spki) = try Enclave.create()
            return Fields([(name: "blob", value: .bytes(blob)), (name: "spki", value: .bytes(spki))])
        } catch let f as BackendFailure {
            throw f
        } catch {
            throw BackendFailure.failed("Secure Enclave key creation failed")
        }
    }

    func seSign(blob: [UInt8], data: [UInt8]) throws -> Fields {
        try requireEnclave()
        do {
            return Fields([(name: "sig", value: .bytes(try Enclave.sign(blob: blob, data: data)))])
        } catch {
            throw BackendFailure.failed("key blob not usable")
        }
    }

    func seSelftest(blob: [UInt8]) throws -> Fields {
        try requireEnclave()
        do {
            return Fields([(name: "verified", value: .bool(try Enclave.selftest(blob: blob)))])
        } catch {
            throw BackendFailure.failed("key blob not usable")
        }
    }

    func powerGet() throws -> Fields { try Probes.power() }
    func thermalGet() throws -> Fields { try Probes.thermal() }
    func presenceGet() throws -> Fields { try Probes.presence() }
    func gpuGet() throws -> Fields { try Probes.gpu() }
    func memGet() throws -> Fields { try Probes.mem() }

    func assertHold(reason: String) throws { try assertion.hold(reason: reason) }
    func assertRelease() { assertion.release() }

    func sleepAck(token: Int64) throws { try sleepWatcher.ack(token: token) }

    func svc(_ action: String, kind: String) throws -> Fields { try Service.perform(action, kind: kind) }
    func backupExclude(path: String) throws { try Backup.exclude(path: path) }
    func platformUUID() throws -> Fields { try Probes.platformUUID() }
    func pathsGet() throws -> Fields { try Probes.paths() }
}
#endif
