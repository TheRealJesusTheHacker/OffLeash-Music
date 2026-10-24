package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.model.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-level tests driven through a fixture transport: the parsers are
 * pinned to the truth of the responses recorded 2026-10-23.
 */
class YoutubeiClientFixtureTest {

    private fun clientFor(fixture: String) =
        YoutubeiClient(FixedTransport(body = Fixtures.load(fixture)))

    @Test
    fun homeSectionsMatchFixture() {
        val sections = clientFor("browse_home.json").home()
        assertTrue("expected >= 2 sections, got ${sections.size}", sections.size >= 2)
        assertEquals("Quick picks", sections[0].title)
        val tracks = sections.flatMap { it.items }.filterIsInstance<FeedItem.OfTrack>()
        assertTrue("expected >= 15 track items, got ${tracks.size}", tracks.size >= 15)
        val first = (sections[0].items.first() as FeedItem.OfTrack).track
        assertEquals("xukbqwRuN5w", first.videoId)
        assertEquals("Choosin' Texas", first.title)
        assertEquals(listOf("Ella Langley"), first.artists)
        assertEquals("UCecnyZYofHiBVDJpx1XNYOQ", first.artistBrowseId)
        assertEquals("Dandelion", first.album)
        assertEquals("MPREb_QC0L98k1tGe", first.albumBrowseId)
        val throwbacks = sections.first { it.title == "Throwbacks" }
        assertTrue(throwbacks.items.all { it is FeedItem.OfPlaylist })
        assertEquals(10, throwbacks.items.size)
    }

    @Test
    fun chartsSectionsMatchFixture() {
        val sections = clientFor("browse_charts.json").charts()
        val titles = sections.map { it.title }
        assertTrue(titles.toString(), titles.contains("Video charts"))
        assertTrue(titles.toString(), titles.contains("Top albums"))
        assertTrue(titles.toString(), titles.contains("Top artists"))
        val topAlbums = sections.first { it.title == "Top albums" }
        assertTrue(topAlbums.items.isNotEmpty())
        assertTrue(topAlbums.items.all { it is FeedItem.OfAlbum })
        val firstAlbum = (topAlbums.items.first() as FeedItem.OfAlbum).album
        assertEquals("Hamilton (Original Broadway Cast Recording)", firstAlbum.title)
        assertEquals("Lin-Manuel Miranda", firstAlbum.artist)
        assertEquals("2015", firstAlbum.year)
    }

    @Test
    fun searchResultsMatchFixture() {
        val results = clientFor("search.json").search("never gonna give you up")
        val byLabel = results.filters.associateBy { it.label }
        for (label in listOf("Songs", "Albums", "Artists")) {
            val filter = byLabel[label]
            assertNotNull("filter $label must be present", filter)
            assertTrue(
                "filter $label params must start with EgWKAQ, was ${filter!!.params}",
                filter.params!!.startsWith("EgWKAQ")
            )
        }
        assertEquals("EgWKAQIIAWoSEAQQAxAJEAUQChAQEBUQDhAR", byLabel["Songs"]!!.params)
        assertEquals("EgWKAQIYAWoSEAQQAxAJEAUQChAQEBUQDhAR", byLabel["Albums"]!!.params)
        assertEquals("EgWKAQIgAWoSEAQQAxAJEAUQChAQEBUQDhAR", byLabel["Artists"]!!.params)

        val tracks = results.sections.flatMap { it.items }
            .filterIsInstance<FeedItem.OfTrack>().map { it.track }
        val rick = tracks.firstOrNull { it.videoId == "dQw4w9WgXcQ" }
        assertNotNull("the top-result card track must be parsed", rick)
        assertEquals("Never Gonna Give You Up", rick!!.title)
        assertEquals(listOf("Rick Astley"), rick.artists)
        assertEquals("UCwZEU0wAwIyZb4x5G_KJp2w", rick.artistBrowseId)
        assertEquals(214, rick.durationSeconds)

        // The mixed result list carries typed rows too.
        val albums = results.sections.flatMap { it.items }.filterIsInstance<FeedItem.OfAlbum>()
        assertTrue("expected album rows in search results", albums.isNotEmpty())
        val artists = results.sections.flatMap { it.items }.filterIsInstance<FeedItem.OfArtist>()
        assertTrue(artists.any { it.artist.name == "Rick Astley" })
        val playlists = results.sections.flatMap { it.items }.filterIsInstance<FeedItem.OfPlaylist>()
        assertTrue(playlists.any { it.playlist.browseId == "VLPLd5Vfgy_sILZXNGHKlAHnwPOEhboVQNBG" })
    }

