package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 51 sweep contracts (confirmation round — breaking round
 * 50's spelling-complete census):
 *
 * R50 made the write census name-blind, but it keys on the
 * RECEIVER'S SPELLING and enumerates the write API by sampling
 * it. Proven live this round, each green across the full app
 * suite (609):
 *
 *  (F1) `MutableStateFlow` IS a `MutableSharedFlow`, so
 *  `_state.tryEmit(narrowed)` republishes the landed search
 *  narrowed to its first section after every load-more merge —
 *  a write carrying no `.value =`, no `.update`, and none of
 *  the three update-family names R50's ban enumerates. The ban
 *  sampled the API instead of enumerating it; the emit family
 *  (`tryEmit`, `.emit(`) had zero shipped uses and is now
 *  banned outright.
 *
 *  (F2) The per-property census attributes a write by the
 *  receiver identifier its regex captures (`_[A-Za-z0-9]+`), and
 *  no per-file TOTAL exists: `val flow = _state` followed by
 *  `flow.value = narrowed` attributes the write to no property
 *  at all. The same holds for a `by`-delegate whose use-site
 *  write is a bare `publishMirrored = …` (the delegate typed on
 *  `MutableSharedFlow`, its `property` parameter typed as the
 *  `Any` supertype so the word `reflect` never appears). The
 *  fix counts EVERY `.value =` in the file (any receiver — the
 *  shipped total is exactly R50's 108, so the total census is
 *  the per-property census with the attribution hole closed)
 *  and bans delegate definitions module-wide (zero shipped).
 *
 *  (F3) The parallel-state chain of R50's F2 reproduces under
 *  the SharedFlow spelling: a `MutableSharedFlow` declaration
 *  matches neither R50's declaration regex (`MutableStateFlow`
 *  only) nor its write census (its writes are `tryEmit`), and a
 *  screen can render it through a `LaunchedEffect` collect into
 *  a Compose state — an expression the collected-flow census,
 *  which sees only `viewModel.<flow>.collectAsState()`, never
 *  counts (the shipped `collectAsState()` kept as a decoy holds
 *  the multiset). `MutableSharedFlow`, non-StateFlow collects
 *  off a ViewModel, and `replayCache` reads are all zero in the
 *  shipped tree and are now censused at zero.
 *
 *  (F4) R50 closed the cast spellings, but the parser's lists
 *  are mutable through the `List` interface itself: a
 *  PlaylistViewModel helper calling
 *  `page.tracks.subList(1, page.tracks.size).clear()` narrows
 *  every published playlist page to its first track IN PLACE —
 *  no cast, no banned word, no assignment. The sibling view
 *  channels run green identically: `listIterator(1)` + `remove()`
 *  (and `iterator()` + `remove()`), and the static
 *  `java.util.Collections` mutators (`shuffle`/`rotate`/`sort`/…),
 *  which take the list AS its `List` type. The fix censuses
 *  `.subList(` per file at its shipped counts in BOTH modules
 *  (AppPreferences' two local uses stand; every other file is
 *  zero) and bans the iterator and Collections channels at
 *  their shipped zero.
 *
 *  (F5) R50's navigation census confines CONSTRUCTION (proven
 *  again this round: a direct positional `TrackActions(…)`
 *  construction with crossed lambdas fails exactly R50's
 *  inventory test), but field INVOCATION was pinned only where
 *  R49 looked: its crossed bans name the literal receiver
 *  `actions` (covering receivers ENDING in `actions` only by
 *  substring accident) and its correct-pairing counts are
 *  scoped to TrackMenu.kt. An AlbumScreen effect invoking
 *  `menu.onAlbumClick(artistId)` — the actions object aliased
 *  to a local named `menu` — fires the album lambda with the
 *  artist browse id and no pin moves. The fix censuses
 *  `.onAlbumClick(`/`.onArtistClick(` per file over ANY
 *  receiver and bans the crossed argument pairings
 *  receiver-blind (dot-prefixed).
 *
 * The ViewModel/UI files are Compose-bound, so the shape is
 * the pin (house style; CI is the compile gate).
 */
class R51SweepContractTest {

