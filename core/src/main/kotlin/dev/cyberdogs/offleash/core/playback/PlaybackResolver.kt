package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.api.YoutubeiContexts
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.error.AllRoadsRefused
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
 */
class PlaybackResolver(
    private val client: YoutubeiClient,
) {

    fun resolve(videoId: String, auth: AuthSession?): ResolvedStream {
        if (auth == null) {
            val (info, attempts) = resolveAnonymous(videoId)
            return ResolvedStream(
                info = info,
                servedByRoad = "anonymous",
                attempts = attempts,
            )
        }
        val accountError = try {
            val info = client.streamInfoAuthed(videoId, auth)
            return ResolvedStream(
                info = info,
                servedByRoad = "account",
                attempts = listOf(RoadAttempt("account", "served")),
            )
        } catch (e: OffleashException) {
            e
        }
        val attempts = mutableListOf(
            RoadAttempt("account", accountError.javaClass.simpleName)
        )
        var firstAnonymousError: OffleashException? = null
        for (context in YoutubeiContexts.ANONYMOUS_ROAD) {
            try {
                val info = client.streamInfo(videoId, context)
                attempts.add(RoadAttempt("anonymous", "served:${context.clientName}"))
                return ResolvedStream(
                    info = info,
                    servedByRoad = "anonymous",
                    attempts = attempts,
                )
            } catch (e: OffleashException) {
                if (firstAnonymousError == null) firstAnonymousError = e
                attempts.add(
                    RoadAttempt(
                        "anonymous",
                        "${e.javaClass.simpleName}:${context.clientName}",
                    )
                )
            }
        }
        throw AllRoadsRefused(accountError, firstAnonymousError!!)
    }

    /**
     * The anonymous cascade on its own. Returns the stream and every
     * attempt on success; when all contexts refuse, rethrows the FIRST
     * context's exception.
     */
    private fun resolveAnonymous(videoId: String): Pair<StreamInfo, List<RoadAttempt>> {
        val attempts = mutableListOf<RoadAttempt>()
        var firstError: OffleashException? = null
        for (context in YoutubeiContexts.ANONYMOUS_ROAD) {
            try {
                val info = client.streamInfo(videoId, context)
                attempts.add(RoadAttempt("anonymous", "served:${context.clientName}"))
                return info to attempts
            } catch (e: OffleashException) {
                if (firstError == null) firstError = e
                attempts.add(
                    RoadAttempt(
                        "anonymous",
                        "${e.javaClass.simpleName}:${context.clientName}",
                    )
                )
            }
        }
        throw firstError!!
    }
}
