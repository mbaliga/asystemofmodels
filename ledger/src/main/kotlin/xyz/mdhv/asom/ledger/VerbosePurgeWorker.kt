package xyz.mdhv.asom.ledger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 24h TTL purge for verbose-mode rows (brief §9). Verbose mode is opt-in and
 * NEVER the default; this worker just enforces the retention ceiling while
 * it's on — it runs regardless (a no-op when the table is empty) so a mode
 * toggled off still cleans up what it wrote.
 */
class VerbosePurgeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - TTL_MS
        LedgerDatabase.open(applicationContext).dao().purgeVerboseOlderThan(cutoff)
        return Result.success()
    }

    companion object {
        const val TTL_MS: Long = 24L * 60 * 60 * 1000

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<VerbosePurgeWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("verbose-purge", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("verbose-purge")
        }
    }
}
