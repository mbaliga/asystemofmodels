/// Overflow-checked 64-bit arithmetic (LAB_SPEC.md section 4.6, "Arithmetic"). Every multiplication and addition in
/// `derive()`, the projection and `consistency()` goes through here; an overflow is a typed failure, never a wrap.
public struct BenchArithmeticError: Error, Equatable, CustomStringConvertible {
    public let operation: String
    public var description: String { "arithmetic overflow or undefined result in \(operation)" }
}

public enum Checked {
    public static func mul(_ a: Int64, _ b: Int64) throws -> Int64 {
        let (r, overflow) = a.multipliedReportingOverflow(by: b)
        if overflow { throw BenchArithmeticError(operation: "\(a) * \(b)") }
        return r
    }

    public static func add(_ a: Int64, _ b: Int64) throws -> Int64 {
        let (r, overflow) = a.addingReportingOverflow(b)
        if overflow { throw BenchArithmeticError(operation: "\(a) + \(b)") }
        return r
    }

    public static func sub(_ a: Int64, _ b: Int64) throws -> Int64 {
        let (r, overflow) = a.subtractingReportingOverflow(b)
        if overflow { throw BenchArithmeticError(operation: "\(a) - \(b)") }
        return r
    }

    /// Floor division. Every quantity in M04 is non-negative, so truncation and floor agree; a negative operand is
    /// treated as a defect rather than silently rounded toward zero.
    public static func div(_ a: Int64, _ b: Int64) throws -> Int64 {
        if b == 0 || a < 0 || b < 0 { throw BenchArithmeticError(operation: "\(a) / \(b)") }
        return a / b
    }

    /// a * b / c, with the product checked.
    public static func mulDiv(_ a: Int64, _ b: Int64, _ c: Int64) throws -> Int64 {
        try div(try mul(a, b), c)
    }
}
