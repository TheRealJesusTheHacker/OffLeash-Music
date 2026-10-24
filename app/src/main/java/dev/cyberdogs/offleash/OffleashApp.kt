package dev.cyberdogs.offleash

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.playback.PlaybackResolver
import dev.cyberdogs.offleash.account.AutoDownloadLiked
import dev.cyberdogs.offleash.account.SessionKeepAlive
import dev.cyberdogs.offleash.data.AesGcmSecretBox
import dev.cyberdogs.offleash.data.AppPreferences
import dev.cyberdogs.offleash.data.DownloadManager
import dev.cyberdogs.offleash.data.DownloadStore
import dev.cyberdogs.offleash.data.FileSecretStorage
import dev.cyberdogs.offleash.data.KeystoreSecretKey
import dev.cyberdogs.offleash.data.LocalPlaylistStore
import dev.cyberdogs.offleash.data.OkHttpTransport
import dev.cyberdogs.offleash.data.RecentsStore
import dev.cyberdogs.offleash.data.SessionManager
import dev.cyberdogs.offleash.data.SessionStore
import dev.cyberdogs.offleash.data.OkHttpChunkFetcher
import dev.cyberdogs.offleash.data.buildSharedClient
import dev.cyberdogs.offleash.data.downloadRecordsFileIn
import dev.cyberdogs.offleash.data.downloadsDirIn
import dev.cyberdogs.offleash.data.sessionFileIn
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.cyberdogs.offleash.playback.PlaybackDiagnostics
import dev.cyberdogs.offleash.playback.PlaybackService
import dev.cyberdogs.offleash.playback.QueueController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * Application entry point. Builds the single [AppContainer] in onCreate so
 * every component (activity, playback service) shares one wiring graph.
 * The container exists before any service can start, because services are
 * only started after the process (and therefore this Application) is up.
 */
class OffleashApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * The one wiring class for the whole app: HTTP transport, core API client,
 * on-device stores, the shared ExoPlayer (with replay cache), and the
 * QueueController that drives it.
 *
 * The player lives here, in the app process. PlaybackService adopts this
 * same player into its MediaSession, so the UI, the notification and the
 * lock-screen controls all drive one player instance.
 */
class AppContainer(app: OffleashApp) {

    /** Shared playback-diagnostics recorder (last attempt only). */
    val playbackDiagnostics: PlaybackDiagnostics = PlaybackDiagnostics()

    /** Shared sign-in diagnostics recorder (stage lines for the last attempt). */
    val signInDiagnostics: dev.cyberdogs.offleash.data.SignInDiagnostics =
        dev.cyberdogs.offleash.data.SignInDiagnostics()

    /**
     * Late-bound handle to the session manager, for the transport's
     * cookie-rotation callback. The wiring has a natural cycle —
     * the transport wants the callback, the client wants the
     * transport, the manager wants the client — so the transport is
     * built with a callback that delegates through this reference,
     * and the reference is assigned the moment the manager exists
     * (below, before anything can make a request).
     */
    @Volatile
    private var sessionManagerRef: SessionManager? = null

    /**
     * The encrypted session store: a Keystore-sealed file in the
     * app's no-backup directory. Built before the transport so the
     * manager can be assembled as soon as the client exists.
     */
    val sessionStore: SessionStore = SessionStore(
        storage = FileSecretStorage(sessionFileIn(app)),
        box = AesGcmSecretBox { KeystoreSecretKey.getOrCreate(SESSION_KEY_ALIAS) },
    )

    /**
     * The ONE OkHttp client for the whole app: youtubei API calls and
     * stream fetches go through the same stack (same connection pool,
     * same address-family choices), because googlevideo stream URLs are
     * bound to the network identity that resolved them. The scoped
     * RangeInterceptor normalizes googlevideo GETs to bounded ranges
     * and records their first-fetch status into diagnostics.
     * Construction lives in [buildSharedClient] so a contract test
     * can build the production client and pin what is (and is not)
     * installed on it.
     */
    val okHttpClient: OkHttpClient = buildSharedClient(playbackDiagnostics)

    /** Scope for session work that outlives any single screen. */
    private val sessionScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val client: YoutubeiClient = YoutubeiClient(
        OkHttpTransport(
            okHttpClient,
            onResponseCookies = { pairs -> sessionManagerRef?.onResponseCookies(pairs) },
        ),
    )

    /**
     * Owns the signed-in session (encrypted store + in-memory
     * snapshot + UI state). Constructing it loads any stored session.
     */
    val sessionManager: SessionManager = SessionManager(
        store = sessionStore,
        client = client,
        scope = sessionScope,
        signInDiagnostics = signInDiagnostics,
    )

