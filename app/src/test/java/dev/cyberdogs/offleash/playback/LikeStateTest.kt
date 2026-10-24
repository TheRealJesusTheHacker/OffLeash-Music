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
        val rolledBack = likeToggleRolledBack(optimisticLikeToggle(before))
        assertEquals(before.liked, rolledBack.liked)
        assertFalse(rolledBack.pending)

        val beforeUnlike = LikeUiState(liked = false, accountBacked = true)
        val rolledBackUnlike = likeToggleRolledBack(optimisticLikeToggle(beforeUnlike))
        assertFalse(rolledBackUnlike.liked)
        assertFalse(rolledBackUnlike.pending)
    }

    @Test
    fun rollbackWithoutAPendingWriteChangesNothing() {
        val settled = LikeUiState(liked = true, accountBacked = true, pending = false)
        assertEquals(settled, likeToggleRolledBack(settled))
    }

    @Test
    fun aSecondToggleWhilePendingIsIgnored() {
        val pending = optimisticLikeToggle(LikeUiState(liked = false))
        assertEquals(pending, optimisticLikeToggle(pending))
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
