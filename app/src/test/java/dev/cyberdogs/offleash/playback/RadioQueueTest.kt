package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Radio queue construction + share text (Lane G). The radio tracks
 * here stand in for the core client's fixture-pinned `related()`
 * output (recorded `next.json`: 49 tracks, seed excluded) — this unit
 * pins what the app does with that list: seed first, API order kept,
 * deduped by videoId.
 */
class RadioQueueTest {

    private fun track(id: String, title: String = "Track $id") = Track(
        videoId = id,
        title = title,
        artists = listOf("Artist $id"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 200,
        thumbnailUrl = null,
    )

    @Test
    fun seedIsFirstAndRelatedOrderIsKept() {
        val seed = track("seed")
        val related = listOf(track("a"), track("b"), track("c"))
        val queue = buildRadioQueue(seed, related, preferAudioVersion = false)
        assertEquals(listOf("seed", "a", "b", "c"), queue.map { it.videoId })
    }

    @Test
    fun seedAndDuplicatesInsideRelatedAreDeduped() {
        val seed = track("seed")
        val related = listOf(track("seed"), track("a"), track("a"), track("b"))
        val queue = buildRadioQueue(seed, related, preferAudioVersion = false)
        assertEquals(listOf("seed", "a", "b"), queue.map { it.videoId })
    }

    @Test
    fun emptyRelatedYieldsJustTheSeed() {
        val queue = buildRadioQueue(track("seed"), emptyList(), preferAudioVersion = false)
        assertEquals(listOf("seed"), queue.map { it.videoId })
    }

    @Test
    fun audioVersionPreferenceIsADocumentedNoOpToday() {
        // The Track model carries no audio-vs-video distinction, so
        // the preference must not reorder or drop anything — pinning
        // that honesty here until the API evidence supports more.
        val seed = track("seed")
        val related = listOf(track("a"), track("b"))
        assertEquals(
            buildRadioQueue(seed, related, preferAudioVersion = false),
            buildRadioQueue(seed, related, preferAudioVersion = true),
        )
    }

    // -- autoplayContinuation (2026-10-27 queue-advancement hunt) -----

    @Test
    fun continuationKeepsApiOrderAndDropsTheSeed() {
        val seed = track("seed")
        val related = listOf(track("a"), track("seed"), track("b"))
        val continuation = autoplayContinuation(seed, related, queuedIds = setOf("seed"))
        assertEquals(listOf("a", "b"), continuation.map { it.videoId })
    }

    @Test
    fun continuationExcludesEverythingAlreadyQueued() {
        // Played and upcoming alike: the chain must never loop back
        // over the queue it extends.
        val seed = track("now")
        val related = listOf(track("played"), track("now"), track("upnext"), track("fresh"))
        val continuation = autoplayContinuation(
            seed,
            related,
            queuedIds = setOf("played", "now", "upnext"),
        )
        assertEquals(listOf("fresh"), continuation.map { it.videoId })
    }

    @Test
    fun continuationDedupesInsideRelated() {
        val continuation = autoplayContinuation(
            track("seed"),
            listOf(track("a"), track("a"), track("b")),
            queuedIds = setOf("seed"),
        )
        assertEquals(listOf("a", "b"), continuation.map { it.videoId })
    }

    @Test
    fun continuationIsEmptyWhenNothingNewIsOffered() {
        val continuation = autoplayContinuation(
            track("seed"),
            listOf(track("seed"), track("only")),
            queuedIds = setOf("seed", "only"),
        )
        assertEquals(emptyList<Track>(), continuation)
    }

    @Test
    fun shareUrlIsTheCanonicalMusicUrl() {
        assertEquals(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            youtubeMusicTrackUrl("dQw4w9WgXcQ"),
        )
    }

    @Test
    fun shareTextCarriesTitleArtistsAndUrl() {
        val text = shareTextFor(track("abc", title = "Midnight Wire"))
        assertEquals(
            "Midnight Wire — Artist abc\nhttps://music.youtube.com/watch?v=abc",
            text,
        )
    }

    @Test
    fun shareTextWithoutArtistsIsTitleAndUrl() {
        val bare = Track(
            videoId = "xyz",
            title = "Solo",
            artists = emptyList(),
            artistBrowseId = null,
            album = null,
            albumBrowseId = null,
            durationSeconds = null,
            thumbnailUrl = null,
        )
        assertEquals("Solo\nhttps://music.youtube.com/watch?v=xyz", shareTextFor(bare))
    }
}
