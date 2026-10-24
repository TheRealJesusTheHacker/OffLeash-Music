package dev.cyberdogs.offleash.core.parse

import dev.cyberdogs.offleash.core.model.AlbumPage
import dev.cyberdogs.offleash.core.model.AlbumSummary
import dev.cyberdogs.offleash.core.model.ArtistPage
import dev.cyberdogs.offleash.core.model.ArtistSummary
import dev.cyberdogs.offleash.core.model.FeedItem
import dev.cyberdogs.offleash.core.model.MoodCategory
import dev.cyberdogs.offleash.core.model.PlaylistPage
import dev.cyberdogs.offleash.core.model.PlaylistSummary
import dev.cyberdogs.offleash.core.model.SearchFilter
import dev.cyberdogs.offleash.core.model.SearchResults
import dev.cyberdogs.offleash.core.model.Section
import dev.cyberdogs.offleash.core.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * youtubei response parsers, written against the real responses recorded
 * 2026-10-23 (see core/src/test/resources/fixtures). Strategy per SPEC §3:
 * generic tree walking plus renderer-specific extractors; every lookup is
 * total, unknown renderers are skipped, nothing here throws on shape.
 */
object YoutubeiParsers {

    private val TYPE_LABELS = setOf(
        "Album", "Single", "EP", "Song", "Video", "Episode",
        "Playlist", "Chart", "Profile", "Artist", "Podcast",
    )
    private val YEAR_REGEX = Regex("""(19|20)\d{2}""")
    private val COUNT_REGEX = Regex("""([\d,]+)\s+(songs|tracks)""")

    // ------------------------------------------------------------------
    // Shared row extractors
    // ------------------------------------------------------------------

    private fun flexColumnRuns(item: JsonObject, fixed: Boolean): List<List<JsonObject>> {
        val key = if (fixed) "fixedColumns" else "flexColumns"
        val rendererKey =
            if (fixed) "musicResponsiveListItemFixedColumnRenderer"
            else "musicResponsiveListItemFlexColumnRenderer"
        return item.arr(key)?.map { column ->
            runsOf(column.asObj().obj(rendererKey).obj("text"))
        } ?: emptyList()
    }

    private fun columnText(runs: List<JsonObject>): String =
        runs.joinToString("") { it.str("text") ?: "" }.trim()

    /** The browse endpoint on a row/card itself (not on one of its text runs). */
    private fun ownBrowseEndpoint(item: JsonObject): JsonObject? =
        item.obj("navigationEndpoint").obj("browseEndpoint")

    private fun pageTypeOf(browseEndpoint: JsonObject?): String? =
        browseEndpoint
            .obj("browseEndpointContextSupportedConfigs")
            .obj("browseEndpointContextMusicConfig")
            .str("pageType")

    private fun inferPageType(browseId: String?): String? = when {
        browseId == null -> null
        browseId.startsWith("MPREb") -> "MUSIC_PAGE_TYPE_ALBUM"
        browseId.startsWith("VL") -> "MUSIC_PAGE_TYPE_PLAYLIST"
        browseId.startsWith("UC") -> "MUSIC_PAGE_TYPE_ARTIST"
        else -> null
    }

    private fun parseCount(text: String): Int? =
        COUNT_REGEX.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

