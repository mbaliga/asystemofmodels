package xyz.mdhv.asom.lab.proto.wire

import java.lang.reflect.Modifier

/**
 * Answers whether a piece of text is held anywhere in an object graph (fields, collections, arrays, byte arrays read as UTF-8). It is how the lab checks that a
 * received `ERROR` never stores a peer-authored `message`: a model with a field for it would hold the text and fail the probe (LAB_SPEC 7.2, design T15).
 */
object StoredTextProbe {
    private const val MAX_DEPTH = 6

    fun holds(root: Any?, needle: String): Boolean = walk(root, needle, 0, java.util.IdentityHashMap())

    private fun walk(o: Any?, needle: String, depth: Int, seen: java.util.IdentityHashMap<Any, Boolean>): Boolean {
        if (o == null || depth > MAX_DEPTH) return false
        when (o) {
            is CharSequence -> return o.contains(needle)
            is ByteArray -> return String(o, Charsets.UTF_8).contains(needle)
            is Enum<*> -> return o.name.contains(needle)
            is Number, is Boolean, is Char -> return false
            is Map<*, *> -> return o.entries.any { walk(it.key, needle, depth + 1, seen) || walk(it.value, needle, depth + 1, seen) }
            is Iterable<*> -> return o.any { walk(it, needle, depth + 1, seen) }
            is Array<*> -> return o.any { walk(it, needle, depth + 1, seen) }
        }
        if (seen.put(o, true) != null) return false
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                if (walk(f.get(o), needle, depth + 1, seen)) return true
            }
            c = c.superclass
        }
        return false
    }
}
