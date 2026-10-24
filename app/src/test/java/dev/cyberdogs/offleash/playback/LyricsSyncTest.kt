package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.core.model.LyricsLine
import dev.cyberdogs.offleash.core.model.LyricsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lyrics line-sync selection (Lane G): given a position and timed
 * lines, which line is current — including the before-first and
 * after-last edges, and the unsynced case where no line may ever be
 * claimed current.
 */
class LyricsSyncTest {

    private val timed = listOf(
        LyricsLine("first", startMs = 10_000L),
        LyricsLine("second", startMs = 20_000L),
        LyricsLine("third", startMs = 35_000L),
    )

    @Test
    fun beforeTheFirstLineNothingIsCurrent() {
        assertEquals(-1, currentLyricsIndex(timed, 0L))
        assertEquals(-1, currentLyricsIndex(timed, 9_999L))
    }

    @Test
    fun atAndBetweenLinesTheLastStartedLineIsCurrent() {
        assertEquals(0, currentLyricsIndex(timed, 10_000L))
        assertEquals(0, currentLyricsIndex(timed, 19_999L))
        assertEquals(1, currentLyricsIndex(timed, 20_000L))
        assertEquals(1, currentLyricsIndex(timed, 34_999L))
        assertEquals(2, currentLyricsIndex(timed, 35_000L))
    }

    @Test
    fun afterTheLastLineTheLastLineStaysCurrent() {
        assertEquals(2, currentLyricsIndex(timed, 35_001L))
        assertEquals(2, currentLyricsIndex(timed, 3_600_000L))
    }

    @Test
    fun unsyncedLinesNeverSelectACurrentLine() {
        val plain = listOf(LyricsLine("a"), LyricsLine("b"), LyricsLine("c"))
        assertEquals(-1, currentLyricsIndex(plain, 0L))
        assertEquals(-1, currentLyricsIndex(plain, 60_000L))
        assertFalse(lyricsAreSynced(plain))
        assertTrue(lyricsAreSynced(timed))
    }

    @Test
    fun emptyLinesSelectNothing() {
        assertEquals(-1, currentLyricsIndex(emptyList(), 0L))
        assertEquals(-1, currentLyricsIndex(emptyList(), 60_000L))
    }

    @Test
    fun untimedLinesBetweenTimedOnesAreSkippedNotSelected() {
        val mixed = listOf(
            LyricsLine("timed a", startMs = 5_000L),
            LyricsLine("untimed bridge"),
            LyricsLine("timed b", startMs = 15_000L),
        )
        assertEquals(0, currentLyricsIndex(mixed, 14_999L))
        assertEquals(2, currentLyricsIndex(mixed, 15_000L))
    }

    @Test
    fun outOfOrderTimedLinesAreStillSelected() {
        // Sweep #5: a multi-timestamp LRC line expands in textual
        // order, so entries can arrive out of chronological order.
        // The old early-break scan returned -1 here (it stopped at
        // the 20s line and never reached the 10s line behind it).
        val unordered = listOf(
            LyricsLine("later chorus", startMs = 20_000L),
            LyricsLine("earlier verse", startMs = 10_000L),
        )
        assertEquals(1, currentLyricsIndex(unordered, 15_000L))
        assertEquals(0, currentLyricsIndex(unordered, 25_000L))
        assertEquals(-1, currentLyricsIndex(unordered, 5_000L))
    }

    @Test
    fun cacheStoresAndReturnsPerTrackIncludingUnavailable() {
        val cache = LyricsCache(maxEntries = 4)
        val found = LyricsResult.Found(listOf(LyricsLine("x", 0L)), source = "LRCLIB")
        val unavailable = LyricsResult.Unavailable("no lyrics")
        cache.put("a", found)
        cache.put("b", unavailable)
        assertEquals(found, cache["a"])
        assertEquals(unavailable, cache["b"])
        assertNull(cache["missing"])
        assertEquals(2, cache.size())
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedBeyondCapacity() {
        val cache = LyricsCache(maxEntries = 2)
        val result = LyricsResult.Unavailable("no lyrics")
        cache.put("a", result)
        cache.put("b", result)
        // Touch "a" so "b" is the eldest, then overflow.
        assertEquals(result, cache["a"])
        cache.put("c", result)
        assertNull(cache["b"])
        assertEquals(result, cache["a"])
        assertEquals(result, cache["c"])
        assertEquals(2, cache.size())
    }
}
