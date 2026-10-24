package dev.cyberdogs.offleash.core.error

/**
 * The OffLeash failure taxonomy (SPEC §6). Every failure the user can hit
 * has one of these names; messages follow the plain-language shapes the
 * spec pins, with the server's own words attached where they exist.
 */
sealed class OffleashException(message: String) : Exception(message)

/** HTTP 4xx from youtubei with an error body — the API likely changed under us. */
class ContextRejected(val serverMessage: String) : OffleashException(
    "YouTube refused the app's request — the API has likely changed. (server said: $serverMessage)"
)

/** `playabilityStatus` was not OK for a player request. */
class PlayerRefused(val reason: String) : OffleashException(
    "YouTube won't play this track: $reason"
)

/** Playable in principle, but every format served was locked/ciphered. */
class NoPlayableStream : OffleashException(
    "No playable stream for this track — YouTube served only locked formats."
)

/** The transport threw, or youtubei answered with a 5xx. */
class NetworkFailure(val networkDetail: String?) : OffleashException(
    "Couldn't reach YouTube Music — check your connection." +
        (networkDetail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
)

/** The response arrived but in a shape this build doesn't understand. */
class ParseFailure(val detail: String) : OffleashException(
    "YouTube Music answered in a shape this app doesn't understand yet — the API has likely changed. ($detail)"
)

/**
 * Playback failed on BOTH roads (Phase 2 road-switching): the account
 * road refused and the anonymous spare refused too. Carries both
 * refusals so the UI can name what actually happened on each road.
 */
class AllRoadsRefused(
    val accountError: OffleashException,
    val anonymousError: OffleashException,
) : OffleashException(
    "YouTube won't play this track on either road — " +
        "account road: ${accountError.message} " +
        "anonymous road: ${anonymousError.message}"
)

/**
 * Whether this failure reads as an auth refusal: a [ContextRejected]
 * (HTTP 4xx — e.g. a dead session's 401/403), or a [PlayerRefused]
 * whose server reason names LOGIN_REQUIRED. Everything else (network,
 * parse, unavailable, locked formats) is not an auth refusal.
 */
fun OffleashException.isAuthRefusal(): Boolean = when (this) {
    is ContextRejected -> true
    is PlayerRefused -> reason.contains("LOGIN_REQUIRED", ignoreCase = true)
    else -> false
}
