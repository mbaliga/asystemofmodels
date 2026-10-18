package xyz.mdhv.asom.lab.bench

/** An overflow, a negative operand or a division by a non-positive number. The verifier maps it to `INCONSISTENT` (LAB_SPEC 4.6 "Arithmetic"). */
class BenchArithmeticException(message: String) : ArithmeticException(message)

/**
 * Checked integer arithmetic for M04, the projection and the consistency rules. Nothing wraps and nothing is
 * floating point: an intermediate that does not fit a signed 64-bit integer, or a result beyond 2^53 - 1, is a typed failure.
 */
object Checked {
    const val MAX_SAFE: Long = 9_007_199_254_740_991L
    const val NANO: Long = 1_000_000_000L
    const val MICRO: Long = 1_000_000L
    const val MILLI: Long = 1_000L

    fun mul(a: Long, b: Long): Long = try {
        Math.multiplyExact(a, b)
    } catch (e: ArithmeticException) {
        throw BenchArithmeticException("multiplication overflow: $a * $b")
    }

    fun add(a: Long, b: Long): Long = try {
        Math.addExact(a, b)
    } catch (e: ArithmeticException) {
        throw BenchArithmeticException("addition overflow: $a + $b")
    }

    fun sub(a: Long, b: Long): Long = try {
        Math.subtractExact(a, b)
    } catch (e: ArithmeticException) {
        throw BenchArithmeticException("subtraction overflow: $a - $b")
    }

    /** Floor division on the non-negative domain; the divisor must be positive. */
    fun div(a: Long, b: Long): Long {
        if (b <= 0L) throw BenchArithmeticException("division by $b")
        if (a < 0L) throw BenchArithmeticException("negative dividend $a")
        return a / b
    }

    /** ceil(a / b) on the non-negative domain. */
    fun ceilDiv(a: Long, b: Long): Long {
        if (b <= 0L) throw BenchArithmeticException("division by $b")
        if (a < 0L) throw BenchArithmeticException("negative dividend $a")
        return if (a == 0L) 0L else add(sub(a, 1L) / b, 1L)
    }

    fun u53(v: Long): Long {
        if (v < 0L || v > MAX_SAFE) throw BenchArithmeticException("$v is outside 0..2^53-1")
        return v
    }

    /** `tokens * 10^9 / micros`: milli-tokens per second, floored. */
    fun rate(tokens: Long, micros: Long): Long = u53(div(mul(tokens, NANO), micros))

    /** `num * 1000 / den`: a permille, floored. */
    fun permille(num: Long, den: Long): Long = u53(div(mul(num, MILLI), den))

    fun absDiff(a: Long, b: Long): Long = if (a >= b) sub(a, b) else sub(b, a)
}
