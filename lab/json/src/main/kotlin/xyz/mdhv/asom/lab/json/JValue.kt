package xyz.mdhv.asom.lab.json

/** The largest magnitude a [JInt] may hold: 2^53 - 1, the last integer every IEEE-754 double represents exactly. */
const val MAX_SAFE_INT: Long = 9_007_199_254_740_991L

/**
 * The value model of the asom JSON profile (LAB_SPEC 4.2). There is no floating-point member on purpose, and a value
 * that could not be produced by [StrictJson.parse] cannot be built: strings hold no lone surrogate, integers stay
 * within +-[MAX_SAFE_INT], and an object never repeats a member name.
 */
sealed interface JValue

class JObject(members: List<Pair<String, JValue>>) : JValue {
    /** Members in the order given (the input order when parsed). Equality ignores the order. */
    val members: List<Pair<String, JValue>> = members.toList()
    private val byName: Map<String, JValue>

    init {
        val map = LinkedHashMap<String, JValue>(this.members.size * 2)
        for ((name, value) in this.members) {
            requireWellFormed(name)
            require(map.put(name, value) == null) { "duplicate member name" }
        }
        byName = map
    }

    operator fun get(name: String): JValue? = byName[name]

    override fun equals(other: Any?): Boolean = other is JObject && byName == other.byName

    override fun hashCode(): Int = byName.hashCode()

    override fun toString(): String = members.joinToString(prefix = "JObject{", postfix = "}") { "${it.first}=${it.second}" }
}

data class JArray(val items: List<JValue>) : JValue

data class JString(val value: String) : JValue {
    init {
        requireWellFormed(value)
    }
}

data class JInt(val value: Long) : JValue {
    init {
        require(value in -MAX_SAFE_INT..MAX_SAFE_INT) { "integer outside +-(2^53-1)" }
    }
}

data class JBool(val value: Boolean) : JValue

data object JNull : JValue

private fun requireWellFormed(s: String) {
    var k = 0
    while (k < s.length) {
        val c = s[k]
        if (Character.isHighSurrogate(c)) {
            require(k + 1 < s.length && Character.isLowSurrogate(s[k + 1])) { "lone surrogate in string" }
            k += 2
        } else {
            require(!Character.isLowSurrogate(c)) { "lone surrogate in string" }
            k++
        }
    }
}
