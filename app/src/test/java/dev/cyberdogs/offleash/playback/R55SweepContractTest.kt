package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 55 sweep contracts (adversarial lane — residual
 * closure + breaking the round 54 fixes):
 *
 *  (Charter 1 — the round 54 residual, CLOSED BY A
 *  FINDING) Round 54 recorded one residual: a bare
 *  COMPOUND write (`value += 1`) through a library-imported
 *  gateway carries no `.value` text, matches neither round
 *  53's bare inc/dec forms nor round 54's plain-assignment
 *  regex. The shipped dependencies were enumerated for
 *  scope-function-like entry points taking a receiver
 *  lambda over an arbitrary T: kotlin-stdlib's are with /
 *  run / apply / let / also — all censused by rounds 53–54 —
 *  plus ONE more the censuses never named: the extension
 *  `runCatching`, whose block is a receiver lambda
 *  (`T.() -> R`). kotlinx-coroutines, Compose, media3,
 *  okhttp, coil, work and lifecycle expose no generic
 *  receiver-lambda over an arbitrary value type (their
 *  builder receivers are concrete types a flow can never
 *  be). Proven this round: `_showPlayerRequests
 *  .runCatching { value += 1 }` as an additive line in
 *  requestShowPlayer ran GREEN across R49–R54 (round 53's
 *  gateway needle is the literal `.run {`, which
 *  `.runCatching {` does not contain), and the shape
 *  compiles standalone against the shipped coroutines jar.
 *  The sibling forms all die on named pins, verified this
 *  round: `.apply { value += 1 }` fails round 53's per-file
 *  apply census; a bare write inside `.let {` does not
 *  compile at all (let's lambda has `it`, no implicit
 *  receiver — proven with the compiler, and the compilable
 *  `it.value += 1` spelling carries `.value` text the
 *  round 51/52 compound totals count); `getAndUpdate { }`
 *  fails round 50's literal occurrence ban, whose text was
 *  re-verified in the integrated tree; `.value =
 *  _showPlayerRequests.value.inc()` fails the round 50/51
 *  write totals. The fix censuses the two spellings the
 *  residual actually needs, module-wide in both modules:
 *  the extension gateway spelling `.runCatching {` at zero
 *  (the shipped top-level `runCatching {` in SecretBox,
 *  whose block has no receiver, is untouched by the dotted
 *  needle), and every bare compound assignment on `value`
 *  (`value +=` / `-=` / `*=` / `/=` / `%=`) per file at
 *  shipped counts — the one shipped site is a local Double
 *  in Downloads' byte formatter, so a bare-receiver
 *  compound write through ANY gateway, named or not, is
 *  one more occurrence in exactly one file.
 *
 *  (Charter 2 — NEW) Round 54 counted `.collect {` (3,
 *  OffleashApp) and banned `.collect(`. The wider Flow
 *  collection surface stood uncensused: proven this round,
 *  each green across R49–R54 on PlaybackDiagnosticsScreen
 *  collecting the SHIPPED diagnostics snapshot flow into a
 *  doctored render — (a) `snapshot.onEach { … }
 *  .launchIn(scope)`, (b) `snapshot.collectLatest { … }`
 *  inside a LaunchedEffect, and (c) the terminal one-shot
 *  read `snapshot.first()` inside a LaunchedEffect. The
 *  shipped census taken this round: collectLatest /
 *  collectIndexed / launchIn / onEach occur ZERO times in
 *  either module, as do stateIn / shareIn (round 52/53's
 *  app-side zeros re-verified module-wide). The fix
 *  censuses the collect family per file at shipped counts
 *  in both modules: the six spellings above at zero, the
 *  terminal-read spellings `.first(` and `firstOrNull` at
 *  their shipped per-file counts (every shipped site is a
 *  COLLECTION read — a Flow terminal read in any file is
 *  one more occurrence there), `.single(` at zero, and
 *  `.singleOrNull(` at its shipped count (SessionManager's
 *  two identity reads).
 *
 *  (Charter 3 — closure table, one link pinned here)
 *  Round 54 censused receiver-LAMBDA definitions at zero;
 *  an extension FUNCTION on the flow type is a different
 *  definition shape. Every placement was attempted this
 *  round: in an existing app file the body `value =
 *  block(value)` fails round 54's bare-`value =` census AND
 *  the receiver type text itself moves round 50's per-file
 *  MutableStateFlow declaration count; in a new app file it
 *  additionally fails round 49's file set; in a new or
 *  existing core file it fails round 54's core walk of the
 *  bare census; with the body re-spelled through the
 *  counted channel (`= update { v }`) it fails round 50's
 *  `.update` ban in an app file, and a `run { value = v }`
 *  body fails round 50's declaration count plus round 54's
 *  bare census (the dotted `.run {` gateway spelling is
 *  separately at zero under round 53). ONE link stood
 *  outside every contract: the
 *  update-channel extension in a CORE file — round 50's
 *  update ban walks the app module only. That link is dead
 *  in the real build, proven with the compiler: the core
 *  module's only dependency is kotlinx-serialization-json
 *  (core/build.gradle.kts), so `MutableStateFlow` and
 *  `update` are unresolved references on the core classpath
 *  — the gateway cannot exist in core at all. This round's
 *  fix pins that closure from both sides: extension-function
 *  definitions on any Flow type (`fun … MutableStateFlow<…>.…`,
 *  StateFlow / SharedFlow / MutableSharedFlow spellings
 *  included) at zero in both modules, the update/emit family
 *  mirrored into the core walk at its shipped zeros, and
 *  the dependency ground truth itself — core's build file
 *  names no coroutines artifact and no core production file
 *  names kotlinx.coroutines — so adding the dependency (the
 *  one move that could reopen the cross-module form) is
 *  itself a census event.
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R55SweepContractTest {

    @Test
    fun collectFamilySpellingsAreCensusedAtShippedCountsModuleWide() {
        val zeroSpellings = listOf(
            "collectLatest", "collectIndexed", "launchIn",
            "onEach", "stateIn", "shareIn",
        )
        val dotFirst = Regex("""\.first\(""")
        val dotSingle = Regex("""\.single\(""")
        val dotSingleOrNull = Regex("""\.singleOrNull\(""")
        val expectedFirst: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 1,
            "app:dev/cyberdogs/offleash/data/SessionManager.kt" to 4,
            "app:dev/cyberdogs/offleash/playback/QueueController.kt" to 1,
            "core:dev/cyberdogs/offleash/core/auth/SessionCookies.kt" to 2,
            "core:dev/cyberdogs/offleash/core/playback/StreamResolver.kt" to 4,
        )
        val expectedFirstOrNull: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 5,
            "app:dev/cyberdogs/offleash/data/SessionManager.kt" to 6,
            "app:dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to 2,
            "app:dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt" to 1,
            "app:dev/cyberdogs/offleash/playback/QueueController.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/JsonNav.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/Parsers.kt" to 3,
            "core:dev/cyberdogs/offleash/core/playback/PlayQueue.kt" to 2,
        )
        val expectedSingleOrNull: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/SessionManager.kt" to 2,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val key = "$label:$path"
                for (spelling in zeroSpellings) {
                    assertEquals(
                        "$key must name no `$spelling` — a " +
                            "collect-family spelling no earlier " +
                            "census counts (round 54 counted " +
                            "only `.collect {` / `.collect(`): " +
                            "a screen collecting a shipped flow " +
                            "through `onEach { … }.launchIn" +
                            "(scope)` or `collectLatest { … }` " +
                            "into a doctored render ran green " +
                            "across R49–R54 this round, and " +
                            "stateIn/shareIn conversions are " +
                            "the parallel-state source round " +
                            "52/53 banned app-side; shipped " +
                            "uses of all six are zero in both " +
                            "modules",
                        0,
                        occurrences(text, spelling),
                    )
                }
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.first(` count — every shipped site " +
                        "is a collection read; a terminal " +
                        "one-shot Flow read (`snapshot" +
                        ".first()` inside a LaunchedEffect, " +
                        "rendering a snapshot beside the " +
                        "shipped collectAsState decoy) is the " +
                        "same spelling on a flow receiver and " +
                        "ran green across R49–R54 this round, " +
                        "so it is one more occurrence here",
                    expectedFirst[key] ?: 0,
                    dotFirst.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`firstOrNull` count — the nullable " +
                        "twin of the terminal-read spelling " +
                        "above; every shipped site reads a " +
                        "collection or a list",
                    expectedFirstOrNull[key] ?: 0,
                    occurrences(text, "firstOrNull"),
                )
                assertEquals(
                    "$key must hold no `.single(` call — " +
                        "the strict terminal Flow read; " +
                        "shipped uses are zero module-wide",
                    0,
                    dotSingle.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.singleOrNull(` count — Session" +
                        "Manager's two identity reads are " +
                        "the only shipped sites; the nullable " +
                        "terminal Flow read is the same " +
                        "spelling on a flow receiver",
                    expectedSingleOrNull[key] ?: 0,
                    dotSingleOrNull.findAll(text).count(),
                )
            }
        }
    }

    @Test
    fun bareCompoundWritesAndFlowExtensionGatewaysAreCensusedModuleWide() {
        val bareCompound = Regex(
            """(?<![\w.])value[ \t]*(?:\+=|-=|\*=|/=|%=)""",
        )
        val runCatchingBlock = Regex("""\.runCatching\s*\{""")
        val flowExtensionDef = Regex(
            """fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?""" +
                """(?:MutableStateFlow|MutableSharedFlow|""" +
                """StateFlow|SharedFlow)\s*<""",
        )
        val expectedBareCompound: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/Downloads.kt" to 1,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val key = "$label:$path"
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "bare compound assignment count on " +
                        "`value` — the one shipped site is a " +
                        "local Double in Downloads' byte " +
                        "formatter; a bare-receiver compound " +
                        "StateFlow write (`value += 1` inside " +
                        "any scope block) carries no `.value` " +
                        "text, matches neither round 53's " +
                        "bare inc/dec forms nor round 54's " +
                        "plain-assignment regex, and was " +
                        "round 54's recorded residual — the " +
                        "write TEXT stands in exactly one " +
                        "file whatever gateway carried it",
                    expectedBareCompound[key] ?: 0,
                    bareCompound.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no `.runCatching {` " +
                        "block — the extension runCatching " +
                        "is the one stdlib receiver-lambda " +
                        "gateway rounds 53–54 never named " +
                        "(round 53's needle is the literal " +
                        "`.run {`); inside it the receiver's " +
                        "`value` writes are bare, and the " +
                        "form `_showPlayerRequests" +
                        ".runCatching { value += 1 }` ran " +
                        "green across R49–R54 this round. " +
                        "The shipped top-level `runCatching " +
                        "{` in SecretBox has no dot and no " +
                        "receiver and stands untouched",
                    0,
                    runCatchingBlock.findAll(text).count(),
                )
                assertEquals(
                    "$key must declare no extension " +
                        "function on a Flow type (`fun <T> " +
                        "MutableStateFlow<T>.…`, qualified " +
                        "and SharedFlow spellings included) " +
                        "— an extension FUNCTION is a gateway " +
                        "definition shape round 54's " +
                        "receiver-LAMBDA census cannot see; " +
                        "its body must write through a bare " +
                        "`value =` (round 54), a counted " +
                        "channel (rounds 50/53), or — in a " +
                        "core file — the update channel this " +
                        "test now mirrors into the core walk; " +
                        "censusing the definition itself " +
                        "closes every body spelling at once",
                    0,
                    flowExtensionDef.findAll(text).count(),
                )
                if (label == "core") {
                    assertEquals(
                        "$key must hold no StateFlow " +
                            "update/emit-family spelling " +
                            "(`.update`, updateAndGet, " +
                            "getAndUpdate, tryEmit, `.emit(`) " +
                            "— round 50's ban of this family " +
                            "walks the app module only; this " +
                            "mirror closes the core side, " +
                            "where the family is additionally " +
                            "uncompilable today (the core " +
                            "module has no coroutines " +
                            "dependency, pinned by the " +
                            "companion test below)",
                        0,
                        Regex("""\.update\b""").findAll(text).count() +
                            occurrences(text, "updateAndGet") +
                            occurrences(text, "getAndUpdate") +
                            occurrences(text, "tryEmit") +
                            occurrences(text, ".emit("),
                    )
                }
            }
        }
    }

    @Test
    fun coreModuleCarriesNoCoroutinesDependencyOrReference() {
        val javaRoot = locateAppJavaRoot()
        val coreBuild = File(javaRoot, "../../../../core/build.gradle.kts")
            .canonicalFile
        assertTrue(
            "core/build.gradle.kts must be locatable beside the app module",
            coreBuild.isFile,
        )
        assertEquals(
            "core/build.gradle.kts must name no coroutines artifact — " +
                "the core module's only dependency is kotlinx-" +
                "serialization-json, so no core file can name " +
                "MutableStateFlow, define a flow gateway, or host the " +
                "cross-module half of an extension-gateway chain; " +
                "adding the dependency is the one move that could " +
                "reopen that form, so it is a census event here",
            0,
            occurrences(coreBuild.readText(), "kotlinx-coroutines"),
        )
        for ((path, text) in coreSources()) {
            assertEquals(
                "core:$path must name no kotlinx.coroutines reference — " +
                    "shipped core production is coroutines-free (no Flow, " +
                    "no StateFlow, no scope gateway can be defined or " +
                    "consumed there while the dependency above stays " +
                    "absent)",
                0,
                occurrences(text, "kotlinx.coroutines"),
            )
        }
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** The app module walk, shared with R49–R54's census
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
     *  root exactly as R51–R54's censuses locate them. */
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
