package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 44 sweep contracts (confirmation round — breaking round
 * 43's fixes at their shared joints):
 *
 * Charter 3 — SectionRow's queueing bindings. The section rows
 * are the queueing surface of every browse host (home, artist,
 * charts, moods, search): SectionRow flattens a section's tracks,
 * renders one TrackRow per track, and a tap reports the section's
 * list plus the tapped index to FeedActions.onTrackClick, which
 * rememberFeedActions binds to QueueController.playTracks. The
 * chain was traced honest in round 41 but pinned NOWHERE — no
 * test in the tree mentioned SectionRow or onTrackClick, and the
 * R21 pin's playTracks(tracks, index) counts read DetailScreens
 * only. Proven live in round 44, each green across the full app
 * suite (590): (S1) the row click doctored to always report
 * index 0 (every row played the section's first track);
 * (S2) the section's track list reversed at the definition
 * (every host's rows and queues upside down together — the
 * definition is shared, so home sections and artist sections
 * break together); (S3) rememberFeedActions' binding doctored
 * to playTracks(tracks, 0) (the same first-track queueing from
 * the shared factory every host builds its actions through).
 *
 * Round 45 addition: the definition/factory pins bind what
 * SectionRow and rememberFeedActions ARE, but a host could
 * still intercept at its call site — proven live this round
 * (an index-shifting FeedActions wrapper built in HomeScreen
 * ran green, 592). The third test therefore binds every
 * host's call-site arguments: the plain hosts pass the
 * factory's own actions object, by name, unwrapped, and
 * Search's single sanctioned wrapper keeps its exact mapping.
 *
 * Common.kt is Compose-bound, so the shape is the pin (house
 * style; CI is the compile gate). The claims are slice-scoped
 * counts on the raw text and on strictCode (the whole-text
 * lexical strip, as in the R18/R21 pins), so a comment or
 * literal copy cannot supply a needle and a transformed form
 * cannot hide in one either.
 */
class R44SweepContractTest {

    @Test
    fun sectionRowQueuesTheSectionsOwnTracksAtTheTappedIndex() {
        val common = mainSource("java/dev/cyberdogs/offleash/ui/components/Common.kt")
        for ((viewName, view) in listOf("raw" to common, "strict" to strictCode(common))) {
            val row = view
                .substringAfter("fun SectionRow(")
                .substringBefore("fun ErrorPanel")
            assertEquals(
                "SectionRow must derive its queue from the section's own " +
                    "track items exactly once ($viewName view) — a transformed " +
                    "list (reversed, dropped, sorted) queues a different set " +
                    "than the rows display, on every host at once",
                1,
                occurrences(
                    row,
                    // Newline-terminated: the bare statement is a
                    // PREFIX of a transformed line (`...map { it.track
                    // }.reversed()`), which a prefix count cannot see
                    // (proven live against this pin's first draft).
                    "val tracks = section.items.filterIsInstance<FeedItem.OfTrack>().map { it.track }\n",
                ),
            )
            assertEquals(
                "SectionRow must iterate that list in order exactly once ($viewName view)",
                1,
                occurrences(row, "tracks.forEachIndexed { index, track ->"),
            )
            assertEquals(
                "a row tap must report the row's own index against the " +
                    "section's list exactly once ($viewName view) — reporting " +
                    "0 plays the first track from every row",
                1,
                occurrences(row, "onClick = { actions.onTrackClick(tracks, index) }"),
            )
            for (crossed in listOf(
                "onTrackClick(tracks, 0)",
                "onTrackClick(tracks.reversed()",
                // Any transformation of the section's list inside
                // this definition, under any spelling — the shipped
                // SectionRow holds none of these.
                ".reversed()",
                ".drop(",
                ".sortedBy",
                ".shuffled()",
                "tracks.filter",
                "tracks.distinctBy",
            )) {
                assertEquals(
                    "the crossed/transformed form `$crossed` must not occur " +
                        "in SectionRow ($viewName view)",
                    0,
                    occurrences(row, crossed),
                )
            }
            val factory = view
                .substringAfter("fun rememberFeedActions(")
                .substringBefore("fun LoadingPanel")
            assertEquals(
                "rememberFeedActions must bind track taps to " +
                    "playTracks(tracks, index) exactly once ($viewName view) — " +
                    "every host builds its actions through this factory",
                1,
                occurrences(
                    factory,
                    "onTrackClick = { tracks, index -> queueController.playTracks(tracks, index) }",
                ),
            )
            assertEquals(
                "the factory must never queue from index 0 ($viewName view)",
                0,
                occurrences(factory, "playTracks(tracks, 0)"),
            )
        }
    }

    @Test
    fun everyBrowseHostRendersThroughTheOneSectionRowDefinition() {
        val common = mainSource("java/dev/cyberdogs/offleash/ui/components/Common.kt")
        assertEquals(
            "SectionRow must be defined exactly once — a second, " +
                "host-local row definition would route its host around " +
                "the pinned queueing above",
            1,
            occurrences(common, "fun SectionRow("),
        )
        // The hosts: home and artist are the pair the charter
        // names; charts, moods and search share the definition
        // too, so the census covers every call site in the tree.
        val hosts = listOf(
            "java/dev/cyberdogs/offleash/ui/home/HomeScreen.kt",
            "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
            "java/dev/cyberdogs/offleash/ui/home/ChartsScreen.kt",
            "java/dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
            "java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
        )
        for (host in hosts) {
            assertEquals(
                "$host must render its sections through the shared " +
                    "SectionRow exactly once",
                1,
                occurrences(mainSource(host), "SectionRow(section = section"),
            )
        }
    }

    @Test
    fun everyHostPassesItsFactoryActionsUnwrappedToSectionRow() {
        // Round 45: the pins above bind the shared DEFINITION
        // and the shared FACTORY, and the census counts one
        // `SectionRow(section = section` call per host — but no
        // assertion read any host's CALL-SITE arguments, so
        // wrapping Home's actions in a host-built FeedActions
        // whose onTrackClick shifted the index before delegating
        // (definition and factory verbatim, census counts
        // unchanged) ran green across the full app suite (592)
        // while every home row queued the NEXT track. Bind the
        // arguments themselves: the four plain hosts must pass
        // the very actions object their rememberFeedActions call
        // produced, must construct no FeedActions of their own,
        // and Search's one sanctioned wrapper (the flattened
        // actions) must keep its shipped mapping.
        val plainHosts = listOf(
            "java/dev/cyberdogs/offleash/ui/home/HomeScreen.kt",
            "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
            "java/dev/cyberdogs/offleash/ui/home/ChartsScreen.kt",
            "java/dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
        )
        for (host in plainHosts) {
            val text = mainSource(host)
            assertEquals(
                "$host must build its actions through the shared " +
                    "factory exactly once",
                1,
                occurrences(text, "val actions = rememberFeedActions("),
            )
            assertEquals(
                "$host must pass those very actions to SectionRow " +
                    "unwrapped — an intercepting wrapper at the call " +
                    "site rewrites this call",
                1,
                occurrences(
                    text,
                    "SectionRow(section = section, actions = actions)",
                ),
            )
            assertEquals(
                "$host must construct no FeedActions of its own — " +
                    "a host-built actions object is the interception " +
                    "vehicle the definition/factory pins cannot see " +
                    "(constructions = FeedActions( calls that are not " +
                    "the rememberFeedActions factory call itself)",
                0,
                occurrences(text, "FeedActions(") -
                    occurrences(text, "rememberFeedActions("),
            )
        }
        val search = mainSource(
            "java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
        )
        assertEquals(
            "Search must build its base actions through the shared factory",
            1,
            occurrences(search, "val baseActions = rememberFeedActions("),
        )
        assertEquals(
            "Search must pass its flattened actions to SectionRow unwrapped",
            1,
            occurrences(
                search,
                "SectionRow(section = section, actions = flattenedActions)",
            ),
        )
        assertEquals(
            "Search holds the tree's one sanctioned host-side " +
                "FeedActions construction (the flattened mapping; " +
                "constructions counted as for the plain hosts)",
            1,
            occurrences(search, "FeedActions(") -
                occurrences(search, "rememberFeedActions("),
        )
        val flat = search
            .substringAfter("onTrackClick = { sectionTracks, sectionIndex ->")
            .substringBefore("onAlbumClick = baseActions.onAlbumClick")
        assertEquals(
            "the flattened mapping must resolve the tapped track " +
                "into the flat list exactly as shipped",
            1,
            occurrences(flat, "val tapped = sectionTracks.getOrNull(sectionIndex)"),
        )
        assertEquals(
            "the flattened mapping must locate the tap by video id",
            1,
            occurrences(flat, "val startIndex = flatTracks.indexOfFirst {"),
        )
        assertEquals(
            "the flattened mapping must play the flat list at the " +
                "resolved index — a shifted index queues the wrong track",
            1,
            occurrences(
                flat,
                "flatTracks," + "\n" +
                    "                                        startIndex,",
            ),
        )
        assertEquals(
            "the flattened mapping's fallback must play the section's " +
                "own list at the section's own (clamped) index",
            1,
            occurrences(
                flat,
                "sectionIndex.coerceIn(sectionTracks.indices)",
            ),
        )
        for (shifted in listOf("startIndex + 1", "startIndex - 1", "startIndex.inc()", "startIndex.dec()")) {
            assertEquals(
                "the flattened mapping must never shift its resolved " +
                    "index (`$shifted`)",
                0,
                occurrences(search, shifted),
            )
        }
    }

    @Test
    fun everyHostFactoryAndRowBindsItsNavigationLambdasUnswapped() {
        // Round 46: the pins above and the round-45 host pin bind
        // the TRACK-click arguments at every joint, but no pin
        // read the NAVIGATION lambdas anywhere: not the hosts'
        // rememberFeedActions arguments, not the factory's own
        // delegations inside Common.kt, not SectionRow's card
        // click targets, and not Search's flattened wrapper's
        // delegations. Proven live this round: swapping Charts'
        // onAlbumClick/onArtistClick arguments at its factory
        // call (SectionRow arguments verbatim, factory definition
        // verbatim) ran green across the full app suite (593)
        // while every Charts artist card opened the album route
        // and vice versa. Bind every joint: each host passes its
        // own lambdas by name, the factory delegates them by
        // name, SectionRow reports each card kind to its own
        // lambda, and Search's wrapper delegates the base
        // actions' lambdas by name — with the swapped forms
        // banned at the hosts and in the factory.
        val hosts = listOf(
            "java/dev/cyberdogs/offleash/ui/home/HomeScreen.kt",
            "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
            "java/dev/cyberdogs/offleash/ui/home/ChartsScreen.kt",
            "java/dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
            "java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
        )
        for (host in hosts) {
            val text = mainSource(host)
            for (delegation in listOf(
                "onAlbumClick = onAlbumClick",
                "onArtistClick = onArtistClick",
                "onPlaylistClick = onPlaylistClick",
            )) {
                assertEquals(
                    "$host must pass `$delegation` to the factory " +
                        "exactly once — a swapped or rerouted lambda " +
                        "sends a card kind to the wrong route",
                    1,
                    occurrences(text, delegation),
                )
            }
            for (swapped in listOf(
                "onAlbumClick = onArtistClick",
                "onArtistClick = onAlbumClick",
            )) {
                assertEquals(
                    "$host must never cross its navigation lambdas (`$swapped`)",
                    0,
                    occurrences(text, swapped),
                )
            }
        }
        val common = mainSource("java/dev/cyberdogs/offleash/ui/components/Common.kt")
        val factory = common
            .substringAfter("fun rememberFeedActions(")
            .substringBefore("fun LoadingPanel")
        for (delegation in listOf(
            "onAlbumClick = onAlbumClick",
            "onArtistClick = onArtistClick",
            "onPlaylistClick = onPlaylistClick",
        )) {
            assertEquals(
                "the factory must delegate `$delegation` exactly once",
                1,
                occurrences(factory, delegation),
            )
        }
        val row = common
            .substringAfter("fun SectionRow(")
            .substringBefore("fun ErrorPanel")
        for (target in listOf(
            "onClick = { actions.onAlbumClick(item.album.browseId) }",
            "onClick = { actions.onArtistClick(item.artist.browseId) }",
            "onClick = { actions.onPlaylistClick(item.playlist.browseId, null) }",
        )) {
            assertEquals(
                "SectionRow must report each card kind to its own " +
                    "navigation lambda exactly once (`$target`)",
                1,
                occurrences(row, target),
            )
        }
        val search = mainSource("java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt")
        for (delegation in listOf(
            "onAlbumClick = baseActions.onAlbumClick",
            "onArtistClick = baseActions.onArtistClick",
            "onPlaylistClick = baseActions.onPlaylistClick",
        )) {
            assertEquals(
                "Search's flattened wrapper must delegate `$delegation` exactly once",
                1,
                occurrences(search, delegation),
            )
        }
    }

    @Test
    fun searchFlattenedWrapperBindsItsFlatListConstructionAndRemainingDelegations() {
        // Round 46: the round-45 pin bound Search's wrapper
        // "verbatim" — but its slice starts at the wrapper's
        // onTrackClick and stops before onAlbumClick, so the two
        // statements that decide WHAT the wrapper plays were
        // never read: the flatTracks construction above the
        // wrapper, and the wrapper's remaining delegations below
        // the slice. Proven live this round, each green across
        // the full app suite (593): (A1) flatTracks built from
        // `sections.dropLast(1)` — taps in the last section fell
        // back to section-local queueing and the flat queue lost
        // that section's tracks; (A2) flatTracks suffixed with
        // `.distinctBy { it.videoId }` — a track listed in two
        // sections queued only at its first position. Bind the
        // construction verbatim, ban transformations inside it,
        // and bind the delegations the round-45 slice never
        // reached.
        val search = mainSource("java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt")
        assertEquals(
            "the flattened queue list must be built from every " +
                "result section's items, in order, exactly as shipped",
            1,
            occurrences(
                search,
                "val flatTracks = current.results.sections\n" +
                    "                        .flatMap { it.items }\n" +
                    "                        .filterIsInstance<FeedItem.OfTrack>()\n" +
                    "                        .map { it.track }\n" +
                    "                    val flattenedActions = remember(baseActions, flatTracks) {",
            ),
        )
        val flat = search
            .substringAfter("val flatTracks = current.results.sections")
            .substringBefore("val flattenedActions")
        for (transformed in listOf(
            ".dropLast(", ".distinctBy", ".drop(", ".sortedBy",
            ".reversed()", ".shuffled()", ".filter {",
        )) {
            assertEquals(
                "the flat list construction must hold no " +
                    "transformation (`$transformed`) — a transformed " +
                    "flat list queues a different set than the " +
                    "sections display",
                0,
                occurrences(flat, transformed),
            )
        }
        for (delegation in listOf(
            "onPlayNext = baseActions.onPlayNext",
            "onAddToQueue = baseActions.onAddToQueue",
            "playlists = baseActions.playlists",
            "onAddToPlaylist = baseActions.onAddToPlaylist",
            "trackActions = baseActions.trackActions",
        )) {
            assertEquals(
                "the flattened wrapper must delegate `$delegation` " +
                    "exactly once — the round-45 slice ends before " +
                    "these, so an unbound delegation here rewires " +
                    "the search rows' menus and queue actions",
                1,
                occurrences(search, delegation),
            )
        }
    }

    // -- lexical helpers (the R18/R21 house lexer, verbatim shape) --

    private data class CommentFrame(
        val mode: String,
        var depth: Int = 0,
        val template: Boolean = false,
    )

    /**
     * A whole-text lexical strip — removes line comments, (nested)
     * block comments, regular and raw string contents, and char
     * literals, while keeping code (including string-template
     * expressions) and newlines. A literal or comment can supply
     * no needle in this view.
     */
    private fun strictCode(text: String): String {
        val out = StringBuilder()
        val stack = mutableListOf(CommentFrame("code"))
        var i = 0
        fun top() = stack[stack.size - 1]
        while (i < text.length) {
            val c = text[i]
            when (top().mode) {
                "string" -> {
                    when {
                        c == '\\' -> i += 2
                        c == '"' -> {
                            stack.removeAt(stack.size - 1)
                            out.append('"')
                            i++
                        }
                        c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                            stack.add(CommentFrame("code", template = true))
                            i += 2
                        }
                        else -> i++
                    }
                }
                "raw" -> {
                    when {
                        c == '"' && text.startsWith("\"\"\"", i) -> {
                            stack.removeAt(stack.size - 1)
                            out.append("\"\"")
                            i += 3
                        }
                        c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                            stack.add(CommentFrame("code", template = true))
                            i += 2
                        }
                        else -> {
                            if (c == '\n') out.append('\n')
                            i++
                        }
                    }
                }
                "char" -> {
                    when {
                        c == '\\' -> i += 2
                        c == '\'' -> {
                            stack.removeAt(stack.size - 1)
                            out.append('\'')
                            i++
                        }
                        else -> i++
                    }
                }
                else -> { // code
                    when {
                        c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                            while (i < text.length && text[i] != '\n') i++
                        }
                        c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                            var depth = 1
                            i += 2
                            while (i < text.length && depth > 0) {
                                if (text[i] == '/' && i + 1 < text.length && text[i + 1] == '*') {
                                    depth++
                                    i += 2
                                } else if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '/') {
                                    depth--
                                    i += 2
                                } else {
                                    if (text[i] == '\n') out.append('\n')
                                    i++
                                }
                            }
                            out.append(' ')
                        }
                        c == '"' && text.startsWith("\"\"\"", i) -> {
                            stack.add(CommentFrame("raw"))
                            out.append("\"\"")
                            i += 3
                        }
                        c == '"' -> {
                            stack.add(CommentFrame("string"))
                            out.append(c)
                            i++
                        }
                        c == '\'' -> {
                            stack.add(CommentFrame("char"))
                            out.append(c)
                            i++
                        }
                        c == '{' && top().template -> {
                            top().depth++
                            out.append(c)
                            i++
                        }
                        c == '}' && top().template -> {
                            if (top().depth == 0) {
                                stack.removeAt(stack.size - 1)
                            } else {
                                top().depth--
                                out.append(c)
                            }
                            i++
                        }
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                }
            }
        }
        return out.toString()
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** Reads a file under app/src/main, located like the other contract tests. */
    private fun mainSource(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/$relativePath")
            candidates += File(dir, "app/src/main/$relativePath")
            dir = dir.parentFile
            hops++
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$relativePath not found from " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
        return found.readText()
    }
}
