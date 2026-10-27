package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.RoutingTransport
import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver's token road (0.7.0): with a Bearer token the
 * ANDROID_MUSIC player is tried FIRST; its refusal falls back to
 * the proven anonymous cascade with the token attempt kept on the
 * record — the split the build report names (token for identity and
 * library always; token for the stream where the surface allows).
 *
 * Routing keys on the body's exact quoted clientName, as in
 * [PlaybackResolverTest]: `"clientName":"ANDROID"` cannot match an
 * ANDROID_MUSIC body.
 */
class PlaybackResolverOAuthTest {

    private val bearer = "ya29.test-token"

    private val unauthorizedBody =
        """{"error":{"code":401,"message":"Request had invalid authentication credentials.","status":"UNAUTHENTICATED"}}"""

    private fun routingClient(
        androidMusic: HttpResponse,
        ios: HttpResponse,
        android: HttpResponse,
    ): YoutubeiClient {
        val transport = RoutingTransport { _, _, body ->
            val b = body ?: ""
            when {
                b.contains("\"clientName\":\"ANDROID_MUSIC\"") -> androidMusic
                b.contains("\"clientName\":\"ANDROID\"") -> android
                b.contains("\"clientName\":\"IOS\"") -> ios
                else -> HttpResponse(500, "{}")
            }
        }
        return YoutubeiClient(transport)
    }

    private fun ok(fixture: String) = HttpResponse(200, Fixtures.load(fixture))

    @Test
    fun tokenRoadServesFirstWhenItAnswers() {
        val client = routingClient(
            androidMusic = ok("derived_player_android.json"),
            ios = ok("player_ios.json"),
            android = ok("derived_player_android.json"),
        )
        val resolved = PlaybackResolver(client)
            .resolve("derivedVideoId", auth = null, bearerToken = bearer)
        assertEquals("account", resolved.servedByRoad)
        assertEquals(1, resolved.attempts.size)
        assertEquals("account", resolved.attempts[0].road)
        assertEquals("served:ANDROID_MUSIC", resolved.attempts[0].outcome)
        assertTrue(resolved.info.url.isNotBlank())
    }

    @Test
    fun tokenRefusalFallsBackToTheAnonymousCascade() {
        val client = routingClient(
            androidMusic = HttpResponse(401, unauthorizedBody),
            ios = ok("player_ios.json"),
            android = ok("derived_player_android.json"),
        )
        val resolved = PlaybackResolver(client)
            .resolve("derivedVideoId", auth = null, bearerToken = bearer)
        assertEquals("anonymous", resolved.servedByRoad)
        // The token attempt stays on the record, ahead of the
        // anonymous attempt that served.
        assertEquals(2, resolved.attempts.size)
        assertEquals("account", resolved.attempts[0].road)
        assertTrue(resolved.attempts[0].outcome.contains("ANDROID_MUSIC"))
        assertEquals("anonymous", resolved.attempts[1].road)
        assertEquals("served:IOS", resolved.attempts[1].outcome)
    }

    @Test
    fun noTokenKeepsTheSignedOutSemantics() {
        val client = routingClient(
            androidMusic = ok("derived_player_android.json"),
            ios = ok("player_ios.json"),
            android = ok("derived_player_android.json"),
        )
        val resolved = PlaybackResolver(client)
            .resolve("derivedVideoId", auth = null, bearerToken = null)
        assertEquals("anonymous", resolved.servedByRoad)
        assertEquals(1, resolved.attempts.size)
        assertEquals("served:IOS", resolved.attempts[0].outcome)
    }
}
