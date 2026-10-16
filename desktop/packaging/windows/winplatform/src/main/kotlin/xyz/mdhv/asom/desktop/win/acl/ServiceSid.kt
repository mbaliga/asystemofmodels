package xyz.mdhv.asom.desktop.win.acl

import java.security.MessageDigest

/**
 * The per-service SID Windows derives for a service name: `S-1-5-80-` followed by the five little-endian DWORDs of the
 * SHA-1 of the UPPER-CASED name in UTF-16LE (what `sc.exe showsid <name>` prints). It lets a key or directory DACL name
 * `NT SERVICE\asom` before the service exists, which is when the installer writes those DACLs. Pure and deterministic.
 */
object ServiceSid {
    fun of(serviceName: String): String {
        require(serviceName.isNotEmpty() && serviceName.none { it == '\\' || it == '/' || it.code < 0x20 }) { "invalid service name" }
        val digest = MessageDigest.getInstance("SHA-1").digest(serviceName.uppercase().toByteArray(Charsets.UTF_16LE))
        val parts = (0 until 5).map { i ->
            val o = i * 4
            (digest[o].toLong() and 0xff) or ((digest[o + 1].toLong() and 0xff) shl 8) or
                ((digest[o + 2].toLong() and 0xff) shl 16) or ((digest[o + 3].toLong() and 0xff) shl 24)
        }
        return "S-1-5-80-" + parts.joinToString("-")
    }
}
