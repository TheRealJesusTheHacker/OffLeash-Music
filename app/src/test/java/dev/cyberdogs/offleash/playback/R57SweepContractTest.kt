package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Round 57 sweep contracts (adversarial lane — breaking the
 * round 56 fixes):
 *
 *  (Charter 1 — the top-level operator diff, and FINDING
 *  F1) Round 56's operator census keys on its enumerated
 *  list. Diffed literally against the kotlinx-coroutines
 *  1.8.1 top-level / operator surface (FlowKt in the
 *  shipped jar, enumerated with javap this round): bare
 *  `combine(` IS pinned — round 56's zero regexes hold
 *  both `(?<![\w.])combine\s*[({]` and the dotted form,
 *  and the chartered prime suspect (a `combine(_snapshot,
 *  _snapshot) { s, _ -> … }` declaration in
 *  PlaybackDiagnostics) was doctored on disk this round
 *  and fails exactly round 56's census (1 failure in 56).
 *  Bare top-level `zip(` does not exist — the compiler
 *  resolves it to the `Flow.zip` extension and rejects
 *  the two-flow call ("too many arguments"), and the
 *  dotted form is round 56's zero. `flowOf(` is round
 *  53's zero. What SURVIVED, all proven on disk this
 *  round against R47–R56 + UiSweep + LifecycleChaos (56
 *  tests): the rest of the operator family round 56's
 *  list never named. A derived flow declared in
 *  PlaybackDiagnostics as `_snapshot.mapNotNull { s ->
 *  if (s.isEmpty()) null else listOf(s[0]) }` (compiled
 *  in the real app-main subset at 0 errors) collected on
 *  PlaybackDiagnosticsScreen through a receiver swap
 *  (charter 2) ran GREEN — as did a `.scan(…)`-declared
 *  twin, and a top-level `merge(_snapshot, _snapshot)`
 *  declaration. `combineTransform(…)`'s spelling is
 *  likewise unnamed, but every emission inside it is
 *  already pinned (its bare `emit(` doctor fails exactly
 *  round 56's census), and the dotted `combineLatest` is
 *  compile-dead on the shipped jar (deprecation ERROR in
 *  1.8.1, compiler-proved this round) — both are
 *  censused below anyway, so the family is closed by
 *  enumeration, not by the luck of which link happened
 *  to be pinned. The first test censuses the whole
 *  remainder at shipped counts in both modules.
 *
 *  (Charter 2 — FINDING F2) Round 56 counted collect
 *  TOTALS; round 50's multiset binds only
 *  `viewModel.<flow>.collectAsState()` receivers — 13 of
 *  the 52 shipped collects. The other 39 (every
 *  `container.` / `diagnostics.` / `manager.` /
 *  `downloadManager.` / `likeController.` /
 *  `playlistStore.` receiver) were bound by count only.
 *  Proven this round: swapping ONLY the receiver of
 *  PlaybackDiagnosticsScreen's shipped
 *  `diagnostics.snapshot.collectAsState()` line to a
 *  doctored derived flow — count, line shape and every
 *  viewModel multiset untouched — ran GREEN (56/56),
 *  with and without the charter-1 declaration beside it.
 *  The second test extends the round-50 multiset to ALL
 *  collectAsState receivers per file (the full receiver
 *  expression, verbatim), plus the one
 *  collectAsStateWithLifecycle receiver in Common.kt.
 *
 *  (Charter 3 — FINDING F3) Round 56 pinned the four
 *  Gradle build files and the verification metadata.
 *  The remaining build surface stood unpinned, proven
 *  this round by a three-part doctor that ran GREEN
 *  (56/56): a line appended to gradle.properties, the
 *  wrapper distribution downgraded 8.9 -> 8.7 in
 *  gradle/wrapper/gradle-wrapper.properties, and the
 *  android.yml CI gate weakened (the app unit-test task
 *  dropped from the test step). Traced this round:
 *  gradle.properties feeds NO coordinate or version (its
 *  four properties are the Gradle JVM args, the Kotlin
 *  code-style flag, and two AGP behaviour flags; every
 *  version is a literal in the round-56-pinned build
 *  files, and the one gradleProperty the build reads —
 *  offleashDebugKeystore — arrives via -P on the CI
 *  command line, not this file), but it is build
 *  BEHAVIOUR surface, the wrapper properties + jar are
 *  the supply-chain root (which Gradle, from where,
 *  booted by which jar), and the workflows are the gate
 *  itself — precedent for pinning repo-root files is
 *  round 56's own build pins. The third test pins
 *  gradle.properties and the wrapper properties
 *  verbatim, and the wrapper jar, the three workflows
 *  and dependabot.yml by SHA-256. Observation, not a
 *  fix: gradle/verification-metadata.xml carries
 *  verify-metadata=true and verify-signatures=false
 *  (415 components, 742 artifacts, no trusted or
 *  ignored entries) — hash enforcement is on and is the
 *  fleet's chosen standard (the generate-metadata
 *  workflow documents that a normal CI run enforces
 *  it); PGP signature verification is off, by the
 *  file's own shipped configuration.
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R57SweepContractTest {

    @Test
    fun flowOperatorRemainderIsCensusedAtShippedCountsModuleWide() {
        val zeroRegexes = listOf(
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
        val zeroNeedles = listOf(
            "combineTransform", "combineLatest",
            "flattenMerge", "flattenConcat", "emptyFlow",
        )
        val dotMapNotNull = Regex("""\.mapNotNull\s*[({]""")
        val expectedMapNotNull: Map<String, Int> = mapOf(
            "app:dev/cyberdogs/offleash/data/DownloadStore.kt" to 2,
            "app:dev/cyberdogs/offleash/data/Stores.kt" to 3,
            "core:dev/cyberdogs/offleash/core/auth/AuthSession.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/JsonNav.kt" to 1,
            "core:dev/cyberdogs/offleash/core/parse/Parsers.kt" to 3,
        )
        val dotFilterNot = Regex("""\.filterNot\s*[({]""")
        val dotDropWhile = Regex("""\.dropWhile\s*[({]""")
        val dotWithIndex = Regex("""\.withIndex\s*\(""")
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
        for ((label, sources) in listOf(
            "app" to moduleSources(),
            "core" to coreSources(),
        )) {
            for ((path, text) in sources) {
                val key = "$label:$path"
                for (regex in zeroRegexes) {
                    assertEquals(
                        "$key must hold no remainder-operator " +
                            "spelling matching `$regex` — round " +
                            "56 censused its enumerated operator " +
                            "list; the spellings here complete " +
                            "the kotlinx 1.8.1 FlowKt surface " +
                            "(javap-enumerated this round) and " +
                            "stood unnamed by every census: a " +
                            "`mapNotNull`-derived flow in " +
                            "PlaybackDiagnostics collected " +
                            "through a swapped screen receiver, " +
                            "a `scan`-declared twin, and a " +
                            "top-level `merge(…)` declaration " +
                            "each ran green across R47–R56 this " +
                            "round; shipped uses of every " +
                            "spelling here are zero in both " +
                            "modules",
                        0,
                        regex.findAll(text).count(),
                    )
                }
                for (needle in zeroNeedles) {
                    assertEquals(
                        "$key must name no `$needle` — the " +
                            "named remainder spellings of the " +
                            "same family (the top-level " +
                            "combineTransform / the flatten " +
                            "pair / the emptyFlow source; " +
                            "combineLatest is compile-dead on " +
                            "the shipped jar — deprecation " +
                            "ERROR — and is censused anyway so " +
                            "the family is closed by " +
                            "enumeration); shipped uses are " +
                            "zero module-wide",
                        0,
                        occurrences(text, needle),
                    )
                }
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.mapNotNull` count — the dual-use " +
                        "spelling (its lambda is written with " +
                        "a brace, so the census keys on " +
                        "`[({]`): every shipped site maps a " +
                        "collection or a JSON array; a Flow " +
                        "`.mapNotNull { … }` deriving a " +
                        "narrowed stream in any file (the " +
                        "finding's own spelling, proven green " +
                        "across R47–R56 this round) is one " +
                        "more occurrence here",
                    expectedMapNotNull[key] ?: 0,
                    dotMapNotNull.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.filterNot` count — the dual-use " +
                        "spelling, pinned for the same reason " +
                        "round 56 pinned `.filter`: every " +
                        "shipped site filters a collection; " +
                        "a Flow use is one more occurrence",
                    expectedFilterNot[key] ?: 0,
                    dotFilterNot.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.dropWhile` count — the dual-use " +
                        "twin of round 56's `.drop(`; the one " +
                        "shipped site drops characters, a " +
                        "Flow use is one more occurrence",
                    expectedDropWhile[key] ?: 0,
                    dotDropWhile.findAll(text).count(),
                )
                assertEquals(
                    "$key must hold exactly its shipped " +
                        "`.withIndex` count — the dual-use " +
                        "spelling; every shipped site indexes " +
                        "a collection, a Flow use is one more " +
                        "occurrence",
                    expectedWithIndex[key] ?: 0,
                    dotWithIndex.findAll(text).count(),
                )
            }
        }
    }

    @Test
    fun everyCollectAsStateReceiverIsBoundPerFileAsAMultiset() {
        val collectRegex =
            Regex("""([A-Za-z_][\w.]*(?:\(\))?)\.collectAsState\(""")
        val lifecycleRegex =
            Regex("""([A-Za-z_][\w.]*)\s*\.collectAsStateWithLifecycle\(""")
        val expected: Map<String, Map<String, Int>> = mapOf(
            "dev/cyberdogs/offleash/ui/OffleashRoot.kt" to mapOf(
                "container.queueController.queueState" to 2,
                "container.queueController.playbackState" to 2,
                "container.queueController.namedError" to 1,
                "container.showPlayerRequests" to 1,
                "container.likeController.error" to 1,
            ),
            "dev/cyberdogs/offleash/ui/about/AboutScreen.kt" to mapOf(
                "container.accountUiState" to 1,
            ),
            "dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt" to mapOf(
                "diagnostics.snapshot" to 1,
                "container.accountUiState" to 1,
            ),
            "dev/cyberdogs/offleash/ui/account/SignInScreen.kt" to mapOf(
                "manager.phase" to 1,
                "manager.uiState" to 1,
            ),
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt" to mapOf(
                "downloadManager.records" to 1,
                "likeController.states" to 1,
                "likeController.accountBacked" to 1,
            ),
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to mapOf(
                "viewModel.state" to 3,
                "viewModel.hasMore" to 1,
                "viewModel.loadMoreError" to 1,
                "container.localPlaylistStore.playlists" to 2,
                "container.accountUiState" to 1,
            ),
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to mapOf(
                "viewModel.state" to 1,
            ),
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to mapOf(
                "viewModel.state" to 1,
                "viewModel.selectedChip" to 1,
                "container.accountUiState" to 1,
            ),
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to mapOf(
                "viewModel.state" to 2,
            ),
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt" to mapOf(
                "container.localPlaylistStore.playlists" to 2,
                "container.recentsStore.recents()" to 1,
                "container.downloadManager.records" to 1,
                "container.appPreferences.autoDownloadLikedFlow" to 1,
                "container.accountUiState" to 1,
            ),
            "dev/cyberdogs/offleash/ui/player/PlayerScreen.kt" to mapOf(
                "container.queueController.queueState" to 2,
                "container.queueController.playbackState" to 1,
                "container.queueController.namedError" to 1,
                "container.queueController.sleepTimerState" to 1,
                "container.queueController.sleepTimerCountdownMs" to 1,
                "container.queueController.speedState" to 1,
                "container.likeController.states" to 1,
                "container.localPlaylistStore.playlists" to 1,
            ),
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to mapOf(
                "viewModel.state" to 1,
                "viewModel.hasMore" to 1,
                "viewModel.loadMoreError" to 1,
            ),
            "dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt" to mapOf(
                "container.appPreferences.streamQualityFlow" to 1,
                "container.parityPreferences.downloadQualityFlow" to 1,
                "container.parityPreferences.autoDownloadLikedFlow" to 1,
                "container.parityPreferences.audioVersionInRadioFlow" to 1,
                "container.accountUiState" to 1,
            ),
        )
        val expectedLifecycle: Map<String, Map<String, Int>> = mapOf(
            "dev/cyberdogs/offleash/ui/components/Common.kt" to mapOf(
                "playlistStore.playlists" to 1,
            ),
        )
        for ((path, text) in moduleSources()) {
            val actual = collectRegex.findAll(text)
                .map { it.groupValues[1] }
                .groupingBy { it }
                .eachCount()
            assertEquals(
                "$path must collect exactly its shipped " +
                    "multiset of collectAsState RECEIVER " +
                    "expressions — round 50 binds only the " +
                    "viewModel receivers (13 of 52) and round " +
                    "56 counts totals per file, so a receiver " +
                    "SWAP at constant count on any of the 39 " +
                    "non-viewModel sites (proven this round " +
                    "on PlaybackDiagnosticsScreen: the " +
                    "shipped `diagnostics.snapshot` collect " +
                    "swapped to a doctored derived flow, " +
                    "GREEN across R47–R56) moved no pin; " +
                    "the receiver expression itself is now " +
                    "the pinned unit, for every receiver",
                expected[path] ?: emptyMap<String, Int>(),
                actual,
            )
            val actualLifecycle = lifecycleRegex.findAll(text)
                .map { it.groupValues[1] }
                .groupingBy { it }
                .eachCount()
            assertEquals(
                "$path must hold exactly its shipped " +
                    "collectAsStateWithLifecycle receiver " +
                    "multiset — the lifecycle collect's one " +
                    "shipped site (Common.kt's " +
                    "`playlistStore.playlists`) is bound by " +
                    "round 56 as a word count only; its " +
                    "receiver is bound here for the same " +
                    "reason as the collectAsState receivers " +
                    "above",
                expectedLifecycle[path] ?: emptyMap<String, Int>(),
                actualLifecycle,
            )
        }
    }

    @Test
    fun remainingBuildSurfaceIsPinnedVerbatimAndByHash() {
        val javaRoot = locateAppJavaRoot()
        val repoRoot = File(javaRoot, "../../../..").canonicalFile
        fun repoFile(name: String): File {
            val file = File(repoRoot, name)
            assertTrue(
                "$name must be locatable at the repo root",
                file.isFile,
            )
            return file
        }
        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        assertEquals(
            "gradle.properties is pinned verbatim — it " +
                "feeds no coordinate or version (traced " +
                "this round: the four properties are the " +
                "Gradle JVM args, the Kotlin code-style " +
                "flag and two AGP behaviour flags; every " +
                "version is a literal in the round-56-" +
                "pinned build files, and the one " +
                "gradleProperty the build reads arrives " +
                "via -P in CI), but it is build-behaviour " +
                "surface: flipping " +
                "android.nonTransitiveRClass here ran " +
                "green across R47–R56 this round beside " +
                "the wrapper and workflow doctors below",
            """org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
kotlin.code.style=official
android.useAndroidX=true
android.nonTransitiveRClass=true""".trim(),
            repoFile("gradle.properties").readText().trim(),
        )
        assertEquals(
            "gradle/wrapper/gradle-wrapper.properties is " +
                "pinned verbatim — the distribution URL " +
                "and version are the supply-chain root of " +
                "every build (which Gradle, from where); " +
                "a downgrade edit (8.9 -> 8.7) ran green " +
                "across R47–R56 this round",
            """distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists""".trim(),
            repoFile("gradle/wrapper/gradle-wrapper.properties")
                .readText().trim(),
        )
        assertEquals(
            "gradle/wrapper/gradle-wrapper.jar's SHA-256 " +
                "is pinned (43,504 bytes) — the jar is the " +
                "code that fetches and boots the pinned " +
                "distribution; a swapped jar would defeat " +
                "the properties pin above",
            "498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17",
            sha256(repoFile("gradle/wrapper/gradle-wrapper.jar")),
        )
        assertEquals(
            ".github/workflows/android.yml's SHA-256 is " +
                "pinned (4,444 bytes) — this workflow IS " +
                "the gate (it runs the unit tests and " +
                "builds the APK); dropping the app " +
                "unit-test task from its test step ran " +
                "green across R47–R56 this round, so the " +
                "gate's own text is now a test-visible " +
                "surface, extending round 56's repo-root " +
                "build pins by their own precedent",
            "a51db572d13d22518036e2998513124a679bf436be5943f8de37fd0c2f2e2055",
            sha256(repoFile(".github/workflows/android.yml")),
        )
        assertEquals(
            ".github/workflows/release.yml's SHA-256 is " +
                "pinned (5,271 bytes) — the release route " +
                "carries the expected signer digest and " +
                "the asset-attach step; it shares the " +
                "gate's test step and is pinned for the " +
                "same reason",
            "8596a4d640e7e9fc2e789a2bc71f7897298d69b83f198797064bfa5a804bdfbd",
            sha256(repoFile(".github/workflows/release.yml")),
        )
        assertEquals(
            ".github/workflows/generate-verification-" +
                "metadata.yml's SHA-256 is pinned (2,964 " +
                "bytes) — the regeneration path for the " +
                "hash lock round 56 pinned; its text is " +
                "part of the same supply-chain surface",
            "ab4671ca526bdf830219487b8e0d91dd05e828308feba309e1b2d44713ea5c87",
            sha256(
                repoFile(
                    ".github/workflows/generate-verification-metadata.yml",
                ),
            ),
        )
        assertEquals(
            ".github/dependabot.yml's SHA-256 is pinned " +
                "(1,239 bytes) — it carries the fleet's " +
                "dependabot hash-lock reconciliation (pip " +
                "and gradle version updates off, actions " +
                "on); a silent edit could re-enable the " +
                "unrelocked bump PRs the policy forbids",
            "db47005731e431b93dd735fc7ca64244863205eef9819299fb7d67de43f91fa2",
            sha256(repoFile(".github/dependabot.yml")),
        )
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** The app module walk, shared with R49–R56's census
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
     *  root exactly as R51–R56's censuses locate them. */
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
