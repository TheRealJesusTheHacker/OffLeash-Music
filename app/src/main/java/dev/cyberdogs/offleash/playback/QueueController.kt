package dev.cyberdogs.offleash.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.api.YoutubeiContexts
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.core.playback.PlayQueue
import dev.cyberdogs.offleash.core.playback.RepeatMode
import dev.cyberdogs.offleash.data.RecentsStore
import dev.cyberdogs.offleash.ui.ErrorMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * User agent paired with the stream URLs we resolve. Streams are fetched
 * through the core client's iOS context, so playback requests present the
 * same iOS YouTube app UA the URLs were issued to.
 */
const val APP_UA: String =
    "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)"

/** Snapshot of the play queue for the UI. */
data class QueueUiState(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: Track? = null,
    val isShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
)

/** Snapshot of player transport state for the UI. */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isBuffering: Boolean = false,
)

/**
 * Drives the shared [ExoPlayer] one resolved item at a time (SPEC §4).
 *
 * Queue semantics (order, shuffle, repeat) live in the core [PlayQueue];
 * this controller resolves the current track's stream on the IO dispatcher,
 * hands a MediaItem to the player, and advances the queue when playback
 * ends. On a resolve failure the queue STOPS: the named SPEC §6 error is
 * published on [namedError] and the queue position is kept — no silent
 * skipping (SPEC §4).
 */
/** Pure retry decision for a player error: exactly one re-resolve on 403. */
fun shouldRetryAfterPlayerError(httpStatus: Int?, alreadyRetriedForTrack: Boolean): Boolean =
    httpStatus == 403 && !alreadyRetriedForTrack

/** HTTP status carried by a player error's cause chain, if any. */
fun httpStatusFromCauseChain(error: Throwable): Int? {
    var t: Throwable? = error
    while (t != null) {
        if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
        t = t.cause
    }
    return null
}

