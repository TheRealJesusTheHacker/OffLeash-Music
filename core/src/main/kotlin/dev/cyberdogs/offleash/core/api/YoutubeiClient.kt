package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
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
import dev.cyberdogs.offleash.core.model.TrackContinuation
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
        return SearchResults(
            sections = parsed.sections,
            filters = filters,
            continuation = parsed.continuation,
        )
    }

    /**
     * The next page of a playlist (or of Liked Songs, whose pages are
     * playlist pages): a browse POST carrying only the continuation
     * token the previous page offered. The response's own offered
     * token comes back on the result; callers terminate via
     * [dev.cyberdogs.offleash.core.model.ContinuationTracker], which
     * also ends paging when a misbehaving server repeats a token.
     */
    fun playlistContinuation(
        continuation: String,
        auth: AuthSession? = null,
    ): TrackContinuation {
        val body = buildJsonObject {
            put("context", contextJsonWithVisitor(YoutubeiContexts.WEB_REMIX.toJson(), auth))
            put("continuation", continuation)
        }
        val root = postJson("browse", YoutubeiContexts.WEB_REMIX, body, auth)
        return YoutubeiParsers.parsePlaylistContinuation(root)
    }

    /** The next page of search results for [continuation]. */
    fun searchContinuation(continuation: String): SearchResults {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.WEB_REMIX.toJson())
            put("continuation", continuation)
        }
        val root = postJson("search", YoutubeiContexts.WEB_REMIX, body)
        val parsed = YoutubeiParsers.parseSearch(root)
        val filters = if (parsed.filters.isEmpty()) {
            SearchFilters.ALL_FILTERS
        } else {
            listOf(SearchFilters.ALL) + parsed.filters.filter { it.label != SearchFilters.ALL.label }
        }
        return SearchResults(
            sections = parsed.sections,
            filters = filters,
            continuation = parsed.continuation,
        )
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
            // The plain fixed reason, NOT e.message (bug sweep
            // round 6): an OffleashException's message embeds the
            // server's own words (ContextRejected carries the raw
            // server message) or the transport's detail
            // (NetworkFailure carries the failing call's message,
            // which names hosts/addresses) — and this reason is
            // USER COPY, rendered verbatim by the player's lyrics
            // panel and then cached for the process. Raw server or
            // transport text never reaches a message the user reads
            // (the ErrorMessages rule); the generic catch below has
            // always used the fixed string, and this catch now
            // matches it.
            LyricsResult.Unavailable("Lyrics are unavailable for this track.")
        } catch (e: Exception) {
            // The documented contract is "never an exception": any
            // other failure on the way (a drifted shape the parsers
            // didn't model, a transport quirk) is Unavailable too.
            LyricsResult.Unavailable("Lyrics are unavailable for this track.")
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
        } catch (t: Throwable) {
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
     *
     * An `[offset:±ms]` metadata tag shifts EVERY line by that many
     * milliseconds (the mainstream LRC convention: the tag's value is
     * added to each timestamp, so a positive offset makes the lines
     * land later). Until the bug sweep the tag was dropped like any
     * other metadata line, so an offset-carrying track's synced
     * lyrics drifted by exactly the tag value from start to finish.
     * A shifted start never goes below zero.
     */
    private fun parseLrc(syncedLyrics: String): List<LyricsLine> {
        val offsetMs = LRC_OFFSET_REGEX.find(syncedLyrics)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
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
                // Checked arithmetic (round 2): the minute field is
                // unbounded digits, and the plain Long math wrapped
                // silently for absurd stamps — the coerceAtLeast
                // then pinned the line at 0, claiming it current
                // from the track's first moment. An overflowing
                // stamp is junk and is skipped, like a missing one.
                val startMs = try {
                    Math.addExact(
                        Math.addExact(
                            Math.multiplyExact(
                                Math.addExact(Math.multiplyExact(minutes, 60L), seconds),
                                1000L,
                            ),
                            fractionMs,
                        ),
                        offsetMs,
                    )
                } catch (e: ArithmeticException) {
                    continue
                }
                lines.add(
                    LyricsLine(
                        text = text,
                        startMs = startMs.coerceAtLeast(0L),
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
     * response lists no identity (a signed-out session).
     *
     * The request is built exactly the way the endpoint's own web
     * surface builds it: a GET on `www.youtube.com` with the session
     * cookies and a SAPISIDHASH minted for the `www.youtube.com`
     * origin (see [AuthSession.authorizationHeader]). Until the
     * 2026-10-27 sign-in hunt this call went to the music host with
     * a music-origin hash, and — the actual defect — its answer was
     * parsed as a flat `{"accounts": [...]}` list, a shape this
     * endpoint has never returned. The real answer is the account
     * menu structure parsed by [parseSwitcherIdentities]; the flat
     * shape existed only in a hand-derived test fixture, so the
     * suite certified a response that never occurs while the real
     * one parsed to zero identities on every attempt.
     */
    fun accountIdentities(auth: AuthSession): IdentityLookup {
        // The switcher endpoint is a YouTube web-app XHR: it is built
        // to be called by the page, with the page's presentation. Send
        // the WEB_REMIX user agent and a JSON Accept alongside the
        // session headers — the bare OkHttp default UA is not how this
        // endpoint is ever legitimately consumed, and Google's
        // session checks treat unknown presentations inconsistently
        // (2026-10-24 sign-in hunt).
        val switcherHeaders = auth.headers(nowEpochSeconds(), ACCOUNT_SWITCHER_ORIGIN) + mapOf(
            "User-Agent" to YoutubeiContexts.WEB_REMIX.userAgent,
            "Accept" to "application/json",
        )
        val response = try {
            transport.get(ACCOUNT_SWITCHER_URL, switcherHeaders)
        } catch (e: Exception) {
            // Class name only (round 2 scrub): this detail is written
            // to the sign-in diagnostics, and a transport exception's
            // message can embed the host/address it failed to reach.
            // GoogleAuth's "transport: <class>" is the house shape.
            return IdentityLookup.Failed("transport: ${e.javaClass.simpleName}")
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
            // Class name only: decoding errors echo the body, and
            // this string lands on the diagnostics card.
            return IdentityLookup.Failed(
                "account response was not valid JSON (${e.javaClass.simpleName})"
            )
        } catch (t: Throwable) {
            // Same never-throws contract as the parse below: a deeply
            // nested body overflows the parser's own stack (an Error).
            return IdentityLookup.Failed(
                "account response was not valid JSON (${t.javaClass.simpleName})"
            )
        }
        // The parse walk is total by construction — every helper is
        // nullable-safe, and the 2026-10-27 crash hunt proved it
        // against 23 structural variants (no/several selected, many
        // sections, missing optional fields, alien renderer kinds,
        // deep and wide trees) without one escape. This boundary is
        // still wrapped, Throwable and not just Exception: the input
        // is a network response nobody modeled exhaustively, and a
        // pathological tree failing with an Error (a recursive walk
        // running out of stack) must degrade to the diagnostics card
        // naming the stage, never to a dead process. The lookup's
        // "never throws" contract is the load-bearing one.
        val identities = try {
            parseSwitcherIdentities(root)
        } catch (t: Throwable) {
            return IdentityLookup.Failed(
                "account response couldn't be read (${t.javaClass.simpleName})" +
                    " — shape: ${switcherShapeSummary(root)}"
            )
        }
        return IdentityLookup.Found(identities, switcherShapeSummary(root))
    }

    /**
     * What the switcher answer looked like, for the diagnostics
     * panel: the top-level keys and how many account sections and
     * identity items the menu carried. Names and counts only — no
     * value from the response is ever included. Defensive by
     * contract: a summary that itself fails reads "unavailable"
     * rather than becoming the failure it was describing.
     */
    private fun switcherShapeSummary(root: JsonElement): String = try {
        val topKeys = root.asObj()?.keys?.joinToString(", ") ?: "not an object"
        val sections = findAll(root, "accountSectionListRenderer").size
        val items = findAll(root, "accountItem").size
        "top-level keys [$topKeys], $sections account section(s), $items identity item(s)"
    } catch (t: Throwable) {
        "unavailable (${t.javaClass.simpleName})"
    }

    /**
     * Parses the switcher's real response shape: the account menu.
     *
     * The body nests, under
     * `data.actions[].getMultiPageMenuAction.menu.multiPageMenuRenderer`,
     * one `accountSectionListRenderer` section per signed-in Google
     * account. A section's `googleAccountHeaderRenderer` header
     * carries that account's name and email; every `accountItem`
     * inside it is one selectable YouTube identity of the account —
     * the personal identity and any brand channels. Per identity:
     *
     * - name / avatar: `accountName` and `accountPhoto.thumbnails`;
     * - selected: the BOOLEAN `isSelected` (not a string);
     * - pageId: the `pageIdToken` among the item's
     *   `serviceEndpoint.selectActiveIdentityEndpoint.supportedTokens`
     *   — present for brand identities, absent for the personal one;
     * - authUser: the `authuser=` parameter of the item's
     *   `accountSigninToken` sign-in URL — the Google account's index
     *   in the session, SHARED by all its identities (the pageId is
     *   what distinguishes them, never the index). Items whose token
     *   carries no readable index fall back to their section's
     *   position, which enumerates the same accounts in the same
     *   order;
     * - email: the section header's — brand identities have no
     *   email of their own.
     *
     * The shape follows, field for field, the paths two independent
     * production parsers of this endpoint read (Rehike's
     * `Switcher::parseResponse` and `SignInV2\Parser\SwitcherParser`);
     * the test fixture derived from them is the regression pin. The
     * walk is by key search rather than by the full literal path, so
     * menu sections the app doesn't model can't break it.
     */
    private fun parseSwitcherIdentities(root: JsonElement): List<AccountIdentity> {
        val identities = mutableListOf<AccountIdentity>()
        val sections = findAll(root, "accountSectionListRenderer")
        for ((sectionIndex, sectionElement) in sections.withIndex()) {
            val section = sectionElement.asObj() ?: continue
            val header = section.obj("header").obj("googleAccountHeaderRenderer")
            val email = textOf(header?.get("email"))
                ?: textOf(
                    findFirst(section, "accountItemSectionHeaderRenderer")
                        .asObj()?.get("title")
                )
            for (itemElement in findAll(section, "accountItem")) {
                val item = itemElement.asObj() ?: continue
                val name = textOf(item["accountName"]) ?: continue
                val avatar = largestThumbnailUrl(item.obj("accountPhoto").arr("thumbnails"))
                val selected = (item["isSelected"] as? JsonPrimitive)?.booleanOrNull == true
                var pageId: String? = null
                var authUser: Int? = null
                val tokens = item.obj("serviceEndpoint")
                    .obj("selectActiveIdentityEndpoint")
                    .arr("supportedTokens")
                if (tokens != null) {
                    for (tokenElement in tokens) {
                        val token = tokenElement.asObj() ?: continue
                        token.obj("pageIdToken").str("pageId")?.let { pageId = it }
                        token.obj("accountSigninToken").str("signinUrl")?.let { url ->
                            AUTHUSER_PARAM_REGEX.find(url)
                                ?.groupValues?.get(1)?.toIntOrNull()
                                ?.let { authUser = it }
                        }
                    }
                }
                identities.add(
                    AccountIdentity(
                        name = name,
                        email = email,
                        avatarUrl = avatar,
                        authUser = authUser ?: sectionIndex,
                        pageId = pageId,
                        isSelected = selected,
                    )
                )
            }
        }
        // The endpoint flags the active identity; when it flags none,
        // the first identity is the session's default.
        if (identities.isNotEmpty() && identities.none { it.isSelected }) {
            identities[0] = identities[0].copy(isSelected = true)
        }
        return identities
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
    // Google OAuth session (0.7.0): the token road
    //
    // Every call in this section authenticates the way the official
    // YouTube Music app does: the ANDROID_MUSIC context plus an
    // `Authorization: Bearer <token>` header. No cookies, no
    // SAPISIDHASH, no visitor data — the token IS the session.
    // Failures map onto the same error taxonomy as the cookie road
    // (4xx → ContextRejected with the server's message, 5xx and
    // transport trouble → NetworkFailure), so callers handle both
    // roads identically.
    // ------------------------------------------------------------------

    /**
     * Who a Google OAuth Bearer token belongs to, through the
     * OpenID Connect userinfo endpoint — the token road's answer to
     * [accountIdentities], with the same never-throws
     * [IdentityLookup] contract: 401/403 → Rejected (the token is
     * dead or revoked), transport/other failures → Failed, a 200
     * with no name and no email → Found with an empty list.
     */
    fun oauthIdentity(bearerToken: String): IdentityLookup {
        val response = try {
            transport.get(
                GoogleOAuth.USERINFO_URL,
                mapOf(
                    "Authorization" to "Bearer $bearerToken",
                    "Accept" to "application/json",
                ),
            )
        } catch (e: Exception) {
            return IdentityLookup.Failed("transport: ${e.javaClass.simpleName}")
        }
        if (response.status == 401 || response.status == 403) {
            return IdentityLookup.Rejected(response.status)
        }
        if (response.status != 200) {
            return IdentityLookup.Failed("HTTP ${response.status}")
        }
        val root = try {
            Json.parseToJsonElement(stripXssiPrefix(response.body)).asObj()
        } catch (e: Exception) {
            return IdentityLookup.Failed(
                "userinfo response was not valid JSON (${e.javaClass.simpleName})"
            )
        } catch (t: Throwable) {
            return IdentityLookup.Failed(
                "userinfo response was not valid JSON (${t.javaClass.simpleName})"
            )
        } ?: return IdentityLookup.Failed("userinfo response was not a JSON object")
        val name = root.str("name")
        val email = root.str("email")
        if (name.isNullOrBlank() && email.isNullOrBlank()) {
            return IdentityLookup.Found(emptyList(), userinfoShapeSummary(root))
        }
        return IdentityLookup.Found(
            listOf(
                AccountIdentity(
                    name = name ?: "",
                    email = email,
                    avatarUrl = root.str("picture"),
                    authUser = 0,
                    pageId = null,
                    isSelected = true,
                )
            ),
            userinfoShapeSummary(root),
        )
    }

    /** Key names only — a userinfo value never enters diagnostics. */
    private fun userinfoShapeSummary(root: JsonObject): String =
        "userinfo keys [${root.keys.joinToString(", ")}]"

    /** The personalized home feed under the token session. */
    fun homeFeedOAuth(bearerToken: String, chip: HomeChip? = null): HomeFeed {
        val root = postJsonOAuth(
            "browse", YoutubeiContexts.ANDROID_MUSIC,
            browseBodyFor(
                YoutubeiContexts.ANDROID_MUSIC,
                chip?.browseId ?: "FEmusic_home",
                chip?.params,
            ),
            bearerToken,
        )
        return YoutubeiParsers.parseHome(root)
    }

    /** The token session's library playlists, in shelf order. */
    fun libraryPlaylistsOAuth(bearerToken: String): List<PlaylistSummary> =
        browseSectionsOAuth("FEmusic_liked_playlists", bearerToken)
            .flatMap { it.items }
            .filterIsInstance<FeedItem.OfPlaylist>()
            .map { it.playlist }

    /** The token session's Liked Songs (browse id `VLLM`, the "LM" list). */
    fun likedSongsOAuth(bearerToken: String): PlaylistPage =
        playlistOAuth(bearerToken, "VLLM")

    /** The next page of a playlist under the token session. */
    fun playlistContinuationOAuth(
        bearerToken: String,
        continuation: String,
    ): TrackContinuation {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.ANDROID_MUSIC.toJson())
            put("continuation", continuation)
        }
        val root = postJsonOAuth("browse", YoutubeiContexts.ANDROID_MUSIC, body, bearerToken)
        return YoutubeiParsers.parsePlaylistContinuation(root)
    }

    /** One playlist page under the token session. */
    fun playlistOAuth(
        bearerToken: String,
        browseId: String,
        params: String? = null,
    ): PlaylistPage {
        val root = postJsonOAuth(
            "browse", YoutubeiContexts.ANDROID_MUSIC,
            browseBodyFor(YoutubeiContexts.ANDROID_MUSIC, browseId, params),
            bearerToken,
        )
        return YoutubeiParsers.parsePlaylist(root, browseId)
    }

    /**
     * Playback resolution under the token session: a player POST on
     * ANDROID_MUSIC carrying the Bearer token. Structural probe
     * (2026-10-27): the surface enforces OAuth Bearer — a bad token
     * is refused 401 with Google's standard OAuth error — so this is
     * the music app's own playback road, not a hoped-for one. Where
     * it refuses a given track, [dev.cyberdogs.offleash.core.playback.PlaybackResolver]
     * falls back to the proven anonymous road for the stream; the
     * token still provides identity and library.
     */
    fun streamInfoOAuth(
        videoId: String,
        bearerToken: String,
        quality: QualityPreference = QualityPreference.HIGH,
    ): StreamInfo {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.ANDROID_MUSIC.toJson())
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val raw = postRawOAuth("player", YoutubeiContexts.ANDROID_MUSIC, body, bearerToken)
        return StreamResolver.resolve(raw, quality)
    }

    /** Likes or un-likes a track on the account, under the token session. */
    fun setLikeOAuth(videoId: String, bearerToken: String, liked: Boolean) {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.ANDROID_MUSIC.toJson())
            putJsonObject("target") {
                put("videoId", videoId)
            }
        }
        postJsonOAuth(
            if (liked) "like/like" else "like/removelike",
            YoutubeiContexts.ANDROID_MUSIC,
            body,
            bearerToken,
        )
    }

    /**
     * The token session's like state for a track, from the
     * ANDROID_MUSIC player response's `likeStatus` — the same field
     * the cookie road reads. Absent/unrecognized reads as null.
     */
    fun likeStatusOAuth(videoId: String, bearerToken: String): LikeStatus? {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.ANDROID_MUSIC.toJson())
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val root = postJsonOAuth("player", YoutubeiContexts.ANDROID_MUSIC, body, bearerToken)
        val raw = root.str("likeStatus") ?: root.obj("videoDetails").str("likeStatus")
        return when (raw) {
            "LIKE" -> LikeStatus.LIKED
            "DISLIKE" -> LikeStatus.DISLIKED
            "INDIFFERENT" -> LikeStatus.INDIFFERENT
            else -> null
        }
    }

    private fun browseSectionsOAuth(
        browseId: String,
        bearerToken: String,
        params: String? = null,
    ): List<Section> {
        val root = postJsonOAuth(
            "browse", YoutubeiContexts.ANDROID_MUSIC,
            browseBodyFor(YoutubeiContexts.ANDROID_MUSIC, browseId, params),
            bearerToken,
        )
        return YoutubeiParsers.parseSections(root)
    }

    /** A browse body on an explicit context (no visitor injection, ever). */
    private fun browseBodyFor(
        context: YoutubeiContext,
        browseId: String,
        params: String?,
    ): JsonObject = buildJsonObject {
        put("context", context.toJson())
        put("browseId", browseId)
        params?.let { put("params", it) }
    }

    private fun postJsonOAuth(
        path: String,
        context: YoutubeiContext,
        body: JsonObject,
        bearerToken: String,
    ): JsonObject {
        val raw = postRawOAuth(path, context, body, bearerToken)
        return parseJsonObject(raw, path)
    }

    /**
     * Parses a 200 response body into its root object, with the two
     * honesty rules the raw parse alone misses (bug sweep round 1):
     *
     * - A body carrying a top-level `error` object is an ERROR even
     *   when the HTTP status is 200 — youtubei serves that shape,
     *   and parsing it as content produced a fake-empty feed ("no
     *   results") for what was really a refusal. It maps to
     *   [ContextRejected] with the server's own message, exactly like
     *   the same body on a 4xx.
     * - The parse boundary catches Throwable, not just Exception:
     *   the JSON parser itself recurses, and a deeply nested hostile
     *   body (a captive portal's junk, a drifted tree) overflows its
     *   stack — an Error that must degrade to the named
     *   [ParseFailure], never crash the caller. The detail names the
     *   failure class only; body content never enters a message.
     */
    private fun parseJsonObject(raw: String, path: String): JsonObject {
        val root = try {
            Json.parseToJsonElement(raw).asObj()
        } catch (e: Exception) {
            // The failure class only — NEVER the exception's message:
            // kotlinx decoding errors echo the raw body back inside
            // their message ("JSON input: …"), and a response body
            // can carry signed URLs or token material that must
            // never reach diagnostics (bug sweep round 1, found by
            // the armor tests after their first green run).
            throw ParseFailure(
                "response for '$path' was not valid JSON (${e.javaClass.simpleName})"
            )
        } catch (t: Throwable) {
            throw ParseFailure(
                "response for '$path' could not be read (${t.javaClass.simpleName})"
            )
        } ?: throw ParseFailure("response for '$path' was not a JSON object")
        root.obj("error")?.let { error ->
            throw ContextRejected(error.str("message") ?: "unknown server error")
        }
        return root
    }

    /**
     * [postRaw]'s twin for the token road: identical failure mapping,
     * but the headers are the context's own plus the Bearer token,
     * and no session visitor data is ever injected.
     */
    private fun postRawOAuth(
        path: String,
        context: YoutubeiContext,
        body: JsonObject,
        bearerToken: String,
    ): String {
        val headers = context.headers() + ("Authorization" to "Bearer $bearerToken")
        val response = try {
            transport.post(context.endpointUrl(path), headers, body.toString())
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
        return parseJsonObject(raw, path)
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
    } catch (t: Throwable) {
        // A deeply nested error body can overflow the parser's own
        // stack; the caller still owes the named HTTP failure, not
        // an escaping Error (round 2).
        null
    }

    private companion object {
        const val ACCOUNT_SWITCHER_URL =
            "https://www.youtube.com/getAccountSwitcherEndpoint"
        const val ACCOUNT_SWITCHER_ORIGIN = "https://www.youtube.com"
        val AUTHUSER_PARAM_REGEX = Regex("""[?&]authuser=(\d+)""")
        const val VISITOR_DATA_URL = "https://music.youtube.com/sw.js_data"
        const val LRCLIB_GET_URL = "https://lrclib.net/api/get"
        const val NO_LYRICS_REASON = "YouTube offers no lyrics for this track"
        val VISITOR_DATA_REGEX = Regex("\"visitorData\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val LRC_TIMESTAMP_REGEX = Regex("""\[(\d+):(\d{2})(?:\.(\d+))?\]""")
        val LRC_OFFSET_REGEX = Regex("""\[offset:\s*([+-]?\d+)\s*\]""")
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
