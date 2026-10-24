package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import dev.cyberdogs.offleash.core.auth.SessionValidation
import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.model.AccountIdentity
import dev.cyberdogs.offleash.core.model.AlbumPage
import dev.cyberdogs.offleash.core.model.ArtistPage
import dev.cyberdogs.offleash.core.model.FeedItem
import dev.cyberdogs.offleash.core.model.MoodCategory
import dev.cyberdogs.offleash.core.model.PlaylistPage
import dev.cyberdogs.offleash.core.model.PlaylistSummary
import dev.cyberdogs.offleash.core.model.SearchFilter
import dev.cyberdogs.offleash.core.model.SearchResults
import dev.cyberdogs.offleash.core.model.Section
import dev.cyberdogs.offleash.core.model.StreamInfo
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.core.parse.YoutubeiParsers
import dev.cyberdogs.offleash.core.parse.asArr
import dev.cyberdogs.offleash.core.parse.asObj
import dev.cyberdogs.offleash.core.parse.arr
import dev.cyberdogs.offleash.core.parse.findFirst
import dev.cyberdogs.offleash.core.parse.int
import dev.cyberdogs.offleash.core.parse.largestThumbnailUrl
import dev.cyberdogs.offleash.core.parse.obj
import dev.cyberdogs.offleash.core.parse.str
import dev.cyberdogs.offleash.core.parse.textOf
import dev.cyberdogs.offleash.core.playback.StreamResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Synchronous youtubei client (SPEC §3). Blocking by design — callers in
 * the app run it on an IO dispatcher; tests drive it with a fixture
 * transport. Browse/search/next run on the WEB_REMIX context; the player
 * runs on the iOS context (SPEC §1).
 *
 * Phase 2 adds the account road: calls that take an optional
 * [AuthSession] send its Cookie/Authorization headers and visitor data
 * when one is given, and behave exactly as Phase 1 when it is null.
 *
 * Every failure surfaces as a named [OffleashException] (SPEC §6):
 * transport exceptions and HTTP 5xx as [NetworkFailure], HTTP 4xx with
 * the server's own message as [ContextRejected], and bodies that aren't
 * recognizable JSON as [ParseFailure]. The account-identity calls are
 * the exception: they report outcomes as [IdentityLookup] /
 * [SessionValidation] values instead of throwing, because "signed out"
 * and "offline" are states the app must tell apart, not errors.
 */
