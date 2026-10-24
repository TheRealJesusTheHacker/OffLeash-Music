package dev.cyberdogs.offleash

import android.app.Application
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.data.LocalPlaylistStore
import dev.cyberdogs.offleash.data.OkHttpTransport
import dev.cyberdogs.offleash.data.RecentsStore
import dev.cyberdogs.offleash.playback.APP_UA
import dev.cyberdogs.offleash.playback.QueueController
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

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

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    val client: YoutubeiClient = YoutubeiClient(OkHttpTransport(okHttpClient))

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
    )

    private fun buildPlayer(app: OffleashApp): ExoPlayer {
        // Replay cache: 512 MB LRU SimpleCache in the app cache dir, wrapped
        // by CacheDataSource so replays and seeks hit local bytes first.
        // This is a replay accelerator only — no download feature (SPEC §4).
        val cache = SimpleCache(
            File(app.cacheDir, "replay-cache"),
            LeastRecentlyUsedCacheEvictor(REPLAY_CACHE_BYTES),
            StandaloneDatabaseProvider(app),
        )
        val httpFactory = DefaultHttpDataSource.Factory()
            // Stream URLs were resolved with the iOS context; keep the UA
            // paired with them (see APP_UA in QueueController).
            .setUserAgent(APP_UA)
            .setAllowCrossProtocolRedirects(true)
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
    }
}
