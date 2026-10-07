/// Runtime ceilings (benchmark.md section 11.3). A soft ceiling ends the sustained phase 120 s after it was first crossed; a hard
/// ceiling aborts at once. The result names the end reason of 6.4.
public enum Ceiling: Sendable, Equatable {
    case none
    case soft(String)
    case hard(String)

    public var text: String {
        switch self {
        case .none: return "none"
        case let .soft(reason): return "soft \(reason)"
        case let .hard(reason): return "hard \(reason)"
        }
    }
}

public struct CeilingInputs: Sendable, Equatable {
    public var platform: String
    public var form: String
    /// Thermal code 0 to 4 (benchmark.md 6.1; Apple states map 0 nominal, 1 fair, 3 serious, 4 critical).
    public var thermalCode: Int64
    public var headroomPermille: Int64?
    public var batteryTempDeciC: Int64?
    public var powerSource: String
    public var batteryLevelPermille: Int64?
    public var lowPowerMode: Bool
    public var gpuBusyHeldMs: Int64

    public init(
        platform: String, form: String, thermalCode: Int64, headroomPermille: Int64? = nil, batteryTempDeciC: Int64? = nil,
        powerSource: String, batteryLevelPermille: Int64? = nil, lowPowerMode: Bool = false, gpuBusyHeldMs: Int64 = 0
    ) {
        self.platform = platform
        self.form = form
        self.thermalCode = thermalCode
        self.headroomPermille = headroomPermille
        self.batteryTempDeciC = batteryTempDeciC
        self.powerSource = powerSource
        self.batteryLevelPermille = batteryLevelPermille
        self.lowPowerMode = lowPowerMode
        self.gpuBusyHeldMs = gpuBusyHeldMs
    }
}

public struct UnknownCeilingPlatform: Error, Equatable, Sendable, CustomStringConvertible {
    public let platform: String
    public var description: String { "benchmark.md 11.3 has no ceilings for platform \(platform)" }
}

public enum Ceilings {
    public static let androidSoftHeadroomPermille: Int64 = 950
    public static let androidSoftBatteryDeciC: Int64 = 420
    public static let androidHardBatteryDeciC: Int64 = 440
    public static let linuxHardBatteryDeciC: Int64 = 450
    public static let hardBatteryLevelPermille: Int64 = 200
    public static let gpuBusyHardMs: Int64 = 10_000

    /// The strongest ceiling that applies: hard before soft; among hard ones, thermal first, then battery, then the rest (the spec
    /// does not order simultaneous ceilings, ERRATA E-32).
    public static func evaluate(_ i: CeilingInputs) throws -> Ceiling {
        switch i.platform {
        case "android":
            if i.thermalCode >= 3 { return .hard("THERMAL_HARD") }
            if let t = i.batteryTempDeciC, t >= androidHardBatteryDeciC { return .hard("BATTERY_TEMP") }
            if let level = i.batteryLevelPermille, level < hardBatteryLevelPermille { return .hard("BATTERY_TEMP") }
            if let h = i.headroomPermille, h >= androidSoftHeadroomPermille { return .soft("THERMAL_SOFT") }
            if let t = i.batteryTempDeciC, t >= androidSoftBatteryDeciC { return .soft("THERMAL_SOFT") }
            return .none
        case "ios":
            if i.thermalCode >= 3 || i.lowPowerMode { return .hard("THERMAL_HARD") }
            if let level = i.batteryLevelPermille, level < hardBatteryLevelPermille { return .hard("BATTERY_TEMP") }
            return .none
        case "macos":
            if i.thermalCode >= 4 { return .hard("THERMAL_HARD") }
            if i.form == "laptop", i.powerSource != "ac" { return .hard("CHARGER_REMOVED") }
            if i.thermalCode >= 3 { return .soft("THERMAL_SOFT") }
            return .none
        case "linux":
            if i.thermalCode >= 4 { return .hard("THERMAL_HARD") }
            if let t = i.batteryTempDeciC, t >= linuxHardBatteryDeciC { return .hard("BATTERY_TEMP") }
            if i.form == "handheld", i.gpuBusyHeldMs >= gpuBusyHardMs { return .hard("DEVICE_BUSY") }
            if i.thermalCode >= 3 { return .soft("THERMAL_SOFT") }
            return .none
        default:
            throw UnknownCeilingPlatform(platform: i.platform)
        }
    }
}
