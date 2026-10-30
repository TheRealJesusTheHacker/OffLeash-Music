package dev.cyberdogs.offleash.core.model

/**
 * Continuation paging discipline (bug sweep round 1), pure so every
 * surface that pages — playlist pages, Liked Songs, search — shares
 * the one honest set of rules:
 *
 * - a response that offers a token has a next page; one that offers
 *   none is the true end;
 * - a token the server repeats — the one just sent, or any token
 *   already seen this session (an A→B→A cycle) — TERMINATES paging
 *   instead of looping forever on a misbehaving server;
 * - tracks already on screen are never appended twice: a page that
 *   overlaps the previous one contributes only its new tracks;
 * - [ContinuationTracker.reset] (a refresh / a new list) forgets all
 *   seen tokens, so the first page is never lost to stale state.
 */
class ContinuationTracker {

    private val seenTokens = mutableSetOf<String>()

    /** The token to send for the next page, or null when paging is done. */
    var current: String? = null
        private set

    /** Starts a fresh paging session from a first page's offered token. */
    fun start(firstToken: String?): String? {
        seenTokens.clear()
        current = if (firstToken.isNullOrEmpty()) {
            null
        } else {
            seenTokens.add(firstToken)
            firstToken
        }
        return current
    }

    /**
     * Records the token a continuation response offered in answer to
     * [sentToken]. Returns the token to send next, or null when
     * paging ends here: nothing offered, the sent token repeated, or
     * a token already seen in this session.
     */
    fun advance(sentToken: String, receivedToken: String?): String? {
        current = when {
            receivedToken.isNullOrEmpty() -> null
            receivedToken == sentToken -> null
            !seenTokens.add(receivedToken) -> null
            else -> receivedToken
        }
        return current
    }

    /**
     * [advance], guarded for an in-flight page fetch whose paging
     * session may have been superseded while the (blocking) fetch
     * ran (bug sweep round 5): a newer search or refresh resets this
     * tracker and starts its own session, but nothing cancels the
     * old fetch's coroutine, so it still returns holding its chunk.
     * An unguarded [advance] from that stale fetch CLOBBERS the new
     * session — [current] jumps to the old session's next token and
     * the new session's token is marked seen — after which the new
     * session's own "load more" fetches the OLD session's pages
     * (which its merge guard then refuses, leaving a dead control
     * that spins forever) or, when the tokens coincide, merges the
     * stale chunk in. The merge-side guards in the view models
     * never see this: they read [current] only after it was already
     * overwritten.
     *
     * Returns true when [sentToken] is still this tracker's current
     * token and the advance was applied; returns false — and leaves
     * the tracker exactly as the newer session left it — when the
     * session has moved on.
     */
    fun tryAdvance(sentToken: String, receivedToken: String?): Boolean {
        if (current != sentToken) return false
        advance(sentToken, receivedToken)
        return true
    }

    /** Forgets the session (refresh): the next [start] begins clean. */
    fun reset() {
        seenTokens.clear()
        current = null
    }
}

/**
 * The tracks of [incoming] that are not already in [existing], in
 * order — deduplicated by videoId, including duplicates inside
 * [incoming] itself. A re-requested page therefore appends nothing.
 */
fun newTracksOnly(existing: List<Track>, incoming: List<Track>): List<Track> {
    val seen = existing.mapTo(mutableSetOf()) { it.videoId }
    return incoming.filter { seen.add(it.videoId) }
}