    @Test
    fun pinnedSearchFilterParamsMatchFixtureChips() {
        assertEquals(null, SearchFilters.ALL.params)
        assertEquals("EgWKAQIIAWoSEAQQAxAJEAUQChAQEBUQDhAR", SearchFilters.SONGS.params)
        assertEquals("EgWKAQIQAWoSEAQQAxAJEAUQChAQEBUQDhAR", SearchFilters.VIDEOS.params)
        assertEquals("EgWKAQIYAWoSEAQQAxAJEAUQChAQEBUQDhAR", SearchFilters.ALBUMS.params)
        assertEquals("EgWKAQIgAWoSEAQQAxAJEAUQChAQEBUQDhAR", SearchFilters.ARTISTS.params)
        assertEquals(
            listOf("All", "Songs", "Videos", "Albums", "Artists", "Playlists"),
            SearchFilters.ALL_FILTERS.map { it.label }
        )
    }

    @Test
    fun albumPageMatchesFixture() {
        val page = clientFor("album.json").album("MPREb_GbGA9XuA9g9")
        // The fixture's title uses a typographic apostrophe (U+2019).
        assertEquals("I’m The Problem", page.album.title)
        assertEquals("Morgan Wallen", page.album.artist)
        assertEquals("2025", page.album.year)
        assertEquals("MPREb_GbGA9XuA9g9", page.album.browseId)
        assertNotNull(page.album.thumbnailUrl)
        assertNotNull(page.description)
        assertTrue(page.description!!.contains("fourth studio album"))
        assertTrue("expected >= 10 tracks, got ${page.tracks.size}", page.tracks.size >= 10)
        assertEquals(37, page.tracks.size)
        val first = page.tracks.first()
        assertEquals("wIA-4LeRgsc", first.videoId)
        // Track rows spell the title with an ASCII apostrophe, unlike the header.
        assertEquals("I'm The Problem", first.title)
        assertEquals(listOf("Morgan Wallen"), first.artists)
        assertEquals("UC5xaQ6_dP7EGDmGLzVGZ1Ow", first.artistBrowseId)
        assertEquals(178, first.durationSeconds) // fixedColumns "2:58"
    }

    @Test
    fun playlistPageMatchesFixture() {
        val page = clientFor("playlist.json").playlist("VLPLd5Vfgy_sILZXNGHKlAHnwPOEhboVQNBG")
        assertEquals("Never Gonna Give You Up", page.title)
        assertEquals("ゆうきtvメイン", page.author) // header facepile
        assertNotNull(page.thumbnailUrl)
        assertTrue("expected >= 40 tracks, got ${page.tracks.size}", page.tracks.size >= 40)
        assertEquals(52, page.tracks.size)
        val first = page.tracks.first()
        assertEquals("dQw4w9WgXcQ", first.videoId)
        assertEquals("Never Gonna Give You Up", first.title)
        assertEquals(listOf("Rick Astley"), first.artists)
        assertEquals(214, first.durationSeconds) // fixedColumns "3:34"
    }

    @Test
    fun artistPageMatchesFixture() {
        val page = clientFor("artist.json").artist("UC0OoAEcydpNnBTualVhYM9A")
        assertEquals("LDR", page.name) // musicVisualHeaderRenderer title
        assertNotNull(page.thumbnailUrl)
        val playlists = page.sections.firstOrNull { it.title == "Playlists" }
        assertNotNull("artist page must carry its Playlists section", playlists)
        assertEquals(10, playlists!!.items.size)
        assertTrue(playlists.items.all { it is FeedItem.OfPlaylist })
    }

    @Test
    fun moodsMatchFixture() {
        val moods = clientFor("browse_moods.json").moods()
        assertEquals(40, moods.size)
        assertEquals("Chill", moods.first().title)
        assertEquals("FEmusic_moods_and_genres_category", moods.first().browseId)
        assertTrue(moods.first().params.startsWith("ggMP"))
        assertTrue(moods.any { it.title == "Workout" || it.title == "Party" || it.title == "Focus" })
    }

    @Test
    fun relatedSkipsSeedAndMatchesFixture() {
        val related = clientFor("next.json").related("dQw4w9WgXcQ")
        assertTrue("expected >= 40 related tracks, got ${related.size}", related.size >= 40)
        assertEquals(49, related.size) // 50 panels minus the seed itself
        assertTrue(related.none { it.videoId == "dQw4w9WgXcQ" })
        val first = related.first()
        assertEquals("yBwD4iYcWC4", first.videoId)
        assertEquals("She Wants to Dance", first.title)
        assertEquals(listOf("Rick Astley"), first.artists)
        assertEquals("UCwZEU0wAwIyZb4x5G_KJp2w", first.artistBrowseId)
        assertEquals(260, first.durationSeconds) // lengthText "4:20"
    }
}
