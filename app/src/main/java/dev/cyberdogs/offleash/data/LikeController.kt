package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.LikeStatus
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.playback.LikeUiState
import dev.cyberdogs.offleash.playback.likeToggleRolledBack
import dev.cyberdogs.offleash.playback.likeToggleSucceeded
import dev.cyberdogs.offleash.playback.optimisticLikeToggle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns like state for every track surface (Lane G).
 *
 * Signed in, a toggle writes to the account through the core client's
 * `setLike` (WEB_REMIX `like/like` / `like/removelike`) with an
 * optimistic UI flip and an honest rollback — state restored plus a
 * named [error] — when the write fails. Signed out, a toggle only
 * updates the on-device record in [AppPreferences], and the UI labels
 * it device-only (see `likeActionLabel`); it never pretends to touch
 * an account.
 *
 * The per-track state starts from the on-device record. The player
 * additionally refreshes the CURRENT track's state from the account
 * (`likeStatus`) when signed in, so a like set elsewhere shows up
 * without the user toggling first. List rows do not refresh per row —
 * one player call per rendered row would flood the API.
 */
class LikeController(
    private val client: YoutubeiClient,
    private val preferences: AppPreferences,
    private val sessionProvider: () -> AuthSession?,
    /**
     * The Google OAuth access token (0.7.0). IO THREADS ONLY — the
     * provider may refresh the token, which is blocking network
     * work. When it yields a token, that token is the account road
     * for likes, ahead of the cookie session. [bearerSessionPresent]
     * is its cheap companion (a stored Google session exists), safe
     * on any thread, for the branch decisions that must not block.
     * Both default to none so existing constructions (and the
     * cookie road alone) behave exactly as before.
     */
    private val bearerProvider: () -> String? = { null },
    private val bearerSessionPresent: () -> Boolean = { false },
    private val scope: CoroutineScope,
) {

    private val _states = MutableStateFlow<Map<String, LikeUiState>>(
        preferences.localLikedVideoIds().associateWith { videoId ->
            LikeUiState(liked = true, accountBacked = sessionProvider() != null)
        },
    )
    val states: StateFlow<Map<String, LikeUiState>> = _states.asStateFlow()

    private val _accountBacked = MutableStateFlow(sessionProvider() != null)
    /** True while a session exists — like writes go to the account. */
    val accountBacked: StateFlow<Boolean> = _accountBacked.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Plain-language copy for the last failed like write, or null. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Called by the container whenever sign-in state flips. */
    fun setAccountBacked(backed: Boolean) {
        _accountBacked.value = backed
        _states.value = _states.value.mapValues { (_, state) ->
            state.copy(accountBacked = backed)
        }
    }

    fun stateFor(videoId: String): LikeUiState =
        _states.value[videoId]
            ?: LikeUiState(liked = false, accountBacked = _accountBacked.value)

    fun isLiked(videoId: String): Boolean = stateFor(videoId).liked

    fun clearError() {
        _error.value = null
    }

    fun toggle(track: Track) {
        val current = stateFor(track.videoId)
        val optimistic = optimisticLikeToggle(current)
        if (optimistic == current) return // a write for this track is already in flight
        _states.value = _states.value + (track.videoId to optimistic)
        val auth = sessionProvider()
        if (auth == null && !bearerSessionPresent()) {
            // Device-only like: persist locally and finish — there is
            // no account write to confirm or roll back.
            persistLocal()
            update(track.videoId, likeToggleSucceeded(optimistic))
            return
        }
        scope.launch {
            // The bearer is read HERE, on the IO thread: obtaining it
            // can mean a token refresh, and a UI-thread toggle must
            // never block on the network.
            var wroteToAccount = false
            val failure = try {
                withContext(Dispatchers.IO) {
                    val bearer = bearerProvider()
                    when {
                        bearer != null -> {
                            client.setLikeOAuth(track.videoId, bearer, optimistic.liked)
                            wroteToAccount = true
                        }
                        auth != null -> {
                            client.setLike(track.videoId, auth, optimistic.liked)
                            wroteToAccount = true
                        }
                        else -> Unit // the session ended mid-toggle: device-only outcome
                    }
                }
                null
            } catch (e: Exception) {
                e
            }
            if (failure == null && !wroteToAccount) {
                persistLocal()
                update(track.videoId, likeToggleSucceeded(stateFor(track.videoId)))
                return@launch
            }
            val latest = stateFor(track.videoId)
            if (failure == null) {
                update(track.videoId, likeToggleSucceeded(latest))
                persistLocal()
            } else {
                update(track.videoId, likeToggleRolledBack(latest))
                _error.value =
                    "Couldn't update your like on YouTube — check your connection and try again."
            }
        }
    }

    /**
     * Refreshes one track's state from the account's own report
     * (`likeStatus`). Signed out, or when the response carries no
     * likeStatus (an honest unknown), the current state stands.
     */
    fun refreshAccountStatus(track: Track) {
        val auth = sessionProvider()
        if (auth == null && !bearerSessionPresent()) return
        scope.launch {
            val status = try {
                withContext(Dispatchers.IO) {
                    // Bearer read on the IO thread, as in toggle().
                    val bearer = bearerProvider()
                    when {
                        bearer != null -> client.likeStatusOAuth(track.videoId, bearer)
                        auth != null -> client.likeStatus(track.videoId, auth)
                        else -> null
                    }
                }
            } catch (e: Exception) {
                null
            }
            when (status) {
                LikeStatus.LIKED -> {
                    update(
                        track.videoId,
                        stateFor(track.videoId).copy(liked = true, accountBacked = true),
                    )
                    persistLocal()
                }
                LikeStatus.DISLIKED, LikeStatus.INDIFFERENT -> {
                    update(
                        track.videoId,
                        stateFor(track.videoId).copy(liked = false, accountBacked = true),
                    )
                    persistLocal()
                }
                null -> Unit // unknown stays unknown — never guessed
            }
        }
    }

    private fun update(videoId: String, state: LikeUiState) {
        _states.value = _states.value + (videoId to state)
    }

    private fun persistLocal() {
        val likedIds = _states.value.filterValues { it.liked }.keys
        preferences.setLocalLikedVideoIds(likedIds)
    }
}
