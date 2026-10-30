package dev.cyberdogs.offleash.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The paging discipline (bug sweep round 1): a present token means
 * more, an absent token means the true end, and a server that
 * repeats a token — the one just sent, or an earlier one in an
 * A→B→A cycle — terminates paging instead of looping forever.
 * Re-requesting a page appends nothing twice.
 */
class ContinuationTrackerTest {

    private fun track(id: String) = Track(
        videoId = id,
        title = "t-$id",
        artists = emptyList(),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = null,
        thumbnailUrl = null,
    )

    @Test
    fun presentTokenMeansMoreAndAbsentMeansEnd() {
        val tracker = ContinuationTracker()
        assertEquals("A", tracker.start("A"))
        assertEquals("A", tracker.current)
        assertNull(tracker.advance("A", null))
        assertNull(tracker.current)
    }

    @Test
    fun startWithoutATokenIsAlreadyTheEnd() {
        val tracker = ContinuationTracker()
        assertNull(tracker.start(null))
        assertNull(tracker.start(""))
        assertNull(tracker.current)
    }

    @Test
    fun aRepeatedTokenTerminatesInsteadOfLooping() {
        val tracker = ContinuationTracker()
        tracker.start("A")
        // The server answers the A request by offering A again.
        assertNull(tracker.advance("A", "A"))
        assertNull(tracker.current)
    }

    @Test
    fun aTokenCycleTerminates() {
        val tracker = ContinuationTracker()
        tracker.start("A")
        assertEquals("B", tracker.advance("A", "B"))
        // B's page offers A again — already seen this session.
        assertNull(tracker.advance("B", "A"))
        assertNull(tracker.current)
    }

    @Test
    fun resetForgetsSeenTokensSoARefreshStartsClean() {
        val tracker = ContinuationTracker()
        tracker.start("A")
        tracker.advance("A", "A")
        assertNull(tracker.current)
        tracker.reset()
        // After a refresh the same first-page token is fresh again —
        // the first page is never lost to stale tracker state.
        assertEquals("A", tracker.start("A"))
        assertEquals("B", tracker.advance("A", "B"))
    }

    // -- Round 5: the superseded-session guard (tryAdvance) ------------
    //
    // A page fetch is blocking, so a newer search/refresh can reset
    // and re-start this tracker while the old fetch is still in
    // flight. When the old fetch returns it must not advance the
    // NEW session: the old code's plain advance() overwrote current
    // with the old session's next token (and marked it seen), which
    // left the new session's Load more fetching the old session's
    // pages — refused by its merge guard, forever, with hasMore
    // stuck on.

    @Test
    fun tryAdvanceAppliesWhileTheSessionIsStillTheFetchersOwn() {
        val tracker = ContinuationTracker()
        tracker.start("A")
        assertEquals(true, tracker.tryAdvance("A", "B"))
        assertEquals("B", tracker.current)
        // The end of paging applies too, and reports as applied.
        assertEquals(true, tracker.tryAdvance("B", null))
        assertNull(tracker.current)
    }

    @Test
    fun tryAdvanceFromASupersededSessionLeavesTheNewSessionUntouched() {
        val tracker = ContinuationTracker()
        tracker.start("OLD-1")
        // The newer search lands while the old page fetch is out:
        // the tracker is reset and re-started as the new session.
        tracker.reset()
        tracker.start("NEW-1")
        // The stale fetch returns and tries to advance its own token.
        assertEquals(false, tracker.tryAdvance("OLD-1", "OLD-2"))
        // The new session is exactly as its own first page left it…
        assertEquals("NEW-1", tracker.current)
        // …including its seen set: the stale chunk's token was NOT
        // marked seen, so the new session may still legitimately
        // be offered (and accept) that same token string later.
        assertEquals(true, tracker.tryAdvance("NEW-1", "OLD-2"))
        assertEquals("OLD-2", tracker.current)
    }

    @Test
    fun tryAdvanceRefusedByADirectStartSupersessionLeavesSeenUntouched() {
        // Round 6 (area B): the R5 pins supersede via reset+start;
        // start() alone (it clears the seen set itself) must refuse
        // the stale advance the same way, and the refusal must not
        // mark the stale chunk's token seen in the new session.
        val tracker = ContinuationTracker()
        tracker.start("OLD-1")
        tracker.start("NEW-1")
        assertEquals(false, tracker.tryAdvance("OLD-1", "OLD-2"))
        assertEquals("NEW-1", tracker.current)
        assertEquals(true, tracker.tryAdvance("NEW-1", "OLD-2"))
        assertEquals("OLD-2", tracker.current)
    }

