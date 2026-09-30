// UNVERIFIED: written on Linux, never compiled, never run on a Mac. The IOKit calls follow the documented signatures; the
// PowerAssertionIT job of desktop-macos.yml (pmset -g assertions, then kill -9) is the first check that they do what is claimed.
#if os(macOS)
import Foundation
import HelperProtocol
import IOKit
import IOKit.ps
import IOKit.pwr_mgt

/// One `kIOPMAssertionTypePreventUserIdleSystemSleep` assertion named "asom: lending compute to your paired devices" (macos.md 2.1).
/// Never `PreventSystemSleep`. It is released on `assert.release`, on stdin EOF, and by IOKit when the process exits (AM11).
final class PowerAssertion {
    private var id: IOPMAssertionID = 0
    private var held = false

    func hold(reason: String) throws {
        if held { return }
        var newID: IOPMAssertionID = 0
        let rc = IOPMAssertionCreateWithName(
            kIOPMAssertionTypePreventUserIdleSystemSleep as CFString,
            IOPMAssertionLevel(kIOPMAssertionLevelOn),
            reason as CFString,
            &newID
        )
        guard rc == kIOReturnSuccess else { throw BackendFailure.failed("IOPMAssertionCreateWithName failed") }
        id = newID
        held = true
    }

    func release() {
        guard held else { return }
        IOPMAssertionRelease(id)
        held = false
    }
}

/// `kIOMessage*` are computed macros that Swift does not import; the values are the documented ones (IOMessage.h).
private let kMessageCanSystemSleep: UInt32 = 0xE000_0270
private let kMessageSystemWillSleep: UInt32 = 0xE000_0280
private let kMessageSystemHasPoweredOn: UInt32 = 0xE000_0300

/// System sleep and wake notifications (IORegisterForSystemPower) and power-source changes, on one CFRunLoop thread.
/// On `kIOMessageSystemWillSleep` it emits `ev:"sleep.will"` with a token and calls `IOAllowPowerChange` when the node answers
/// `sleep.ack` or after 2 seconds, whichever comes first: forced sleep (lid, Apple menu, low battery) cannot be prevented, only
/// delayed [FM08], and a user who closed the lid is not held awake for the 30 s macOS allows (macos.md 2.1).
final class SleepWatcher {
    private let emit: (Event) -> Void
    private let lock = NSLock()
    private var rootPort: io_connect_t = 0
    private var nextToken: Int64 = 1
    private var pending = [Int64: Int]()
    static let ackBudget: TimeInterval = 2.0

    init(emit: @escaping (Event) -> Void) {
        self.emit = emit
    }

    func start() {
        let thread = Thread { [self] in
            var notifyPort: IONotificationPortRef?
            var notifier: io_object_t = 0
            let refcon = Unmanaged.passUnretained(self).toOpaque()
            let callback: IOServiceInterestCallback = { refcon, _, messageType, messageArgument in
                guard let refcon = refcon else { return }
                let me = Unmanaged<SleepWatcher>.fromOpaque(refcon).takeUnretainedValue()
                me.handle(messageType: messageType, argument: Int(bitPattern: messageArgument))
            }
            let port = IORegisterForSystemPower(refcon, &notifyPort, callback, &notifier)
            guard port != 0, let notifyPort = notifyPort else { return }
            lock.lock()
            rootPort = port
            lock.unlock()
            CFRunLoopAddSource(CFRunLoopGetCurrent(), IONotificationPortGetRunLoopSource(notifyPort).takeUnretainedValue(), .commonModes)
            CFRunLoopRun()
        }
        thread.name = "asom-sleep-watcher"
        thread.start()
    }

    fileprivate func handle(messageType: UInt32, argument: Int) {
        switch messageType {
        case kMessageCanSystemSleep:
            // Idle sleep is never vetoed here: the assertion (while SERVING) is what holds it off.
            IOAllowPowerChange(rootPortValue(), argument)
        case kMessageSystemWillSleep:
            lock.lock()
            let token = nextToken
            nextToken += 1
            pending[token] = argument
            lock.unlock()
            emit(Event(name: "sleep.will", fields: Fields([(name: "token", value: .int(token))])))
            DispatchQueue.global().asyncAfter(deadline: .now() + SleepWatcher.ackBudget) { [self] in
                allow(token: token)
            }
        case kMessageSystemHasPoweredOn:
            emit(Event(name: "wake"))
        default:
            break
        }
    }

    private func rootPortValue() -> io_connect_t {
        lock.lock()
        defer { lock.unlock() }
        return rootPort
    }

    /// Calls IOAllowPowerChange once for the token, if it is still pending.
    @discardableResult
    private func allow(token: Int64) -> Bool {
        lock.lock()
        guard let argument = pending.removeValue(forKey: token) else {
            lock.unlock()
            return false
        }
        let port = rootPort
        lock.unlock()
        IOAllowPowerChange(port, argument)
        return true
    }

    /// The node's `sleep.ack`.
    func ack(token: Int64) throws {
        guard allow(token: token) else { throw BackendFailure.badRequest("unknown token") }
    }
}

/// Power-source changes as `ev:"power"` events.
final class PowerSourceWatcher {
    private let emit: (Event) -> Void

    init(emit: @escaping (Event) -> Void) {
        self.emit = emit
    }

    func start() {
        let thread = Thread { [self] in
            let refcon = Unmanaged.passUnretained(self).toOpaque()
            let callback: IOPowerSourceCallbackType = { context in
                guard let context = context else { return }
                let me = Unmanaged<PowerSourceWatcher>.fromOpaque(context).takeUnretainedValue()
                if let fields = try? Probes.power() { me.emit(Event(name: "power", fields: fields)) }
            }
            guard let source = IOPSNotificationCreateRunLoopSource(callback, refcon)?.takeRetainedValue() else { return }
            CFRunLoopAddSource(CFRunLoopGetCurrent(), source, .defaultMode)
            CFRunLoopRun()
        }
        thread.name = "asom-power-watcher"
        thread.start()
    }
}
#endif
