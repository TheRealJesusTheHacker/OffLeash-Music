package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 53 sweep contracts (adversarial lane — breaking the
 * round 52 fixes):
 *
 *  (Charter 1 — NEW) The write-census family counts a
 *  StateFlow write by the operator text that FOLLOWS
 *  `.value` (`=`, `+=`, `-=`, and round 52's zero-censused
 *  postfix `++`/`--`). Three no-adjacent-operator spellings
 *  ran green across R50+R51+R52 this round, each as an
 *  ADDITIVE second write beside the shipped compound line
 *  in OffleashApp (the replacement forms are caught only
 *  because the shipped write vanishes from the count
 *  tables — the additive forms leave every table exact):
 *  the PREFIX increment `++_showPlayerRequests.value`
 *  (the operator precedes the property, so no census regex
 *  ever sees an operator after `.value`), and the
 *  bare-receiver writes inside a scope block,
 *  `with(_showPlayerRequests) { value++ }` and
 *  `with(_showPlayerRequests) { value = value + 1 }`
 *  (no `.value` text at all). The postfix forms themselves
 *  are CLOSED — proven this round: `.value++` additively
 *  fails exactly round 52's postfix-zero leg, and the
 *  replacement form fails all three legs. `_x.value.inc()`
 *  is a read, not a write, and StateFlow has no operator
 *  write besides assignment/inc-dec, so the family below
 *  is complete: prefix inc/dec on any `.value` at zero,
 *  bare `value++`/`value--`/`++value`/`--value` at zero,
 *  the bare-receiver gateways `with(` and `.run {` at
 *  zero, and `.apply {` per file at its shipped counts
 *  (its shipped receivers are builders and intents, never
 *  a flow; a flow receiver would be one more occurrence).
 *
 *  (Charter 2 — NEW) Round 52's navigation-lambda census
 *  keys on the two UNNAMED String spellings. The id can
 *  travel in other shapes, and one ran green across the
 *  full suite this round: a `(Track) -> Unit` lambda —
 *  TrackActions gained a track-carrying field wired in
 *  rememberTrackActions to the ARTIST lambda reading the
 *  track's own artistBrowseId (the callee decides from the
 *  Track's fields, so no String is needed at the joint),
 *  and a helper composable taking `go: (Track) -> Unit`
 *  rendered a second "Album" item through it beside the
 *  shipped decoy. No census moved: round 51 counts
 *  `.onArtistClick(` (the factory's crossing is a BARE
 *  parameter call, no dot), round 49 binds only the named
 *  String joints, and round 52's type regex matches
 *  neither `(Track) -> Unit` nor the NAMED-parameter
 *  spellings (`(browseId: String) -> Unit` — the shipped
 *  FeedActions/TrackActions declarations themselves use
 *  named parameters and were never in round 52's count).
 *  The fix censuses the whole id-carrying function-type
 *  family per file at shipped counts: String single
 *  (named or not) 34, String pair 9, Track single 8, and
 *  the nullable / receiver-lambda / suspend variants at
 *  zero.
 *
 *  (Charter 3 — NEW) Round 52 scoped its Channel ban to
 *  `ui/` files because GoogleSessionManager's data-layer
 *  signal Channel is legitimate. The chain therefore moved
 *  one layer down and ran green across the full suite
 *  (the data-layer half also compiles with zero errors):
 *  PlaybackDiagnostics (playback/) declared a conflated
 *  Channel, wrote it with trySend, exposed it as a plain
 *  Flow through the builder form
 *  `flow { for (note in ch) emit(note) }` — which uses
 *  NEITHER receiveAsFlow NOR stateIn (round 52's two
 *  module-wide legs) and whose bare `emit(` carries no
 *  dot for round 51's `.emit(` needle — and
 *  PlaybackDiagnosticsScreen collected it through a local
 *  alias in a LaunchedEffect while keeping the shipped
 *  collectAsState as decoy: round 50's collected-flow
 *  multiset counts `viewModel.<flow>.collectAsState()`
 *  expressions only, and round 51's raw-collect ban
 *  requires the literal `viewModel.` receiver. No pin
 *  censused Channel/trySend declarations outside `ui/`
 *  (round 50/51's declaration censuses are module-wide
 *  but name only the StateFlow/SharedFlow spellings).
 *  The fix censuses the family module-wide at shipped
 *  counts — `Channel` (GoogleSessionManager's signal is
 *  the counted legitimate site; AtomicFileWrite's
 *  FileChannel substring stands at 1), `trySend` (the
 *  session signal's one write), `.send(`, the `flow {`
 *  builder, and the remaining Flow-source spellings
 *  (flowOf / consumeAsFlow / callbackFlow / channelFlow /
 *  shareIn / produceIn) at their shipped zeros — so the
 *  chain dies at its first link without touching the
 *  legitimate session channel.
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R53SweepContractTest {

    @Test
    fun noEqualsAndBareReceiverWritesAreCensusedModuleWide() {
        val prefixValue =
            Regex("""(?:\+\+|--)[ \t]*\(?[ \t]*[A-Za-z_][A-Za-z0-9_]*\.value""")
        val barePostfixValue =
            Regex("""(?<![\w.])value[ \t]*(?:\+\+|--)""")
        val barePrefixValue =
            Regex("""(?:\+\+|--)[ \t]*\(?[ \t]*value(?![\w])""")
        val withCall = Regex("""\bwith\(""")
        val runBlock = Regex("""\.run\s*\{""")
        val applyBlock = Regex("""\.apply\s*\{""")
        val expectedApply: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/RangeInterceptor.kt" to 1,
            "app:dev/cyberdogs/offleash/data/SecretBox.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/SplashScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to 2,
            "core:dev/cyberdogs/offleash/core/playback/PlayQueue.kt" to 1,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val key = "$label:$path"
                assertEquals(
                    "$key must hold no prefix inc/dec write on " +
                        "a flow's `.value` (`++_x.value`) — the " +
                        "operator precedes the property, so " +
                        "every census keyed on operator text " +
                        "AFTER `.value` (plain, compound, and " +
                        "round 52's postfix zero) is blind to " +
                        "it; proven green additively this round",
                    0,
                    prefixValue.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no bare-receiver inc/dec " +
                        "on `value` (`value++` / `++value` " +
                        "inside a scope block carries no " +
                        "`.value` text at all); shipped uses " +
                        "are zero",
                    0,
                    barePostfixValue.findAll(text).count() +
                        barePrefixValue.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no `with(` call — `with` " +
                        "is the bare-receiver gateway: inside " +
                        "`with(_flow) { … }` every write to " +
                        "`value` is a StateFlow write with no " +
                        "censused text (proven green this " +
                        "round in both the `value++` and the " +
                        "`value = value + 1` forms); the " +
                        "shipped tree calls withContext, never " +
                        "with",
                    0,
                    withCall.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no `.run {` block — the " +
                        "other bare-receiver gateway, with the " +
                        "same invisible-write shape as `with(`; " +
                        "shipped uses are zero",
                    0,
                    runBlock.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.apply {` count — apply is the third " +
                        "bare-receiver gateway; its shipped " +
                        "receivers are request builders, a " +
                        "keystore and intents, never a flow, " +
                        "so a flow-receiver apply (inside " +
                        "which `value` writes are invisible) " +
                        "is one more occurrence here",
                    expectedApply[key] ?: 0,
                    applyBlock.findAll(text).count(),
                )
            }
        }
    }

    @Test
    fun idCarryingLambdaTypesAreCensusedPerFileAtShippedCounts() {
        val files = moduleSources()
        val name = """(?:[A-Za-z_][A-Za-z0-9_]*:\s*)?"""
        val stringSingle =
            Regex("""\(""" + name + """String\)\s*->\s*Unit""")
        val stringNullable =
            Regex("""\(""" + name + """String\?\)\s*->\s*Unit""")
        val stringPair = Regex(
            """\(""" + name + """String,\s*""" + name +
                """String\?\)\s*->\s*Unit""",
        )
        val trackSingle =
            Regex("""\(""" + name + """Track\)\s*->\s*Unit""")
        val trackNullable =
            Regex("""\(""" + name + """Track\?\)\s*->\s*Unit""")
        val receiverLambda =
            Regex("""(?:String|Track)\.\(\)\s*->\s*Unit""")
        val suspendCarrying = Regex(
            """suspend\s*\(""" + name +
                """(?:String|Track)\??(?:,\s*""" + name +
                """String\?)?\)\s*->\s*Unit""",
        )
        val expectedStringSingle: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/ui/OffleashRoot.kt" to 2,
            "dev/cyberdogs/offleash/ui/components/Common.kt" to 4,
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to 4,
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 6,
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to 2,
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 2,
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to 2,
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 6,
            "dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to 4,
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 2,
        )
        val expectedStringPair: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/ui/OffleashRoot.kt" to 1,
            "dev/cyberdogs/offleash/ui/components/Common.kt" to 2,
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 1,
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 1,
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to 1,
        )
        val expectedTrackSingle: Map<String, Int> = mapOf(
            "dev/cyberdogs/offleash/ui/components/Common.kt" to 4,
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to 4,
        )
        for ((path, text) in files) {
            assertEquals(
                "$path must declare exactly its shipped " +
                    "number of `(String) -> Unit` lambda " +
                    "types, NAMED parameters included — round " +
                    "52's regex matched only the unnamed " +
                    "spelling, so the shipped named form " +
                    "(`(browseId: String) -> Unit`, four " +
                    "declarations) stood outside its count " +
                    "and a helper parameter in the named " +
                    "spelling is invisible to it",
                expectedStringSingle[path] ?: 0,
                stringSingle.findAll(text).count(),
            )
            assertEquals(
                "$path must declare exactly its shipped " +
                    "number of `(String, String?) -> Unit` " +
                    "lambda types, named parameters included",
                expectedStringPair[path] ?: 0,
                stringPair.findAll(text).count(),
            )
            assertEquals(
                "$path must declare exactly its shipped " +
                    "number of `(Track) -> Unit` lambda " +
                    "types — a track-carrying lambda lets " +
                    "the callee pick album vs artist from " +
                    "the track's own browse ids, so the " +
                    "crossing needs no String joint at all: " +
                    "a TrackActions field of this shape wired " +
                    "to the artist lambda and a helper " +
                    "composable taking `go: (Track) -> Unit` " +
                    "ran green across the full suite this " +
                    "round. The shipped eight are the " +
                    "playback callbacks (play-next / queue / " +
                    "radio / download)",
                expectedTrackSingle[path] ?: 0,
                trackSingle.findAll(text).count(),
            )
            assertEquals(
                "$path must declare no nullable-id, " +
                    "nullable-track, receiver-lambda or " +
                    "suspend id/track-carrying lambda type " +
                    "(`(String?) -> Unit`, `(Track?) -> " +
                    "Unit`, `String.() -> Unit`, " +
                    "`Track.() -> Unit`, `suspend (String) " +
                    "-> Unit`, …) — every other shape that " +
                    "can carry a browse id or a whole track " +
                    "into a callee stands at its shipped zero",
                0,
                stringNullable.findAll(text).count() +
                    trackNullable.findAll(text).count() +
                    receiverLambda.findAll(text).count() +
                    suspendCarrying.findAll(text).count(),
            )
        }
    }

    @Test
    fun channelAndFlowBuilderFamilyIsCensusedModuleWide() {
        val flowBuilder = Regex("""(?<![A-Za-z])flow\s*\{""")
        val expectedChannel: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/AtomicFileWrite.kt" to 1,
            "app:dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to 3,
        )
        val expectedTrySend: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to 1,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val key = "$label:$path"
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`Channel` count module-wide — round " +
                        "52 banned the family in `ui/` files " +
                        "only, so a Channel declared in data/ " +
                        "or playback/ (where round 50/51's " +
                        "declaration censuses name only the " +
                        "StateFlow/SharedFlow spellings) began " +
                        "a parallel-state chain no pin could " +
                        "see (proven green this round). The " +
                        "counted legitimate sites are " +
                        "GoogleSessionManager's check-now " +
                        "signal (import + declaration + " +
                        "CONFLATED) and AtomicFileWrite's " +
                        "FileChannel substring",
                    expectedChannel[key] ?: 0,
                    occurrences(text, "Channel"),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`trySend` count — the Channel write " +
                        "spelling, censused before this round " +
                        "only inside `ui/` files; the session " +
                        "signal's one write is the counted site",
                    expectedTrySend[key] ?: 0,
                    occurrences(text, "trySend"),
                )
                assertEquals(
                    "$key must hold no `.send(` — the " +
                        "suspending Channel write; shipped " +
                        "uses are zero module-wide",
                    0,
                    occurrences(text, ".send("),
                )
                assertEquals(
                    "$key must hold no `flow {` builder — " +
                        "a hand-built Flow over a Channel " +
                        "(`flow { for (x in ch) emit(x) }`) " +
                        "exposes it to collectors using " +
                        "NEITHER receiveAsFlow NOR stateIn " +
                        "(round 52's module-wide legs), and " +
                        "its bare `emit(` carries no dot for " +
                        "round 51's `.emit(` needle; shipped " +
                        "uses are zero in both modules",
                    0,
                    flowBuilder.findAll(text).count(),
                )
                for (needle in listOf(
                    "flowOf(", "consumeAsFlow", "callbackFlow",
                    "channelFlow", "shareIn", "produceIn",
                )) {
                    assertEquals(
                        "$key must hold no `$needle` — the " +
                            "remaining Flow-source and " +
                            "Channel-to-Flow spellings, each " +
                            "another uncensused way to surface " +
                            "a Channel (or any source) as a " +
                            "collectable Flow; shipped uses " +
                            "are zero module-wide",
                        0,
                        occurrences(text, needle),
                    )
                }
            }
        }
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** The app module walk, shared with R49–R52's census
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
     *  root exactly as R51's census locates them. */
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
    @Test
    fun playlistNameDialogConfirmsOnImeDone() {
        val text = libraryScreenSource()
        assertEquals(
            "the playlist name field must declare the Done " +
                "IME action exactly once — without it the " +
                "keyboard shows a dead Done key on the app's " +
                "only dialog text input",
            1,
            occurrences(text, "keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)"),
        )
        assertEquals(
            "the field must bind exactly one keyboard-actions " +
                "block — a second binding is a second confirm path",
            1,
            occurrences(text, "keyboardActions = KeyboardActions("),
        )
        assertEquals(
            "IME Done must confirm through the dialog's one " +
                "onConfirm, under the same blank guard the " +
                "confirm button's enabled state and the callers " +
                "enforce — an unguarded or divergent call would " +
                "let the keyboard create the blank-named " +
                "playlist the button exists to prevent",
            1,
            occurrences(text, "onDone = { if (name.isNotBlank()) onConfirm(name) }"),
        )
        assertEquals(
            "the confirm button must keep its blank guard — " +
                "the IME path joins the button path, it does " +
                "not replace it",
            1,
            occurrences(text, "enabled = name.isNotBlank()"),
        )
    }

    private fun libraryScreenSource(): String {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            for (candidate in listOf(
                File(dir, "src/main/java"),
                File(dir, "app/src/main/java"),
            )) {
                val file = File(
                    candidate,
                    "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt",
                )
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
            hops++
        }
        throw AssertionError(
            "LibraryScreen.kt not found from " + File(".").absolutePath,
        )
    }
}
