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
 * looping or silently giving up. The backoff is WorkManager's
 * default exponential schedule (30 seconds growing to a 5-hour
 * cap), so even a permanently failing load costs a handful of
 * wakeups a day, never a retry loop. The batch itself is bounded
 * inside the manager: each run enqueues at most
 * `MAX_AUTO_DOWNLOADS_PER_RUN` tracks, and a track whose last
 * attempt failed recently is not auto-retried until its cooldown
 * passes (a manual retry from the Library is never cooled down).
 *
 * Sign-out behaviour, decided: signing out does not delete or stop
 * anything here. Tracks already downloaded stay downloaded — they
 * are local files, not account data — the preference keeps the
 * value the owner set, and this worker simply does nothing while
 * signed out (no session, no liked list, [Result.success]). If the
 * owner signs back in, the next enqueue acts on the preference as
 * it stands.
 *
 * The worker is one-time (not periodic): it is enqueued at app
 * start and again whenever the Library is opened signed-in with
 * the preference on, which is when a freshly loaded liked list
 * exists to act on. The network constraint is CONNECTED — any
 * connected network, matching the owner's own choice to enable
 * the feature; the app makes no unmetered-only claim anywhere.
 */
class AutoDownloadLikedWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OffleashApp ?: return Result.success()
        val container = app.container
        if (!container.appPreferences.autoDownloadLiked) return Result.success()
        // The credential this run's fetch rides on, captured BEFORE
        // the fetch (bug sweep round 7): the liked list is a blocking
        // network call, and a sign-out (or a sign-out + sign-in as a
        // different account) landing while it is out must not let
        // this run enqueue the PREVIOUS account's liked tracks —
        // the decided sign-out behaviour above ("does nothing while
        // signed out") covers a run that STARTS signed out, and this
        // capture extends it to a run that BECOMES signed out
        // mid-flight. The apply below re-checks the same credential.
        var fetchBearer: String? = null
        var fetchSessionGeneration: Long? = null
        val liked = try {
            val page = withContext(Dispatchers.IO) {
                // The token road first (0.7.0), the cookie session
                // as the fallback — the same order as every surface.
                val bearer = container.currentBearerToken()
                fetchBearer = bearer
                when {
                    bearer != null -> container.client.likedSongsOAuth(bearer)
                    else -> {
                        // Dormancy (bug sweep round 9): the cookie
                        // session is a FALLBACK ONLY — while a
                        // Google session is stored, or a Google
                        // sign-in has ever landed (the seen
                        // marker), the legacy session is dormant
                        // and must not act (the SessionManager
                        // legacy-generation rule the keep-alive
                        // worker already obeys). The bearer being
                        // null does NOT mean no Google session
                        // exists: a stored Google session whose
                        // token is expired and unrefreshable right
                        // now answers null here while it stands —
                        // and a revoked one leaves the marker
                        // behind. Either way this run does nothing
                        // rather than fetch (and below, enqueue)
                        // the dormant session's liked list.
                        if (container.googleSessionManager.hasStoredSession() ||
                            container.googleSignInSeenMarker.seen()
                        ) {
                            null
                        } else {
                        // The session and its generation are read
                        // as ONE consistent pair (round 9): two
                        // separate reads could pair the pre-turnover
                        // session with the post-turnover generation
                        // when a sign-out + sign-in landed between
                        // them, and the re-check below would then
                        // pass for a list fetched under the OLD
                        // session (see SessionManager
                        // .currentSessionWithGeneration).
                        val (session, generation) =
                            container.sessionManager.currentSessionWithGeneration()
                        // The session's GENERATION, not the session
                        // instance (round 8): round 7 compared the
                        // AuthSession by reference, but rotation
                        // replaces the instance for the SAME account
                        // as a matter of routine — the fetch's own
                        // response can rotate the session cookies
                        // (OkHttpTransport hands every YouTube
                        // response's Set-Cookie pairs to the session
                        // manager) before the re-check below ran, so
                        // a healthy same-account session read as
                        // "no longer current" and the run silently
                        // enqueued nothing, potentially every run.
                        // The generation turns over only when the
                        // session itself is adopted, ended, or
                        // signed out — exactly the events this guard
                        // exists to catch.
                        fetchSessionGeneration = if (session != null) generation else null
                        session?.let { container.client.likedSongs(it) }
                        }
                    }
                }
            }
            page ?: return Result.success()
        } catch (e: Exception) {
            // A network-shaped failure gets WorkManager's backoff; the
            // manager's health handling owns anything session-shaped.
            return Result.retry()
        }
        // Stale-apply guard (round 7, generation form round 8):
        // enqueue only while the credential that fetched the list
        // is still the current one. A token that merely refreshed
        // in the meantime also fails this check — the honest cost
        // is one skipped run; the next app start or Library visit
        // re-enqueues under the session that is actually current
        // then. (The cookie road's generation, by contrast, does
        // NOT turn over on a mere cookie rotation — that is the
        // same session continuing, and discarding for it was the
        // round-8 bug.)
        val stillCurrent = withContext(Dispatchers.IO) {
            when {
                fetchBearer != null ->
                    container.currentBearerToken() == fetchBearer
                fetchSessionGeneration != null ->
                    // The generation re-check (round 8) AND the
                    // dormancy re-check (round 9): a Google session
                    // adopted — or a Google sign-in landing and
                    // being wiped — while the cookie fetch was out
                    // makes the legacy session dormant, and a list
                    // fetched under it must not enqueue behind the
                    // now-current Google account's back, even
                    // though the legacy generation itself never
                    // moved. (This side's session/generation read
                    // order is fail-safe: any turnover between the
                    // two reads moves the generation and fails the
                    // equality, never the reverse.)
                    container.sessionManager.currentAuthSession() != null &&
                        container.sessionManager.sessionGeneration() == fetchSessionGeneration &&
                        !container.googleSessionManager.hasStoredSession() &&
                        !container.googleSignInSeenMarker.seen()
                else -> false
            }
        }
        if (!stillCurrent) return Result.success()
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
