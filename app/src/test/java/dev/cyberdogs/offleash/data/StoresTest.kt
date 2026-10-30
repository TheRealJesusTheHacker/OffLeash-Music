package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Store corruption tolerance and growth caps (bug sweep round 1).
 * The pre-sweep decode was whole-document: ONE malformed entry in
 * recents.json / playlists.json read back as "nothing stored at
 * all". These tests pin per-entry salvage, the honest-empty read
 * for a wholly unreadable file, and the recents cap.
 */
class StoresTest {

    private fun tempFile(name: String): File {
        val dir = Files.createTempDirectory("offleash-stores-test").toFile()
        return File(dir, name)
    }

    private fun track(id: String) = Track(
        videoId = id,
        title = "t-$id",
        artists = listOf("a"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 60,
        thumbnailUrl = null,
    )

    private fun trackJson(id: String): String =
        """{"videoId": "$id", "title": "t-$id", "artists": ["a"], """ +
            """"artistBrowseId": null, "album": null, "albumBrowseId": null, """ +
            """"durationSeconds": 60, "thumbnailUrl": null}"""

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for: $label")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun recentsSalvageGoodEntriesAroundABadOne() {
        val file = tempFile("recents.json")
        file.writeText(
            """{"tracks": [${trackJson("a")}, {"videoId": 42, "title": true}, ${trackJson("b")}]}""",
        )
        val store = RecentsStore(file)
        await("recents loaded") { store.recents().value.isNotEmpty() }
        assertEquals(listOf("a", "b"), store.recents().value.map { it.videoId })
    }

    @Test
    fun recentsWhollyCorruptFileReadsAsEmpty() {
        val file = tempFile("recents.json")
        file.writeText("this is not json at all {{{")
        val store = RecentsStore(file)
        // Give the async load its chance; the state must stay empty.
        Thread.sleep(300)
        assertEquals(emptyList<Track>(), store.recents().value)
    }

    @Test
    fun recentsAreCappedAtFiftyMostRecent() = runBlocking {
        val file = tempFile("recents.json")
        val store = RecentsStore(file)
        for (i in 1..55) {
            store.recordPlayed(track("v$i"))
        }
        val recents = store.recents().value
        assertEquals(RecentsStore.MAX_RECENTS, recents.size)
        assertEquals("v55", recents.first().videoId)
        assertEquals("v6", recents.last().videoId)
        // Re-recording an existing id dedupes instead of duplicating.
        store.recordPlayed(track("v55"))
        assertEquals(RecentsStore.MAX_RECENTS, store.recents().value.size)
        assertEquals(1, store.recents().value.count { it.videoId == "v55" })
    }

    @Test
    fun playlistsSalvageGoodEntriesAroundABadOne() {
        val file = tempFile("playlists.json")
        file.writeText(
            """{"playlists": [
                {"id": "p1", "name": "Good", "createdAtEpochMs": 1, "tracks": [${trackJson("a")}]},
                {"id": 99, "name": false},
                {"id": "p2", "name": "Also good", "createdAtEpochMs": 2, "tracks": []}
            ]}""",
        )
        val store = LocalPlaylistStore(file)
        await("playlists loaded") { store.playlists.value.isNotEmpty() }
        assertEquals(listOf("p1", "p2"), store.playlists.value.map { it.id })
        assertEquals(listOf("a"), store.playlists.value.first().tracks.map { it.videoId })
    }

