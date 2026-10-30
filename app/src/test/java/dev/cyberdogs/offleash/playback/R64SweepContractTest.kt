package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 64 sweep contracts (adversarial lane — breaking the
 * round-63 fixes):
 *
 *  (F1) Round 63's comment normalizer is a lexical scanner
 *  with no model of string TEMPLATES: inside `"${ … }"`,
 *  code resumes, but the scanner stays in its string state.
 *  A quote-bearing literal inside a template therefore
 *  desynchronizes it — this round's doctor line
 *  `val doctorDesync: String = "${ '"' }"` in
 *  SearchViewModel.search() (valid Kotlin: a char literal
 *  naming the double-quote character, inside a template
 *  expression) makes the scanner read the template's inner
 *  `"` as the string's end and the char literal's closing
 *  `'` as a char literal's start, so everything after it —
 *  including an added `_state.value /* gap */ = Error(…)`
 *  tail write — is "char literal content", preserved
 *  verbatim, comment and all: the normalized census never
 *  sees the write, and the doctor ran GREEN across the
 *  full 658-test suite. (The destructive twin — a nested
 *  string holding a comment opener inside a template —
 *  makes the scanner strip shipped
 *  code as a phantom comment and is caught by the count
 *  drop; plain markers inside strings, chars and raw
 *  strings, and nested block comments, are all handled by
 *  round 63's scanner and their doctors fail its first
 *  test by name.) The first test below re-runs round 63's
 *  write/birth census on a TEMPLATE-AWARE normalized view
 *  (a frame stack: `${` in a string or raw string pushes a
 *  code frame, its matching `}` pops back) with the SAME
 *  shipped tables, and pins the scanner's behaviour on
 *  synthetic samples so the desync shapes are named where
 *  they are closed.
 *
 *  (F2) Round 63's getter census runs on RAW text with one
 *  shape — `(?:val|var) name : Type get()` — so round 63's
 *  own comment-gap class defeats it, and two more shapes
 *  stand outside it: this round's conversions of
 *  SearchViewModel's continuationTracker into
 *  `private val continuationTracker: ContinuationTracker
 *  /* gap */ get() = ContinuationTracker()` and into the
 *  `get /* gap */ ()` spelling, and an ADDITIVE extension
 *  getter (`private val ContinuationTracker.doctorFresh
 *  get() = …`), each ran GREEN across the full suite (the
 *  plain untyped conversion still dies on round 62's
 *  untyped leg, and a gap-respelled SHIPPED getter still
 *  dies on round 63's verbatim body binds — the hole is
 *  conversions and additions, not the shipped eleven).
 *  The delegate spelling is closed from the other side:
 *  a fresh-per-read delegate must name
 *  `kotlin.reflect.KProperty`, and round 50's module-wide
 *  reflection zero-census kills that doctor by name. The
 *  third test re-runs the getter inventory on the
 *  normalized view and censuses extension properties and
 *  extension getters at their shipped zero.
 *
 *  (F3–F8) Round 63 normalized three census families
 *  (writes/births, the round-56 operator/collect list,
 *  album/artist navigation). The neighbouring families
 *  kept their raw-text legs, and one comment-gap doctor
 *  per family ran GREEN this round:
 *   - F3, round 55's collect family: PlaybackDiagnostics-
 *     Screen read the shipped snapshot through
 *     `snapshot.first /* gap */ ()` in a LaunchedEffect
 *     and rendered that instead of the collected lines
 *     (the shipped collect kept as decoy). Round 55's
 *     patterns are `\.first\(` with NO `\s*` — even a
 *     normalized re-run in round 55's own spelling would
 *     miss `.first ()`, so the fix's patterns carry the
 *     `\s*` Kotlin permits.
 *   - F4, round 57's operator remainder: a derived flow
 *     `_snapshot.scan /* gap */ (emptyList()) { … }` in
 *     PlaybackDiagnostics (compiles clean against the
 *     harness subset).
 *   - F5, round 55's gateway family: an added
 *     `_showPlayerRequests.runCatching /* gap */ { value
 *     /* gap */ += 1 }` in requestShowPlayer — both the
 *     gateway spelling and the bare-compound payload
 *     gapped at once.
 *   - F6, navigation remainder: round 63 normalized the
 *     album/artist invocations only; an added crossed
 *     `actions.onPlaylistClick/* gap */(item.artist
 *     .browseId, null)` in SectionRow's artist branch
 *     (rendering an artist card also fires playlist
 *     navigation with the artist id) stood outside every
 *     census. (This round's first placement of that
 *     doctor, in TrackMenu, was withdrawn: TrackActions
 *     carries no onPlaylistClick field, so the line could
 *     not compile; FeedActions in Common.kt carries it.)
 *   - F7, round 44's section definition census: a second
 *     `fun SectionRow /* gap */ (…)` overload definition
 *     in Common.kt — the raw substring `fun SectionRow(`
 *     never fires.
 *   - F8, round 54's lambda-type census: an additive
 *     `(String) /* gap */ -> Boolean` probe declaration in
 *     Downloads.kt (compiles clean); the plain spelling
 *     fails round 54's test by name.
 *  The second test re-runs every one of those families on
 *  the template-aware normalized view with the shipped
 *  tables (round 57's zero + counted legs, round 55's
 *  terminal reads and gateway legs with `\s*`-tolerant
 *  patterns, round 54's bare-assign and lambda legs,
 *  round 52's navigation-lambda counts, the playlist
 *  invocation counts and crossed bans, and the SectionRow
 *  definition count). Families that needed no re-run are
 *  named in the report with their killing tests (round
 *  50/62's declaration legs and round 63's first test for
 *  births; round 47/48's publish slices, backstopped by
 *  the normalized write census).
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R64SweepContractTest {

    // -- F1: the write/birth census on a template-aware view -------------

    @Test
    fun scannerHandlesStringTemplatesByConstruction() {
        val write = Regex("""(_[A-Za-z0-9]+)\.value\s*(?:=(?!=)|\+=|-=)""")
        val templateCharQuote =
            "val s: String = \"\${ '\"' }\"\n" +
                "_state.value /* gap */ = X\n"
        assertEquals(
            "a char literal naming a quote, inside a string " +
                "template, must not desynchronize the " +
                "normalizer: the template expression is code, " +
                "the gap comment after it is a comment, and " +
                "the write must count — round 63's scanner " +
                "read the template's inner quote as the " +
                "string's end and preserved the write " +
                "verbatim as phantom char-literal content, " +
                "and this round's doctor hid a tail write " +
                "behind exactly that line, green suite-wide",
            1,
            write.findAll(stripComments(templateCharQuote)).count(),
        )
        val templateStringMarker =
            "val s: String = \"\${ \"/*\" }\"\n" +
                "_state.value /* gap */ = X\n"
        assertEquals(
            "a comment marker inside a string nested in a " +
                "template is string content, not a comment: " +
                "the code after the template is code, and " +
                "the gap write after it must count",
            1,
            write.findAll(stripComments(templateStringMarker)).count(),
        )
        val plainMarker =
            "val s: String = \"/*\"\n" +
                "_state.value /* gap */ = X\n"
        val strippedPlain = stripComments(plainMarker)
        assertEquals(
            "the plain shapes round 63 already handled stay " +
                "handled on the template-aware view",
            1,
            write.findAll(strippedPlain).count(),
        )
        assertTrue(
            "string contents pass through verbatim (only " +
                "comments are normalized)",
            strippedPlain.contains("\"/*\""),
        )
    }

    @Test
    fun stateWritesAndFlowBirthsAreCensusedOnATemplateAwareNormalizedView() {
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
                    "writes on the TEMPLATE-AWARE normalized " +
                    "view (round 63's tables, re-based on a " +
                    "scanner that models string templates)",
                expectedWrites[path] ?: emptyMap<String, Int>(),
                writes,
            )
            val total = anyReceiverWrite.findAll(text).count()
            grandTotal += total
            assertEquals(
                "$path must hold exactly its shipped " +
                    "receiver-blind write total on the " +
                    "template-aware view",
                expectedTotals[path] ?: 0,
                total,
            )
            val declarations = declarationRegex.findAll(
                text.lines().filterNot { it.startsWith("import ") }
                    .joinToString("\n"),
            ).count()
            assertEquals(
                "$path must declare exactly its shipped " +
                    "MutableStateFlow(s) on the template-aware view",
                expectedDeclarations[path] ?: 0,
                declarations,
            )
            val rawNames = text.lines()
                .filterNot { it.trimStart().startsWith("import ") }
                .sumOf { line -> line.split("MutableStateFlow").size - 1 }
            grandBirths += rawNames
            assertEquals(
                "$path must name MutableStateFlow exactly its " +
                    "shipped number of times on the " +
                    "template-aware view",
                expectedDeclarations[path] ?: 0,
                rawNames,
            )
        }
        assertEquals(110, grandTotal)
        assertEquals(39, grandBirths)
    }

    // -- F3–F8: the remainder families on the normalized view ------------

    @Test
    fun remainderCensusFamiliesAreCensusedOnANormalizedView() {
        val zeroOperatorRegexes = listOf(
            Regex("""(?<![\w.])merge\s*\("""),
            Regex("""\.merge\s*\("""),
            Regex("""\.asFlow\s*\("""),
            Regex("""\.scan\s*\("""),
            Regex("""\.runningFold\s*\("""),
            Regex("""\.runningReduce\s*\("""),
            Regex("""\.filterNotNull\s*\("""),
            Regex("""\.filterIsInstance\s*[({]"""),
            Regex("""\.distinctUntilChangedBy\s*[({]"""),
            Regex("""\.takeWhile\s*[({]"""),
            Regex("""\.transformWhile\s*[({]"""),
            Regex("""\.onEmpty\s*[({]"""),
            Regex("""\.conflate\s*\("""),
            Regex("""\.buffer\s*\("""),
            Regex("""\.flowOn\s*\("""),
            Regex("""\.sample\s*\("""),
            Regex("""\.cancellable\s*\("""),
            Regex("""\.startWith\s*\("""),
            Regex("""\.concatWith\s*\("""),
            Regex("""\.fold\s*\("""),
            Regex("""\.reduce\s*[({]"""),
        )
        val zeroOperatorNeedles = listOf(
            "combineTransform", "combineLatest",
            "flattenMerge", "flattenConcat", "emptyFlow",
        )
        val dotMapNotNull = Regex("""\.mapNotNull\s*[({]""")
        val dotFilterNot = Regex("""\.filterNot\s*[({]""")
        val dotDropWhile = Regex("""\.dropWhile\s*[({]""")
        val dotWithIndex = Regex("""\.withIndex\s*\(""")
        val expectedMapNotNull: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 2,
            "app:dev/cyberdogs/offleash/data/Stores.kt" to 3,
            "core:dev/cyberdogs/offleash/core/auth/AuthSession.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/JsonNav.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/Parsers.kt" to 3,
        )
        val expectedFilterNot: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 1,
            "app:dev/cyberdogs/offleash/data/Stores.kt" to 3,
            "app:dev/cyberdogs/offleash/ui/components/Common.kt" to 1,
        )
        val expectedDropWhile: Map<String, Int> = mapOf(
            "core:dev/cyberdogs/offleash/core/api/YoutubeiClient.kt" to 1,
        )
        val expectedWithIndex: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 1,
            "app:dev/cyberdogs/offleash/playback/LyricsSync.kt" to 1,
            "core:dev/cyberdogs/offleash/core/api/YoutubeiClient.kt" to 1,
        )
        val dotFirst = Regex("""\.first\s*\(""")
        val dotSingle = Regex("""\.single\s*\(""")
        val dotSingleOrNull = Regex("""\.singleOrNull\s*\(""")
        val expectedFirst: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 1,
            "app:dev/cyberdogs/offleash/data/SessionManager.kt" to 4,
            "app:dev/cyberdogs/offleash/playback/QueueController.kt" to 1,
            "core:dev/cyberdogs/offleash/core/auth/SessionCookies.kt" to 2,
            "core:dev/cyberdogs/offleash/core/playback/StreamResolver.kt" to 4,
        )
        val expectedSingleOrNull: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/SessionManager.kt" to 2,
        )
        val runCatchingBlock = Regex("""\.runCatching\s*\{""")
        val runBlock = Regex("""\.run\s*\{""")
        val withCall = Regex("""\bwith\s*\(""")
        val bareCompound = Regex(
            """(?<![\w.])value\s*(?:\+=|-=|\*=|/=|%=)""",
        )
        val expectedBareCompound: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/Downloads.kt" to 1,
        )
        val bareValueAssign = Regex("""(?<![\w.])value\s*=(?!=)""")
        val expectedBareValue: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/Downloads.kt" to 1,
            "app:dev/cyberdogs/offleash/data/OkHttpTransport.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 1,
            "core:dev/cyberdogs/offleash/core/auth/SessionCookies.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/JsonNav.kt" to 1,
        )
        val lambdaName = """(?:[A-Za-z_][A-Za-z0-9_]*:\s*)?"""
        val lambdaZeroRegexes = listOf(
            Regex("""\(""" + lambdaName + """Track\??\)\s*->\s*Boolean"""),
            Regex("""\(""" + lambdaName + """String\??\)\s*->\s*Boolean"""),
            Regex("""\(""" + lambdaName + """Pair<String,\s*String\??>\)\s*->\s*Unit"""),
            Regex("""\(""" + lambdaName + """Pair<String,\s*String\??>\)\s*->\s*Boolean"""),
            Regex("""Pair<[^>]*>\)\s*->\s*(?:Unit|Boolean)"""),
            Regex("""suspend\s*\(""" + lambdaName + """(?:String|Track)\??\)\s*->\s*Boolean"""),
        )
        val navLambdaType = Regex("""\((?:String|String, String\?)\)\s*->\s*Unit""")
        val expectedNavLambda: Map<String, Int> = mapOf(
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
        val playlistCall = Regex("""onPlaylistClick\s*\(""")
        val expectedPlaylist: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/ui/components/Common.kt" to 1,
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 1,
        )
        val playlistCrossed = listOf(
            Regex("""onPlaylistClick\s*\(\s*artistId\b"""),
            Regex("""onPlaylistClick\s*\(\s*item\.artist\.browseId\b"""),
            Regex("""onPlaylistClick\s*\(\s*albumId\b"""),
        )
        val sectionRowDef = Regex("""fun\s+SectionRow\s*\(""")
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, raw) in sources) {
                val key = "$label:$path"
                val text = stripComments(raw)
                for (regex in zeroOperatorRegexes) {
                    assertEquals(
                        "$key must hold no remainder-operator " +
                            "spelling matching `$regex` on the " +
                            "normalized view — round 57's census " +
                            "is raw-text, and this round's " +
                            "derived flow `_snapshot.scan " +
                            "/* gap */ (…)` in PlaybackDiagnostics " +
                            "ran green across the full suite",
                        0,
                        regex.findAll(text).count(),
                    )
                }
                for (needle in zeroOperatorNeedles) {
                    assertEquals(
                        "$key must name no `$needle` on the " +
                            "normalized view (round 57's needles)",
                        0,
                        occurrences(text, needle),
                    )
                }
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.mapNotNull` count on the normalized view",
                    expectedMapNotNull[key] ?: 0,
                    dotMapNotNull.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.filterNot` count on the normalized view",
                    expectedFilterNot[key] ?: 0,
                    dotFilterNot.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.dropWhile` count on the normalized view",
                    expectedDropWhile[key] ?: 0,
                    dotDropWhile.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.withIndex` count on the normalized view",
                    expectedWithIndex[key] ?: 0,
                    dotWithIndex.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.first(` count on the normalized view " +
                        "— round 55's pattern is `\\.first\\(` " +
                        "with no `\\s*`, so it misses even the " +
                        "normalized `.first ()`; this pattern " +
                        "carries the whitespace Kotlin permits, " +
                        "and this round's LaunchedEffect doctor " +
                        "`snapshot.first /* gap */ ()` rendered " +
                        "in place of the shipped collect, green " +
                        "suite-wide, on raw text",
                    expectedFirst[key] ?: 0,
                    dotFirst.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no `.single(` on the normalized view",
                    0,
                    dotSingle.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.singleOrNull(` count on the normalized view",
                    expectedSingleOrNull[key] ?: 0,
                    dotSingleOrNull.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no `.runCatching {` on the " +
                        "normalized view — round 55's gateway " +
                        "leg is raw-text, and this round's " +
                        "`_showPlayerRequests.runCatching " +
                        "/* gap */ { value /* gap */ += 1 }` " +
                        "ran green suite-wide",
                    0,
                    runCatchingBlock.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no `.run {` gateway on the normalized view",
                    0,
                    runBlock.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no `with(` gateway on the normalized view",
                    0,
                    withCall.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped bare " +
                        "compound `value` assignment count on " +
                        "the normalized view — round 55's " +
                        "pattern allows only `[ \\t]*` before " +
                        "the operator, so a comment in that " +
                        "gap is invisible to it raw and plain " +
                        "here, where the comment is a space " +
                        "and `\\s*` spans it",
                    expectedBareCompound[key] ?: 0,
                    bareCompound.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped bare " +
                        "`value =` assignment count on the " +
                        "normalized view (round 54's leg, its " +
                        "`[ \\t]*` widened to `\\s*`)",
                    expectedBareValue[key] ?: 0,
                    bareValueAssign.findAll(text).count(),
                )
                for (regex in lambdaZeroRegexes) {
                    assertEquals(
                        "$key must declare no lambda type " +
                            "matching `$regex` on the normalized " +
                            "view — round 54's census is " +
                            "raw-text, and this round's " +
                            "`(String) /* gap */ -> Boolean` " +
                            "probe in Downloads.kt compiled " +
                            "clean and ran green suite-wide; " +
                            "the plain spelling fails round " +
                            "54's test by name",
                        0,
                        regex.findAll(text).count(),
                    )
                }
                if (label == "app") {
                    assertEquals(
                        "$key must declare exactly its shipped " +
                            "navigation-lambda-typed parameters " +
                            "on the normalized view (round 52's " +
                            "counts, comment-proofed)",
                        expectedNavLambda[path] ?: 0,
                        navLambdaType.findAll(text).count(),
                    )
                    assertEquals(
                        "$key must invoke `onPlaylistClick(` " +
                            "exactly its shipped number of times " +
                            "on the normalized view — round 63 " +
                            "normalized the album/artist " +
                            "invocations only, and this round's " +
                            "crossed `actions.onPlaylistClick" +
                            "/* gap */(item.artist.browseId, " +
                            "null)` in SectionRow's artist " +
                            "branch ran green suite-wide",
                        expectedPlaylist[path] ?: 0,
                        playlistCall.findAll(text).count(),
                    )
                    for (crossed in playlistCrossed) {
                        assertEquals(
                            "$key must never cross the playlist " +
                                "destination with an artist/album " +
                                "id (`$crossed`) on the normalized view",
                            0,
                            crossed.findAll(text).count(),
                        )
                    }
                    assertEquals(
                        "$key must define SectionRow exactly " +
                            "its shipped number of times on the " +
                            "normalized view — round 44's census " +
                            "is the raw substring `fun " +
                            "SectionRow(`, and this round's " +
                            "second definition spelled `fun " +
                            "SectionRow /* gap */ (…)` in " +
                            "Common.kt ran green suite-wide",
                        if (path.endsWith("ui/components/Common.kt")) 1 else 0,
                        sectionRowDef.findAll(text).count(),
                    )
                }
            }
        }
    }

    // -- F2: getters on the normalized view + extension zero -------------

    @Test
    fun getterCensusRunsOnTheNormalizedViewAndExtensionPropertiesAreZero() {
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
        val extensionProperty = Regex(
            """(?:val|var)\s+[A-Za-z_][A-Za-z0-9_]*""" +
                """(?:\.[A-Za-z_][A-Za-z0-9_]*)+\s*:""",
        )
        val extensionGetter = Regex(
            """(?:val|var)\s+[A-Za-z_][A-Za-z0-9_]*""" +
                """(?:\.[A-Za-z_][A-Za-z0-9_]*)+""" +
                """\s*(?::\s*[A-Za-z0-9_.<>?,]+)?\s+get\s*\(\s*\)""",
        )
        for ((path, raw) in moduleSources()) {
            val text = stripComments(raw)
            val found = getterRegex.findAll(text)
                .associate { it.groupValues[1] to it.groupValues[2] }
            assertEquals(
                "$path must hold exactly its shipped getter-" +
                    "computed properties on the NORMALIZED view " +
                    "— round 63's inventory runs on raw text, " +
                    "so a comment between the type and `get()` " +
                    "(or inside `get()`'s parens) defeats its " +
                    "`\\s+` the same way round 62's gap " +
                    "defeated the write legs: this round's " +
                    "typed-gap and paren-gap conversions of " +
                    "the continuationTracker each ran green " +
                    "suite-wide. On the normalized view the " +
                    "interposed comment is a space and both " +
                    "spellings are plain",
                expectedGetters[path] ?: emptyMap<String, String>(),
                found,
            )
            assertEquals(
                "$path must declare no extension property — " +
                    "an extension getter's receiver sits where " +
                    "the inventory regex expects the property " +
                    "name, so the shape is invisible to it at " +
                    "any normalization: this round's additive " +
                    "extension getter on ContinuationTracker " +
                    "ran green suite-wide, and the shipped " +
                    "module declares no extension property " +
                    "of any kind",
                0,
                extensionProperty.findAll(text).count(),
            )
            assertEquals(
                "$path must declare no extension getter (the " +
                    "getter-spelled half of the same zero)",
                0,
                extensionGetter.findAll(text).count(),
            )
        }
    }

    // -- the template-aware comment scanner -------------------------------

    /**
     * The template-aware normalized view: every comment
     * (line or block, nested as Kotlin nests them) replaced
     * by a single space, newlines inside comments preserved;
     * string, raw-string and char literals pass through
     * verbatim — EXCEPT that a `${` inside a string or raw
     * string pushes a code frame (its braces tracked, its
     * own comments normalized, its nested literals lexed),
     * and the matching `}` pops back to the literal. Round
     * 63's scanner had no template frames, so a
     * quote-bearing literal inside a template expression
     * desynchronized it (see the class doc).
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder()
        // Frame kinds: 0 code (template = brace depth, -1 =
        // not a template frame), 1 string, 2 raw, 3 char.
        class Frame(val kind: Int, var templateDepth: Int = -1)
        val stack = mutableListOf(Frame(0))
        fun top() = stack[stack.size - 1]
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else ' '
            when (top().kind) {
                1 -> when {
                    c == '\\' && i + 1 < n -> {
                        out.append(text[i]); out.append(text[i + 1]); i += 2
                    }
                    c == '"' -> {
                        stack.removeAt(stack.size - 1)
                        out.append(c); i++
                    }
                    c == '$' && next == '{' -> {
                        stack.add(Frame(0, 0))
                        out.append(c); out.append(next); i += 2
                    }
                    else -> {
                        out.append(c); i++
                    }
                }
                2 -> when {
                    c == '"' && i + 2 < n &&
                        text[i + 1] == '"' && text[i + 2] == '"' -> {
                        stack.removeAt(stack.size - 1)
                        out.append("\"\"\""); i += 3
                    }
                    c == '$' && next == '{' -> {
                        stack.add(Frame(0, 0))
                        out.append(c); out.append(next); i += 2
                    }
                    else -> {
                        out.append(c); i++
                    }
                }
                3 -> when {
                    c == '\\' && i + 1 < n -> {
                        out.append(text[i]); out.append(text[i + 1]); i += 2
                    }
                    else -> {
                        out.append(c); i++
                        if (c == '\'') stack.removeAt(stack.size - 1)
                    }
                }
                else -> when {
                    c == '/' && next == '/' -> {
                        out.append(' '); i += 2
                        while (i < n && text[i] != '\n') i++
                    }
                    c == '/' && next == '*' -> {
                        out.append(' '); i += 2
                        var depth = 1
                        while (i < n && depth > 0) {
                            if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                                depth++; i += 2
                            } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                                depth--; i += 2
                            } else {
                                if (text[i] == '\n') out.append('\n')
                                i++
                            }
                        }
                    }
                    c == '"' && i + 2 < n &&
                        text[i + 1] == '"' && text[i + 2] == '"' -> {
                        stack.add(Frame(2))
                        out.append("\"\"\""); i += 3
                    }
                    c == '"' -> {
                        stack.add(Frame(1))
                        out.append(c); i++
                    }
                    c == '\'' -> {
                        stack.add(Frame(3))
                        out.append(c); i++
                    }
                    c == '{' && top().templateDepth >= 0 -> {
                        top().templateDepth++
                        out.append(c); i++
                    }
                    c == '}' && top().templateDepth >= 0 -> {
                        if (top().templateDepth == 0) {
                            stack.removeAt(stack.size - 1)
                        } else {
                            top().templateDepth--
                        }
                        out.append(c); i++
                    }
                    else -> {
                        out.append(c); i++
                    }
                }
            }
        }
        return out.toString()
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

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
