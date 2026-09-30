package xyz.mdhv.asom.desktop.linux.host

/** `/etc/os-release` (freedesktop format: `KEY=value`, values optionally in single or double quotes). */
class OsRelease(private val fields: Map<String, String>) {
    val id: String? get() = fields["ID"]

    /** SteamOS reports `ID=steamos`; only USER and FOREGROUND are offered there (no root-managed install survives updates). */
    val isSteamOS: Boolean get() = id == "steamos"

    operator fun get(key: String): String? = fields[key]

    companion object {
        fun parse(text: String?): OsRelease {
            val out = LinkedHashMap<String, String>()
            for (raw in (text ?: "").lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                var v = line.substring(eq + 1).trim()
                if (v.length >= 2 && (v.first() == '"' && v.last() == '"' || v.first() == '\'' && v.last() == '\'')) {
                    v = v.substring(1, v.length - 1)
                }
                out[key] = v
            }
            return OsRelease(out)
        }
    }
}
