package xyz.mdhv.asom

import android.content.Context

/**
 * Local-only toggles (no ledger row — these aren't egress). Boot-start
 * defaults OFF (brief P8): nothing runs unless the user starts it.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("asom-settings", Context.MODE_PRIVATE)

    var bootStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOOT_START, false) // default OFF
        set(value) = prefs.edit().putBoolean(KEY_BOOT_START, value).apply()

    /** Verbose mode (§9): opt-in, stores request/response bodies with a 24h TTL. */
    var verboseModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE, false) // default OFF
        set(value) = prefs.edit().putBoolean(KEY_VERBOSE, value).apply()

    companion object {
        private const val KEY_BOOT_START = "boot_start_enabled"
        private const val KEY_VERBOSE = "verbose_mode_enabled"
    }
}
