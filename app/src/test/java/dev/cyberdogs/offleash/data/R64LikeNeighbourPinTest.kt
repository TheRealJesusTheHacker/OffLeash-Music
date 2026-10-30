package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 64 candidate pins (adversarial lane, charter 3):
 * the neighbours of round 63's two adoption pins.
 */
class R64LikeNeighbourPinTest {

    private val session = AuthSession(cookies = mapOf("SAPISID" to "hash-source"))

    private fun track(videoId: String) = Track(
        videoId = videoId,
        title = "Song $videoId",
        artists = listOf("Artist"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 100,
        thumbnailUrl = null,
    )

    private fun controller(preferences: AppPreferences) = LikeController(
        client = YoutubeiClient(
            HttpTransport { _, _, _ -> HttpResponse(200, "{}") },
        ),
        preferences = preferences,
        sessionProvider = { session },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Test
    fun aSameValueAccountBackedCallIsNotASessionFlip() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        preferences.setLocalLikedVideoIds(setOf("local1"))
        val controller = controller(preferences)
        controller.noteAccountLiked(listOf(track("seed1")))
        assertEquals(setOf("seed1"), preferences.seededLikedVideoIds())

        // The container re-publishes the standing sign-in state
        // (a recomposition, a re-collect, a duplicate callback):
        // the value does not change, so this is not a flip and
        // the standing session's seeds are not the previous
        // session's to drop.
        controller.setAccountBacked(true)
        assertTrue(
            "a same-value setAccountBacked(true) call must not " +
                "drop the standing session's seed",
            controller.isLiked("seed1"),
        )
        assertEquals(setOf("seed1"), preferences.seededLikedVideoIds())
        assertEquals(
            setOf("local1", "seed1"),
            preferences.localLikedVideoIds(),
        )

        // A real flip still drops the seed, as ever.
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("seed1"))
        assertTrue(controller.isLiked("local1"))
    }

    @Test
    fun successiveSeedCallsAccumulateOneSessionsProvenance() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        preferences.setLocalLikedVideoIds(setOf("local1"))
        val controller = controller(preferences)

        // The Liked page can land in more than one fetch (a
        // first page, then its continuation): each call seeds
        // only its own new ids, and the session's provenance
        // is the UNION — a later call never un-seeds an
        // earlier page's tracks.
        controller.noteAccountLiked(listOf(track("pageA")))
        controller.noteAccountLiked(listOf(track("pageB")))
        assertEquals(
            setOf("pageA", "pageB"),
            preferences.seededLikedVideoIds(),
        )

        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("pageA"))
        assertFalse(controller.isLiked("pageB"))
        assertTrue(controller.isLiked("local1"))
        assertEquals(setOf("local1"), preferences.localLikedVideoIds())
    }

    @Test
    fun multipleStaleProvenanceIdsNeverRideAlongIntoALaterPersist() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        // A persisted pair whose provenance names TWO ids the
        // record no longer holds, beside one genuine seed.
        preferences.setLikedState(
            setOf("rec1", "seedReal"),
            setOf("seedReal", "stale1", "stale2"),
        )
        val controller = controller(preferences)
        assertTrue(controller.isLiked("rec1"))
        assertTrue(controller.isLiked("seedReal"))
        assertFalse(controller.isLiked("stale1"))
        assertFalse(controller.isLiked("stale2"))

        controller.noteAccountLiked(listOf(track("new1")))
        assertEquals(
            "neither stale id may ride along into the next " +
                "persisted pair; the genuine seed stands",
            setOf("seedReal", "new1"),
            preferences.seededLikedVideoIds(),
        )
        assertEquals(
            setOf("rec1", "seedReal", "new1"),
            preferences.localLikedVideoIds(),
        )

        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("seedReal"))
        assertFalse(controller.isLiked("new1"))
        assertTrue(controller.isLiked("rec1"))
        assertEquals(setOf("rec1"), preferences.localLikedVideoIds())
    }

    @Test
    fun seedArrivingBeforeAnyLocalEntryThenALocalToggleOfAnotherTrackKeepsBothClassifications() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val controller = controller(preferences)
        // The seed arrives first, before the device has any
        // local entry at all (fresh install, first sign-in).
        controller.noteAccountLiked(listOf(track("seed1")))
        assertEquals(setOf("seed1"), preferences.seededLikedVideoIds())

        // Signing out drops the seed; a device-local like made
        // while signed out is the device's own, and signing
        // back in and re-seeding the SAME page must classify
        // the re-seeded id as the new session's seed again —
        // while the local like is never enrolled (round 63's
        // same-id rule, one history later).
        controller.setAccountBacked(false)
        assertFalse(controller.isLiked("seed1"))
        // The device-local like is written as a pair (the
        // v2 store the reader prefers), exactly as a signed-
        // out toggle would have persisted it.
        preferences.setLikedState(setOf("local1"), emptySet())
        val relaunched = controller(preferences)
        assertTrue(relaunched.isLiked("local1"))
        relaunched.noteAccountLiked(listOf(track("local1"), track("seed1")))
        assertEquals(setOf("seed1"), preferences.seededLikedVideoIds())

        relaunched.setAccountBacked(false)
        relaunched.setAccountBacked(true)
        assertFalse(relaunched.isLiked("seed1"))
        assertTrue(relaunched.isLiked("local1"))
    }
}