    init {
        // Close the wiring cycle described on [sessionManagerRef].
        sessionManagerRef = sessionManager
        // Periodic validation (12h, connected) for as long as the app
        // is installed; the manager decides what each result means.
        SessionKeepAlive.enqueue(app)
        // Auto-download of Liked (parity step 2): the worker itself
        // checks the preference and the session before doing anything.
        AutoDownloadLiked.enqueue(app)
        // App-start validation when a stored session exists: a dead
        // session is found — and wiped, with a plain explanation in
        // the UI state — at launch, not mid-playlist.
        if (sessionManager.currentAuthSession() != null) {
            sessionScope.launch {
                try {
                    sessionManager.validateNow()
                } catch (e: Exception) {
                    // validateNow maps its own outcomes; anything that
                    // still escapes leaves the stored session in place
                    // for the next attempt rather than crashing startup.
                }
            }
        }
    }

    /**
     * Plain app preferences (stream quality and friends), persisted
     * in the `offleash_prefs` SharedPreferences. Constructed before
     * the queue controller so playback can read the current quality
     * at every resolve.
     */
    val appPreferences: AppPreferences = AppPreferences.fromContext(app)

    /**
     * Offline downloads (parity step 2, Lane F): records in
     * `downloads.json` and audio files in `downloads/`, both under the
     * app files dir — app-private storage, NOT the no-backup session
     * directory (downloads are re-fetchable content, not credentials).
     *
     * Resolution is wired to the EXISTING road-switching resolver —
     * the same [PlaybackResolver] playback uses, with the current
     * session read at download time — at the effective download
     * quality (the download-quality preference, which defaults to
     * following the stream quality). Chunk fetches ride the shared
     * OkHttp client, so a download's network identity matches the
     * resolver's, exactly as stream fetches do (SPEC §4).
     */
    val downloadStore: DownloadStore =
        DownloadStore(downloadRecordsFileIn(app.filesDir))

    val downloadManager: DownloadManager = DownloadManager(
        store = downloadStore,
        downloadsDir = downloadsDirIn(app.filesDir),
        resolveStream = { track, quality ->
            PlaybackResolver(client).resolve(
                track.videoId,
                sessionManager.currentAuthSession(),
                quality,
            ).info
        },
        fetcher = OkHttpChunkFetcher(okHttpClient),
        qualityProvider = { appPreferences.effectiveDownloadQuality() },
    )


    /**
     * Lane G: like state for every track surface. Signed-in toggles
     * write to the account via the core client; signed-out toggles are
     * device-only. Built after both the session manager and the plain
     * preferences it persists local likes into.
     */
    val likeController: dev.cyberdogs.offleash.data.LikeController =
        dev.cyberdogs.offleash.data.LikeController(
            client = client,
            preferences = appPreferences,
            sessionProvider = { sessionManager.currentAuthSession() },
            scope = sessionScope,
        )

    init {
        // Keep the like controller's account-backed flag in step with
        // sign-in state, so menu labels never claim an account write
        // while signed out (or hide one while signed in).
        sessionScope.launch {
            sessionManager.uiState.collect { state ->
                likeController.setAccountBacked(state.signedIn)
            }
        }
    }

    /**
     * Lane G: "show the player" requests from track menus (the sleep
     * timer and speed items deep-link to the player controls). The
     * root observes the counter and expands the player.
     */
    private val _showPlayerRequests =
        kotlinx.coroutines.flow.MutableStateFlow(0)
    val showPlayerRequests: kotlinx.coroutines.flow.StateFlow<Int> =
        _showPlayerRequests

    fun requestShowPlayer() {
        _showPlayerRequests.value += 1
    }

    /**
     * The Lane F-owned parity preferences (download quality,
     * auto-download Liked, audio-version-in-radio — Lane H), over the
     * same `offleash_prefs` file under the contract's exact keys.
     */
    val parityPreferences: dev.cyberdogs.offleash.data.ParityPreferences =
        dev.cyberdogs.offleash.data.ParityPreferences.fromContext(app)


    val localPlaylistStore: LocalPlaylistStore =
        LocalPlaylistStore(File(app.filesDir, "playlists.json"))

    val recentsStore: RecentsStore =
        RecentsStore(File(app.filesDir, "recents.json"))

    /** Shared player, adopted by PlaybackService's MediaSession. */
    val player: ExoPlayer = buildPlayer(app)

