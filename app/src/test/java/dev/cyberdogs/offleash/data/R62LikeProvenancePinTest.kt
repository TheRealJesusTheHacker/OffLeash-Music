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
 * Round 62 sweep pin (adversarial lane, charter 3b): the
 * LikeController provenance classification at a session flip
 * was runtime-pinned only in its PURE cases — a seed alone
 * drops (round 37/38 tests), a device-local entry alone
 * survives (deviceLocalEntrySurvivesASessionFlip, whose flip
 * never opens the drop block because the provenance set is
 * empty). The MIXED case — a seeded entry and a device-local
 * entry coexisting when the flip lands — was constructed by
 * no test: a doctor that dropped EVERY state entry whenever
 * the provenance set was non-empty (the local entry
 * misclassified as seeded) ran GREEN across LikeControllerTest,
 * because every existing flip test holds only one class of
 * entry at a time. This pin holds both classes at one flip:
 * the seed must drop and the device-local entry must stand,
 * in the presented state and in the persisted pair alike.
 */
class R62LikeProvenancePinTest {

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

    @Test
    fun sessionFlipDropsOnlyTheSeededEntryWhenALocalEntryCoexists() {
        val transport = HttpTransport { _, _, _ -> HttpResponse(200, "{}") }
        val preferences = AppPreferences(InMemoryKeyValueStore())
        // The device-local like is in the record BEFORE the
        // controller exists: it loads with no provenance, as
        // the device's own like.
        preferences.setLocalLikedVideoIds(setOf("local1"))
        val controller = LikeController(
            client = YoutubeiClient(transport),
            preferences = preferences,
            sessionProvider = { session },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        assertTrue(controller.isLiked("local1"))

        // The account's Liked page seeds a second track: both
        // classes now coexist in the presented state and in
        // the persisted pair.
        controller.noteAccountLiked(listOf(track("seed1")))
        assertTrue(controller.isLiked("seed1"))
        assertEquals(setOf("local1", "seed1"), preferences.localLikedVideoIds())
        assertEquals(setOf("seed1"), preferences.seededLikedVideoIds())

        controller.setAccountBacked(false) // sign-out
        controller.setAccountBacked(true) // a different account signs in

        assertFalse(
            "the seeded entry is the previous account's page snapshot and must drop",
            controller.isLiked("seed1"),
        )
        assertTrue(
            "the device-local entry is the device's own like and must survive the flip",
            controller.isLiked("local1"),
        )
        assertEquals(setOf("local1"), preferences.localLikedVideoIds())
        assertEquals(emptySet<String>(), preferences.seededLikedVideoIds())
    }
}
