package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 54 sweep contracts (adversarial lane — breaking the
 * round 53 fixes):
 *
 *  (Charter 1 — NEW) Round 53's lambda census keys on the
 *  id-carrying family with `Unit` returns, and its own report
 *  recorded the residual: a VALUE-RETURNING navigation lambda
 *  fired for its side effect stands outside the table. This
 *  round a `(track: Track) -> Boolean` TrackActions field
 *  wired crossed in rememberTrackActions (album intent
 *  opening the artist from the track's own artistBrowseId,
 *  returning true) plus a `(ids: Pair<String, String>) ->
 *  Unit` field whose callee picks the pair's SECOND component
 *  (the artist id) for an album-intent item — both invoked
 *  from live Album menu items beside the shipped decoy —
 *  ran green across R49–R53. The fix extends the family
 *  census to the value-returning spellings of the
 *  id-carrying shapes and to the composite Pair carrier,
 *  each at its shipped zero, and counts the legitimate
 *  Boolean-returning family that does ship — the three
 *  parameterless `() -> Boolean` probes — per file as its
 *  own family so the census stays honest.
 *
 *  (Charter 2 — NEW) Round 53 banned the stdlib
 *  bare-receiver gateways (`with(` / `.run {` at zero,
 *  `.apply {` counted per file). This round a HOME-GROWN
 *  gateway — `inline fun <T> T.mutateState(block: T.() ->
 *  Unit)` — with `_state.mutateState { value = value.copy(
 *  …) }` at SearchViewModel's tail ran green across
 *  R49–R53: the write text is a bare `value =` inside a
 *  lambda whose receiver is the flow, and the call site
 *  shows only a custom name. (The charter's NEW-FILE form
 *  of the gateway dies on round 49's exact file-set census;
 *  the definition therefore stood inside the existing
 *  SearchScreen.kt — the file set is the only pin the
 *  new-file form breaks, which is stated in the report.)
 *  The fix censuses the gateway CONCEPT, not its name:
 *  receiver-lambda parameter spellings (`X.() -> …`) at
 *  zero module-wide (a gateway cannot be defined without
 *  one), the remaining stdlib bare-receiver gateways
 *  `.let {` / `.also {` per file at shipped counts (round
 *  53 counted `.apply {`; let/also were the uncounted
 *  siblings — inside either, `this` is the receiver just
 *  as in apply), and every bare `value =` assignment text
 *  per file at shipped counts (the seven shipped sites are
 *  `val`/`var` declarations and named arguments, enumerated
 *  below; a bare-receiver write through ANY gateway —
 *  home-grown, stdlib, or imported — is one more bare
 *  `value =` in exactly one file). The same write spelled
 *  through `.apply {` was separately proven to fail round
 *  53's per-file apply census this round.
 *
 *  (Charter 3 — NEW) Round 53 censused the Flow-BUILDER
 *  family at zero. This round the parallel stream was
 *  built with NO builder: PlaybackDiagnostics gained
 *  `val narrowNotes: Flow<String> = object : Flow<String>
 *  { … }` backed by its own lines list (values handed to
 *  the collector through the `collector::emit` method
 *  reference, whose text carries no `.emit(` for round
 *  51's needle), and PlaybackDiagnosticsScreen collected
 *  it through a local alias in a LaunchedEffect while
 *  keeping the shipped collectAsState as decoy — green
 *  across R49–R53, and the playback half compiles with
 *  zero errors. Round 53's collect-side discipline never
 *  engaged: round 50's multiset counts `viewModel.<flow>
 *  .collectAsState()` only, and round 51's raw-collect ban
 *  needs the literal `viewModel.` receiver. The fix
 *  censuses the SOURCE side: `object : Flow`
 *  implementations, plain `Flow<…>` type spellings (the
 *  shipped tree's flows are all StateFlow/SharedFlow —
 *  a plain Flow type occurs nowhere), and `FlowCollector`
 *  at zero module-wide; the `::emit` method-reference
 *  spelling at zero (it is the one emit spelling round
 *  51's `.emit(` needle cannot see); and screen-side raw
 *  collects (`.collect {`) per file at shipped counts —
 *  the three shipped sites are OffleashApp's own session
 *  collects, none in a screen.
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R54SweepContractTest {

    @Test
    fun valueReturningAndPairCarryingLambdaTypesAreCensusedAtShippedCounts() {
        val name = """(?:[A-Za-z_][A-Za-z0-9_]*:\s*)?"""
        val trackBool = Regex(
            """\(""" + name + """Track\??\)\s*->\s*Boolean""",
        )
        val stringBool = Regex(
            """\(""" + name + """String\??\)\s*->\s*Boolean""",
        )
        val pairUnit = Regex(
            """\(""" + name +
                """Pair<String,\s*String\??>\)\s*->\s*Unit""",
        )
        val pairBool = Regex(
            """\(""" + name +
                """Pair<String,\s*String\??>\)\s*->\s*Boolean""",
        )
        val pairAny = Regex(
            """Pair<[^>]*>\)\s*->\s*(?:Unit|Boolean)""",
        )
        val suspendBool = Regex(
            """suspend\s*\(""" + name +
                """(?:String|Track)\??\)\s*->\s*Boolean""",
        )
        val emptyBool = Regex("""\(\)\s*->\s*Boolean""")
        val expectedEmptyBool: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadManager.kt" to 1,
            "app:dev/cyberdogs/offleash/data/LikeController.kt" to 1,
            "app:dev/cyberdogs/offleash/playback/QueueController.kt" to 1,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val key = "$label:$path"
                assertEquals(
                    "$key must declare no value-returning " +
                        "id/track-carrying lambda type " +
                        "(`(Track) -> Boolean`, `(String) -> " +
                        "Boolean`, nullable or suspend " +
                        "spellings) — round 53's census keys " +
                        "on `-> Unit` returns, so a handler " +
                        "fired for its side effect (opens a " +
                        "destination, returns true) whose " +
                        "result is discarded at the call site " +
                        "stood outside the table; a crossed " +
                        "field of exactly this shape ran " +
                        "green across R49–R53 this round",
                    0,
                    trackBool.findAll(text).count() +
                        stringBool.findAll(text).count() +
                        suspendBool.findAll(text).count(),
                )
                assertEquals(
                    "$key must declare no Pair-carrying " +
                        "lambda type (`(Pair<String, String>) " +
                        "-> Unit` / `-> Boolean`, named " +
                        "parameters included, and any other " +
                        "Pair<…> parameter with a Unit/Boolean " +
                        "return) — a composite (albumId, " +
                        "artistId) carrier lets the callee " +
                        "pick the wrong component with no " +
                        "String joint for the older censuses " +
                        "to count; a crossed field of this " +
                        "shape ran green across R49–R53 this " +
                        "round, and no Pair-carrying lambda " +
                        "type ships anywhere in either module",
                    0,
                    pairUnit.findAll(text).count() +
                        pairBool.findAll(text).count() +
                        pairAny.findAll(text).count(),
                )
                assertEquals(
                    "$key must declare exactly its shipped " +
                        "number of `() -> Boolean` lambda " +
                        "types — the legitimate Boolean-" +
                        "returning family that does ship " +
                        "(parameterless liveness/session " +
                        "probes), counted as its own family " +
                        "so the value-returning census above " +
                        "stays honest",
                    expectedEmptyBool[key] ?: 0,
                    emptyBool.findAll(text).count(),
                )
            }
        }
    }

    @Test
    fun scopeGatewayDefinitionsAndBareValueWritesAreCensusedModuleWide() {
        val receiverLambdaDef = Regex(
            """[A-Za-z_][A-Za-z0-9_<>?,. ]*\.\(\)[ \t]*->""",
        )
        val bareValueAssign = Regex(
            """(?<![\w.])value[ \t]*=(?!=)""",
        )
        val letBlock = Regex("""\.let\s*\{""")
        val alsoBlock = Regex("""\.also\s*\{""")
        val expectedBareValue: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/Downloads.kt" to 1,
            "app:dev/cyberdogs/offleash/data/OkHttpTransport.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 1,
            "core:dev/cyberdogs/offleash/core/auth/SessionCookies.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/JsonNav.kt" to 1,
        )
        val expectedLet: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/account/AutoDownloadLikedWorker.kt" to 1,
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 2,
            "app:dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt" to 2,
            "app:dev/cyberdogs/offleash/playback/QueueController.kt" to 7,
            "app:dev/cyberdogs/offleash/ui/account/SignInScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 4,
            "app:dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 1,
            "core:dev/cyberdogs/offleash/core/api/YoutubeiClient.kt" to 10,
            "core:dev/cyberdogs/offleash/core/api/YoutubeiContexts.kt" to 5,
            "core:dev/cyberdogs/offleash/core/auth/AuthSession.kt" to 4,
            "core:dev/cyberdogs/offleash/core/auth/SessionCookies.kt" to 3,
            "core:dev/cyberdogs/offleash/core/error/OffleashException.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/JsonNav.kt" to 6,
            "core:dev/cyberdogs/offleash/core/parse/Parsers.kt" to 29,
            "core:dev/cyberdogs/offleash/core/playback/PlayQueue.kt" to 2,
            "core:dev/cyberdogs/offleash/core/playback/StreamResolver.kt" to 2,
        )
        val expectedAlso: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 1,
            "app:dev/cyberdogs/offleash/data/SecretBox.kt" to 1,
            "core:dev/cyberdogs/offleash/core/playback/PlayQueue.kt" to 1,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val key = "$label:$path"
                assertEquals(
                    "$key must declare no receiver-lambda " +
                        "parameter (`T.() -> …`, " +
                        "`MutableStateFlow<…>.() -> …`, any " +
                        "receiver type, any return) — a " +
                        "bare-receiver gateway cannot be " +
                        "DEFINED without one, so censusing " +
                        "the definition spelling closes the " +
                        "home-grown gateway class whatever " +
                        "the helper is named; a gateway " +
                        "defined in an existing file (the " +
                        "new-file form already dies on round " +
                        "49's file-set census) ran green " +
                        "across R49–R53 this round",
                    0,
                    receiverLambdaDef.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "bare `value =` count — the seven " +
                        "shipped sites module-wide are " +
                        "`val`/`var` declarations and named " +
                        "arguments; a bare-receiver StateFlow " +
                        "write inside ANY scope block (the " +
                        "home-grown gateway this round, a " +
                        "stdlib gateway, or a library-" +
                        "imported one) is a bare `value =` " +
                        "assignment in exactly one file and " +
                        "moves this count, whatever gateway " +
                        "carried it",
                    expectedBareValue[key] ?: 0,
                    bareValueAssign.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.let {` count — let is a bare-" +
                        "receiver gateway for its receiver's " +
                        "members through `this` in the same " +
                        "way apply is, and round 53 counted " +
                        "only apply; a flow-receiver let " +
                        "(inside which `value` writes are " +
                        "bare) is one more occurrence here",
                    expectedLet[key] ?: 0,
                    letBlock.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.also {` count — the last stdlib " +
                        "bare-receiver gateway; its shipped " +
                        "receivers are a byte array, a record " +
                        "and a file name, never a flow",
                    expectedAlso[key] ?: 0,
                    alsoBlock.findAll(text).count(),
                )
            }
        }
    }

    @Test
    fun objectExpressionFlowsAndPlainFlowTypesAreCensusedModuleWide() {
        val objectFlow = Regex(
            """object\s*:\s*(?:kotlinx\.coroutines\.flow\.)?Flow\b""",
        )
        val plainFlowType = Regex(
            """(?<![A-Za-z])(?:kotlinx\.coroutines\.flow\.)?Flow\s*<""",
        )
        val rawCollect = Regex("""\.collect\s*\{""")
        val expectedRawCollect: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/OffleashApp.kt" to 3,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val key = "$label:$path"
                assertEquals(
                    "$key must hold no `object : Flow` " +
                        "implementation — a hand-written Flow " +
                        "needs NO builder spelling (round 53 " +
                        "censused `flow {` and the builder " +
                        "family at zero), so an object-" +
                        "expression Flow over a plain list " +
                        "source, collected on a screen " +
                        "through a local alias, completed a " +
                        "parallel-stream chain green across " +
                        "R49–R53 this round (its data half " +
                        "also compiles with zero errors)",
                    0,
                    objectFlow.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no plain `Flow<…>` type " +
                        "spelling (declaration, parameter or " +
                        "return) — every shipped flow type in " +
                        "either module is StateFlow or " +
                        "SharedFlow; a plain Flow type is the " +
                        "declaration shape a hand-built or " +
                        "builder-free stream must take",
                    0,
                    plainFlowType.findAll(text).count(),
                )
                assertEquals(
                    "$key must name no `FlowCollector` — " +
                        "the collector type exists in a file " +
                        "only to implement or consume a Flow " +
                        "by hand; shipped uses are zero",
                    0,
                    occurrences(text, "FlowCollector"),
                )
                assertEquals(
                    "$key must hold no `::emit` method " +
                        "reference — the one emit spelling " +
                        "round 51's `.emit(` needle cannot " +
                        "see (`collector::emit` hands values " +
                        "to a collector with no dot-paren " +
                        "text); shipped uses are zero",
                    0,
                    occurrences(text, "::emit"),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "raw `.collect {` count — the three " +
                        "shipped sites are OffleashApp's own " +
                        "session collects; a screen-side raw " +
                        "collect of a non-ViewModel flow " +
                        "through a local alias (round 50's " +
                        "multiset counts only `viewModel." +
                        "<flow>.collectAsState()`, round 51's " +
                        "ban needs the literal `viewModel.` " +
                        "receiver) is one more occurrence " +
                        "in a screen file",
                    expectedRawCollect[key] ?: 0,
                    rawCollect.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no `.collect(` call — " +
                        "the parenthesised collect spelling; " +
                        "shipped uses are zero module-wide",
                    0,
                    occurrences(text, ".collect("),
                )
            }
        }
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** The app module walk, shared with R49–R53's census
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

    /** Core production sources, located relative to the app
     *  root exactly as R51–R53's censuses locate them. */
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
        assertTrue(
            "the core walk must find its anchor file PlayQueue.kt",
            out.containsKey(
                "dev/cyberdogs/offleash/core/playback/PlayQueue.kt",
            ),
        )
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