    /**
     * A `musicResponsiveListItemRenderer` row. Depending on the row's own
     * browse endpoint it is a track, an album, an artist or a playlist.
     */
    fun parseResponsiveListItem(item: JsonObject): FeedItem? {
        val flex = flexColumnRuns(item, fixed = false)
        val title = flex.getOrNull(0)?.let { columnText(it) }?.takeIf { it.isNotEmpty() }
            ?: return null
        val thumbnail = thumbnailUrlOf(item)
        val browse = ownBrowseEndpoint(item)
        val pageType = pageTypeOf(browse) ?: inferPageType(browse.str("browseId"))
        if (browse != null && pageType != null) {
            val browseId = browse.str("browseId") ?: return null
            val byline = flex.getOrNull(1)?.let { segmentsOf(it) } ?: emptyList()
            val bylineText = flex.getOrNull(1)?.let { columnText(it) }
            when (pageType) {
                "MUSIC_PAGE_TYPE_ALBUM" ->
                    return FeedItem.OfAlbum(albumSummaryFromByline(browseId, title, byline, thumbnail))
                "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL", "MUSIC_PAGE_TYPE_NON_MUSIC" ->
                    return FeedItem.OfArtist(
                        ArtistSummary(browseId, title, thumbnail, bylineText?.takeIf { it.isNotEmpty() })
                    )
                "MUSIC_PAGE_TYPE_PLAYLIST" ->
                    return FeedItem.OfPlaylist(playlistSummaryFromByline(browseId, title, byline, thumbnail))
                else -> return null // podcasts and anything else: not Phase 1 feed items
            }
        }
        return parseTrackFromResponsive(item, title, flex)?.let { FeedItem.OfTrack(it) }
    }

    private fun albumSummaryFromByline(
        browseId: String,
        title: String,
        byline: List<TextSegment>,
        thumbnail: String?,
    ): AlbumSummary {
        var artist: String? = null
        var year: String? = null
        for (segment in byline) {
            val text = segment.text
            when {
                text in TYPE_LABELS -> Unit
                YEAR_REGEX.matches(text) -> year = text
                artist == null -> artist = text
            }
        }
        return AlbumSummary(browseId, title, artist, year, thumbnail)
    }

    private fun playlistSummaryFromByline(
        browseId: String,
        title: String,
        byline: List<TextSegment>,
        thumbnail: String?,
    ): PlaylistSummary {
        var author: String? = null
        var trackCount: Int? = null
        var sawTypeLabel = false
        for (segment in byline) {
            val text = segment.text
            when {
                text in TYPE_LABELS -> sawTypeLabel = true
                parseCount(text) != null -> trackCount = parseCount(text)
                text.endsWith("views") || text.endsWith("likes") -> Unit
                // The author is only named when the byline is typed
                // ("Playlist • <author> • …"); an untyped byline on a card
                // is usually an artist list, not an author.
                sawTypeLabel && author == null -> author = text
            }
        }
        return PlaylistSummary(browseId, title, author, trackCount, thumbnail)
    }

    /**
     * Track reading of a responsive row: flexColumns are title / byline /
     * album, duration comes from fixedColumns or a duration-shaped byline
     * segment (album rows omit the fixed column the coordinator saw in
     * other rows, and search rows carry the duration in the byline).
     */
    fun parseTrackFromResponsive(
        item: JsonObject,
        title: String = flexColumnRuns(item, fixed = false)
            .getOrNull(0)?.let { columnText(it) } ?: "",
        flex: List<List<JsonObject>> = flexColumnRuns(item, fixed = false),
    ): Track? {
        if (title.isEmpty()) return null
        val videoId = item.obj("playlistItemData").str("videoId")
            ?: flex.getOrNull(0)?.firstNotNullOfOrNull { run ->
                run.obj("navigationEndpoint").obj("watchEndpoint").str("videoId")
            }
            ?: findFirst(item.obj("overlay"), "watchEndpoint").asObj().str("videoId")
            ?: return null

        val artists = mutableListOf<String>()
        var artistBrowseId: String? = null
        var durationSeconds: Int? = null

        val bylineSegments = flex.getOrNull(1)?.let { segmentsOf(it) } ?: emptyList()
        for (segment in bylineSegments) {
            val text = segment.text
            when {
                text in TYPE_LABELS -> Unit
                isDurationText(text) -> durationSeconds = parseDurationSeconds(text)
                text.endsWith("views") || text.endsWith("plays") || text.endsWith("likes") -> Unit
                else -> {
                    artists.add(text)
                    if (artistBrowseId == null) {
                        artistBrowseId = segment.browseId()?.takeIf {
                            segment.pageType() == "MUSIC_PAGE_TYPE_ARTIST" ||
                                segment.pageType() == "MUSIC_PAGE_TYPE_USER_CHANNEL" ||
                                segment.pageType() == null
                        }
                    }
                }
            }
        }

        var album: String? = null
        var albumBrowseId: String? = null
        val albumColumn = flex.getOrNull(2)?.let { segmentsOf(it) } ?: emptyList()
        for (segment in albumColumn) {
            if (segment.pageType() == "MUSIC_PAGE_TYPE_ALBUM") {
                album = segment.text
                albumBrowseId = segment.browseId()
            }
        }

        if (durationSeconds == null) {
            val fixed = flexColumnRuns(item, fixed = true)
            for (column in fixed) {
                parseDurationSeconds(columnText(column))?.let { durationSeconds = it }
            }
        }

        return Track(
            videoId = videoId,
            title = title,
            artists = artists,
            artistBrowseId = artistBrowseId,
            album = album,
            albumBrowseId = albumBrowseId,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrlOf(item),
        )
    }

