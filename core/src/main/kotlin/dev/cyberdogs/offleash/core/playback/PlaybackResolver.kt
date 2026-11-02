package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.api.YoutubeiContexts
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.error.AllRoadsRefused
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.model.StreamInfo

/** One road's attempt at resolving a track, for diagnostics. */
data class RoadAttempt(
    /** "account" or "anonymous". */
    val road: String,
    /**
     * The outcome. On the anonymous road the outcome names the context
     * tried: "served:<CLIENTNAME>" or "<ErrorClass>:<CLIENTNAME>"
     * (e.g. "served:ANDROID", "PlayerRefused:IOS"). On the account
     * road it is "served" or the refusal's simple class name.
     */
    val outcome: String,
)

/** A resolved stream plus which road served it and what was tried. */
data class ResolvedStream(
    val info: StreamInfo,
    val servedByRoad: String,
    val attempts: List<RoadAttempt>,
)

/**
 * Per-track road-switching (Phase 2): with a session, the account road
 * (authenticated WEB_REMIX player) goes first — it is the road that can
 * serve Premium formats — and the anonymous road is the silent spare.
 * A track errors only when every road refuses, and then by name
 * ([AllRoadsRefused]).
 *
 * The anonymous road is itself an ordered cascade over
 * [YoutubeiContexts.ANONYMOUS_ROAD]: some refusals are specific to one
 * context or network identity, so a context's refusal cascades to the
 * next context and the first success serves.
 *
 * Signed-out playback keeps Phase 1 error semantics: the cascade runs,
 * but when every context refuses, the FIRST context's exception is
 * rethrown (iOS is first, so the common case reads exactly as Phase 1).
 *
 * A [QualityPreference] (Wave 2) rides through to every road and
 * context unchanged — the road that serves is picked by availability,
 * the format it serves by the preference. It defaults to HIGH, so
 * existing callers resolve exactly as they always did.
 *
 * Results are cached briefly (0.8.7 sweep 3): a successful
 * resolution is re-served for the same track, quality, and auth
 * scope for five minutes, so a replay, a queue re-entry, or a
 * download retry does not pay the whole cascade again. Stream
 * URLs live for hours, so five minutes is conservative; failures
 * are never cached, and [forceFresh] bypasses the read (the
 * playback retry paths pass it, so a recovery never re-serves
 * the URL that just failed). The cache key carries a hash of
 * the credential it resolved under — never the credential — so
 * a different account or a rotated token misses honestly.
 */
