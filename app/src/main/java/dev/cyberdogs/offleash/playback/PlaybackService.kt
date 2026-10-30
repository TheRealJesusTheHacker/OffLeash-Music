package dev.cyberdogs.offleash.playback

import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.cyberdogs.offleash.OffleashApp
import dev.cyberdogs.offleash.core.playback.RepeatMode

/**
 * MediaSessionService that adopts the app process's shared ExoPlayer
 * (created by AppContainer in Application.onCreate) into a MediaSession.
 *
 * The service owns no queue or resolution logic — QueueController drives
 * the player; this service exists so playback, the media notification and
 * lock-screen controls survive the activity going away.
 *
 * The session's player is a [QueueTransportPlayer], not the raw
 * ExoPlayer: QueueController feeds the player ONE resolved item at a
 * time, so the raw player's own playlist is a single item and its
 * seek-to-next/previous commands are unavailable or meaningless to
 * external controllers (notification, lock screen, Bluetooth). The
 * wrapper routes those commands to the real queue, so every control
 * a controller can see acts on the queue the listener hears.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // The container is created in Application.onCreate, which always
        // runs before any service in this process starts.
        val container = (application as OffleashApp).container
        val sessionPlayer = QueueTransportPlayer(container.player, container.queueController)
        val session = MediaSession.Builder(this, sessionPlayer).build()
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

/**
 * The session-facing view of the shared player: everything delegates
 * to the real player except the queue-shaped transport commands,
 * which belong to [QueueController] (the player's own playlist is
 * the one resolved item it is currently playing, so its native
 * next/previous cannot express the app's queue, shuffle or repeat).
 *
 * The seek-to-next/previous commands are always advertised: whether
 * a next exists is the queue's call (it advances, wraps, or lines
 * up autoplay tracks), and QueueController's next()/previous() are
 * the same entry points the in-app buttons call — one behavior for
 * every surface. Seek keeps delegating to the player, which is the
 * honest owner of the current item; pause routes to the controller
 * since round 10 (an external pause must cancel an in-flight
 * autoplay continuation exactly like the in-app pause — see the
 * pause() override below), and stop / setPlayWhenReady route to
 * the controller since round 11 (the same defeat, one verb over —
 * see those overrides below). Play mostly delegates too — with
 * one exception (bug sweep round 4): in STATE_ENDED / STATE_IDLE
 * the raw player ignores play() (the item is over, or nothing is
 * prepared after an error or a failed resolve), so a notification
 * or lock-screen Play pressed in exactly the states the listener
 * most needs it — after a failure, after the queue ran out —
 * looked alive and did nothing, while the in-app button re-resolved
 * through [QueueController.togglePlayPause]. The session player's
 * play() routes those two states to the same toggle, so every
 * surface has the same honest Play. Repeat and shuffle route to
 * the controller since round 13 (they are queue state; left to
 * the raw player they looped or flagged its single item while
 * the queue and the UI said otherwise), and the corresponding
 * getters report the queue's state, so no controller surface can
 * set or read a repeat/shuffle truth the app itself disowns.
 */
private class QueueTransportPlayer(
    player: Player,
    private val queueController: QueueController,
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    override fun play() {
        // Dead-state routing (see the class doc): play() must
        // never PAUSE, so the controller's toggle is only invoked
        // when nothing is playing — its pause branch cannot fire —
        // and only in the states the raw player cannot start from.
        // Every other state (READY / BUFFERING with an item the
        // listener paused) keeps the player's own play().
        val state = playbackState
        if (!isPlaying &&
            (state == Player.STATE_ENDED || state == Player.STATE_IDLE)
        ) {
            queueController.togglePlayPause()
        } else {
            super.play()
        }
    }

    override fun seekToNext() {
        queueController.next()
    }

    override fun seekToNextMediaItem() {
        queueController.next()
    }

    override fun seekToPrevious() {
        queueController.previous()
    }

    override fun seekToPreviousMediaItem() {
        queueController.previous()
    }

    override fun pause() {
        // Pause is a queue-shaped intent too (bug sweep round 10):
        // round 9 taught the in-app pause (togglePlayPause) to
        // cancel an in-flight autoplay continuation — a fetch
        // started by a manual Next at the end of the queue lands
        // after a pause, advances, and PLAYS — but an external
        // pause delegated straight to the raw player and carried
        // no cancel, so a notification / lock-screen / Bluetooth
        // pause during that fetch was defeated the moment the
        // fetch landed. Route through the controller's one pause
        // so every surface's pause means the same thing.
        queueController.pausePlayback()
    }

    override fun stop() {
        // Stop is the terminal transport intent (bug sweep round
        // 11): it reaches this player through the MediaSession
        // stop command and through the platform session's onStop
        // (a Bluetooth / media-button STOP), and delegating it to
        // the raw player halted playback while leaving every
        // in-flight fetch and resolve alive — a landing
        // continuation then appended, advanced and PLAYED against
        // an explicit stop, the round-10 pause defeat one verb
        // over. Route through the controller's one stop, which
        // cancels everything in flight before stopping.
        queueController.stopPlayback()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        // The other play/pause verb (bug sweep round 11): the
        // session exposes setPlayWhenReady to controllers as its
        // own command, and delegating it forwarded a pause in
        // this verb straight to the raw player — bypassing both
        // the play() routing above and the pause() routing, so
        // the continuation cancel never ran. Route both
        // directions through the same entries every other
        // surface uses: true through play() (whose dead-state
        // routing then applies to this verb too) and false
        // through the controller's one pause.
        if (playWhenReady) {
            play()
        } else {
            queueController.pausePlayback()
        }
    }

    override fun setRepeatMode(repeatMode: Int) {
        // Repeat is queue state, not player state (bug sweep round
        // 13): delegating this verb to the raw player set the
        // player's own loop mode while the queue and the UI kept
        // saying OFF — under a player-level loop the single item
        // repeats inside the player, the track end the controller
        // advances on never fires, and the queue stalls silently
        // (repeat ALL at the player level loops the one item the
        // same way). The verb is reachable: the session advertises
        // the repeat command to every connected controller, and
        // the platform/legacy stub converts the repeat calls of
        // Bluetooth (AVRCP), Wear and system controllers into
        // exactly this method. Route it to the controller's one
        // repeat write, which sets the queue and the player
        // consistently together.
        queueController.setRepeatMode(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
        )
    }

    override fun getRepeatMode(): Int =
        // What controllers are shown is the queue's repeat mode —
        // the state the app itself publishes — never the raw
        // player's private loop setting (round 13).
        when (queueController.queueState.value.repeatMode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        // Shuffle is queue state too (round 13): the shuffle order
        // lives in the core queue, and the raw player holds a
        // single item it could not meaningfully shuffle anyway.
        // A player-level shuffle flag would only diverge from the
        // queue and the UI, so the verb routes to the controller's
        // shuffle write and the player is never touched.
        queueController.setShuffle(shuffleModeEnabled)
    }

    override fun getShuffleModeEnabled(): Boolean =
        queueController.queueState.value.isShuffled
}
