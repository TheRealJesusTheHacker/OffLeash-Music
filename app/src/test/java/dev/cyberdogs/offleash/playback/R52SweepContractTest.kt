package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 52 sweep contracts (adversarial lane — closing the
 * carried r51b F1 and breaking round 51's fixes):
 *
 *  (Charter 0, carried r51b F1 — CLOSED here) The R50 write
 *  census regex `(_name)\.value\s*=(?!=)` never matches
 *  compound assignment, and its table omitted the property
 *  entirely: OffleashApp's `requestShowPlayer()` writes
 *  `_showPlayerRequests.value += 1`, so the true shipped
 *  StateFlow write total was 109, not the censused 108 — and
 *  R51's receiver-blind per-file totals, keyed on the same
 *  plain spelling, still summed to 108. Proven live this
 *  round: a second shipped-form compound write ran green
 *  across R50+R51. The fix makes the whole census family
 *  spelling-invariant: R50's per-property regex and R51's
 *  receiver-blind totals now count `+=`/`-=` alongside `=`
 *  (R50's table gains `_showPlayerRequests` at 1; R51's
 *  OffleashApp total is 3; the family total is 109), and
 *  this class pins the compound table on its own — per
 *  property, per file over any receiver, with the
 *  increment/decrement spellings and the `plusAssign`
 *  spelling at their shipped zero (no shipped flow value
 *  type — Int, Boolean, String?, data classes, Lists —
 *  declares a `plusAssign` member, so `.value.plusAssign(…)`
 *  is not constructible against any of them; the zero census
 *  keeps the spelling closed if a value type ever grows one).
 *
 *  (Charter 2 — NEW) Channel is a third state family the
 *  census never named. A parallel-state chain built on
 *  `Channel<SearchUiState>(Channel.CONFLATED)` in
 *  SearchViewModel ran green link by link and then across
 *  the full suite: the declaration matches no declaration
 *  census (StateFlow/SharedFlow spellings only), the write
 *  `narrowCh.trySend(narrowed)` matches no write census,
 *  and the render path — `narrowCh.receiveAsFlow()` exposed
 *  on the ViewModel and collected in SearchScreen through
 *  a local alias into a Compose state — moves neither the
 *  collected-flow multiset (collectAsState expressions
 *  only; the shipped collectAsState kept as decoy) nor
 *  R51's raw-collect ban (which requires the literal
 *  `viewModel.` receiver). `stateIn` is likewise censused
 *  nowhere. The one legitimate Channel in the tree is a
 *  data-layer signal (GoogleSessionManager's check-now
 *  wake-up, never rendered), so the pin scopes the family
 *  to where state is rendered: ViewModel/screen files (the
 *  `ui/` tree) hold no Channel/trySend/receiveAsFlow/
 *  stateIn/`.send(` at all, and `receiveAsFlow`/`stateIn`
 *  are zero module-wide.
 *
 *  (Charter 3 — NEW) Navigation dispatch can abandon the
 *  `.onXClick(` spelling entirely. Two constructions ran
 *  green across the full suite: (a) string-keyed dispatch —
 *  `val nav = mapOf("album" to actions.onArtistClick, …)`
 *  fired as `nav["album"]?.invoke(id)` in TrackMenu, no
 *  invocation text anywhere; (b) a neutral-val pass-through —
 *  a helper composable taking `go: (String) -> Unit` and
 *  rendering the "Album" item through it, with the call
 *  site (inside the shipped artist item's block) passing
 *  `actions.onArtistClick` — no pin inventories
 *  helper-parameter pass-throughs, so the crossing sits in
 *  an unbound position. The fix censuses the dispatch
 *  machinery itself: `mapOf(` per file at its shipped count
 *  (Downloads' state-machine table is the only one), every
 *  `invoke(` per file at its shipped count (RangeInterceptor's
 *  callback is the only one), and the navigation-lambda
 *  TYPE `(String) -> Unit` / `(String, String?) -> Unit` per
 *  file at its shipped counts (38 total) — a new helper
 *  parameter of navigation type is one more occurrence in
 *  whatever file declares it.
 *
 * Charter 1's isolated doctors are recorded in the lane
 * report, not here: the suspend `emit` and all seven
 * non-shuffle `java.util.Collections` mutators each fail
 * exactly their R51 test already (R51's needles are the
 * `.emit(` spelling and the `Collections.` family), so no
 * new pin was needed for them.
 *
 * The ViewModel/UI files are Compose-bound, so the shape is
 * the pin (house style; CI is the compile gate).
 */
class R52SweepContractTest {

    @Test
    fun compoundStateFlowWritesAreCensusedPerPropertyAndReceiverBlind() {
        val files = moduleSources()
        // The COMBINED census: plain and compound are two
        // spellings of one write, so the tables below count
        // them together — a write's spelling may move between
        // the plain and compound tables (the shipped compound
        // line rewritten as `.value = .value + 1`), but it is
        // counted exactly once in either form, per property
        // and per file over any receiver.
        val combinedRegex =
            Regex("""(_[A-Za-z0-9]+)\.value\s*(?:=(?!=)|\+=|-=)""")
        val anyReceiverCombined =
            Regex("""\.value\s*(?:=(?!=)|\+=|-=)""")
        val anyReceiverPlain = Regex("""\.value\s*=(?!=)""")
        val anyReceiverCompound = Regex("""\.value\s*[+\-]=""" )
        val expectedCombined: Map<String, Map<String, Int>> = mapOf(
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
        val expectedTotals: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/OffleashApp.kt" to 3,
            "dev/cyberdogs/offleash/data/AppPreferences.kt" to 8,
            "dev/cyberdogs/offleash/data/DownloadStore.kt" to 1,
            "dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to 19,
            "dev/cyberdogs/offleash/data/LikeController.kt" to 6,
            "dev/cyberdogs/offleash/data/ParityPreferences.kt" to 6,
            "dev/cyberdogs/offleash/data/SessionManager.kt" to 1,
            "dev/cyberdogs/offleash/data/SignInDiagnostics.kt" to 1,
            "dev/cyberdogs/offleash/data/Stores.kt" to 6,
            "dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt" to 1,
            "dev/cyberdogs/offleash/playback/QueueController.kt" to 21,
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 13,
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to 2,
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 5,
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to 4,
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 10,
        )
        var grandCombined = 0
        for ((path, text) in files) {
            val combined = combinedRegex.findAll(text)
                .groupingBy { it.groupValues[1] }
                .eachCount()
            assertEquals(
                "$path must hold exactly its shipped StateFlow " +
                    "writes counted per property over BOTH " +
                    "spellings (plain `=` and compound `+=`/`-=" +
                    "`) — round 50's regex required `=` " +
                    "immediately after `.value`, so the shipped " +
                    "compound write on _showPlayerRequests was " +
                    "counted nowhere and its property was absent " +
                    "from the table; here it stands at 1, and a " +
                    "compound write on any flow is one more " +
                    "entry in this map",
                expectedCombined[path] ?: emptyMap<String, Int>(),
                combined,
            )
            val plainCount = anyReceiverPlain.findAll(text).count()
            val compoundCount =
                anyReceiverCompound.findAll(text).count()
            assertEquals(
                "$path's plain and compound tables must " +
                    "partition its combined receiver-blind " +
                    "total exactly — every write is counted in " +
                    "exactly one of the two tables, in either " +
                    "spelling, over any receiver (a compound " +
                    "write through a local alias, `val f = " +
                    "_showPlayerRequests; f.value += 1`, names " +
                    "no property and is caught HERE)",
                plainCount + compoundCount,
                anyReceiverCombined.findAll(text).count(),
            )
            assertEquals(
                "$path must hold exactly its shipped COMBINED " +
                    "`.value` write total over any receiver " +
                    "(the totals sum to the true 107: 106 plain " +
                    "+ 1 compound; the total was 112 through " +
                    "0.8.2, and 0.8.3's removal of the legacy " +
                    "session road took the old SessionManager's " +
                    "five extra _uiState writes with it, leaving " +
                    "the one sign-out write)",
                expectedTotals[path] ?: 0,
                anyReceiverCombined.findAll(text).count(),
            )
            assertEquals(
                "$path must hold no `.value++` / `.value--` " +
                    "write and no `plusAssign` spelling — the " +
                    "remaining compound shapes; shipped uses " +
                    "are zero, and no shipped flow value type " +
                    "declares plusAssign, so that spelling is " +
                    "not constructible against any of them",
                0,
                Regex("""\.value\s*(?:\+\+|--)""").findAll(text).count() +
                    occurrences(text, "plusAssign"),
            )
            grandCombined += anyReceiverCombined.findAll(text).count()
        }
        assertEquals(
            "the module's true shipped StateFlow write total, " +
                "plain and compound together, is 107 (112 " +
                "through 0.8.2; 0.8.3's legacy-road removal " +
                "is -5 — see the per-file note above)",
            107,
            grandCombined,
        )
    }

    @Test
    fun channelFamilyIsAbsentFromViewModelAndScreenFiles() {
        val files = moduleSources()
        for ((path, text) in files) {
            assertEquals(
                "$path must expose no flow through " +
                    "receiveAsFlow — a Channel (or any flow) " +
                    "surfaced to a screen this way reaches " +
                    "rendering without ever being a censused " +
                    "StateFlow declaration; shipped uses are " +
                    "zero module-wide",
                0,
                occurrences(text, "receiveAsFlow"),
            )
            assertEquals(
                "$path must hold no stateIn conversion — a " +
                    "stateIn-derived flow is parallel state " +
                    "manufactured from an uncensused source, " +
                    "and the spelling was censused nowhere " +
                    "before this round; shipped uses are zero",
                0,
                occurrences(text, "stateIn"),
            )
            if (path.contains("/ui/")) {
                for (needle in listOf(
                    "Channel", "trySend", ".send(",
                )) {
                    assertEquals(
                        "$path is a ViewModel/screen file and " +
                            "must hold no `$needle` — a Channel " +
                            "held by a ViewModel and collected " +
                            "by its screen is a parallel state " +
                            "under a third family name: its " +
                            "declaration matches no flow census, " +
                            "its trySend writes match no write " +
                            "census, and its collection through " +
                            "a local alias moves no collected-" +
                            "flow multiset (proven green link by " +
                            "link this round). The tree's one " +
                            "legitimate Channel is a data-layer " +
                            "signal in GoogleSessionManager, " +
                            "outside this tree and never rendered",
                        0,
                        occurrences(text, needle),
                    )
                }
            }
        }
    }

    @Test
    fun navigationDispatchCarriesNoUncensusedSpelling() {
        val files = moduleSources()
        val expectedMapOf: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/data/Downloads.kt" to 1,
        )
        val expectedInvoke: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/data/RangeInterceptor.kt" to 1,
        )
        val expectedNavLambdaType: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/ui/OffleashRoot.kt" to 3,
            "dev/cyberdogs/offleash/ui/components/Common.kt" to 3,
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to 2,
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 7,
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to 3,
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 3,
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to 3,
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 7,
            "dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to 4,
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 3,
        )
        val navLambdaType =
            Regex("""\((?:String|String, String\?)\)\s*->\s*Unit""")
        for ((path, text) in files) {
            assertEquals(
                "$path must hold exactly its shipped `mapOf(` " +
                    "count — a string-keyed dispatch table " +
                    "carrying navigation lambdas (`mapOf(\"album\" " +
                    "to onArtistClick, …)` fired as " +
                    "`nav[\"album\"]?.invoke(id)`) abandons the " +
                    "invocation spelling every navigation census " +
                    "counts; Downloads' download-state table is " +
                    "the only shipped mapOf",
                expectedMapOf[path] ?: 0,
                occurrences(text, "mapOf("),
            )
            assertEquals(
                "$path must hold exactly its shipped `invoke(` " +
                    "count — an explicit invoke() is the other " +
                    "half of the string-keyed dispatch (and of " +
                    "any lambda called without its name); " +
                    "RangeInterceptor's response callback is the " +
                    "only shipped use",
                expectedInvoke[path] ?: 0,
                occurrences(text, "invoke("),
            )
            assertEquals(
                "$path must declare exactly its shipped number " +
                    "of navigation-lambda-typed parameters " +
                    "(`(String) -> Unit` / `(String, String?) -> " +
                    "Unit`) — a NEW helper composable taking a " +
                    "neutral `go: (String) -> Unit` creates an " +
                    "unbound pass-through position: the call " +
                    "site can hand it the artist lambda for the " +
                    "album slot with no invocation text at the " +
                    "crossing (proven green this round). Every " +
                    "such parameter in the module is one of " +
                    "these 38 shipped declarations",
                expectedNavLambdaType[path] ?: 0,
                navLambdaType.findAll(text).count(),
            )
        }
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** The app module walk, shared with R49/R50/R51's census
     *  shape, with its completeness pinned on the anchor files. */
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
    @Test
    fun searchSubmitSearchesThenDismissesTheKeyboard() {
        val text = searchScreenSource()
        val submitBlock =
            "keyboardActions = KeyboardActions(\n" +
                "                onSearch = {\n" +
                "                    viewModel.search(query, selectedFilter)\n"
        assertEquals(
            "the IME submit block must appear exactly once, " +
                "opening with the same search call the debounce " +
                "path makes — a second submit binding or a " +
                "divergent call is a second search path",
            1,
            occurrences(text, submitBlock),
        )
        val blockStart = text.indexOf(submitBlock)
        val clearIndex = text.indexOf("focusManager.clearFocus()", blockStart)
        assertTrue(
            "the submit must clear focus (dismissing the " +
                "keyboard) after running the search — before " +
                "round 52 the submit searched and left the " +
                "keyboard up over the results",
            clearIndex > blockStart,
        )
        assertEquals(
            "exactly one focus clear may exist in the search " +
                "screen — the debounce/typing path must never " +
                "dismiss the keyboard mid-query",
            1,
            occurrences(text, "focusManager.clearFocus()"),
        )
        assertEquals(
            "the focus manager must be obtained exactly once, " +
                "from the composition local",
            1,
            occurrences(text, "val focusManager = LocalFocusManager.current"),
        )
        assertEquals(
            "both search triggers (the debounce effect and the " +
                "IME submit) must call the one shipped search " +
                "entry with the one shipped argument pair",
            2,
            occurrences(text, "viewModel.search(query, selectedFilter)"),
        )
    }

    private fun searchScreenSource(): String {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            for (candidate in listOf(
                File(dir, "src/main/java"),
                File(dir, "app/src/main/java"),
            )) {
                val file = File(
                    candidate,
                    "dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
                )
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        throw AssertionError(
            "SearchScreen.kt not found from " + File(".").absolutePath,
        )
    }
}