    /** A `musicTwoRowItemRenderer` card (home shelves, charts, artist pages). */
    fun parseTwoRowItem(item: JsonObject): FeedItem? {
        val title = textOf(item.obj("title"))?.takeIf { it.isNotEmpty() } ?: return null
        val thumbnail = thumbnailUrlOf(item)
        val subtitleSegments = segmentsOf(runsOf(item.obj("subtitle")))
        val browse = ownBrowseEndpoint(item)
            ?: subtitleSegments.firstNotNullOfOrNull { it.browseEndpoint() }
            ?: runsOf(item.obj("title")).firstNotNullOfOrNull { run ->
                run.obj("navigationEndpoint").obj("browseEndpoint")
            }
        val watchVideoId = item.obj("navigationEndpoint").obj("watchEndpoint").str("videoId")
        if (browse == null) {
            if (watchVideoId != null) {
                val artist = subtitleSegments.firstOrNull()?.text
                return FeedItem.OfTrack(
                    Track(
                        videoId = watchVideoId,
                        title = title,
                        artists = listOfNotNull(artist),
                        artistBrowseId = null,
                        album = null,
                        albumBrowseId = null,
                        durationSeconds = subtitleSegments
                            .firstNotNullOfOrNull { parseDurationSeconds(it.text) },
                        thumbnailUrl = thumbnail,
                    )
                )
            }
            return null
        }
        val browseId = browse.str("browseId") ?: return null
        val pageType = pageTypeOf(browse) ?: inferPageType(browseId) ?: return null
        return when (pageType) {
            "MUSIC_PAGE_TYPE_ALBUM" ->
                FeedItem.OfAlbum(albumSummaryFromByline(browseId, title, subtitleSegments, thumbnail))
            "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL", "MUSIC_PAGE_TYPE_NON_MUSIC" ->
                FeedItem.OfArtist(
                    ArtistSummary(browseId, title, thumbnail, textOf(item.obj("subtitle")))
                )
            "MUSIC_PAGE_TYPE_PLAYLIST" ->
                FeedItem.OfPlaylist(playlistSummaryFromByline(browseId, title, subtitleSegments, thumbnail))
            else -> null
        }
    }

    /** Any feed-item renderer we know, or null for anything else. */
    fun parseFeedItem(element: JsonElement?): FeedItem? {
        val obj = element.asObj() ?: return null
        obj.obj("musicResponsiveListItemRenderer")?.let { return parseResponsiveListItem(it) }
        obj.obj("musicTwoRowItemRenderer")?.let { return parseTwoRowItem(it) }
        return null
    }

    // ------------------------------------------------------------------
    // Sections (home, charts, search shelves, artist pages, mood pages)
    // ------------------------------------------------------------------

    private fun carouselTitle(shelf: JsonObject): String? =
        textOf(shelf.obj("header").obj("musicCarouselShelfBasicHeaderRenderer").obj("title"))
            ?: textOf(shelf.obj("header").obj("musicCarouselShelfHeaderRenderer").obj("title"))

    private fun itemsOf(contents: JsonElement?): List<FeedItem> =
        contents.asArr()?.mapNotNull { parseFeedItem(it) } ?: emptyList()

