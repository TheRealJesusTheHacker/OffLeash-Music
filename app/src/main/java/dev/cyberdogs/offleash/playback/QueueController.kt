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
import dev.cyberdogs.offleash.data.RecentsStore
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
 * User agent paired with the stream URLs we resolve. Anonymous-road
 * streams are fetched through the core client's iOS context, so playback
 * requests present the same iOS YouTube app UA the URLs were issued to.
 * (Account-road streams are resolved on the WEB_REMIX context but are
 * still fetched by the same player stack; the UA pairing that matters
 * for googlevideo is the network identity, not the context label.)
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
     * Supplies the current stream-quality preference. Read fresh at
     * every resolve (and at every ladder re-resolve) so a Settings
     * change applies to the next track — and to the player-stage 403
     * re-resolve of the current one, which re-enters the same resolve
     * path — with no restart. The default keeps callers that do not
     * wire preferences on the core's own default.
     */
    private val qualityProvider: () -> QualityPreference = { QualityPreference.HIGH },
) {

    private val queue = PlayQueue(Random)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var resolveJob: Job? = null
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

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                syncPlaybackState()
                if (playbackState == Player.STATE_READY) {
                    setResolving(false)
                    if (player.playWhenReady) {
                        retriedCurrentTrack = false
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
            val stream = try {
                resolveWithRoads(track)
            } catch (e: CancellationException) {
                setResolving(false)
                throw e
            } catch (e: Exception) {
                // Named failure: stop the queue, keep the position, tell
                // the user (SPEC §4/§6). No silent skip. Resolver-stage
                // diagnostics were already recorded inside resolveWithRoads.
                setResolving(false)
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

    /**
     * Resolve [track] through the core road-switching resolver, applying
     * the auth-refusal revalidation retry and the anonymous-road probe
     * ladder. Returns the [StreamInfo] to hand to the player. Throws the
     * named failure (the resolver's original exception signed-out, or
     * [AllRoadsRefused] signed-in) when every road refuses.
     */
    private suspend fun resolveWithRoads(track: Track): StreamInfo {
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
     * Account-road successes are never probed: the ladder is scoped to
     * the anonymous road only. When the account road refuses and the
     * anonymous cascade serves, the ladder runs on that result, starting
     * from the cascade-served context.
     */
    private suspend fun runLadderIfNeeded(
        track: Track,
        first: ResolvedStream,
    ): StreamInfo {
        if (first.servedByRoad != "anonymous") return first.info
        val probe = probeClient
        if (probe == null) {
            diagnostics.log(
                "ladder",
                "no probe client wired — handing the resolved URL to the player unprobed",
            )
            return first.info
        }
        val road = YoutubeiContexts.ANONYMOUS_ROAD
        if (road.isEmpty()) {
            diagnostics.log(
                "ladder",
                "anonymous road has no contexts — handing the resolved URL to the player",
            )
            return first.info
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
                        return lastInfo
                    }
                    pair++
                    continue
                }
            }
            val result = probeUrl(probe, info.url)
            val statusText = when {
                result.status != null -> "HTTP ${result.status}"
                result.exceptionClass != null -> "probe failed (${result.exceptionClass})"
                else -> "probe failed"
            }
            val pairLabel = "probe $pair/$LADDER_MAX_PAIRS context ${pairContext.clientName}"
            when (ladderProbeVerdict(pair, result.status)) {
                LadderAction.PROCEED -> {
                    diagnostics.recordRoadAttempt(
                        road = "anonymous",
                        outcome = "$pairLabel $statusText — serving this URL",
                    )
                    return info
                }
                LadderAction.HAND_TO_PLAYER_ANYWAY -> {
                    diagnostics.recordRoadAttempt(
                        road = "anonymous",
                        outcome = "$pairLabel $statusText",
                    )
                    diagnostics.log(
                        "ladder",
                        "all $LADDER_MAX_PAIRS probes refused — handing the last resolved " +
                            "URL to the player anyway",
                    )
                    return info
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
        return lastInfo
    }

    /** Outcome of one ladder probe: an HTTP status, or the transport failure's class. */
    private data class ProbeResult(val status: Int?, val exceptionClass: String?)

    /**
     * One bounded-range probe GET of [url] through the shared client.
     * The header is already bounded (`bytes=0-0`), so the shared stack's
     * RangeInterceptor leaves it untouched. The body is closed without
     * being read — only the status matters.
     */
    private suspend fun probeUrl(client: OkHttpClient, url: String): ProbeResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    ProbeResult(status = response.code, exceptionClass = null)
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
