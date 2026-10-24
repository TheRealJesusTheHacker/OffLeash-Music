package dev.cyberdogs.offleash.core.model

/**
 * Plain model types for OffLeash Music.
 *
 * These are deliberately free of any JSON/serialization types: the public
 * surface of `:core` never leaks the wire format, so parsers can change
 * without touching callers.
 */

data class Track(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val artistBrowseId: String?,
    val album: String?,
    val albumBrowseId: String?,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
)

data class AlbumSummary(
    val browseId: String,
    val title: String,
    val artist: String?,
    val year: String?,
    val thumbnailUrl: String?,
)

data class ArtistSummary(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String?,
    val detail: String?,
)

data class PlaylistSummary(
    val browseId: String,
    val title: String,
    val author: String?,
    val trackCount: Int?,
    val thumbnailUrl: String?,
)

sealed interface FeedItem {
    data class OfTrack(val track: Track) : FeedItem
    data class OfAlbum(val album: AlbumSummary) : FeedItem
    data class OfArtist(val artist: ArtistSummary) : FeedItem
    data class OfPlaylist(val playlist: PlaylistSummary) : FeedItem
}

data class Section(
    val title: String?,
    val items: List<FeedItem>,
)

data class AlbumPage(
    val album: AlbumSummary,
    val description: String?,
    val tracks: List<Track>,
)

data class ArtistPage(
    val name: String,
    val thumbnailUrl: String?,
    val sections: List<Section>,
)

data class PlaylistPage(
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val tracks: List<Track>,
)

/** A search filter chip. The "All" filter is represented by `params == null`. */
data class SearchFilter(
    val label: String,
    val params: String?,
)

data class SearchResults(
    val sections: List<Section>,
    val filters: List<SearchFilter>,
)

data class MoodCategory(
    val title: String,
    val params: String,
    val browseId: String = "FEmusic_moods_and_genres_category",
)

data class StreamInfo(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long?,
    val approxDurationMs: Long?,
)

data class LocalPlaylist(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val tracks: List<Track>,
)

/**
 * One identity a session cookie can act as, learned from the account
 * switcher (Phase 2). [authUser] and [pageId] are the routing pair a
 * request must carry to act as this identity — a brand channel's
 * [pageId] is only valid together with its owning [authUser].
 */
data class AccountIdentity(
    val name: String,
    val email: String?,
    val avatarUrl: String?,
    val authUser: Int,
    val pageId: String?,
    val isSelected: Boolean,
)