class PlaybackResolver(
    private val client: YoutubeiClient,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** What a cached resolution is keyed by (see the class doc). */
    private data class CacheKey(
        val videoId: String,
        val quality: QualityPreference,
        val authScope: String,
        val authFingerprint: Int,
    )

    private val cacheLock = Any()
    private val streamCache =
        LinkedHashMap<CacheKey, Pair<Long, ResolvedStream>>(
            CACHE_MAX_ENTRIES + 1,
            0.75f,
            true,
        )

    private fun cacheKey(
        videoId: String,
        quality: QualityPreference,
        auth: AuthSession?,
        bearerToken: String?,
    ): CacheKey = CacheKey(
        videoId = videoId,
        quality = quality,
        authScope = when {
            bearerToken != null -> "bearer"
            auth != null -> "cookie"
            else -> "anonymous"
        },
        authFingerprint = bearerToken?.hashCode() ?: auth?.hashCode() ?: 0,
    )

    private fun cachedStream(key: CacheKey): ResolvedStream? =
        synchronized(cacheLock) {
            val entry = streamCache[key] ?: return null
            if (nowMs() - entry.first > CACHE_TTL_MS) {
                streamCache.remove(key)
                null
            } else {
                entry.second
            }
        }

    private fun cacheStream(key: CacheKey, resolved: ResolvedStream) {
        synchronized(cacheLock) {
            // Bounded by generation, not eviction: at the cap
            // the cache restarts empty rather than tracking an
            // eldest entry — a miss only ever costs a re-resolve.
            if (streamCache.size >= CACHE_MAX_ENTRIES) {
                streamCache.clear()
            }
            streamCache[key] = nowMs() to resolved
        }
    }

    /**
     * Resolution with a Google OAuth Bearer token (0.7.0): the
     * token road — the ANDROID_MUSIC player under the owner's Google
     * session — goes FIRST, ahead of both the cookie account road
     * and the anonymous cascade. Where the token road refuses a
     * track, the proven roads still apply exactly as below (cookie
     * account road when a cookie session also exists, then the
     * anonymous cascade), with the token attempt kept on the record:
     * a stream the token can't serve is still served, while the
     * token provides identity and library.
     */
    fun resolve(
        videoId: String,
        auth: AuthSession?,
        quality: QualityPreference = QualityPreference.HIGH,
        bearerToken: String?,
        forceFresh: Boolean = false,
    ): ResolvedStream {
        val key = cacheKey(videoId, quality, auth, bearerToken)
        val cached = if (forceFresh) null else cachedStream(key)
        if (cached != null) return cached
        val resolved =
            resolveWithBearer(videoId, auth, quality, bearerToken, forceFresh)
        cacheStream(key, resolved)
        return resolved
    }

    private fun resolveWithBearer(
        videoId: String,
        auth: AuthSession?,
        quality: QualityPreference,
        bearerToken: String?,
        forceFresh: Boolean,
    ): ResolvedStream {
        if (bearerToken == null) {
            return resolve(videoId, auth, quality, forceFresh = forceFresh)
        }
        val tokenError = try {
            val info = client.streamInfoOAuth(videoId, bearerToken, quality)
            return ResolvedStream(
                info = info,
                servedByRoad = "account",
                attempts = listOf(RoadAttempt("account", "served:ANDROID_MUSIC")),
            )
        } catch (e: OffleashException) {
            e
        } catch (e: Exception) {
            NetworkFailure(e.javaClass.simpleName)
        }
        val base = resolve(videoId, auth, quality, forceFresh = forceFresh)
        return base.copy(
            attempts = listOf(
                RoadAttempt(
                    "account",
                    "${tokenError.javaClass.simpleName}:ANDROID_MUSIC",
                )
            ) + base.attempts,
        )
    }

    fun resolve(
        videoId: String,
        auth: AuthSession?,
        quality: QualityPreference = QualityPreference.HIGH,
        forceFresh: Boolean = false,
    ): ResolvedStream {
        val key = cacheKey(videoId, quality, auth, bearerToken = null)
        val cached = if (forceFresh) null else cachedStream(key)
        if (cached != null) return cached
        val resolved = resolveOnRoads(videoId, auth, quality)
        cacheStream(key, resolved)
        return resolved
    }

    private fun resolveOnRoads(
        videoId: String,
        auth: AuthSession?,
        quality: QualityPreference,
    ): ResolvedStream {
        if (auth == null) {
            val (info, attempts) = resolveAnonymous(videoId, quality)
            return ResolvedStream(
                info = info,
                servedByRoad = "anonymous",
                attempts = attempts,
            )
        }
        val accountError = try {
            val info = client.streamInfoAuthed(videoId, auth, quality)
            return ResolvedStream(
                info = info,
                servedByRoad = "account",
                attempts = listOf(RoadAttempt("account", "served")),
            )
        } catch (e: OffleashException) {
            e
        } catch (e: Exception) {
            NetworkFailure(e.javaClass.simpleName)
        }
        val attempts = mutableListOf(
            RoadAttempt("account", accountError.javaClass.simpleName)
        )
        var firstAnonymousError: OffleashException? = null
        for (context in YoutubeiContexts.ANONYMOUS_ROAD) {
            try {
                val info = client.streamInfo(videoId, context, quality)
                attempts.add(RoadAttempt("anonymous", "served:${context.clientName}"))
                return ResolvedStream(
                    info = info,
                    servedByRoad = "anonymous",
                    attempts = attempts,
                )
            } catch (e: Exception) {
                val failure = e as? OffleashException
                    ?: NetworkFailure(e.javaClass.simpleName)
                if (firstAnonymousError == null) firstAnonymousError = failure
                attempts.add(
                    RoadAttempt(
                        "anonymous",
                        "${failure.javaClass.simpleName}:${context.clientName}",
                    )
                )
            }
        }
        throw AllRoadsRefused(
            accountError,
            firstAnonymousError ?: NetworkFailure(null),
        )
    }

    /**
     * The anonymous cascade on its own. Returns the stream and every
     * attempt on success; when all contexts refuse, rethrows the FIRST
     * context's exception.
     */
    private fun resolveAnonymous(
        videoId: String,
        quality: QualityPreference,
    ): Pair<StreamInfo, List<RoadAttempt>> {
        val attempts = mutableListOf<RoadAttempt>()
        var firstError: OffleashException? = null
        for (context in YoutubeiContexts.ANONYMOUS_ROAD) {
            try {
                val info = client.streamInfo(videoId, context, quality)
                attempts.add(RoadAttempt("anonymous", "served:${context.clientName}"))
                return info to attempts
            } catch (e: Exception) {
                val failure = e as? OffleashException
                    ?: NetworkFailure(e.javaClass.simpleName)
                if (firstError == null) firstError = failure
                attempts.add(
                    RoadAttempt(
                        "anonymous",
                        "${failure.javaClass.simpleName}:${context.clientName}",
                    )
                )
            }
        }
        throw firstError ?: NetworkFailure(null)
    }
}

/** How long a cached resolution is re-served (sweep 3). */
private const val CACHE_TTL_MS = 5 * 60 * 1000L

/** The cache's entry cap; at the cap the cache restarts empty. */
private const val CACHE_MAX_ENTRIES = 64
