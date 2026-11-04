package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.playback.LikeUiState
import dev.cyberdogs.offleash.playback.likeToggleSucceeded
import dev.cyberdogs.offleash.playback.optimisticLikeToggle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns like state for every track surface (Lane G).
 *
 * Account-free shape (0.9.0, owner's 2026-11-04 order): a like is
 * OffLeash's own on-device state. A toggle updates the on-device
 * record in [AppPreferences] (the video ids behind every heart) and
 * the [LikedTrackStore] (the track rows behind the Library's Liked
 * songs list) — nothing else. There is no account write, no account
 * refresh, and no session to consult: the controller takes no
 * client and no session provider, so no code path can reach an
 * account from a like.
 *
 * The seeded-provenance half of the old persisted pair is retired
 * with the account road: [AppPreferences.setLikedState] is still
 * the single-write persistence, always called with an empty seed
 * set, so a file an older build left with account-seeded ids reads
 * back as plain on-device likes — which is what they are now.
 */
class LikeController(
    private val preferences: AppPreferences,
    private val likedTrackStore: LikedTrackStore? = null,
    private val scope: CoroutineScope? = null,
) {

    private val _states = MutableStateFlow<Map<String, LikeUiState>>(
        preferences.localLikedVideoIds().associateWith { videoId ->
            LikeUiState(liked = true, accountBacked = false)
        },
    )
    val states: StateFlow<Map<String, LikeUiState>> = _states.asStateFlow()

    /**
     * Likes are never account-backed in the account-free product.
     * The flow stays so existing collectors keep compiling; it is
     * permanently false.
     */
    private val _accountBacked = MutableStateFlow(false)
    val accountBacked: StateFlow<Boolean> = _accountBacked.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Plain-language copy for the last failed like write, or null. */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun stateFor(videoId: String): LikeUiState =
        _states.value[videoId]
            // A like persisted before this controller existed (an
            // older build's record, read at first ask) is a like:
            // the persisted record is the fallback for any id the
            // toggle map has not seen yet.
            ?: if (videoId in preferences.localLikedVideoIds()) {
                LikeUiState(liked = true, accountBacked = false)
            } else {
                LikeUiState(liked = false, accountBacked = false)
            }

    fun isLiked(videoId: String): Boolean = stateFor(videoId).liked

    fun clearError() {
        _error.value = null
    }

    private val intentLock = Any()

    /**
     * Flips the like for [track] on this device and persists it.
     * The flip is immediate and final — there is no remote write to
     * confirm or roll back — and the liked-track store follows in
     * the controller's scope so the Library's Liked songs list
     * shows (or drops) the row.
     */
    fun toggle(track: Track) {
        val optimistic: LikeUiState
        synchronized(intentLock) {
            optimistic = likeToggleSucceeded(
                optimisticLikeToggle(stateFor(track.videoId)),
            )
            _states.value = _states.value + (track.videoId to optimistic)
        }
        persistLikeState()
        val store = likedTrackStore
        val storeScope = scope
        if (store != null && storeScope != null) {
            storeScope.launch {
                if (optimistic.liked) {
                    store.recordLiked(track)
                } else {
                    store.removeLiked(track.videoId)
                }
            }
        }
    }

    /**
     * Learns the track behind an already-liked id (an older build
     * persisted ids without track rows). When [track] is liked,
     * its row joins the liked-track store so the Library list can
     * show it; when it is not liked, nothing is recorded.
     */
    fun rememberLikedTrack(track: Track) {
        if (!isLiked(track.videoId)) return
        val store = likedTrackStore ?: return
        val storeScope = scope ?: return
        storeScope.launch { store.recordLiked(track) }
    }

    private fun persistLikeState() {
        val snapshot = synchronized(intentLock) {
            _states.value.filterValues { it.liked }.keys
        }
        preferences.setLikedState(snapshot, emptySet())
    }
}
