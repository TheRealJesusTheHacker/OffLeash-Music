package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Next-track prefetch decisions (2026-11-04 playback-finish run):
 * the pure matrix behind smooth transitions — which entry is worth
 * resolving ahead, when a prefetched resolution may serve the
 * transition (and when the force-fresh law refuses it), and when
 * playback is close enough to the end to arm the prefetch.
 */
class PlaybackPrefetchTest {

    private fun track(id: String) = Track(
        videoId = id,
        title = "Track $id",
        artists = listOf("Artist $id"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 200,
        thumbnailUrl = null,
    )

    // -- candidate ---------------------------------------------------

    @Test
    fun candidateIsThePeekedNextEntry() {
        val current = track("a")
        val next = track("b")
        assertSame(next, prefetchCandidate(current, next))
    }

    @Test
    fun candidateIsNullWhenTheQueueHasNoNext() {
        assertNull(prefetchCandidate(track("a"), null))
        assertNull(prefetchCandidate(null, null))
    }

    @Test
    fun candidateIsNeverTheCurrentEntryItself() {
        // Repeat ALL on a one-track queue peeks the playing entry:
        // resolving it again buys nothing.
        val current = track("a")
        assertNull(prefetchCandidate(current, current))
    }

    @Test
    fun candidateAllowsADuplicateOfTheCurrentSongAsADistinctEntry() {
        // The same song queued twice is two entries: the second is
        // a real transition and earns its prefetch.
        val first = track("a")
        val second = track("a")
        assertSame(second, prefetchCandidate(first, second))
    }

    // -- consume verdict ----------------------------------------------

    @Test
    fun freshMatchingStashServesTheTransition() {
        val entry = track("b")
        assertEquals(
            PrefetchConsume.TAKE,
            prefetchConsumeVerdict(
                stashEntry = entry,
                requested = entry,
                isRetry = false,
                recoverySeekMs = 0L,
                ageMs = 1_000L,
            ),
        )
    }

    @Test
    fun stashAtExactlyTheFreshnessLimitStillServes() {
        val entry = track("b")
        assertEquals(
            PrefetchConsume.TAKE,
            prefetchConsumeVerdict(
                stashEntry = entry,
                requested = entry,
                isRetry = false,
                recoverySeekMs = 0L,
                ageMs = PREFETCH_MAX_AGE_MS,
            ),
        )
    }

    @Test
    fun staleStashIsDiscardedNeverStretched() {
        val entry = track("b")
        assertEquals(
            PrefetchConsume.RESOLVE_FRESH,
            prefetchConsumeVerdict(
                stashEntry = entry,
                requested = entry,
                isRetry = false,
                recoverySeekMs = 0L,
                ageMs = PREFETCH_MAX_AGE_MS + 1L,
            ),
        )
    }

    @Test
    fun recoveryNeverConsumesTheStash() {
        // The force-fresh law: a retry re-resolve (403 fast path,
        // IO recovery) must resolve live — the stash was resolved
        // before the failure and may name the very URL that died.
        val entry = track("b")
        assertEquals(
            PrefetchConsume.RESOLVE_FRESH,
            prefetchConsumeVerdict(
                stashEntry = entry,
                requested = entry,
                isRetry = true,
                recoverySeekMs = 0L,
                ageMs = 1_000L,
            ),
        )
    }

    @Test
    fun resumeSeekNeverConsumesTheStash() {
        // A resolve carrying a recovery position is a recovery,
        // whatever its retry flag says.
        val entry = track("b")
        assertEquals(
            PrefetchConsume.RESOLVE_FRESH,
            prefetchConsumeVerdict(
                stashEntry = entry,
                requested = entry,
                isRetry = false,
                recoverySeekMs = 42_000L,
                ageMs = 1_000L,
            ),
        )
    }

    @Test
    fun equalIdDifferentInstanceIsNotTheStashEntry() {
        // A replaced queue can hold an equal-videoId copy of the
        // stashed track: a different ENTRY, and it resolves live
        // (the 0.8.7 identity discipline).
        val stashed = track("b")
        val replacement = track("b")
        assertEquals(
            PrefetchConsume.RESOLVE_FRESH,
            prefetchConsumeVerdict(
                stashEntry = stashed,
                requested = replacement,
                isRetry = false,
                recoverySeekMs = 0L,
                ageMs = 1_000L,
            ),
        )
    }

    @Test
    fun missingStashResolvesFresh() {
        assertEquals(
            PrefetchConsume.RESOLVE_FRESH,
            prefetchConsumeVerdict(
                stashEntry = null,
                requested = track("b"),
                isRetry = false,
                recoverySeekMs = 0L,
                ageMs = 0L,
            ),
        )
    }

    // -- position arming ----------------------------------------------

    @Test
    fun prefetchArmsInsideTheLeadWindow() {
        assertTrue(prefetchDueByPosition(durationMs = 200_000L, positionMs = 150_000L))
        assertTrue(prefetchDueByPosition(durationMs = 200_000L, positionMs = 200_000L))
    }

    @Test
    fun prefetchDoesNotArmEarlyInATrack() {
        assertFalse(prefetchDueByPosition(durationMs = 200_000L, positionMs = 100_000L))
    }

    @Test
    fun prefetchNeverArmsOnAnUnknownDuration() {
        assertFalse(prefetchDueByPosition(durationMs = 0L, positionMs = 0L))
        assertFalse(prefetchDueByPosition(durationMs = -1L, positionMs = 0L))
    }

    @Test
    fun prefetchDoesNotArmPastTheEnd() {
        // A position past the reported duration is a metadata
        // mismatch, not a lead window.
        assertFalse(prefetchDueByPosition(durationMs = 200_000L, positionMs = 210_000L))
    }
}
