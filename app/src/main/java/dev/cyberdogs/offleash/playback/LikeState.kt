package dev.cyberdogs.offleash.playback

/**
 * Like-toggle state logic (Lane G), pure so the optimistic-UI rules
 * are unit-testable without Android or a network.
 *
 * The rules: a toggle flips [LikeUiState.liked] immediately
 * (optimistic) and marks the state [LikeUiState.pending]; a confirmed
 * write clears pending and keeps the flipped value; a failed write
 * rolls back to the pre-toggle value and clears pending. A toggle
 * attempted while one is already pending is ignored — two in-flight
 * writes for the same track could land out of order and leave the UI
 * claiming the opposite of the account.
 */
data class LikeUiState(
    val liked: Boolean = false,
    val accountBacked: Boolean = false,
    val pending: Boolean = false,
)

/** The optimistic flip. Returns the unchanged state when a write is already pending. */
fun optimisticLikeToggle(state: LikeUiState): LikeUiState =
    if (state.pending) {
        state
    } else {
        state.copy(liked = !state.liked, pending = true)
    }

/** The write landed: keep the optimistic value, clear pending. */
fun likeToggleSucceeded(state: LikeUiState): LikeUiState = state.copy(pending = false)

/** The write failed: restore the pre-toggle value, clear pending. */
fun likeToggleRolledBack(state: LikeUiState): LikeUiState =
    if (state.pending) {
        state.copy(liked = !state.liked, pending = false)
    } else {
        state
    }

/**
 * The menu/button label for the like action. Signed out, the like is
 * local-only and the label says so plainly — a device-local like must
 * never dress itself up as an account like.
 */
fun likeActionLabel(state: LikeUiState): String = when {
    state.liked && state.accountBacked -> "Remove like"
    state.liked -> "Remove like (on this device only)"
    state.accountBacked -> "Like"
    else -> "Like (on this device only)"
}
