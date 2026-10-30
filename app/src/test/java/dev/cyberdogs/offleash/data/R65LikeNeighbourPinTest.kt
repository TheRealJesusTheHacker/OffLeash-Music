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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 65 pins (adversarial lane, charter 3): round 64's
 * adoption neighbours, round 3 — plus the bearer-road
 * (Google sign-in) legs of the seed, the toggle and the
 * refresh, which no runtime test had ever exercised.
 *
 * The bearer pins close a GREEN hole: in the shipped
 * container the cookie sessionProvider is null for a
 * Google-only user, so the `bearerSessionPresent()` leg of
 * each guard is the ONLY road the primary sign-in has —
 * and dropping that leg from `noteAccountLiked`, from
 * `toggle`'s device-only branch, or from
 * `refreshAccountStatus` (three separate doctors) ran
 * green across the full 667-test suite, because every
 * runtime LikeController test drives the cookie road.
 * Each doctor now fails exactly its own pin below.
 *
 * The remaining four pins are the round-3 neighbours of
 * round 64's pins: a third seed call (plus an overlap
 * re-list), a same-value re-publish after a RESTORE, a
 * local entry adopted in-session and then listed by a
 * seed, and a session flip landing while a seed-takeover
 * drain is in flight. Their doctors fail them together
 * with the older pins they neighbour (named in the lane
 * report).
 */
class R65LikeNeighbourPinTest {

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