class YoutubeiClient(
    private val transport: HttpTransport,
) {

    fun home(auth: AuthSession? = null): List<Section> =
        browseSections("FEmusic_home", auth = auth)

    fun charts(): List<Section> = browseSections("FEmusic_charts")

    fun moods(): List<MoodCategory> {
        val root = postJson("browse", YoutubeiContexts.WEB_REMIX, browseBody("FEmusic_moods_and_genres"))
        return YoutubeiParsers.parseMoods(root)
    }

    fun moodPlaylists(category: MoodCategory): List<Section> =
        browseSections(category.browseId, category.params)

    fun search(query: String, filter: SearchFilter = SearchFilters.ALL): SearchResults {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.WEB_REMIX.toJson())
            put("query", query)
            filter.params?.let { put("params", it) }
        }
        val root = postJson("search", YoutubeiContexts.WEB_REMIX, body)
        val parsed = YoutubeiParsers.parseSearch(root)
        // The response publishes its own chips; when it doesn't, fall back
        // to the pinned set so the UI always has the filters.
        val filters = if (parsed.filters.isEmpty()) {
            SearchFilters.ALL_FILTERS
        } else {
            listOf(SearchFilters.ALL) + parsed.filters.filter { it.label != SearchFilters.ALL.label }
        }
        return SearchResults(sections = parsed.sections, filters = filters)
    }

    fun album(browseId: String): AlbumPage {
        val root = postJson("browse", YoutubeiContexts.WEB_REMIX, browseBody(browseId))
        return YoutubeiParsers.parseAlbum(root, browseId)
    }

    fun artist(browseId: String): ArtistPage {
        val root = postJson("browse", YoutubeiContexts.WEB_REMIX, browseBody(browseId))
        return YoutubeiParsers.parseArtist(root)
    }

    fun playlist(browseId: String, params: String? = null, auth: AuthSession? = null): PlaylistPage {
        val root = postJson(
            "browse", YoutubeiContexts.WEB_REMIX, browseBody(browseId, params, auth), auth,
        )
        return YoutubeiParsers.parsePlaylist(root, browseId)
    }

    /** The signed-in Liked Songs playlist (browse id `VLLM`, the "LM" list). */
    fun likedSongs(auth: AuthSession): PlaylistPage = playlist("VLLM", auth = auth)

    /** The signed-in library's playlists, in shelf order. */
    fun libraryPlaylists(auth: AuthSession): List<PlaylistSummary> =
        browseSections("FEmusic_liked_playlists", auth = auth)
            .flatMap { it.items }
            .filterIsInstance<FeedItem.OfPlaylist>()
            .map { it.playlist }

    /** Radio mix for a track: `next` on `RDAMVM<videoId>`, seed excluded. */
    fun related(videoId: String): List<Track> {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.WEB_REMIX.toJson())
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
        }
        val root = postJson("next", YoutubeiContexts.WEB_REMIX, body)
        return YoutubeiParsers.parseNextTracks(root).filter { it.videoId != videoId }
    }

    fun streamInfo(
        videoId: String,
        context: YoutubeiContext = YoutubeiContexts.IOS,
    ): StreamInfo {
        val body = buildJsonObject {
            put("context", context.toJson())
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val raw = postRaw("player", context, body)
        return StreamResolver.resolve(raw)
    }

    /**
     * The account road for playback: a player POST on the WEB_REMIX
     * context carrying the session (same body flags as the iOS call).
     * This is the road that can serve Premium-only formats.
     */
    fun streamInfoAuthed(videoId: String, auth: AuthSession): StreamInfo {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.WEB_REMIX.toJson())
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val raw = postRaw("player", YoutubeiContexts.WEB_REMIX, body, auth)
        return StreamResolver.resolve(raw)
    }

    // ------------------------------------------------------------------
    // Account identity & session health (Phase 2)
    // ------------------------------------------------------------------

    /**
     * Asks the account switcher who [auth] belongs to. Never throws:
     * 401/403 → [IdentityLookup.Rejected]; transport failure, an
     * unexpected status, or an unparseable body → [IdentityLookup.Failed];
     * a 200 answer → [IdentityLookup.Found], with an empty list when the
     * response carries no accounts (a signed-out session).
     */
    fun accountIdentities(auth: AuthSession): IdentityLookup {
        val response = try {
            transport.get(ACCOUNT_SWITCHER_URL, auth.headers(nowEpochSeconds()))
        } catch (e: Exception) {
            return IdentityLookup.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (response.status == 401 || response.status == 403) {
            return IdentityLookup.Rejected(response.status)
        }
        if (response.status != 200) {
            return IdentityLookup.Failed("HTTP ${response.status}")
        }
        val root = try {
            Json.parseToJsonElement(stripXssiPrefix(response.body))
        } catch (e: Exception) {
            return IdentityLookup.Failed("account response was not valid JSON (${e.message})")
        }
        val accounts = root.asArr() ?: findFirst(root, "accounts").asArr()
            ?: return IdentityLookup.Found(emptyList())
        val identities = mutableListOf<AccountIdentity>()
        for ((index, element) in accounts.withIndex()) {
            val account = element.asObj() ?: continue
            val name = account.str("accountName") ?: textOf(account["accountName"])
                ?: account.str("name") ?: textOf(account["name"])
            val email = account.str("email") ?: textOf(account["email"])
            if (name == null && email == null) continue
            val avatar = largestThumbnailUrl(account.obj("accountPhoto").arr("thumbnails"))
                ?: largestThumbnailUrl(findFirst(account.obj("accountPhoto"), "thumbnails"))
                ?: account.str("photoUrl")
            val selected = account.str("isSelected") == "true" ||
                account.str("selected") == "true"
            identities.add(
                AccountIdentity(
                    name = name ?: email!!,
                    email = email,
                    avatarUrl = avatar,
                    authUser = account.int("accountNo") ?: index,
                    pageId = account.str("pageId") ?: account.str("channelId"),
                    isSelected = selected,
                )
            )
        }
        // The endpoint flags the active identity; when it flags none,
        // the first account is the session's default.
        if (identities.isNotEmpty() && identities.none { it.isSelected }) {
            identities[0] = identities[0].copy(isSelected = true)
        }
        return IdentityLookup.Found(identities)
    }

    /**
     * Fetches the anonymous visitor data from `sw.js_data`. Visitor data
     * is not account data: this call is deliberately made with no auth
     * headers. Any failure yields null — it never throws.
     */
    fun fetchVisitorData(): String? = try {
        val response = transport.get(VISITOR_DATA_URL, emptyMap())
        if (response.status != 200) {
            null
        } else {
            val match = VISITOR_DATA_REGEX.find(stripXssiPrefix(response.body))
            val captured = match?.groupValues?.get(1)
            captured?.let {
                (Json.parseToJsonElement("\"$it\"") as? JsonPrimitive)?.content
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Classifies a stored session's health via the account switcher.
     * A network failure is [SessionValidation.Unreachable], never
     * [SessionValidation.NeedsSignIn].
     */
    fun validateSession(auth: AuthSession): SessionValidation =
        when (val lookup = accountIdentities(auth)) {
            is IdentityLookup.Found ->
                if (lookup.identities.isEmpty()) {
                    SessionValidation.NeedsSignIn
                } else {
                    SessionValidation.Healthy(lookup.identities)
                }
            is IdentityLookup.Rejected -> SessionValidation.NeedsSignIn
            is IdentityLookup.Failed -> SessionValidation.Unreachable(lookup.detail)
        }

    // ------------------------------------------------------------------

    private fun browseSections(
        browseId: String,
        params: String? = null,
        auth: AuthSession? = null,
    ): List<Section> {
        val root = postJson(
            "browse", YoutubeiContexts.WEB_REMIX, browseBody(browseId, params, auth), auth,
        )
        return YoutubeiParsers.parseSections(root)
    }

    private fun browseBody(
        browseId: String,
        params: String? = null,
        auth: AuthSession? = null,
    ): JsonObject = buildJsonObject {
        put("context", contextJsonWithVisitor(YoutubeiContexts.WEB_REMIX.toJson(), auth))
        put("browseId", browseId)
        params?.let { put("params", it) }
    }

    private fun postJson(
        path: String,
        context: YoutubeiContext,
        body: JsonObject,
        auth: AuthSession? = null,
    ): JsonObject {
        val raw = postRaw(path, context, body, auth)
        return try {
            Json.parseToJsonElement(raw).asObj()
        } catch (e: Exception) {
            throw ParseFailure("response for '$path' was not valid JSON (${e.message})")
        } ?: throw ParseFailure("response for '$path' was not a JSON object")
    }

    private fun postRaw(
        path: String,
        context: YoutubeiContext,
        body: JsonObject,
        auth: AuthSession? = null,
    ): String {
        val headers = if (auth == null) {
            context.headers()
        } else {
            // Auth headers merge OVER the context headers.
            context.headers() + auth.headers(nowEpochSeconds())
        }
        val effectiveBody = bodyWithVisitorData(body, auth)
        val response = try {
            transport.post(context.endpointUrl(path), headers, effectiveBody.toString())
        } catch (e: OffleashException) {
            throw e
        } catch (e: Exception) {
            throw NetworkFailure(e.message ?: e.javaClass.simpleName)
        }
        if (response.status != 200) {
            val serverMessage = extractErrorMessage(response.body)
                ?: "HTTP ${response.status}"
            if (response.status in 400..499) {
                throw ContextRejected(serverMessage)
            }
            throw NetworkFailure(serverMessage)
        }
        return response.body
    }

    /**
     * [body] with the session's visitor data injected into its `context`
     * object as `visitorData` (the context JsonObject is rebuilt with
     * the extra key). Bodies without a session visitor pass through
     * unchanged, so anonymous requests stay byte-identical to Phase 1.
     */
    private fun bodyWithVisitorData(body: JsonObject, auth: AuthSession?): JsonObject {
        if (auth?.visitorData == null) return body
        val context = body["context"].asObj() ?: return body
        if (context.containsKey("visitorData")) return body
        val newContext = JsonObject(context + ("visitorData" to JsonPrimitive(auth.visitorData)))
        return JsonObject(body + ("context" to newContext))
    }

    /** A context JsonObject carrying the session's visitor data, if any. */
    private fun contextJsonWithVisitor(context: JsonObject, auth: AuthSession?): JsonObject {
        val visitorData = auth?.visitorData ?: return context
        if (context.containsKey("visitorData")) return context
        return JsonObject(context + ("visitorData" to JsonPrimitive(visitorData)))
    }

    /** Drops a leading `)]}'` XSSI prefix line, when one is present. */
    private fun stripXssiPrefix(body: String): String {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith(")]}'")) return body
        val newline = trimmed.indexOf('\n')
        return if (newline >= 0) trimmed.substring(newline + 1) else trimmed.substring(4)
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    /** The `error.message` of a youtubei error body, when it has one. */
    private fun extractErrorMessage(body: String): String? = try {
        Json.parseToJsonElement(body).asObj().obj("error").str("message")
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val ACCOUNT_SWITCHER_URL =
            "https://music.youtube.com/getAccountSwitcherEndpoint"
        const val VISITOR_DATA_URL = "https://music.youtube.com/sw.js_data"
        val VISITOR_DATA_REGEX = Regex("\"visitorData\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
