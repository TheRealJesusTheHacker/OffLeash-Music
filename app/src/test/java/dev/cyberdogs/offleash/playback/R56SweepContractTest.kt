package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Round 56 sweep contracts (adversarial lane — completeness
 * proofs + breaking the round 55 fixes):
 *
 *  (Charter 1 — the stdlib receiver-lambda completeness
 *  table, CLOSED) Kotlin is 2.0.20 (root build file). Every
 *  stdlib function whose parameter is a lambda with a
 *  receiver over an arbitrary T, or whose block executes
 *  with an implicit receiver, now carries a disposition:
 *  with (round 53, `with(` at zero), T.run (round 53,
 *  `.run {` at zero), T.apply (round 53, per-file),
 *  T.let / T.also (round 54, per-file — their lambdas in
 *  fact carry `it`, and round 55 compiler-proved a bare
 *  write inside them dead), T.runCatching (round 55,
 *  `.runCatching {` at zero), and the write TEXT of any
 *  bare-receiver write (rounds 54/55: bare `value =` and
 *  bare compound censuses). The entries no round had yet
 *  doctored are closed this round, by compiler proof on
 *  the shipped classpath (standalone kotlinc, Kotlin
 *  2.0.20 jars): T.takeIf and T.takeUnless take an
 *  `it`-parameter predicate — `f.takeIf { value += 1 }`
 *  and the takeUnless twin each fail with `unresolved
 *  reference 'value'`, while the compilable spelling
 *  `f.takeIf { it.value += 1 }` carries `.value` text the
 *  round 51/52 compound totals count (both doctor forms
 *  were also run on disk this round: the bare form fails
 *  exactly round 55's bare-compound census, the `it` form
 *  fails rounds 51+52). T.use is `it`-parameterised and
 *  Closeable-bound: a bare write inside a Closeable's use
 *  block fails identically, and a StateFlow receiver
 *  fails outright (not Closeable). Top-level `run { }`,
 *  `repeat { }` and `withContext { }` blocks carry no
 *  T receiver (withContext's receiver is the concrete
 *  CoroutineScope) — bare writes inside each fail with
 *  the same unresolved reference. Compose's `remember`
 *  and the stdlib concrete-receiver builders (buildString
 *  and kin) can never take a flow as receiver. To make
 *  the table itself the pin's definition, the second test
 *  below censuses the last three spellings at their
 *  shipped counts: `.takeIf` per file (55 sites, every one
 *  a value filter on a plain value), `.takeUnless` at
 *  zero, `.use {` per file (12 sites, all streams/files),
 *  and bare top-level `run {` at zero.
 *
 *  (Charter 2 — FINDING, fixed here) Round 55 censused
 *  the COLLECT family; the OPERATOR family that can carry
 *  a doctored value into a counted collect stood
 *  uncensused. Proven green across R47–R55 this round, on
 *  PlaybackDiagnostics / PlaybackDiagnosticsScreen (the
 *  R55 doctors' stage): a derived flow declared in the VM
 *  as `val narrowNotes = _snapshot.transform { s ->
 *  emit(if (s.isEmpty()) "" else s[0].take(1)) }` collected
 *  on the screen as `diagnostics.narrowNotes
 *  .collectAsState(initial = "")` beside the shipped
 *  snapshot decoy — and the same chain spelled with
 *  `.map { … }` and with `.transformLatest { … }`. Every
 *  link was open: no census names the operator spelling,
 *  the inferred return type carries no plain `Flow<…>`
 *  text for round 54's census, the bare `emit(` inside
 *  transform carries no dot for round 51's needle (round
 *  55 mirrored `.emit(` into the CORE walk only), and
 *  round 50's collected-flow multiset binds only
 *  `viewModel.<flow>.collectAsState()` expressions, so a
 *  `diagnostics.`-receiver collect is invisible to it.
 *  The transform shape also compiles standalone at zero
 *  errors against the shipped coroutines jar. The fix
 *  censuses the operator family per file at shipped
 *  counts in both modules: the Flow-only operator
 *  spellings (transform / transformLatest / mapLatest /
 *  flatMapMerge / flatMapConcat / flatMapLatest / onStart
 *  / onCompletion / distinctUntilChanged / debounce /
 *  combine / zip / Flow `.catch {` / retryWhen / emitAll
 *  / bare `emit(`) at zero, the dual-use operator
 *  spellings (`.map`, `.filter`, `.take(`, `.drop(`,
 *  `.retry(`) at their shipped per-file counts (every
 *  shipped site is a collection/value use — a Flow
 *  operator use in any file is one more occurrence
 *  there), and the collect link itself (`.collectAsState(`
 *  and `collectAsStateWithLifecycle`) per file at shipped
 *  counts, so a NEW collected property on ANY receiver is
 *  a census event even where round 50's viewModel-only
 *  multiset cannot see it.
 *
 *  (Charter 3 — FINDING, fixed here) The build-file
 *  meta-channel: every gateway residual ultimately needs
 *  in-tree text (censused) or a new dependency. Proven
 *  this round: adding one unused dependency line
 *  (`com.google.code.gson:gson:2.11.0`) to BOTH
 *  app/build.gradle.kts and core/build.gradle.kts ran
 *  green across the build-file-aware pins (rounds 4/50/
 *  53/55 classes) — the dependency surface was
 *  suite-invisible except for two narrow needles (round 4
 *  reads app's versionName; round 55 counts the substring
 *  `kotlinx-coroutines` in core's build file). Under the
 *  fleet's supply-chain lens (pin AND hash-verify; the
 *  tree already carries gradle/verification-metadata.xml
 *  for the shipped set), the third test pins the
 *  dependency surface verbatim: both modules' plugins and
 *  dependencies blocks, the root build file (plugin
 *  versions) and settings file whole, and the
 *  verification metadata's SHA-256 — so any dependency
 *  change is a deliberate, test-visible act. No
 *  libs.versions.toml exists in this tree (checked; the
 *  gradle/ directory carries only the wrapper and the
 *  verification metadata).
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R56SweepContractTest {

    @Test
    fun flowOperatorFamilyIsCensusedAtShippedCountsModuleWide() {
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
            "app:dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to 4,
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
            for ((path, text) in sources) {
                val key = "$label:$path"
                for (regex in zeroRegexes) {
                    assertEquals(
                        "$key must hold no Flow-operator " +
                            "spelling matching `$regex` — the " +
                            "operator family that can carry a " +
                            "doctored value INTO a counted " +
                            "collect stood uncensused before " +
                            "this round: a derived flow " +
                            "declared as `_snapshot.transform " +
                            "{ s -> emit(…) }` in a VM and " +
                            "collected on its screen through " +
                            "a non-viewModel receiver ran " +
                            "green across R47–R55 (bare " +
                            "`emit(` carries no dot for round " +
                            "51's needle; the inferred type " +
                            "carries no `Flow<` text for round " +
                            "54's); shipped uses of every " +
                            "spelling here are zero in both " +
                            "modules",
                        0,
                        regex.findAll(text).count(),
                    )
                }
                for (needle in zeroNeedles) {
                    assertEquals(
                        "$key must name no `$needle` — the " +
                            "named Flow-operator spellings of " +
                            "the family above; shipped uses " +
                            "are zero module-wide (the " +
                            "transformLatest-spelled chain ran " +
                            "green across R47–R55 this round " +
                            "exactly as the transform one did)",
                        0,
                        occurrences(text, needle),
                    )
                }
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.map` count — every shipped site " +
                        "maps a collection or a value; a Flow " +
                        "`.map { … }` deriving a narrowed " +
                        "stream in any file (the map-spelled " +
                        "chain ran green across R47–R55 this " +
                        "round) is one more occurrence here",
                    expectedMap[key] ?: 0,
                    dotMap.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.filter` count — the dual-use " +
                        "operator spelling, pinned for the " +
                        "same reason as `.map` above",
                    expectedFilter[key] ?: 0,
                    dotFilter.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.take(` count — the dual-use " +
                        "operator spelling (round 48 pinned " +
                        "publish SLICES; this pins the " +
                        "spelling module-wide)",
                    expectedTake[key] ?: 0,
                    dotTake.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.drop(` count — the dual-use " +
                        "operator spelling",
                    expectedDrop[key] ?: 0,
                    dotDrop.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.retry(` count — the one shipped " +
                        "site is WorkManager's `Result" +
                        ".retry()`; a Flow `.retry(…)` is " +
                        "the same spelling on a flow receiver",
                    expectedRetry[key] ?: 0,
                    dotRetry.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.collectAsState(` count — round " +
                        "50's multiset binds only `viewModel." +
                        "<flow>.collectAsState()` expressions, " +
                        "so a collect on any other receiver " +
                        "(the shipped `diagnostics.`, " +
                        "`container.` and store collects " +
                        "included) stood outside it; a NEW " +
                        "collected property on any receiver " +
                        "is one more occurrence here",
                    expectedCollect[key] ?: 0,
                    dotCollect.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`collectAsStateWithLifecycle` count " +
                        "— the lifecycle collect spelling " +
                        "(import + call in Common.kt are the " +
                        "shipped sites), the collect link's " +
                        "second spelling",
                    expectedLifecycle[key] ?: 0,
                    occurrences(text, "collectAsStateWithLifecycle"),
                )
            }
        }
    }

    @Test
    fun gatewayRemainderSpellingsAreCensusedAtShippedCountsModuleWide() {
        val dotTakeIf = Regex("""\.takeIf\b""")
        val dotTakeUnless = Regex("""\.takeUnless\b""")
        val useBlock = Regex("""\.use\s*\{""")
        val bareRunBlock = Regex("""(?<![\w.])run\s*\{""")
        val expectedTakeIf: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/CrashReporter.kt" to 2,
            "app:dev/cyberdogs/offleash/data/DownloadManager.kt" to 2,
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 4,
            "app:dev/cyberdogs/offleash/data/Downloads.kt" to 1,
            "app:dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to 5,
            "app:dev/cyberdogs/offleash/data/SessionStore.kt" to 1,
            "app:dev/cyberdogs/offleash/data/Stores.kt" to 2,
            "app:dev/cyberdogs/offleash/playback/QueueController.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to 2,
            "app:dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to 4,
            "app:dev/cyberdogs/offleash/ui/home/HomeChrome.kt" to 3,
            "app:dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to 1,
            "core:dev/cyberdogs/offleash/core/api/YoutubeiClient.kt" to 3,
            "core:dev/cyberdogs/offleash/core/auth/AuthSession.kt" to 1,
            "core:dev/cyberdogs/offleash/core/auth/SessionCookies.kt" to 6,
            "core:dev/cyberdogs/offleash/core/error/OffleashException.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/JsonNav.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/Parsers.kt" to 9,
        )
        val expectedUse: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/AtomicFileWrite.kt" to 2,
            "app:dev/cyberdogs/offleash/data/CrashReporter.kt" to 2,
            "app:dev/cyberdogs/offleash/data/DownloadManager.kt" to 3,
            "app:dev/cyberdogs/offleash/data/OkHttpTransport.kt" to 1,
            "app:dev/cyberdogs/offleash/data/SessionStore.kt" to 1,
            "app:dev/cyberdogs/offleash/playback/QueueController.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/about/AboutScreen.kt" to 1,
            "app:dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt" to 1,
        )
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val key = "$label:$path"
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.takeIf` count — takeIf completes " +
                        "the stdlib receiver-lambda table " +
                        "(charter 1): its predicate carries " +
                        "`it`, not a receiver, so a bare " +
                        "StateFlow write inside it does not " +
                        "compile (compiler-proved this round) " +
                        "and the compilable `it.value` " +
                        "spelling is counted by rounds 51/52; " +
                        "censusing the spelling itself makes " +
                        "the enumerated table the pin's " +
                        "definition — every shipped site is " +
                        "a value filter on a plain value",
                    expectedTakeIf[key] ?: 0,
                    dotTakeIf.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no `.takeUnless` — the " +
                        "takeIf twin, with the same `it`-" +
                        "parameter disposition (compiler-" +
                        "proved this round); shipped uses " +
                        "are zero module-wide",
                    0,
                    dotTakeUnless.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.use {` count — use completes the " +
                        "table: `it`-parameterised and " +
                        "Closeable-bound, so a bare write " +
                        "inside it does not compile and a " +
                        "StateFlow can never be its receiver " +
                        "(both compiler-proved this round); " +
                        "every shipped site closes a stream, " +
                        "a reader or a response",
                    expectedUse[key] ?: 0,
                    useBlock.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold no bare top-level " +
                        "`run {` block — the top-level run " +
                        "has no receiver at all (a bare write " +
                        "inside it is an unresolved " +
                        "reference, compiler-proved this " +
                        "round); the dotted receiver form " +
                        "stands at zero under round 53",
                    0,
                    bareRunBlock.findAll(text).count(),
                )
            }
        }
    }

    @Test
    fun buildFileDependencySurfaceIsPinnedVerbatim() {
        val javaRoot = locateAppJavaRoot()
        val repoRoot = File(javaRoot, "../../../..").canonicalFile
        fun repoFile(name: String): String {
            val file = File(repoRoot, name)
            assertTrue(
                "$name must be locatable at the repo root",
                file.isFile,
            )
            return file.readText()
        }
        fun block(text: String, header: String): String {
            val start = text.indexOf(header)
            assertTrue(
                "the block `$header` must exist in its build file",
                start >= 0,
            )
            val end = text.indexOf("\n}", start)
            assertTrue(
                "the block `$header` must close at column zero",
                end >= 0,
            )
            return text.substring(start, end + 2).trim()
        }
        val appBuild = repoFile("app/build.gradle.kts")
        val coreBuild = repoFile("core/build.gradle.kts")
        assertEquals(
            "app/build.gradle.kts's plugins block is pinned " +
                "verbatim — a plugin change is a dependency-" +
                "surface change and must be a deliberate, " +
                "test-visible act (this round an unused " +
                "gson line added to both modules' " +
                "dependencies ran green across every " +
                "build-file-aware pin)",
            """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}""".trim(),
            block(appBuild, "plugins {"),
        )
        assertEquals(
            "app/build.gradle.kts's dependencies block is " +
                "pinned verbatim — the app module's whole " +
                "dependency surface (coordinates + versions) " +
                "stands here; adding, removing or " +
                "re-versioning any line moves this pin",
            """dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
}""".trim(),
            block(appBuild, "dependencies {"),
        )
        assertEquals(
            "core/build.gradle.kts's plugins block is " +
                "pinned verbatim, as the app's above",
            """plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}""".trim(),
            block(coreBuild, "plugins {"),
        )
        assertEquals(
            "core/build.gradle.kts's dependencies block is " +
                "pinned verbatim — round 55 pinned only the " +
                "absence of one substring (kotlinx-" +
                "coroutines) here; the whole block is the " +
                "surface (an unused gson line added here " +
                "ran green this round)",
            """dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}""".trim(),
            block(coreBuild, "dependencies {"),
        )
        assertEquals(
            "the root build file is pinned verbatim — it " +
                "carries every plugin version for both " +
                "modules, so it is dependency surface too",
            """// Top-level build file — plugin versions live here, applied per-module.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
}""".trim(),
            repoFile("build.gradle.kts").trim(),
        )
        assertEquals(
            "settings.gradle.kts is pinned verbatim — it " +
                "carries the repository set and the module " +
                "set the dependency surface resolves " +
                "against",
            """pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "OffLeash"
include(":core", ":app")""".trim(),
            repoFile("settings.gradle.kts").trim(),
        )
        val metadata = File(repoRoot, "gradle/verification-metadata.xml")
        assertTrue(
            "gradle/verification-metadata.xml must exist — " +
                "it is the hash half of the fleet's pin+hash " +
                "supply-chain discipline for the shipped " +
                "dependency set",
            metadata.isFile,
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(metadata.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "gradle/verification-metadata.xml's SHA-256 is " +
                "pinned — regenerating or editing the hash " +
                "lock (the act that would legitimise a new " +
                "dependency in CI) is itself a test-visible " +
                "act, never a silent companion change",
            "5b0a34219f3d6819417136ab51143100f7751278942af1fc747ca6d3f6b67c5c",
            digest,
        )
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** The app module walk, shared with R49–R55's census
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
     *  root exactly as R51–R55's censuses locate them. */
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
