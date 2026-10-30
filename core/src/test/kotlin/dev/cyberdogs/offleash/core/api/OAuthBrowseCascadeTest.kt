package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import dev.cyberdogs.offleash.core.error.ContextRejected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed-in browse cascade (0.8.2 browse hunt). The owner's
 * 0.8.1 device run proved the naive pairing — a TV-minted token
 * presented as ANDROID_MUSIC with no brand delegation — refused on
 * every signed-in browse surface while identity worked. These tests
 * pin the fix: presentations are tried in the hunt's ranked order
 * (ANDROID_MUSIC+brand, ANDROID_MUSIC, WEB_REMIX+brand, WEB_REMIX),
 * the winner is remembered, refusals are self-naming (HTTP status +
 * Google's status/reason ride the exception and the attempt log),
 * and the brand delegation actually lands on the wire.
 *
 * Error bodies below are verbatim from the hunt's live probes
 * (2026-10-30, this sandbox, no credentials): the 401 and 400 shapes
 * are Google's real responses to the shipped client fingerprint;
 * the 403 is the same standard shape with PERMISSION_DENIED.
 */
class OAuthBrowseCascadeTest {

    private val bearer = "test-bearer-token"

    private val authErrorBody = """
        {"error": {"code": 401, "message": "Request had invalid authentication credentials. Expected OAuth access token, login cookie or other valid authentication credential.", "errors": [{"message": "Invalid Credentials", "domain": "global", "reason": "authError", "locationType": "header", "location": "Authorization"}], "status": "UNAUTHENTICATED"}}
    """.trimIndent()

    private val preconditionBody = """
        {"error": {"code": 400, "message": "Precondition check failed.", "errors": [{"message": "Precondition check failed.", "domain": "global", "reason": "failedPrecondition"}], "status": "FAILED_PRECONDITION"}}
    """.trimIndent()

    private val permissionBody = """
        {"error": {"code": 403, "message": "The caller does not have permission to execute the specified operation.", "errors": [{"message": "The caller does not have permission to execute the specified operation.", "domain": "global", "reason": "forbidden"}], "status": "PERMISSION_DENIED"}}
    """.trimIndent()

    /** Routes by the request's own presentation: client + delegation. */
    private class RoutingTransport(
        private val script: (clientName: String, delegated: Boolean) -> HttpResponse,
    ) : HttpTransport {
        data class Call(
            val url: String,
            val headers: Map<String, String>,
            val body: String,
            val clientName: String,
            val delegated: Boolean,
        )

        val calls = mutableListOf<Call>()

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            val clientName = when {
                body.contains("\"ANDROID_MUSIC\"") -> "ANDROID_MUSIC"
                body.contains("\"WEB_REMIX\"") -> "WEB_REMIX"
                else -> "?"
            }
            val delegated = headers.containsKey("X-Goog-PageId") ||
                body.contains("onBehalfOfUser")
            calls += Call(url, headers, body, clientName, delegated)
            return script(clientName, delegated)
        }
    }

    private fun homeOk() = HttpResponse(200, Fixtures.load("browse_home.json"))

    @Test
    fun brandDelegatedAndroidMusicIsTriedFirstAndCarriesTheDelegation() {
        val log = mutableListOf<String>()
        val transport = RoutingTransport { _, _ -> homeOk() }
        val client = YoutubeiClient(transport, browseAttemptLog = { log += it })
        val delegation = BrowseDelegation(authUser = 1, pageId = "page-123")

        val feed = client.homeFeedOAuth(bearer, delegation = delegation)

        assertTrue(feed.sections.isNotEmpty())
        assertEquals(1, transport.calls.size)
        val call = transport.calls.single()
        assertEquals("ANDROID_MUSIC", call.clientName)
        assertEquals("1", call.headers["X-Goog-AuthUser"])
        assertEquals("page-123", call.headers["X-Goog-PageId"])
        assertTrue(call.body.contains("\"onBehalfOfUser\":\"page-123\""))
        assertEquals(
            listOf("surface=FEmusic_home config=ANDROID_MUSIC+brand outcome=ok"),
            log,
        )
    }

    @Test
    fun fallsThroughToWebRemixWhenAndroidMusicRefuses() {
        val transport = RoutingTransport { clientName, _ ->
            if (clientName == "ANDROID_MUSIC") {
                HttpResponse(403, permissionBody)
            } else {
                homeOk()
            }
        }
        val client = YoutubeiClient(transport)

        val playlists = client.libraryPlaylistsOAuth(bearer)

        assertTrue(playlists.isNotEmpty())
        assertEquals(
            listOf("ANDROID_MUSIC", "WEB_REMIX"),
            transport.calls.map { it.clientName },
        )
        // No delegation was supplied: no identity headers anywhere.
        for (call in transport.calls) {
            assertFalse(call.headers.containsKey("X-Goog-AuthUser"))
            assertFalse(call.headers.containsKey("X-Goog-PageId"))
            assertFalse(call.body.contains("onBehalfOfUser"))
        }
    }

    @Test
    fun brandWebRemixWinsWhenAndroidMusicRefusesBothWays() {
        val transport = RoutingTransport { clientName, delegated ->
            if (clientName == "WEB_REMIX" && delegated) {
                HttpResponse(200, Fixtures.load("playlist.json"))
            } else {
                HttpResponse(401, authErrorBody)
            }
        }
        val client = YoutubeiClient(transport)
        val delegation = BrowseDelegation(authUser = 0, pageId = "page-9")

        val page = client.playlistOAuth(bearer, "VLPLexample", delegation = delegation)

        assertTrue(page.tracks.isNotEmpty())
        assertEquals(3, transport.calls.size)
        assertEquals(
            listOf(true, false, true),
            transport.calls.map { it.delegated },
        )
    }

    @Test
    fun allRefusedThrowsTheFirstRefusalWithStructuredFields() {
        val log = mutableListOf<String>()
        val transport = RoutingTransport { _, delegated ->
            if (delegated) HttpResponse(401, authErrorBody) else HttpResponse(403, permissionBody)
        }
        val client = YoutubeiClient(transport, browseAttemptLog = { log += it })
        val delegation = BrowseDelegation(authUser = 2, pageId = "page-7")

        val thrown: ContextRejected = try {
            client.likedSongsOAuth(bearer, delegation)
            throw AssertionError("expected the cascade to throw")
        } catch (e: ContextRejected) {
            e
        }

        // The FIRST refusal wins: it is the shipped pairing's answer,
        // the most diagnostic of the four.
        assertEquals(401, thrown.httpStatus)
        assertEquals("UNAUTHENTICATED", thrown.serverStatus)
        assertEquals("authError", thrown.serverReason)
        assertEquals(4, transport.calls.size)
        assertEquals(4, log.size)
        assertTrue(log[0].contains("config=ANDROID_MUSIC+brand"))
        assertTrue(log[0].contains("refused http=401 status=UNAUTHENTICATED reason=authError"))
        // The log is diagnostics-safe: statuses and class names only.
        for (line in log) {
            assertFalse(line.contains(bearer))
            assertFalse(line.contains("Invalid Credentials"))
        }
    }

    @Test
    fun winnerIsRememberedAndReprobedWhenItStartsRefusing() {
        var webRefuses = false
        val transport = RoutingTransport { clientName, _ ->
            when {
                clientName == "ANDROID_MUSIC" && !webRefuses ->
                    HttpResponse(403, permissionBody)
                clientName == "WEB_REMIX" && webRefuses ->
                    HttpResponse(403, permissionBody)
                else -> homeOk()
            }
        }
        val client = YoutubeiClient(transport)

        client.homeFeedOAuth(bearer) // AM refuses, WR wins: 2 calls
        assertEquals(2, transport.calls.size)

        client.homeFeedOAuth(bearer) // winner first: 1 call
        assertEquals(3, transport.calls.size)
        assertEquals("WEB_REMIX", transport.calls.last().clientName)

        webRefuses = true
        client.homeFeedOAuth(bearer) // winner refuses, AM now wins: 2 calls
        assertEquals(5, transport.calls.size)
        assertEquals("WEB_REMIX", transport.calls[3].clientName)
        assertEquals("ANDROID_MUSIC", transport.calls[4].clientName)

        client.homeFeedOAuth(bearer) // new winner first: 1 call
        assertEquals(6, transport.calls.size)
        assertEquals("ANDROID_MUSIC", transport.calls.last().clientName)
    }

    @Test
    fun transportFailureFallsThroughAndIsLoggedByClass() {
        val log = mutableListOf<String>()
        val transport = RoutingTransport { clientName, _ ->
            if (clientName == "ANDROID_MUSIC") {
                throw RuntimeException("connection reset")
            }
            homeOk()
        }
        val client = YoutubeiClient(transport, browseAttemptLog = { log += it })

        val feed = client.homeFeedOAuth(bearer)

        assertTrue(feed.sections.isNotEmpty())
        assertTrue(log[0].contains("failed NetworkFailure"))
        assertTrue(log[1].contains("outcome=ok"))
    }

    @Test
    fun errorObjectOn200FallsThroughAndNamesItselfWhenAllDoIt() {
        val transport = RoutingTransport { _, _ -> HttpResponse(200, preconditionBody) }
        val client = YoutubeiClient(transport)

        val thrown: ContextRejected = try {
            client.homeFeedOAuth(bearer)
            throw AssertionError("expected the cascade to throw")
        } catch (e: ContextRejected) {
            e
        }

        assertEquals("FAILED_PRECONDITION", thrown.serverStatus)
        assertEquals("failedPrecondition", thrown.serverReason)
        // A 200-carried error has no HTTP refusal status.
        assertEquals(null, thrown.httpStatus)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun accountMenuOAuthParsesBrandIdentitiesWithBearerOnly() {
        val transport = RoutingTransport { _, _ ->
            HttpResponse(200, Fixtures.load("derived_account_switcher.json"))
        }
        val client = YoutubeiClient(transport)

        val lookup = client.accountIdentitiesOAuth(bearer)

        assertTrue(lookup is IdentityLookup.Found)
        val identities = (lookup as IdentityLookup.Found).identities
        val brand = identities.single { it.name == "Sentinel Brand Channel" }
        assertEquals("987654321098765432109", brand.pageId)
        assertTrue(brand.isSelected)
        val call = transport.calls.single()
        assertTrue(call.url.contains("account/account_menu"))
        assertEquals("Bearer $bearer", call.headers["Authorization"])
        assertFalse(call.headers.keys.any { it.equals("Cookie", ignoreCase = true) })
    }

    @Test
    fun accountMenuOAuthMapsRefusalToRejected() {
        val transport = RoutingTransport { _, _ -> HttpResponse(401, authErrorBody) }
        val client = YoutubeiClient(transport)

        val lookup = client.accountIdentitiesOAuth(bearer)

        assertTrue(lookup is IdentityLookup.Rejected)
        assertEquals(401, (lookup as IdentityLookup.Rejected).httpStatus)
    }
}
