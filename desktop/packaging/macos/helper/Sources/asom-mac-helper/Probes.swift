// UNVERIFIED: written on Linux, never compiled, never run on a Mac. Whether presence and GPU counters track real use is S-M8
// (owner device); whether they are readable at all from a LaunchAgent is AM12 and AM13.
#if os(macOS)
import CoreGraphics
import CryptoKit
import Foundation
import HelperProtocol
import IOKit
import IOKit.ps
import Metal
import SystemConfiguration

enum Probes {
    // ---- power (IOPowerSources, ProcessInfo) ----------------------------------------------------------------------------

    static func power() throws -> Fields {
        guard let snapshot = IOPSCopyPowerSourcesInfo()?.takeRetainedValue() else {
            throw BackendFailure.unavailable("power sources unreadable")
        }
        guard let providingRef = IOPSGetProvidingPowerSourceType(snapshot) else {
            throw BackendFailure.unavailable("power source type unreadable")
        }
        let providing = providingRef.takeUnretainedValue() as String
        let source: String
        switch providing {
        case "AC Power": source = "ac"
        // A UPS is backup power, not mains: it counts as battery, which is the conservative reading.
        case "Battery Power", "UPS Power": source = "battery"
        default: throw BackendFailure.unavailable("power source type not recognised")
        }
        var permille: HValue = .null
        var charging = false
        if let list = IOPSCopyPowerSourcesList(snapshot)?.takeRetainedValue() as? [CFTypeRef] {
            for item in list {
                guard let d = IOPSGetPowerSourceDescription(snapshot, item)?.takeUnretainedValue() as? [String: Any] else { continue }
                guard (d["Type"] as? String) == "InternalBattery" else { continue }
                if let cur = d["Current Capacity"] as? Int, let max = d["Max Capacity"] as? Int, max > 0 {
                    permille = .int(Int64(Swift.min(1000, Swift.max(0, cur * 1000 / max))))
                }
                charging = (d["Is Charging"] as? Bool) ?? false
                break
            }
        }
        return Fields([
            (name: "source", value: .text(source)),
            (name: "charging", value: .bool(charging)),
            (name: "batteryPermille", value: permille),
            (name: "lowPower", value: .bool(ProcessInfo.processInfo.isLowPowerModeEnabled)),
        ])
    }

    // ---- thermal --------------------------------------------------------------------------------------------------------

