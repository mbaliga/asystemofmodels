package xyz.mdhv.asom.lab.router

/**
 * Integer arithmetic for the estimator (LAB_SPEC 6.4): every operand is a non-negative count, division is floor unless [ceilDiv], and every result
 * saturates at 2^53 - 1 so that nothing wraps (law RL20). Nothing here allocates or reads a clock.
 */
object Sat {
    const val MAX: Long = (1L shl 53) - 1

    private fun cl(a: Long): Long = if (a < 0) 0 else if (a > MAX) MAX else a

    fun add(a: Long, b: Long): Long = cl(cl(a) + cl(b))

    fun add(vararg xs: Long): Long = xs.fold(0L) { acc, x -> add(acc, x) }

    fun mul(a: Long, b: Long): Long {
        val x = cl(a)
        val y = cl(b)
        return if (x == 0L || y == 0L) 0 else if (x > MAX / y) MAX else cl(x * y)
    }

    fun floorDiv(a: Long, b: Long): Long {
        require(b > 0) { "a divisor is positive" }
        return cl(a) / b
    }

    fun ceilDiv(a: Long, b: Long): Long {
        require(b > 0) { "a divisor is positive" }
        val x = cl(a)
        return x / b + (if (x % b == 0L) 0 else 1)
    }

    /** `max(0, a - b)`. */
    fun sub0(a: Long, b: Long): Long = if (a > b) cl(a - b) else 0
}
