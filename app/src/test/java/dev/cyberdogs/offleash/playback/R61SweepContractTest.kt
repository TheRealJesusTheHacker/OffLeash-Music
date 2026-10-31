package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 61 sweep contracts (adversarial lane — breaking the
 * round-60 fixes):
 *
 *  Charter 1 (F1–F4 slices): round 60 bound the pending-grant
 *  box, the buildPlayer body, the media-controller token lines
 *  and the show-player birth as PRESENCE-in-slice — which, as
 *  round 60 itself disclosed, cannot see an ADDED line inside
 *  a slice. Each slice fell this round to an additive doctor
 *  that left every bound line byte-identical and ran GREEN
 *  across the full 642-test app suite:
 *   - F1 slice: an `init { googlePendingGrantStore.wipe() }`
 *     block inserted inside the slice (after the store's own
 *     construction, before the marker val) wipes every resumed
 *     pending grant at each container build.
 *   - F2 slice: a second `.setWakeMode(C.WAKE_MODE_LOCAL)`
 *     Builder call inserted in the ExoPlayer chain after the
 *     bound NETWORK line — last setter wins, so the player
 *     loses network wake behaviour.
 *   - F3 slice: a second `future.addListener(...)` inserted
 *     after the bound Builder line, nulling the connected
 *     controller the shipped listener just stored.
 *   - F4 slice: an `init { requestShowPlayer() }` block
 *     inserted inside the slice raises the counter at
 *     construction — the shipped birth-0 doctor (round 60's
 *     own F4 effect) by addition instead of respelling.
 *  The first test therefore binds each of the four slices as
 *  its EXACT trimmed non-blank line list (order + content +
 *  count) — the F5 verbatim-block discipline applied to the
 *  whole slice, so any inserted, removed or reordered line
 *  fails it.
 *
 *  Charter 2 (branch-swap siblings of F5):
 *   - (F6) LibraryScreen's account-library selection: the
 *     Home leg is bound verbatim (round 30 — this round's
 *     Home swap doctor fails exactly that pin), but the
 *     Library `when` stood unbound: gating the bearer leg
 *     with `&& currentAuthSession() == null` (the cookie
 *     road then wins whenever both sessions exist) ran
 *     GREEN 642/642. The same gating at the Detail
 *     PlaylistViewModel's load leg (authProvider() == null)
 *     ran GREEN too. Both bound verbatim below.
 *   - (F7) The session-health label branches: swapping the
 *     HEALTHY / NEEDS_SIGN_IN results in
 *     PlaybackDiagnosticsScreen ran GREEN 642/642 (every
 *     label string stays present in the file). The
 *     diagnostics block is bound verbatim below; its About
 *     and Settings twins are bound by per-branch segment
 *     assertions (each health name's segment must carry its
 *     own label and not the other's).
 *   - (F8) The playback resolver's downloaded-first leg in
 *     QueueController.resolveAndPlay: swapping the two legs
 *     of `val local = if (forceStream) ... else ...` (the
 *     local lookup then runs only when a stream is being
 *     FORCED, and a DONE download is streamed in every
 *     normal resolve) ran GREEN 642/642 — the bound
 *     `if (forceStream)` presence pin cannot see which leg
 *     holds the lookup. Bound verbatim below. (The core
 *     PlaybackResolver's token-first ordering and the pure
 *     downloadedPlaybackFor DONE gate are runtime-pinned —
 *     PlaybackResolverOAuthTest / DownloadsTest — and this
 *     round's census found no unbound leg there.)
 *   - (F9) DownloadManager's row presentation
 *     (downloadStatusText): swapping the QUEUED and FAILED
 *     results ran GREEN 642/642. Bound verbatim below.
 *
 *  Charter 3 (F10, the birth-value family): every
 *  MutableStateFlow declaration in both modules was
 *  censused. The session/phase births are runtime-pinned
 *  (this round's doctors — Google phase born Failed, both
 *  managers' uiState born signed-in — each FAIL fresh-
 *  manager/totality tests), and the account/show-player
 *  births are bound by round 60. The SCREEN and QUEUE
 *  births stood unbound: Home born UiState.Error, Search
 *  born Loading instead of Idle, Album born UiState.Error,
 *  Search _hasMore born true, and the queue's playback
 *  state born isPlaying = true each ran GREEN 642/642.
 *  The last test binds every behaviour-bearing birth in
 *  both modules as one census table (file -> exact
 *  initializer text). No MutableSharedFlow declaration
 *  exists in either module (round 51's census holds).
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R61SweepContractTest {

    @Test
    fun round60SlicesHoldExactlyTheirShippedLines() {
        val app = fileText("dev/cyberdogs/offleash/OffleashApp.kt")
        assertSliceExactLines(
            app, "GooglePendingGrantStore(", "val googleSignInSeenMarker",
            listOf(
                "GooglePendingGrantStore(",
                "storage = FileSecretStorage(googlePendingGrantFileIn(app)),",
                // 0.8.1 hotfix re-base: the box slice now holds the
                // named provider + regenerator lines (seal recovery).
                "box = AesGcmSecretBox(",
                "keyProvider = { KeystoreSecretKey.getOrCreate(SESSION_KEY_ALIAS) },",
                "keyRegenerator = { KeystoreSecretKey.regenerate(SESSION_KEY_ALIAS) },",
                "),",
                ")",
                "/**",
                "* The \"a Google sign-in has happened here\" marker (bug sweep",
                "* round 1): once set, the legacy cookie session never drives",
                "* the auto-download worker's decisions — see",
                "* [GoogleSignInSeenMarker]. (Its original resurrection watch —",
                "* the presented account state — is moot since 0.8.3: the",
                "* session manager discards any stored legacy session at",
                "* construction, so there is nothing left to resurrect.)",
                "*/"
            ),
            "F1 pending-grant slice",
        )
        assertSliceExactLines(
            app, "private fun buildMediaController", "private fun buildPlayer",
            listOf(
                "private fun buildMediaController(app: OffleashApp): ListenableFuture<MediaController>? {",
                "return try {",
                "val token = SessionToken(",
                "app,",
                "ComponentName(app, PlaybackService::class.java),",
                ")",
                "val future = MediaController.Builder(app, token).buildAsync()",
                "future.addListener(",
                "{",
                "try {",
                "mediaController = future.get()",
                "playbackDiagnostics.log(\"controller\", \"MediaController connected\")",
                "} catch (e: Exception) {",
                "playbackDiagnostics.log(",
                "\"controller\",",
                "\"MediaController connection failed (\${e.javaClass.simpleName})\",",
                ")",
                "}",
                "},",
                "ContextCompat.getMainExecutor(app),",
                ")",
                "future",
                "} catch (e: Exception) {",
                "playbackDiagnostics.log(",
                "\"controller\",",
                "\"MediaController build failed (\${e.javaClass.simpleName})\",",
                ")",
                "null",
                "}",
                "}"
            ),
            "F3 buildMediaController slice",
        )
        assertSliceExactLines(
            app, "private val _showPlayerRequests", "fun requestShowPlayer()",
            listOf(
                "private val _showPlayerRequests =",
                "kotlinx.coroutines.flow.MutableStateFlow(0)",
                "val showPlayerRequests: kotlinx.coroutines.flow.StateFlow<Int> =",
                "_showPlayerRequests"
            ),
            "F4 show-player slice",
        )
        assertSliceExactLines(
            app, "private fun buildPlayer", "private companion object",
            listOf(
                "private fun buildPlayer(app: OffleashApp): ExoPlayer {",
                "// Replay cache: 512 MB LRU SimpleCache in the app cache dir, wrapped",
                "// by CacheDataSource so replays and seeks hit local bytes first.",
                "// This is a replay accelerator only — no download feature (SPEC §4).",
                "val cache = SimpleCache(",
                "File(app.cacheDir, \"replay-cache\"),",
                "LeastRecentlyUsedCacheEvictor(REPLAY_CACHE_BYTES),",
                "StandaloneDatabaseProvider(app),",
                ")",
                "// Stream fetches go through the shared OkHttp stack (SPEC §4):",
                "// the same client that resolved the URLs fetches them, so the",
                "// network identity matches. OkHttpDataSource replaces",
                "// DefaultHttpDataSource (HttpURLConnection) for http(s); local /",
                "// file sources still go through DefaultDataSource below.",
                "// The factory deliberately sets NO User-Agent: the fetch rides",
                "// OkHttp's default UA, exactly like the ladder probe and the",
                "// diagnostics test fetch. The 2026-10-24 hunt proved the",
                "// pairing matters — with an iOS app UA set here, the player",
                "// was refused 403 on URLs the probe (default UA) had just been",
                "// served 206 on. The resolver's iOS context UA is a separate",
                "// thing: it belongs to the youtubei requests in :core, not to",
                "// stream fetches. PlayerFetchShapeContractTest pins the",
                "// absence of a UA override here.",
                "val httpFactory = OkHttpDataSource.Factory(okHttpClient)",
                "// DefaultDataSource routes http(s) to the OkHttp factory above",
                "// and keeps local/content schemes on their own sources.",
                "val upstreamFactory = DefaultDataSource.Factory(app, httpFactory)",
                "val cacheFactory = CacheDataSource.Factory()",
                ".setCache(cache)",
                ".setUpstreamDataSourceFactory(upstreamFactory)",
                ".setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)",
                "val mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory)",
                "val audioAttributes = AudioAttributes.Builder()",
                ".setUsage(C.USAGE_MEDIA)",
                ".setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)",
                ".build()",
                "return ExoPlayer.Builder(app)",
                ".setMediaSourceFactory(mediaSourceFactory)",
                ".setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)",
                ".setHandleAudioBecomingNoisy(true)",
                ".setWakeMode(C.WAKE_MODE_NETWORK)",
                ".build()",
                "}"
            ),
            "F2 buildPlayer slice",
        )
    }

    @Test
    fun librarySelectionAndDownloadStatusBranchesAreBoundVerbatim() {
        val library = fileText(
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt",
        )
        val detail = fileText(
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
        )
        assertTrue(
            "the account library must take the token road " +
                "whenever a bearer exists, and the cookie " +
                "road only as its else — gating the bearer " +
                "leg on the cookie session's absence (round " +
                "61 F6 doctor, GREEN across the full suite) " +
                "lets the dormant cookie road win whenever " +
                "both sessions exist",
            library.contains(
                "                when {\n" +
                    "                    bearer != null -> container.client.libraryPlaylistsOAuth(bearer, delegation = container.currentBrowseDelegation())\n" +
                    "                    else -> container.sessionManager.currentAuthSession()\n" +
                    "                        ?.let { container.client.libraryPlaylists(it) }\n" +
                    "                }\n"
            ),
        )

        assertTrue(
            "the playlist page must take the token road " +
                "whenever a bearer exists, on the first page " +
                "and on continuations — gating the bearer " +
                "leg on the cookie session's absence (round " +
                "61 F6 doctor at the Detail sibling, GREEN " +
                "across the full suite) lets the cookie road " +
                "win whenever both sessions exist",
            detail.contains(
                "                val page = if (bearer != null) {\n" +
                    "                    try {\n" +
                    "                        client.playlistOAuth(\n" +
                    "                            bearer, browseId, params, delegationProvider(),\n" +
                    "                        )\n" +
                    "                    } catch (e: Exception) {\n" +
                    "                        if (e is kotlinx.coroutines.CancellationException) throw e\n" +
                    "                        // Degradation (0.8.2): the token road\n" +
                    "                        // refused — the cookie/anonymous road still\n" +
                    "                        // serves PUBLIC playlists, so a refused\n" +
                    "                        // account must not blank a page the public\n" +
                    "                        // can open. One dishonest shape is guarded:\n" +
                    "                        // the anonymous Liked page (VLLM) is an\n" +
                    "                        // empty shell (200, zero tracks), so an\n" +
                    "                        // empty VLLM fallback rethrows the token\n" +
                    "                        // road's refusal instead of presenting a\n" +
                    "                        // fake-empty Liked list.\n" +
                    "                        val fallback = client.playlist(\n" +
                    "                            browseId, params, auth = authProvider(),\n" +
                    "                        )\n" +
                    "                        if (browseId == \"VLLM\" && fallback.tracks.isEmpty()) throw e\n" +
                    "                        fallback\n" +
                    "                    }\n" +
                    "                } else {\n" +
                    "                    client.playlist(browseId, params, auth = authProvider())\n" +
                    "                }\n"
            ),
        )
        assertTrue(
            "the playlist continuation must keep the same " +
                "token-first selection as the first page",
            detail.contains(
                "                val chunk = if (bearer != null) {\n" +
                    "                    client.playlistContinuationOAuth(bearer, token, delegationProvider())\n" +
                    "                } else {\n" +
                    "                    client.playlistContinuation(token, auth = authProvider())\n" +
                    "                }\n"
            ),
        )
        assertTrue(
            "each download state must render its own status " +
                "line — swapping the QUEUED and FAILED " +
                "results (round 61 F9 doctor, GREEN across " +
                "the full suite) tells the owner a queued " +
                "download failed and a failed one is waiting",
            library.contains(
                "private fun downloadStatusText(record: DownloadRecord, fileLength: Long?): String =\n" +
                    "    when (record.state) {\n" +
                    "        DownloadState.QUEUED -> \"Waiting to download\"\n" +
                    "        DownloadState.DOWNLOADING -> {\n" +
                    "            val percent = downloadProgressPercent(record)\n" +
                    "            if (percent != null) {\n" +
                    "                \"Downloading… \$percent%\"\n" +
                    "            } else {\n" +
                    "                \"Downloading…\"\n" +
                    "            }\n" +
                    "        }\n" +
                    "        DownloadState.DONE ->\n" +
                    "            \"Downloaded • \${formatDownloadBytes(fileLength ?: record.totalBytes ?: 0L)}\"\n" +
                    "        DownloadState.FAILED ->\n" +
                    "            \"Download failed: \${record.failureReason ?: \"something went wrong\"}\"\n" +
                    "    }\n"
            ),
        )
    }

    @Test
    fun healthLabelBranchesAreBoundPerBranch() {
        val diagnostics = fileText(
            "dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt",
        )
        assertTrue(
            "the diagnostics health labels must stay on " +
                "their own health names — swapping the " +
                "HEALTHY and NEEDS_SIGN_IN results (round 61 " +
                "F7 doctor, GREEN across the full suite) " +
                "reports a healthy session as needing " +
                "sign-in and a dead one as healthy",
            diagnostics.contains(
                "    val accountHealthLine = when (sessionState.health.name) {\n" +
                    "        \"HEALTHY\" -> \"Healthy\"\n" +
                    "        \"NEEDS_SIGN_IN\" -> \"Sign in again\"\n" +
                    "        // UNKNOWN also means \"the last check couldn't reach Google\"\n" +
                    "        // and persists until the next one — a present-tense checking\n" +
                    "        // label claimed an activity that had already ended\n" +
                    "        // (chaos sweep round 2).\n" +
                    "        else -> \"Not confirmed\"\n" +
                    "    }\n"
            ),
        )
        for ((label, path) in listOf(
            "About" to "dev/cyberdogs/offleash/ui/about/AboutScreen.kt",
            "Settings" to "dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt",
        )) {
            val text = fileText(path)
            val healthyAt = text.indexOf("\"HEALTHY\" ->")
            val healthyLabelAt = text.indexOf("\"Session: healthy\"")
            val needsAt = text.indexOf("\"NEEDS_SIGN_IN\" ->")
            val needsLabelAt = text.indexOf("\"Session: sign in again\"")
            val elseLabelAt = text.indexOf("\"Session: not confirmed yet\"")
            assertTrue("$label: every health branch must exist", healthyAt >= 0 && needsAt > healthyAt && elseLabelAt > needsAt)
            assertTrue(
                "$label: the healthy label must sit inside " +
                    "the HEALTHY branch, before NEEDS_SIGN_IN",
                healthyLabelAt > healthyAt && healthyLabelAt < needsAt,
            )
            assertTrue(
                "$label: the sign-in-again label must sit " +
                    "inside the NEEDS_SIGN_IN branch, before " +
                    "the else label",
                needsLabelAt > needsAt && needsLabelAt < elseLabelAt,
            )
            assertFalse(
                "$label: the HEALTHY branch must not carry " +
                    "the sign-in-again label",
                text.substring(healthyAt, needsAt)
                    .contains("\"Session: sign in again\""),
            )
        }
    }

    @Test
    fun downloadedFirstLegOfTheResolveIsBoundVerbatim() {
        val controller = fileText(
            "dev/cyberdogs/offleash/playback/QueueController.kt",
        )
        assertTrue(
            "a normal resolve must consult the local " +
                "download BEFORE any network road, and a " +
                "forced stream must skip it — swapping the " +
                "two legs of the forceStream branch (round " +
                "61 F8 doctor, GREEN across the full suite) " +
                "streams every DONE download and re-consults " +
                "the just-failed local file on the fallback",
            controller.contains(
                "            val local = if (forceStream) {\n" +
                    "                null\n" +
                    "            } else {\n" +
                    "                try {\n" +
                    "                    localFileProvider(track)\n" +
                    "                } catch (e: Exception) {\n" +
                    "                    // A broken download lookup must not break playback:\n" +
                    "                    // fall through to the network roads.\n" +
                    "                    diagnostics.log(\n" +
                    "                        \"download\",\n" +
                    "                        \"local download lookup failed (\${e.javaClass.simpleName}) — using the network\",\n" +
                    "                    )\n" +
                    "                    null\n" +
                    "                }\n" +
                    "            }\n"
            ),
        )
    }

    @Test
    fun behaviorBearingFlowBirthsAreCensusedExactly() {
        val births = listOf(
                "dev/cyberdogs/offleash/OffleashApp.kt" to "private val _accountUiState = MutableStateFlow(accountUiStateSnapshot())",
                "dev/cyberdogs/offleash/OffleashApp.kt" to "kotlinx.coroutines.flow.MutableStateFlow(0)",
                "dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to "private val _uiState = MutableStateFlow(SessionUiState(signedIn = false))",
                "dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to "private val _phase = MutableStateFlow<GoogleSignInPhase>(GoogleSignInPhase.Idle)",
                "dev/cyberdogs/offleash/data/SessionManager.kt" to "private val _uiState = MutableStateFlow(SessionUiState(signedIn = false))",
                "dev/cyberdogs/offleash/data/LikeController.kt" to "private val _accountBacked = MutableStateFlow(sessionProvider() != null)",
                "dev/cyberdogs/offleash/data/LikeController.kt" to "private val _error = MutableStateFlow<String?>(null)",
                "dev/cyberdogs/offleash/data/LikeController.kt" to "private val _states = MutableStateFlow<Map<String, LikeUiState>>(",
                "dev/cyberdogs/offleash/data/DownloadStore.kt" to "private val _records = MutableStateFlow<List<DownloadRecord>>(loadFromDisk())",
                "dev/cyberdogs/offleash/data/AppPreferences.kt" to "private val _streamQualityFlow = MutableStateFlow(readStreamQuality())",
                "dev/cyberdogs/offleash/data/AppPreferences.kt" to "private val _audioVersionInRadioFlow = MutableStateFlow(readAudioVersionInRadio())",
                "dev/cyberdogs/offleash/data/AppPreferences.kt" to "private val _downloadQualityFlow = MutableStateFlow(readDownloadQuality())",
                "dev/cyberdogs/offleash/data/AppPreferences.kt" to "MutableStateFlow(readBoolean(KEY_AUTO_DOWNLOAD_LIKED, DEFAULT_AUTO_DOWNLOAD_LIKED))",
                "dev/cyberdogs/offleash/data/ParityPreferences.kt" to "MutableStateFlow(readDownloadQuality())",
                "dev/cyberdogs/offleash/data/ParityPreferences.kt" to "MutableStateFlow(readBoolean(KEY_AUTO_DOWNLOAD_LIKED, DEFAULT_AUTO_DOWNLOAD_LIKED))",
                "dev/cyberdogs/offleash/data/ParityPreferences.kt" to "MutableStateFlow(readBoolean(KEY_AUDIO_VERSION_IN_RADIO, DEFAULT_AUDIO_VERSION_IN_RADIO))",
                "dev/cyberdogs/offleash/data/SignInDiagnostics.kt" to "private val _snapshot = MutableStateFlow<List<String>>(emptyList())",
                "dev/cyberdogs/offleash/data/Stores.kt" to "private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())",
                "dev/cyberdogs/offleash/data/Stores.kt" to "private val _recents = MutableStateFlow<List<Track>>(emptyList())",
                "dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt" to "private val _snapshot = MutableStateFlow<List<String>>(emptyList())",
                "dev/cyberdogs/offleash/playback/QueueController.kt" to "private val _queueState = MutableStateFlow(QueueUiState())",
                "dev/cyberdogs/offleash/playback/QueueController.kt" to "private val _playbackState = MutableStateFlow(PlaybackUiState())",
                "dev/cyberdogs/offleash/playback/QueueController.kt" to "private val _namedError = MutableStateFlow<String?>(null)",
                "dev/cyberdogs/offleash/playback/QueueController.kt" to "private val _sleepTimerState = MutableStateFlow(SleepTimerState())",
                "dev/cyberdogs/offleash/playback/QueueController.kt" to "private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)",
                "dev/cyberdogs/offleash/playback/QueueController.kt" to "private val _speedState = MutableStateFlow(initialSpeedState)",
                "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to "private val _state = MutableStateFlow<UiState<HomeFeed>>(UiState.Loading)",
                "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to "private val _selectedChip = MutableStateFlow<HomeChip?>(null)",
                "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to "private val _state = MutableStateFlow<UiState<List<Section>>>(UiState.Loading)",
                "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to "private val _state = MutableStateFlow<UiState<List<MoodCategory>>>(UiState.Loading)",
                "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to "private val _state = MutableStateFlow<UiState<List<Section>>>(UiState.Loading)",
                "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to "private val _state = MutableStateFlow<UiState<AlbumPage>>(UiState.Loading)",
                "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to "private val _state = MutableStateFlow<UiState<ArtistPage>>(UiState.Loading)",
                "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to "private val _state = MutableStateFlow<UiState<PlaylistPage>>(UiState.Loading)",
                "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to "private val _hasMore = MutableStateFlow(false)",
                "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to "private val _loadMoreError = MutableStateFlow<String?>(null)",
                "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to "private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)",
                "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to "private val _hasMore = MutableStateFlow(false)",
                "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to "private val _loadMoreError = MutableStateFlow<String?>(null)"
        )
        for ((path, needle) in births) {
            assertTrue(
                "$path must keep its shipped flow birth " +
                    "exactly: `$needle` — a wrong birth is a " +
                    "wrong first frame or first decision " +
                    "(round 61 F10: the Home/Search/Detail/" +
                    "queue births each ran GREEN doctored " +
                    "across the full 642-test suite)",
                fileText(path).contains(needle),
            )
        }
    }

    // -- helpers ------------------------------------------------------------

    private fun slice(
        text: String,
        startAnchor: String,
        endAnchor: String,
        label: String,
    ): String {
        val start = text.indexOf(startAnchor)
        assertTrue("$label: start anchor must exist: $startAnchor", start >= 0)
        val end = text.indexOf(endAnchor, start)
        assertTrue("$label: end anchor must exist after the start: $endAnchor", end > start)
        return text.substring(start, end)
    }

    /** The slice's trimmed non-blank lines, in order, must
     *  equal the shipped list exactly — an inserted line
     *  (round 61's additive doctors), a removed line, or a
     *  reordering each fail this, where round 60's
     *  presence-in-slice pins saw none of them. */
    private fun assertSliceExactLines(
        text: String,
        startAnchor: String,
        endAnchor: String,
        expected: List<String>,
        label: String,
    ) {
        val actual = slice(text, startAnchor, endAnchor, label)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        assertEquals(
            "$label must hold exactly its shipped lines — " +
                "round 61's additive doctors (an init block, " +
                "a second Builder call, a second listener) " +
                "each inserted a behaviour-changing line " +
                "inside this slice with every round-60 bound " +
                "line intact and ran GREEN across the full suite",
            expected,
            actual,
        )
    }

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
}
