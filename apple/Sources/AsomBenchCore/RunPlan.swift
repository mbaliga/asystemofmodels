import AsomDSSE
import AsomJSON

/// Run plans are data compiled into the core; a document records the plan id and the SHA-256 of the plan's JCS bytes
/// (benchmark.md section 5.2).
///
/// Design revision 4 (R4-B-06) gives the JSON of `standard`, `quick` and `ci` in 5.2 (`quick` and `ci` are the 5.1 table rows with the
/// `standard` value wherever the table is silent; PROVISIONAL). `sustained`, `battery` and `extended` are not built, so `jcsValue` is nil
/// for them and for every other id (ERRATA E-30, R4-B-06). The recorded `planSha256` of a document is checked for shape only (E-22).
public enum RunPlan {
    public static let standardId = "standard"
    public static let quickId = "quick"
    public static let ciId = "ci"

    /// The plan ids this lane holds as data.
    public static let compiled: [String] = [standardId, quickId, ciId]

    public static func jcsValue(_ id: String) -> JValue? {
        switch id {
        case standardId: return standard
        case quickId: return quick
        case ciId: return ci
        default: return nil
        }
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

    private static let allForms = ["phone", "tablet", "handheld", "laptop", "desktop", "server"]

    private static let coolDown: JValue = obj([
        ("pollMs", .int(5000)), ("maxWaitMsMobile", .int(180_000)), ("maxWaitMsDesktop", .int(120_000)), ("maxWaitMsBeforeSustain", .int(600_000)),
    ])

    /// benchmark.md 5.2 (r4): no sustained phase, no required charger, no wall cap.
    private static func shortPlan(id: String, tiers: JValue, headline: [String], minBattery: Int64) -> JValue {
        obj([
            ("plan", .string(id)), ("planVersion", .int(1)),
            ("reps", .int(3)), ("depthReps", .int(3)), ("warmupReps", .int(1)), ("interRepMs", .int(500)),
            ("tiers", tiers),
            ("headlineTests", strings(headline)), ("headlineTestsDesktop", strings([])), ("otherTests", strings([])),
            ("sustain", .null), ("coolDown", coolDown), ("charger", obj([])),
            ("minBatteryPermille", .int(minBattery)), ("wallCapMs", obj([])),
        ])
    }

    /// benchmark.md 5.2 (r4, R4-B-06): T1 if it fits, else T0 on a phone or tablet; T1 elsewhere.
    private static let quick: JValue = {
        let mobile = obj([("auto", strings(["T1"])), ("fallback", .string("T0"))])
        let other = obj([("auto", strings(["T1"]))])
        let tiers = obj(allForms.map { ($0, ($0 == "phone" || $0 == "tablet") ? mobile : other) })
        return shortPlan(id: "quick", tiers: tiers, headline: ["load", "pp512@d0", "tg128@d0", "nll1024"], minBattery: 300)
    }()

    /// benchmark.md 5.2 (r4, R4-B-06): T0 on every form.
    private static let ci: JValue = shortPlan(
        id: "ci", tiers: obj(allForms.map { ($0, obj([("auto", strings(["T0"]))])) }), headline: ["pp512@d0", "tg128@d0", "nll1024"], minBattery: 0
    )

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