    @Test
    fun playlistWithOneBadTrackKeepsItsGoodTracks() {
        val file = tempFile("playlists.json")
        file.writeText(
            """{"playlists": [
                {"id": "p1", "name": "Mixed", "createdAtEpochMs": 1,
                 "tracks": [${trackJson("a")}, {"videoId": null}, ${trackJson("b")}]}
            ]}""",
        )
        val store = LocalPlaylistStore(file)
        await("playlists loaded") { store.playlists.value.isNotEmpty() }
        assertEquals(
            listOf("a", "b"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
    }

    @Test
    fun removingOneDuplicatePlaylistRowKeepsTheOtherCopy() = runBlocking {
        // Round 5: the playlist screen deliberately supports the
        // same track twice (index-keyed rows since round 4), but
        // its delete button removed by videoId — deleting one
        // duplicate row deleted EVERY copy. Row removal must be
        // exact: removeTrackAt takes the row's index.
        val file = tempFile("playlists.json")
        val store = LocalPlaylistStore(file)
        val playlist = store.createPlaylist("Dups")
        store.addTrack(playlist.id, track("a"))
        store.addTrack(playlist.id, track("b"))
        store.addTrack(playlist.id, track("a"))
        store.removeTrackAt(playlist.id, 2)
        assertEquals(
            listOf("a", "b"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
        store.removeTrackAt(playlist.id, 0)
        assertEquals(
            listOf("b"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
        // An out-of-range index is a no-op, never a crash.
        store.removeTrackAt(playlist.id, 5)
        assertEquals(
            listOf("b"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
    }

    @Test
    fun staleRowRemovalDropsInsteadOfDeletingTheWrongTrack() = runBlocking {
        // Round 6: the row index is captured at render time and the
        // removal runs async. Here the list shifts first (row a is
        // removed by an earlier action), so the delete tapped for
        // b — captured as index 1 — now points at c. The unguarded
        // round-5 call deleted c, a track the user never tapped
        // Delete on; the identity-guarded call must drop the stale
        // tap and leave both remaining tracks in place.
        val file = tempFile("playlists.json")
        val store = LocalPlaylistStore(file)
        val playlist = store.createPlaylist("Shifted")
        store.addTrack(playlist.id, track("a"))
        store.addTrack(playlist.id, track("b"))
        store.addTrack(playlist.id, track("c"))
        store.removeTrackAt(playlist.id, 0, "a")
        assertEquals(
            listOf("b", "c"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
        // The stale tap for b, carrying its render-time index:
        store.removeTrackAt(playlist.id, 1, "b")
        assertEquals(
            listOf("b", "c"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
        // A fresh tap at b's settled index still removes exactly b.
        store.removeTrackAt(playlist.id, 0, "b")
        assertEquals(
            listOf("c"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
    }

    @Test
    fun guardedRowRemovalStillRemovesExactlyOneDuplicateCopy() = runBlocking {
        // Round 6: the guard must not weaken the round-5 exactness —
        // when the row at the index still holds the expected track,
        // one duplicate copy goes and the other stays.
        val file = tempFile("playlists.json")
        val store = LocalPlaylistStore(file)
        val playlist = store.createPlaylist("Dups2")
        store.addTrack(playlist.id, track("a"))
        store.addTrack(playlist.id, track("b"))
        store.addTrack(playlist.id, track("a"))
        store.removeTrackAt(playlist.id, 2, "a")
        assertEquals(
            listOf("a", "b"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
        // The removal persisted the right document.
        val reloaded = LocalPlaylistStore(file)
        await("reloaded") { reloaded.playlists.value.isNotEmpty() }
        assertEquals(
            listOf("a", "b"),
            reloaded.playlists.value.first().tracks.map { it.videoId },
        )
    }

    @Test
    fun staleRowMoveDropsInsteadOfMovingTheWrongTrack() = runBlocking {
        // Round 7: the move buttons captured bare render-time
        // indices with no identity guard — the round-6 guard's
        // sibling that never got one. A double-tap on Move-up (two
        // launched moves carrying the same indices) moved the
        // tapped track once, then moved whatever slid into its old
        // row a second time. The guarded move must drop the stale
        // second tap.
        val file = tempFile("playlists.json")
        val store = LocalPlaylistStore(file)
        val playlist = store.createPlaylist("Moves")
        store.addTrack(playlist.id, track("a"))
        store.addTrack(playlist.id, track("b"))
        store.addTrack(playlist.id, track("c"))
        // First tap: c moves up over b — the intended move.
        store.moveTrack(playlist.id, 2, 1, "c")
        assertEquals(
            listOf("a", "c", "b"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
        // The double-tap's second launch, still carrying c's
        // render-time indices (2 -> 1): row 2 now holds b, a track
        // the user never tapped Move on. It must be a no-op.
        store.moveTrack(playlist.id, 2, 1, "c")
        assertEquals(
            listOf("a", "c", "b"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
        // A fresh tap at c's settled row still moves exactly c.
        store.moveTrack(playlist.id, 1, 0, "c")
        assertEquals(
            listOf("c", "a", "b"),
            store.playlists.value.first().tracks.map { it.videoId },
        )
    }

    @Test
    fun playlistsWhollyCorruptFileReadsAsEmptyAndStaysUsable() = runBlocking {
        val file = tempFile("playlists.json")
        file.writeText("<html>not a store file</html>")
        val store = LocalPlaylistStore(file)
        Thread.sleep(300)
        assertTrue(store.playlists.value.isEmpty())
        val created = store.createPlaylist("Fresh")
        assertEquals(listOf(created.id), store.playlists.value.map { it.id })
    }
}
