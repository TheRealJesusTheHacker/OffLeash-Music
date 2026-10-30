package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Round 58 sweep contracts (adversarial lane — breaking the
 * round 57 fixes):
 *
 *  (Charter 1 — FINDING F1) Round 57 binds every
 *  collectAsState RECEIVER expression (`diagnostics.snapshot`
 *  and friends), but the VALUE behind a bound receiver is
 *  wired in [AppContainer] (OffleashApp.kt), and no test
 *  bound the wiring arguments. Proven this round against
 *  the FULL app suite (633/633 GREEN each time, files
 *  restored md5-verified after): (a) the credential-store
 *  file swap — SessionStore built on
 *  `googleSessionFileIn(app)` and GoogleTokenStore built
 *  on `sessionFileIn(app)`, the two same-typed
 *  `(Context) -> File` helpers exchanged at their
 *  construction sites, cross-wiring the cookie session's
 *  sealed file with the Google tokens' sealed file;
 *  (b) the QueueController diagnostics re-point —
 *  `diagnostics = playbackDiagnostics` replaced by a fresh
 *  `PlaybackDiagnostics()`, so the controller's stage log
 *  lands in a recorder no screen reads while every bound
 *  receiver text stays identical; (c) the shared-client
 *  window-store re-point — `buildSharedClient(
 *  playbackDiagnostics, streamWindows)` given a fresh
 *  `StreamWindowStore()`, breaking the probe/player window
 *  parity the container documents as holding "by
 *  construction". The diagnostics class itself takes no
 *  constructor arguments (its snapshot's only inputs are
 *  its own recorded lines, runtime-pinned by
 *  DiagnosticsGrowthTest), and the remaining same-typed
 *  swap candidates in the graph are compile-dead by type
 *  distinctness (buildSharedClient's two parameters;
 *  QueueController's non-suspend sessionProvider vs its
 *  suspend revalidateSession; every ViewModel factory's
 *  session/bearer provider pair, whose return types
 *  differ). The first test therefore binds the wiring
 *  layer itself: the argument lines of every container
 *  construction, sliced per construction (the argument
 *  spellings repeat across constructions, so file-level
 *  presence cannot pair an argument with its owner), plus
 *  every ViewModel factory line in the screens.
 *
 *  (Charter 3a — FINDING F2) The manifest's security
 *  surface was bound only as a subset: the shipped
 *  ManifestPlaybackContractTest requires five permissions
 *  to be PRESENT and the PlaybackService's exported /
 *  foregroundServiceType to hold, and BackupRules pins
 *  the application's two backup-rule attributes. Proven
 *  this round (66/66 contract set GREEN, restored
 *  md5-verified): adding
 *  `android.permission.READ_EXTERNAL_STORAGE` to the
 *  permission set AND flipping MainActivity's
 *  `android:exported` to false moved no pin. The second
 *  test pins the security surface exactly: the
 *  uses-permission set verbatim, the component set with
 *  every `android:exported` value, and the service's
 *  foregroundServiceType as an exact value. Cosmetic
 *  attributes (label, icon, theme) stay unpinned by
 *  design, so a cosmetic manifest edit does not churn
 *  this pin.
 *
 *  (Charter 3b — FINDING F3) Round 57 hash-pinned the
 *  wrapper JAR, but the wrapper SCRIPTS are what invoke
 *  it: a doctored `gradlew` could bypass the jar
 *  entirely. Proven this round (66/66 GREEN, restored
 *  md5-verified): appending a line to `gradlew` moved no
 *  pin — no test read either script. The third test
 *  hash-pins both scripts the same way round 57 pinned
 *  the jar.
 *
 *  Charter 2 is a dismissal with evidence, recorded in
 *  the lane report: `emptyFlow(` is already censused at
 *  zero module-wide by round 57's remainder census (its
 *  doctor fails exactly that test), and the one
 *  compilable asStateFlow backing swap (the two Boolean
 *  preference flows in AppPreferences /
 *  ParityPreferences — the only same-typed backing pair
 *  behind a collected property; the reverse direction is
 *  a compile-dead forward reference) fails three runtime
 *  round-trip tests. No test is added for a dismissed
 *  charter.
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R58SweepContractTest {

    @Test
    fun containerAndViewModelWiringArgumentsAreBoundVerbatim() {
        val app = fileText(
            "dev/cyberdogs/offleash/OffleashApp.kt",
        )
        // The two diagnostics recorders: one shared instance
        // each, constructed with no arguments.
        assertSliceLines(
            app,
            "val playbackDiagnostics: PlaybackDiagnostics",
            "val signInDiagnostics",
            listOf(
                "val playbackDiagnostics: PlaybackDiagnostics = PlaybackDiagnostics()",
            ),
            "playbackDiagnostics declaration",
        )
        assertEquals(
            "OffleashApp.kt must construct exactly one " +
                "PlaybackDiagnostics — the container's shared " +
                "recorder; a fresh instance at any wiring site " +
                "(the round-58 re-point doctor) is a second " +
                "construction here",
            1,
            occurrences(app, "PlaybackDiagnostics()"),
        )
        // The three sealed stores: the file helper paired
        // with its store is the wiring (round-58 swap
        // doctor (a) exchanged the first two, GREEN).
        assertSliceLines(
            app,
            "val sessionStore: SessionStore = SessionStore(",
            "val googleTokenStore",
            listOf(
                "storage = FileSecretStorage(sessionFileIn(app))",
                // 0.8.1 hotfix re-base: the box now takes the key
                // provider AND the scoped key regenerator by name —
                // the seal-recovery wiring every sealed store shares.
                "box = AesGcmSecretBox(",
                "keyProvider = { KeystoreSecretKey.getOrCreate(SESSION_KEY_ALIAS) }",
                "keyRegenerator = { KeystoreSecretKey.regenerate(SESSION_KEY_ALIAS) }",
            ),
            "sessionStore construction",
        )
        assertSliceLines(
            app,
            "val googleTokenStore: GoogleTokenStore = GoogleTokenStore(",
            "val googlePendingGrantStore",
            listOf(
                "storage = FileSecretStorage(googleSessionFileIn(app))",
                // 0.8.1 hotfix re-base: same recovery wiring as the
                // session store above.
                "box = AesGcmSecretBox(",
                "keyProvider = { KeystoreSecretKey.getOrCreate(SESSION_KEY_ALIAS) }",
                "keyRegenerator = { KeystoreSecretKey.regenerate(SESSION_KEY_ALIAS) }",
            ),
            "googleTokenStore construction",
        )
        assertSliceLines(
            app,
            "GooglePendingGrantStore(",
            "val googleSignInSeenMarker",
            listOf(
                "storage = FileSecretStorage(googlePendingGrantFileIn(app))",
            ),
            "googlePendingGrantStore construction",
        )
        assertSliceLines(
            app,
            "val googleSignInSeenMarker: GoogleSignInSeenMarker =",
            "val streamWindows",
            listOf(
                "GoogleSignInSeenMarker(googleSignInSeenFileIn(app))",
            ),
            "googleSignInSeenMarker construction",
        )
        // The shared client: the SAME diagnostics recorder
        // and the SAME window store the QueueController
        // reads (round-58 re-point doctor (c)).
        assertSliceLines(
            app,
            "val streamWindows: StreamWindowStore",
            "val okHttpClient",
            listOf(
                "val streamWindows: StreamWindowStore = StreamWindowStore()",
            ),
            "streamWindows declaration",
        )
        assertTrue(
            "the shared client must be built from the " +
                "container's own diagnostics and window store",
            app.contains(
                "val okHttpClient: OkHttpClient = " +
                    "buildSharedClient(playbackDiagnostics, streamWindows)",
            ),
        )
        // The core client: the shared OkHttp client plus the
        // cookie-rotation callback through the late-bound
        // session-manager reference.
        assertSliceLines(
            app,
            "val client: YoutubeiClient = YoutubeiClient(",
            "val sessionManager",
            listOf(
                "OkHttpTransport(",
                "okHttpClient,",
                "onResponseCookies = { pairs -> sessionManagerRef?.onResponseCookies(pairs) }",
            ),
            "client construction",
        )
        assertSliceLines(
            app,
            "val sessionManager: SessionManager = SessionManager(",
            "val googleAuth",
            listOf(
                "store = sessionStore,",
                "client = client,",
                "scope = sessionScope,",
                "signInDiagnostics = signInDiagnostics,",
            ),
            "sessionManager construction",
        )
        // The Google OAuth transport deliberately carries NO
        // cookie callback (token material never enters the
        // cookie path) — pinned as an absence inside its
        // own slice.
        val googleAuthSlice = slice(
            app,
            "val googleAuth: GoogleDeviceAuth = GoogleDeviceAuth(",
            "val googleSessionManager",
            "googleAuth construction",
        )
        assertTrue(
            "googleAuth must ride a bare OkHttpTransport " +
                "over the shared client",
            googleAuthSlice.contains("OkHttpTransport(okHttpClient),"),
        )
        assertFalse(
            "googleAuth's transport must carry no cookie " +
                "callback — OAuth responses never enter the " +
                "cookie-rotation path",
            googleAuthSlice.contains("onResponseCookies"),
        )
        assertSliceLines(
            app,
            "val googleSessionManager: GoogleSessionManager = GoogleSessionManager(",
            "private val _accountUiState",
            listOf(
                "store = googleTokenStore,",
                "auth = googleAuth,",
                "client = client,",
                "scope = sessionScope,",
                "pendingGrantStore = googlePendingGrantStore,",
                "signInDiagnostics = signInDiagnostics,",
            ),
            "googleSessionManager construction",
        )
        assertSliceLines(
            app,
            "val downloadStore: DownloadStore =",
            "val downloadManager: DownloadManager = DownloadManager(",
            listOf(
                "DownloadStore(downloadRecordsFileIn(app.filesDir))",
            ),
            "downloadStore construction",
        )
        assertSliceLines(
            app,
            "val downloadManager: DownloadManager = DownloadManager(",
            "val likeController",
            listOf(
                "store = downloadStore,",
                "downloadsDir = downloadsDirIn(app.filesDir),",
                "fetcher = OkHttpChunkFetcher(okHttpClient),",
                "qualityProvider = { appPreferences.effectiveDownloadQuality() }",
            ),
            "downloadManager construction",
        )
        assertSliceLines(
            app,
            "dev.cyberdogs.offleash.data.LikeController(",
            "init {",
            listOf(
                "client = client,",
                "preferences = appPreferences,",
                "sessionProvider = { sessionManager.currentAuthSession() },",
                "bearerProvider = { googleSessionManager.currentBearer() },",
                "bearerSessionPresent = { googleSessionManager.hasStoredSession() }",
                "scope = sessionScope,",
            ),
            "likeController construction",
        )
        assertSliceLines(
            app,
            "val localPlaylistStore: LocalPlaylistStore =",
            "val player: ExoPlayer",
            listOf(
                "LocalPlaylistStore(File(app.filesDir, \"playlists.json\"))",
                "RecentsStore(File(app.filesDir, \"recents.json\"))",
            ),
            "playlist/recents store constructions",
        )
        // The QueueController: the shared player, the
        // container's diagnostics recorder (round-58
        // re-point doctor (b)), and the resolve-time
        // providers paired with their sources.
        assertSliceLines(
            app,
            "val queueController: QueueController = QueueController(",
            "var mediaController: MediaController?",
            listOf(
                "context = app,",
                "client = client,",
                "player = player,",
                "recentsStore = recentsStore,",
                "diagnostics = playbackDiagnostics,",
                "sessionProvider = { sessionManager.currentAuthSession() },",
                "bearerProvider = { googleSessionManager.currentBearer() },",
                "probeClient = okHttpClient,",
                "streamWindows = streamWindows,",
            ),
            "queueController construction",
        )
        // The ViewModel factories in the screens: each
        // factory's construction line(s) verbatim. Every
        // factory takes the container's one client; the
        // two provider-carrying factories pair their
        // providers with the session manager / the
        // container bearer by name.
        val detail = fileText(
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
        )
        assertTrue(
            "the Album factory must build its ViewModel " +
                "from the container client and its browseId",
            detail.contains(
                "viewModel(key = browseId) { AlbumViewModel(container.client, browseId) }",
            ),
        )
        assertTrue(
            "the Artist factory must build its ViewModel " +
                "from the container client and its browseId",
            detail.contains(
                "viewModel(key = browseId) { ArtistViewModel(container.client, browseId) }",
            ),
        )
        assertSliceLines(
            detail,
            "val viewModel: PlaylistViewModel = viewModel(",
            "val state by viewModel.state.collectAsState()",
            listOf(
                "PlaylistViewModel(",
                "container.client,",
                "browseId,",
                "params,",
                "authProvider = { container.sessionManager.currentAuthSession() }",
                "bearerProvider = { container.currentBearerToken() }",
            ),
            "PlaylistViewModel factory",
        )
        val charts = fileText(
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt",
        )
        assertTrue(
            "the Charts factory must build its ViewModel " +
                "from the container client",
            charts.contains(
                "viewModel { ChartsViewModel(container.client) }",
            ),
        )
        val home = fileText(
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt",
        )
        assertSliceLines(
            home,
            "val viewModel: HomeViewModel = viewModel {",
            "val state by viewModel.state.collectAsState()",
            listOf(
                "HomeViewModel(",
                "container.client,",
                "sessionProvider = { container.sessionManager.currentAuthSession() }",
                "bearerProvider = { container.currentBearerToken() }",
            ),
            "HomeViewModel factory",
        )
        val moods = fileText(
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
        )
        assertTrue(
            "the Moods factory must build its ViewModel " +
                "from the container client",
            moods.contains(
                "viewModel { MoodsViewModel(container.client) }",
            ),
        )
        assertTrue(
            "the MoodPlaylists factory must build its " +
                "ViewModel from the container client and " +
                "the screen's category",
            moods.contains(
                "viewModel(key = params) { MoodPlaylistsViewModel(container.client, category) }",
            ),
        )
        val search = fileText(
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
        )
        assertTrue(
            "the Search factory must build its ViewModel " +
                "from the container client",
            search.contains(
                "viewModel { SearchViewModel(container.client) }",
            ),
        )
    }

    @Test
    fun manifestSecuritySurfaceIsPinnedExactly() {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(locateManifest())
        val permissions = sortedSetOf<String>()
        val permissionNodes = doc.getElementsByTagName("uses-permission")
        for (i in 0 until permissionNodes.length) {
            permissions += androidAttr(
                permissionNodes.item(i) as Element, "name",
            )
        }
        assertEquals(
            "the uses-permission set is pinned verbatim — " +
                "the shipped contract tests require five " +
                "permissions to be present but bound the set " +
                "only from below, so an added permission " +
                "(round-58 doctor: READ_EXTERNAL_STORAGE, " +
                "GREEN) or a removed one outside the " +
                "required five moved no pin; the set is the " +
                "security surface and is now exact",
            sortedSetOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                "android.permission.WAKE_LOCK",
            ),
            permissions,
        )
        val exportedByComponent = sortedMapOf<String, String>()
        var serviceType: String? = null
        for (tag in listOf("activity", "service", "provider", "receiver")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                val name = androidAttr(el, "name")
                exportedByComponent["$tag:$name"] =
                    androidAttr(el, "exported")
                if (tag == "service") {
                    serviceType = androidAttr(el, "foregroundServiceType")
                }
            }
        }
        assertEquals(
            "every component and its android:exported value " +
                "is pinned — the shipped tests bound only " +
                "the PlaybackService's exported flag, so " +
                "flipping MainActivity's exported to false " +
                "(round-58 doctor, GREEN) or adding any new " +
                "component moved no pin; the component set " +
                "is the manifest's exposure surface",
            sortedMapOf(
                "activity:.MainActivity" to "true",
                "service:.playback.PlaybackService" to "true",
            ),
            exportedByComponent,
        )
        assertEquals(
            "the PlaybackService foregroundServiceType is " +
                "pinned as an exact value (the shipped test " +
                "accepts any value CONTAINING mediaPlayback, " +
                "so an added type would pass it)",
            "mediaPlayback",
            serviceType,
        )
    }

    @Test
    fun wrapperScriptsArePinnedByHash() {
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
            "gradlew's SHA-256 is pinned (3,526 bytes) — " +
                "round 57 pinned the wrapper JAR the script " +
                "invokes, but not the script itself, so a " +
                "doctored gradlew bypassing the jar entirely " +
                "(round-58 doctor: one appended line, GREEN) " +
                "moved no pin; the script is part of the " +
                "same supply-chain surface as the jar",
            "4372e158fe4d10c58c50b37da4d7e170a024c566a1637d8a99aa73bbbbf21aaa",
            sha256(repoFile("gradlew")),
        )
        assertEquals(
            "gradlew.bat's SHA-256 is pinned (2,426 " +
                "bytes) — the Windows twin of the same " +
                "surface, pinned for the same reason",
            "3c77452540f52e00d5347a79ed704fd3000a45d5f7dc215d1457cba07a00b6ee",
            sha256(repoFile("gradlew.bat")),
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
     *  discipline: a missing anchor fails here, never falls
     *  back to the whole file). */
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
     *  line inside the named construction's slice — a swap
     *  moves a line OUT of its owner's slice even when the
     *  same spelling survives elsewhere in the file. */
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
                    "container's construction arguments were " +
                    "bound by no test before round 58: the " +
                    "credential-store file swap, the " +
                    "diagnostics re-point and the window-store " +
                    "re-point each ran GREEN across the full " +
                    "suite this round with every round-57 " +
                    "receiver binding intact, because only " +
                    "the DATA behind the bound receivers had " +
                    "moved",
                expected.trim().trimEnd(',') in lines,
            )
        }
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    private fun fileText(relativePath: String): String {
        val file = File(locateAppJavaRoot(), relativePath)
        assertTrue("source must exist: $relativePath", file.isFile)
        return file.readText()
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

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
