package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import dev.cyberdogs.offleash.core.error.ContextRejected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visitor-shape legs of the signed-in browse cascade (0.8.4
 * browse hunt). The owner's 0.8.3 device run refused EVERY signed-in
 * browse — home, Liked, library playlists, both client contexts,
 * no delegation in play — with HTTP 400 INVALID_ARGUMENT /
 * badRequest, while the identical envelope answered 200
 * anonymously from the sandbox and 401 to a garbage Bearer: a
 * post-auth request-shape verdict. The one authenticated-shape
 * element real Bearer clients bind and the OAuth road never
 * carried is the session visitor data (the cookie road injected
 * it into every body; the token road never did). These tests pin
 * the fix's shape: visitor legs lead the cascade and carry the
 * binding on the wire (body `context.visitorData` +
 * `X-Goog-Visitor-Id` header), an API-format leg follows, the
 * 0.8.2 plain legs remain as the control group, the winner cache
 * remembers shapes, and a missing or throwing provider degrades
 * to exactly the 0.8.2 cascade.
 *
 * The refusal body below is verbatim from the hunt's live oracle
 * probes (2026-10-31, this sandbox, no credentials) — Google's
 * real 400 INVALID_ARGUMENT shape, the same status/reason the
 * owner's diagnostics recorded.
 */
class OAuthVisitorShapeTest {

    private val bearer = "test-bearer-token"
    private val visitorData = "visitor-data-xyz"

    private val badRequestBody = """
        {"error": {"code": 400, "message": "Request contains an invalid argument.", "errors": [{"message": "Request contains an invalid argument.", "domain": "global", "reason": "badRequest"}], "status": "INVALID_ARGUMENT"}}
    """.trimIndent()

