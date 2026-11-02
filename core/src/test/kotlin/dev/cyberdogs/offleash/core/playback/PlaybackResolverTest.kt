package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.RoutingTransport
import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.api.YoutubeiContexts
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.error.AllRoadsRefused
import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.NoPlayableStream
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.error.PlayerRefused
import dev.cyberdogs.offleash.core.error.isAuthRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Road-switching (Phase 2, as amended): account road first when signed
 * in, the anonymous road as an ordered context cascade
 * (IOS, then ANDROID) behind it.
 *
 * The routing fake keys on the request body's exact quoted clientName —
 * `"clientName":"ANDROID"` does not match an ANDROID_VR-style body
 * (`"clientName":"ANDROID_VR"`), because the closing quote is part of
 * the needle.
 *
 * The `derived_*.json` player fixtures are DERIVED-SYNTHETIC (hand-built
 * to the documented player shape; no real account capture exists). The
 * ANDROID one's best format mirrors the 2026-10-23 probe: itag 251 at
 * 139055 bps.
 */
class PlaybackResolverTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap", "SID" to "sid"),
    )

    private val preconditionBody =
        """{"error":{"code":400,"message":"Precondition check failed.","status":"FAILED_PRECONDITION"}}"""

    /** A client whose player answers are fixed per context. */
    private fun routingClient(
        webRemix: HttpResponse,
        ios: HttpResponse,
        android: HttpResponse,
    ): YoutubeiClient {
        val transport = RoutingTransport { _, _, body ->
            val b = body ?: ""
            when {
                b.contains("\"clientName\":\"WEB_REMIX\"") -> webRemix
                b.contains("\"clientName\":\"ANDROID\"") -> android
                b.contains("\"clientName\":\"IOS\"") -> ios
                else -> HttpResponse(500, "{}")
            }
        }
        return YoutubeiClient(transport)
    }

    private fun ok(fixture: String) = HttpResponse(200, Fixtures.load(fixture))

    // ------------------------------------------------------------------
    // Signed out: the anonymous cascade, Phase 1 semantics on failure
    // ------------------------------------------------------------------

    @Test
    fun signedOutServedByIosFirst() {
        val client = routingClient(
            webRemix = HttpResponse(500, "{}"),
            ios = ok("player_ios.json"),
            android = ok("derived_player_android.json"),
        )
        val resolved = PlaybackResolver(client).resolve("dQw4w9WgXcQ", null)
        assertEquals("anonymous", resolved.servedByRoad)
        assertEquals(
            listOf(RoadAttempt("anonymous", "served:IOS")),
            resolved.attempts
        )
        assertEquals(136544, resolved.info.bitrate) // iOS fixture's itag 251
    }

    @Test
    fun signedOutIosRefusedAndroidServes() {
        val client = routingClient(
            webRemix = HttpResponse(500, "{}"),
            ios = ok("player_unplayable.json"),
            android = ok("derived_player_android.json"),
        )
        val resolved = PlaybackResolver(client).resolve("pYAg_d-iQLk", null)
        assertEquals("anonymous", resolved.servedByRoad)
        assertEquals(
            listOf(
                RoadAttempt("anonymous", "PlayerRefused:IOS"),
                RoadAttempt("anonymous", "served:ANDROID"),
            ),
            resolved.attempts
        )
        assertEquals(139055, resolved.info.bitrate) // probe-shaped itag 251
        assertEquals("https://example.com/android251.opus", resolved.info.url)
    }

    @Test
    fun signedOutBothRefusedRethrowsFirstContextsException() {
        val client = routingClient(
            webRemix = HttpResponse(500, "{}"),
            ios = ok("player_unplayable.json"), // PlayerRefused (iOS, first)
            android = HttpResponse(400, preconditionBody), // ContextRejected
        )
        try {
            PlaybackResolver(client).resolve("pYAg_d-iQLk", null)
            fail("expected PlayerRefused")
        } catch (e: PlayerRefused) {
            // The FIRST context's exception surfaces, not the ANDROID one.
            assertEquals("Video unavailable", e.reason)
        }
    }

    // ------------------------------------------------------------------
    // Signed in: account road, then the anonymous cascade
    // ------------------------------------------------------------------

    @Test
    fun signedInAccountRoadServes() {
        val client = routingClient(
            webRemix = ok("derived_player_webremix.json"),
            ios = ok("player_ios.json"),
            android = ok("derived_player_android.json"),
        )
        val resolved = PlaybackResolver(client).resolve("dQw4w9WgXcQ", session)
        assertEquals("account", resolved.servedByRoad)
        assertEquals(listOf(RoadAttempt("account", "served")), resolved.attempts)
        // The derived WEB_REMIX body's itag-774-shaped entry wins.
        assertEquals(256000, resolved.info.bitrate)
        assertEquals("https://example.com/audio774.opus", resolved.info.url)
    }

    @Test
    fun signedInAccountRefusedFallsBackToIos() {
        val client = routingClient(
            webRemix = ok("player_unplayable.json"),
            ios = ok("player_ios.json"),
            android = ok("derived_player_android.json"),
        )
        val resolved = PlaybackResolver(client).resolve("dQw4w9WgXcQ", session)
        assertEquals("anonymous", resolved.servedByRoad)
        assertEquals(
            listOf(
                RoadAttempt("account", "PlayerRefused"),
                RoadAttempt("anonymous", "served:IOS"),
            ),
            resolved.attempts
        )
    }

    @Test
    fun signedInAccountAndIosRefusedAndroidServes() {
        val client = routingClient(
            webRemix = ok("player_unplayable.json"),
            ios = ok("player_unplayable.json"),
            android = ok("derived_player_android.json"),
        )
        val resolved = PlaybackResolver(client).resolve("pYAg_d-iQLk", session)
        assertEquals("anonymous", resolved.servedByRoad)
        assertEquals(
            listOf(
                RoadAttempt("account", "PlayerRefused"),
                RoadAttempt("anonymous", "PlayerRefused:IOS"),
                RoadAttempt("anonymous", "served:ANDROID"),
            ),
            resolved.attempts
        )
    }

    @Test
    fun signedInAllRoadsRefusedCarriesFirstAnonymousError() {
        val client = routingClient(
            webRemix = ok("player_unplayable.json"),
            ios = ok("player_unplayable.json"), // PlayerRefused (first anon)
            android = HttpResponse(400, preconditionBody), // ContextRejected
        )
        try {
            PlaybackResolver(client).resolve("pYAg_d-iQLk", session)
            fail("expected AllRoadsRefused")
        } catch (e: AllRoadsRefused) {
            assertTrue(e.accountError is PlayerRefused)
            assertTrue(
                "anonymousError must be the FIRST anonymous context's, was ${e.anonymousError}",
                e.anonymousError is PlayerRefused
            )
            assertTrue(e.message!!.contains("account road"))
            assertTrue(e.message!!.contains("anonymous road"))
        }
    }

    // ------------------------------------------------------------------
    // Resolution cache (0.8.7 sweep 3)
    // ------------------------------------------------------------------

    private class CountingTransport(
        private val responseBody: String,
    ) : HttpTransport {
        var calls = 0

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse {
            calls++
            return HttpResponse(200, responseBody)
        }
    }

    @Test
    fun aSecondResolveWithinTheTtlMakesNoNewCalls() {
        val transport =
            CountingTransport(Fixtures.load("derived_player_android.json"))
        var now = 1_000_000L
        val resolver = PlaybackResolver(YoutubeiClient(transport)) { now }
        val first = resolver.resolve("pYAg_d-iQLk", null)
        val callsAfterFirst = transport.calls
        assertTrue(callsAfterFirst > 0)
        val second = resolver.resolve("pYAg_d-iQLk", null)
        assertEquals(
            "a cached resolve must not touch the network",
            callsAfterFirst,
            transport.calls,
        )
        assertEquals(first.info.url, second.info.url)
        // A different quality is a different entry.
        resolver.resolve("pYAg_d-iQLk", null, QualityPreference.LOW)
        assertTrue(transport.calls > callsAfterFirst)
        // forceFresh bypasses the read.
        val beforeForce = transport.calls
        resolver.resolve("pYAg_d-iQLk", null, forceFresh = true)
        assertTrue(transport.calls > beforeForce)
        // Past the TTL the entry is resolved again.
        now += 6 * 60 * 1000L
        val beforeExpiry = transport.calls
        resolver.resolve("pYAg_d-iQLk", null)
        assertTrue(transport.calls > beforeExpiry)
    }

    @Test
    fun failuresAreNeverCached() {
        val transport = CountingTransport(preconditionBody)
        val resolver = PlaybackResolver(YoutubeiClient(transport)) { 5_000L }
        repeat(2) {
            try {
                resolver.resolve("pYAg_d-iQLk", null)
                fail("a refused cascade must throw")
            } catch (e: OffleashException) {
                // Expected: every context refused.
            }
        }
        assertTrue(
            "the second refusal must have re-run the cascade",
            transport.calls >= 4,
        )
    }

    // ------------------------------------------------------------------
    // Generalized streamInfo + the ANDROID context record
    // ------------------------------------------------------------------

    @Test
    fun streamInfoWithAndroidContextHitsAndroidEndpointAndKey() {
        val transport = FixedTransport(body = Fixtures.load("derived_player_android.json"))
        val info = YoutubeiClient(transport).streamInfo("pYAg_d-iQLk", YoutubeiContexts.ANDROID)
        assertEquals(139055, info.bitrate)
        assertEquals(
            "https://www.youtube.com/youtubei/v1/player" +
                "?key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w&prettyPrint=false",
            transport.lastUrl
        )
        assertTrue(transport.lastBody!!.contains("\"clientName\":\"ANDROID\""))
        assertTrue(transport.lastBody!!.contains("\"clientVersion\":\"20.31.34\""))
        assertTrue(transport.lastBody!!.contains("\"androidSdkVersion\":34"))
        assertEquals(
            "com.google.android.youtube/20.31.34 (Linux; U; Android 14; en_US)",
            transport.lastHeaders!!["User-Agent"]
        )
    }

    @Test
    fun anonymousRoadIsIosThenAndroid() {
        assertEquals(
            listOf(YoutubeiContexts.IOS, YoutubeiContexts.ANDROID),
            YoutubeiContexts.ANONYMOUS_ROAD
        )
        // Existing contexts carry no androidSdkVersion.
        assertEquals(null, YoutubeiContexts.IOS.androidSdkVersion)
        assertEquals(null, YoutubeiContexts.WEB_REMIX.androidSdkVersion)
        assertEquals(34, YoutubeiContexts.ANDROID.androidSdkVersion)
    }

    // ------------------------------------------------------------------
    // isAuthRefusal truth table
    // ------------------------------------------------------------------

    @Test
    fun isAuthRefusalTruthTable() {
        assertTrue(ContextRejected("anything").isAuthRefusal())
        assertTrue(PlayerRefused("LOGIN_REQUIRED").isAuthRefusal())
        assertTrue(PlayerRefused("Sign in to confirm: login_required").isAuthRefusal())
        assertFalse(PlayerRefused("Video unavailable").isAuthRefusal())
        assertFalse(NoPlayableStream().isAuthRefusal())
        assertFalse(NetworkFailure("offline").isAuthRefusal())
        assertFalse(ParseFailure("shape").isAuthRefusal())
    }

    @Test
    fun runtimeExceptionOnFirstAnonymousContextStillCascades() {
        // A non-taxonomy escape from a client call must not abort
        // the remaining roads (0.8.7 sweep 1): iOS blows up with a
        // raw RuntimeException, ANDROID still serves.
        val transport = RoutingTransport { _, _, body ->
            val b = body ?: ""
            when {
                b.contains("\"clientName\":\"IOS\"") ->
                    throw IllegalStateException("boom MARKER")
                b.contains("\"clientName\":\"ANDROID\"") ->
                    ok("derived_player_android.json")
                else -> HttpResponse(500, "{}")
            }
        }
        val resolved = PlaybackResolver(YoutubeiClient(transport))
            .resolve("vid", null)
        assertEquals("anonymous", resolved.servedByRoad)
        // The blown-up attempt is on the record by failure class
        // (the client wraps the transport's raw exception into the
        // taxonomy; the resolver's own widened catch covers escapes
        // that bypass that wrap).
        assertTrue(
            resolved.attempts.any {
                it.outcome.contains("NetworkFailure")
            }
        )
    }

    @Test
    fun runtimeExceptionsOnEveryRoadSurfaceAsAllRoadsRefused() {
        // Every road throwing raw exceptions must still end in the
        // named taxonomy — AllRoadsRefused carrying NetworkFailure
        // class records — never an escaping RuntimeException or NPE.
        val transport = RoutingTransport { _, _, _ ->
            throw IllegalStateException("boom MARKER")
        }
        try {
            PlaybackResolver(YoutubeiClient(transport)).resolve("vid", session)
            fail("expected AllRoadsRefused")
        } catch (e: AllRoadsRefused) {
            assertTrue(e.accountError is NetworkFailure)
            assertTrue(e.anonymousError is NetworkFailure)
        }
        try {
            PlaybackResolver(YoutubeiClient(transport)).resolve("vid", null)
            fail("expected NetworkFailure from the anonymous cascade")
        } catch (e: NetworkFailure) {
            assertEquals("IllegalStateException", e.networkDetail)
        }
    }
}
