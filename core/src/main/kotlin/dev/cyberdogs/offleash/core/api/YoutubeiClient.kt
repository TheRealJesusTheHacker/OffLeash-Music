package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.model.AlbumPage
import dev.cyberdogs.offleash.core.model.ArtistPage
import dev.cyberdogs.offleash.core.model.MoodCategory
import dev.cyberdogs.offleash.core.model.PlaylistPage
import dev.cyberdogs.offleash.core.model.SearchFilter
import dev.cyberdogs.offleash.core.model.SearchResults
import dev.cyberdogs.offleash.core.model.Section
import dev.cyberdogs.offleash.core.model.StreamInfo
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.core.parse.YoutubeiParsers
import dev.cyberdogs.offleash.core.parse.asObj
import dev.cyberdogs.offleash.core.parse.obj
import dev.cyberdogs.offleash.core.parse.str
import dev.cyberdogs.offleash.core.playback.StreamResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Synchronous youtubei client (SPEC §3). Blocking by design — callers in
 * the app run it on an IO dispatcher; tests drive it with a fixture
 * transport. Browse/search/next run on the WEB_REMIX context; the player
 * runs on the iOS context (SPEC §1).
 *
 * Every failure surfaces as a named [OffleashException] (SPEC §6):
 * transport exceptions and HTTP 5xx as [NetworkFailure], HTTP 4xx with
 * the server's own message as [ContextRejected], and bodies that aren't
 * recognizable JSON as [ParseFailure].
 */
class YoutubeiClient(
    private val transport: HttpTransport,
) {

    fun home(): List<Section> = browseSections("FEmusic_home")

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

    fun playlist(browseId: String, params: String? = null): PlaylistPage {
        val root = postJson("browse", YoutubeiContexts.WEB_REMIX, browseBody(browseId, params))
        return YoutubeiParsers.parsePlaylist(root, browseId)
    }

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

    fun streamInfo(videoId: String): StreamInfo {
        val body = buildJsonObject {
            put("context", YoutubeiContexts.IOS.toJson())
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val raw = postRaw("player", YoutubeiContexts.IOS, body)
        return StreamResolver.resolve(raw)
    }

    // ------------------------------------------------------------------

    private fun browseSections(browseId: String, params: String? = null): List<Section> {
        val root = postJson("browse", YoutubeiContexts.WEB_REMIX, browseBody(browseId, params))
        return YoutubeiParsers.parseSections(root)
    }

    private fun browseBody(browseId: String, params: String? = null): JsonObject = buildJsonObject {
        put("context", YoutubeiContexts.WEB_REMIX.toJson())
        put("browseId", browseId)
        params?.let { put("params", it) }
    }

    private fun postJson(path: String, context: YoutubeiContext, body: JsonObject): JsonObject {
        val raw = postRaw(path, context, body)
        return try {
            Json.parseToJsonElement(raw).asObj()
        } catch (e: Exception) {
            throw ParseFailure("response for '$path' was not valid JSON (${e.message})")
        } ?: throw ParseFailure("response for '$path' was not a JSON object")
    }

    private fun postRaw(path: String, context: YoutubeiContext, body: JsonObject): String {
        val response = try {
            transport.post(context.endpointUrl(path), context.headers(), body.toString())
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

    /** The `error.message` of a youtubei error body, when it has one. */
    private fun extractErrorMessage(body: String): String? = try {
        Json.parseToJsonElement(body).asObj().obj("error").str("message")
    } catch (e: Exception) {
        null
    }
}