    private class RoadTransport(
        var likeStatus: String = "INDIFFERENT",
    ) : HttpTransport {
        val writes = CopyOnWriteArrayList<Boolean>()
        @Volatile var gate: CountDownLatch? = null
        @Volatile var writeStarted: CountDownLatch? = null

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse {
            return when {
                url.contains("like/like") || url.contains("like/removelike") -> {
                    writes.add(url.contains("like/like"))
                    writeStarted?.countDown()
                    gate?.await(10, TimeUnit.SECONDS)
                    HttpResponse(200, "{}")
                }
                url.contains("player") -> HttpResponse(
                    200,
                    """{"likeStatus": "$likeStatus"}""",
                )
                else -> HttpResponse(200, "{}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A controller on the Google road only: no cookie session. */
    private fun bearerOnlyController(
        transport: RoadTransport,
        preferences: AppPreferences,
    ) = LikeController(
        client = YoutubeiClient(transport),
        preferences = preferences,
        sessionProvider = { null },
        bearerProvider = { "google-access-token" },
        bearerSessionPresent = { true },
        scope = scope,
    )

    private fun cookieController(preferences: AppPreferences) = LikeController(
        client = YoutubeiClient(
            HttpTransport { _, _, _ -> HttpResponse(200, "{}") },
        ),
        preferences = preferences,
        sessionProvider = { session },
        scope = scope,
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

    // -- The bearer road (Google sign-in) ---------------------------

    @Test
    fun bearerRoadSeedClassifiesAndDropsAtAFlipLikeTheCookieRoad() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val controller = bearerOnlyController(RoadTransport(), preferences)
        controller.setAccountBacked(true) // the container's Google sign-in flip
        controller.noteAccountLiked(listOf(track("seed1")))
        assertTrue(
            "a Liked-page seed must classify on the bearer road " +
                "too — it is the only road a Google-only session has",
            controller.isLiked("seed1"),
        )
        assertEquals(setOf("seed1"), preferences.seededLikedVideoIds())
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("seed1"))
        assertEquals(emptySet<String>(), preferences.localLikedVideoIds())
    }

    @Test
    fun bearerRoadToggleWritesToTheAccountNotJustTheDevice() {
        val transport = RoadTransport()
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val controller = bearerOnlyController(transport, preferences)
        controller.setAccountBacked(true)
        controller.toggle(track("vid1"))
        await("account write recorded") {
            transport.writes.toList() == listOf(true)
        }
        await("settled") { !controller.stateFor("vid1").pending }
        assertTrue(controller.isLiked("vid1"))
    }

    @Test
    fun bearerRoadRefreshAppliesTheAccountsReport() {
        val transport = RoadTransport(likeStatus = "LIKE")
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val controller = bearerOnlyController(transport, preferences)
        controller.setAccountBacked(true)
        controller.refreshAccountStatus(track("vid1"))
        await("refresh applied") { controller.isLiked("vid1") }
        assertTrue(controller.stateFor("vid1").accountBacked)
    }

    // -- Round 3 neighbours of the round-64 pins --------------------

    @Test
    fun thirdSeedCallExtendsTheUnionAndAnOverlapCallChangesNothing() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        preferences.setLocalLikedVideoIds(setOf("local1"))
        val controller = cookieController(preferences)
        controller.noteAccountLiked(listOf(track("pageA")))
        controller.noteAccountLiked(listOf(track("pageB")))
        // A THIRD call, and a fourth that re-lists the first
        // page (its ids already hold entries, so it is a no-op):
        // the session's provenance is the union of the three
        // pages, nothing re-seeded, nothing dropped early.
        controller.noteAccountLiked(listOf(track("pageC")))
        controller.noteAccountLiked(listOf(track("pageA")))
        assertEquals(
            setOf("pageA", "pageB", "pageC"),
            preferences.seededLikedVideoIds(),
        )
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("pageA"))
        assertFalse(controller.isLiked("pageB"))
        assertFalse(controller.isLiked("pageC"))
        assertTrue(controller.isLiked("local1"))
    }

    @Test
    fun sameValueRepublishAfterARestoreKeepsTheRestoredProvenance() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        // A persisted pair from a previous process: one genuine
        // seed beside a device-local entry.
        preferences.setLikedState(setOf("rec1", "seedReal"), setOf("seedReal"))
        val controller = cookieController(preferences)
        // The container re-publishes the standing state at
        // start-up: same value, not a flip — the RESTORED
        // provenance must stand exactly as an in-session one.
        controller.setAccountBacked(true)
        assertEquals(setOf("seedReal"), preferences.seededLikedVideoIds())
        assertTrue(controller.isLiked("seedReal"))
        // A real flip still drops the restored seed.
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("seedReal"))
        assertTrue(controller.isLiked("rec1"))
    }

    @Test
    fun localEntryAdoptedInSessionIsNeverEnrolledByALaterSeed() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        var currentSession: AuthSession? = null
        val controller = LikeController(
            client = YoutubeiClient(
                HttpTransport { _, _, _ -> HttpResponse(200, "{}") },
            ),
            preferences = preferences,
            sessionProvider = { currentSession },
            scope = scope,
        )
        // Signed out, the user likes a track on-device.
        controller.toggle(track("local1"))
        assertTrue(controller.isLiked("local1"))
        // The session arrives and the container flips; the
        // Liked page then lands, listing the SAME track beside
        // a new one. The in-session local entry must not be
        // enrolled as a seed (round 63's rule, one history
        // later: the local entry was made in THIS session of
        // the controller, not restored from a previous one).
        currentSession = session
        controller.setAccountBacked(true)
        controller.noteAccountLiked(listOf(track("local1"), track("seed1")))
        assertEquals(setOf("seed1"), preferences.seededLikedVideoIds())
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("seed1"))
        assertTrue(controller.isLiked("local1"))
    }

    @Test
    fun flipLandingWhileASeedTakeoverDrainIsInFlightKeepsTheTakenOverEntry() {
        val transport = RoadTransport()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        transport.gate = gate
        transport.writeStarted = started
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val controller = LikeController(
            client = YoutubeiClient(transport),
            preferences = preferences,
            sessionProvider = { session },
            scope = scope,
        )
        controller.noteAccountLiked(listOf(track("vid1")))
        assertEquals(setOf("vid1"), preferences.seededLikedVideoIds())
        // The user unlikes the seeded track: the toggle takes
        // over its provenance BEFORE its drain writes, and the
        // drain is genuinely in flight (gated) when the session
        // flip lands. The flip may drop seeds — this entry is
        // no longer one — and the drain's completion persist
        // must not resurrect its provenance either.
        controller.toggle(track("vid1"))
        assertTrue(
            "drain write in flight",
            started.await(10, TimeUnit.SECONDS),
        )
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        gate.countDown()
        await("unlike written") { transport.writes.toList() == listOf(false) }
        await("settled") { !controller.stateFor("vid1").pending }
        assertEquals(emptySet<String>(), preferences.seededLikedVideoIds())
        // The taken-over (unliked) entry is the user's intent:
        // a later flip neither drops nor revives it.
        controller.toggle(track("vid1"))
        await("like written") {
            transport.writes.toList() == listOf(false, true)
        }
        await("settled") { !controller.stateFor("vid1").pending }
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertTrue(controller.isLiked("vid1"))
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
        assertEquals(emptySet<String>(), preferences.seededLikedVideoIds())
    }
}
