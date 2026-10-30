package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 62 sweep contracts (adversarial lane — breaking the
 * round-61 fixes):
 *
 *  Charter 2 (the birth census's own completeness): round
 *  61's 39-entry table binds each flow birth as a CONTAINS
 *  needle on its property line. Two completeness questions
 *  stood behind it:
 *   - A flow born LOCALLY inside a function names the same
 *     class, so it is a declaration-census event — round
 *     50's per-file counts walk every app-main file. But
 *     round 50's declaration regex is
 *     `MutableStateFlow\s*(?:<|\()`: it cannot span a
 *     COMMENT between the class name and the paren, and
 *     Kotlin comments are whitespace. This round's doctor —
 *     SearchViewModel's exposure respelled as a getter over
 *     a function-local flow — `val state` becomes a computed
 *     property whose `get()` calls a private function that
 *     constructs a MutableStateFlow born Error, with a block
 *     comment interposed between the class name and the
 *     constructor paren — ran GREEN across the full 648-test
 *     suite: the
 *     shipped `_state` birth (and its needle) stood intact
 *     beside the parallel flow the screen actually collects,
 *     round 50's count never moved (the comment defeats the
 *     regex), the collect receiver text never changed, and
 *     no write census keys on the local. (The same doctor
 *     WITHOUT the comment fails exactly round 50's census —
 *     the gap is the regex's comment blindness, not the
 *     census's absence.)
 *   - A getter-computed flow property has no initializer
 *     line for any birth table. Shipped reality: zero —
 *     every StateFlow-typed property in the module is
 *     initializer-backed, and the module's only `get()`
 *     properties are three non-flow ones.
 *  The first test therefore counts the RAW class-name token
 *  per file (comments cannot hide a name the code must
 *  spell to construct the class), and the second pins the
 *  getter-computed flow property at its shipped zero in
 *  both its typed and untyped spellings. stateIn / shareIn
 *  / channelFlow births remain at round 52/53/55's zeros,
 *  and core carries no coroutines reference at all (round
 *  55), so the MutableStateFlow name is the only road to a
 *  StateFlow birth in either module.
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R62SweepContractTest {

    @Test
    fun flowBirthsAreCountedByRawClassNamePerFile() {
        val expected: Map<String, Int> = mapOf(
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
        val files = moduleSources()
        var total = 0
        for ((path, text) in files) {
            val count = text.lines()
                .filterNot { it.trimStart().startsWith("import ") }
                .sumOf { line ->
                    line.split("MutableStateFlow").size - 1
                }
            total += count
            assertEquals(
                "$path must name MutableStateFlow exactly its " +
                    "shipped number of times — a flow birth must " +
                    "spell the class name wherever it happens " +
                    "(property, function-local, or behind a " +
                    "getter), and a comment interposed between " +
                    "the name and the constructor paren (round " +
                    "62 doctor, GREEN across the full suite) " +
                    "cannot hide the name from a raw count the " +
                    "way it hides the declaration from round " +
                    "50's regex",
                expected[path] ?: 0,
                count,
            )
        }
        assertEquals(
            "the module must hold exactly its 40 shipped " +
                "MutableStateFlow namings (round 61's census, " +
                "+1 at 0.8.2 for Home's feedNote flow)",
            40,
            total,
        )
    }

    @Test
    fun noFlowPropertyIsGetterComputedModuleWide() {
        val typedGetter = Regex(
            """(?:StateFlow|SharedFlow)(?:\s*<[^;{}]*>)?\s+get\s*\(\s*\)""",
        )
        val untypedGetter = Regex(
            """\bval\s+[A-Za-z_][A-Za-z0-9_]*\s+get\s*\(\s*\)""",
        )
        for ((path, text) in moduleSources()) {
            assertEquals(
                "$path must hold no getter-computed StateFlow " +
                    "property — a `get()` body has no initializer " +
                    "line for any birth census to bind, so the " +
                    "shipped count is zero and any computed flow " +
                    "exposure (round 62's getter doctor) is a " +
                    "census event here in both spellings",
                0,
                typedGetter.findAll(text).count(),
            )
            assertEquals(
                "$path must hold no untyped getter-computed " +
                    "property either (the shipped getters are " +
                    "all explicitly typed non-flow properties)",
                0,
                untypedGetter.findAll(text).count(),
            )
        }
    }

    // -- helpers ------------------------------------------------------------

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
}
