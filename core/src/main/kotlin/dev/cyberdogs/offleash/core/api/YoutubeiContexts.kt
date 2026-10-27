package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.model.SearchFilter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A youtubei client context: the (clientName, clientVersion, userAgent,
 * apiKey, host) record a request is made as. Contexts are configuration,
 * not code paths — when Google retires a client version, the fix is a
 * data update here (SPEC §1).
 *
 * The contexts below are anonymous and public (they ship in every page
 * load of music.youtube.com / the official apps). Account sessions
 * (Phase 2) ride on top of WEB_REMIX via auth headers; no credential
 * material lives in a context record.
 */
data class YoutubeiContext(
    val clientName: String,
    val clientVersion: String,
    val apiKey: String,
    val host: String,
    val userAgent: String,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null,
) {
    /** The `context` object embedded in every youtubei request body. */
    fun toJson(): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "en")
            put("gl", "US")
            put("userAgent", userAgent)
            deviceMake?.let { put("deviceMake", it) }
            deviceModel?.let { put("deviceModel", it) }
            osName?.let { put("osName", it) }
            osVersion?.let { put("osVersion", it) }
            androidSdkVersion?.let { put("androidSdkVersion", it) }
        }
        putJsonObject("user") {
            put("lockedSafetyMode", false)
        }
    }

    fun endpointUrl(path: String): String =
        "https://$host/youtubei/v1/$path?key=$apiKey&prettyPrint=false"

    fun headers(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )
}

object YoutubeiContexts {

    /**
     * The YouTube Music web client. Used for browse, search and next.
     * Proven live 2026-10-23 against every Phase 1 browse surface.
     */
    val WEB_REMIX = YoutubeiContext(
        clientName = "WEB_REMIX",
        clientVersion = "1.20241127.01.00",
        apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
        host = "music.youtube.com",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    )

    /**
     * The iOS client. Used for player requests only: it is the context
     * that answers anonymously with direct (unciphered) stream URLs.
     * Version 20.10.4 proven live 2026-10-23 (playability OK, 27 direct
     * formats); the older 19.29.1 is refused with HTTP 400
     * "Precondition check failed." — keep this version fresh.
     */
    val IOS = YoutubeiContext(
        clientName = "IOS",
        clientVersion = "20.10.4",
        apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
        host = "www.youtube.com",
        userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)",
        deviceMake = "Apple",
        deviceModel = "iPhone16,2",
        osName = "iPhone",
        osVersion = "18.3.1.22D72",
    )

    /**
     * The Android client, the anonymous road's second context. Proven
     * live 2026-10-23 from the sandbox on a track the owner's phone saw
     * the iOS stream gate refuse (itag 251 served, bounded fetch 206):
     * some refusals are network-identity-specific, so the anonymous
     * road cascades across contexts before giving up.
     */
    val ANDROID = YoutubeiContext(
        clientName = "ANDROID",
        clientVersion = "20.31.34",
        apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
        host = "www.youtube.com",
        userAgent = "com.google.android.youtube/20.31.34 (Linux; U; Android 14; en_US)",
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = 34,
    )

    /**
     * The official YouTube Music Android app's context. This is the
     * context the OAuth Bearer session rides (0.7.0 Google sign-in):
     * Google's own music app authenticates its youtubei calls exactly
     * this way — this context plus an `Authorization: Bearer` header.
     * It shares the ANDROID client's API key (the key belongs to the
     * youtubei surface, not to one client name). Proven live
     * 2026-10-27 from the sandbox: `FEmusic_home` answered 200 with
     * this context, and a deliberately bad Bearer was refused 401 —
     * the surface enforces OAuth Bearer as its auth mechanism.
     */
    val ANDROID_MUSIC = YoutubeiContext(
        clientName = "ANDROID_MUSIC",
        clientVersion = "5.26.1",
        apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
        host = "music.youtube.com",
        userAgent = "com.google.android.apps.youtube.music/5.26.1 (Linux; U; Android 14; en_US)",
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = 34,
    )

    /**
     * The anonymous playback road, in order: contexts are tried one by
     * one at resolution level and the first that serves wins.
     */
    val ANONYMOUS_ROAD: List<YoutubeiContext> = listOf(IOS, ANDROID)
}

/**
 * The pinned search filters. The params strings are the ones the API
 * itself publishes in the search response's filter chips; Songs, Videos,
 * Albums and Artists are fixture-verified against the recorded search
 * response (captured 2026-10-23).
 */
object SearchFilters {
    val ALL = SearchFilter("All", null)
    val SONGS = SearchFilter("Songs", "EgWKAQIIAWoSEAQQAxAJEAUQChAQEBUQDhAR")
    val VIDEOS = SearchFilter("Videos", "EgWKAQIQAWoSEAQQAxAJEAUQChAQEBUQDhAR")
    val ALBUMS = SearchFilter("Albums", "EgWKAQIYAWoSEAQQAxAJEAUQChAQEBUQDhAR")
    val ARTISTS = SearchFilter("Artists", "EgWKAQIgAWoSEAQQAxAJEAUQChAQEBUQDhAR")

    // Pinned from API convention, fixture-unverified: the recorded search
    // fixture's chip row has no "Playlists" chip (it carries "Community
    // playlists" and "Featured playlists" instead, with different params).
    val PLAYLISTS = SearchFilter("Playlists", "EgWKAQIoAWoSEAQQAxAJEAUQChAQEBUQDhAR")

    val ALL_FILTERS: List<SearchFilter> = listOf(ALL, SONGS, VIDEOS, ALBUMS, ARTISTS, PLAYLISTS)
}