    val queueController: QueueController = QueueController(
        context = app,
        client = client,
        player = player,
        recentsStore = recentsStore,
        diagnostics = playbackDiagnostics,
        // Phase 2 road-switching wiring (Lane C, merged by the
        // coordinator). `sessionManager` is this container's member;
        // the lambdas read it at resolve time, never at construction
        // time.
        sessionProvider = { sessionManager.currentAuthSession() },
        revalidateSession = {
            sessionManager.validateNow()
            sessionManager.currentAuthSession()
        },
        // The shared client runs the anonymous-road probe ladder, so a
        // probe sees the same network identity as the resolve and the
        // player's own fetch.
        probeClient = okHttpClient,
        // Stream quality (Wave 2 parity): read at resolve time, so a
        // Settings change applies to the next resolve without restart.
        qualityProvider = { appPreferences.streamQuality },
        // Offline downloads (parity step 2): a DONE download's local
        // file takes precedence over any network resolution. The
        // default (null) behaviour is unchanged when no download
        // exists — the provider simply returns null.
        localFileProvider = { track -> downloadManager.downloadedPlayback(track.videoId) },
        // Lane G: the audio-version-in-radio preference, read at
        // radio-start time (default false = pre-preference behavior).
        audioVersionInRadioProvider = { appPreferences.audioVersionInRadio },
    )

    /**
     * Process-lifetime MediaController connection to PlaybackService.
     *
     * MediaSessionService vends its session — and with it the media
     * notification and foreground promotion — to connected controllers
     * (and to sessions registered via addSession, which PlaybackService
     * also does). Retaining this connection for the process lifetime
     * keeps that path live. QueueController keeps driving the shared
     * player directly; playback never depends on this controller, and a
     * failed connection only logs into diagnostics.
     */
    @Volatile
    var mediaController: MediaController? = null
        private set

    val mediaControllerFuture: ListenableFuture<MediaController>? = buildMediaController(app)

    private fun buildMediaController(app: OffleashApp): ListenableFuture<MediaController>? {
        return try {
            val token = SessionToken(
                app,
                ComponentName(app, PlaybackService::class.java),
            )
            val future = MediaController.Builder(app, token).buildAsync()
            future.addListener(
                {
                    try {
                        mediaController = future.get()
                        playbackDiagnostics.log("controller", "MediaController connected")
                    } catch (e: Exception) {
                        playbackDiagnostics.log(
                            "controller",
                            "MediaController connection failed (${e.javaClass.simpleName})",
                        )
                    }
                },
                ContextCompat.getMainExecutor(app),
            )
            future
        } catch (e: Exception) {
            playbackDiagnostics.log(
                "controller",
                "MediaController build failed (${e.javaClass.simpleName})",
            )
            null
        }
    }

    private fun buildPlayer(app: OffleashApp): ExoPlayer {
        // Replay cache: 512 MB LRU SimpleCache in the app cache dir, wrapped
        // by CacheDataSource so replays and seeks hit local bytes first.
        // This is a replay accelerator only — no download feature (SPEC §4).
        val cache = SimpleCache(
            File(app.cacheDir, "replay-cache"),
            LeastRecentlyUsedCacheEvictor(REPLAY_CACHE_BYTES),
            StandaloneDatabaseProvider(app),
        )
        // Stream fetches go through the shared OkHttp stack (SPEC §4):
        // the same client that resolved the URLs fetches them, so the
        // network identity matches. OkHttpDataSource replaces
        // DefaultHttpDataSource (HttpURLConnection) for http(s); local /
        // file sources still go through DefaultDataSource below.
        // The factory deliberately sets NO User-Agent: the fetch rides
        // OkHttp's default UA, exactly like the ladder probe and the
        // diagnostics test fetch. The 2026-10-24 hunt proved the
        // pairing matters — with an iOS app UA set here, the player
        // was refused 403 on URLs the probe (default UA) had just been
        // served 206 on. The resolver's iOS context UA is a separate
        // thing: it belongs to the youtubei requests in :core, not to
        // stream fetches. PlayerFetchShapeContractTest pins the
        // absence of a UA override here.
        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
        // DefaultDataSource routes http(s) to the OkHttp factory above
        // and keeps local/content schemes on their own sources.
        val upstreamFactory = DefaultDataSource.Factory(app, httpFactory)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private companion object {
        const val REPLAY_CACHE_BYTES: Long = 512L * 1024L * 1024L
        const val SESSION_KEY_ALIAS: String = "offleash-session-key"
    }
}
