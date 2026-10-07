package xyz.mdhv.asom.lab.conformance

/**
 * The lab's reference SSE parser (LAB_SPEC 3.8). Rules: `\n`, `\r\n` and `\r` end lines; a line starting with
 * `:` is a comment; `data:` lines of one event are joined with `\n`; an event ends at a blank line; `data: [DONE]`
 * ends the stream; a final event without a terminating blank line is dropped and reported as [Result.truncated].
 */
object Sse {
    data class Result(val events: List<String>, val done: Boolean, val truncated: Boolean)

    fun parse(bytes: ByteArray): Result {
        val text = String(bytes, Charsets.UTF_8)
        val events = ArrayList<String>()
        val data = ArrayList<String>()
        var hasData = false
        val line = StringBuilder()
        var i = 0
        var done = false

        fun dispatchLine(l: String): Boolean {
            if (l.isEmpty()) {
                if (hasData) {
                    val payload = data.joinToString("\n")
                    data.clear()
                    hasData = false
                    if (payload == "[DONE]") return true
                    events += payload
                }
                return false
            }
            if (l.startsWith(":")) return false
            val colon = l.indexOf(':')
            val field = if (colon < 0) l else l.substring(0, colon)
            var value = if (colon < 0) "" else l.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            if (field == "data") {
                data += value
                hasData = true
            }
            return false
        }

        while (i < text.length && !done) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                done = dispatchLine(line.toString())
                line.setLength(0)
            } else {
                line.append(c)
            }
            i++
        }
        val partialData = !done && line.isNotEmpty() && !line.startsWith(":") &&
            (line.startsWith("data") || hasData)
        val truncated = !done && (hasData || partialData)
        return Result(events, done, truncated)
    }
}
