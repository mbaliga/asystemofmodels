import AsomDSSE
import AsomJSON

/// Run plans are data compiled into the core; a document records the plan id and the SHA-256 of the plan's JCS bytes
/// (benchmark.md section 5.2).
///
/// Only the `standard` plan is given in full by the spec (the JSON of 5.2). The table of 5.1 describes `quick`, `ci` and the
/// others in prose, which does not fix a JCS encoding, so this lane does not invent one: `jcsValue` is nil for them
/// (ERRATA E-30). The recorded `planSha256` of a document is checked for shape only (E-22).
public enum RunPlan {
    public static let standardId = "standard"

    /// The plan ids this lane holds as data.
    public static let compiled: [String] = [standardId]

    public static func jcsValue(_ id: String) -> JValue? {
        id == standardId ? standard : nil
    }

    public static func jcsBytes(_ id: String) throws -> [UInt8]? {
        guard let value = jcsValue(id) else { return nil }
        return try JCS.serialize(value)
    }

    /// base64url, no padding, of SHA-256 over the JCS bytes: the form of a document's `planSha256`.
    public static func planSha256(_ id: String) throws -> String? {
        guard let bytes = try jcsBytes(id) else { return nil }
        return Base64Strict.encodeURL(NodeIdentity.sha256(bytes))
    }

    private static func strings(_ items: [String]) -> JValue { .array(items.map { .string($0) }) }

    private static func obj(_ members: [(String, JValue)]) -> JValue {
        .object(members.map { JMember(name: $0.0, value: $0.1) })
    }

    /// benchmark.md 5.2, member for member.
    private static let standard: JValue = {
        let desktopTiers = obj([("auto", strings(["T2", "T3", "T4"]))])
        let mobileTiers = obj([("auto", strings(["T1", "T2"])), ("optIn", strings(["T3"]))])
        return obj([
            ("plan", .string("standard")), ("planVersion", .int(1)),
            ("reps", .int(5)), ("depthReps", .int(3)), ("warmupReps", .int(1)), ("interRepMs", .int(500)),
            ("tiers", obj([
                ("phone", mobileTiers), ("tablet", mobileTiers), ("handheld", desktopTiers),
                ("laptop", desktopTiers), ("desktop", desktopTiers), ("server", desktopTiers),
            ])),
            ("headlineTests", strings(["load", "pp512@d0", "tg128@d0", "tg128@d2048", "nll1024"])),
            ("headlineTestsDesktop", strings(["pp2048@d0"])),
            ("otherTests", strings(["load", "pp512@d0", "tg128@d0", "nll1024"])),
            ("sustain", obj([
                ("capMsMobile", .int(600_000)), ("capMsDesktop", .int(900_000)), ("windowMs", .int(15_000)),
                ("chunkTokens", .int(256)), ("promptTokens", .int(64)),
            ])),
            ("coolDown", obj([
                ("pollMs", .int(5000)), ("maxWaitMsMobile", .int(180_000)), ("maxWaitMsDesktop", .int(120_000)),
                ("maxWaitMsBeforeSustain", .int(600_000)),
            ])),
            ("charger", obj([
                ("phone", .string("required")), ("tablet", .string("required")), ("handheld", .string("required")),
                ("laptop", .string("required")),
            ])),
            ("minBatteryPermille", .int(500)),
            ("wallCapMs", obj([
                ("phone", .int(2_100_000)), ("tablet", .int(2_100_000)), ("handheld", .int(2_400_000)),
                ("laptop", .int(2_700_000)), ("desktop", .int(2_700_000)), ("server", .int(2_700_000)),
            ])),
        ])
    }()
}
