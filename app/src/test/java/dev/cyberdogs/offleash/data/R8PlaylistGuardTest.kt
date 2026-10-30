package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Round 8 (composition attack on the round-7 moveTrack identity
 * guard): the guard keyed on videoId alone, which cannot tell
 * duplicate copies of one track apart. Copies of the same videoId
 * are NOT always the same [Track] value — a track added from
 * search and the same track added from a playlist page can carry
 * different duration/thumbnail/album fields — so a stale launch
 * replayed after the copies swapped places passed the videoId
 * guard and moved (or deleted) the copy the user never tapped
 * on: in the double-tap shape, the second launch silently UNDID
 * the first move. The full-Track guard compares the row's whole
 * value: metadata-differing copies are told apart, while fully
 * identical copies remain indistinguishable AND interchangeable
 * — swapping two equal values is no observable change, so no
 * stronger guard exists or is needed there.
 */
class R8PlaylistGuardTest {

    private fun tempFile(name: String): File {
        val dir = Files.createTempDirectory("offleash-r8-playlists").toFile()
        return File(dir, name)
    }

    private fun track(id: String, durationSeconds: Int?) = Track(
        videoId = id,
        title = "t-$id",
        artists = listOf("a"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = durationSeconds,
        thumbnailUrl = null,
    )

    /** The same videoId as it arrives from search (no duration). */
    private val searchCopy = track("x", durationSeconds = null)

    /** The same videoId as it arrives from a playlist page. */
    private val playlistCopy = track("x", durationSeconds = 187)

    @Test
    fun doubleTapSecondLaunchMustNotUndoTheMoveByMovingTheOtherCopy() = runBlocking {
        val store = LocalPlaylistStore(tempFile("playlists.json"))
        val playlist = store.createPlaylist("DupMoves")
        store.addTrack(playlist.id, searchCopy)
        store.addTrack(playlist.id, playlistCopy)
        // First tap: the playlist copy moves up over the search copy.
        store.moveTrack(playlist.id, 1, 0, playlistCopy)
        assertEquals(
            listOf(playlistCopy, searchCopy),
            store.playlists.value.first().tracks,
        )
        // The double-tap's second launch carries the same
        // render-time indices (1 -> 0) and expects the playlist
        // copy — but row 1 now holds the SEARCH copy (same
        // videoId, different Track value). Round 7's videoId guard
        // passed this launch and moved the search copy back up,
        // silently undoing the move. The full-Track guard drops it.
        store.moveTrack(playlist.id, 1, 0, playlistCopy)
        assertEquals(
            listOf(playlistCopy, searchCopy),
            store.playlists.value.first().tracks,
        )
    }

    @Test
    fun freshMoveOfTheExactCopyStillLands() = runBlocking {
        val store = LocalPlaylistStore(tempFile("playlists.json"))
        val playlist = store.createPlaylist("DupMovesFresh")
        store.addTrack(playlist.id, searchCopy)
        store.addTrack(playlist.id, track("a", 60))
        store.addTrack(playlist.id, playlistCopy)
        store.moveTrack(playlist.id, 2, 1, playlistCopy)
        assertEquals(
            listOf(searchCopy, playlistCopy, track("a", 60)),
            store.playlists.value.first().tracks,
        )
        // A fresh tap at the playlist copy's settled row moves
        // exactly that copy again — the stronger guard must not
        // weaken the round-7 exactness.
        store.moveTrack(playlist.id, 1, 0, playlistCopy)
        assertEquals(
            listOf(playlistCopy, searchCopy, track("a", 60)),
            store.playlists.value.first().tracks,
        )
    }

    @Test
    fun staleDeleteDropsWhenTheRowNowHoldsTheOtherCopy() = runBlocking {
        val store = LocalPlaylistStore(tempFile("playlists.json"))
        val playlist = store.createPlaylist("DupDeletes")
        store.addTrack(playlist.id, playlistCopy)
        store.addTrack(playlist.id, searchCopy)
        // The copies swap places legitimately (the playlist copy
        // moves down), settling the search copy at index 0.
        store.moveTrack(playlist.id, 0, 1, playlistCopy)
        assertEquals(
            listOf(searchCopy, playlistCopy),
            store.playlists.value.first().tracks,
        )
        // A delete launch captured earlier for the PLAYLIST copy
        // at index 0 now finds the SEARCH copy there — same
        // videoId, different value. The full-Track guard drops
        // the stale delete instead of deleting the wrong copy.
        store.removeTrackAt(playlist.id, 0, playlistCopy)
        assertEquals(
            listOf(searchCopy, playlistCopy),
            store.playlists.value.first().tracks,
        )
        // The exact delete of the copy that DOES stand at index 0
        // still removes exactly that copy.
        store.removeTrackAt(playlist.id, 0, searchCopy)
        assertEquals(
            listOf(playlistCopy),
            store.playlists.value.first().tracks,
        )
    }
}
