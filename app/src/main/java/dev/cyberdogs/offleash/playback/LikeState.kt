package dev.cyberdogs.offleash.playback

/**
 * Like-toggle state logic (Lane G), pure so the optimistic-UI rules
 * are unit-testable without Android or a network.
 *
 * The rules: a toggle flips [LikeUiState.liked] immediately
 * (optimistic) and marks the state [LikeUiState.pending]; a confirmed
 * write clears pending and keeps the flipped value; a failed write
 * rolls back to the last account-confirmed value and clears pending.
 *
 * A toggle attempted while one is already pending is NOT ignored
 * (bug sweep round 1): it flips the intent again, and the
 * controller's per-track write drain serializes the writes so the
 * LAST intent is the one the account ends with. The old rule —
 * ignore the second toggle — silently dropped a user's
 * like→unlike, leaving the account liked against their last tap.
 * Out-of-order landing is prevented by the drain (one write in
 * flight per track, in intent order), not by dropping taps.
 */
data class LikeUiState(
    val liked: Boolean = false,
    val accountBacked: Boolean = false,
    val pending: Boolean = false,
)

/**
 * The optimistic flip. Always flips — while a write is pending the
 * flip records the newer intent (the state stays pending), and the
 * controller's drain makes the account converge to it.
 */
fun optimisticLikeToggle(state: LikeUiState): LikeUiState =
    state.copy(liked = !state.liked, pending = true)

/** The write landed: keep the optimistic value, clear pending. */
fun likeToggleSucceeded(state: LikeUiState): LikeUiState = state.copy(pending = false)

/**
 * The write failed: restore the account-confirmed value and clear
 * pending. The confirmed value is an explicit input (chaos sweep
 * round 2): the previous shape flipped [LikeUiState.liked] once,
 * which is only the pre-toggle value when EXACTLY ONE toggle is
 * outstanding. A mashed toggle sequence (like→unlike→like, or any
 * even count back to the confirmed value) rolled back to the WRONG
 * side — the UI claimed the opposite of what the account held.
 * Callers pass the last value the account is known to hold (the
 * LikeController drain tracks exactly that per track); with no
 * write pending there is nothing to roll back.
 */
fun likeToggleRolledBack(state: LikeUiState, accountConfirmedLiked: Boolean): LikeUiState =
    if (state.pending) {
        state.copy(liked = accountConfirmedLiked, pending = false)
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
