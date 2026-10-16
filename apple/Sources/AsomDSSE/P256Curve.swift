/// 256-bit unsigned integers, just enough to check that a point lies on secp256r1 (LAB_SPEC.md section 4.5)
/// and to compare signature scalars with the group order. Not constant time: it only ever handles public data.
struct U256: Equatable {
    /// Little-endian 64-bit limbs.
    var limbs: [UInt64]

    static let zero = U256(limbs: [0, 0, 0, 0])

    init(limbs: [UInt64]) { self.limbs = limbs }

    init(bigEndian bytes: [UInt8]) {
        precondition(bytes.count == 32)
        var l = [UInt64](repeating: 0, count: 4)
        for k in 0..<4 {
            var v: UInt64 = 0
            for j in 0..<8 { v = v << 8 | UInt64(bytes[(3 - k) * 8 + j]) }
            l[k] = v
        }
        limbs = l
    }

    init(hex: String) {
        var bytes: [UInt8] = []
        var it = hex.utf8.makeIterator()
        func nibble(_ c: UInt8) -> UInt8 { c <= 0x39 ? c - 0x30 : (c | 0x20) - 0x61 + 10 }
        while let hi = it.next(), let lo = it.next() { bytes.append(nibble(hi) << 4 | nibble(lo)) }
        self.init(bigEndian: bytes)
    }

    var bigEndianBytes: [UInt8] {
        var out: [UInt8] = []
        for k in (0..<4).reversed() { for j in (0..<8).reversed() { out.append(UInt8(truncatingIfNeeded: limbs[k] >> UInt64(j * 8))) } }
        return out
    }

    var isZero: Bool { limbs.allSatisfy { $0 == 0 } }

    static func < (a: U256, b: U256) -> Bool {
        for k in (0..<4).reversed() where a.limbs[k] != b.limbs[k] { return a.limbs[k] < b.limbs[k] }
        return false
    }

    /// Returns the sum modulo 2^256 and the carry out.
    func adding(_ other: U256) -> (U256, Bool) {
        var out = [UInt64](repeating: 0, count: 4)
        var carry = false
        for k in 0..<4 {
            let (s1, o1) = limbs[k].addingReportingOverflow(other.limbs[k])
            let (s2, o2) = s1.addingReportingOverflow(carry ? 1 : 0)
            out[k] = s2
            carry = o1 || o2
        }
        return (U256(limbs: out), carry)
    }

    /// Wrapping subtraction modulo 2^256.
    func subtracting(_ other: U256) -> U256 {
        var out = [UInt64](repeating: 0, count: 4)
        var borrow = false
        for k in 0..<4 {
            let (d1, o1) = limbs[k].subtractingReportingOverflow(other.limbs[k])
            let (d2, o2) = d1.subtractingReportingOverflow(borrow ? 1 : 0)
            out[k] = d2
            borrow = o1 || o2
        }
        return U256(limbs: out)
    }

    func bit(_ index: Int) -> Bool { limbs[index / 64] >> UInt64(index % 64) & 1 == 1 }

    /// Floor of half the value.
    var halved: U256 {
        var out = limbs
        for k in 0..<4 { out[k] = limbs[k] >> 1 | (k < 3 ? limbs[k + 1] << 63 : 0) }
        return U256(limbs: out)
    }
}

enum P256Curve {
    static let p = U256(hex: "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
    static let b = U256(hex: "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b")
    /// The group order n.
    static let n = U256(hex: "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")

    static func addMod(_ a: U256, _ c: U256) -> U256 {
        let (sum, carry) = a.adding(c)
        if carry || !(sum < p) { return sum.subtracting(p) }
        return sum
    }

    static func subMod(_ a: U256, _ c: U256) -> U256 {
        if c < a || c == a { return a.subtracting(c) }
        return a.adding(p.subtracting(c)).0
    }

    /// Double-and-add multiplication; slow and obviously correct.
    static func mulMod(_ a: U256, _ c: U256) -> U256 {
        var result = U256.zero
        for index in (0..<256).reversed() {
            result = addMod(result, result)
            if c.bit(index) { result = addMod(result, a) }
        }
        return result
    }

    /// y^2 == x^3 - 3x + b (mod p), with 0 <= x, y < p.
    static func isOnCurve(x xBytes: [UInt8], y yBytes: [UInt8]) -> Bool {
        let x = U256(bigEndian: xBytes)
        let y = U256(bigEndian: yBytes)
        guard x < p, y < p else { return false }
        let lhs = mulMod(y, y)
        let x3 = mulMod(mulMod(x, x), x)
        let threeX = addMod(addMod(x, x), x)
        let rhs = addMod(subMod(x3, threeX), b)
        return lhs == rhs
    }
}
