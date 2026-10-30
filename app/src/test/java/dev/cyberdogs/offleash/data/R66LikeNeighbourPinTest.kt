package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
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
 * Round 66 pins (adversarial lane, charter 2): round 4 of
 * the adoption neighbours, on round 65's bearer pins
 * themselves — the bearer road in the shapes round 65 did
 * not construct: a bearer session that is PRESENT but
 * whose token is unavailable (expired with a failed
 * refresh, or revoked in the window before the container
 * flips the state), adoption under bearer-only, and a
 * session flip landing while a bearer-road seed-takeover
 * drain is in flight.
 *
 * The charter-2 census behind them: every other
 * session-guard leg in the data layer was doctored and is
 * pinned by a named test (LikeController's drain and
 * refresh fetch legs fall to round 65's pins and to
 * LikeControllerTest; the dormancy function's three legs
 * fall to LegacyDormancyTest; the worker's and the
 * container's gates are pinned verbatim — see
 * data/R66SweepContractTest). These five pins pass on the
 * shipped tree and hold the bearer road's stale-token and
 * adoption shapes against regression.
 */
class R66LikeNeighbourPinTest {

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
        val playerCalls = CopyOnWriteArrayList<String>()
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
                url.contains("player") -> {
                    playerCalls.add(url)
                    HttpResponse(200, """{"likeStatus": "$likeStatus"}""")
                }
                else -> HttpResponse(200, "{}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for: $label")
            }
            Thread.sleep(10)
        }
    }

    /** Bearer session present, but its token never materializes. */
    private fun staleBearerController(
        transport: RoadTransport,
        preferences: AppPreferences,
    ) = LikeController(
        client = YoutubeiClient(transport),
        preferences = preferences,
        sessionProvider = { null },
        bearerProvider = { null },
        bearerSessionPresent = { true },
        scope = scope,
    )

    @Test
    fun bearerPresentButTokenUnavailableSeedStillClassifies() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val controller = staleBearerController(RoadTransport(), preferences)
        controller.setAccountBacked(true)
        // The seed guard consults session PRESENCE, not the
        // token: a stored Google session whose token is
        // mid-refresh still owns its Liked page.
        controller.noteAccountLiked(listOf(track("seed1")))
        assertTrue(controller.isLiked("seed1"))
        assertEquals(setOf("seed1"), preferences.seededLikedVideoIds())
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("seed1"))
    }

    @Test
    fun bearerPresentButTokenUnavailableToggleSettlesAsADeviceOnlyOutcome() {
        val transport = RoadTransport()
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val controller = staleBearerController(transport, preferences)
        controller.setAccountBacked(true)
        controller.toggle(track("vid1"))
        await("toggle settled") { !controller.stateFor("vid1").pending }
        assertTrue(
            "with the token unavailable the drain takes the " +
                "session-ended outcome: the like stands on-device",
            controller.isLiked("vid1"),
        )
        assertEquals(
            "no account write may be attempted without a token",
            emptyList<Boolean>(),
            transport.writes.toList(),
        )
        assertEquals(setOf("vid1"), preferences.localLikedVideoIds())
    }

    @Test
    fun bearerPresentButTokenUnavailableRefreshAppliesNothing() {
        val transport = RoadTransport(likeStatus = "LIKE")
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val controller = staleBearerController(transport, preferences)
        controller.setAccountBacked(true)
        controller.refreshAccountStatus(track("vid1"))
        // The fetch's two roads are both closed (no token, no
        // cookie session), so the report can never even be
        // fetched: give the launched coroutine its window,
        // then the state must be exactly untouched.
        Thread.sleep(700)
        assertEquals(
            "no player fetch may go out without a credential",
            emptyList<String>(),
            transport.playerCalls.toList(),
        )
        assertFalse(controller.isLiked("vid1"))
    }

    @Test
    fun adoptionUnderBearerOnlyKeepsTheLocalEntryOutOfTheSeed() {
        val preferences = AppPreferences(InMemoryKeyValueStore())
        var bearerPresent = false
        var bearer: String? = null
        val controller = LikeController(
            client = YoutubeiClient(
                HttpTransport { _, _, _ -> HttpResponse(200, "{}") },
            ),
            preferences = preferences,
            sessionProvider = { null },
            bearerProvider = { bearer },
            bearerSessionPresent = { bearerPresent },
            scope = scope,
        )
        // Signed out on every road, the user likes a track
        // on-device (round 65 ran this adoption on the cookie
        // road; this is its bearer twin).
        controller.toggle(track("local1"))
        assertTrue(controller.isLiked("local1"))
        // The Google session lands; the Liked page then lists
        // the SAME track beside a new one. The in-session
        // local entry must not be enrolled as a seed.
        bearerPresent = true
        bearer = "google-access-token"
        controller.setAccountBacked(true)
        controller.noteAccountLiked(listOf(track("local1"), track("seed1")))
        assertEquals(setOf("seed1"), preferences.seededLikedVideoIds())
        controller.setAccountBacked(false)
        controller.setAccountBacked(true)
        assertFalse(controller.isLiked("seed1"))
        assertTrue(controller.isLiked("local1"))
    }

    @Test
    fun flipLandingWhileABearerSeedTakeoverDrainIsInFlightKeepsTheTakenOverEntry() {
        val transport = RoadTransport()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        transport.gate = gate
        transport.writeStarted = started
        val preferences = AppPreferences(InMemoryKeyValueStore())
        val controller = LikeController(
            client = YoutubeiClient(transport),
            preferences = preferences,
            sessionProvider = { null },
            bearerProvider = { "google-access-token" },
            bearerSessionPresent = { true },
            scope = scope,
        )
        controller.setAccountBacked(true)
        controller.noteAccountLiked(listOf(track("vid1")))
        assertEquals(setOf("vid1"), preferences.seededLikedVideoIds())
        // Bearer twin of round 65's cookie-road interleave:
        // the unlike takes over the seed's provenance, its
        // drain is genuinely in flight (gated) when the flip
        // lands, and the taken-over entry must survive both
        // the flip and the drain's completion persist.
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
