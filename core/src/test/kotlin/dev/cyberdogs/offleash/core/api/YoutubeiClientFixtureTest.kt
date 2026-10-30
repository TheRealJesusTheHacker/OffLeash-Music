package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.model.FeedItem
import dev.cyberdogs.offleash.core.model.MoodCategory
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

    // ------------------------------------------------------------------
    // Round 45: the edge-vs-middle census completed. Every surface
    // below was pinned only at its edges (first item / count /
    // by-title presence), and a middle reversal at its parse/client
    // joint ran green across the full suite (268/592) for each —
    // proven live this round, one construction per surface. The
    // exact recorded orders are now hardcoded from the fixtures
    // (ground truth by independent JSON document-order walk,
    // cross-checked against the parser's output).
    // ------------------------------------------------------------------

    @Test
    fun homeFeedReturnsTheRecordedFixturesExactSectionsAndItems() {
        // The home fixture carries exactly two sections; the feed
        // was pinned only for sections[0]'s title and the chips, so
        // dropping the LAST section in place inside homeFeed (the
        // parser hands its mutable section list straight through)
        // and reversing every section's middle items both ran
        // green while the home rails lost a shelf / scrambled.
        val feed = clientFor("browse_home.json").homeFeed()
        assertEquals(
            listOf("Quick picks", "Throwbacks"),
            feed.sections.map { it.title },
        )
        assertEquals(listOf(20, 10), feed.sections.map { it.items.size })
        val quickPicks = feed.sections[0].items
            .filterIsInstance<FeedItem.OfTrack>().map { it.track.videoId }
        assertEquals(
            listOf(
                "xukbqwRuN5w", "Z85lxckrtzg", "CLSuAhT71yU", "mzhbRUniU4U",
                "ljAH6jNaApU", "b4mUzvt_lIw", "vOfyxPRunFM", "8WtxJPXX4e0",
                "tdS3ijuqxW4", "28jp-30w8Lg", "nEUZPehC6rs", "lFQdcPTTzSg",
                "D4Ha40XUE1E", "9KcnxA36s04", "Ai6LZlMCtNE", "FJX0JPXD2nM",
                "HJ-3MI_absQ", "QY495Kk9gKE", "UHV0ja0vhzU", "nyNm-dVBMoo",
            ),
            quickPicks,
        )
        val throwbacks = feed.sections[1].items
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist.browseId }
        assertEquals(
            listOf(
                "VLRDCLAK5uy_lMzHW51iFg1Kx0d_2EHpzbOgCrwtu8cgI",
                "VLRDCLAK5uy_lvHI2Z7dSfpD5g8wvmePjWPfYwq5IgkLo",
                "VLRDCLAK5uy_lWy02cQBnTVTlwuRauaGKeUDH3L6PXNxI",
                "VLRDCLAK5uy_nZiG9ehz_MQoWQxY5yElsLHCcG0tv9PRg",
                "VLRDCLAK5uy_kN4eY_ibobGvCBwIJGEGpDjuwzYHIG_iE",
                "VLRDCLAK5uy_m_h-nx7OCFaq9AlyXv78lG0AuloqW_NUA",
                "VLRDCLAK5uy_kpxnNxJpPZjLKbL9WgvrPuErWkUxMP6x4",
                "VLRDCLAK5uy_m8tb7ahoXoyC82BkVgvWpJo2TUw8VnrlI",
                "VLRDCLAK5uy_lk7bC42AIcIBpSBiioTBrFdJeDNLSGCyE",
                "VLRDCLAK5uy_kfLKunrTVrL7hzgZz_skOKXn_MVzrpB6g",
            ),
            throwbacks,
        )
        // The chip re-browse rides the same return path: its
        // sections must carry the same recorded order (the
        // fixture transport answers every browse with the home
        // fixture, as in the HomeChipsTest re-browse pins).
        val chips = dev.cyberdogs.offleash.core.parse.YoutubeiParsers.parseHomeChips(
            kotlinx.serialization.json.Json.parseToJsonElement(
                Fixtures.load("browse_home.json"),
            ),
        )
        val rebrowsed = clientFor("browse_home.json")
            .homeFeed(chip = chips.first { it.label == "Relax" })
        assertEquals(
            listOf("Quick picks", "Throwbacks"),
            rebrowsed.sections.map { it.title },
        )
        assertEquals(listOf(20, 10), rebrowsed.sections.map { it.items.size })
    }

    @Test
    fun homeReturnsTheRecordedFixturesExactSectionsAndItems() {
        // The sections-only road (home()) was pinned the same
        // edge-only way; pin its full recorded order too, so the
        // shared browseSections joint is bound for this fixture
        // on every road that consumes it.
        val sections = clientFor("browse_home.json").home()
        assertEquals(
            listOf("Quick picks", "Throwbacks"),
            sections.map { it.title },
        )
        assertEquals(listOf(20, 10), sections.map { it.items.size })
        val quickPicks = sections[0].items
            .filterIsInstance<FeedItem.OfTrack>().map { it.track.videoId }
        assertEquals(
            listOf(
                "xukbqwRuN5w", "Z85lxckrtzg", "CLSuAhT71yU", "mzhbRUniU4U",
                "ljAH6jNaApU", "b4mUzvt_lIw", "vOfyxPRunFM", "8WtxJPXX4e0",
                "tdS3ijuqxW4", "28jp-30w8Lg", "nEUZPehC6rs", "lFQdcPTTzSg",
                "D4Ha40XUE1E", "9KcnxA36s04", "Ai6LZlMCtNE", "FJX0JPXD2nM",
                "HJ-3MI_absQ", "QY495Kk9gKE", "UHV0ja0vhzU", "nyNm-dVBMoo",
            ),
            quickPicks,
        )
    }

    @Test
    fun searchResultsReturnTheRecordedFixturesExactOrder() {
        // The search pin found the top-result track BY ID and the
        // typed rows BY PRESENCE, so reversing the result section's
        // middle items in search() ran green while the results
        // page rendered (and SectionRow queued) its middle
        // backwards; the published filter chips were likewise
        // read only through a by-label map, so reversing them ran
        // green too. Pin the exact recorded sequence: section
        // sizes, the flattened typed items in order, and the
        // filter labels in published order.
        val results = clientFor("search.json").search("never gonna give you up")
        assertEquals(listOf(1, 25), results.sections.map { it.items.size })
        fun kindId(item: FeedItem): String = when (item) {
            is FeedItem.OfTrack -> "T:" + item.track.videoId
            is FeedItem.OfAlbum -> "A:" + item.album.browseId
            is FeedItem.OfArtist -> "R:" + item.artist.browseId
            is FeedItem.OfPlaylist -> "P:" + item.playlist.browseId
        }
        assertEquals(
            listOf(
                "T:dQw4w9WgXcQ",
                "T:lYBUbBu4W08", "T:0Om-vyXny3Y",
                "A:MPREb_M0tRu9fOpa7", "A:MPREb_j2NKBCaSN1x", "A:MPREb_EiHRlD7vWaN",
                "T:0qgh3SPyYm4", "T:hZ7Om9gE-Oc", "T:xTO73XbiARY",
                "R:UCrtwSXAnOmk5TPqxRrmrXTw", "R:UCwZEU0wAwIyZb4x5G_KJp2w",
                "T:jBkDww-83dw", "T:MRW7d7PIZ6U", "T:HH7ohZOtsQM",
                "P:VLPLO1zVwxmFm30", "P:VLPLa96xoiRdUZdQto2ePfco4ulMBDBkbSjL",
                "P:VLPLd5Vfgy_sILZXNGHKlAHnwPOEhboVQNBG",
                "T:S4Q3EqtixDU", "T:GHoHxs9nyC4", "T:zFkf7cG_ZHo",
                "R:UC8r3Q7u1SJtHFI0WZznAnZA", "R:UCDsVDmLU4jOnrEurmbSz6aA",
                "R:UC0OoAEcydpNnBTualVhYM9A",
                "P:VLRDCLAK5uy_lMzHW51iFg1Kx0d_2EHpzbOgCrwtu8cgI",
                "P:VLRDCLAK5uy_lBdj-S7dlwHFWFuEWGbHSc8CX84Xw6zto",
                "P:VLRDCLAK5uy_k6FEgr-_4EVPoITSfmGGCMt1M6WtLBLbU",
            ),
            results.sections.flatMap { it.items }.map { kindId(it) },
        )
        assertEquals(
            listOf(
                "All", "Videos", "Songs", "Albums", "Artists",
                "Community playlists", "Episodes", "Profiles",
                "Featured playlists", "Podcasts",
            ),
            results.filters.map { it.label },
        )
    }

    @Test
    fun artistPageReturnsTheRecordedFixturesExactSectionOrder() {
        // The artist pin located the Playlists section BY TITLE
        // and asserted its count and item kind only, so reversing
        // its middle items in artist() ran green while the
        // artist page's cards queued/displayed scrambled. The
        // recorded fixture carries exactly this one section; pin
        // its exact recorded order.
        val page = clientFor("artist.json").artist("UC0OoAEcydpNnBTualVhYM9A")
        assertEquals(listOf("Playlists"), page.sections.map { it.title })
        val playlists = page.sections[0].items
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist.browseId }
        assertEquals(
            listOf(
                "VLPLa1di_9tE4wWJ80M9LpXa08i-100jCqF6",
                "VLPLa1di_9tE4wUijrszjs584uT_EeOexTQn",
                "VLPLTkem192ro_8",
                "VLPLPQaPujZ3Pks",
                "VLPLa1di_9tE4wWBxlsIU3XBfLp0Emuq7qE8",
                "VLPLa1di_9tE4wXRWsWmwgrQZA8qN39p_5xO",
                "VLPLa1di_9tE4wV9xs0B65YbaJ1oF_IV7_gx",
                "VLPLa1di_9tE4wWL1hwcUlf-u3zHhWvye7lg",
                "VLPLa1di_9tE4wVcUMNxsjLAPIuIxDMGKCDO",
                "VLPLa1di_9tE4wUM1DNXP3-RD60ZIV6cKpCn",
            ),
            playlists,
        )
    }

    @Test
    fun chartsSectionsItemsComeInFixtureOrder() {
        // Round 43 pinned the charts sections' titles, sizes, and
        // FIRST/LAST item of every section — edges only — so
        // reversing every section's middle items in charts() ran
        // green while each Charts shelf displayed its middle
        // backwards. Pin every section's full recorded order.
        val sections = clientFor("browse_charts.json").charts()
        val videoCharts = sections[0].items
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist.browseId }
        assertEquals(
            listOf(
                "VLPL4fGSI1pDJn4yCNzulPkUbxgr4pl0gmI-",
                "VLOLAK5uy_kNWGJvgWVqlt5LsFDL9Sdluly4M8TvGkM",
                "VLPL4fGSI1pDJn61unMfmrUSz68RT8IFFnks",
                "VLPL4fGSI1pDJn69On1f-8NAvX_CYlx7QyZc",
            ),
            videoCharts,
        )
        val genres = sections[1].items
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist.browseId }
        assertEquals(
            listOf(
                "VLPL4fGSI1pDJn7Wkr6Ll6ds1AhA42rT8uaU",
                "VLPL4fGSI1pDJn4w4wTTgOmP_S80PoCtbGrL",
                "VLPL4fGSI1pDJn4EBsWVeFpcSAVOFMfhyipg",
                "VLPL4fGSI1pDJn4rBU0RHnR6-b1_uE20CzRH",
                "VLPL4fGSI1pDJn4fmCoF1vKHLtivI0f9yHiF",
                "VLPL4fGSI1pDJn5LOptOQixqnzXNGjNXAgYY",
                "VLPL4fGSI1pDJn5O8siDeZuI_4hbk6JWtTX1",
                "VLPL4fGSI1pDJn77aK7sAW2AT0oOzo5inWY8",
            ),
            genres,
        )
        val albums = sections[2].items
            .filterIsInstance<FeedItem.OfAlbum>().map { it.album.browseId }
        assertEquals(
            listOf(
                "MPREb_iNWH4dO6PQO", "MPREb_V8RKzQSrtms", "MPREb_L2anhKI1Knd",
                "MPREb_gtAqZBtFDvm", "MPREb_LjIW4hKEFIk", "MPREb_L64BnYDQpHZ",
                "MPREb_0B7VSbvCk4h", "MPREb_eSR1QCwkrKw", "MPREb_OUh6Wf5Cn8H",
                "MPREb_YlJkhcgyqrg", "MPREb_gIabrzkEtSR", "MPREb_UkJNOQbd1RK",
                "MPREb_7Tqkp1pDodU", "MPREb_Mtj88BOEFLa", "MPREb_8LisnoK7FJH",
                "MPREb_tc3z4MYeOpY", "MPREb_fylklEw93Dp", "MPREb_lGEmdyqhFN5",
                "MPREb_jfCQjypdsln", "MPREb_ydqH1bhNxXV", "MPREb_hyeYr0nmnuP",
                "MPREb_LRfu61MqLoU", "MPREb_MYKnxdwBLIs", "MPREb_DycSiwjOPMo",
                "MPREb_wrUgXiSUzPw", "MPREb_0O5rr3thh50", "MPREb_kLY36z1cD4E",
                "MPREb_dNCCmdwegvj", "MPREb_ztv6DhWg3Rn", "MPREb_4JjyvXvgHC1",
                "MPREb_7p07QIJBGyG", "MPREb_ThyGRhfjSbZ", "MPREb_rFEKRR3bPAS",
                "MPREb_8iBjcbqpRI1", "MPREb_qwNla6OtnKT", "MPREb_IywulcIpQ1O",
                "MPREb_nHbCAGX6uUL", "MPREb_6sigFkPXE0T", "MPREb_E4p8dQ3RECj",
                "MPREb_qMlbe7gLeuH", "MPREb_feX1cs1aNqP", "MPREb_ZgWMotVlM2a",
                "MPREb_IrTVSodLnvP", "MPREb_n8UL81saTvI", "MPREb_BtXfAQVI3yJ",
                "MPREb_G8GnGrFOY75", "MPREb_fh4et6FVBzo", "MPREb_u2QoWh93SQk",
                "MPREb_wKlgketlqrg", "MPREb_dqWTncCjkSp",
            ),
            albums,
        )
        val artists = sections[3].items
            .filterIsInstance<FeedItem.OfArtist>().map { it.artist.browseId }
        assertEquals(
            listOf(
                "UCU6cE7pdJPc6DU2jSrKEsdQ", "UCR28YDxjDE3ogQROaNdnRbQ",
                "UC0kxNxFQCK6d2spPz5Sme7Q", "UCiY3z8HAGD6BlSNKVn2kSvQ",
                "UCoIOOL7QKuBhQHVKL8y7BEQ", "UC5xaQ6_dP7EGDmGLzVGZ1Ow",
                "UChWPNW87QHcXAsw2mzlsYNw", "UC1_liDR4fRFJgH4HoJeV8cw",
                "UCPC0L1d253x-KuMNwa05TpA", "UCedvOgsKFzcK3hA5taf3KoQ",
                "UC7n3gWRN0vQzgiOKc51aZ4w", "UC_z9AthnCGSAk_tZf-KqoFA",
                "UCX9kfYB9t0tnd6DYUC2iuKg", "UCSWKNHx2g_yk9VlErHqJaMw",
                "UClYV6hHlupm_S_ObS1W-DYw", "UCMXDyVR2tclKWhbqNforSyA",
                "UCzmabbKsmXlWnI9N2kKQ4lA", "UC4IAZ3dowcXyvVYBx4hucSQ",
                "UCOUlVCyTJqvT9AHeUlbK03w", "UCZn4r7heNOPY-C43YIywnVA",
                "UCbBaYg2UToDaoOwo-R6xi4g", "UCecnyZYofHiBVDJpx1XNYOQ",
                "UCvWtix2TtWGe9kffqnwdaMw", "UCwA5HHGsAlpU1UlhwWD52uA",
                "UCxgN32UVVztKAQd2HkXzBtw", "UCbn0GRdgsQtl9hlV-IqxFGg",
                "UCJKREB2c3dO8dgdo8FtdO_A", "UCjDCUfKytMMjrshUQJ30qxg",
                "UCATfo9SAdXImyc6ygfbzJBg", "UCprAFmT0C6O4X0ToEXpeFTQ",
                "UC0076UMUgEng8HORUw_MYHA", "UCE5XNpliPM-SmyFEp61tL_g",
                "UCo6JijJGA3IvIiPsawDK3Ww", "UCm7mbvc5QOn5JzkeCrk-wdw",
                "UCpB_98tUTs3zSiOxZuGPnOA", "UCGvj8kfUV5Q6lzECIrGY19g",
                "UCypKp6ICj83mgrtPyCBboiw", "UC0PsqWsDU7JJKqztI8V6spA",
                "UCRY5dYsbIN5TylSbd7gVnZg", "UC8zFXOPlDTI5lhy3fAn6E5w",
            ),
            artists,
        )
    }

    @Test
    fun moodPlaylistsReturnTheHomeFixturesExactItemOrder() {
        // The mood-category pin cross-derives its sections
        // against home() (titles + sizes) and reads only the
        // first track — reversing every section's middle items
        // in moodPlaylists() ran green while the category pages
        // rendered their middles backwards. Pin the stand-in
        // fixture's exact item order on this road itself.
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val category = MoodCategory(title = "Chill", params = "ggMPChillParams")
        val sections = YoutubeiClient(transport).moodPlaylists(category)
        assertEquals(
            listOf("Quick picks", "Throwbacks"),
            sections.map { it.title },
        )
        val quickPicks = sections[0].items
            .filterIsInstance<FeedItem.OfTrack>().map { it.track.videoId }
        assertEquals(
            listOf(
                "xukbqwRuN5w", "Z85lxckrtzg", "CLSuAhT71yU", "mzhbRUniU4U",
                "ljAH6jNaApU", "b4mUzvt_lIw", "vOfyxPRunFM", "8WtxJPXX4e0",
                "tdS3ijuqxW4", "28jp-30w8Lg", "nEUZPehC6rs", "lFQdcPTTzSg",
                "D4Ha40XUE1E", "9KcnxA36s04", "Ai6LZlMCtNE", "FJX0JPXD2nM",
                "HJ-3MI_absQ", "QY495Kk9gKE", "UHV0ja0vhzU", "nyNm-dVBMoo",
            ),
            quickPicks,
        )
        val throwbacks = sections[1].items
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist.browseId }
        assertEquals(
            listOf(
                "VLRDCLAK5uy_lMzHW51iFg1Kx0d_2EHpzbOgCrwtu8cgI",
                "VLRDCLAK5uy_lvHI2Z7dSfpD5g8wvmePjWPfYwq5IgkLo",
                "VLRDCLAK5uy_lWy02cQBnTVTlwuRauaGKeUDH3L6PXNxI",
                "VLRDCLAK5uy_nZiG9ehz_MQoWQxY5yElsLHCcG0tv9PRg",
                "VLRDCLAK5uy_kN4eY_ibobGvCBwIJGEGpDjuwzYHIG_iE",
                "VLRDCLAK5uy_m_h-nx7OCFaq9AlyXv78lG0AuloqW_NUA",
                "VLRDCLAK5uy_kpxnNxJpPZjLKbL9WgvrPuErWkUxMP6x4",
                "VLRDCLAK5uy_m8tb7ahoXoyC82BkVgvWpJo2TUw8VnrlI",
                "VLRDCLAK5uy_lk7bC42AIcIBpSBiioTBrFdJeDNLSGCyE",
                "VLRDCLAK5uy_kfLKunrTVrL7hzgZz_skOKXn_MVzrpB6g",
            ),
            throwbacks,
        )
    }

    @Test
    fun relatedReturnsTheRecordedFixturesExactOrder() {
        // The radio pin asserted the count (49), the seed's
        // absence, and the FIRST track only, so reversing the
        // middle of related() ran green while every radio queue
        // played its middle backwards. Pin the exact recorded
        // order of all 49 (the fixture's 50 panels minus the
        // seed itself, which stands first in the response).
        val related = clientFor("next.json").related("dQw4w9WgXcQ")
        assertEquals(
            listOf(
                "yBwD4iYcWC4", "aGCdLKXNF3w", "djV11Xbc914", "oRdxUFDoQe0",
                "HEXWRTEbj1I", "7YvAYIJSSZY", "qeMFqkcPYcg", "u-XqzY5y8uA",
                "eNvUS-6PTbs", "Gs069dndIYk", "PIb6AZdTr-A", "btPJPFnesV4",
                "I_izvAbhExY", "bWcASV2sey0", "6p-lDYPR2P8", "OMOGaugKpzs",
                "uxX2gA18grk", "OPf0YbXqDm0", "eYuUAGXN0KM", "PGNiXGX2nLU",
                "XEjLoHdbVeE", "4fndeDfaWCg", "BeyEGebJ1l4", "ltrMfT4Qz5Y",
                "3wxyN3z9PL4", "eH3giaIzONA", "yPYZpwSpKmA", "ZhIsAZO5gl0",
                "Wu8c5RdfL8Y", "nZXRV4MezEw", "rZlQ28OeGMI", "Zi_XLOBDo_Y",
                "RP0_8J7uxhs", "ZHwVBirqD2s", "H5v3kku4y6Q", "ApXoWvfEYVU",
                "XXYlFuWEuKI", "VPRjCeoBqrI", "v2AC41dglnM", "3D8bmo5Dlwc",
                "pIgZ7gMze7A", "ru0K8uYEZWw", "FTQbiNvZqaY", "f4Mc-NYPHaQ",
                "OnT58cIJSpw", "Lrle0x_DHBM", "lDK9QqIzhwk", "ILWSp0m9G2U",
                "fcnDmrtj6Sk",
            ),
            related.map { it.videoId },
        )
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
    fun chartsSectionsComeInFixtureOrderWithEverySectionsItems() {
        // Round 43: the charts pin above checked the section
        // titles by containment and one section's first item,
        // so reversing the sections in charts() itself ran
        // green across the full suite while the Charts page
        // rendered its shelves upside down. Pin the section
        // order, every section's size, and the first and last
        // item of every section from the recorded fixture.
        val sections = clientFor("browse_charts.json").charts()
        assertEquals(
            listOf("Video charts", "Genres", "Top albums", "Top artists"),
            sections.map { it.title },
        )
        assertEquals(listOf(4, 8, 50, 40), sections.map { it.items.size })

        val videoCharts = sections[0].items.map { (it as FeedItem.OfPlaylist).playlist }
        assertEquals("VLPL4fGSI1pDJn4yCNzulPkUbxgr4pl0gmI-", videoCharts.first().browseId)
        assertEquals("Top 100 Live Performances - United States", videoCharts.first().title)
        assertEquals("VLPL4fGSI1pDJn69On1f-8NAvX_CYlx7QyZc", videoCharts.last().browseId)

        val genres = sections[1].items.map { (it as FeedItem.OfPlaylist).playlist }
        assertEquals("VLPL4fGSI1pDJn7Wkr6Ll6ds1AhA42rT8uaU", genres.first().browseId)
        assertEquals("VLPL4fGSI1pDJn77aK7sAW2AT0oOzo5inWY8", genres.last().browseId)

        val albums = sections[2].items.map { (it as FeedItem.OfAlbum).album }
        assertEquals("MPREb_iNWH4dO6PQO", albums.first().browseId)
        assertEquals("Hamilton (Original Broadway Cast Recording)", albums.first().title)
        assertEquals("MPREb_dqWTncCjkSp", albums.last().browseId)
        assertEquals("Thriller", albums.last().title)

        val artists = sections[3].items.map { (it as FeedItem.OfArtist).artist }
        assertEquals("UCU6cE7pdJPc6DU2jSrKEsdQ", artists.first().browseId)
        assertEquals("Drake", artists.first().name)
        assertEquals("UC8zFXOPlDTI5lhy3fAn6E5w", artists.last().browseId)
        assertEquals("Kodak Black", artists.last().name)
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
    fun playlistPageReturnsTheRecordedFixturesExactTrackOrder() {
        // Round 44: the cookie road's page was pinned only for
        // its first track and count — and it served as the
        // cross-derived oracle for the token road's full-order
        // pin, so a transformation inside the shared pageTracks
        // parser moved both roads together behind a green
        // suite. Pin the exact recorded order on this road too.
        val page = clientFor("playlist.json").playlist("VLPLd5Vfgy_sILZXNGHKlAHnwPOEhboVQNBG")
        assertEquals(
            listOf(
                "dQw4w9WgXcQ", "ghGoI7xVtSI", "cyMHZVT91Dw", "mgRhrr_SHNw",
                "rJyKoMkB_aM", "DD70oKDlemE", "YwlVgpXXJS0", "4U-gFecl0BU",
                "dMbZfdrzILA", "wuggc60TVzU", "2MZMAxmmQwA", "IO9XlQrEt2Y",
                "v8y1OnE_MYo", "XVs3dsI5Hxc", "_buPIVDW2So", "AbjyINUWIXU",
                "AxYRHsQDdIw", "_W5JIr-AR1o", "IdkCEioCp24", "a9WHZ5M8I8w",
                "dJRsWJqDjFE", "q3yUYEkNUQU", "OiZlXOAOLLw", "ds8sVhxdvfA",
                "VbUuB1aN2DA", "ZemRrRr6fd0", "OdF8_SpBkgM", "rTga41r3a4s",
                "hLZW4U86cL4", "qKlmSZQJM6A", "xtpwcUSQEVM", "o-YBDTqX_ZU",
                "v_nRCCD0j-k", "8AMeRsZCJYs", "TE-DW-5pvbA", "QFK09y01me0",
                "0k7BvzQRrOI", "hEHLZ65v_dE", "GtL1huin9EE", "zdzcOS1ldfs",
                "nsCIeklgp1M", "oZL6cZoPuPM", "XGxIE1hr0w4", "vNS8edY8JV0",
                "a-mMiDiPdyM", "tuWyKF6WKKU", "IVLxoJ8vTn4", "wO-ue7E1EY0",
                "Hc9seZ0W8Ug", "yahsYfmEb8U", "w2Sz_yClQyI", "OIuG1bBkfs0",
            ),
            page.tracks.map { it.videoId },
        )
    }

    @Test
    fun albumPageReturnsTheRecordedFixturesExactTrackOrder() {
        // Round 44: the album page rides the same shared
        // pageTracks parser as the playlist page, and its order
        // was pinned nowhere at all (first track and count
        // only) — the same shared-parser middle reversal that
        // defeated the playlist pin played every album's middle
        // backwards too. Pin the exact recorded order.
        val page = clientFor("album.json").album("MPREb_GbGA9XuA9g9")
        assertEquals(
            listOf(
                "wIA-4LeRgsc", "hwUixddbmrQ", "6wiUcsVuEh0", "hNrwlzBeFN8",
                "iw9wcJ7RZzY", "N7q4IalCAkQ", "qVZUcxFN2E8", "EgbfJqJI2x0",
                "KEjtbaWDVYw", "bW38jYPgbJ8", "pCv0oP9JLKw", "CMj3yeyOw2c",
                "ADEPikhQ5Lg", "af6JsTi7fUY", "O6e6cbmiKqs", "qatdvWJDSHk",
                "IO6qBvMDXiI", "cCK_I4AZ_F0", "5yM819NbMMY", "1xvg5fHLhWM",
                "Imjyp0PTHaQ", "zxMo0CZZzyg", "8fq9uibhVMc", "_rnxmZG0KHM",
                "2cS0qCwBuHk", "TIht2xh5aSo", "4eBIUoUVMR4", "-pP6Kl-nZoc",
                "9bzMFgsRXD4", "F7KdQ8CTe5E", "-MEfF9MpX_k", "bwPB0IPcK5Q",
                "mnyFUZvf3ec", "J8kV7rlrOp8", "Lu25eTpwWLY", "Qxo2ToDM-uE",
                "BcCCBNoDVwk",
            ),
            page.tracks.map { it.videoId },
        )
    }

    @Test
    fun moodsReturnTheRecordedFixturesExactCategoryOrder() {
        // Round 44: the moods pin below asserts the count, the
        // first category, and that three named categories exist
        // somewhere — reversing the middle categories inside
        // parseMoods (first and last preserved) ran green
        // across the full suite while the Moods grid rendered
        // its categories scrambled. Pin the exact recorded
        // order of all forty.
        val moods = clientFor("browse_moods.json").moods()
        assertEquals(
            listOf(
                "Chill", "Commute", "Diwali", "Energize", "Feel good",
                "Focus", "Gaming", "Halloween", "Party", "Romance",
                "Sad", "Sleep", "Workout", "African", "Arabic",
                "Blues", "Bollywood & Indian", "Brazilian",
                "Christian & gospel", "Classical", "Country & Americana",
                "Dance & electronic", "Decades", "Family", "Folk & acoustic",
                "Hip-hop", "Indie & alternative", "J-Pop", "Jazz",
                "K-Pop", "Latin", "Mandopop & cantopop", "Metal",
                "OPM", "Pop", "R&B & soul", "Reggae & caribbean",
                "Rock", "Songwriters & producers", "Soundtracks & musicals",
            ),
            moods.map { it.title },
        )
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
    fun moodPlaylistsReturnTheCategoryBrowseSectionsWithTheirItems() {
        // Round 41: the moods CATEGORIES were pinned (below), but
        // the category browse itself — moodPlaylists, whose result
        // MoodsScreen renders as-is — was called by no test
        // anywhere, so a post-parse transformation in the client
        // (every section's items emptied) shipped the category
        // pages as bare section titles across a fully green suite.
        // No recorded fixture exists for a category browse; the
        // home fixture is the established stand-in for a sectioned
        // browse response (the library tests read its shelves the
        // same way), so the category browse of it must return the
        // home browse's sections, titles in order, items intact.
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val category = MoodCategory(
            title = "Chill",
            params = "ggMPChillParams",
        )
        val sections = YoutubeiClient(transport).moodPlaylists(category)
        assertTrue(
            "the request must browse the category endpoint",
            transport.lastBody!!.contains(
                "\"browseId\":\"FEmusic_moods_and_genres_category\"",
            ),
        )
        assertTrue(
            "the request must carry the category's params",
            transport.lastBody!!.contains("ggMPChillParams"),
        )
        val home = clientFor("browse_home.json").home()
        assertEquals(home.map { it.title }, sections.map { it.title })
        assertEquals(home.map { it.items.size }, sections.map { it.items.size })
        assertEquals("Quick picks", sections[0].title)
        val first = (sections[0].items.first() as FeedItem.OfTrack).track
        assertEquals("xukbqwRuN5w", first.videoId)
    }

    @Test
    fun moodPlaylistsParseACategoryShapedBrowseResponse() {
        // Round 48: every mood pin above serves the category
        // browse the HOME fixture — titled shelves of tracks and
        // playlist cards in the home page's own arrangement. A
        // category page's own first response is a single-column
        // browse whose section list mixes a playlist carousel
        // with a track shelf; no recorded fixture exists for it,
        // so serve a synthetic one in that shape (the
        // ContinuationTest style) and pin titles, kinds, and
        // identities in order, plus the request's identity.
        val body = """{"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [
            {"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
                {"musicCarouselShelfRenderer": {
                    "header": {"musicCarouselShelfHeaderRenderer": {"title": {"runs": [{"text": "Chill playlists"}]}}},
                    "contents": [
                        {"musicTwoRowItemRenderer": {
                            "title": {"runs": [{"text": "Cat One"}]},
                            "navigationEndpoint": {"browseEndpoint": {"browseId": "VLcat1"}},
                            "thumbnailRenderer": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://img.example/cat1.jpg"}]}}}
                        }},
                        {"musicTwoRowItemRenderer": {
                            "title": {"runs": [{"text": "Cat Two"}]},
                            "navigationEndpoint": {"browseEndpoint": {"browseId": "VLcat2"}},
                            "thumbnailRenderer": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://img.example/cat2.jpg"}]}}}
                        }}
                    ]
                }},
                {"musicShelfRenderer": {
                    "title": {"runs": [{"text": "Chill tracks"}]},
                    "contents": [
                        {"musicResponsiveListItemRenderer": {
                            "flexColumns": [
                                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Cat Song 1"}]}}}
                            ],
                            "playlistItemData": {"videoId": "catVid1"}
                        }},
                        {"musicResponsiveListItemRenderer": {
                            "flexColumns": [
                                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Cat Song 2"}]}}}
                            ],
                            "playlistItemData": {"videoId": "catVid2"}
                        }}
                    ]
                }}
            ]}}}}
        ]}}}"""
        val transport = FixedTransport(body = body)
        val sections = YoutubeiClient(transport).moodPlaylists(
            MoodCategory(title = "Chill", params = "ggMPChillParams"),
        )
        assertTrue(
            "the request must browse the category endpoint",
            transport.lastBody!!.contains(
                "\"browseId\":\"FEmusic_moods_and_genres_category\"",
            ),
        )
        assertTrue(
            "the request must carry the category's params",
            transport.lastBody!!.contains("ggMPChillParams"),
        )
        assertEquals(
            listOf("Chill playlists", "Chill tracks"),
            sections.map { it.title },
        )
        assertEquals(
            listOf("VLcat1", "VLcat2"),
            sections[0].items.filterIsInstance<FeedItem.OfPlaylist>()
                .map { it.playlist.browseId },
        )
        assertEquals(
            listOf("Cat One", "Cat Two"),
            sections[0].items.filterIsInstance<FeedItem.OfPlaylist>()
                .map { it.playlist.title },
        )
        assertEquals(
            listOf("catVid1", "catVid2"),
            sections[1].items.filterIsInstance<FeedItem.OfTrack>()
                .map { it.track.videoId },
        )
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

    // ------------------------------------------------------------------
    // Round 46: the field-level census. Round 45 closed ORDER for
    // every surface; the fields inside the items were pinned only
    // for edge items (the first track of each page) or by presence.
    // Proven live this round at the parser/client joints, each
    // green across the full suite (276/593): (F1) every Track's
    // thumbnailUrl nulled at all four parser constructions —
    // TrackRow artwork and the player's artwork URI read it, and
    // no fixture test asserted any track's thumbnail at all;
    // (F2) PlaylistSummary.trackCount inflated +100 wherever
    // present — the Library renders it as "<n> tracks", and only
    // the search fixture's RDCLAK cards carry one; (F3) every
    // ArtistSummary.detail dropped — the artist card's subtitle
    // (subscriber / monthly-audience text); (F4) PlaylistPage's
    // continuation truncated to 40 chars — the one existing pin
    // asserted only a 32-char prefix; (F5) AlbumSummary.artist
    // blanked for every chart album after the first — only the
    // first chart album's artist/year were exact. The exact
    // recorded values are now pinned for representative item sets
    // (ground truth by parser-output probe of the shipped parser).
    // ------------------------------------------------------------------

    @Test
    fun trackFieldsMatchTheRecordedFixturesForRepresentativeItems() {
        fun assertTrack(
            track: dev.cyberdogs.offleash.core.model.Track,
            videoId: String,
            title: String,
            artists: List<String>,
            artistBrowseId: String?,
            album: String?,
            albumBrowseId: String?,
            durationSeconds: Int?,
            thumbnailUrl: String?,
        ) {
            assertEquals(videoId, track.videoId)
            assertEquals(title, track.title)
            assertEquals(artists, track.artists)
            assertEquals(artistBrowseId, track.artistBrowseId)
            assertEquals(album, track.album)
            assertEquals(albumBrowseId, track.albumBrowseId)
            assertEquals(durationSeconds, track.durationSeconds)
            assertEquals(thumbnailUrl, track.thumbnailUrl)
        }
        val thumbPrefix = "hqdefault.jpg?sqp=-oaymwEWCJADEOEBIAQqCggAEOADGC0guwJIWg&rs="
        val playlist = clientFor("playlist.json")
            .playlist("VLPLd5Vfgy_sILZXNGHKlAHnwPOEhboVQNBG")
        assertTrack(
            playlist.tracks[0],
            "dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"),
            "UCwZEU0wAwIyZb4x5G_KJp2w", null, null, 214,
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/$thumbPrefix" +
                "AMzJL3lrEQLVd7aUH6RTaLjc4JkVVNINHQ",
        )
        assertTrack(
            playlist.tracks[1],
            "ghGoI7xVtSI", "Rick Astley - Never Gonna Give You Up (Live 1987)",
            listOf("debandana"), "UC4oqQrj0OTEN3cd10tUQudQ", null, null, 191,
            "https://i.ytimg.com/vi/ghGoI7xVtSI/$thumbPrefix" +
                "AMzJL3nm8InRAJalvzR0Cqx4qwcRADc2MQ",
        )
        assertTrack(
            playlist.tracks[25],
            "ZemRrRr6fd0", "Rick Astley - Never Gonna Give You Up (Live On The One Show)",
            listOf("Amos Lee"), "UClEDoyrwahCf1GdwF4BbBKw", null, null, 152,
            "https://i.ytimg.com/vi/ZemRrRr6fd0/$thumbPrefix" +
                "AMzJL3nHpQkWpo2g1J34k1WpvxQSDzupEQ",
        )
        val feed = clientFor("browse_home.json").homeFeed()
        val quickPicks = feed.sections[0].items
            .filterIsInstance<FeedItem.OfTrack>().map { it.track }
        assertTrack(
            quickPicks[0],
            "xukbqwRuN5w", "Choosin' Texas", listOf("Ella Langley"),
            "UCecnyZYofHiBVDJpx1XNYOQ", "Dandelion", "MPREb_QC0L98k1tGe", null,
            "https://yt3.googleusercontent.com/pXFQCa7a4g8yaLQOuE2y0zWK4X0A9Kdjx1PTC_KICZc9OEgahsFl-F6VBihBNOLAiYX7XnuPqaC4Yio=w120-h120-l90-rj",
        )
        assertTrack(
            quickPicks[1],
            "Z85lxckrtzg", "Thriller", listOf("Michael Jackson"),
            "UCoIOOL7QKuBhQHVKL8y7BEQ", "Thriller", "MPREb_dqWTncCjkSp", null,
            "https://yt3.googleusercontent.com/URvHCfI2iyGAlAwqqBFeaFhU9DeKk_iuX40OIIIj8Zp0wIT3BVsJ2JRMwLLbUB9EZS7t7oDlMrI2S3OvGA=w120-h120-l90-rj",
        )
        val albumPage = clientFor("album.json").album("MPREb_GbGA9XuA9g9")
        assertTrack(
            albumPage.tracks[0],
            "wIA-4LeRgsc", "I'm The Problem", listOf("Morgan Wallen"),
            "UC5xaQ6_dP7EGDmGLzVGZ1Ow", null, null, 178, null,
        )
        assertTrack(
            albumPage.tracks[1],
            "hwUixddbmrQ", "I Got Better", listOf("Morgan Wallen"),
            "UC5xaQ6_dP7EGDmGLzVGZ1Ow", null, null, 205, null,
        )
        val related = clientFor("next.json").related("dQw4w9WgXcQ")
        assertTrack(
            related[0],
            "yBwD4iYcWC4", "She Wants to Dance", listOf("Rick Astley"),
            "UCwZEU0wAwIyZb4x5G_KJp2w", null, null, 260,
            "https://i.ytimg.com/vi/yBwD4iYcWC4/hq720.jpg?sqp=-oaymwEKCNUGEN8DIABIWg&rs=AMzJL3khxPtGmJB5eQy3zMPSrjNbEpKjtA",
        )
        assertTrack(
            related[1],
            "aGCdLKXNF3w", "Everybody Wants To Rule The World (Official Music Video)",
            listOf("Tears For Fears"), "UCcozhiTOkGB1HCOgdBlEFPA", null, null, 291,
            "https://i.ytimg.com/vi/aGCdLKXNF3w/hq720.jpg?sqp=-oaymwEKCNUGEN8DIABIWg&rs=AMzJL3n4cTXCTp6IJ4Dm-PCT64GQgRdKrg",
        )
    }

    @Test
    fun playlistSummariesCarryTheirRecordedAuthorsCountsAndThumbnails() {
        // F2: the +100 inflation of every present trackCount ran
        // green — no fixture test read the field at all. The
        // recorded counts live on the search fixture's three
        // playlist cards (the rest record null, also pinned).
        val search = clientFor("search.json").search("never gonna give you up")
        val searchPlaylists = search.sections.flatMap { it.items }
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist }
        assertEquals(
            listOf(
                "Teguh Raja Sakti" to null,
                "Julio Alfaro Music" to null,
                "ゆうきtvメイン" to null,
                "YouTube Music" to 111,
                "YouTube Music" to 59,
                "YouTube Music" to 35,
            ),
            searchPlaylists.map { it.author to it.trackCount },
        )
        assertEquals(
            "https://yt3.googleusercontent.com/VGxWK75H8M5k3r2I89b7Tg9Mi1JPNkCtc9IOkAfpznmJu4N7toBj_PB2Fubc_sFHa8CZ1MqSb6AqVTE=w544-h544-l90-rj",
            searchPlaylists[3].thumbnailUrl,
        )
        // Charts cards record their channel as author and no count.
        val charts = clientFor("browse_charts.json").charts()
        val chartPlaylists = charts.flatMap { it.items }
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist }
        assertTrue(chartPlaylists.isNotEmpty())
        assertTrue(chartPlaylists.all { it.author == "YouTube Charts" })
        assertTrue(chartPlaylists.all { it.trackCount == null })
        // The home Throwbacks cards record neither author nor count.
        val throwbacks = clientFor("browse_home.json").homeFeed()
            .sections[1].items.filterIsInstance<FeedItem.OfPlaylist>()
            .map { it.playlist }
        assertTrue(throwbacks.all { it.author == null && it.trackCount == null })
        assertEquals(searchPlaylists[3].thumbnailUrl, throwbacks[0].thumbnailUrl)
        // The artist page's playlists record the artist as author.
        val artistPlaylists = clientFor("artist.json")
            .artist("UC0OoAEcydpNnBTualVhYM9A")
            .sections[0].items.filterIsInstance<FeedItem.OfPlaylist>()
            .map { it.playlist }
        assertTrue(artistPlaylists.all { it.author == "LDR" && it.trackCount == null })
    }

    @Test
    fun artistSummariesCarryTheirRecordedSubscriberDetail() {
        // F3: dropping every ArtistSummary.detail ran green —
        // the field is the artist card's subtitle and was read by
        // no fixture test. Pin the recorded subscriber text of a
        // representative set (first, second, a middle, and last).
        val artists = clientFor("browse_charts.json").charts()[3].items
            .filterIsInstance<FeedItem.OfArtist>().map { it.artist }
        assertEquals("Drake" to "33.2M subscribers", artists[0].name to artists[0].detail)
        assertEquals(
            "YoungBoy Never Broke Again" to "15.8M subscribers",
            artists[1].name to artists[1].detail,
        )
        assertEquals("Anuel AA" to "30M subscribers", artists[20].name to artists[20].detail)
        assertEquals(
            "Kodak Black" to "9.96M subscribers",
            artists[39].name to artists[39].detail,
        )
        assertEquals(
            "https://yt3.googleusercontent.com/MxNjcRJ-uK4Xvx7u90IhEFLQM8x9LIGTA9VCKHq5U4Wn2jOgiWaMtg-qz329SIzqnCyhdCCB3MpdAGs=w120-h120-p-l90-rj",
            artists[0].thumbnailUrl,
        )
    }

    @Test
    fun albumSummariesCarryTheirRecordedArtistYearAndThumbnailBeyondTheFirst() {
        // F5: blanking the artist of every chart album after the
        // first ran green — only the first chart album's artist
        // and year were exact. Pin the recorded fields of the
        // second, third and last chart albums, and of the search
        // fixture's non-first albums.
        val albums = clientFor("browse_charts.json").charts()[2].items
            .filterIsInstance<FeedItem.OfAlbum>().map { it.album }
        assertEquals("Olivia Rodrigo" to "2026", albums[1].artist to albums[1].year)
        assertEquals("Drake" to "2026", albums[2].artist to albums[2].year)
        assertEquals("Michael Jackson" to "1982", albums[49].artist to albums[49].year)
        assertEquals(
            "https://yt3.googleusercontent.com/REA_Otoa5g-YBrDU0lRxSPu5YBP3tZHiIs2DWnO9mqXRZjTWDXyL8Zv83I56-Benty2h5Q0UzX9xBw7g=w544-h544-l90-rj",
            albums[1].thumbnailUrl,
        )
        val searchAlbums = clientFor("search.json").search("never gonna give you up")
            .sections.flatMap { it.items }
            .filterIsInstance<FeedItem.OfAlbum>().map { it.album }
        assertEquals("Reinaeiry" to "2022", searchAlbums[1].artist to searchAlbums[1].year)
        assertEquals("Xtc Planet" to "2013", searchAlbums[2].artist to searchAlbums[2].year)
    }

    @Test
    fun detailPagesCarryTheirRecordedHeaderThumbnailsAndContinuation() {
        // F4: the playlist page's continuation was pinned only by
        // a 32-character prefix — truncating the token to 40
        // characters ran green while Load more would have paged
        // with a corrupt token. Pin the full recorded token, and
        // the three detail pages' header thumbnails exactly (they
        // were presence-only, assertNotNull).
        val playlist = clientFor("playlist.json")
            .playlist("VLPLd5Vfgy_sILZXNGHKlAHnwPOEhboVQNBG")
        assertEquals(
            "4qmFsgI0EiRWTFBMZDVWZmd5X3NJTFpYTkdIS2xBSG53UE9FaGJvVlFOQkcaDGtnRURDTTBHOEFFQQ%3D%3D",
            playlist.continuation,
        )
        assertEquals(
            "https://yt3.googleusercontent.com/ZnXo6KsDJ7zYbGQ4BmnhJY27e2N0swg_9cjiK-F74PhBRAGb5qgzNSATbTzQUSINwGxnTwDaK5XG=s1200",
            playlist.thumbnailUrl,
        )
        val album = clientFor("album.json").album("MPREb_GbGA9XuA9g9")
        assertEquals(
            "https://yt3.googleusercontent.com/BVROJWUTP4kX9vzIKwN7NJgLRhxx30yjdg8XNI2UGMjcrMZbcV_UmGRsfJ5Zk0FOqUHPN2MMQpMCMS_0=w544-h544-l90-rj",
            album.album.thumbnailUrl,
        )
        val artist = clientFor("artist.json").artist("UC0OoAEcydpNnBTualVhYM9A")
        assertEquals(
            "https://yt3.googleusercontent.com/9ffSFs92NzBfX7GBrZ776xIN5z-BvC3Ba6d7S7K2PTV5BN4O5L-hVRqXtcud-MOf_d3XKJwcIw=w2880-h1200-p-l90-rj",
            artist.thumbnailUrl,
        )
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
    // ------------------------------------------------------------------
    // Round 47: the field census's honest remainder (round 46's
    // report left these classified, not shut). Proven live this
    // round against shadow core-mains, the combined doctors green
    // across the full core suite (281): the search surface's
    // middle playlist titles replaced; the charts surfaces'
    // middle playlist/album titles and middle artist names
    // replaced; the artist page's middle playlist titles and the
    // home shelf's middle titles replaced; every mood category's
    // params after the first collapsed onto the first's; the
    // playlist/album/search tracks' artistBrowseIds beyond the
    // representative tuples nulled or rewritten; the home chips'
    // and the search filters' first/last params swapped; and the
    // album description's artist name substituted while the one
    // pinned phrase ("fourth studio album") survived. The exact
    // recorded values are now pinned (ground truth by the same
    // parser-output probe as round 46).
    // ------------------------------------------------------------------

    @Test
    fun summaryTitlesAndNamesMatchTheRecordedFixturesBeyondTheEdges() {
        val search = clientFor("search.json").search("never gonna give you up")
        val searchPlaylists = search.sections.flatMap { it.items }
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist }
        assertEquals(
            listOf(
                "Never Gonna Give You Up", "never gonna give you up rock covers",
                "Never Gonna Give You Up", "The Hits: '80s",
                "The Golden Age of Boy Bands", "Y2K Pop Party",
            ),
            searchPlaylists.map { it.title },
        )
        val searchAlbums = search.sections.flatMap { it.items }
            .filterIsInstance<FeedItem.OfAlbum>().map { it.album }
        assertEquals(
            listOf("Never Gonna Give You Up", "Never Gonna Give You Up", "Never Gonna Give You Up"),
            searchAlbums.map { it.title },
        )
        val charts = clientFor("browse_charts.json").charts()
        val videoCharts = charts[0].items
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist }
        assertEquals(
            listOf(
                "Top 100 Live Performances - United States", "Trending 20 United States",
                "Daily Top Music Videos - United States", "Top 100 Music Videos United States",
            ),
            videoCharts.map { it.title },
        )
        val genres = charts[1].items
            .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist }
        assertEquals(
            listOf(
                "Top 50 Jazz Music Videos United States",
                "Top 50 Hard Rock & Metal Music Videos United States",
                "Top 50 Country & Americana Music Videos United States",
                "Top 50 Dance & Electronic Music Videos United States",
                "Top 50 Hip Hop Music Videos United States",
                "Top 50 Rock Music Videos United States",
                "Top 50 Latin Music Videos United States",
                "Top 50 Pop Music Videos United States",
            ),
            genres.map { it.title },
        )
        val chartAlbums = charts[2].items
            .filterIsInstance<FeedItem.OfAlbum>().map { it.album }
        assertEquals("you seem pretty sad for a girl so in love", chartAlbums[1].title)
        assertEquals("HABIBTI (FOMO)", chartAlbums[2].title)
        assertEquals("QRÖMELIFE (Extended Version)", chartAlbums[25].title)
        val chartArtists = charts[3].items
            .filterIsInstance<FeedItem.OfArtist>().map { it.artist }
        assertEquals(
            listOf("Drake", "YoungBoy Never Broke Again", "Fuerza Regida", "Bad Bunny", "Michael Jackson", "Morgan Wallen", "Rod Wave", "Future", "Taylor Swift", "Eminem", "KAROL G", "Lil Baby", "KATSEYE", "Lil Durk", "The Weeknd", "Chris Brown", "Peso Pluma", "Lil Wayne", "Kevin Gates", "Bruno Mars", "Anuel AA", "Ella Langley", "Rihanna", "Junior H", "Linkin Park", "Juice WRLD", "Grupo Firme", "LOS DOS DE TAMAULIPAS", "Tito Double P", "Kendrick Lamar", "Ariana Grande", "Olivia Rodrigo", "Shakira", "Luis Conriquez", "Romeo Santos", "Justin Bieber", "Los Temerarios", "\$uicideboy\$", "Kanye West", "Kodak Black"),
            chartArtists.map { it.name },
        )
        val artistPlaylists = clientFor("artist.json")
            .artist("UC0OoAEcydpNnBTualVhYM9A")
            .sections[0].items.filterIsInstance<FeedItem.OfPlaylist>()
            .map { it.playlist }
        assertEquals(
            listOf(
                "chill phonk", "cool music", "Boleros", "Techno 2026", "phonk",
                "New 2025 July", "September  2022", "tony Spanish",
                "2026 new playlist", "1990 to 2000 music",
            ),
            artistPlaylists.map { it.title },
        )
        // The home shelf's playlist titles on every road that
        // serves this fixture: home(), homeFeed(), and both
        // library collections (cookie and token roads).
        val throwbackTitles = listOf(
            "The Hits: '80s", "'80s Rock", "Feel-Good Classic Rock",
            "Classic Rock's Greatest Hits", "'00s Hip Hop Party",
            "'90s Alternative", "'90s Country", "'70s Rock",
            "Old-School Reggaeton", "Los Himnos",
        )
        assertEquals(
            throwbackTitles,
            clientFor("browse_home.json").home()[1].items
                .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist.title },
        )
        assertEquals(
            throwbackTitles,
            clientFor("browse_home.json").homeFeed().sections[1].items
                .filterIsInstance<FeedItem.OfPlaylist>().map { it.playlist.title },
        )
        val session = dev.cyberdogs.offleash.core.auth.AuthSession(
            cookies = mapOf("SAPISID" to "sap-value", "SID" to "sid-value"),
            authUser = 1,
            visitorData = "CgVisitorDataValue",
        )
        assertEquals(
            throwbackTitles,
            clientFor("browse_home.json").libraryPlaylists(session).map { it.title },
        )
        assertEquals(
            throwbackTitles,
            clientFor("browse_home.json").libraryPlaylistsOAuth("token-abc").map { it.title },
        )
    }

    @Test
    fun moodCategoriesCarryTheirRecordedParams() {
        // The params are the routing field: MoodsScreen's tap
        // encodes them into the mood route and the category page
        // browses with them, while the title is only the label.
        // Only the first category's params were even presence-
        // checked before this round.
        val moods = clientFor("browse_moods.json").moods()
        assertEquals(
            listOf("ggMPOg1uX1JOQWZFeDByc2Jm", "ggMPOg1uX044Z2o5WERLckpU", "ggMPOg1uX0lQVm1ablYxWml5", "ggMPOg1uX2lRZUZiMnNrQnJW", "ggMPOg1uXzZQbDB5eThLRTQ3", "ggMPOg1uX0NvNGNhWThMYWRh", "ggMPOg1uX3NmUVV4Vzl3WGQ0", "ggMPOg1uX0VzUzRpMkExMENQ", "ggMPOg1uX2w1aW1CRDFTSUNo", "ggMPOg1uX1JCQnB2QXVYVEIz", "ggMPOg1uX3NISTh4UmtWcFgz", "ggMPOg1uX1MxaFQ3Z0JMZkN4", "ggMPOg1uXzIxYkNac21YZ2Z0", "ggMPOg1uX0UzWGxlRE5jMDVk", "ggMPOg1uX3VOQWxsblVZTFNE", "ggMPOg1uX2NXUkgxdW0zUHJp", "ggMPOg1uX2hacTRJOU5KcndD", "ggMPOg1uX3Vmb2NXbUdLcHNU", "ggMPOg1uX3E1VnpSRHhLVElG", "ggMPOg1uX1N4VmduTmdUR3dm", "ggMPOg1uX1RXcFlyZEpRb1d3", "ggMPOg1uX1NPTld3SDN3WGs4", "ggMPOg1uX3NjZllsNGVEMkZo", "ggMPOg1uXzMyY3J2SGM0bVh5", "ggMPOg1uXzBTRFBmQ3N4b0R6", "ggMPOg1uX01sVVAwVmNXcEIx", "ggMPOg1uX21NWWpBbU01SDgy", "ggMPOg1uXzAwSjVITDBZckJR", "ggMPOg1uX3lPcDFRaE9wM1BS", "ggMPOg1uX0JrbjBDOFFPSzJW", "ggMPOg1uX29wWTRjMHV1dWN5", "ggMPOg1uX2hXVUQwc0JTNXlE", "ggMPOg1uXzdlSXhKZ0hMV1Z4", "ggMPOg1uX2JScEx5R3J4bmp5", "ggMPOg1uX1BmNzc2V2p0YXJ5", "ggMPOg1uX2JxQ2hxc2J5UFhR", "ggMPOg1uX1JUc2lFcDFuUUth", "ggMPOg1uXzJKTm5jUEZ5Uzlu", "ggMPOg1uX0cyenN2blVadXBC", "ggMPOg1uX2tWZXBsRm05cHNR"),
            moods.map { it.params },
        )
        assertTrue(moods.all { it.browseId == "FEmusic_moods_and_genres_category" })
    }

    @Test
    fun routingParamsMatchTheRecordedFixtures() {
        // Home chips: each chip re-browses home with its own
        // params; only distinctness and the Relax chip's request
        // were pinned, so a first/last swap routed Podcasts to
        // Focus's slice and vice versa behind a green suite.
        val chips = clientFor("browse_home.json").homeFeed().chips
        assertEquals(
            listOf("ggNCSgQIDBADSgQIBxABSgQICBABSgQICRABSgQIChABSgQIDRABSgQIDhABSgQIBRABSgQIAxABSgQIBBABSgQIBhAB", "ggNCSgQIDBABSgQIBxADSgQICBABSgQICRABSgQIChABSgQIDRABSgQIDhABSgQIBRABSgQIAxABSgQIBBABSgQIBhAB", "ggNCSgQIDBABSgQIBxABSgQICBADSgQICRABSgQIChABSgQIDRABSgQIDhABSgQIBRABSgQIAxABSgQIBBABSgQIBhAB", "ggNCSgQIDBABSgQIBxABSgQICBABSgQICRADSgQIChABSgQIDRABSgQIDhABSgQIBRABSgQIAxABSgQIBBABSgQIBhAB", "ggNCSgQIDBABSgQIBxABSgQICBABSgQICRABSgQIChADSgQIDRABSgQIDhABSgQIBRABSgQIAxABSgQIBBABSgQIBhAB", "ggNCSgQIDBABSgQIBxABSgQICBABSgQICRABSgQIChABSgQIDRADSgQIDhABSgQIBRABSgQIAxABSgQIBBABSgQIBhAB", "ggNCSgQIDBABSgQIBxABSgQICBABSgQICRABSgQIChABSgQIDRABSgQIDhADSgQIBRABSgQIAxABSgQIBBABSgQIBhAB", "ggNCSgQIDBABSgQIBxABSgQICBABSgQICRABSgQIChABSgQIDRABSgQIDhABSgQIBRADSgQIAxABSgQIBBABSgQIBhAB", "ggNCSgQIDBABSgQIBxABSgQICBABSgQICRABSgQIChABSgQIDRABSgQIDhABSgQIBRABSgQIAxADSgQIBBABSgQIBhAB", "ggNCSgQIDBABSgQIBxABSgQICBABSgQICRABSgQIChABSgQIDRABSgQIDhABSgQIBRABSgQIAxABSgQIBBADSgQIBhAB", "ggNCSgQIDBABSgQIBxABSgQICBABSgQICRABSgQIChABSgQIDRABSgQIDhABSgQIBRABSgQIAxABSgQIBBABSgQIBhAD"),
            chips.map { it.params },
        )
        // Search filters: the params select the filtered search;
        // only Songs/Albums/Artists were exact, so a Videos/
        // Podcasts swap routed those chips to each other's
        // searches behind a green suite.
        val filters = clientFor("search.json")
            .search("never gonna give you up").filters
        assertEquals(
            listOf(
                "All" to null,
                "Videos" to "EgWKAQIQAWoSEAQQAxAJEAUQChAQEBUQDhAR",
                "Songs" to "EgWKAQIIAWoSEAQQAxAJEAUQChAQEBUQDhAR",
                "Albums" to "EgWKAQIYAWoSEAQQAxAJEAUQChAQEBUQDhAR",
                "Artists" to "EgWKAQIgAWoSEAQQAxAJEAUQChAQEBUQDhAR",
                "Community playlists" to "EgeKAQQoAEABahIQBBADEAkQBRAKEBAQFRAOEBE%3D",
                "Episodes" to "EgWKAQJIAWoSEAQQAxAJEAUQChAQEBUQDhAR",
                "Profiles" to "EgWKAQJYAWoSEAQQAxAJEAUQChAQEBUQDhAR",
                "Featured playlists" to "EgeKAQQoADgBahIQBBADEAkQBRAKEBAQFRAOEBE%3D",
                "Podcasts" to "EgWKAQJQAWoSEAQQAxAJEAUQChAQEBUQDhAR",
            ),
            filters.map { it.label to it.params },
        )
    }

    @Test
    fun trackNavigationIdentitiesMatchTheRecordedFixturesBeyondTheRepresentatives() {
        // Track.artistBrowseId is the TrackMenu "Artist" route's
        // identity. Round 46 pinned full tuples for representative
        // items only ([0]/[1]/[25] of the playlist, [0]/[1] of the
        // album and home rails, the search top result); every
        // other track's identity was presence-only at best.
        val playlist = clientFor("playlist.json")
            .playlist("VLPLd5Vfgy_sILZXNGHKlAHnwPOEhboVQNBG")
        assertEquals(
            listOf("UCwZEU0wAwIyZb4x5G_KJp2w", "UC4oqQrj0OTEN3cd10tUQudQ", null, "UCv5anZ8hXfKoqwhz5jZOsrw", "UCn3RaByTsRYpDlEPXB96odw", "UCwZEU0wAwIyZb4x5G_KJp2w", null, "UCRLIaEsHRuncmQ6PsklV6hA", "UCxjzlr0hdKWfIwK2lY0SvEg", "UCIU3UrW1wQHKJdi-tZvxsfw", "UCk6TpMJVc5ep4IwqT24Fubw", "UCwZEU0wAwIyZb4x5G_KJp2w", "UCa82prud1bjpnaiHNL3OKmw", null, "UCUASQhjhunTVHrwtKgKVMLw", "UCCpd6qvE03uwMqoHwMfulSg", "UCkgzK9y_1xtHBjq4Bi2V3RA", "UC7W-oUd9RGefgouWU_A8Lhg", null, "UCTqDzPyS7dDBxjywNROdAUg", "UC0Kte42xsXIT80UzG1jotiw", "UChtmCUEEJu3exJqrv28-RzQ", "UCZtDUmC3W7j25XHZWFT_XgQ", "UC3bXPMuWq79iiQDl66J5pzg", "UCnULp16WtSX404Du_Nlmcxw", "UClEDoyrwahCf1GdwF4BbBKw", "UCZtDUmC3W7j25XHZWFT_XgQ", "UCwZEU0wAwIyZb4x5G_KJp2w", "UCoB3pvUbYxbSnXZ54IRz-6w", "UC5YNq79VzrCNw11XfJZktQw", "UCEAZf8VrQA9yBKZWz7vEfaw", null, "UCBopMndfRUTUP-g5Dob3DXQ", "UCwZEU0wAwIyZb4x5G_KJp2w", "UCaui7KwXIR2voapCLrlOGtg", "UCIq-5ALaaM-hiL_bFUc5GlA", "UCwZEU0wAwIyZb4x5G_KJp2w", "UCxjzlr0hdKWfIwK2lY0SvEg", "UCJjjxrjyjMMKFzq23xsaK-Q", null, "UCwZEU0wAwIyZb4x5G_KJp2w", null, "UCCj956IF62FbT7Gouszaj9w", "UCZtDUmC3W7j25XHZWFT_XgQ", "UCVqfM76GbP-DS9Pfuqo4Dmg", "UCVG6RhupwJnEWQnG33QqEwg", null, null, "UCXYxq7kMofJ6zH-9OBFgzUA", null, "UC52Ou5u4yl7PiQzDaYIrICg", null),
            playlist.tracks.map { it.artistBrowseId },
        )
        val album = clientFor("album.json").album("MPREb_GbGA9XuA9g9")
        assertEquals(37, album.tracks.size)
        assertTrue(album.tracks.all { it.artistBrowseId == "UC5xaQ6_dP7EGDmGLzVGZ1Ow" })
        assertEquals("Superman", album.tracks[2].title)
        assertEquals("20 Cigarettes", album.tracks[10].title)
        assertEquals("I'm A Little Crazy", album.tracks[36].title)
        val searchTracks = clientFor("search.json")
            .search("never gonna give you up")
            .sections.flatMap { it.items }
            .filterIsInstance<FeedItem.OfTrack>().map { it.track }
        assertEquals(
            listOf("dQw4w9WgXcQ" to "UCwZEU0wAwIyZb4x5G_KJp2w", "lYBUbBu4W08" to "UCwZEU0wAwIyZb4x5G_KJp2w", "0Om-vyXny3Y" to "UCVgZX_7OqcAKvxOG75wB9hw", "0qgh3SPyYm4" to "UCV35yz91pIrWhHYkfmf4ocg", "hZ7Om9gE-Oc" to "UCUxesMwPc38gIKN39L6nfig", "xTO73XbiARY" to "UCaOfRU7sXMarrgaC3zrklYQ", "jBkDww-83dw" to "UC2mos8O891ghGeQFB0p7GUw", "MRW7d7PIZ6U" to "UCjJn75rK6lUaYwal1Ald1gg", "HH7ohZOtsQM" to "UCiU3t9-6cPDDIufQ7rB_zBw", "S4Q3EqtixDU" to null, "GHoHxs9nyC4" to null, "zFkf7cG_ZHo" to null),
            searchTracks.map { it.videoId to it.artistBrowseId },
        )
        val related = clientFor("next.json").related("dQw4w9WgXcQ")
        assertEquals(
            listOf("UCwZEU0wAwIyZb4x5G_KJp2w", "UCcozhiTOkGB1HCOgdBlEFPA", "UCx00XxqRCfPS6zaTzRm3H2A", "UCoIOOL7QKuBhQHVKL8y7BEQ", "UCfS_1LLbHxkCHWVL8u2mXlg", "UCsUjEuAbP8wAEz8tiB3dvNw", "UCQiH3xAP0i48o74BK6EBkMw", "UC02eqrW1fCLDZwOKVOA62vQ", "UCoQve3vhvnrr_n8GboFptdQ", "UCdyqvgHHq9NEEfV20lO61jQ", "UC9I3hbllzsS4QZqNekPE76g", "UC52srG5obKLJ16vRsTqZyZQ", "UC4GVf3NpBzQiuNVf5EYsI5A", "UC0BdKEjWDC0hw4Bex9fTnMA", "UCo4CrqhIV_tSOGdDWfVyCsg", "UCbsRGw640UF_F7AIwHW-zmg", "UC7yYP0Cq2zcwURQrklwkiPw", "UCm9XijVCOsRrxdx0giD_1fQ", "UCwZEU0wAwIyZb4x5G_KJp2w", "UCNSBZ4vYV5go9h-iXcjOEBw", "UCuubAXN9AYgtcmSrD0frggQ", "UCQsJi7L4_zXzYRN2X0Fw3Zg", "UCwZEU0wAwIyZb4x5G_KJp2w", "UCF-DjS6Y9CKZThQf01SYe7w", "UCx5EhC862RMB7_dOLBUCjAg", "UCcoxwwIh_afLwJHVBEQw62Q", "UCwZEU0wAwIyZb4x5G_KJp2w", "UCL0dlEc0rXV1CIawUKeme4g", "UCPKnuPciHDB8qw-wyBVQo9A", "UCUacxoTL1MkUQp2QrWb0yaA", "UCwZEU0wAwIyZb4x5G_KJp2w", "UCoIOOL7QKuBhQHVKL8y7BEQ", "UChUr6-Fbc5f5FH9f0YNWLfQ", "UCn9i4cdLe14CbsOycTbKMoA", "UCVacQ2t5GUZ2t_J3Ia9BynA", "UCyD3XWRK9ko-izf2nBSFitw", "UClYV6hHlupm_S_ObS1W-DYw", "UCIaFw5VBEK8qaW6nRpx_qnw", "UCVm4YdI3hobkwsHTTOMVJKg", "UC-GWJZkyivsS-0yMjKMH0AA", "UCpVKXA1YEpLtD_keJogkCwg", "UC6VwktUsQQDCkHG1h6YftAQ", "UCewH1MBbYlEZMWx3ZUNywyg", "UCEPMVbUzImPl4p8k4LkGevA", "UCWEtnEiVwUy7mwFeshyAWLA", "UCdyqvgHHq9NEEfV20lO61jQ", "UCCL-yoaPLR-7bxZM1xlcQcQ", "UCyiXsKGKbYWQTkg919MyrNQ", "UCo6JijJGA3IvIiPsawDK3Ww"),
            related.map { it.artistBrowseId },
        )
        val quickPicks = clientFor("browse_home.json").homeFeed()
            .sections[0].items.filterIsInstance<FeedItem.OfTrack>().map { it.track }
        assertEquals(
            listOf("UCecnyZYofHiBVDJpx1XNYOQ", "UCoIOOL7QKuBhQHVKL8y7BEQ", "UCXi-L3_hX9d3TdX5_wO-EJw", "UCoOFEu6s0wk7CUljMh8wisw", "UC7n3gWRN0vQzgiOKc51aZ4w", "UCZkSg23fAsaHkqgbCEA-f3g", "UCX9kfYB9t0tnd6DYUC2iuKg", "UCiC_0Ed56gltQMFkWTmoLow", "UCjb-MC_d8-oYT-sZJ8ShaJw", "UCGKXb1syicud01CJOOFRykg", "UC6ASQWaW0NHTcdf3bJTgBlQ", "UCo6JijJGA3IvIiPsawDK3Ww", "UCRLE_fwmN2CQw1vuWPwo6eA", "UCE5XNpliPM-SmyFEp61tL_g", "UC97rm8qp1St0Pqneh_S6pRg", "UCmNtyqQl03eWyvikCMbO3fA", "UCB-GmTOjewVU2F_7S4ZFmEA", "UCrNhhCo8Mp8XHTPiYm6xdKw", "UCXg6YtKpgC59gRKUfxQw8Fw", "UC0tecxozlRzTMrqvShI8QWg"),
            quickPicks.map { it.artistBrowseId },
        )
        assertEquals(
            listOf("MPREb_QC0L98k1tGe", "MPREb_dqWTncCjkSp", "MPREb_68UVAxQ9YKP", "MPREb_diG2KaWVLf6", "MPREb_gtAqZBtFDvm", "MPREb_ey65TwkRLb6", "MPREb_1mXPdjGVwiP", "MPREb_2MBpvRAuKX4", "MPREb_PVOMyidLw45", "MPREb_vSrMeqOiRTc", "MPREb_QNncvPwM3Jd", "MPREb_2DfQTkeyE0f", "MPREb_QctNwXu9OrK", "MPREb_8qrRVNBHgV8", "MPREb_nrhOI5Iqcfr", "MPREb_xqqsLAkNmAY", "MPREb_DtJI85JEurP", "MPREb_WKA9KC3n2Sk", "MPREb_PAYaQWbzA2X", "MPREb_BtXfAQVI3yJ"),
            quickPicks.map { it.albumBrowseId },
        )
    }

    @Test
    fun albumPageDescriptionMatchesTheRecordedFixtureExactly() {
        // Presence-plus before this round: assertNotNull plus a
        // contains() on one phrase — substituting the artist's
        // name throughout kept the phrase and ran green.
        val page = clientFor("album.json").album("MPREb_GbGA9XuA9g9")
        assertEquals(
            """I'm the Problem is the fourth studio album by American country music singer Morgan Wallen. It was released through Big Loud, Republic, and Mercury Records on May 16, 2025. The album features guest appearances from Tate McRae, Eric Church, Hardy, Ernest, and Post Malone. Production was handled by Joey Moi, Charlie Handsome, and Jacob Durrett. Unlike Wallen's previous album that was recorded in the Abbey Road Studios, I'm the Problem was recorded and written in his farm outside of Nashville, Tennessee.
I'm the Problem consists of 37 tracks and has a running time that is nearly 117 minutes. This became Wallen's longest running album and his album to feature the most tracks, surpassing One Thing at a Time. Additionally, I'm the Problem spawned nine singles and promotional singles: "Lies Lies Lies", "Love Somebody", "Smile", the title track, "I'm a Little Crazy", "Just in Case", "I Ain't Comin' Back", "Superman", and "What I Want". Six of the tracks listed above debuted or reached the top-ten of the US Billboard Hot 100 before the album's release, eclipsing the conjoint record held by Taylor Swift's Red and Post Malone's Hollywood's Bleeding respectively.

From Wikipedia (https://en.wikipedia.org/wiki/I'm_the...) under Creative Commons Attribution CC-BY-SA 3.0 (https://creativecommons.org/licenses/...)""",
            page.description,
        )
    }
}
