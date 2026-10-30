package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Like optimistic-toggle logic (Lane G): flip first, confirm or roll
 * back honestly, never double-write, and label device-only likes as
 * device-only.
 */
class LikeStateTest {

    @Test
    fun toggleFlipsOptimisticallyAndMarksPending() {
        val next = optimisticLikeToggle(LikeUiState(liked = false, accountBacked = true))
        assertTrue(next.liked)
        assertTrue(next.pending)

        val off = optimisticLikeToggle(LikeUiState(liked = true, accountBacked = true))
        assertFalse(off.liked)
        assertTrue(off.pending)
    }

    @Test
    fun successKeepsTheOptimisticValueAndClearsPending() {
        val pending = optimisticLikeToggle(LikeUiState(liked = false))
        val done = likeToggleSucceeded(pending)
        assertTrue(done.liked)
        assertFalse(done.pending)
    }

    @Test
    fun failureRollsBackToThePreToggleValue() {
        val before = LikeUiState(liked = true, accountBacked = true)
        val rolledBack = likeToggleRolledBack(
            optimisticLikeToggle(before),
            accountConfirmedLiked = before.liked,
        )
        assertEquals(before.liked, rolledBack.liked)
        assertFalse(rolledBack.pending)

        val beforeUnlike = LikeUiState(liked = false, accountBacked = true)
        val rolledBackUnlike = likeToggleRolledBack(
            optimisticLikeToggle(beforeUnlike),
            accountConfirmedLiked = beforeUnlike.liked,
        )
        assertFalse(rolledBackUnlike.liked)
        assertFalse(rolledBackUnlike.pending)
    }

    @Test
    fun rollbackWithoutAPendingWriteChangesNothing() {
        val settled = LikeUiState(liked = true, accountBacked = true, pending = false)
        assertEquals(
            settled,
            likeToggleRolledBack(settled, accountConfirmedLiked = false),
        )
    }

    @Test
    fun rollbackAfterMashedTogglesRestoresTheConfirmedValueNotAFlip() {
        // Chaos sweep round 2: with several toggles outstanding,
        // "flip once" is not the pre-toggle value — an EVEN number
        // of mashes lands back on the confirmed value, and an odd
        // number lands on its opposite; a naive single flip gets
        // both wrong half the time. Rollback must restore the
        // account-confirmed value the caller passes in.
        var state = LikeUiState(liked = false, accountBacked = true)
        repeat(2) { state = optimisticLikeToggle(state) } // like → unlike
        assertFalse(state.liked)
        assertTrue(state.pending)
        val rolledBackEven = likeToggleRolledBack(state, accountConfirmedLiked = false)
        assertFalse(rolledBackEven.liked)
        assertFalse(rolledBackEven.pending)

        var state2 = LikeUiState(liked = true, accountBacked = true)
        repeat(3) { state2 = optimisticLikeToggle(state2) } // unlike → like → unlike
        assertFalse(state2.liked)
        val rolledBackOdd = likeToggleRolledBack(state2, accountConfirmedLiked = true)
        assertTrue(rolledBackOdd.liked)
        assertFalse(rolledBackOdd.pending)
    }

    @Test
    fun mashedTogglesConvergeToTheLastIntentWhenWritesLand() {
        // The drain contract at the pure seam: flips accumulate as
        // intent while pending, and one success settles the state
        // on the LAST intent — like→unlike→like ends liked.
        var state = LikeUiState(liked = false, accountBacked = true)
        state = optimisticLikeToggle(state)
        state = optimisticLikeToggle(state)
        state = optimisticLikeToggle(state)
        val settled = likeToggleSucceeded(state)
        assertTrue(settled.liked)
        assertFalse(settled.pending)
    }

    @Test
    fun aSecondToggleWhilePendingFlipsTheIntentAndStaysPending() {
        // Bug sweep round 1: the old rule ignored the second toggle,
        // so like→unlike left the account liked against the user's
        // last tap. The flip now records the newer intent; the
        // controller's serialized drain converges the account to it
        // (pinned end-to-end in LikeControllerTest).
        val pending = optimisticLikeToggle(LikeUiState(liked = false))
        val flippedBack = optimisticLikeToggle(pending)
        assertFalse(flippedBack.liked)
        assertTrue(flippedBack.pending)
        val flippedAgain = optimisticLikeToggle(flippedBack)
        assertTrue(flippedAgain.liked)
        assertTrue(flippedAgain.pending)
    }

    @Test
    fun labelsNameAccountAndDeviceOnlyStatesHonestly() {
        assertEquals(
            "Like",
            likeActionLabel(LikeUiState(liked = false, accountBacked = true)),
        )
        assertEquals(
            "Remove like",
            likeActionLabel(LikeUiState(liked = true, accountBacked = true)),
        )
        assertEquals(
            "Like (on this device only)",
            likeActionLabel(LikeUiState(liked = false, accountBacked = false)),
        )
        assertEquals(
            "Remove like (on this device only)",
            likeActionLabel(LikeUiState(liked = true, accountBacked = false)),
        )
    }
}
