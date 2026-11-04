package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The playlist journey, end to end at the store (0.9.0, owner
 * order 2026-11-04): playlists are OffLeash's own system — create
 * one, add tracks to it, play it in order, reorder it, take a
 * track back out, rename it, delete it — with everything surviving
 * a store re-read, because a playlist that forgets itself between
 * launches is not a playlist system. The screen drives exactly
 * these calls (LocalPlaylistScreen: Play all / Shuffle / move /
 * remove), and the queue's play order is the store's row order,
 * pinned here.
 */
class LocalPlaylistJourneyTest {

    private fun tempFile(name: String): File {
        val dir = Files.createTempDirectory("offleash-journey-test").toFile()
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
        thumbnailUrl = "https://img/$id-row.jpg",
        heroThumbnailUrl = "https://img/$id-hero.jpg",
    )

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
    fun createAddPlayOrderReorderRemoveRenameDelete() = runBlocking {
        val file = tempFile("playlists.json")
        val store = LocalPlaylistStore(file)

        // Create.
        val playlist = store.createPlaylist("Road mix")
        assertEquals("Road mix", playlist.name)
        assertTrue(playlist.tracks.isEmpty())

        // Add — duplicates of the same song stay distinct rows.
        store.addTrack(playlist.id, track("a"))
        store.addTrack(playlist.id, track("b"))
        store.addTrack(playlist.id, track("a"))
        var current = store.playlists.value.single()
        assertEquals(listOf("a", "b", "a"), current.tracks.map { it.videoId })

        // Rows carry both artwork references into the playlist.
        assertEquals("https://img/a-row.jpg", current.tracks[0].thumbnailUrl)
        assertEquals("https://img/a-hero.jpg", current.tracks[0].heroThumbnailUrl)

        // Play order is row order: the detail screen hands this
        // exact list to the queue for Play all.
        val playOrder = current.tracks.map { it.videoId }
        assertEquals(listOf("a", "b", "a"), playOrder)

        // Reorder: move the last row to the front (guarded move).
        store.moveTrack(playlist.id, 2, 0)
        current = store.playlists.value.single()
        assertEquals(listOf("a", "a", "b"), current.tracks.map { it.videoId })

        // Remove exactly one of the duplicate rows.
        store.removeTrackAt(playlist.id, 0, track("a"))
        current = store.playlists.value.single()
        assertEquals(listOf("a", "b"), current.tracks.map { it.videoId })

        // Rename.
        store.renamePlaylist(playlist.id, "Night drive")
        current = store.playlists.value.single()
        assertEquals("Night drive", current.name)

        // Everything above survives a re-read from disk.
        val reread = LocalPlaylistStore(file)
        await("playlist reloaded") { reread.playlists.value.isNotEmpty() }
        val persisted = reread.playlists.value.single()
        assertEquals("Night drive", persisted.name)
        assertEquals(listOf("a", "b"), persisted.tracks.map { it.videoId })
        assertEquals(
            "https://img/b-hero.jpg",
            persisted.tracks.first { it.videoId == "b" }.heroThumbnailUrl,
        )

        // Delete — and the deletion persists too.
        store.deletePlaylist(playlist.id)
        assertTrue(store.playlists.value.isEmpty())
        val afterDelete = LocalPlaylistStore(file)
        Thread.sleep(300)
        assertTrue(afterDelete.playlists.value.isEmpty())
    }
}
