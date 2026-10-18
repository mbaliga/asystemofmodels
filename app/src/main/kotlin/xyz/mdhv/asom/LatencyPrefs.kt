package xyz.mdhv.asom

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.mdhv.asom.routing.LatencyStore

/**
 * Durable backing for the §7 latency EWMA ("persisted per provider"). Without
 * it every reboot or FGS kill degrades `fastest`/`auto` into `cheapest` until
 * the tracker warms up again.
 *
 * [save] is called from the request path, so the write is handed to [scope]
 * rather than performed inline.
 */
class SharedPrefsLatencyStore(
    context: Context,
    private val scope: CoroutineScope,
) : LatencyStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Map<String, Double> = prefs.all.mapNotNull { (providerId, stored) ->
        val ewma = (stored as? String)?.toDoubleOrNull()
        if (ewma != null && ewma.isFinite()) providerId to ewma else null
    }.toMap()

    override fun save(values: Map<String, Double>) {
        scope.launch {
            val editor = prefs.edit().clear()
            values.forEach { (providerId, ewma) -> editor.putString(providerId, ewma.toString()) }
            editor.apply()
        }
    }

    private companion object {
        const val PREFS = "asom-latency"
    }
}
