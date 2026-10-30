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
 * Round 63 sweep pins (adversarial lane, charter 3): the
 * ADOPTION side of LikeController's provenance
 * classification. Round 62 pinned the mixed case at
 * sign-out (seed + device-local coexisting at a flip, in
 * DIFFERENT ids). The reverse direction — what sign-in
 * adoption does to entries that already exist — was
 * unpinned in both its forms, each proven by a doctor
 * that ran green across LikeControllerTest + the round-62
 * pin + the Google churn/lifecycle suites this round:
 *
 *  (a) SAME-ID ADOPTION: a device-local like whose id the
 *  account's Liked page ALSO carries. The shipped seed
 *  road never touches an existing entry (round 36's rule:
 *  an entry already present is never overridden), so the
 *  local like keeps its device provenance and survives
 *  the next flip. The doctor added an adoption leg that
 *  enrolled an existing entry into the seeded set (state
 *  untouched) — the local like was then dropped at the
 *  next flip as if it were the account's snapshot, and
 *  no test constructed the same-id seed-then-flip
 *  sequence (round 62's pin holds local and seed in
 *  different ids; the never-overrides seed test never
 *  flips after seeding an existing entry).
 *
 *  (b) THE RESTORE INTERSECT: at construction the
 *  persisted provenance is intersected with the record it
 *  describes (round 38), so a provenance id that is no
 *  longer in the record classifies nothing. The doctor
 *  removed the intersect — the stale id stayed in the
 *  in-memory classification set and RODE ALONG into the
 *  next persist (a seed of an unrelated track wrote the
 *  pair {record, provenance ∪ stale}), reviving dead
 *  provenance on disk; no test had ever constructed a
 *  persisted pair whose provenance names an id outside
 *  the record.
 */
class R63LikeAdoptionPinTest {

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
    fun seedAdoptionNeverReclassifiesADeviceLocalLikeWhoseIdTheAccountAlsoLikes() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        // The device-local like predates sign-in: it is in
        // the record before the controller exists, with no
        // provenance — the device's own like.
        preferences.setLocalLikedVideoIds(setOf("local1"))
        val controller = controller(preferences)
        assertTrue(controller.isLiked("local1"))

        // The account's Liked page carries the SAME id plus
        // one the device has never seen. Adoption must seed
        // only the new id; the coinciding id keeps its
        // device-local provenance.
        controller.noteAccountLiked(listOf(track("local1"), track("seed1")))
        assertEquals(
            "adoption must not enroll the device-local entry " +
                "into the seed provenance — its id coinciding " +
                "with an account like does not make it the " +
                "account's snapshot",
            setOf("seed1"),
            preferences.seededLikedVideoIds(),
        )
        assertEquals(
            setOf("local1", "seed1"),
            preferences.localLikedVideoIds(),
        )

        controller.setAccountBacked(false) // sign-out
        controller.setAccountBacked(true) // a different account signs in

        assertFalse(
            "the adopted seed drops at the flip",
            controller.isLiked("seed1"),
        )
        assertTrue(
            "the device-local like whose id coincided with " +
                "the account's page must survive the flip — " +
                "the user created it on this device, signed out",
            controller.isLiked("local1"),
        )
        assertEquals(setOf("local1"), preferences.localLikedVideoIds())
        assertEquals(emptySet<String>(), preferences.seededLikedVideoIds())
    }

    @Test
    fun stalePersistedProvenanceNeverRidesAlongIntoALaterPersist() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        // A persisted pair whose provenance names an id the
        // record no longer holds — written by an older
        // build, a damaged store, or a foreign writer.
        preferences.setLikedState(setOf("rec1"), setOf("stale1"))
        val controller = controller(preferences)
        assertTrue(controller.isLiked("rec1"))
        assertFalse(controller.isLiked("stale1"))

        // An unrelated seed forces a persist of the pair.
        // The restored classification is the intersect, so
        // the stale id must not be written back to disk.
        controller.noteAccountLiked(listOf(track("new1")))
        assertEquals(
            "the stale provenance id describes no record " +
                "entry and must not ride along into the next " +
                "persisted pair",
            setOf("new1"),
            preferences.seededLikedVideoIds(),
        )
        assertEquals(
            setOf("rec1", "new1"),
            preferences.localLikedVideoIds(),
        )

        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("new1"))
        assertTrue(
            "the record's own entry stands through the flip",
            controller.isLiked("rec1"),
        )
        assertEquals(setOf("rec1"), preferences.localLikedVideoIds())
        assertEquals(emptySet<String>(), preferences.seededLikedVideoIds())
    }
}