class QueueController(
    private val context: Context,
    private val client: YoutubeiClient,
    private val player: ExoPlayer,
    private val recentsStore: RecentsStore,
    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
) {

    private val queue = PlayQueue(Random)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var resolveJob: Job? = null
    private var retriedCurrentTrack: Boolean = false
    private var lastQueueIndex: Int = -1
    private var keepDiagnosticsAttempt: Boolean = false

    /**
     * Play [track] as part of an already-started diagnostics attempt
     * (the Test playback flow), so its resolve/fetch stages stay in the
     * same recorded attempt instead of starting a new one.
     */
    fun playTestTrack(track: Track) {
        keepDiagnosticsAttempt = true
        play(track)
    }

    private val _queueState = MutableStateFlow(QueueUiState())
    val queueState: StateFlow<QueueUiState> = _queueState.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private val _namedError = MutableStateFlow<String?>(null)
    /** SPEC §6 user copy for the current failure, or null. */
    val namedError: StateFlow<String?> = _namedError.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                syncPlaybackState()
                if (playbackState == Player.STATE_READY && player.playWhenReady) {
                    retriedCurrentTrack = false
                    diagnostics.recordReady()
                }
                if (playbackState == Player.STATE_ENDED) {
                    onTrackEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncPlaybackState()
            }

            override fun onPlayerError(error: PlaybackException) {
                val httpStatus = httpStatusFromCauseChain(error)
                val causes = mutableListOf<String>()
                var t: Throwable? = error
                while (t != null) {
                    causes.add(t.javaClass.simpleName)
                    t = t.cause
                }
                diagnostics.recordPlayerError(
                    errorCode = error.errorCode,
                    exceptionClass = error.javaClass.simpleName,
                    rootCauseClasses = causes,
                )
                if (shouldRetryAfterPlayerError(httpStatus, retriedCurrentTrack)) {
                    retriedCurrentTrack = true
                    diagnostics.log("retry", "HTTP 403 — re-resolving the same track once")
                    queue.current()?.let { resolveAndPlay(it, isRetry = true) }
                    syncPlaybackState()
                    return
                }
                _namedError.value = ErrorMessages.playerMessage(error.errorCode, httpStatus)
                syncPlaybackState()
            }
        })
        // Position polling: 500 ms cadence while anything is loaded.
        scope.launch {
            while (true) {
                if (player.currentMediaItem != null) {
                    syncPlaybackState()
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    // -- Public API ---------------------------------------------------------

    fun playTracks(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        queue.setQueue(tracks, startIndex.coerceIn(tracks.indices))
        syncQueueState()
        queue.current()?.let { resolveAndPlay(it) }
    }

    fun play(track: Track) = playTracks(listOf(track), 0)

    fun togglePlayPause() {
        when {
            player.isPlaying -> player.pause()
            player.currentMediaItem != null -> player.play()
            else -> queue.current()?.let { resolveAndPlay(it) }
        }
        syncPlaybackState()
    }

    fun next() {
        val nextTrack = queue.next()
        syncQueueState()
        if (nextTrack != null) {
            resolveAndPlay(nextTrack)
        } else {
            // End of queue with repeat off: stop where we are.
            player.pause()
            syncPlaybackState()
        }
    }

    fun previous() {
        if (player.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
            player.seekTo(0)
            return
        }
        val previousTrack = queue.previous()
        syncQueueState()
        if (previousTrack != null) {
            resolveAndPlay(previousTrack)
        } else {
            player.seekTo(0)
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
        syncPlaybackState()
    }

    fun toggleShuffle() = setShuffle(!queue.shuffle)

    fun setShuffle(enabled: Boolean) {
        queue.shuffle = enabled
        syncQueueState()
    }

    fun cycleRepeat() {
        val nextMode = when (queue.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        queue.repeatMode = nextMode
        // Repeat-one is the player's own repeat mode (SPEC §4); the core
        // queue and the player are always set consistently together.
        player.repeatMode =
            if (nextMode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        syncQueueState()
    }

    fun playQueueItem(index: Int) {
        val track = queue.playIndex(index) ?: return
        syncQueueState()
        resolveAndPlay(track)
    }

    fun addToQueue(track: Track) {
        val current = queue.tracks
        val index = queue.currentIndex
        if (current.isEmpty()) {
            queue.setQueue(listOf(track), 0)
        } else {
            // PlayQueue has no append in the core contract; rebuild the
            // queue with the same current position.
            queue.setQueue(current + track, index.coerceAtLeast(0))
        }
        syncQueueState()
    }

    fun playNext(track: Track) {
        val current = queue.tracks
        val index = queue.currentIndex
        if (current.isEmpty() || index < 0) {
            queue.setQueue(listOf(track), 0)
            syncQueueState()
            return
        }
        val rebuilt = current.toMutableList().apply { add(index + 1, track) }
        queue.setQueue(rebuilt, index)
        syncQueueState()
    }

    fun clearError() {
        _namedError.value = null
    }

    // -- Internals ----------------------------------------------------------

    private fun onTrackEnded() {
        // With RepeatMode.ONE the player loops internally and STATE_ENDED
        // is not delivered; reaching here means advance (repeat ALL wraps
        // inside PlayQueue.next()).
        val nextTrack = queue.next()
        syncQueueState()
        if (nextTrack != null) {
            resolveAndPlay(nextTrack)
        } else {
            player.pause()
            syncPlaybackState()
        }
    }

    private fun resolveAndPlay(track: Track, isRetry: Boolean = false) {
        if (!isRetry) {
            retriedCurrentTrack = false
        }
        val preserveDiagnostics = keepDiagnosticsAttempt
        keepDiagnosticsAttempt = false
        ensureServiceStarted()
        _namedError.value = null
        resolveJob?.cancel()
        resolveJob = scope.launch {
            if (isRetry) {
                diagnostics.log("resolve", "retry for track ${track.videoId}")
            } else if (preserveDiagnostics) {
                diagnostics.log("resolve", "player resolve for track ${track.videoId}")
            } else {
                diagnostics.startAttempt("track ${track.videoId} — ${track.title}")
            }
            diagnostics.recordNetworkFrom(context)
            val stream = try {
                val resolved = withContext(Dispatchers.IO) { client.streamInfo(track.videoId) }
                diagnostics.recordResolverOutcome(
                    contextClientName = YoutubeiContexts.IOS.clientName,
                    contextClientVersion = YoutubeiContexts.IOS.clientVersion,
                    exceptionClass = null,
                )
                diagnostics.recordChosenFormat(resolved.mimeType, resolved.bitrate, resolved.url)
                resolved
            } catch (e: OffleashException) {
                diagnostics.recordResolverOutcome(
                    contextClientName = YoutubeiContexts.IOS.clientName,
                    contextClientVersion = YoutubeiContexts.IOS.clientVersion,
                    exceptionClass = e.javaClass.simpleName,
                )
                // Named failure: stop the queue, keep the position, tell the
                // user (SPEC §4/§6). No silent skip.
                _namedError.value = ErrorMessages.userMessage(e)
                player.pause()
                syncPlaybackState()
                return@launch
            } catch (e: Exception) {
                diagnostics.recordResolverOutcome(
                    contextClientName = YoutubeiContexts.IOS.clientName,
                    contextClientVersion = YoutubeiContexts.IOS.clientVersion,
                    exceptionClass = e.javaClass.simpleName,
                )
                _namedError.value = ErrorMessages.userMessage(e)
                player.pause()
                syncPlaybackState()
                return@launch
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artists.joinToString(", "))
                .setAlbumTitle(track.album)
                .setArtworkUri(track.thumbnailUrl?.let { Uri.parse(it) })
                .build()
            val mediaItem = MediaItem.Builder()
                .setMediaId(track.videoId)
                .setUri(stream.url)
                .setMimeType(stream.mimeType)
                .setMediaMetadata(metadata)
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            syncQueueState()
            syncPlaybackState()
            // Record the play once the track actually starts.
            scope.launch(Dispatchers.IO) { recentsStore.recordPlayed(track) }
        }
    }

    private fun ensureServiceStarted() {
        // Attempted on every play, with no permanent latch: after
        // onTaskRemoved stops the service while paused, a later play in
        // the same process must be able to start it again. Repeat attempts
        // are safe — once the service is running/foreground,
        // startForegroundService is a no-op re-delivery of the intent.
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java),
            )
        } catch (e: IllegalStateException) {
            // Background-start restrictions: playback still works in-process;
            // the next foreground play attempt retries service start.
        }
    }

    private fun syncQueueState() {
        if (queue.currentIndex != lastQueueIndex) {
            // A queue-position change starts a fresh retry budget.
            retriedCurrentTrack = false
            lastQueueIndex = queue.currentIndex
        }
        _queueState.value = QueueUiState(
            tracks = queue.tracks,
            currentIndex = queue.currentIndex,
            currentTrack = queue.current(),
            isShuffled = queue.shuffle,
            repeatMode = queue.repeatMode,
        )
    }

    private fun syncPlaybackState() {
        val duration = player.duration
        _playbackState.value = PlaybackUiState(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration > 0) duration else 0L,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
        )
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
    }
}
