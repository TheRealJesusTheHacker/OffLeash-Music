package dev.cyberdogs.offleash.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.api.YoutubeiContext
import dev.cyberdogs.offleash.core.api.YoutubeiContexts
import dev.cyberdogs.offleash.core.error.AllRoadsRefused
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.error.isAuthRefusal
import dev.cyberdogs.offleash.core.model.StreamInfo
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.core.playback.PlayQueue
import dev.cyberdogs.offleash.core.playback.PlaybackResolver
import dev.cyberdogs.offleash.core.playback.QualityPreference
import dev.cyberdogs.offleash.core.playback.RepeatMode
import dev.cyberdogs.offleash.core.playback.ResolvedStream
import dev.cyberdogs.offleash.data.DownloadedPlayback
import dev.cyberdogs.offleash.data.RangeInterceptor
import dev.cyberdogs.offleash.data.RecentsStore
import dev.cyberdogs.offleash.data.StreamWindowStore
import dev.cyberdogs.offleash.ui.ErrorMessages
import kotlinx.coroutines.CancellationException
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
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random

/**
 * There is deliberately NO app-level stream User-Agent constant
 * anymore. Until the 2026-10-24 hunt, the player's data source
 * presented the iOS YouTube app UA on stream fetches ("the UA the
 * URLs were issued to"); on the owner's phone the ladder probe —
 * which sends no UA of its own and rides OkHttp's default — was
 * served 206 on the very URL the player was then refused 403 on.
 * Stream fetches now carry no UA of ours at any layer (see
 * RangeInterceptor's shape contract and AppContainer.buildPlayer),
 * so the probe, the test fetch and the player cannot diverge. The
 * iOS context UA still exists where it belongs: inside :core's
 * YoutubeiContexts, on the youtubei requests themselves.
 */

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
    /**
     * True from the moment a resolve begins (any road, any ladder step,
     * any revalidation) until the player reaches STATE_READY or a named
     * error is surfaced. The UI shows a loading indicator whenever this
     * (or [isBuffering]) is true — a tap never stalls silently.
     */
    val isResolving: Boolean = false,
)

/** Pure loading-visibility decision shared by the mini player and the full player. */
fun showLoadingIndicator(isResolving: Boolean, isBuffering: Boolean): Boolean =
    isResolving || isBuffering

/**
 * Drives the shared [ExoPlayer] one resolved item at a time (SPEC §4).
 *
 * Queue semantics (order, shuffle, repeat) live in the core [PlayQueue];
 * this controller resolves the current track's stream on the IO dispatcher,
 * hands a MediaItem to the player, and advances the queue when playback
 * ends. On a resolve failure the queue STOPS: the named SPEC §6 error is
 * published on [namedError] and the queue position is kept — no silent
 * skipping (SPEC §4).
 *
 * Phase 2 road-switching: resolution goes through the core
 * [PlaybackResolver]. Signed in, the account road is tried first and the
 * anonymous road is the silent spare; signed out, only the anonymous road
 * runs. Every road attempt is recorded into [diagnostics]. Anonymous-road
 * successes additionally run the probe ladder (see [ladderProbeVerdict])
 * before the URL reaches the player, because on some carrier networks a
 * freshly resolved URL's first fetch intermittently 403s.
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

/** What the anonymous-road probe ladder should do after one probe. */
enum class LadderAction {
    /** Probe answered 2xx — hand this URL to the player. */
    PROCEED,

    /** Probe refused — throw the URL away and resolve fresh. */
    RE_RESOLVE,

    /** Ladder exhausted — hand the last URL to the player anyway. */
    HAND_TO_PLAYER_ANYWAY,
}

/** Number of (fresh resolve → probe) pairs the ladder runs at most. */
const val LADDER_MAX_PAIRS: Int = 3

/**
 * Consecutive failures autoplay tolerates among the tracks IT lined
 * up before it stops the chain and surfaces the failure like any
 * other (2026-10-27). A track the listener chose always stops on
 * failure (SPEC §4); a track autoplay chose skips forward instead —
 * but never forever: three dead tracks in a row means the road is
 * broken, not the songs, and spinning on is worse than stopping.
 */
const val MAX_AUTOPLAY_CONSECUTIVE_FAILURES: Int = 3

/** Bytes the ladder probe reads from a served response (see probeUrl). */
const val PROBE_READ_BYTES: Long = 2048L

/**
 * Pure ladder decision. [attemptsSoFar] is the 1-based number of the pair
 * whose probe just finished; [probeStatus] is the probe's HTTP status, or
 * null when the probe itself failed in transport (no status at all) —
 * a transport failure counts exactly like a refusal.
 *
 * A 2xx probe wins on ANY attempt. A refused probe re-resolves while
 * pairs remain, and hands the last resolved URL to the player once the
 * ladder is exhausted (the player's own fetch, plus the player-stage 403
 * re-resolve retry, remain as final chances — never error earlier than
 * we must).
 */
fun ladderProbeVerdict(attemptsSoFar: Int, probeStatus: Int?): LadderAction = when {
    probeStatus != null && probeStatus in 200..299 -> LadderAction.PROCEED
    attemptsSoFar >= LADDER_MAX_PAIRS -> LadderAction.HAND_TO_PLAYER_ANYWAY
    else -> LadderAction.RE_RESOLVE
}