    /**
     * All sections of a browse/search response, in document order.
     * Shelves and carousels become titled sections; the loose
     * `itemSectionRenderer` rows the search response uses are grouped
     * into one untitled section, and the search `musicCardShelfRenderer`
     * (the top result) becomes its own untitled section holding the card
     * track. Empty shelves (e.g. the charts divider shelf) are dropped.
     */
    fun parseSections(root: JsonElement?): List<Section> {
        val sections = mutableListOf<Section>()
        walkSections(root, sections)
        return sections
    }

    private fun walkSections(node: JsonElement?, out: MutableList<Section>) {
        when (node) {
            is JsonObject -> {
                val sectionList = node.obj("sectionListRenderer")
                if (sectionList != null) {
                    processSectionContents(sectionList.arr("contents"), out)
                } else {
                    for (value in node.values) walkSections(value, out)
                }
            }
            is JsonArray -> for (element in node) walkSections(element, out)
            else -> Unit
        }
    }

    private fun processSectionContents(contents: JsonArray?, out: MutableList<Section>) {
        if (contents == null) return
        val anonymous = mutableListOf<FeedItem>()
        fun flushAnonymous() {
            if (anonymous.isNotEmpty()) {
                out.add(Section(null, anonymous.toList()))
                anonymous.clear()
            }
        }
        for (element in contents) {
            val obj = element.asObj() ?: continue
            when {
                obj.containsKey("musicCarouselShelfRenderer") -> {
                    flushAnonymous()
                    val shelf = obj.obj("musicCarouselShelfRenderer")!!
                    val items = itemsOf(shelf.arr("contents"))
                    if (items.isNotEmpty()) out.add(Section(carouselTitle(shelf), items))
                }
                obj.containsKey("musicShelfRenderer") -> {
                    flushAnonymous()
                    val shelf = obj.obj("musicShelfRenderer")!!
                    val items = itemsOf(shelf.arr("contents"))
                    if (items.isNotEmpty()) {
                        out.add(Section(textOf(shelf.obj("title")), items))
                    }
                }
                obj.containsKey("musicCardShelfRenderer") -> {
                    flushAnonymous()
                    val shelf = obj.obj("musicCardShelfRenderer")!!
                    // The card itself is the top-result track; its
                    // `contents` rows duplicate the main result list, so
                    // they are deliberately not repeated here.
                    parseCardShelfTrack(shelf)?.let { out.add(Section(null, listOf(FeedItem.OfTrack(it)))) }
                }
                obj.containsKey("itemSectionRenderer") -> {
                    val section = obj.obj("itemSectionRenderer")!!
                    anonymous.addAll(itemsOf(section.arr("contents")))
                }
                obj.containsKey("sectionListRenderer") -> {
                    flushAnonymous()
                    processSectionContents(obj.obj("sectionListRenderer").arr("contents"), out)
                }
                else -> {
                    flushAnonymous()
                    walkSections(obj, out)
                }
            }
        }
        flushAnonymous()
    }