    @Test
    fun stateFlowWritesCarryNoUncensusedSpellingModuleWide() {
        val files = moduleSources()
        // Every `.value =` in the file, ANY receiver — the
        // shipped totals equal R50's per-property census summed
        // (109 since round 52 counted the compound write; see
        // the regex note below), so this total is that census
        // with the alias hole closed: a write behind
        // `val flow = _state` moves the file's total even
        // though it names no property.
        val expectedTotals: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/OffleashApp.kt" to 3,
            "dev/cyberdogs/offleash/data/AppPreferences.kt" to 8,
            "dev/cyberdogs/offleash/data/DownloadStore.kt" to 1,
            "dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to 19,
            "dev/cyberdogs/offleash/data/LikeController.kt" to 6,
            "dev/cyberdogs/offleash/data/ParityPreferences.kt" to 6,
            "dev/cyberdogs/offleash/data/SessionManager.kt" to 6,
            "dev/cyberdogs/offleash/data/SignInDiagnostics.kt" to 1,
            "dev/cyberdogs/offleash/data/Stores.kt" to 6,
            "dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt" to 1,
            "dev/cyberdogs/offleash/playback/QueueController.kt" to 21,
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 13,
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to 2,
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 3,
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to 4,
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 10,
        )
        // Round 52 (carried r51b F1): compound assignment
        // (`+=` / `-=`) counts toward the same per-file total —
        // OffleashApp's shipped `_showPlayerRequests.value += 1`
        // is a StateFlow write no plain-assignment census can
        // see, so the totals now sum to the true 109 (108 plain
        // + 1 compound), and a compound write through an aliased
        // receiver moves the total exactly as a plain one does.
        val anyReceiverWrite = Regex("""\.value\s*(?:=(?!=)|\+=|-=)""")
        for ((path, text) in files) {
            assertEquals(
                "$path must hold exactly its shipped TOTAL of " +
                    "`.value =` writes over every receiver " +
                    "spelling — round 50 counted writes by the " +
                    "property name its regex captured, so a write " +
                    "through a local alias (`val flow = _state`) " +
                    "was attributed to no property and landed " +
                    "silently; the total cannot be aliased away",
                expectedTotals[path] ?: 0,
                anyReceiverWrite.findAll(text).count(),
            )
            assertEquals(
                "$path must hold no emit-family StateFlow write " +
                    "(`tryEmit` / `.emit(`) — a MutableStateFlow " +
                    "is a MutableSharedFlow, so tryEmit " +
                    "republishes with no `.value =` text and no " +
                    "update-family name; the family had zero " +
                    "shipped uses and is banned, not sampled",
                0,
                occurrences(text, "tryEmit") +
                    occurrences(text, ".emit("),
            )
            for (delegate in listOf(
                "operator fun setValue",
                "operator fun getValue",
                "operator fun provideDelegate",
                "ReadWriteProperty",
                "PropertyDelegateProvider",
                "Delegates.",
            )) {
                assertEquals(
                    "$path must define no property delegate " +
                        "(`$delegate`) — a delegated property's " +
                        "use-site write is a bare assignment " +
                        "carrying no flow text at all, and the " +
                        "shipped tree defines none",
                    0,
                    occurrences(text, delegate),
                )
            }
        }
    }

    @Test
    fun noSharedFlowParallelStateOrNonStateCollectionExistsModuleWide() {
        val files = moduleSources()
        val viewModelCollect = Regex("""viewModel\.\w+\.collect(?!\w)""")
        val viewModelLifecycleCollect =
            Regex("""viewModel\.\w+\.collectAsStateWithLifecycle""")
        for ((path, text) in files) {
            assertEquals(
                "$path must declare no MutableSharedFlow — round " +
                    "50's declaration census matches the " +
                    "MutableStateFlow spelling only, so a parallel " +
                    "state under the SharedFlow spelling (written " +
                    "by tryEmit, rendered through a LaunchedEffect " +
                    "collect) reproduced its F2 chain entire; " +
                    "the shipped tree declares none",
                0,
                occurrences(text, "MutableSharedFlow"),
            )
            assertEquals(
                "$path must collect no ViewModel flow through " +
                    "the raw collect operator — the collected-" +
                    "flow census sees collectAsState() only, and " +
                    "a LaunchedEffect collect of a parallel flow " +
                    "into a Compose state changes no multiset " +
                    "(the shipped collectAsState kept as a decoy " +
                    "holds it); shipped uses are zero",
                0,
                viewModelCollect.findAll(text).count() +
                    viewModelLifecycleCollect.findAll(text).count(),
            )
            assertEquals(
                "$path must read no replayCache — a SharedFlow's " +
                    "replay cache is its standing value under " +
                    "another name, and a delegate or reader built " +
                    "on it needs no collect and no `.value` read " +
                    "of any censused shape; shipped uses are zero",
                0,
                occurrences(text, "replayCache"),
            )
        }
    }

    @Test
    fun publishedListsHaveNoCastFreeMutationChannel() {
        val expectedSubList: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/data/AppPreferences.kt" to 2,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                assertEquals(
                    "$label:$path must hold exactly its shipped " +
                        "`.subList(` count — a subList is a VIEW " +
                        "backed by the parser's own ArrayList, so " +
                        "clearing it rewrites the published list " +
                        "in place with no cast, no banned word, " +
                        "and no assignment for any census to see " +
                        "(AppPreferences' two uses are on a local " +
                        "list and stand; every other file is zero)",
                    if (label == "app") {
                        expectedSubList[path] ?: 0
                    } else {
                        0
                    },
                    occurrences(text, ".subList("),
                )
                assertEquals(
                    "$label:$path must obtain no list iterator " +
                        "(`.listIterator(` / `.iterator()`) — an " +
                        "iterator's remove() mutates the backed " +
                        "ArrayList in place, the same cast-free " +
                        "road as the subList view; shipped uses " +
                        "are zero in both modules",
                    0,
                    occurrences(text, ".listIterator(") +
                        occurrences(text, ".iterator()"),
                )
                assertEquals(
                    "$label:$path must name no java.util.Collections " +
                        "mutator channel (`Collections.` / the " +
                        "qualified name) — Collections.shuffle / " +
                        "rotate / sort take the published list AS " +
                        "its List type and mutate it with no cast; " +
                        "shipped uses are zero in both modules",
                    0,
                    occurrences(text, "Collections.") +
                        occurrences(text, "java.util.Collections"),
                )
            }
        }
    }

    @Test
    fun trackActionFieldInvocationsAreCensusedReceiverBlind() {
        val files = moduleSources()
        val expectedInvocations: Map<String, Pair<Int, Int>> = mapOf(
            "dev/cyberdogs/offleash/ui/components/Common.kt" to (1 to 1),
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to (1 to 1),
        )
        for ((path, text) in files) {
            val expected = expectedInvocations[path] ?: (0 to 0)
            assertEquals(
                "$path must invoke `.onAlbumClick(` exactly its " +
                    "shipped number of times over ANY receiver — " +
                    "round 49 counted the correct pairing only in " +
                    "TrackMenu under the literal receiver " +
                    "`actions`, so an invocation through the " +
                    "actions object aliased to another local name " +
                    "was counted nowhere",
                expected.first,
                occurrences(text, ".onAlbumClick("),
            )
            assertEquals(
                "$path must invoke `.onArtistClick(` exactly its " +
                    "shipped number of times over ANY receiver",
                expected.second,
                occurrences(text, ".onArtistClick("),
            )
            for (crossed in listOf(
                ".onAlbumClick(artistId)",
                ".onArtistClick(albumId)",
            )) {
                assertEquals(
                    "$path must never cross its track navigation " +
                        "fields (`$crossed`) under any receiver " +
                        "name — round 49's crossed bans name the " +
                        "receiver `actions` and cover receivers " +
                        "ending in `actions` only by substring " +
                        "accident; the dot-prefixed pairing is " +
                        "crossed whatever the holder is called",
                    0,
                    occurrences(text, crossed),
                )
            }
        }
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** The app module walk, shared with R49/R50's census shape,
     *  with its completeness pinned on the anchor files. */
    private fun moduleSources(): Map<String, String> {
        val javaRoot = locateAppJavaRoot()
        val out = sortedMapOf<String, String>()
        javaRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val relative = file.relativeTo(javaRoot).invariantSeparatorsPath
                out[relative] = file.readText()
            }
        for (anchor in listOf(
            "dev/cyberdogs/offleash/ui/OffleashRoot.kt",
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt",
        )) {
            assertTrue(
                "the module walk must find its anchor file $anchor",
                out.containsKey(anchor),
            )
        }
        return out
    }

    private fun locateAppJavaRoot(): File {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            for (candidate in listOf(
                File(dir, "src/main/java"),
                File(dir, "app/src/main/java"),
            )) {
                if (File(candidate, "dev/cyberdogs/offleash/ui/OffleashRoot.kt").isFile) {
                    return candidate
                }
            }
            dir = dir.parentFile
            hops++
        }
        throw AssertionError(
            "app/src/main/java not found from " + File(".").absolutePath,
        )
    }

    /** Core production sources, located relative to the app root
     *  (the repo layout is the same wherever the tests run). */
    private fun coreSources(): Map<String, String> {
        val javaRoot = locateAppJavaRoot()
        val coreRoot = File(javaRoot, "../../../../core/src/main/kotlin")
            .canonicalFile
        assertTrue(
            "core sources must be locatable beside the app module " +
                "(anchor: the parser whose lists the view census " +
                "is about)",
            File(coreRoot, "dev/cyberdogs/offleash/core/parse/Parsers.kt").isFile,
        )
        val out = sortedMapOf<String, String>()
        coreRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val relative = file.relativeTo(coreRoot).invariantSeparatorsPath
                out[relative] = file.readText()
            }
        assertTrue("core walk must find the production sources", out.isNotEmpty())
        return out
    }
}
