package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 0.9.0 — likes are on-device state, full stop (owner order,
 * 2026-11-04: the YouTube sign-in surface is removed "for now";
 * "everything else is us"). The account-era controller this file
 * used to pin — its serialized drain onto the account's Liked page,
 * its seeded provenance, its rate-limit parking — is deleted with
 * the rest of the sign-in machinery; what a like means now is
 * simpler and is pinned here instead:
 *
 *  - a toggle flips the on-device liked state immediately and
 *    persists the id set through preferences;
 *  - the liked-tracks store gains/loses the row in step, so the
 *    Library's Liked songs list and the auto-download feed read
 *    rows, not bare ids;
 *  - nothing is account-backed, ever: the state says so plainly;
 *  - ids an older build seeded from an account read back as plain
 *    on-device likes (the provenance split is retired with the
 *    empty seed set on every toggle).
 */
class LikeControllerTest {

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

    private class Fixture {
        val prefs = AppPreferences(InMemoryKeyValueStore())
        val dir: File = Files.createTempDirectory("offleash-likes-test").toFile()
        val store = LikedTrackStore(File(dir, "liked_tracks.json"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = LikeController(prefs, store, scope)

        fun close() {
            scope.cancel()
        }
    }

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
    fun toggleLikesImmediatelyAndPersistsOnDevice() {
        val f = Fixture()
        try {
            val track = track("v1")
            val before = f.controller.stateFor(track)
            assertFalse(before.liked)
            assertFalse(before.accountBacked)

            f.controller.toggle(track)

            val after = f.controller.stateFor(track)
            assertTrue(after.liked)
            assertFalse(after.accountBacked)
            assertTrue(f.prefs.localLikedVideoIds().contains("v1"))
            await("liked row stored") {
                f.store.tracks.value.map { it.videoId } == listOf("v1")
            }
            // The row carries both artwork references.
            val row = f.store.tracks.value.single()
            assertEquals("https://img/v1-row.jpg", row.thumbnailUrl)
            assertEquals("https://img/v1-hero.jpg", row.heroThumbnailUrl)
        } finally {
            f.close()
        }
    }

    @Test
    fun toggleAgainRemovesTheLikeAndTheRow() {
        val f = Fixture()
        try {
            val track = track("v1")
            f.controller.toggle(track)
            await("liked row stored") { f.store.tracks.value.size == 1 }

            f.controller.toggle(track)

            assertFalse(f.controller.stateFor(track).liked)
            assertFalse(f.prefs.localLikedVideoIds().contains("v1"))
            await("liked row removed") { f.store.tracks.value.isEmpty() }
        } finally {
            f.close()
        }
    }

    @Test
    fun accountSeededIdsReadBackAsPlainLocalLikes() = runBlocking {
        val f = Fixture()
        try {
            // The 0.8.x seed shape: an id marked account-seeded.
            f.prefs.setLikedState(setOf("seeded"), setOf("seeded"))
            val state = f.controller.stateFor(track("seeded"))
            assertTrue(state.liked)
            assertFalse(state.accountBacked)

            // Toggling it off retires the provenance entirely.
            f.controller.toggle(track("seeded"))
            assertFalse(f.prefs.localLikedVideoIds().contains("seeded"))
            assertTrue(f.prefs.seededLikedVideoIds().isEmpty())
        } finally {
            f.close()
        }
    }

    @Test
    fun rememberLikedTrackBackfillsOnlyWhenLiked() {
        val f = Fixture()
        try {
            val liked = track("v9")
            f.controller.toggle(liked)
            await("row stored") { f.store.tracks.value.size == 1 }
            f.controller.toggle(liked) // unlike removes the row
            await("row removed") { f.store.tracks.value.isEmpty() }

            // Unliked: a remember call must not resurrect a row.
            f.controller.rememberLikedTrack(liked)
            Thread.sleep(200)
            assertTrue(f.store.tracks.value.isEmpty())

            // Liked through preferences alone (the legacy id-only
            // record): the remember call backfills the row.
            f.prefs.setLikedState(setOf("v9"), emptySet())
            f.controller.rememberLikedTrack(liked)
            await("row backfilled") {
                f.store.tracks.value.map { it.videoId } == listOf("v9")
            }
        } finally {
            f.close()
        }
    }

    @Test
    fun accountBackedIsNeverTrueAndErrorNeverFires() {
        val f = Fixture()
        try {
            f.controller.toggle(track("v1"))
            assertFalse(f.controller.accountBacked.value)
            assertEquals(null, f.controller.error.value)
        } finally {
            f.close()
        }
    }
}
