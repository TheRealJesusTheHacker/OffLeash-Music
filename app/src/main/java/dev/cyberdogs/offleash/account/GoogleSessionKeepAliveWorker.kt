package dev.cyberdogs.offleash.account

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.cyberdogs.offleash.OffleashApp
import java.util.concurrent.TimeUnit

/**
 * Periodic keep-alive for the Google sign-in (0.7.0): asks the
 * Google session manager to re-read the account identity, which —
 * through the manager's bearer access — also refreshes the access
 * token when it is near expiry and discovers a revoked grant early,
 * with a plain explanation, instead of mid-playlist. Every outcome
 * is handled inside the manager; this always reports success.
 */
class GoogleSessionKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            val app = applicationContext as? OffleashApp
            app?.container?.googleSessionManager?.refreshIdentity()
        } catch (e: Exception) {
            // Health handling lives in the manager; a worker-level
            // failure simply means the next run tries again.
        }
        return Result.success()
    }
}

/** Enqueues the Google keep-alive schedule (once — KEEP policy). */
object GoogleSessionKeepAlive {

    private const val WORK_NAME = "offleash-google-session-keepalive"

    fun enqueue(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<GoogleSessionKeepAliveWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } catch (e: Exception) {
            // Best effort: app-start identity refresh still runs, and
            // the next cold start tries to enqueue again.
        }
    }
}
