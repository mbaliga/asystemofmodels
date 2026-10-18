import AsomJSON

/// The wire signature is raw r||s, 64 octets, big-endian, leading zeros kept (C2). DER exists only for platforms
/// whose APIs return it, and is never accepted on the wire (M03-102).
public enum SignatureCodec {
    public static let rawLength = 64

    /// Strict DER: SEQUENCE { INTEGER r, INTEGER s }, minimal lengths, minimal positive integers, no trailing bytes.
    public static func derToRaw(_ der: [UInt8]) -> Result<[UInt8], RejectCode> {
        let bad = Result<[UInt8], RejectCode>.failure(.signatureEncoding)
        guard der.count >= 8, der[0] == 0x30, der[1] < 0x80, Int(der[1]) == der.count - 2 else { return bad }
        var p = 2
        var parts: [[UInt8]] = []
        for _ in 0..<2 {
            guard p + 2 <= der.count, der[p] == 0x02, der[p + 1] >= 1, der[p + 1] < 0x80 else { return bad }
            let length = Int(der[p + 1])
            guard p + 2 + length <= der.count else { return bad }
            var value = Array(der[(p + 2)..<(p + 2 + length)])
            guard value[0] & 0x80 == 0 else { return bad }
            if value.count > 1, value[0] == 0x00 {
                guard value[1] & 0x80 != 0 else { return bad }
                value.removeFirst()
            }
            guard value.count <= 32 else { return bad }
            parts.append([UInt8](repeating: 0, count: 32 - value.count) + value)
            p += 2 + length
        }
        guard p == der.count else { return bad }
        return .success(parts[0] + parts[1])
    }

    public static func rawToDer(_ raw: [UInt8]) -> Result<[UInt8], RejectCode> {
        guard raw.count == rawLength else { return .failure(.signatureEncoding) }
        func integer(_ scalar: ArraySlice<UInt8>) -> [UInt8] {
            var v = Array(scalar.drop { $0 == 0 })
            if v.isEmpty { v = [0] }
            if v[0] & 0x80 != 0 { v.insert(0, at: 0) }
            return [0x02, UInt8(v.count)] + v
        }
        let body = integer(raw[0..<32]) + integer(raw[32..<64])
        return .success([0x30, UInt8(body.count)] + body)
    }

    /// True when s <= n/2.
    public static func isLowS(_ raw: [UInt8]) -> Bool {
        precondition(raw.count == rawLength)
        let s = U256(bigEndian: Array(raw[32..<64]))
        return !(P256Curve.n.halved < s)
    }

    /// Producer rule (C2): if s > n/2 then s = n - s; r is unchanged. Verifiers still accept high-S.
    public static func normaliseLowS(_ raw: [UInt8]) -> [UInt8] {
        precondition(raw.count == rawLength)
        return isLowS(raw) ? raw : flipS(raw)
    }

    /// (r, s) -> (r, n - s). The high-S twin of a low-S signature, and the low-S twin of a high-S one.
    public static func flipS(_ raw: [UInt8]) -> [UInt8] {
        precondition(raw.count == rawLength)
        let s = U256(bigEndian: Array(raw[32..<64]))
        return Array(raw[0..<32]) + P256Curve.n.subtracting(s).bigEndianBytes
    }
}
