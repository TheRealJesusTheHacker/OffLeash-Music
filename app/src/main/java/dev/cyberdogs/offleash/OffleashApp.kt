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
import dev.cyberdogs.offleash.core.api.BrowseDelegation
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import dev.cyberdogs.offleash.core.playback.PlaybackResolver
import dev.cyberdogs.offleash.account.AutoDownloadLiked
import dev.cyberdogs.offleash.account.GoogleSessionKeepAlive
import dev.cyberdogs.offleash.data.AesGcmSecretBox
import dev.cyberdogs.offleash.data.AppPreferences
import dev.cyberdogs.offleash.data.DownloadManager
import dev.cyberdogs.offleash.data.DownloadStore
import dev.cyberdogs.offleash.data.FileSecretStorage
import dev.cyberdogs.offleash.data.GooglePendingGrantStore
import dev.cyberdogs.offleash.data.GoogleSessionManager
import dev.cyberdogs.offleash.data.GoogleSignInSeenMarker
import dev.cyberdogs.offleash.data.googleSignInSeenFileIn
import dev.cyberdogs.offleash.data.legacyFallbackApplies
import dev.cyberdogs.offleash.data.GoogleTokenStore
import dev.cyberdogs.offleash.data.KeystoreSecretKey
import dev.cyberdogs.offleash.data.LocalPlaylistStore
import dev.cyberdogs.offleash.data.OkHttpTransport
import dev.cyberdogs.offleash.data.RecentsStore
import dev.cyberdogs.offleash.data.SessionManager
import dev.cyberdogs.offleash.data.SessionStore
import dev.cyberdogs.offleash.data.SessionUiState
import dev.cyberdogs.offleash.data.googlePendingGrantFileIn
import dev.cyberdogs.offleash.data.googleSessionFileIn
import dev.cyberdogs.offleash.data.StreamWindowStore
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
import dev.cyberdogs.offleash.playback.SpeedUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        // First, before anything can fail: the last-crash recorder,
        // so a dying process leaves its trace where the next launch's
        // sign-in screen can show it (2026-10-27 crash hunt).
        dev.cyberdogs.offleash.data.CrashReporter.install(this)
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

    /** The application, kept for the few container operations that need a Context. */
    private val application: OffleashApp = app

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
     * The same late-bound arrangement for the Google session
     * manager, for the client transport's Bearer-retry hook (the
     * reactive 401 → refresh → retry-once path): the hook delegates
     * through this reference, assigned the moment the manager
     * exists, before anything can make a request.
     */
    @Volatile
    private var googleSessionManagerRef: GoogleSessionManager? = null

    /**
     * The encrypted session store: a Keystore-sealed file in the
     * app's no-backup directory. Built before the transport so the
     * manager can be assembled as soon as the client exists.
     */
    val sessionStore: SessionStore = SessionStore(
        storage = FileSecretStorage(sessionFileIn(app)),
        box = AesGcmSecretBox(
            keyProvider = { KeystoreSecretKey.getOrCreate(SESSION_KEY_ALIAS) },
            // 0.8.1 hotfix: a seal that proves the standing key's
            // parameters unusable (the device-class failure behind
            // the owner's 0.8.0 sign-in panel) regenerates the
            // shared key once and retries — see AesGcmSecretBox.
            keyRegenerator = { KeystoreSecretKey.regenerate(SESSION_KEY_ALIAS) },
        ),
    )

    /**
     * The Google sign-in's token store (0.7.0): the same Keystore
     * seal and no-backup directory as the cookie session's store,
     * but its own sealed file — the two sign-in generations never
     * share bytes.
     */
    val googleTokenStore: GoogleTokenStore = GoogleTokenStore(
        storage = FileSecretStorage(googleSessionFileIn(app)),
        box = AesGcmSecretBox(
            keyProvider = { KeystoreSecretKey.getOrCreate(SESSION_KEY_ALIAS) },
            keyRegenerator = { KeystoreSecretKey.regenerate(SESSION_KEY_ALIAS) },
        ),
    )

    /**
     * The pending device-grant store (0.7.1): an issued-but-not-
     * yet-approved sign-in code, sealed with the same box into its
     * own file, so the grant survives the app being killed while
     * the owner is in the browser approving it.
     */
    val googlePendingGrantStore: GooglePendingGrantStore =
        GooglePendingGrantStore(
            storage = FileSecretStorage(googlePendingGrantFileIn(app)),
            box = AesGcmSecretBox(
                keyProvider = { KeystoreSecretKey.getOrCreate(SESSION_KEY_ALIAS) },
                keyRegenerator = { KeystoreSecretKey.regenerate(SESSION_KEY_ALIAS) },
            ),
        )

    /**
     * The "a Google sign-in has happened here" marker (bug sweep
     * round 1): once set, the legacy cookie session never drives
     * the auto-download worker's decisions — see
     * [GoogleSignInSeenMarker]. (Its original resurrection watch —
     * the presented account state — is moot since 0.8.3: the
     * session manager discards any stored legacy session at
     * construction, so there is nothing left to resurrect.)
     */
    val googleSignInSeenMarker: GoogleSignInSeenMarker =
        GoogleSignInSeenMarker(googleSignInSeenFileIn(app))

    /**
     * The ONE OkHttp client for the whole app: youtubei API calls and
     * stream fetches go through the same stack (same connection pool,
     * same address-family choices), because googlevideo stream URLs are
     * bound to the network identity that resolved them. The scoped
     * RangeInterceptor normalizes googlevideo GETs to bounded ranges
     * (per-stream windows from [streamWindows]) and records every
     * stream load's status into diagnostics.
     * Construction lives in [buildSharedClient] so a contract test
     * can build the production client and pin what is (and is not)
     * installed on it.
     */
    /**
     * The ONE per-stream window record (round 3): the ladder probe's
     * winning Range window per stream URL. This single instance is
     * shared by the shared client's RangeInterceptor (which shapes
     * every stream fetch with the recorded window) and the
     * QueueController (which records candidates and winners) — the
     * probe/player window parity holds by construction.
     */
    val streamWindows: StreamWindowStore = StreamWindowStore()

    val okHttpClient: OkHttpClient = buildSharedClient(playbackDiagnostics, streamWindows)

    /** Scope for session work that outlives any single screen. */
    private val sessionScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val client: YoutubeiClient = YoutubeiClient(
        dev.cyberdogs.offleash.data.BearerRetryTransport(
            OkHttpTransport(
                okHttpClient,
                onResponseCookies = { pairs -> sessionManagerRef?.onResponseCookies(pairs) },
            ),
            bearerRefresh = { rejected ->
                googleSessionManagerRef?.bearerAfterRejection(rejected)
            },
        ),
        // Signed-in browse attempts land in the same owner-visible
        // diagnostics trail as playback (0.8.2): the core hook's
        // lines carry surface, client config, and refusal class —
        // statuses only, never tokens or server text.
        browseAttemptLog = { line -> playbackDiagnostics.log("browse", line) },
    )

    /**
     * The legacy session's remains (0.8.3): constructing it
     * DISCARDS any WebView-era session an older build left in the
     * sealed store — the cookie sign-in road is removed, and this
     * manager now only answers the app's legacy reads (always
     * signed out) and performs total sign-out's legacy wipe.
     */
    val sessionManager: SessionManager = SessionManager(
        store = sessionStore,
        signInDiagnostics = signInDiagnostics,
    )

    /**
     * The Google OAuth client (device-code flow), riding its own
     * transport instance over the shared OkHttp client — no cookie
     * rotation callback: OAuth responses carry no session cookies
     * to rotate, and token material never enters the cookie path.
     */
    val googleAuth: GoogleDeviceAuth = GoogleDeviceAuth(
        OkHttpTransport(okHttpClient),
    )

    /**
     * Owns the Google sign-in (0.7.0, the app's primary session):
     * the device-code flow, the sealed token store, refresh, and
     * revocation. Constructing it loads any stored Google session.
     */
    val googleSessionManager: GoogleSessionManager = GoogleSessionManager(
        store = googleTokenStore,
        auth = googleAuth,
        client = client,
        scope = sessionScope,
        pendingGrantStore = googlePendingGrantStore,
        signInDiagnostics = signInDiagnostics,
    )

    /**
     * The account state the UI presents: the Google session's
     * state. The legacy fallback shape is kept (the round-1 rule:
     * the cookie session's state only on a device that has never
     * had a Google sign-in), but since 0.8.3 the cookie session's
     * state is permanently signed out — the session manager
     * discards any stored legacy session at construction — so the
     * presented state is the Google state in every case, and a
     * Google store wiped on its own lands on the honest Google
     * signed-out state. Kept current by the collectors in the
     * wiring init below.
     */
    private val _accountUiState = MutableStateFlow(accountUiStateSnapshot())
    val accountUiState: StateFlow<SessionUiState> = _accountUiState.asStateFlow()

    private fun accountUiStateSnapshot(): SessionUiState {
        val google = googleSessionManager.uiState.value
        return if (legacyFallbackApplies(google.signedIn, googleSignInSeenMarker.seen())) {
            sessionManager.uiState.value
        } else {
            google
        }
    }

    /**
     * Signs out of BOTH sign-in generations: the Google grant is
     * revoked at Google and its tokens wiped; any stored cookie
     * session is wiped too, so "signed out" is total.
     */
    suspend fun signOutOfAccount() {
        googleSessionManager.signOut()
        sessionManager.signOut()
        // The legacy sign-in WebView's cookie jar and web storage
        // are wiped too: SessionManager.signOut documents that as
        // the caller's job, and this is the one sign-out path every
        // screen uses — until the round 3 sweep no caller did it, so
        // a ≤0.6.x install's WebView cookies survived every sign-out.
        dev.cyberdogs.offleash.data.wipeWebViewData(application)
    }

    /**
     * A currently-valid Google access token, or null. BLOCKING
     * (refreshes on the caller's thread when near expiry) — call
     * from an IO dispatcher, like every core client call.
     */
    fun currentBearerToken(): String? = googleSessionManager.currentBearer()

    /**
     * The stored brand delegation signed-in browse runs as
     * (0.8.2), or null when signed out / no brand channel. Cheap
     * read — call beside [currentBearerToken].
     */
    fun currentBrowseDelegation(): BrowseDelegation? =
        googleSessionManager.browseDelegation()

    init {
        // Close the wiring cycle described on [sessionManagerRef].
        sessionManagerRef = sessionManager
        googleSessionManagerRef = googleSessionManager
        // No legacy keep-alive is enqueued (0.8.3): the WebView-era
        // session it used to validate no longer exists — a stored
        // one is discarded by the session manager at construction.
        // The Google sign-in's periodic care: identity
        // re-read + proactive token refresh.
        GoogleSessionKeepAlive.enqueue(app)
        // Auto-download of Liked (parity step 2): the worker itself
        // checks the preference and the session before doing anything.
        AutoDownloadLiked.enqueue(app)
        // A stored Google session means a Google sign-in has
        // happened on this device (covers upgrades into this build):
        // set the marker before anything consults it below.
        if (googleSessionManager.hasStoredSession()) {
            googleSignInSeenMarker.mark()
        }
        // No app-start validation of a legacy session (0.8.3): the
        // session manager discarded any stored one at construction,
        // and there is nothing left to validate.
        // App-start identity refresh for a stored Google session:
        // names the account fresh, and discovers a revoked grant at
        // launch rather than mid-playlist.
        if (googleSessionManager.hasStoredSession()) {
            sessionScope.launch {
                try {
                    googleSessionManager.refreshIdentity()
                } catch (e: Exception) {
                    // refreshIdentity maps its own outcomes; anything
                    // that still escapes leaves the stored tokens in
                    // place for the next attempt.
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
                googleSessionManager.currentBearer(),
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
            bearerProvider = { googleSessionManager.currentBearer() },
            bearerSessionPresent = { googleSessionManager.hasStoredSession() },
            scope = sessionScope,
        )

    init {
        // Keep the presented account state current: the Google
        // session when one exists, else the cookie session's.
        sessionScope.launch {
            googleSessionManager.uiState.collect {
                // An adoption landing sets the marker first, so the
                // snapshot below (and every later one) can never fall
                // back to the cookie session again.
                if (it.signedIn) googleSignInSeenMarker.mark()
                _accountUiState.value = accountUiStateSnapshot()
            }
        }
        sessionScope.launch {
            sessionManager.uiState.collect {
                _accountUiState.value = accountUiStateSnapshot()
            }
        }
        // Keep the like controller's account-backed flag in step with
        // sign-in state, so menu labels never claim an account write
        // while signed out (or hide one while signed in).
        sessionScope.launch {
            accountUiState.collect { state ->
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
        // The token road (0.7.0): read fresh at every resolve, like
        // the cookie session — a sign-in or refresh takes effect on
        // the next track without rebuilding the controller.
        bearerProvider = { googleSessionManager.currentBearer() },
        revalidateSession = {
            // The resolver's fallback-road revalidation hook: since
            // 0.8.3 there is no legacy session to revalidate — the
            // read is the whole answer (always null; the Google
            // bearer road and the anonymous road carry playback).
            sessionManager.currentAuthSession()
        },
        // The shared client runs the anonymous-road probe ladder, so a
        // probe sees the same network identity as the resolve and the
        // player's own fetch.
        probeClient = okHttpClient,
        // Round 3: the same window record the shared client's
        // interceptor reads, so ladder candidates/winners shape the
        // player's loads of the winning stream.
        streamWindows = streamWindows,
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
        // Speed + pitch persistence (bug sweep round 1): seed the
        // owner's last choice and write every change back, so a
        // process death no longer resets playback to 1.0×.
        initialSpeedState = SpeedUiState(
            speed = appPreferences.playbackSpeed,
            preservePitch = appPreferences.keepPitchAtSpeed,
        ),
        onSpeedStateChanged = { state ->
            appPreferences.setPlaybackSpeed(state.speed)
            appPreferences.setKeepPitchAtSpeed(state.preservePitch)
        },
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
