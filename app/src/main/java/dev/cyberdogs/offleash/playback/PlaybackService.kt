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
        mediaSession = MediaSession.Builder(this, container.player).build()
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
        // Release the session only. The player itself belongs to
        // AppContainer and outlives any single service instance.
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
