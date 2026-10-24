package dev.cyberdogs.offleash.playback

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.cyberdogs.offleash.OffleashApp

/**
 * MediaSessionService that adopts the app process's shared ExoPlayer
 * (created by AppContainer in Application.onCreate) into a MediaSession.
 *
 * The service owns no queue or resolution logic — QueueController drives
 * the player; this service exists so playback, the media notification and
 * lock-screen controls survive the activity going away.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // The container is created in Application.onCreate, which always
        // runs before any service in this process starts.
        val container = (application as OffleashApp).container
        val session = MediaSession.Builder(this, container.player).build()
        mediaSession = session
        // Register the session with this service: MediaSessionService only
        // posts the media notification and promotes the service to the
        // foreground for sessions it knows about (via addSession or a
        // connected controller). Without this, the started service never
        // became a foreground service and was reaped shortly after the
        // screen went off.
        addSession(session)
        container.playbackDiagnostics.recordServiceRunning(true)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val sessionPlayer = mediaSession?.player
        if (sessionPlayer != null && !sessionPlayer.playWhenReady) {
            // Nothing is playing or queued to play: don't linger.
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            (application as OffleashApp).container.playbackDiagnostics
                .recordServiceRunning(false)
        } catch (e: Exception) {
            // Diagnostics must never block service teardown.
        }
        // Release the session only. The player itself belongs to
        // AppContainer and outlives any single service instance.
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
