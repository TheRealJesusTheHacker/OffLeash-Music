package dev.cyberdogs.offleash.core.model

/**
 * One line of lyrics. [startMs] is set only for synced (timed) lyrics;
 * plain lyrics from YouTube's lyrics page carry no timing, so it stays
 * null there rather than pretending a position was known.
 */
data class LyricsLine(
    val text: String,
    val startMs: Long? = null,
)

/**
 * The outcome of a lyrics lookup. Lyrics are decoration on top of
 * playback, never a precondition for it, so failure is a value —
 * [Unavailable] with a plain-language reason — not an exception.
 */
sealed interface LyricsResult {

    /** Lyrics were found; [source] names where they came from. */
    data class Found(
        val lines: List<LyricsLine>,
        val source: String,
    ) : LyricsResult

    /** No lyrics this build could serve; [reason] says why, plainly. */
    data class Unavailable(
        val reason: String,
    ) : LyricsResult
}
