package dev.cyberdogs.offleash.account

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.cyberdogs.offleash.OffleashApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Auto-download of Liked songs (parity step 2, Lane F).
 *
 * Preference-gated: this worker does nothing at all unless
 * `auto_download_liked` is ON (default off) and a session is signed
 * in. When both hold, it loads the signed-in Liked list through the
 * existing client (`likedSongs`, the `VLLM` playlist) and hands the
 * tracks to the download manager's `enqueueMissing`, which skips
 * anything already downloaded or queued. Downloads themselves then
 * run one at a time inside the manager — the worker only enqueues,
 * so WorkManager never fans out parallel chunk streams.
 *
 * A failed liked-list load is [Result.retry]: WorkManager backs off
 * and tries again on a connected network, instead of the app busy-
 * looping or silently giving up. The worker is one-time (not
 * periodic): it is enqueued at app start and again whenever the
 * Library is opened signed-in with the preference on, which is when
 * a freshly loaded liked list exists to act on.
 */
class AutoDownloadLikedWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OffleashApp ?: return Result.success()
        val container = app.container
        if (!container.appPreferences.autoDownloadLiked) return Result.success()
        val liked = try {
            val page = withContext(Dispatchers.IO) {
                // The token road first (0.7.0), the cookie session
                // as the fallback — the same order as every surface.
                val bearer = container.currentBearerToken()
                when {
                    bearer != null -> container.client.likedSongsOAuth(bearer)
                    else -> container.sessionManager.currentAuthSession()
                        ?.let { container.client.likedSongs(it) }
                }
            }
            page ?: return Result.success()
        } catch (e: Exception) {
            // A network-shaped failure gets WorkManager's backoff; the
            // manager's health handling owns anything session-shaped.
            return Result.retry()
        }
        container.downloadManager.enqueueMissing(liked.tracks)
        return Result.success()
    }
}

/** Enqueues the auto-download check (once at a time — KEEP policy). */
object AutoDownloadLiked {

    private const val WORK_NAME = "offleash-auto-download-liked"

    fun enqueue(context: Context) {
        try {
            val request = OneTimeWorkRequestBuilder<AutoDownloadLikedWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        } catch (e: Exception) {
            // Best effort (SessionKeepAlive's rule): if WorkManager is
            // unavailable in this process, the next cold start or
            // Library visit tries to enqueue again.
        }
    }
}