    /** Routes by the request's own presentation: client, delegation, shape. */
    private class ShapeTransport(
        private val script: (Call) -> HttpResponse,
    ) : HttpTransport {
        data class Call(
            val url: String,
            val headers: Map<String, String>,
            val body: String,
            val clientName: String,
            val delegated: Boolean,
            val hasVisitor: Boolean,
            val hasApiHeaders: Boolean,
        )

        val calls = mutableListOf<Call>()

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            val call = Call(
                url = url,
                headers = headers,
                body = body,
                clientName = when {
                    body.contains("\"ANDROID_MUSIC\"") -> "ANDROID_MUSIC"
                    body.contains("\"WEB_REMIX\"") -> "WEB_REMIX"
                    else -> "?"
                },
                delegated = headers.containsKey("X-Goog-PageId") ||
                    body.contains("onBehalfOfUser"),
                hasVisitor = body.contains("\"visitorData\""),
                hasApiHeaders = headers.containsKey("X-Goog-Api-Format-Version"),
            )
            calls += call
            return script(call)
        }
    }

    private fun homeOk() = HttpResponse(200, Fixtures.load("browse_home.json"))

    private fun vdClient(
        transport: ShapeTransport,
        log: MutableList<String>? = null,
    ) = YoutubeiClient(
        transport,
        browseAttemptLog = log?.let { sink -> { line: String -> sink += line } },
        visitorDataProvider = { visitorData },
    )

    @Test
    fun visitorWebRemixLeadsAndCarriesTheBindingOnTheWire() {
        val log = mutableListOf<String>()
        val transport = ShapeTransport { homeOk() }
        val client = vdClient(transport, log)
        val delegation = BrowseDelegation(authUser = 1, pageId = "page-123")

        val feed = client.homeFeedOAuth(bearer, delegation = delegation)

        assertTrue(feed.sections.isNotEmpty())
        assertEquals(1, transport.calls.size)
        val call = transport.calls.single()
        assertEquals("WEB_REMIX", call.clientName)
        assertTrue(call.delegated)
        assertTrue(call.hasVisitor)
        assertFalse(call.hasApiHeaders)
        assertTrue(call.body.contains("\"visitorData\":\"visitor-data-xyz\""))
        assertEquals("visitor-data-xyz", call.headers["X-Goog-Visitor-Id"])
        assertEquals("1", call.headers["X-Goog-AuthUser"])
        assertEquals("page-123", call.headers["X-Goog-PageId"])
        assertEquals(
            listOf("surface=FEmusic_home config=WEB_REMIX+brand+vd outcome=ok"),
            log,
        )
    }

    @Test
    fun apiFormatLegFollowsWhenVisitorOnlyLegsRefuse() {
        val transport = ShapeTransport { call ->
            if (call.hasApiHeaders) homeOk() else HttpResponse(400, badRequestBody)
        }
        val client = vdClient(transport)

        val feed = client.homeFeedOAuth(bearer)

        assertTrue(feed.sections.isNotEmpty())
        assertEquals(3, transport.calls.size)
        assertEquals(
            listOf("WEB_REMIX", "ANDROID_MUSIC", "WEB_REMIX"),
            transport.calls.map { it.clientName },
        )
        assertTrue(transport.calls[0].hasVisitor)
        assertTrue(transport.calls[1].hasVisitor)
        val winning = transport.calls[2]
        assertTrue(winning.hasVisitor)
        assertTrue(winning.hasApiHeaders)
        assertEquals("1", winning.headers["X-Goog-Api-Format-Version"])
        assertEquals("WEB_REMIX", winning.headers["X-YouTube-Client-Name"])
        assertEquals(
            YoutubeiContexts.WEB_REMIX.clientVersion,
            winning.headers["X-YouTube-Client-Version"],
        )
    }

    @Test
    fun plainLegsRemainTheControlGroupAndCanStillWin() {
        val transport = ShapeTransport { call ->
            if (!call.hasVisitor && call.clientName == "ANDROID_MUSIC" && call.delegated) {
                homeOk()
            } else {
                HttpResponse(400, badRequestBody)
            }
        }
        val client = vdClient(transport)
        val delegation = BrowseDelegation(authUser = 0, pageId = "page-9")

        val feed = client.homeFeedOAuth(bearer, delegation = delegation)

        assertTrue(feed.sections.isNotEmpty())
        // 4 visitor legs (WR/AM x vd/vd+api), then the first plain leg.
        assertEquals(5, transport.calls.size)
        val winning = transport.calls.last()
        assertFalse(winning.hasVisitor)
        assertFalse(winning.headers.containsKey("X-Goog-Visitor-Id"))
        assertEquals("ANDROID_MUSIC", winning.clientName)
        assertTrue(winning.delegated)
    }

    @Test
    fun allRefusedSurfacesTheDeviceErrorShapeUnchanged() {
        val transport = ShapeTransport { HttpResponse(400, badRequestBody) }
        val client = vdClient(transport)

        val thrown: ContextRejected = try {
            client.libraryPlaylistsOAuth(bearer)
            throw AssertionError("expected the cascade to throw")
        } catch (e: ContextRejected) {
            e
        }

        assertEquals(400, thrown.httpStatus)
        assertEquals("INVALID_ARGUMENT", thrown.serverStatus)
        assertEquals("badRequest", thrown.serverReason)
        // No delegation: WR/AM x (vd, vd+api) + the two plain legs.
        assertEquals(6, transport.calls.size)
    }

    @Test
    fun visitorWinnerIsRememberedAcrossSurfaces() {
        val transport = ShapeTransport { homeOk() }
        val client = vdClient(transport)

        client.homeFeedOAuth(bearer)
        assertEquals(1, transport.calls.size)

        client.homeFeedOAuth(bearer)
        assertEquals(2, transport.calls.size)
        assertTrue(transport.calls.last().hasVisitor)
        assertEquals("WEB_REMIX", transport.calls.last().clientName)
    }

    @Test
    fun noProviderKeepsThe082CascadeExactly() {
        val transport = ShapeTransport { HttpResponse(400, badRequestBody) }
        val client = YoutubeiClient(transport)
        val delegation = BrowseDelegation(authUser = 2, pageId = "page-7")

        try {
            client.likedSongsOAuth(bearer, delegation)
            throw AssertionError("expected the cascade to throw")
        } catch (e: ContextRejected) {
            assertEquals(400, e.httpStatus)
        }

        assertEquals(4, transport.calls.size)
        for (call in transport.calls) {
            assertFalse(call.hasVisitor)
            assertFalse(call.headers.containsKey("X-Goog-Visitor-Id"))
            assertFalse(call.hasApiHeaders)
        }
    }

    @Test
    fun throwingProviderReadsAsAbsent() {
        val transport = ShapeTransport { homeOk() }
        val client = YoutubeiClient(
            transport,
            visitorDataProvider = { throw RuntimeException("fetch died") },
        )

        val feed = client.homeFeedOAuth(bearer)

        assertTrue(feed.sections.isNotEmpty())
        assertEquals(1, transport.calls.size)
        assertFalse(transport.calls.single().hasVisitor)
        assertEquals("ANDROID_MUSIC", transport.calls.single().clientName)
    }

    /** The continuation response shape, as pinned by ContinuationTest. */
    private fun continuationBody(): String {
        fun trackRow(videoId: String, title: String) =
            """{"musicResponsiveListItemRenderer": {
                "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$title"}]}}}
                ],
                "playlistItemData": {"videoId": "$videoId"}
            }}"""
        return """{"continuationContents": {"musicPlaylistShelfContinuation": {
            "contents": [${trackRow("vidA", "Song A")}, ${trackRow("vidB", "Song B")}]
        }}}"""
    }

    @Test
    fun continuationCarriesTheVisitorBinding() {
        val transport = ShapeTransport { call ->
            if (call.hasVisitor) {
                HttpResponse(200, continuationBody())
            } else {
                HttpResponse(400, badRequestBody)
            }
        }
        val client = vdClient(transport)

        val page = client.playlistContinuationOAuth(bearer, "continuation-token-1")

        assertTrue(page.tracks.isNotEmpty())
        val call = transport.calls.first()
        assertTrue(call.body.contains("\"visitorData\":\"visitor-data-xyz\""))
        assertTrue(call.body.contains("\"continuation\":\"continuation-token-1\""))
        assertEquals("visitor-data-xyz", call.headers["X-Goog-Visitor-Id"])
    }

    @Test
    fun accountMenuCarriesTheVisitorBinding() {
        val transport = ShapeTransport {
            HttpResponse(200, Fixtures.load("derived_account_switcher.json"))
        }
        val client = vdClient(transport)

        val lookup = client.accountIdentitiesOAuth(bearer)

        assertTrue(lookup is IdentityLookup.Found)
        val call = transport.calls.single()
        assertTrue(call.url.contains("account/account_menu"))
        assertTrue(call.body.contains("\"visitorData\":\"visitor-data-xyz\""))
        assertEquals("visitor-data-xyz", call.headers["X-Goog-Visitor-Id"])
    }
}
