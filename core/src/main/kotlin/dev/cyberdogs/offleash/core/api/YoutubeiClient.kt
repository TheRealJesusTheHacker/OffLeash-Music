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
import dev.cyberdogs.offleash.core.model.HomeChip
import dev.cyberdogs.offleash.core.model.HomeFeed
import dev.cyberdogs.offleash.core.model.LyricsLine
import dev.cyberdogs.offleash.core.model.LyricsResult
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
import dev.cyberdogs.offleash.core.parse.findAll
import dev.cyberdogs.offleash.core.parse.findFirst
import dev.cyberdogs.offleash.core.parse.int
import dev.cyberdogs.offleash.core.parse.largestThumbnailUrl
import dev.cyberdogs.offleash.core.parse.obj
import dev.cyberdogs.offleash.core.parse.str
import dev.cyberdogs.offleash.core.parse.textOf
import dev.cyberdogs.offleash.core.playback.QualityPreference
import dev.cyberdogs.offleash.core.playback.StreamResolver
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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

    /**
     * The home feed with its header chips (Lane H): one browse of
     * `FEmusic_home` parsed for both the titled sections and the
     * `chipCloudRenderer` chips the sections-only parse drops.
     * With no [chip] this is the plain home browse; with one, it is
     * the chip's own re-browse (its browseId + params, the exact
     * request the response's chip endpoint describes).
     */
    fun homeFeed(auth: AuthSession? = null, chip: HomeChip? = null): HomeFeed {
        val root = postJson(
            "browse", YoutubeiContexts.WEB_REMIX,
            browseBody(chip?.browseId ?: "FEmusic_home", chip?.params, auth), auth,
        )
        return YoutubeiParsers.parseHome(root)
    }

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
        quality: QualityPreference = QualityPreference.HIGH,
    ): StreamInfo {
        val body = buildJsonObject {
            put("context", context.toJson())
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val raw = postRaw("player", context, body)
        return StreamResolver.resolve(raw, quality)
    }

    /**
     * The account road for playback: a player POST on the WEB_REMIX
     * context carrying the session (same body flags as the iOS call).
     * This is the road that can serve Premium-only formats.
     */
    fun streamInfoAuthed(
        videoId: String,
        auth: AuthSession,
        quality: QualityPreference = QualityPreference.HIGH,
    ): StreamInfo {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.WEB_REMIX.toJson())
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val raw = postRaw("player", YoutubeiContexts.WEB_REMIX, body, auth)
        return StreamResolver.resolve(raw, quality)
    }

    // ------------------------------------------------------------------
    // Account like (Wave 2)
    // ------------------------------------------------------------------

    /**
     * Likes ([liked] = true) or un-likes a track on the account, via
     * `like/like` / `like/removelike` on WEB_REMIX with the session.
     * Success is simply the call returning: HTTP failures map through
     * [postJson] to the named errors, as everywhere else in this client.
     */
    fun setLike(videoId: String, auth: AuthSession, liked: Boolean): Unit {
        val body = buildJsonObject {
            put("context", contextJsonWithVisitor(YoutubeiContexts.WEB_REMIX.toJson(), auth))
            putJsonObject("target") {
                put("videoId", videoId)
            }
        }
        postJson(
            if (liked) "like/like" else "like/removelike",
            YoutubeiContexts.WEB_REMIX,
            body,
            auth,
        )
    }

    /**
     * The session's current like state for a track, read from the
     * WEB_REMIX player response's `likeStatus` field (top level, or
     * under `videoDetails` when the response nests it there). An absent
     * or unrecognized value reads as null — unknown is an honest state,
     * and the UI shows neutral for it rather than guessing.
     */
    fun likeStatus(videoId: String, auth: AuthSession): LikeStatus? {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.WEB_REMIX.toJson())
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val root = postJson("player", YoutubeiContexts.WEB_REMIX, body, auth)
        val raw = root.str("likeStatus") ?: root.obj("videoDetails").str("likeStatus")
        return when (raw) {
            "LIKE" -> LikeStatus.LIKED
            "DISLIKE" -> LikeStatus.DISLIKED
            "INDIFFERENT" -> LikeStatus.INDIFFERENT
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Lyrics (Wave 2)
    // ------------------------------------------------------------------

    /**
     * YouTube's own lyrics for a track: a `next` call (WEB_REMIX, with
     * the session when one is given) carries a "Lyrics" tab whose browse
     * endpoint leads to the lyrics page, whose description shelf holds
     * the text. Every failure — no tab, an empty page, any named error
     * on the way — is [LyricsResult.Unavailable], never an exception:
     * lyrics failure must never break playback.
     */
    fun lyrics(videoId: String, auth: AuthSession? = null): LyricsResult {
        return try {
            val body = buildJsonObject {
                put("context", contextJsonWithVisitor(YoutubeiContexts.WEB_REMIX.toJson(), auth))
                put("videoId", videoId)
            }
            val nextRoot = postJson("next", YoutubeiContexts.WEB_REMIX, body, auth)
            val browseId = lyricsBrowseId(nextRoot)
                ?: return LyricsResult.Unavailable(NO_LYRICS_REASON)
            val browseRoot = postJson(
                "browse", YoutubeiContexts.WEB_REMIX, browseBody(browseId, null, auth), auth,
            )
            val shelf = findFirst(browseRoot, "musicDescriptionShelfRenderer").asObj()
            val text = shelf?.let { textOf(it.obj("description")) }
                ?.takeIf { it.isNotBlank() }
                ?: return LyricsResult.Unavailable(NO_LYRICS_REASON)
            val lines = text.lines()
                .map { it.trimEnd('\r') }
                .dropWhile { it.isBlank() }
                .dropLastWhile { it.isBlank() }
                .map { LyricsLine(it) }
            if (lines.isEmpty()) {
                LyricsResult.Unavailable(NO_LYRICS_REASON)
            } else {
                LyricsResult.Found(lines, source = "YouTube Music")
            }
        } catch (e: OffleashException) {
            LyricsResult.Unavailable(e.message ?: "Lyrics are unavailable for this track.")
        }
    }

    /**
     * Synced (timed) lyrics from LRCLIB, a public lyrics database. This
     * is NOT a YouTube call: it is made with no headers at all — no
     * session material ever leaves for a third party. Any failure
     * (transport, status, malformed body, no synced lyrics on file)
     * reads as null; this function never throws.
     */
    fun syncedLyrics(
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): List<LyricsLine>? {
        val query = buildString {
            append("track_name=").append(urlEncode(title))
            append("&artist_name=").append(urlEncode(artist))
            durationSeconds?.let { append("&duration=").append(it) }
        }
        val response = try {
            transport.get("$LRCLIB_GET_URL?$query", emptyMap())
        } catch (e: Exception) {
            return null
        }
        if (response.status != 200) return null
        val root = try {
            Json.parseToJsonElement(response.body).asObj()
        } catch (e: Exception) {
            return null
        } ?: return null
        val synced = root.str("syncedLyrics")?.takeIf { it.isNotBlank() } ?: return null
        return parseLrc(synced).takeIf { it.isNotEmpty() }
    }

    /**
     * The browse id of the `next` response's "Lyrics" tab, walking
     * every `tabRenderer` in the tree (in the recorded response they
     * live under the watch-next tabbed renderer). A response without
     * the tab — or with a tab carrying no browse endpoint — yields
     * null, never a crash.
     */
    private fun lyricsBrowseId(nextRoot: JsonObject): String? {
        for (tabElement in findAll(nextRoot, "tabRenderer")) {
            val tab = tabElement.asObj() ?: continue
            if (tab.str("title")?.trim() != "Lyrics") continue
            return tab.obj("endpoint").obj("browseEndpoint").str("browseId")
                ?: tab.obj("navigationEndpoint").obj("browseEndpoint").str("browseId")
        }
        return null
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * Parses LRC text (`[mm:ss.xx]line` per line; the fraction may be
     * centiseconds or milliseconds — it is read as a decimal fraction
     * of a second either way) into timed lines. Lines without a
     * timestamp (metadata tags, blanks) are skipped; a line carrying
     * several timestamps yields one entry per timestamp.
     */
    private fun parseLrc(syncedLyrics: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        for (rawLine in syncedLyrics.lines()) {
            val stamps = LRC_TIMESTAMP_REGEX.findAll(rawLine).toList()
            if (stamps.isEmpty()) continue
            val text = LRC_TIMESTAMP_REGEX.replace(rawLine, "").trim()
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLongOrNull() ?: continue
                val seconds = stamp.groupValues[2].toLongOrNull() ?: continue
                val fraction = stamp.groupValues[3]
                val fractionMs = if (fraction.isEmpty()) {
                    0L
                } else {
                    fraction.take(3).padEnd(3, '0').toLong()
                }
                lines.add(
                    LyricsLine(
                        text = text,
                        startMs = (minutes * 60 + seconds) * 1000 + fractionMs,
                    )
                )
            }
        }
        return lines
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
        // The switcher endpoint is a YouTube web-app XHR: it is built
        // to be called by the page, with the page's presentation. Send
        // the WEB_REMIX user agent and a JSON Accept alongside the
        // session headers — the bare OkHttp default UA is not how this
        // endpoint is ever legitimately consumed, and Google's
        // session checks treat unknown presentations inconsistently
        // (2026-10-24 sign-in hunt).
        val switcherHeaders = auth.headers(nowEpochSeconds()) + mapOf(
            "User-Agent" to YoutubeiContexts.WEB_REMIX.userAgent,
            "Accept" to "application/json",
        )
        val response = try {
            transport.get(ACCOUNT_SWITCHER_URL, switcherHeaders)
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
        const val LRCLIB_GET_URL = "https://lrclib.net/api/get"
        const val NO_LYRICS_REASON = "YouTube offers no lyrics for this track"
        val VISITOR_DATA_REGEX = Regex("\"visitorData\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val LRC_TIMESTAMP_REGEX = Regex("""\[(\d+):(\d{2})(?:\.(\d+))?\]""")
    }
}

/**
 * A session's like state for one track, as the player response's
 * `likeStatus` field reports it (Wave 2). Absence of the field is not
 * a fourth state — [YoutubeiClient.likeStatus] returns null for it.
 */
enum class LikeStatus {
    LIKED,
    DISLIKED,
    INDIFFERENT,
}
