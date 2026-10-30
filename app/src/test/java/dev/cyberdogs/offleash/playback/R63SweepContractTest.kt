package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 63 sweep contracts (adversarial lane — comment-in-the-
 * gap generalization, and breaking the round-62 fixes):
 *
 *  (F1, charter 1a — the WRITE side of round 62's comment
 *  evasion IS open, for the additive form round 62 did not
 *  construct) Every write-census leg keys on the SAME shape —
 *  `\.value\s*(?:=|\+=|-=)` (round 50 per-property, round 51
 *  receiver-blind totals, round 52 combined/partition) — and
 *  Kotlin treats a comment as whitespace while `\s` does not
 *  span one. Respelling a SHIPPED write is caught (the exact
 *  counts drop), and a comment AFTER the operator or BEFORE
 *  `.value` is caught (the totals still count the intact
 *  `.value =` text) — all four respell placements were run
 *  this round and fail their legs. But an ADDED write with
 *  the comment in the operator gap is invisible to all three
 *  legs at once, because the legs' redundancy is redundancy
 *  of TABLES over one shared pattern, not of patterns:
 *   - `_state.value /* gap */ = SearchUiState.Error(...)`
 *     appended as a tail write in SearchViewModel.search()
 *     (every landed search overwritten with an error) ran
 *     GREEN across the full 651-test suite;
 *   - `_showPlayerRequests.value /* gap */ += 1` added in
 *     OffleashApp.requestShowPlayer() (every show-player
 *     request double-fires) ran GREEN across the full suite.
 *  (Round 62's report recorded the write side as not open;
 *  its doctor respelled the shipped compound line, which the
 *  exact counts catch — the additive form is the open one.)
 *
 *  (F2, charter 1c — the same gap class in two more regex
 *  families) Round 56's operator census (`\.map\s*[({]`,
 *  `\.collectAsState\s*\(`) and round 57's receiver census
 *  share the adjacency assumption: PlaybackDiagnostics
 *  gained `val latestLine = _snapshot.map /* gap */ { ... }`
 *  (inferred type — no `Flow<` text for round 54), the
 *  diagnostics screen collected it as
 *  `collectAsState/* gap */(initial = "")` and rendered it
 *  INSTEAD of the shipped snapshot text (the shipped collect
 *  kept as decoy) — GREEN across the full suite, and the
 *  production leg compiles clean against the harness subset.
 *  Round 51's navigation census keys on the SUBSTRING
 *  `.onAlbumClick(`: an added crossed invocation
 *  `actions.onAlbumClick/* gap */(artistId)` inside
 *  TrackMenu's Artist item (tapping Artist ALSO fires album
 *  navigation with the artist id) moves neither the
 *  invocation counts nor the crossed-pair bans — GREEN
 *  across the full suite.
 *
 *  THE FIX for F1+F2 is one discipline, applied below: the
 *  affected censuses are re-run on a COMMENT-NORMALIZED view
 *  of every file (a small Kotlin-aware scanner replaces each
 *  comment with a space, preserving newlines and string /
 *  char literals verbatim), with the SAME shipped tables.
 *  On the shipped tree the normalized counts are identical
 *  to the raw counts for every pattern in this battery
 *  (verified pattern-by-pattern this round), so the tables
 *  carry over unchanged; under any comment-gap doctor the
 *  normalized view sees the plain spelling and the count
 *  moves. Comments can no longer hide census-shaped text
 *  in either direction.
 *
 *  (F1b, charter 1b — count-masking against round 62's RAW
 *  name census) The raw census counts name occurrences, so
 *  a comment or string MENTION of `MutableStateFlow` in a
 *  file would be slack a doctor could trade against a real
 *  birth at net zero. Shipped reality (scanner-enumerated
 *  this round): the module's 39 non-import namings are ALL
 *  constructions (each immediately followed by `<` or `(`);
 *  comment mentions and string mentions are ZERO in both
 *  modules. The fourth test pins that zero, so a future
 *  innocent comment naming the class fails HERE, by name,
 *  before it can ever serve as masking slack.
 *
 *  (F3, charter 2 — getter-computed NON-flow properties)
 *  Round 62 zero-censused getter-computed FLOW properties.
 *  The sibling stood open: SearchViewModel's
 *  `continuationTracker` — an initializer-form, non-flow,
 *  decision-bearing property (the paging tracker every
 *  search/loadMore read goes through) — converted to a
 *  TYPED getter returning a FRESH tracker per read
 *  (`private val continuationTracker: ContinuationTracker
 *  get() = ContinuationTracker()`: paging state never
 *  persists, Load more silently dead) ran GREEN across the
 *  full suite: the declaration censuses count initializers
 *  and flows, round 62's untyped leg requires the type-less
 *  spelling, and rounds 5/47 bind only the tracker's USE
 *  sites. The shipped getter inventory (enumerated this
 *  round) is eleven properties in four files — the six
 *  store-fresh preference getters and three parity twins
 *  (all decision-bearing: quality / auto-download / radio /
 *  speed / pitch reads), DownloadRecord.videoId (the key
 *  every record map is built on) and SleepTimerState.
 *  isArmed (the queue's stop decision). Their BODIES are
 *  partly runtime-pinned already (this round's control
 *  doctors: narrowing playbackSpeed's clamp fails
 *  AppPreferencesTest's round-trip + malformed tests;
 *  dropping isArmed's end-of-track leg fails
 *  SleepTimerTest's end-of-track test) — the fifth test
 *  censuses the getter INVENTORY for every type and binds
 *  every shipped getter's body verbatim, so a conversion,
 *  an addition, or a body doctor is a census event
 *  whatever the property's type.
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R63SweepContractTest {

    // -- F1: writes and births on the normalized view --------------------

    @Test
    fun stateWritesAndFlowBirthsAreCensusedOnACommentNormalizedView() {
        val files = moduleSources()
        val expectedWrites: Map<String, Map<String, Int>> = mapOf(
            "dev/cyberdogs/offleash/OffleashApp.kt" to mapOf("_accountUiState" to 2, "_showPlayerRequests" to 1),
            "dev/cyberdogs/offleash/data/AppPreferences.kt" to mapOf("_audioVersionInRadioFlow" to 2, "_autoDownloadLikedFlow" to 2, "_downloadQualityFlow" to 2, "_streamQualityFlow" to 2),
            "dev/cyberdogs/offleash/data/DownloadStore.kt" to mapOf("_records" to 1),
            "dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to mapOf("_phase" to 10, "_uiState" to 9),
            "dev/cyberdogs/offleash/data/LikeController.kt" to mapOf("_accountBacked" to 1, "_error" to 2, "_states" to 3),
            "dev/cyberdogs/offleash/data/ParityPreferences.kt" to mapOf("_audioVersionInRadioFlow" to 2, "_autoDownloadLikedFlow" to 2, "_downloadQualityFlow" to 2),
            "dev/cyberdogs/offleash/data/SessionManager.kt" to mapOf("_uiState" to 6),
            "dev/cyberdogs/offleash/data/SignInDiagnostics.kt" to mapOf("_snapshot" to 1),
            "dev/cyberdogs/offleash/data/Stores.kt" to mapOf("_playlists" to 3, "_recents" to 3),
            "dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt" to mapOf("_snapshot" to 1),
            "dev/cyberdogs/offleash/playback/QueueController.kt" to mapOf("_namedError" to 9, "_playbackState" to 2, "_queueState" to 1, "_sleepTimerRemainingMs" to 4, "_sleepTimerState" to 3, "_speedState" to 2),
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to mapOf("_hasMore" to 3, "_loadMoreError" to 3, "_state" to 7),
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to mapOf("_state" to 2),
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to mapOf("_selectedChip" to 1, "_state" to 2),
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
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 2,
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to 2,
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 3,
        )
        val writeRegex = Regex("""(_[A-Za-z0-9]+)\.value\s*(?:=(?!=)|\+=|-=)""")
        val anyReceiverWrite = Regex("""\.value\s*(?:=(?!=)|\+=|-=)""")
        val declarationRegex = Regex("""MutableStateFlow\s*(?:<|\()""")
        var grandTotal = 0
        var grandBirths = 0
        for ((path, raw) in files) {
            val text = stripComments(raw)
            val writes = writeRegex.findAll(text)
                .groupingBy { it.groupValues[1] }
                .eachCount()
            assertEquals(
                "$path must hold exactly its shipped StateFlow " +
                    "writes on the COMMENT-NORMALIZED view — the " +
                    "raw-text legs (rounds 50/51/52) all key on " +
                    "`\\.value\\s*(op)`, and a block comment in " +
                    "that gap is whitespace to Kotlin but not " +
                    "to `\\s`: this round's added tail write " +
                    "`_state.value /* gap */ = Error(...)` and " +
                    "added compound `_showPlayerRequests" +
                    ".value /* gap */ += 1` each ran green " +
                    "across the full suite on raw text; on the " +
                    "normalized view both spell plainly and " +
                    "move these counts",
                expectedWrites[path] ?: emptyMap<String, Int>(),
                writes,
            )
            val total = anyReceiverWrite.findAll(text).count()
            grandTotal += total
            assertEquals(
                "$path must hold exactly its shipped receiver-" +
                    "blind `.value` write TOTAL on the " +
                    "normalized view (the alias-proof leg, " +
                    "comment-proofed)",
                expectedTotals[path] ?: 0,
                total,
            )
            val declarations = declarationRegex.findAll(
                text.lines().filterNot { it.startsWith("import ") }
                    .joinToString("\n"),
            ).count()
            assertEquals(
                "$path must declare exactly its shipped " +
                    "MutableStateFlow(s) on the normalized " +
                    "view — round 62's comment-spelled birth " +
                    "is counted here even before the raw-name " +
                    "census speaks, because the comment that " +
                    "hid it is a space here",
                expectedDeclarations[path] ?: 0,
                declarations,
            )
            val rawNames = text.lines()
                .filterNot { it.trimStart().startsWith("import ") }
                .sumOf { line -> line.split("MutableStateFlow").size - 1 }
            grandBirths += rawNames
            assertEquals(
                "$path must name MutableStateFlow exactly its " +
                    "shipped number of times on the normalized " +
                    "view (round 62's raw census, restated " +
                    "where a comment mention cannot inflate " +
                    "it: mentions are stripped with the comment)",
                expectedDeclarations[path] ?: 0,
                rawNames,
            )
        }
        assertEquals(
            "the module's shipped StateFlow write total on " +
                "the normalized view is 110, as on raw text",
            110,
            grandTotal,
        )
        assertEquals(
            "the module's shipped MutableStateFlow namings " +
                "on the normalized view total 39, as on raw text",
            39,
            grandBirths,
        )
    }

    // -- F2: collects and operators on the normalized view ---------------

    @Test
    fun collectsAndOperatorsAreCensusedOnACommentNormalizedView() {
        val collectRegex = Regex("""viewModel\.(\w+)\.collectAsState\(\)""")
        val expectedCollectVM: Map<String, List<String>> = mapOf(
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to listOf("state", "state", "state", "hasMore", "loadMoreError"),
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to listOf("state"),
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to listOf("state", "selectedChip"),
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to listOf("state", "state"),
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to listOf("state", "hasMore", "loadMoreError"),
        )
        for ((path, raw) in moduleSources()) {
            val collected = collectRegex.findAll(stripComments(raw))
                .map { it.groupValues[1] }
                .toList()
            assertEquals(
                "$path must collect exactly its shipped " +
                    "ViewModel flows on the normalized view " +
                    "(round 50's multiset, comment-proofed)",
                expectedCollectVM[path] ?: emptyList<String>(),
                collected,
            )
        }
        val zeroRegexes = listOf(
            Regex("""\.transform\s*[({]"""),
            Regex("""\.onStart\s*[({]"""),
            Regex("""\.onCompletion\s*[({]"""),
            Regex("""\.distinctUntilChanged\s*[({]"""),
            Regex("""\.debounce\s*[({]"""),
            Regex("""\.combine\s*[({]"""),
            Regex("""(?<![\w.])combine\s*[({]"""),
            Regex("""\.zip\s*[({]"""),
            Regex("""\.catch\s*\{"""),
            Regex("""\.retryWhen\s*[({]"""),
            Regex("""(?<![\w.])emit\s*\("""),
        )
        val zeroNeedles = listOf(
            "transformLatest", "mapLatest", "flatMapMerge",
            "flatMapConcat", "flatMapLatest", "emitAll",
        )
        val dotMap = Regex("""\.map\s*[({]""")
        val dotFilter = Regex("""\.filter\s*[({]""")
        val dotTake = Regex("""\.take\s*\(""")
        val dotDrop = Regex("""\.drop\s*\(""")
        val dotRetry = Regex("""\.retry\s*\(""")
        val dotCollect = Regex("""\.collectAsState\s*\(""")
        val expectedMap: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/AppPreferences.kt" to 4,
            "app:dev/cyberdogs/offleash/data/DownloadManager.kt" to 2,
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 6,
            "app:dev/cyberdogs/offleash/data/Downloads.kt" to 1,
            "app:dev/cyberdogs/offleash/data/Stores.kt" to 7,
            "app:dev/cyberdogs/offleash/playback/QueueController.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/components/Common.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 1,
            "core:dev/cyberdogs/offleash/core/api/YoutubeiClient.kt" to 4,
            "core:dev/cyberdogs/offleash/core/auth/SessionCookies.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/Parsers.kt" to 2,
            "core:dev/cyberdogs/offleash/core/playback/PlayQueue.kt" to 2,
        )
        val expectedFilter: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/AppPreferences.kt" to 8,
            "app:dev/cyberdogs/offleash/data/DownloadManager.kt" to 2,
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 1,
            "app:dev/cyberdogs/offleash/data/Downloads.kt" to 1,
            "app:dev/cyberdogs/offleash/data/SessionManager.kt" to 3,
            "app:dev/cyberdogs/offleash/data/SignInDiagnostics.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 1,
            "core:dev/cyberdogs/offleash/core/api/YoutubeiClient.kt" to 3,
            "core:dev/cyberdogs/offleash/core/auth/SessionCookies.kt" to 1,
            "core:dev/cyberdogs/offleash/core/model/Pagination.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/JsonNav.kt" to 1,
            "core:dev/cyberdogs/offleash/core/playback/PlayQueue.kt" to 2,
            "core:dev/cyberdogs/offleash/core/playback/StreamResolver.kt" to 4,
        )
        val expectedTake: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadManager.kt" to 1,
            "app:dev/cyberdogs/offleash/data/Downloads.kt" to 1,
            "app:dev/cyberdogs/offleash/data/Stores.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to 2,
            "core:dev/cyberdogs/offleash/core/api/YoutubeiClient.kt" to 1,
        )
        val expectedDrop: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to 2,
        )
        val expectedRetry: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/account/AutoDownloadLikedWorker.kt" to 1,
        )
        val expectedCollect: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/ui/OffleashRoot.kt" to 7,
            "app:dev/cyberdogs/offleash/ui/about/AboutScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/account/SignInScreen.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to 3,
            "app:dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 8,
            "app:dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 3,
            "app:dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 6,
            "app:dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to 9,
            "app:dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 3,
            "app:dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt" to 5,
        )
        val expectedLifecycle: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/ui/components/Common.kt" to 2,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, raw) in sources) {
                val key = "$label:$path"
                val text = stripComments(raw)
                for (regex in zeroRegexes) {
                    assertEquals(
                        "$key must hold no Flow-operator " +
                            "spelling matching `$regex` on the " +
                            "normalized view — round 56's " +
                            "census keys on the operator's " +
                            "adjacency to its paren/brace, and " +
                            "this round's derived flow " +
                            "`_snapshot.map /* gap */ { ... }` " +
                            "in PlaybackDiagnostics, collected " +
                            "through `collectAsState/* gap */" +
                            "(` and rendered instead of the " +
                            "shipped snapshot text, ran green " +
                            "on raw text across the full suite",
                        0,
                        regex.findAll(text).count(),
                    )
                }
                for (needle in zeroNeedles) {
                    assertEquals(
                        "$key must name no `$needle` on the " +
                            "normalized view (round 56's named " +
                            "spellings, comment-proofed)",
                        0,
                        occurrences(text, needle),
                    )
                }
                assertEquals(
                    "$key must hold exactly its shipped `.map` " +
                        "count on the normalized view",
                    expectedMap[key] ?: 0,
                    dotMap.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.filter` count on the normalized view",
                    expectedFilter[key] ?: 0,
                    dotFilter.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.take(` count on the normalized view",
                    expectedTake[key] ?: 0,
                    dotTake.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.drop(` count on the normalized view",
                    expectedDrop[key] ?: 0,
                    dotDrop.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.retry(` count on the normalized view",
                    expectedRetry[key] ?: 0,
                    dotRetry.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.collectAsState(` count on the " +
                        "normalized view — a collect spelled " +
                        "`collectAsState/* gap */(` is a " +
                        "collect here, as Kotlin reads it",
                    expectedCollect[key] ?: 0,
                    dotCollect.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`collectAsStateWithLifecycle` count " +
                        "on the normalized view",
                    expectedLifecycle[key] ?: 0,
                    occurrences(text, "collectAsStateWithLifecycle"),
                )
            }
        }
    }

    // -- F2b: navigation invocations on the normalized view --------------

    @Test
    fun navigationInvocationsAreCensusedOnACommentNormalizedView() {
        val expectedInvocations: Map<String, Pair<Int, Int>> = mapOf(
            "dev/cyberdogs/offleash/ui/components/Common.kt" to (1 to 1),
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to (1 to 1),
        )
        val albumCall = Regex("""\.onAlbumClick\s*\(""")
        val artistCall = Regex("""\.onArtistClick\s*\(""")
        val crossedCalls = listOf(
            Regex("""\.onAlbumClick\s*\(\s*artistId\s*\)"""),
            Regex("""\.onArtistClick\s*\(\s*albumId\s*\)"""),
        )
        for ((path, raw) in moduleSources()) {
            val text = stripComments(raw)
            val expected = expectedInvocations[path] ?: (0 to 0)
            assertEquals(
                "$path must invoke `.onAlbumClick(` exactly " +
                    "its shipped number of times on the " +
                    "normalized view — round 51's census is a " +
                    "substring count, and a comment inside " +
                    "the needle (`.onAlbumClick/* gap */(`) " +
                    "breaks the substring without breaking " +
                    "the call: this round's added crossed " +
                    "invocation in TrackMenu's Artist item " +
                    "ran green on raw text. (The count here " +
                    "allows whitespace before the paren: on " +
                    "the normalized view the interposed " +
                    "comment IS a space, and Kotlin calls " +
                    "permit it — a needle without `\\s*` " +
                    "would re-open the gap one space later.)",
                expected.first,
                albumCall.findAll(text).count(),
            )
            assertEquals(
                "$path must invoke `.onArtistClick(` exactly " +
                    "its shipped number of times on the " +
                    "normalized view",
                expected.second,
                artistCall.findAll(text).count(),
            )
            for (crossed in crossedCalls) {
                assertEquals(
                    "$path must never cross its track " +
                        "navigation fields (`$crossed`) — on " +
                        "the normalized view the crossed " +
                        "pairing is visible whatever comment " +
                        "the raw spelling interposes",
                    0,
                    crossed.findAll(text).count(),
                )
            }
        }
    }

    // -- F1b: the flow name lives in no comment or string -----------------

    @Test
    fun flowClassNameOccursInNoCommentOrStringLiteral() {
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val (inComments, inStrings) =
                    commentAndStringMentions(text, "MutableStateFlow")
                assertEquals(
                    "$label:$path must mention MutableStateFlow " +
                        "in NO comment — round 62's raw census " +
                        "counts name occurrences per file, so " +
                        "a comment mention is slack a later " +
                        "doctor could trade against a real " +
                        "birth at net zero; the shipped tree " +
                        "has none (every one of the 39 namings " +
                        "is a construction), and this pin keeps " +
                        "it that way by naming the offence " +
                        "instead of letting it surface as a " +
                        "count drift",
                    0,
                    inComments,
                )
                assertEquals(
                    "$label:$path must mention MutableStateFlow " +
                        "in NO string literal either — a string " +
                        "mention is the same masking slack " +
                        "under another lexical cover",
                    0,
                    inStrings,
                )
            }
        }
    }

    // -- F3: getter-computed properties of every type ----------------------

    @Test
    fun getterComputedPropertiesOfEveryTypeAreCensusedAndBoundVerbatim() {
        val files = moduleSources()
        val expectedGetters: Map<String, Map<String, String>> = mapOf(
            "dev/cyberdogs/offleash/data/AppPreferences.kt" to mapOf(
                "streamQuality" to "QualityPreference",
                "audioVersionInRadio" to "Boolean",
                "downloadQuality" to "DownloadQuality",
                "autoDownloadLiked" to "Boolean",
                "playbackSpeed" to "Float",
                "keepPitchAtSpeed" to "Boolean",
            ),
            "dev/cyberdogs/offleash/data/ParityPreferences.kt" to mapOf(
                "downloadQuality" to "DownloadQualityPreference",
                "autoDownloadLiked" to "Boolean",
                "audioVersionInRadio" to "Boolean",
            ),
            "dev/cyberdogs/offleash/data/Downloads.kt" to mapOf(
                "videoId" to "String",
            ),
            "dev/cyberdogs/offleash/playback/SleepTimer.kt" to mapOf(
                "isArmed" to "Boolean",
            ),
        )
        val getterRegex = Regex(
            """(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*""" +
                """([A-Za-z0-9_.<>?,]+)\s+get\s*\(\s*\)""",
        )
        for ((path, text) in files) {
            val found = getterRegex.findAll(text)
                .associate { it.groupValues[1] to it.groupValues[2] }
            assertEquals(
                "$path must hold exactly its shipped getter-" +
                    "computed properties, of EVERY type — " +
                    "round 62 censused getter-computed FLOW " +
                    "properties only, and this round's " +
                    "conversion of SearchViewModel's " +
                    "continuationTracker (an initializer-form " +
                    "paging tracker) into a typed getter " +
                    "returning a fresh tracker per read ran " +
                    "green across the full suite: a getter " +
                    "has no initializer for any declaration " +
                    "census to count, whatever it computes. " +
                    "The shipped inventory is eleven " +
                    "properties in four files; any conversion, " +
                    "addition, removal or re-typing moves " +
                    "this map",
                expectedGetters[path] ?: emptyMap<String, String>(),
                found,
            )
        }
        val verbatimBodies: Map<String, List<String>> = mapOf(
            "dev/cyberdogs/offleash/data/AppPreferences.kt" to listOf(
                "    val streamQuality: QualityPreference\n" +
                    "        get() {\n" +
                    "            val fresh = readStreamQuality()\n" +
                    "            if (_streamQualityFlow.value != fresh) _streamQualityFlow.value = fresh\n" +
                    "            return fresh\n" +
                    "        }",
                "    val audioVersionInRadio: Boolean\n" +
                    "        get() {\n" +
                    "            val fresh = readAudioVersionInRadio()\n" +
                    "            if (_audioVersionInRadioFlow.value != fresh) _audioVersionInRadioFlow.value = fresh\n" +
                    "            return fresh\n" +
                    "        }",
                "    val downloadQuality: DownloadQuality\n" +
                    "        get() {\n" +
                    "            val fresh = readDownloadQuality()\n" +
                    "            if (_downloadQualityFlow.value != fresh) _downloadQualityFlow.value = fresh\n" +
                    "            return fresh\n" +
                    "        }",
                "    val autoDownloadLiked: Boolean\n" +
                    "        get() {\n" +
                    "            val fresh = readBoolean(KEY_AUTO_DOWNLOAD_LIKED, DEFAULT_AUTO_DOWNLOAD_LIKED)\n" +
                    "            if (_autoDownloadLikedFlow.value != fresh) _autoDownloadLikedFlow.value = fresh\n" +
                    "            return fresh\n" +
                    "        }",
                "    val playbackSpeed: Float\n" +
                    "        get() {\n" +
                    "            // Total like every other getter in this class (bug\n" +
                    "            // sweep round 2): this was the one read that let a\n" +
                    "            // throwing store — SharedPreferences.getString throws\n" +
                    "            // ClassCastException when a key holds a non-string —\n" +
                    "            // escape as a crash instead of reading as the default.\n" +
                    "            val raw = try {\n" +
                    "                store.getString(KEY_PLAYBACK_SPEED)\n" +
                    "            } catch (e: Exception) {\n" +
                    "                null\n" +
                    "            }\n" +
                    "            val parsed = raw?.toFloatOrNull()\n" +
                    "            return when {\n" +
                    "                parsed == null || parsed.isNaN() || parsed <= 0f ->\n" +
                    "                    DEFAULT_PLAYBACK_SPEED\n" +
                    "                else -> parsed.coerceIn(0.5f, 2.0f)\n" +
                    "            }\n" +
                    "        }",
                "    val keepPitchAtSpeed: Boolean\n" +
                    "        get() = readBoolean(KEY_KEEP_PITCH_AT_SPEED, DEFAULT_KEEP_PITCH_AT_SPEED)",
            ),
            "dev/cyberdogs/offleash/data/ParityPreferences.kt" to listOf(
                "    val downloadQuality: DownloadQualityPreference\n" +
                    "        get() {\n" +
                    "            val fresh = readDownloadQuality()\n" +
                    "            if (_downloadQualityFlow.value != fresh) _downloadQualityFlow.value = fresh\n" +
                    "            return fresh\n" +
                    "        }",
                "    val autoDownloadLiked: Boolean\n" +
                    "        get() {\n" +
                    "            val fresh = readBoolean(KEY_AUTO_DOWNLOAD_LIKED, DEFAULT_AUTO_DOWNLOAD_LIKED)\n" +
                    "            if (_autoDownloadLikedFlow.value != fresh) _autoDownloadLikedFlow.value = fresh\n" +
                    "            return fresh\n" +
                    "        }",
                "    val audioVersionInRadio: Boolean\n" +
                    "        get() {\n" +
                    "            val fresh = readBoolean(KEY_AUDIO_VERSION_IN_RADIO, DEFAULT_AUDIO_VERSION_IN_RADIO)\n" +
                    "            if (_audioVersionInRadioFlow.value != fresh) _audioVersionInRadioFlow.value = fresh\n" +
                    "            return fresh\n" +
                    "        }",
            ),
            "dev/cyberdogs/offleash/data/Downloads.kt" to listOf(
                "    val videoId: String get() = track.videoId",
            ),
            "dev/cyberdogs/offleash/playback/SleepTimer.kt" to listOf(
                "    val isArmed: Boolean get() = deadlineMs != null || endOfTrack",
            ),
        )
        for ((path, blocks) in verbatimBodies) {
            val text = files[path]
            assertTrue("getter body source $path must be walked", text != null)
            for (block in blocks) {
                assertEquals(
                    "$path must hold this shipped getter body " +
                        "verbatim — the inventory census above " +
                        "pins THAT a getter exists; this binds " +
                        "WHAT it computes (the preference " +
                        "getters' store-fresh read + flow " +
                        "refresh, playbackSpeed's total parse " +
                        "and 0.5f–2.0f clamp, isArmed's two " +
                        "arming legs), so narrowing a body is " +
                        "a census event even where no runtime " +
                        "test reaches the getter",
                    1,
                    occurrences(text!!, block),
                )
            }
        }
    }

    // -- the comment scanner ----------------------------------------------

    /**
     * The comment-normalized view: every comment (line or
     * block, nested as Kotlin nests them) replaced by a
     * single space, newlines inside comments preserved so
     * line-keyed legs see the same line structure; string,
     * raw-string and char literals pass through verbatim
     * (their contents are code-shaped text Kotlin does NOT
     * treat as whitespace-adjacent, and the censuses above
     * count them as shipped).
     */
    private fun stripComments(text: String): String =
        scanSource(text, "").stripped

    /** Occurrences of [needle] inside comments and inside
     *  string/char literals, counted per lexical segment. */
    private fun commentAndStringMentions(
        text: String,
        needle: String,
    ): Pair<Int, Int> {
        val scan = scanSource(text, needle)
        return scan.commentMentions to scan.stringMentions
    }

    private class ScanResult(
        val stripped: String,
        val commentMentions: Int,
        val stringMentions: Int,
    )

    private fun scanSource(text: String, needle: String): ScanResult {
        val out = StringBuilder()
        var commentMentions = 0
        var stringMentions = 0
        fun countIn(segment: CharSequence): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var from = 0
            while (true) {
                val hit = segment.indexOf(needle, from)
                if (hit < 0) break
                count++
                from = hit + needle.length
            }
            return count
        }
        var state = 0 // 0 code, 1 line comment, 2 block, 3 string, 4 raw string, 5 char
        var depth = 0
        var segment = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else ' '
            when (state) {
                0 -> when {
                    c == '/' && next == '/' -> {
                        state = 1; segment = StringBuilder()
                        out.append(' '); i += 2
                    }
                    c == '/' && next == '*' -> {
                        state = 2; depth = 1; segment = StringBuilder()
                        out.append(' '); i += 2
                    }
                    c == '"' && i + 2 < n &&
                        text[i + 1] == '"' && text[i + 2] == '"' -> {
                        state = 4; segment = StringBuilder()
                        out.append("\"\"\""); i += 3
                    }
                    c == '"' -> {
                        state = 3; segment = StringBuilder()
                        out.append(c); i++
                    }
                    c == '\'' -> {
                        state = 5; out.append(c); i++
                    }
                    else -> {
                        out.append(c); i++
                    }
                }
                1 -> if (c == '\n') {
                    commentMentions += countIn(segment)
                    state = 0; out.append(c); i++
                } else {
                    segment.append(c); i++
                }
                2 -> when {
                    c == '/' && next == '*' -> {
                        depth++; segment.append(c); i++
                    }
                    c == '*' && next == '/' -> {
                        depth--; i += 2
                        if (depth == 0) {
                            commentMentions += countIn(segment)
                            state = 0
                        }
                    }
                    else -> {
                        if (c == '\n') out.append('\n')
                        segment.append(c); i++
                    }
                }
                3 -> when {
                    c == '\\' && i + 1 < n -> {
                        segment.append(text[i]); segment.append(text[i + 1])
                        out.append(text[i]); out.append(text[i + 1]); i += 2
                    }
                    c == '"' -> {
                        stringMentions += countIn(segment)
                        state = 0; out.append(c); i++
                    }
                    else -> {
                        segment.append(c); out.append(c); i++
                    }
                }
                4 -> if (c == '"' && i + 2 < n &&
                    text[i + 1] == '"' && text[i + 2] == '"'
                ) {
                    stringMentions += countIn(segment)
                    state = 0; out.append("\"\"\""); i += 3
                } else {
                    segment.append(c); out.append(c); i++
                }
                else -> { // 5 char literal
                    when {
                        c == '\\' && i + 1 < n -> {
                            out.append(text[i]); out.append(text[i + 1]); i += 2
                        }
                        else -> {
                            out.append(c); i++
                            if (c == '\'') state = 0
                        }
                    }
                }
            }
        }
        if (state == 1 || state == 2) {
            commentMentions += countIn(segment)
        }
        if (state == 3 || state == 4) {
            stringMentions += countIn(segment)
        }
        return ScanResult(out.toString(), commentMentions, stringMentions)
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** The app module walk, shared with R49–R62's census
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

    /** Core production sources, located relative to the app root. */
    private fun coreSources(): Map<String, String> {
        val javaRoot = locateAppJavaRoot()
        val coreRoot = File(javaRoot, "../../../../core/src/main/kotlin")
            .canonicalFile
        assertTrue(
            "core sources must be locatable beside the app module",
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
}