    @Test
    fun refusedAdvanceDoesNotMarkItsReceivedTokenSeen() {
        // Round 6 (area B): a refused advance must leave the seen
        // set byte-identical — probed behaviorally. The stale
        // fetch offers NEXT; if the refusal had marked NEXT seen,
        // the live session's own legitimate advance to NEXT below
        // would terminate paging instead of accepting it.
        val tracker = ContinuationTracker()
        tracker.start("A")
        assertEquals(false, tracker.tryAdvance("STALE", "NEXT"))
        assertEquals("A", tracker.current)
        assertEquals(true, tracker.tryAdvance("A", "NEXT"))
        assertEquals("NEXT", tracker.current)
    }

    @Test
    fun sameTokenReissuedToANewSessionIsTheSameContinuation() {
        // Round 6 (area B) — documented dismissal, pinned: two
        // sessions issued the SAME token string. tryAdvance keys
        // on the token, so a stale advance succeeds here. That is
        // sound, not a hole: a continuation token is the identity
        // of the page request itself (an opaque capability
        // encoding context + offset, not a session nonce) — the
        // chunk a stale fetch returns for token T is exactly the
        // chunk the new session's own load-more for T would
        // return, and both view models additionally require the
        // live state's continuation to equal T before merging.
        // A token that differs refuses, as the tests above pin.
        val tracker = ContinuationTracker()
        tracker.start("T")
        tracker.start("T")
        assertEquals(true, tracker.tryAdvance("T", "NEXT"))
        assertEquals("NEXT", tracker.current)
    }

    @Test
    fun tryAdvanceAfterAResetWithNoNewSessionIsAlsoRefused() {
        val tracker = ContinuationTracker()
        tracker.start("A")
        // The query was cleared (reset, no new session started):
        // an in-flight page fetch returning now owns nothing.
        tracker.reset()
        assertEquals(false, tracker.tryAdvance("A", "B"))
        assertNull(tracker.current)
    }

    @Test
    fun newTracksOnlyKeysOnVideoIdNotTitle() {
        // Round 48: the pin below couples every track's title
        // 1:1 to its videoId ("t-<id>"), so keying the dedupe on
        // TITLE instead of videoId ran green across the full
        // core suite — while every same-title legitimate track
        // (a single and its album cut, a live and studio take)
        // would vanish on load-more. The key is the videoId:
        // same-title tracks with distinct ids are all new, and
        // a re-offered id is dropped whatever its title says.
        fun titled(id: String, title: String) = Track(
            videoId = id,
            title = title,
            artists = emptyList(),
            artistBrowseId = null,
            album = null,
            albumBrowseId = null,
            durationSeconds = null,
            thumbnailUrl = null,
        )
        val existing = listOf(titled("a", "Same Title"))
        val incoming = listOf(
            titled("b", "Same Title"),
            titled("c", "Same Title"),
            titled("d", "Other Title"),
        )
        assertEquals(
            listOf("b", "c", "d"),
            newTracksOnly(existing, incoming).map { it.videoId },
        )
        // Same id under a different title is still the same track.
        assertEquals(
            emptyList<Track>(),
            newTracksOnly(existing, listOf(titled("a", "Retitled"))),
        )
        // In-page duplicates are keyed by id too: two same-title
        // rows with distinct ids both survive.
        assertEquals(
            listOf("b", "c"),
            newTracksOnly(
                emptyList(),
                listOf(titled("b", "Same Title"), titled("c", "Same Title")),
            ).map { it.videoId },
        )
    }

    @Test
    fun newTracksOnlyDropsOverlapAndInPageDuplicates() {
        val existing = listOf(track("a"), track("b"))
        val incoming = listOf(track("b"), track("c"), track("c"), track("d"))
        assertEquals(
            listOf("c", "d"),
            newTracksOnly(existing, incoming).map { it.videoId },
        )
        // A whole re-requested page appends nothing.
        assertEquals(
            emptyList<Track>(),
            newTracksOnly(existing, existing),
        )
    }
}