/** What the window descent should do after one probe at one window. */
enum class WindowAction {
    /** This window answered 2xx — it wins for the stream. */
    WINNER,

    /** Refused at this window — try the next smaller window. */
    DESCEND,

    /** Stop descending this resolution (winner or not, see caller). */
    STOP,
}

/**
 * Pure window-descent decision (round 3). The ladder descends
 * [RangeInterceptor.STREAM_WINDOW_LADDER] WITHIN one resolution
 * before re-resolving: a 403 at the current window descends to the
 * next smaller window; a 2xx at ANY window wins immediately; any
 * other outcome (a non-403 refusal, or a transport failure with no
 * status at all) stops the descent for this resolution — shrinking
 * the window is the specific answer to a 403-shaped refusal, not a
 * generic retry — and the pair-level [ladderProbeVerdict] decides
 * what happens next. A 403 at the LAST window also stops: the
 * descent is exhausted and the pair verdict takes over.
 */
fun windowProbeVerdict(windowIndex: Int, windowCount: Int, probeStatus: Int?): WindowAction =
    when {
        probeStatus != null && probeStatus in 200..299 -> WindowAction.WINNER
        probeStatus == 403 && windowIndex < windowCount - 1 -> WindowAction.DESCEND
        else -> WindowAction.STOP
    }

/**
 * A resolved stream plus the Range window its loads must use: the
 * window the ladder probe won for it, or
 * [RangeInterceptor.STREAM_RANGE_WINDOW_BYTES] when the stream was
 * not probed (account road, or probing unwired). This is the
 * hand-off type from resolution to the player: [windowBytes]
 * travels with the stream and is recorded into the shared
 * [StreamWindowStore] at the hand-off (see handToPlayer), where the
 * shared stack's RangeInterceptor shapes the player's loads of
 * this exact URL with it.
 */
data class ResolvedPlayback(
    val info: StreamInfo,
    val windowBytes: Long,
)

/**
 * Pure ladder rotation: which context pair number [attemptIndex]
 * (0-based) resolves on. The ladder starts on the context the resolver
 * served, rotates to the next context in [road] on each following pair,
 * and wraps — with the two-context anonymous road that is
 * start → other → start → other… A [startContext] that is not in [road]
 * starts from the head of the road. An empty [road] is a programming
 * error and throws, like any empty rotation.
 */
fun ladderContextFor(
    attemptIndex: Int,
    road: List<YoutubeiContext>,
    startContext: YoutubeiContext,
): YoutubeiContext {
    require(road.isNotEmpty()) { "ladder road must not be empty" }
    val startIndex = road.indexOf(startContext).takeIf { it >= 0 } ?: 0
    val offset = if (attemptIndex < 0) 0 else attemptIndex
    return road[(startIndex + offset) % road.size]
}

