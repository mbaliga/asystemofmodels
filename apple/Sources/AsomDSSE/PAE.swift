public enum PAE {
    /// PAE(type, body) = "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body, LEN in decimal bytes without leading zeros.
    public static func encode(payloadType: String, payload: [UInt8]) -> [UInt8] {
        let type = Array(payloadType.utf8)
        var out = Array("DSSEv1 ".utf8)
        out.append(contentsOf: Array(String(type.count).utf8))
        out.append(0x20)
        out.append(contentsOf: type)
        out.append(0x20)
        out.append(contentsOf: Array(String(payload.count).utf8))
        out.append(0x20)
        out.append(contentsOf: payload)
        return out
    }
}
