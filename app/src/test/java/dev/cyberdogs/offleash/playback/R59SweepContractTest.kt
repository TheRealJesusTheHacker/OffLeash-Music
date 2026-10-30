package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Round 59 sweep contracts (adversarial lane — breaking the
 * round 58 fixes):
 *
 *  (Charter 1 — FINDING F1) Round 58 binds the wiring
 *  ARGUMENT lines at every construction in OffleashApp.kt
 *  (`storage = FileSecretStorage(sessionFileIn(app))` and
 *  friends), but the values those arguments resolve to are
 *  decided one level UPSTREAM, and that layer was unbound:
 *  (a) the file-helper BODIES in the data layer — swapping
 *  the file-name constants inside `sessionFileIn` and
 *  `googleSessionFileIn` (each helper now returns the OTHER
 *  store's file) cross-wires the two sealed credential
 *  stores exactly like round 58's D1, while every bound
 *  call-site line, both constants' values, and
 *  BackupRulesContractTest's contains needles (the session
 *  helper's own file still mentions SESSION_FILE_NAME in
 *  its constant declaration) stay intact — proven GREEN
 *  across the full 636-test app suite this round, restored
 *  md5-verified. The same shape at the pending-grant helper
 *  (pointed at GOOGLE_SESSION_FILE_NAME) was also GREEN.
 *  (b) the container's own feeding `val` initializers that
 *  sit OUTSIDE round 58's slices — respelling
 *  `sessionScope`'s initializer from Dispatchers.IO to
 *  Dispatchers.Main (the scope every bound
 *  `scope = sessionScope` line feeds the two session
 *  managers and the like controller; IO is load-bearing
 *  because those managers do blocking refresh/file work
 *  on it) ran GREEN across all 68 tests in the
 *  OffleashApp-reading contract classes. A literal
 *  initializer SWAP between two container vals is
 *  compile-dead — every feeding val in AppContainer is
 *  uniquely typed (census in the lane report) — so the
 *  exploitable upstream surface is the helper bodies, the
 *  file-name constants behind them (GOOGLE_SESSION_FILE_NAME
 *  and two siblings were pinned by NO test entering this
 *  round; only SESSION_FILE_NAME was, by BackupRules), and
 *  the unbound feeding declarations. The first test binds
 *  that whole layer verbatim, and — closing round 58's
 *  stated residual in the same pass — also binds the
 *  multi-line lambda bodies inside the QueueController /
 *  DownloadManager constructions (the revalidate /
 *  resolveStream bodies and the quality / local-file /
 *  audio-version / speed provider lines). A same-typed
 *  swap INSIDE those bodies was attempted and is
 *  type-dead (AuthSession? vs String? at the resolver;
 *  Float vs Boolean at the speed state; QualityPreference
 *  vs Boolean at the providers — census in the report),
 *  so the bodies are bound as a layer, not for one proven
 *  swap.
 *
 *  (Charter 2 — FINDINGS F2/F3, one half dismissed) Round
 *  58's manifest pin IS exact over the tags it enumerates:
 *  adding a third component (an exported broadcast
 *  receiver) FAILS exactly that test (1 in 10, proven this
 *  round) — dismissed with evidence. But the pin reads
 *  only the literal `uses-permission` tag and only four
 *  component tags, and two same-effect spellings escape
 *  it, both proven GREEN (10/10 manifest-reading tests)
 *  this round: (F2) a permission granted through
 *  `uses-permission-sdk-23` — with minSdk 26 that variant
 *  applies on EVERY supported device, so it is a full
 *  grant the exact set never sees (the sdk-m spelling is
 *  the same shape); (F3) an exported `activity-alias`,
 *  a real additional entry point that is none of the
 *  four enumerated component tags. The second test
 *  censuses every permission-carrying tag and every
 *  component-carrying tag, aliases included.
 *
 *  (Charter 3 — FINDING F4) Every census in the lattice
 *  walks src/main, but the hunt APK is a DEBUG build: the
 *  debug and flavor source sets compile into it. The tree
 *  ships exactly one non-main, non-test source set —
 *  app/src/hunt (a strings override) — and no test read
 *  it. Proven this round: creating
 *  app/src/debug/java/dev/cyberdogs/offleash/DebugGateway.kt
 *  (a parallel-state gateway shape, uncalled) ran GREEN
 *  across the full 636-test app suite — suite-invisible
 *  code in a source set the shipped artifact is built
 *  from (a src/debug AndroidManifest or a debug activity
 *  variant would be the reachable escalation of the same
 *  hole). The third test censuses the source sets
 *  exactly: the src/ directory sets of both modules and
 *  the full shipped (non-test) file list under app/src
 *  and core/src, in the R49 file-set discipline extended
 *  to the whole src tree, plus the hunt strings override
 *  verbatim.
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R59SweepContractTest {

    @Test
    fun feedingValsFileHelpersAndLambdaBodiesAreBoundVerbatim() {
        val app = fileText(
            "dev/cyberdogs/offleash/OffleashApp.kt",
        )
        // The container's feeding declarations that sit
        // OUTSIDE round 58's construction slices.
        assertSliceLines(
            app,
            "private val sessionScope: CoroutineScope =",
            "val client: YoutubeiClient",
            listOf(
                "CoroutineScope(SupervisorJob() + Dispatchers.IO)",
            ),
            "sessionScope declaration",
        )
        assertSliceLines(
            app,
            "val signInDiagnostics: dev.cyberdogs.offleash.data.SignInDiagnostics =",
            "private var sessionManagerRef",
            listOf(
                "dev.cyberdogs.offleash.data.SignInDiagnostics()",
            ),
            "signInDiagnostics declaration",
        )
        assertSliceLines(
            app,
            "val appPreferences: AppPreferences",
            "val downloadStore: DownloadStore =",
            listOf(
                "val appPreferences: AppPreferences = AppPreferences.fromContext(app)",
            ),
            "appPreferences declaration",
        )
        assertSliceLines(
            app,
            "val parityPreferences: dev.cyberdogs.offleash.data.ParityPreferences =",
            "val localPlaylistStore",
            listOf(
                "dev.cyberdogs.offleash.data.ParityPreferences.fromContext(app)",
            ),
            "parityPreferences declaration",
        )
        assertTrue(
            "the shared player must be built by the " +
                "container's own buildPlayer",
            app.contains(
                "val player: ExoPlayer = buildPlayer(app)",
            ),
        )
        // Round 58's stated residual: the multi-line lambda
        // bodies inside the two big constructions, bound
        // verbatim as a layer.
        assertSliceLines(
            app,
            "val downloadManager: DownloadManager = DownloadManager(",
            "val likeController",
            listOf(
                "resolveStream = { track, quality ->",
                "PlaybackResolver(client).resolve(",
                "track.videoId",
                "sessionManager.currentAuthSession()",
                "quality",
                "googleSessionManager.currentBearer()",
                ").info",
            ),
            "downloadManager resolveStream lambda body",
        )
        assertSliceLines(
            app,
            "val queueController: QueueController = QueueController(",
            "var mediaController: MediaController?",
            listOf(
                "revalidateSession = {",
                "if (!googleSessionManager.hasStoredSession() &&",
                "!googleSignInSeenMarker.seen()",
                "sessionManager.validateNow()",
                "sessionManager.currentAuthSession()",
                "qualityProvider = { appPreferences.streamQuality }",
                "localFileProvider = { track -> downloadManager.downloadedPlayback(track.videoId) }",
                "audioVersionInRadioProvider = { appPreferences.audioVersionInRadio }",
                "initialSpeedState = SpeedUiState(",
                "speed = appPreferences.playbackSpeed",
                "preservePitch = appPreferences.keepPitchAtSpeed",
                "onSpeedStateChanged = { state ->",
                "appPreferences.setPlaybackSpeed(state.speed)",
                "appPreferences.setKeepPitchAtSpeed(state.preservePitch)",
            ),
            "queueController lambda bodies",
        )
        // The file helpers upstream of round 58's bound
        // call sites: each helper's body must pair it with
        // its OWN file-name constant (round-59 helper-swap
        // doctor exchanged the first two, GREEN 636/636).
        val sessionStoreKt = dataText("SessionStore.kt")
        assertSliceLines(
            sessionStoreKt,
            "fun sessionFileIn",
            "class SessionStore(",
            listOf(
                "fun sessionFileIn(context: Context): File = File(context.noBackupFilesDir, SESSION_FILE_NAME)",
            ),
            "sessionFileIn helper body",
        )
        val googleTokenKt = dataText("GoogleTokenStore.kt")
        assertTrue(
            "the Google session file name must stay " +
                "google_session.bin (pinned by no test " +
                "before round 59)",
            googleTokenKt.contains(
                "const val GOOGLE_SESSION_FILE_NAME: String = \"google_session.bin\"",
            ),
        )
        assertSliceLines(
            googleTokenKt,
            "fun googleSessionFileIn",
            "class GoogleTokenStore(",
            listOf(
                "fun googleSessionFileIn(context: Context): File =",
                "File(context.noBackupFilesDir, GOOGLE_SESSION_FILE_NAME)",
            ),
            "googleSessionFileIn helper body",
        )
        val pendingGrantKt = dataText("GooglePendingGrantStore.kt")
        assertTrue(
            "the pending-grant file name must stay " +
                "google_pending_grant.bin",
            pendingGrantKt.contains(
                "const val GOOGLE_PENDING_GRANT_FILE_NAME: String = \"google_pending_grant.bin\"",
            ),
        )
        assertSliceLines(
            pendingGrantKt,
            "fun googlePendingGrantFileIn",
            "class GooglePendingGrantStore(",
            listOf(
                "fun googlePendingGrantFileIn(context: Context): File =",
                "File(context.noBackupFilesDir, GOOGLE_PENDING_GRANT_FILE_NAME)",
            ),
            "googlePendingGrantFileIn helper body",
        )
        val seenMarkerKt = dataText("GoogleSignInSeenMarker.kt")
        assertTrue(
            "the seen-marker file name must stay " +
                "google_signin_seen.txt",
            seenMarkerKt.contains(
                "const val GOOGLE_SIGN_IN_SEEN_FILE_NAME: String = \"google_signin_seen.txt\"",
            ),
        )
        assertSliceLines(
            seenMarkerKt,
            "fun googleSignInSeenFileIn",
            "class GoogleSignInSeenMarker(",
            listOf(
                "fun googleSignInSeenFileIn(context: Context): File =",
                "File(context.noBackupFilesDir, GOOGLE_SIGN_IN_SEEN_FILE_NAME)",
            ),
            "googleSignInSeenFileIn helper body",
        )
        val downloadStoreKt = dataText("DownloadStore.kt")
        assertTrue(
            "the download records helper must pair with " +
                "downloads.json",
            downloadStoreKt.contains(
                "fun downloadRecordsFileIn(filesDir: File): File = File(filesDir, \"downloads.json\")",
            ),
        )
        assertTrue(
            "the downloads dir helper must pair with " +
                "the downloads directory",
            downloadStoreKt.contains(
                "fun downloadsDirIn(filesDir: File): File = File(filesDir, \"downloads\")",
            ),
        )
    }

    @Test
    fun manifestVariantPermissionTagsAndAliasComponentsAreCensused() {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(locateManifest())
        val permissionsByTag = mutableMapOf<String, Set<String>>()
        for (tag in listOf(
            "uses-permission",
            "uses-permission-sdk-23",
            "uses-permission-sdk-m",
        )) {
            val names = sortedSetOf<String>()
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                names += androidAttr(
                    nodes.item(i) as Element, "name",
                )
            }
            permissionsByTag[tag] = names
        }
        assertEquals(
            "every permission-carrying tag is censused — " +
                "round 58 pinned only the literal " +
                "uses-permission set, so a grant spelled " +
                "uses-permission-sdk-23 (round-59 doctor: " +
                "READ_EXTERNAL_STORAGE, GREEN; with " +
                "minSdk 26 the sdk-23 variant applies on " +
                "every supported device) or sdk-m moved " +
                "no pin; both variant sets must stay empty " +
                "and the plain set must stay round 58's " +
                "exact seven",
            mapOf(
                "uses-permission" to sortedSetOf(
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                    "android.permission.INTERNET",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                    "android.permission.WAKE_LOCK",
                ),
                "uses-permission-sdk-23" to emptySet<String>(),
                "uses-permission-sdk-m" to emptySet<String>(),
            ),
            permissionsByTag,
        )
        val exportedByComponent = sortedMapOf<String, String>()
        for (tag in listOf(
            "activity", "service", "provider", "receiver",
            "activity-alias",
        )) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                exportedByComponent["$tag:${androidAttr(el, "name")}"] =
                    androidAttr(el, "exported")
            }
        }
        assertEquals(
            "every component-carrying tag is censused, " +
                "activity-alias included — round 58's map " +
                "enumerated four tags, so an exported " +
                "activity-alias (round-59 doctor: a second " +
                "MAIN/LAUNCHER entry point, GREEN) was no " +
                "component at all to the pin; the shipped " +
                "census is exactly the two real components",
            sortedMapOf(
                "activity:.MainActivity" to "true",
                "service:.playback.PlaybackService" to "true",
            ),
            exportedByComponent,
        )
    }

    @Test
    fun nonMainSourceSetsAndShippedFileTreeAreCensusedExactly() {
        val repoRoot = repoRoot()
        fun children(relative: String): List<String> =
            File(repoRoot, relative).listFiles()
                ?.map { it.name }?.sorted()
                ?: throw AssertionError(
                    "source-set directory must exist: $relative",
                )
        assertEquals(
            "the app module's source sets are exactly " +
                "hunt/main/test as shipped — a new set " +
                "(round-59 doctor: src/debug, whose Kotlin " +
                "compiles into the hunt APK's debug " +
                "variants, GREEN across the full suite) " +
                "is a shipped surface no census walked",
            listOf("hunt", "main", "test"),
            children("app/src"),
        )
        assertEquals(
            "the core module's source sets are exactly " +
                "main/test as shipped",
            listOf("main", "test"),
            children("core/src"),
        )
        val shipped = mutableListOf<String>()
        for (moduleSrc in listOf("app/src", "core/src")) {
            File(repoRoot, moduleSrc).walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(repoRoot)
                        .invariantSeparatorsPath
                    if ("/test/" !in "/$relative") {
                        shipped += relative
                    }
                }
        }
        assertEquals(
            "the shipped (non-test) file tree under " +
                "app/src and core/src is pinned exactly — " +
                "every src/main census in the lattice is " +
                "blind to files in the flavor/debug sets " +
                "the hunt APK is actually built from, so " +
                "the file list itself is the census (R49 " +
                "discipline, extended to the whole src " +
                "tree)",
            SHIPPED_FILES,
            shipped.sorted(),
        )
        val huntStrings = File(
            repoRoot, "app/src/hunt/res/values/strings.xml",
        )
        assertTrue(
            "the hunt flavor's strings override must exist",
            huntStrings.isFile,
        )
        assertTrue(
            "the hunt flavor overrides only the app name, " +
                "to OffLeash Hunt",
            huntStrings.readText().contains(
                "<string name=\"app_name\">OffLeash Hunt</string>",
            ),
        )
    }

    // -- helpers ------------------------------------------------------------

    private fun androidAttr(element: Element, localName: String): String {
        val ns = element.getAttributeNS(ANDROID_NS, localName)
        if (ns.isNotEmpty()) return ns
        return element.getAttribute("android:$localName")
    }

    private fun locateManifest(): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/AndroidManifest.xml")
            candidates += File(dir, "app/src/main/AndroidManifest.xml")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "AndroidManifest.xml not found from working dir " +
                    File(".").absolutePath,
            )
    }

    /** The text between two anchors, with both anchors
     *  required to exist in order (round 12's presence-guard
     *  discipline). */
    private fun slice(
        text: String,
        startAnchor: String,
        endAnchor: String,
        label: String,
    ): String {
        val start = text.indexOf(startAnchor)
        assertTrue(
            "$label: start anchor must exist: $startAnchor",
            start >= 0,
        )
        val end = text.indexOf(endAnchor, start)
        assertTrue(
            "$label: end anchor must exist after the start: $endAnchor",
            end > start,
        )
        return text.substring(start, end)
    }

    /** Every expected line must appear, trimmed, as a whole
     *  line inside the named slice — a swap moves a line
     *  OUT of its owner's slice even when the same spelling
     *  survives elsewhere in the file. */
    private fun assertSliceLines(
        text: String,
        startAnchor: String,
        endAnchor: String,
        expectedLines: List<String>,
        label: String,
    ) {
        val body = slice(text, startAnchor, endAnchor, label)
        val lines = body.lines()
            .map { it.trim().trimEnd(',') }
            .toSet()
        for (expected in expectedLines) {
            assertTrue(
                "$label must wire exactly `$expected` — the " +
                    "layer upstream of round 58's bound " +
                    "construction arguments was bound by no " +
                    "test before round 59: the file-helper " +
                    "constant swap ran GREEN across the full " +
                    "636-test suite and the sessionScope " +
                    "dispatcher respell ran GREEN across " +
                    "every OffleashApp-reading contract " +
                    "class, with every round-58 line intact",
                expected.trim().trimEnd(',') in lines,
            )
        }
    }

    private fun fileText(relativePath: String): String {
        val file = File(locateAppJavaRoot(), relativePath)
        assertTrue("source must exist: $relativePath", file.isFile)
        return file.readText()
    }

    private fun dataText(name: String): String =
        fileText("dev/cyberdogs/offleash/data/$name")

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

    private fun repoRoot(): File =
        File(locateAppJavaRoot(), "../../../..").canonicalFile

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        val SHIPPED_FILES: List<String> = listOf(
                "app/src/hunt/res/values/strings.xml",
                "app/src/main/AndroidManifest.xml",
                "app/src/main/java/dev/cyberdogs/offleash/MainActivity.kt",
                "app/src/main/java/dev/cyberdogs/offleash/OffleashApp.kt",
                "app/src/main/java/dev/cyberdogs/offleash/account/AutoDownloadLikedWorker.kt",
                "app/src/main/java/dev/cyberdogs/offleash/account/GoogleSessionKeepAliveWorker.kt",
                "app/src/main/java/dev/cyberdogs/offleash/account/SessionKeepAliveWorker.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/AppPreferences.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/AtomicFileWrite.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/BearerRetryTransport.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/CrashReporter.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/DownloadManager.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/DownloadStore.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/Downloads.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/GooglePendingGrantStore.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/GoogleSessionManager.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/GoogleSignInSeenMarker.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/GoogleTokenStore.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/LikeController.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/OkHttpTransport.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/ParityPreferences.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/RangeInterceptor.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/SecretBox.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/SessionManager.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/SessionStore.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/SignInDiagnostics.kt",
                "app/src/main/java/dev/cyberdogs/offleash/data/Stores.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/BatteryOptimization.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/LikeState.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/LyricsSync.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/NotificationPermission.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/PlaybackRecovery.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/PlaybackService.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/PlaybackSpeed.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/RadioQueue.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/SleepTimer.kt",
                "app/src/main/java/dev/cyberdogs/offleash/playback/SplashGate.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/ErrorMessages.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/OffleashRoot.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/SplashScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/about/AboutScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/account/SignInScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/components/BatteryRestrictionCard.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/components/Common.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/components/TrackMenu.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/home/ChartsScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/home/HomeChrome.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/home/HomeScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/player/PlayerScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt",
                "app/src/main/java/dev/cyberdogs/offleash/ui/theme/Theme.kt",
                "app/src/main/res/drawable-nodpi/cyberdogs_logo.jpg",
                "app/src/main/res/drawable-nodpi/cyberdogs_logo_alpha.png",
                "app/src/main/res/drawable/ic_launcher_foreground.xml",
                "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
                "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
                "app/src/main/res/raw/gpl3.txt",
                "app/src/main/res/values/colors.xml",
                "app/src/main/res/values/strings.xml",
                "app/src/main/res/values/themes.xml",
                "app/src/main/res/xml/backup_rules.xml",
                "app/src/main/res/xml/data_extraction_rules.xml",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/api/HttpTransport.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/api/YoutubeiClient.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/api/YoutubeiContexts.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/auth/AuthSession.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/auth/GoogleAuth.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/auth/SessionCookies.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/error/OffleashException.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/model/Lyrics.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/model/Models.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/model/Pagination.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/parse/JsonNav.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/parse/Parsers.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/playback/PlayQueue.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/playback/PlaybackResolver.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/playback/QualityPreference.kt",
                "core/src/main/kotlin/dev/cyberdogs/offleash/core/playback/StreamResolver.kt",
        )
    }
}
