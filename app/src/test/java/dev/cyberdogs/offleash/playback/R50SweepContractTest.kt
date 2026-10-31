package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 50 sweep contracts (confirmation round — breaking round
 * 49's module census):
 *
 * R49 made the state-write census module-wide, but it censused
 * two property NAMES and six cast SPELLINGS. Proven live this
 * round, each green across the full app suite (604):
 *
 *  (F1) A republication through `MutableStateFlow.update { }`
 *  carries no `.value =` text and no `compareAndSet` text, so a
 *  tail helper in SearchViewModel narrowing the landed search to
 *  its first section after every load-more merge was invisible
 *  to R49's census (and to R48's, whose `.update {` ban lives
 *  only inside its slices). The update family is a StateFlow
 *  write shape exactly as `compareAndSet` is, and it is now
 *  banned module-wide (zero shipped uses), while EVERY
 *  `_name.value =` write — any property name, any spacing — is
 *  counted per file per property, so no write hides behind a
 *  third name or a respelled equals.
 *
 *  (F2) A ViewModel can grow a PARALLEL state under a third name
 *  and a screen can be switched to collect it, and no count in
 *  the tree moves: SearchViewModel gained `_narrowed`, mirrored
 *  the landed content narrowed to one section into it from both
 *  publish sites (needle-free call lines; the tail placement is
 *  round 49's own F1 lesson), and SearchScreen collected
 *  `viewModel.narrowed` instead of `viewModel.state` — the
 *  search page would have rendered the narrowed mirror while
 *  every publish pin stayed green. The fix censuses flow
 *  DECLARATIONS per file (a new `MutableStateFlow` anywhere is
 *  a census event) and pins each screen's collected
 *  `viewModel.<flow>` expressions as a per-file multiset.
 *
 *  (F3) The parser returns its own ArrayList instances as every
 *  published list (runtime-probed: HomeFeed.sections/chips,
 *  Section.items, SearchResults.sections/filters,
 *  AlbumPage/PlaylistPage.tracks, Track.artists, the moods and
 *  next lists are all `java.util.ArrayList` under their `List`
 *  type, and an in-place clear through the cast rewrites the
 *  very page instance a screen published). R49 banned the plain
 *  cast spellings, but `page.tracks as java.util.ArrayList`
 *  contains none of them ("as ArrayList" is not a substring of
 *  "as java.util.ArrayList"), and a PlaylistViewModel helper
 *  narrowing every playlist page to its first track through
 *  that spelling ran green. The fix bans the word `ArrayList`
 *  module-wide in app production (zero shipped occurrences in
 *  any spelling), the fully-qualified MutableList /
 *  MutableStateFlow cast spellings, `typealias` (zero shipped —
 *  an alias is the remaining way to spell a cast without any
 *  banned name), and aliased imports (zero shipped).
 *
 *  (F4) R49 bound every `rememberTrackActions` call in its
 *  shipped TEXT, so a wrapper defeats it by renaming: a
 *  `TrackMenuHost` composable (in an existing file — a new file
 *  trips R49's file-set census, verified) declaring
 *  `(goAlbum, goArtist)` and passing them CROSSED into
 *  `rememberTrackActions` under the renamed locals, with
 *  AlbumScreen's live `trackActions` routed through the host
 *  and the shipped call kept as a decoy val, cross-wired every
 *  track menu on the album page while every R49 count held.
 *  The fix stops binding call TEXT and censuses call
 *  INVENTORY: per-file `rememberTrackActions(` counts, the
 *  extracted argument list of every call (whitespace-
 *  normalized, balanced-paren) as a per-file multiset of the
 *  shipped forms, and `TrackActions(` constructions confined
 *  to TrackMenu's declaration + factory. A host in any file is
 *  one more call or one more construction — a census event
 *  under any parameter names.
 *
 *  (F5, round 49 remainder (ii) closed) Reflection was the one
 *  write channel R49 disclosed as uncensused. A census of app
 *  AND core production finds zero reflection entry points
 *  (no `reflect`, `forName`, `getDeclared…`, `setAccessible`,
 *  or `callBy` anywhere — the only `::class.java` uses hand a
 *  class literal to an Intent or `getSystemService`, never a
 *  field), so the closure is pinnable outright: those spellings
 *  are banned in both modules' production code.
 *
 * The ViewModel/UI files are Compose-bound, so the shape is
 * the pin (house style; CI is the compile gate).
 */
class R50SweepContractTest {

    @Test
    fun everyStateFlowWriteAndDeclarationIsCensusedModuleWide() {
        val files = moduleSources()
        val expectedWrites: Map<String, Map<String, Int>> = mapOf(
            "dev/cyberdogs/offleash/OffleashApp.kt" to mapOf("_accountUiState" to 2, "_showPlayerRequests" to 1),
            "dev/cyberdogs/offleash/data/AppPreferences.kt" to mapOf("_audioVersionInRadioFlow" to 2, "_autoDownloadLikedFlow" to 2, "_downloadQualityFlow" to 2, "_streamQualityFlow" to 2),
            "dev/cyberdogs/offleash/data/DownloadStore.kt" to mapOf("_records" to 1),
            "dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to mapOf("_phase" to 10, "_uiState" to 9),
            "dev/cyberdogs/offleash/data/LikeController.kt" to mapOf("_accountBacked" to 1, "_error" to 2, "_states" to 3),
            "dev/cyberdogs/offleash/data/ParityPreferences.kt" to mapOf("_audioVersionInRadioFlow" to 2, "_autoDownloadLikedFlow" to 2, "_downloadQualityFlow" to 2),
            "dev/cyberdogs/offleash/data/SessionManager.kt" to mapOf("_uiState" to 1),
            "dev/cyberdogs/offleash/data/SignInDiagnostics.kt" to mapOf("_snapshot" to 1),
            "dev/cyberdogs/offleash/data/Stores.kt" to mapOf("_playlists" to 3, "_recents" to 3),
            "dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt" to mapOf("_snapshot" to 1),
            "dev/cyberdogs/offleash/playback/QueueController.kt" to mapOf("_namedError" to 9, "_playbackState" to 2, "_queueState" to 1, "_sleepTimerRemainingMs" to 4, "_sleepTimerState" to 3, "_speedState" to 2),
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to mapOf("_hasMore" to 3, "_loadMoreError" to 3, "_state" to 7),
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to mapOf("_state" to 2),
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to mapOf("_feedNote" to 2, "_selectedChip" to 1, "_state" to 2),
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to mapOf("_state" to 4),
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to mapOf("_hasMore" to 3, "_loadMoreError" to 3, "_state" to 4),
        )
        val expectedDeclarations: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/OffleashApp.kt" to 2,
            "dev/cyberdogs/offleash/data/AppPreferences.kt" to 4,
            "dev/cyberdogs/offleash/data/DownloadStore.kt" to 1,
            "dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to 2,
            "dev/cyberdogs/offleash/data/LikeController.kt" to 3,
            "dev/cyberdogs/offleash/data/ParityPreferences.kt" to 3,
            "dev/cyberdogs/offleash/data/SessionManager.kt" to 1,
            "dev/cyberdogs/offleash/data/SignInDiagnostics.kt" to 1,
            "dev/cyberdogs/offleash/data/Stores.kt" to 2,
            "dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt" to 1,
            "dev/cyberdogs/offleash/playback/QueueController.kt" to 6,
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 5,
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 3,
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to 2,
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 3,
        )
        // Round 52 (carried r51b F1): the census counts compound
        // assignment (`+=` / `-=`) alongside plain assignment —
        // the shipped `_showPlayerRequests.value += 1` in
        // OffleashApp was invisible to the plain-only regex and
        // its property was absent from this table entirely, so
        // the true shipped write total is 109, not 108. Counting
        // both spellings per property makes the census
        // spelling-invariant: rewriting the compound line as
        // `.value = .value + 1` moves it between spellings, not
        // in or out of the count.
        val writeRegex = Regex("""(_[A-Za-z0-9]+)\.value\s*(?:=(?!=)|\+=|-=)""")
        val declarationRegex = Regex("""MutableStateFlow\s*(?:<|\()""")
        for ((path, text) in files) {
            val writes = writeRegex.findAll(text)
                .groupingBy { it.groupValues[1] }
                .eachCount()
            assertEquals(
                "$path must hold exactly its shipped StateFlow " +
                    "writes, counted by property name over EVERY " +
                    "`_name.value =` spelling — a write under a " +
                    "third property name (a parallel narrowed " +
                    "state) or behind respelled spacing is one " +
                    "more entry here, not a silent landing",
                expectedWrites[path] ?: emptyMap<String, Int>(),
                writes,
            )
            val declarations = declarationRegex.findAll(
                text.lines().filterNot { it.startsWith("import ") }
                    .joinToString("\n"),
            ).count()
            assertEquals(
                "$path must declare exactly its shipped " +
                    "MutableStateFlow(s) — a parallel state begins " +
                    "with a declaration, and a declaration in any " +
                    "file is a census event",
                expectedDeclarations[path] ?: 0,
                declarations,
            )
            assertEquals(
                "$path must hold no StateFlow update-family write " +
                    "(`.update {}` / `.update(...)` / updateAndGet / " +
                    "getAndUpdate carry no `.value =` text and no " +
                    "compareAndSet text — the one write shape the " +
                    "round-49 census could not see)",
                0,
                Regex("""\.update\b""").findAll(text).count() +
                    occurrences(text, "updateAndGet") +
                    occurrences(text, "getAndUpdate"),
            )
        }
    }

    @Test
    fun screensCollectExactlyTheShippedViewModelFlows() {
        val files = moduleSources()
        val expected: Map<String, List<String>> = mapOf(
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to listOf("state", "state", "state", "hasMore", "loadMoreError"),
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to listOf("state"),
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to listOf("state", "feedNote", "selectedChip"),
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to listOf("state", "state"),
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to listOf("state", "hasMore", "loadMoreError"),
        )
        val collectRegex = Regex("""viewModel\.(\w+)\.collectAsState\(\)""")
        for ((path, text) in files) {
            val collected = collectRegex.findAll(text)
                .map { it.groupValues[1] }
                .toList()
            assertEquals(
                "$path must collect exactly its shipped ViewModel " +
                    "flows, in order — switching a screen to a " +
                    "parallel flow (the round-50 narrowed mirror) " +
                    "changes this multiset even when the ViewModel " +
                    "still publishes the honest state beside it",
                expected[path] ?: emptyList<String>(),
                collected,
            )
        }
    }

    @Test
    fun castAndAliasChannelsAreClosedModuleWide() {
        val files = moduleSources()
        for ((path, text) in files) {
            assertEquals(
                "$path must never name ArrayList in any spelling — " +
                    "the parser's published lists ARE ArrayLists " +
                    "under their List type, and the fully-qualified " +
                    "cast (`as java.util.ArrayList`) contains none " +
                    "of the round-49 banned spellings while opening " +
                    "the same in-place road",
                0,
                occurrences(text, "ArrayList"),
            )
            assertEquals(
                "$path must never name AbstractList either (the " +
                    "same concrete-family cast one level up)",
                0,
                occurrences(text, "AbstractList"),
            )
            for (cast in listOf(
                "as kotlin.collections.MutableList",
                "as? kotlin.collections.MutableList",
                "as kotlinx.coroutines.flow.MutableStateFlow",
                "as? kotlinx.coroutines.flow.MutableStateFlow",
                "as java.util.MutableList",
                "as? java.util.MutableList",
            )) {
                assertEquals(
                    "$path must hold no fully-qualified cast " +
                        "spelling `$cast` — qualifying the type name " +
                        "does not change the cast, only its visibility " +
                        "to a census keyed on the plain spelling",
                    0,
                    occurrences(text, cast),
                )
            }
            assertEquals(
                "$path must declare no typealias — an alias is the " +
                    "one remaining way to spell a cast to a mutable " +
                    "type without any banned name appearing at the " +
                    "cast site, and the shipped tree declares none",
                0,
                occurrences(text, "typealias"),
            )
            assertTrue(
                "$path must hold no aliased import (`import … as …` " +
                    "renames a type for every use below it, casts " +
                    "included; the shipped tree has none)",
                text.lines().none {
                    it.startsWith("import ") && it.contains(" as ")
                },
            )
        }
    }

    @Test
    fun reflectionIsAbsentFromProductionCode() {
        val spellings = listOf(
            "reflect", "forName", "getDeclared", "setAccessible",
            "callBy", "getField(", "getMethod(",
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                for (spelling in spellings) {
                    assertEquals(
                        "$label:$path must hold no reflection entry " +
                            "point (`$spelling`) — a reflective write " +
                            "to a private flow field carries none of " +
                            "the spellings any census counts, and the " +
                            "shipped tree's zero is the closure round " +
                            "49 left open (its remainder (ii))",
                        0,
                        occurrences(text, spelling),
                    )
                }
            }
        }
    }

    @Test
    fun trackActionsAreObtainedOnlyThroughTheShippedCalls() {
        val files = moduleSources()
        val expectedCallCounts: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to 1,
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 3,
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 2,
            "dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 1,
        )
        val shippedArgs: Map<String, List<String>> = mapOf(
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to listOf(
                "container, onAlbumClick, onArtistClick",
                "container, onAlbumClick, onArtistClick",
                "container, onAlbumClick, onArtistClick",
            ),
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to listOf(
                "container, onAlbumClick, onArtistClick",
            ),
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to listOf(
                "container, onAlbumClick, onArtistClick",
            ),
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to listOf(
                "container, onAlbumClick, onArtistClick",
            ),
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to listOf(
                "container, onAlbumClick, onArtistClick,",
                "container, onAlbumClick, onArtistClick,",
            ),
            "dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to listOf(
                "container = container, onAlbumClick = onAlbumClick, " +
                    "onArtistClick = onArtistClick, // Already on the " +
                    "player: the deep-link just returns to it. " +
                    "onShowPlayer = onBack,",
            ),
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to listOf(
                "container, onAlbumClick, onArtistClick",
            ),
        )
        for ((path, text) in files) {
            assertEquals(
                "$path must hold exactly its shipped " +
                    "`rememberTrackActions(` occurrences — a wrapper " +
                    "host is one more call, under whatever parameter " +
                    "names it declares, and the shipped-call TEXT " +
                    "surviving as a decoy does not excuse it",
                expectedCallCounts[path] ?: 0,
                occurrences(text, "rememberTrackActions("),
            )
            assertEquals(
                "$path must hold no TrackActions construction " +
                    "outside TrackMenu's declaration and factory — " +
                    "a host that builds TrackActions directly " +
                    "crosses the same joint without calling the " +
                    "factory at all",
                if (path.endsWith("ui/components/TrackMenu.kt")) 2 else 0,
                occurrences(text, "TrackActions(") -
                    occurrences(text, "rememberTrackActions("),
            )
            if (!path.endsWith("ui/components/TrackMenu.kt")) {
                assertEquals(
                    "$path's rememberTrackActions calls must pass " +
                        "exactly the shipped argument lists — the " +
                        "joint is rename-proof only when the " +
                        "inventory, not the spelling, is the pin",
                    shippedArgs[path] ?: emptyList<String>(),
                    callArguments(text, "rememberTrackActions("),
                )
            }
        }
    }

    /** The argument text of every `needle` call, paren-balanced
     *  and whitespace-normalized, in document order. */
    private fun callArguments(text: String, needle: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = text.indexOf(needle, from)
            if (start < 0) break
            var depth = 1
            var i = start + needle.length
            val argStart = i
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            out.add(
                text.substring(argStart, i - 1)
                    .replace(Regex("""\s+"""), " ")
                    .trim(),
            )
            from = i
        }
        return out
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** The app module walk, shared with R49's census shape, with
     *  its completeness pinned on the anchor files. */
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
                "the module walk must find its anchor file $anchor " +
                    "(a walk that silently covers less than the " +
                    "module is the hole every census inherits)",
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
                "(anchor: the parser whose lists the cast census " +
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
