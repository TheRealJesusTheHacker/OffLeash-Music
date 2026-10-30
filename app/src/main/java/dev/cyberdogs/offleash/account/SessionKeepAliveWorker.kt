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
 * Periodic session keep-alive: asks the session manager to validate
 * the stored session. Validation is what keeps the session fresh
 * (fresh cookie rotation is persisted by the transport callback) and
 * what discovers — early, with a plain explanation — that a session
 * has ended, instead of the user finding out mid-playlist.
 *
 * Legacy-generation semantics (H4, 0.7.2 sweep): the cookie session
 * this worker serves is a fallback ONLY until a Google session
 * exists. While one is stored, this worker validates NOTHING — the
 * legacy session is dormant, and running validation on it every
 * 12 hours would burn battery and network keeping a superseded
 * credential fresh (and persisting its rotations) behind the
 * Google session's back. See [legacySessionValidationShouldRun]
 * and the semantics note on the session manager.
 *
 * Every health outcome is handled inside the manager, so this
 * always reports success.
 */
class SessionKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            val container = (applicationContext as? OffleashApp)?.container
            if (container != null &&
                dev.cyberdogs.offleash.data.legacySessionValidationShouldRun(
                    legacySessionPresent = container.sessionManager.currentAuthSession() != null,
                    googleSessionPresent = container.googleSessionManager.hasStoredSession(),
                    // A Google sign-in that EXISTED and was wiped on
                    // its own leaves no stored Google session — the
                    // marker is what keeps this zombie dormant then
                    // too (bug sweep round 2).
                    googleSignInSeen = container.googleSignInSeenMarker.seen(),
                )
            ) {
                container.sessionManager.validateNow()
            }
        } catch (e: Exception) {
            // Health handling lives in the manager; a worker-level
            // failure simply means the next run tries again.
        }
        return Result.success()
    }
}

/** Enqueues the keep-alive schedule (once — KEEP policy). */
object SessionKeepAlive {

    private const val WORK_NAME = "offleash-session-keepalive"

    fun enqueue(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<SessionKeepAliveWorker>(12, TimeUnit.HOURS)
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
            // Best effort: if WorkManager is unavailable in this
            // process, the app-start validation still runs, and the
            // next cold start will try to enqueue again.
        }
    }
}