    static func thermalState() throws -> String {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: return "nominal"
        case .fair: return "fair"
        case .serious: return "serious"
        case .critical: return "critical"
        @unknown default: throw BackendFailure.unavailable("thermal state not recognised")
        }
    }

    static func thermal() throws -> Fields {
        Fields([(name: "state", value: .text(try thermalState()))])
    }

    // ---- presence (HID idle time, screen lock, console user) -----------------------------------------------------------

    private static func registryProperties(_ entry: io_registry_entry_t) -> [String: Any]? {
        var props: Unmanaged<CFMutableDictionary>?
        guard IORegistryEntryCreateCFProperties(entry, &props, kCFAllocatorDefault, 0) == KERN_SUCCESS else { return nil }
        return props?.takeRetainedValue() as? [String: Any]
    }

    static func hidIdleMs() -> HValue {
        let entry = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOHIDSystem"))
        guard entry != 0 else { return .null }
        defer { IOObjectRelease(entry) }
        guard let props = registryProperties(entry), let ns = (props["HIDIdleTime"] as? NSNumber)?.uint64Value else { return .null }
        return .int(Int64(Swift.min(ns / 1_000_000, UInt64(ProtocolSpec.maxSafeInteger))))
    }

    static func screenLocked() -> HValue {
        // Nil when there is no window-server session (for example over SSH): unknown, which the node treats as present.
        guard let raw = CGSessionCopyCurrentDictionary(), let dict = raw as? [String: Any] else { return .null }
        return .bool((dict["CGSSessionScreenIsLocked"] as? Bool) ?? false)
    }

    static func consoleUserIsSelf() -> HValue {
        var uid: uid_t = 0
        var gid: gid_t = 0
        guard SCDynamicStoreCopyConsoleUser(nil, &uid, &gid) != nil else { return .null }
        return .bool(uid == getuid())
    }

    static func presence() throws -> Fields {
        Fields([
            (name: "hidIdleMs", value: hidIdleMs()),
            (name: "screenLocked", value: screenLocked()),
            (name: "consoleUserIsSelf", value: consoleUserIsSelf()),
        ])
    }

    // ---- GPU device utilisation (IOAccelerator PerformanceStatistics; no per-process figure exists without root, AM12) ----

    static func gpu() throws -> Fields {
        var iterator: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching("IOAccelerator"), &iterator) == KERN_SUCCESS else {
            throw BackendFailure.unavailable("no GPU accelerator service")
        }
        defer { IOObjectRelease(iterator) }
        var best: Int64?
        var entry = IOIteratorNext(iterator)
        while entry != 0 {
            if let props = registryProperties(entry),
               let stats = props["PerformanceStatistics"] as? [String: Any],
               let percent = (stats["Device Utilization %"] as? NSNumber)?.int64Value {
                best = Swift.max(best ?? 0, Swift.min(100, Swift.max(0, percent)) * 10)
            }
            IOObjectRelease(entry)
            entry = IOIteratorNext(iterator)
        }
        guard let permille = best else { throw BackendFailure.unavailable("no GPU utilisation counter") }
        return Fields([(name: "deviceUtilPermille", value: .int(permille))])
    }

    // ---- memory ----------------------------------------------------------------------------------------------------------

    static func mem() throws -> Fields {
        let physical = Int64(Swift.min(ProcessInfo.processInfo.physicalMemory, UInt64(ProtocolSpec.maxSafeInteger)))
        var gpu: HValue = .null
        if let device = MTLCreateSystemDefaultDevice() {
            gpu = .int(Int64(Swift.min(device.recommendedMaxWorkingSetSize, UInt64(ProtocolSpec.maxSafeInteger))))
        }
        return Fields([
            (name: "physicalBytes", value: .int(Swift.max(1, physical))),
            (name: "gpuRecommendedMaxWorkingSetBytes", value: gpu),
        ])
    }

    // ---- platform identity and paths -------------------------------------------------------------------------------------

    /// SHA-256(IOPlatformUUID). The raw UUID never leaves this process.
    static func platformUUID() throws -> Fields {
        let entry = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPlatformExpertDevice"))
        guard entry != 0 else { throw BackendFailure.unavailable("platform expert not found") }
        defer { IOObjectRelease(entry) }
        guard let uuid = IORegistryEntryCreateCFProperty(entry, "IOPlatformUUID" as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() as? String else {
            throw BackendFailure.unavailable("platform uuid unreadable")
        }
        return Fields([(name: "digest", value: .bytes([UInt8](SHA256.hash(data: Data(uuid.utf8)))))])
    }

    static func paths() throws -> Fields {
        let dir = NSTemporaryDirectory()
        guard dir.hasPrefix("/") else { throw BackendFailure.unavailable("temporary directory is not absolute") }
        return Fields([(name: "userTempDir", value: .text(dir))])
    }

    static func modelIdentifier() -> String {
        var size = 0
        guard sysctlbyname("hw.model", nil, &size, nil, 0) == 0, size > 1 else { return "unknown" }
        var buffer = [CChar](repeating: 0, count: size)
        guard sysctlbyname("hw.model", &buffer, &size, nil, 0) == 0 else { return "unknown" }
        let model = String(cString: buffer)
        let printable = !model.isEmpty && model.utf8.count <= 64 && model.utf8.allSatisfy { $0 >= 0x20 && $0 <= 0x7E }
        return printable ? model : "unknown"
    }
}
#endif