    /** The track a search `musicCardShelfRenderer` card itself represents. */
    fun parseCardShelfTrack(shelf: JsonObject): Track? {
        val videoId = shelf.obj("onTap").obj("watchEndpoint").str("videoId")
            ?: findFirst(shelf.obj("thumbnailOverlay"), "watchEndpoint").asObj().str("videoId")
            ?: return null
        val title = textOf(shelf.obj("title"))?.takeIf { it.isNotEmpty() } ?: return null
        val segments = segmentsOf(runsOf(shelf.obj("subtitle")))
        val artists = mutableListOf<String>()
        var artistBrowseId: String? = null
        var durationSeconds: Int? = null
        for (segment in segments) {
            val text = segment.text
            when {
                text in TYPE_LABELS -> Unit
                isDurationText(text) -> durationSeconds = parseDurationSeconds(text)
                text.endsWith("views") || text.endsWith("plays") || text.endsWith("likes") -> Unit
                else -> {
                    artists.add(text)
                    if (artistBrowseId == null) artistBrowseId = segment.browseId()
                }
            }
        }
        return Track(
            videoId = videoId,
            title = title,
            artists = artists,
            artistBrowseId = artistBrowseId,
            album = null,
            albumBrowseId = null,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrlOf(shelf),
        )
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** Filter chips published by the search response itself. */
    fun parseSearchFilters(root: JsonElement?): List<SearchFilter> {
        val filters = mutableListOf<SearchFilter>()
        val seen = mutableSetOf<String>()
        for (chipElement in findAll(root, "chipCloudChipRenderer")) {
            val chip = chipElement.asObj() ?: continue
            val label = textOf(chip.obj("text"))?.takeIf { it.isNotEmpty() } ?: continue
            val params = chip.obj("navigationEndpoint").obj("searchEndpoint").str("params")
            if (seen.add(label)) filters.add(SearchFilter(label, params))
        }
        return filters
    }

    fun parseSearch(root: JsonElement?): SearchResults =
        SearchResults(sections = parseSections(root), filters = parseSearchFilters(root))

    // ------------------------------------------------------------------
    // Moods & genres
    // ------------------------------------------------------------------

    /**
     * Mood/genre category buttons. In the recorded response the label is
     * the button's `buttonText` and the target is
     * `clickCommand.browseEndpoint` (browseId
     * `FEmusic_moods_and_genres_category` plus per-category params).
     */
    fun parseMoods(root: JsonElement?): List<MoodCategory> {
        val categories = mutableListOf<MoodCategory>()
        for (buttonElement in findAll(root, "musicNavigationButtonRenderer")) {
            val button = buttonElement.asObj() ?: continue
            val title = textOf(button.obj("buttonText"))
                ?: textOf(button.obj("text"))
                ?: continue
            val endpoint = button.obj("clickCommand").obj("browseEndpoint")
                ?: button.obj("navigationEndpoint").obj("browseEndpoint")
                ?: continue
            val params = endpoint.str("params") ?: continue
            categories.add(
                MoodCategory(
                    title = title,
                    params = params,
                    browseId = endpoint.str("browseId") ?: MoodCategory(title, params).browseId,
                )
            )
        }
        return categories
    }

    // ------------------------------------------------------------------
    // Detail pages
    // ------------------------------------------------------------------

    /** The largest track shelf of a page (album shelf / playlist shelf). */
    private fun pageTracks(root: JsonElement?): List<Track> {
        var best: List<Track> = emptyList()
        for (key in listOf("musicShelfRenderer", "musicPlaylistShelfRenderer")) {
            for (shelfElement in findAll(root, key)) {
                val shelf = shelfElement.asObj() ?: continue
                val tracks = shelf.arr("contents")
                    ?.mapNotNull { element ->
                        element.asObj().obj("musicResponsiveListItemRenderer")
                            ?.let { parseResponsiveListItem(it) }
                    }
                    ?.mapNotNull { (it as? FeedItem.OfTrack)?.track }
                    ?: emptyList()
                if (tracks.size > best.size) best = tracks
            }
        }
        return best
    }

    private fun responsiveOrDetailHeader(root: JsonElement?): JsonObject? =
        findFirst(root, "musicResponsiveHeaderRenderer").asObj()
            ?: findFirst(root, "musicDetailHeaderRenderer").asObj()

    private fun headerYear(header: JsonObject): String? {
        for (segment in segmentsOf(runsOf(header.obj("subtitle")))) {
            if (YEAR_REGEX.matches(segment.text)) return segment.text
        }
        return null
    }

    fun parseAlbum(root: JsonElement?, browseId: String): AlbumPage {
        val header = responsiveOrDetailHeader(root)
        val title = header?.let { textOf(it.obj("title")) }
            ?: findFirst(root, "musicCardShelfRenderer").asObj()?.let { textOf(it.obj("title")) }
            ?: ""
        val artist = header?.let { textOf(it.obj("straplineTextOne")) }
            ?: header?.let { hdr ->
                segmentsOf(runsOf(hdr.obj("subtitle")))
                    .firstOrNull { it.text !in TYPE_LABELS && !YEAR_REGEX.matches(it.text) }
                    ?.text
            }
        val description = header?.obj("description")
            ?.obj("musicDescriptionShelfRenderer")
            ?.let { textOf(it.obj("description")) }
            ?: findFirst(root, "musicDescriptionShelfRenderer").asObj()
                ?.let { textOf(it.obj("description")) }
        val thumbnail = header?.let { thumbnailUrlOf(it) }
        val summary = AlbumSummary(
            browseId = browseId,
            title = title,
            artist = artist,
            year = header?.let { headerYear(it) },
            thumbnailUrl = thumbnail,
        )
        return AlbumPage(album = summary, description = description, tracks = pageTracks(root))
    }

    fun parsePlaylist(root: JsonElement?, browseId: String): PlaylistPage {
        val header = responsiveOrDetailHeader(root)
        val title = header?.let { textOf(it.obj("title")) } ?: ""
        val author = header?.let { hdr ->
            // The recorded response names the owner in the header facepile.
            textOf(hdr.obj("facepile").obj("avatarStackViewModel").obj("text"))
                ?: textOf(hdr.obj("straplineTextOne"))
                ?: segmentsOf(runsOf(hdr.obj("subtitle")))
                    .firstOrNull { it.text !in TYPE_LABELS && !YEAR_REGEX.matches(it.text) }
                    ?.text
        }
        val thumbnail = header?.let { thumbnailUrlOf(it) }
        return PlaylistPage(
            title = title,
            author = author,
            thumbnailUrl = thumbnail,
            tracks = pageTracks(root),
        )
    }

    fun parseArtist(root: JsonElement?): ArtistPage {
        val headerRoot = root.asObj().obj("header")
        val visual = headerRoot.obj("musicVisualHeaderRenderer")
        val immersive = headerRoot.obj("musicImmersiveHeaderRenderer")
            ?: findFirst(root, "musicImmersiveHeaderRenderer").asObj()
        val name = visual?.let { textOf(it.obj("title")) }
            ?: immersive?.let { textOf(it.obj("title")) }
            ?: responsiveOrDetailHeader(root)?.let { textOf(it.obj("title")) }
            ?: ""
        val thumbnail = visual?.let { thumbnailUrlOf(it) }
            ?: immersive?.let { thumbnailUrlOf(it) }
        return ArtistPage(
            name = name,
            thumbnailUrl = thumbnail,
            sections = parseSections(root.asObj().obj("contents") ?: root),
        )
    }

    // ------------------------------------------------------------------
    // Next / related
    // ------------------------------------------------------------------

    /** Every `playlistPanelVideoRenderer` of a `next` response, in order. */
    fun parseNextTracks(root: JsonElement?): List<Track> {
        val tracks = mutableListOf<Track>()
        for (panelElement in findAll(root, "playlistPanelVideoRenderer")) {
            val panel = panelElement.asObj() ?: continue
            val videoId = panel.str("videoId")
                ?: panel.obj("navigationEndpoint").obj("watchEndpoint").str("videoId")
                ?: continue
            val title = textOf(panel.obj("title"))?.takeIf { it.isNotEmpty() } ?: continue
            val bylineSegments = segmentsOf(runsOf(panel.obj("longBylineText")))
            val artists = mutableListOf<String>()
            var artistBrowseId: String? = null
            for (segment in bylineSegments) {
                val text = segment.text
                if (text.endsWith("views") || text.endsWith("likes") || text.endsWith("plays")) break
                artists.add(text)
                if (artistBrowseId == null) artistBrowseId = segment.browseId()
                break // panel bylines name one artist, then view/like stats
            }
            if (artists.isEmpty()) {
                textOf(panel.obj("shortBylineText"))?.let { artists.add(it) }
            }
            tracks.add(
                Track(
                    videoId = videoId,
                    title = title,
                    artists = artists,
                    artistBrowseId = artistBrowseId,
                    album = null,
                    albumBrowseId = null,
                    durationSeconds = parseDurationSeconds(textOf(panel.obj("lengthText"))),
                    thumbnailUrl = largestThumbnailUrl(panel.obj("thumbnail").arr("thumbnails")),
                )
            )
        }
        return tracks
    }
}
