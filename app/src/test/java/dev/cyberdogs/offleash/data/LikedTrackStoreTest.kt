package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The liked-tracks store (0.9.0): the rows behind the Library's
 * Liked songs list, kept under the same discipline as recents and
 * playlists — one JSON document, per-entry salvage on a damaged
 * file, atomic writes, mutations serialized. A liked id alone is
 * not a row a screen can show; this store is what makes the local
 * like presentable (title, artists, and BOTH artwork references).
 */
class LikedTrackStoreTest {

    private fun tempFile(name: String): File {
        val dir = Files.createTempDirectory("offleash-liked-test").toFile()
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
    fun recordListIsMostRecentFirstAndDedupesByReplacing() = runBlocking {
        val store = LikedTrackStore(tempFile("liked_tracks.json"))
        store.recordLiked(track("a"))
        store.recordLiked(track("b"))
        store.recordLiked(track("a").copy(title = "t-a-new"))
        val rows = store.tracks.value
        assertEquals(listOf("a", "b"), rows.map { it.videoId })
        assertEquals("t-a-new", rows.first().title)
    }

    @Test
    fun removeDropsOnlyThatRow() = runBlocking {
        val store = LikedTrackStore(tempFile("liked_tracks.json"))
        store.recordLiked(track("a"))
        store.recordLiked(track("b"))
        store.removeLiked("a")
        assertEquals(listOf("b"), store.tracks.value.map { it.videoId })
        store.removeLiked("missing")
        assertEquals(listOf("b"), store.tracks.value.map { it.videoId })
    }

    @Test
    fun rowsPersistAcrossStoreInstancesWithBothArtworkReferences() = runBlocking {
        val file = tempFile("liked_tracks.json")
        val first = LikedTrackStore(file)
        first.recordLiked(track("a"))
        val second = LikedTrackStore(file)
        await("rows reloaded") { second.tracks.value.isNotEmpty() }
        val row = second.tracks.value.single()
        assertEquals("a", row.videoId)
        assertEquals("https://img/a-row.jpg", row.thumbnailUrl)
        assertEquals("https://img/a-hero.jpg", row.heroThumbnailUrl)
    }

    @Test
    fun damagedEntriesAreSalvagedPerEntry() {
        val file = tempFile("liked_tracks.json")
        file.writeText(
            """{"tracks": [{"videoId": "a", "title": "t-a", "artists": ["a"], """ +
                """"artistBrowseId": null, "album": null, "albumBrowseId": null, """ +
                """"durationSeconds": 60, "thumbnailUrl": "https://img/a-row.jpg", """ +
                """"heroThumbnailUrl": "https://img/a-hero.jpg"}, {"videoId": 42}, """ +
                """{"videoId": "b", "title": "t-b", "artists": ["a"], "artistBrowseId": null, """ +
                """"album": null, "albumBrowseId": null, "durationSeconds": 60, """ +
                """"thumbnailUrl": null}]}""",
        )
        val store = LikedTrackStore(file)
        await("salvaged rows loaded") { store.tracks.value.isNotEmpty() }
        assertEquals(listOf("a", "b"), store.tracks.value.map { it.videoId })
        assertEquals("https://img/a-hero.jpg", store.tracks.value.first().heroThumbnailUrl)
    }

    @Test
    fun legacyRowsWithoutAHeroReferenceDecodeWithItAbsent() {
        val file = tempFile("liked_tracks.json")
        file.writeText(
            """{"tracks": [{"videoId": "old", "title": "t-old", "artists": ["a"], """ +
                """"artistBrowseId": null, "album": null, "albumBrowseId": null, """ +
                """"durationSeconds": 60, "thumbnailUrl": "https://img/old-row.jpg"}]}""",
        )
        val store = LikedTrackStore(file)
        await("legacy row loaded") { store.tracks.value.isNotEmpty() }
        val row = store.tracks.value.single()
        assertEquals("https://img/old-row.jpg", row.thumbnailUrl)
        assertEquals(null, row.heroThumbnailUrl)
        assertTrue(file.exists())
    }
}
