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
import dev.cyberdogs.offleash.data.AppPreferences
import dev.cyberdogs.offleash.data.DownloadManager
import dev.cyberdogs.offleash.data.DownloadStore
import dev.cyberdogs.offleash.data.LikedTrackStore
import dev.cyberdogs.offleash.data.LocalPlaylistStore
import dev.cyberdogs.offleash.data.OkHttpTransport
import dev.cyberdogs.offleash.data.RecentsStore
import dev.cyberdogs.offleash.data.StreamWindowStore
import dev.cyberdogs.offleash.data.OkHttpChunkFetcher
import dev.cyberdogs.offleash.data.buildSharedClient
import dev.cyberdogs.offleash.data.downloadRecordsFileIn
import dev.cyberdogs.offleash.data.downloadsDirIn
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
import okhttp3.OkHttpClient
import java.io.File

/**
 * Application entry point. Builds the single [AppContainer] in onCreate so
 * every component (activity, playback service) shares one wiring graph.
 * The container exists before any service can start, because services are
 * only started after the process (and therefore this Application) is up.
 */
class OffleashApp : Application(), coil.ImageLoaderFactory {

    /**
     * The one image loader for the whole app (0.8.7 sweep 3):
     * Coil's defaults re-decode on memory pressure far sooner
     * than a music library's scroll-back wants, and every row
     * paid a crossfade. The memory cache is sized to the
     * device (15% of the app's allowance), the disk cache is
     * capped at 64 MB in the cache dir, and crossfade is off —
     * thumbnails appear when they are ready, not a beat later.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val appContext = this
        return coil.ImageLoader.Builder(appContext)
            .memoryCache {
                coil.memory.MemoryCache.Builder(appContext)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, before anything can fail: the last-crash recorder,
        // so a dying process leaves its trace where a later launch
        // can read it (2026-10-27 crash hunt).
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

    init {
        // Account-free upgrade cleanup (0.9.0, owner's 2026-11-04
        // order): a device arriving from a sign-in build may hold
        // sealed session material for roads this build no longer
        // presents or wires. It is destroyed here, before any
        // other container state is built, so the account-free app
        // can never observe a half-signed-in state. The file names
        // are literal because the stores that owned them are
        // deleted with the sign-in product; this is their
        // migration, following the own-key wizard precedent below
        // (0.8.8). Best-effort by design: hygiene must never be a
        // startup failure.
        for (name in LEGACY_ACCOUNT_FILES) {
            try {
                File(app.noBackupFilesDir, name).delete()
            } catch (t: Throwable) {
                // Best-effort hygiene; never a startup failure.
            }
        }
        // The legacy sign-in WebView's cookie jar and web storage
        // are session material too; clear them on the same pass.
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (t: Throwable) {
            // No WebView on this device/profile: nothing to clear.
        }
    }

    /** Shared playback-diagnostics recorder (last attempt only). */
    val playbackDiagnostics: PlaybackDiagnostics = PlaybackDiagnostics()

    /** Scope for app work that outlives any single screen. */
    private val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    val client: YoutubeiClient = YoutubeiClient(
        OkHttpTransport(okHttpClient),
        // Browse attempts land in the same owner-visible
        // diagnostics trail as playback: the core hook's lines
        // carry surface, client config, and refusal class —
        // statuses only, never server text.
        browseAttemptLog = { line -> playbackDiagnostics.log("browse", line) },
    )

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
     * the same [PlaybackResolver] playback uses, resolving
     * anonymously (no session, no bearer) — at the effective download
     * quality (the download-quality preference, which defaults to
     * following the stream quality). Chunk fetches ride the shared
     * OkHttp client, so a download's network identity matches the
     * resolver's, exactly as stream fetches do (SPEC §4).
     */
    val downloadStore: DownloadStore =
        DownloadStore(downloadRecordsFileIn(app.filesDir))

    // One resolver behind the download path (0.8.7 sweep 3):
    // its short-lived cache lets a retried download reuse the
    // stream it just resolved instead of paying the cascade again.
    val downloadStreamResolver: PlaybackResolver = PlaybackResolver(client)

    val downloadManager: DownloadManager = DownloadManager(
        store = downloadStore,
        downloadsDir = downloadsDirIn(app.filesDir),
        resolveStream = { track, quality ->
            downloadStreamResolver.resolve(
                track.videoId,
                auth = null,
                quality,
                bearerToken = null,
            ).info
        },
        fetcher = OkHttpChunkFetcher(okHttpClient),
        qualityProvider = { appPreferences.effectiveDownloadQuality() },
    )


    /**
     * The track rows behind the Library's Liked songs list
     * (0.9.0): `liked_tracks.json` in the app files dir, under the
     * same store discipline as playlists and recents.
     */
    val likedTrackStore: LikedTrackStore =
        LikedTrackStore(File(app.filesDir, "liked_tracks.json"))

    /**
     * Lane G: like state for every track surface. In the
     * account-free product a like is on-device state only (see
     * [dev.cyberdogs.offleash.data.LikeController]): the ids record
     * lives in preferences, the rows live in [likedTrackStore].
     */
    val likeController: dev.cyberdogs.offleash.data.LikeController =
        dev.cyberdogs.offleash.data.LikeController(
            preferences = appPreferences,
            likedTrackStore = likedTrackStore,
            scope = appScope,
        )

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
        /**
         * Sealed/web session files a pre-0.9.0 build could leave
         * behind, destroyed by the upgrade cleanup in the init
         * block above (0.9.0): the cookie session, the Google
         * token store, both pending sign-in attempts, the
         * sign-in-seen marker, and the own-key wizard pair the
         * 0.8.8 cleanup already named.
         */
        val LEGACY_ACCOUNT_FILES: List<String> = listOf(
            "session.bin",
            "google_session.bin",
            "google_pending_grant.bin",
            "google_pending_native_auth.bin",
            "google_signin_seen.txt",
            "own_key.bin",
            "own_key_wizard.bin",
        )
    }
}