class QueueController(
    private val context: Context,
    private val client: YoutubeiClient,
    private val player: ExoPlayer,
    private val recentsStore: RecentsStore,
    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
    /**
     * Supplies the current signed-in session, or null when signed out.
     * Read fresh at every resolve so sign-in/out takes effect on the
     * next track without rebuilding the controller.
     */
    private val sessionProvider: () -> AuthSession? = { null },
    /**
     * Revalidates the stored session (Lane B keep-alive) and returns the
     * refreshed session, or null when there is no live session. Invoked
     * at most once per track, only when the account road refused in a
     * way that looks like a dead session.
     */
    private val revalidateSession: (suspend () -> AuthSession?)? = null,
    /**
     * The app's shared OkHttp client, used for the anonymous-road probe
     * ladder. Null disables probing (the resolved URL goes straight to
     * the player, the pre-ladder behaviour).
     */
    private val probeClient: OkHttpClient? = null,
    /**
     * The shared per-stream window record (round 3): the SAME
     * [StreamWindowStore] instance the shared client's
     * RangeInterceptor reads. The ladder records each candidate
     * window here before probing (the bare probe fetch is then
     * shaped with it by the interceptor), leaves the winning window
     * recorded, and the hand-off re-records the winner for the URL
     * the player is about to load. Null means the descent cannot
     * shape probes, so the ladder falls back to probing the single
     * default window per pair (the pre-round-3 behaviour).
     */
    private val streamWindows: StreamWindowStore? = null,
    /**
     * Supplies the current stream-quality preference. Read fresh at
     * every resolve (and at every ladder re-resolve) so a Settings
     * change applies to the next track — and to the player-stage 403
     * re-resolve of the current one, which re-enters the same resolve
     * path — with no restart. The default keeps callers that do not
     * wire preferences on the core's own default.
     */
    private val qualityProvider: () -> QualityPreference = { QualityPreference.HIGH },
    /**
     * Supplies the playable local download for a track, or null when
     * the track is not DONE-downloaded (parity step 2, Lane F — see
     * `downloadedPlaybackFor` for the decision). Read fresh at every
     * play. The default returns null, so callers that do not wire
     * downloads behave exactly as before: every play resolves over
     * the network. When it returns a file, that file takes precedence
     * over ANY network resolution — the resolve is never attempted.
     */
    private val localFileProvider: (Track) -> DownloadedPlayback? = { null },
    /**
     * Supplies the `audio_version_in_radio` preference ("play the
     * audio version instead of the music video in radio"). Read fresh
     * at every radio start, Lane-E-style. The default (false) is the
     * pre-preference behavior. See `buildRadioQueue` for what the flag
     * can and cannot do today — it is threaded honestly, not faked.
     */
    private val audioVersionInRadioProvider: () -> Boolean = { false },
) {

    private val queue = PlayQueue(Random)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var resolveJob: Job? = null

    // -- Autoplay continuation (2026-10-27 queue-advancement hunt) ------
    //
    // The owner's report: "I click on the song, it plays, then it
    // stops." Queues were built for list taps all along; the chain
    // broke at the END — onTrackEnded/next() found queue.next() null
    // and paused, and nothing anywhere continued with watch-next
    // tracks. The fix reuses the radio machinery (client.related +
    // the pure continuation builder in RadioQueue.kt): when the
    // queue exhausts, related tracks are appended and play continues
    // through the same resolveAndPlay path as a tapped track.

    /** The in-flight related-fetch that extends an exhausted queue. */
    private var autoplayJob: Job? = null

    /** Video ids the current autoplay chain itself lined up. */
    private val autoplayTrackIds = mutableSetOf<String>()

    /** Consecutive failures among autoplay-lined tracks (see the cap). */
    private var consecutiveAutoplayFailures: Int = 0
    private var retriedCurrentTrack: Boolean = false
    private var revalidatedCurrentTrack: Boolean = false
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

    // -- Lane G: sleep timer, speed & pitch ------------------------------

    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    /** The armed sleep timer, if any. Owned here so it survives screen-off. */
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    /** Remaining time on a timed sleep timer, refreshed on the position poll. */
    val sleepTimerCountdownMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()

    private var sleepTimerJob: Job? = null

    private val _speedState = MutableStateFlow(SpeedUiState())
    /** The current playback speed + pitch-preservation choice. */
    val speedState: StateFlow<SpeedUiState> = _speedState.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                syncPlaybackState()
                if (playbackState == Player.STATE_READY) {
                    setResolving(false)
                    if (player.playWhenReady) {
                        retriedCurrentTrack = false
                        consecutiveAutoplayFailures = 0
                        // The app has played: the splash gate
                        // suppresses the splash for the rest of this
                        // process, background returns included.
                        SplashGate.markPlaybackHappened()
                        diagnostics.recordReady()
                    }
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
                // Autoplay-lined tracks skip forward on failure
                // instead of stalling the chain (user-chosen tracks
                // keep the SPEC §4 stop).
                val failedTrack = queue.current()
                if (failedTrack != null &&
                    trySkipFailedAutoplayTrack(failedTrack, "player error ${error.errorCode}")
                ) {
                    syncPlaybackState()
                    return
                }
                setResolving(false)
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
                // The sleep-timer countdown rides the same poll so the
                // player UI's remaining time stays honest.
                val timer = _sleepTimerState.value
                _sleepTimerRemainingMs.value =
                    if (timer.isArmed) {
                        sleepTimerRemainingMs(timer, System.currentTimeMillis())
                    } else {
                        null
                    }
                delay(POSITION_POLL_MS)
            }
        }
    }

    // -- Public API ---------------------------------------------------------

    /**
     * The listener took the wheel: any autoplay chain belonging to
     * the previous queue is over. Cancels the in-flight continuation
     * fetch and forgets which tracks autoplay had lined up, so a
     * failure on the NEW queue stops the way SPEC §4 says a chosen
     * track's failure stops.
     */
    private fun resetAutoplayChain() {
        autoplayJob?.cancel()
        autoplayJob = null
        autoplayTrackIds.clear()
        consecutiveAutoplayFailures = 0
    }

    fun playTracks(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        resetAutoplayChain()
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
            // End of queue with repeat off: keep going with
            // watch-next tracks instead of stopping (autoplay).
            val seed = queue.current()
            if (seed != null) {
                continueWithAutoplay(seed, fromTrackEnd = false)
            } else {
                player.pause()
                syncPlaybackState()
            }
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
        resetAutoplayChain()
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

    // -- Lane G public API -------------------------------------------------

    /**
     * Arms the sleep timer for [minutes]. The countdown is a deadline
     * plus a scheduled pause in this controller's scope — the same
     * process lifetime as the player and its service — so it fires
     * with the screen off. The pure decision lives in [SleepTimer.kt].
     */
    fun setSleepTimerMinutes(minutes: Int) {
        val now = System.currentTimeMillis()
        val state = sleepTimerTimed(minutes, now)
        _sleepTimerState.value = state
        _sleepTimerRemainingMs.value = sleepTimerRemainingMs(state, now)
        sleepTimerJob?.cancel()
        sleepTimerJob = scope.launch {
            delay(state.requestedMinutes * 60_000L)
            if (sleepTimerShouldPause(_sleepTimerState.value, System.currentTimeMillis())) {
                expireSleepTimer()
            }
        }
    }

    /** Arms the sleep timer to stop at the end of the current track. */
    fun setSleepTimerEndOfTrack() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = sleepTimerEndOfTrack()
        _sleepTimerRemainingMs.value = null
    }

    /** Disarms the sleep timer without touching playback. */
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState()
        _sleepTimerRemainingMs.value = null
    }

    private fun expireSleepTimer() {
        // Autoplay never defeats the sleep timer: a continuation
        // fetch still in flight dies with it.
        autoplayJob?.cancel()
        autoplayJob = null
        cancelSleepTimer()
        player.pause()
        syncPlaybackState()
        diagnostics.log("sleep-timer", "sleep timer expired — playback paused")
    }

    /** Applies a new playback speed to the live player, keeping the pitch choice. */
    fun setPlaybackSpeed(speed: Float) {
        val updated = _speedState.value.copy(
            speed = playbackParametersFor(speed, _speedState.value.preservePitch).speed,
        )
        _speedState.value = updated
        applyPlaybackParameters(updated)
    }

    /** Toggles pitch preservation and re-applies the current speed with it. */
    fun setPreservePitch(preservePitch: Boolean) {
        val updated = _speedState.value.copy(preservePitch = preservePitch)
        _speedState.value = updated
        applyPlaybackParameters(updated)
    }

    private fun applyPlaybackParameters(state: SpeedUiState) {
        val params = playbackParametersFor(state.speed, state.preservePitch)
        player.playbackParameters = PlaybackParameters(params.speed, params.pitch)
    }

    /**
     * Starts radio for [track]: the core client's `related()` radio
     * mix (`next` on `RDAMVM<videoId>`) becomes the new queue — radio
     * REPLACES the current queue, seed first (see [buildRadioQueue]).
     * A refusal surfaces as a named error; an empty mix says so
     * plainly instead of silently clearing the queue.
     */
    fun startRadio(track: Track) {
        _namedError.value = null
        scope.launch {
            val related = try {
                withContext(Dispatchers.IO) { client.related(track.videoId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _namedError.value = ErrorMessages.userMessage(e)
                return@launch
            }
            if (related.isEmpty()) {
                _namedError.value = "YouTube returned no radio tracks for this song."
                return@launch
            }
            val radioQueue = buildRadioQueue(
                seed = track,
                related = related,
                preferAudioVersion = audioVersionInRadioProvider(),
            )
            playTracks(radioQueue, 0)
        }
    }

    // -- Internals ----------------------------------------------------------

    private fun onTrackEnded() {
        // Sleep timer, end-of-track mode: stop here instead of
        // advancing. (A manual next() tap is not a track end and
        // never triggers this.)
        if (sleepTimerShouldStopAtTrackEnd(_sleepTimerState.value)) {
            cancelSleepTimer()
            player.pause()
            syncPlaybackState()
            diagnostics.log("sleep-timer", "end of track reached — playback stopped")
            return
        }
        // With RepeatMode.ONE the player loops internally and STATE_ENDED
        // is not delivered; reaching here means advance (repeat ALL wraps
        // inside PlayQueue.next()).
        val nextTrack = queue.next()
        syncQueueState()
        if (nextTrack != null) {
            resolveAndPlay(nextTrack)
        } else {
            // Queue exhausted: autoplay continues with watch-next
            // tracks instead of stopping (the owner's "it plays,
            // then it stops" report). The track that just ended is
            // still the queue's current — it seeds the continuation.
            val seed = queue.current()
            if (seed != null) {
                continueWithAutoplay(seed, fromTrackEnd = true)
            } else {
                player.pause()
                syncPlaybackState()
            }
        }
    }

    /**
     * Extends an exhausted queue with the seed's related/watch-next
     * tracks and plays on. Default behavior, not a setting: the
     * product is hands-free listening.
     *
     * Guards, in order:
     * - One fetch at a time: a second end-of-queue while a fetch is
     *   in flight is a no-op.
     * - Listener takeover: if the queue was replaced while fetching
     *   (a new tap, a radio start), the continuation is abandoned
     *   silently — the listener's queue wins.
     * - The sleep timer always wins: both its end-of-track mode and
     *   a timed deadline that passed during the fetch stop playback
     *   instead of continuing.
     * - An empty continuation or a failed fetch stops gracefully
     *   with a visible, honest state — never a silent spin.
     */
    private fun continueWithAutoplay(seed: Track, fromTrackEnd: Boolean) {
        if (autoplayJob?.isActive == true) return
        val queueAtRequest = queue.tracks
        val indexAtRequest = queue.currentIndex
        setResolving(true)
        autoplayJob = scope.launch {
            val related = try {
                withContext(Dispatchers.IO) { client.related(seed.videoId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (queue.tracks !== queueAtRequest) {
                // The listener replaced the queue mid-fetch; their
                // own resolve owns the resolving flag now.
                if (resolveJob?.isActive != true) setResolving(false)
                return@launch
            }
            if (fromTrackEnd &&
                sleepTimerShouldStopAtTrackEnd(_sleepTimerState.value)
            ) {
                cancelSleepTimer()
                player.pause()
                syncPlaybackState()
                diagnostics.log(
                    "sleep-timer",
                    "end of track reached — playback stopped",
                )
                setResolving(false)
                return@launch
            }
            val timer = _sleepTimerState.value
            if (timer.isArmed &&
                sleepTimerShouldPause(timer, System.currentTimeMillis())
            ) {
                expireSleepTimer()
                setResolving(false)
                return@launch
            }
            val continuation = if (related == null) {
                emptyList()
            } else {
                autoplayContinuation(
                    seed = seed,
                    related = related,
                    queuedIds = queueAtRequest.mapTo(HashSet()) { it.videoId },
                )
            }
            if (continuation.isEmpty()) {
                setResolving(false)
                player.pause()
                syncPlaybackState()
                _namedError.value = if (related == null) {
                    "Playback stopped — couldn't reach YouTube to line up a next song."
                } else {
                    "Playback stopped — YouTube didn't offer a next song to autoplay."
                }
                diagnostics.log(
                    "autoplay",
                    if (related == null) {
                        "related fetch failed at end of queue"
                    } else {
                        "no continuation offered at end of queue"
                    },
                )
                return@launch
            }
            autoplayTrackIds.addAll(continuation.map { it.videoId })
            queue.setQueue(queueAtRequest + continuation, indexAtRequest)
            syncQueueState()
            diagnostics.log(
                "autoplay",
                "lined up ${continuation.size} related tracks after ${seed.videoId}",
            )
            val nextTrack = queue.next()
            if (nextTrack != null) {
                resolveAndPlay(nextTrack)
            } else {
                setResolving(false)
                player.pause()
                syncPlaybackState()
            }
        }
    }

    /**
     * Skips a failed AUTOPLAY-lined track forward instead of
     * stalling the chain. Returns true when the failure was handled
     * by advancing. Only tracks autoplay itself lined up qualify —
     * a track the listener chose stops on failure exactly as SPEC
     * §4 requires — and only up to [MAX_AUTOPLAY_CONSECUTIVE_FAILURES]
     * in a row (a success resets the count in the READY transition).
     */
    private fun trySkipFailedAutoplayTrack(failed: Track, stage: String): Boolean {
        if (failed.videoId !in autoplayTrackIds) return false
        consecutiveAutoplayFailures += 1
        if (consecutiveAutoplayFailures > MAX_AUTOPLAY_CONSECUTIVE_FAILURES) {
            return false
        }
        val next = queue.next() ?: return false
        diagnostics.log(
            "autoplay",
            "autoplay track ${failed.videoId} failed at $stage — " +
                "skipping to ${next.videoId} " +
                "($consecutiveAutoplayFailures/$MAX_AUTOPLAY_CONSECUTIVE_FAILURES)",
        )
        syncQueueState()
        resolveAndPlay(next)
        return true
    }

    private fun resolveAndPlay(track: Track, isRetry: Boolean = false) {
        if (!isRetry) {
            retriedCurrentTrack = false
            revalidatedCurrentTrack = false
        }
        val preserveDiagnostics = keepDiagnosticsAttempt
        keepDiagnosticsAttempt = false
        ensureServiceStarted()
        _namedError.value = null
        setResolving(true)
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

            // Offline downloads take precedence over every network
            // road: a DONE download plays from its local file and the
            // resolver is never consulted (parity step 2, Lane F).
            val local = try {
                localFileProvider(track)
            } catch (e: Exception) {
                // A broken download lookup must not break playback:
                // fall through to the network roads.
                diagnostics.log(
                    "download",
                    "local download lookup failed (${e.javaClass.simpleName}) — using the network",
                )
                null
            }
            if (local != null) {
                diagnostics.log(
                    "download",
                    "playing the downloaded file for track ${track.videoId} — no network resolution",
                )
                handToPlayer(track, Uri.fromFile(local.file), local.mimeType)
                return@launch
            }

            val stream = try {
                resolveWithRoads(track)
            } catch (e: CancellationException) {
                setResolving(false)
                throw e
            } catch (e: Exception) {
                // An autoplay-lined track that fails to resolve skips
                // forward instead of stalling the chain.
                if (trySkipFailedAutoplayTrack(track, "resolve")) {
                    return@launch
                }
                // Named failure: stop the queue, keep the position, tell
                // the user (SPEC §4/§6). No silent skip. Resolver-stage
                // diagnostics were already recorded inside resolveWithRoads.
                setResolving(false)
                _namedError.value = ErrorMessages.userMessage(e)
                player.pause()
                syncPlaybackState()
                return@launch
            }

            handToPlayer(
                track,
                Uri.parse(stream.info.url),
                stream.info.mimeType,
                streamUrl = stream.info.url,
                windowBytes = stream.windowBytes,
            )
        }
    }

    /**
     * Hands one prepared item — a stream URL or a local file — to the
     * player. For a network stream ([streamUrl] + [windowBytes]
     * given), the winning window is recorded into the shared
     * [StreamWindowStore] BEFORE the item reaches the player, so the
     * shared stack shapes the player's very first load of this URL
     * with the window its probe won (round 3) — the record and the
     * diagnostics line happen here, at the hand-off, never after.
     */
    private fun handToPlayer(
        track: Track,
        uri: Uri,
        mimeType: String?,
        streamUrl: String? = null,
        windowBytes: Long? = null,
    ) {
        if (streamUrl != null && windowBytes != null) {
            streamWindows?.recordWinner(streamUrl, windowBytes)
            diagnostics.recordWinningWindow(windowBytes)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists.joinToString(", "))
            .setAlbumTitle(track.album)
            .setArtworkUri(track.thumbnailUrl?.let { Uri.parse(it) })
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(track.videoId)
            .setUri(uri)
            .setMimeType(mimeType)
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

    /**
     * Resolve [track] through the core road-switching resolver, applying
     * the auth-refusal revalidation retry and the anonymous-road probe
     * ladder. Returns the [ResolvedPlayback] to hand to the player —
     * the stream plus the Range window its loads must use. Throws the
     * named failure (the resolver's original exception signed-out, or
     * [AllRoadsRefused] signed-in) when every road refuses.
     */
    private suspend fun resolveWithRoads(track: Track): ResolvedPlayback {
        val resolver = PlaybackResolver(client)
        var auth = sessionProvider()
        while (true) {
            val resolved = try {
                withContext(Dispatchers.IO) {
                    resolver.resolve(track.videoId, auth, qualityProvider())
                }
            } catch (e: AllRoadsRefused) {
                diagnostics.recordRoadAttempt(
                    road = "account",
                    outcome = "refused (${e.accountError.javaClass.simpleName})",
                )
                diagnostics.recordRoadAttempt(
                    road = "anonymous",
                    outcome = "refused (${e.anonymousError.javaClass.simpleName})",
                )
                diagnostics.recordResolverOutcome(
                    contextClientName = YoutubeiContexts.WEB_REMIX.clientName,
                    contextClientVersion = YoutubeiContexts.WEB_REMIX.clientVersion,
                    exceptionClass = e.accountError.javaClass.simpleName,
                )
                // Keep-alive wiring: a dead-looking session gets ONE
                // revalidation + retry per track before we surface the
                // double refusal.
                val fresh = maybeRevalidate(e)
                if (fresh == null) throw e
                auth = fresh
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Signed-out path: the resolver rethrows the original
                // exception. (Any other single-road throw lands here too.)
                val servingContext = if (auth != null) {
                    YoutubeiContexts.WEB_REMIX
                } else {
                    YoutubeiContexts.IOS
                }
                if (auth == null) {
                    diagnostics.recordRoadAttempt(
                        road = "anonymous",
                        outcome = "refused (${e.javaClass.simpleName})",
                    )
                }
                diagnostics.recordResolverOutcome(
                    contextClientName = servingContext.clientName,
                    contextClientVersion = servingContext.clientVersion,
                    exceptionClass = e.javaClass.simpleName,
                )
                throw e
            }
            recordAttempts(resolved)
            return runLadderIfNeeded(track, resolved)
        }
    }

    /**
     * The one-shot session revalidation for an auth-looking double
     * refusal. Returns the refreshed session to retry with, or null when
     * revalidation does not apply (no hook wired, already done for this
     * track, the refusal does not look like a dead session, or the hook
     * found no live session).
     */
    private suspend fun maybeRevalidate(error: AllRoadsRefused): AuthSession? {
        val revalidate = revalidateSession ?: return null
        if (revalidatedCurrentTrack) return null
        val accountError = error.accountError as? OffleashException ?: return null
        if (!accountError.isAuthRefusal()) return null
        revalidatedCurrentTrack = true
        diagnostics.log(
            "session",
            "account road refused as signed out — revalidating the session once",
        )
        val fresh = try {
            revalidate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.log("session", "revalidation failed (${e.javaClass.simpleName})")
            return null
        }
        diagnostics.log(
            "session",
            if (fresh != null) {
                "revalidation returned a session — retrying the resolve once"
            } else {
                "revalidation found no live session"
            },
        )
        return fresh
    }

    /** Look a context up by its clientName across every road we know. */
    private fun contextByClientName(clientName: String?): YoutubeiContext? {
        if (clientName.isNullOrBlank()) return null
        val known = listOf(YoutubeiContexts.WEB_REMIX) + YoutubeiContexts.ANONYMOUS_ROAD
        return known.firstOrNull { it.clientName == clientName }
    }

    /**
     * The context a successful resolve was served on. Lane A's resolver
     * names it in the serving attempt's outcome ("served:ANDROID");
     * the account road resolves on WEB_REMIX, and an anonymous serve
     * with no named context falls back to the head of the anonymous
     * road (iOS).
     */
    private fun servedContextOf(resolved: ResolvedStream): YoutubeiContext {
        if (resolved.servedByRoad == "account") return YoutubeiContexts.WEB_REMIX
        val servedAttempt = resolved.attempts.lastOrNull { it.outcome.startsWith("served") }
        val named = servedAttempt?.outcome
            ?.substringAfter("served:", "")
            ?.takeIf { it.isNotBlank() }
        return contextByClientName(named)
            ?: YoutubeiContexts.ANONYMOUS_ROAD.firstOrNull()
            ?: YoutubeiContexts.IOS
    }

    /** Record every road attempt of a successful resolve, plus its format. */
    private fun recordAttempts(resolved: ResolvedStream) {
        for (attempt in resolved.attempts) {
            diagnostics.recordRoadAttempt(
                road = attempt.road,
                outcome = if (attempt.outcome.startsWith("served")) {
                    // Verbatim: Lane A names the serving context here
                    // ("served" or "served:ANDROID").
                    attempt.outcome
                } else {
                    "refused (${attempt.outcome})"
                },
            )
        }
        val servingContext = servedContextOf(resolved)
        diagnostics.recordResolverOutcome(
            contextClientName = servingContext.clientName,
            contextClientVersion = servingContext.clientVersion,
            exceptionClass = null,
        )
        diagnostics.recordChosenFormat(resolved.info.mimeType, resolved.info.bitrate, resolved.info.url)
    }

    /**
     * Anonymous-road probe ladder (owner inputs, 2026-10-23, as amended
     * by the coordinator's probe evidence): on the owner's carrier a
     * resolved stream URL's bounded fetches can be refused 403 again and
     * again — a network-identity-specific stream-gate refusal (the same
     * track's bounded fetch returns 206 from another network, and a
     * second anonymous context serves the track fine). So before the
     * player ever sees an anonymous-road URL, the ladder runs up to
     * [LADDER_MAX_PAIRS] pairs of (resolve → immediate bounded-range
     * probe), ROTATING contexts across the anonymous road
     * ([YoutubeiContexts.ANONYMOUS_ROAD]): pair 1 probes the URL the
     * resolver already served, pair 2 resolves fresh on the OTHER
     * context and probes that, pair 3 returns to the first context with
     * a fresh resolution. The first pair whose probe answers 2xx wins.
     * If every pair fails, the LAST resolved URL goes to the player
     * anyway — its own fetch and the player-stage 403 retry remain as
     * final chances; the ladder never errors earlier than we must.
     *
     * Round 3 (window descent): WITHIN one pair's resolution, the
     * probe is itself a descent over
     * [RangeInterceptor.STREAM_WINDOW_LADDER] (1 MiB → 256 KiB →
     * 64 KiB → 2 KiB). Before each probe fetch the candidate window
     * is recorded into the shared [StreamWindowStore] for that URL,
     * so the bare probe is shaped with exactly that window by the
     * shared stack's RangeInterceptor; a 403 descends to the next
     * smaller window BEFORE any re-resolve, and the first window to
     * answer 2xx wins for that stream — it stays recorded, travels
     * out in the returned [ResolvedPlayback], and is re-recorded at
     * the hand-off so the player's loads of that stream are shaped
     * with the winning window too. Every window tried is recorded
     * into diagnostics (`probe-window:` lines). When no window store
     * is wired the descent cannot shape probes, so each pair probes
     * the single default window, as before round 3.
     *
     * Account-road successes are never probed: the ladder is scoped to
     * the anonymous road only. When the account road refuses and the
     * anonymous cascade serves, the ladder runs on that result, starting
     * from the cascade-served context.
     */
    private suspend fun runLadderIfNeeded(
        track: Track,
        first: ResolvedStream,
    ): ResolvedPlayback {
        val defaultWindow = RangeInterceptor.STREAM_RANGE_WINDOW_BYTES
        if (first.servedByRoad != "anonymous") {
            return ResolvedPlayback(first.info, defaultWindow)
        }
        val probe = probeClient
        if (probe == null) {
            diagnostics.log(
                "ladder",
                "no probe client wired — handing the resolved URL to the player unprobed",
            )
            return ResolvedPlayback(first.info, defaultWindow)
        }
        val road = YoutubeiContexts.ANONYMOUS_ROAD
        if (road.isEmpty()) {
            diagnostics.log(
                "ladder",
                "anonymous road has no contexts — handing the resolved URL to the player",
            )
            return ResolvedPlayback(first.info, defaultWindow)
        }
        val windows = if (streamWindows != null) {
            RangeInterceptor.STREAM_WINDOW_LADDER
        } else {
            listOf(defaultWindow)
        }
        val startContext = servedContextOf(first)
        // The last URL we managed to resolve; handed to the player when
        // the ladder exhausts, even if a later pair's resolve failed.
        var lastInfo = first.info
        var pair = 1
        while (pair <= LADDER_MAX_PAIRS) {
            val pairContext = ladderContextFor(pair - 1, road, startContext)
            val info: StreamInfo = if (pair == 1) {
                // Pair 1 probes the resolver's own fresh resolution —
                // no second resolve needed.
                first.info
            } else {
                try {
                    val fresh = withContext(Dispatchers.IO) {
                        client.streamInfo(track.videoId, pairContext, qualityProvider())
                    }
                    diagnostics.recordResolverOutcome(
                        contextClientName = pairContext.clientName,
                        contextClientVersion = pairContext.clientVersion,
                        exceptionClass = null,
                    )
                    diagnostics.recordChosenFormat(fresh.mimeType, fresh.bitrate, fresh.url)
                    lastInfo = fresh
                    fresh
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A refused resolve is a failed pair, not the end:
                    // rotate on while pairs remain; on the last pair,
                    // hand the player the last URL we did resolve.
                    diagnostics.recordRoadAttempt(
                        road = "anonymous",
                        outcome = "resolve pair $pair/$LADDER_MAX_PAIRS " +
                            "context ${pairContext.clientName} " +
                            "refused (${e.javaClass.simpleName})",
                    )
                    if (pair >= LADDER_MAX_PAIRS) {
                        diagnostics.log(
                            "ladder",
                            "final pair's resolve refused — handing the last resolved " +
                                "URL to the player anyway",
                        )
                        return ResolvedPlayback(lastInfo, windows.last())
                    }
                    pair++
                    continue
                }
            }
            // Window descent within this pair's one resolution: each
            // candidate window is recorded for the URL first, so the
            // bare probe fetch is shaped with exactly that window by
            // the shared stack; the first window answered 2xx wins.
            var winningWindow: Long? = null
            var pairResult: ProbeResult? = null
            for (windowIndex in windows.indices) {
                val window = windows[windowIndex]
                streamWindows?.recordWinner(info.url, window)
                val result = probeUrl(probe, info.url)
                pairResult = result
                diagnostics.recordProbeWindow(
                    pair = pair,
                    pairs = LADDER_MAX_PAIRS,
                    contextName = pairContext.clientName,
                    windowBytes = window,
                    status = result.status,
                    exceptionClass = result.exceptionClass,
                )
                when (windowProbeVerdict(windowIndex, windows.size, result.status)) {
                    WindowAction.WINNER -> {
                        winningWindow = window
                        break
                    }
                    WindowAction.DESCEND -> continue
                    WindowAction.STOP -> break
                }
            }
            val result = pairResult ?: ProbeResult(status = null, exceptionClass = null)
            val winner = winningWindow
            val statusText = when {
                result.status != null -> "HTTP ${result.status}"
                result.exceptionClass != null -> "probe failed (${result.exceptionClass})"
                else -> "probe failed"
            }
            val pairLabel = "probe $pair/$LADDER_MAX_PAIRS context ${pairContext.clientName}"
            if (winner != null) {
                diagnostics.recordRoadAttempt(
                    road = "anonymous",
                    outcome = "$pairLabel $statusText window=$winner — serving this URL",
                )
                return ResolvedPlayback(info, winner)
            }
            when (ladderProbeVerdict(pair, result.status)) {
                LadderAction.PROCEED -> {
                    // Unreachable: a 2xx probe sets a winning window
                    // above. Kept so the pair verdict's full matrix
                    // stays handled in one place.
                    diagnostics.recordRoadAttempt(
                        road = "anonymous",
                        outcome = "$pairLabel $statusText — serving this URL",
                    )
                    return ResolvedPlayback(info, windows.first())
                }
                LadderAction.HAND_TO_PLAYER_ANYWAY -> {
                    diagnostics.recordRoadAttempt(
                        road = "anonymous",
                        outcome = "$pairLabel $statusText",
                    )
                    diagnostics.log(
                        "ladder",
                        "all $LADDER_MAX_PAIRS probes refused — handing the last resolved " +
                            "URL to the player anyway, at the smallest window " +
                            "(${windows.last()} bytes)",
                    )
                    return ResolvedPlayback(info, windows.last())
                }
                LadderAction.RE_RESOLVE -> {
                    diagnostics.recordRoadAttempt(
                        road = "anonymous",
                        outcome = "$pairLabel $statusText — rotating context, resolving fresh",
                    )
                    pair++
                }
            }
        }
        // Unreachable: the verdict on the final pair always returns.
        return ResolvedPlayback(lastInfo, windows.last())
    }

    /** Outcome of one ladder probe: an HTTP status, or the transport failure's class. */
    private data class ProbeResult(val status: Int?, val exceptionClass: String?)

    /**
     * One probe GET of [url] through the shared client, in the
     * player's EXACT first-fetch shape — no headers of its own at
     * all, so the shared stack shapes it (bounded window Range,
     * default UA, no Accept-Encoding) precisely as it shapes the
     * player's first fetch, and the first bytes are actually read.
     * Round 3: the bounded window the stack shapes it with is the
     * ladder's current CANDIDATE window for this URL, recorded in
     * the shared StreamWindowStore by runLadderIfNeeded just before
     * this call — the probe still presets nothing itself, so a
     * window's probe verdict is a verdict on the exact shape the
     * player would send at that window.
     * A 2xx here therefore predicts the player's own first fetch
     * instead of merely proving the URL answers some other shape.
     *
     * Until the 2026-10-24 hunt this probe preset `Range: bytes=0-0`
     * and read nothing — a shape the player never sends — and it
     * certified URLs the player was then refused on (probe 206,
     * player 403, same URL, same second, on the owner's phone). The
     * parity is pinned by StreamShapeParityTest and
     * PlayerFetchShapeContractTest.
     */
    private suspend fun probeUrl(client: OkHttpClient, url: String): ProbeResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val status = response.code
                    if (status in 200..299) {
                        // Read the first bytes, like the player's
                        // first read: a served status with an
                        // unreadable body is not a serving URL.
                        try {
                            response.body?.source()?.request(PROBE_READ_BYTES)
                        } catch (e: Exception) {
                            // The status already answered the ladder's
                            // question; a body-read hiccup after a 2xx
                            // does not change the verdict.
                        }
                    }
                    ProbeResult(status = status, exceptionClass = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProbeResult(status = null, exceptionClass = e.javaClass.simpleName)
            }
        }

    private fun setResolving(resolving: Boolean) {
        val current = _playbackState.value
        if (current.isResolving != resolving) {
            _playbackState.value = current.copy(isResolving = resolving)
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
            revalidatedCurrentTrack = false
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
            // syncPlaybackState rebuilds the snapshot from the player;
            // the resolving flag is controller-owned and carried over.
            isResolving = _playbackState.value.isResolving,
        )
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
    }
}
