package xyz.mdhv.asom.desktop.mac

import java.nio.file.Path

/**
 * The probe behind `macos/ControlSocketIT`'s check that the node's socket-path limit does not exceed what the JDK can bind, with
 * the bind itself injected so the verdict logic can be tested on any OS. The verdict has no escape for "nothing could be bound"
 * or "nothing was tried": both are exactly the cases where the node's limit would be too high without anyone noticing.
 */
object SocketLimitProbe {
    class Result(val longest: Int, val tried: List<Int>)

    /** Tries one path of every length in [lengths] under [base] and reports the longest one [bind] accepted (0 when none). */
    fun probe(base: Path, lengths: IntRange, bind: (Path) -> Boolean): Result {
        val tried = ArrayList<Int>()
        var longest = 0
        for (len in lengths) {
            val name = "s".repeat(maxOf(1, len - base.toString().length - 1))
            val path = base.resolve(name)
            if (path.toString().toByteArray(Charsets.UTF_8).size != len) continue
            tried += len
            if (bind(path)) longest = len
        }
        return Result(longest, tried)
    }

    /** Null when the probe shows the node's [limit] is bindable; otherwise why it shows nothing or shows too little. */
    fun problem(r: Result, lengths: IntRange, limit: Int): String? = when {
        r.tried.size != lengths.count() ->
            "only ${r.tried.size} of ${lengths.count()} path lengths could be tried (the base directory is too long), so the probe shows nothing"
        r.longest < limit -> "the longest path this host binds is ${r.longest} bytes, below the node's limit of $limit"
        else -> null
    }
}
