package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splash gate (2026-10-27, owner's spec): cold-launch-only,
 * 3.8 seconds, and never again once the app has played — even in
 * the background. The decision is pure; this pins the full matrix.
 */
class SplashGateTest {

    @Test
    fun coldStartShowsTheSplash() {
        assertTrue(
            decideSplash(
                shownThisProcess = false,
                playbackHasHappened = false,
                playerHasMedia = false,
            ),
        )
    }

    @Test
    fun alreadyShownThisProcessSuppresses() {
        assertFalse(
            decideSplash(
                shownThisProcess = true,
                playbackHasHappened = false,
                playerHasMedia = false,
            ),
        )
    }

    @Test
    fun playbackHappenedSuppresses() {
        assertFalse(
            decideSplash(
                shownThisProcess = false,
                playbackHasHappened = true,
                playerHasMedia = false,
            ),
        )
    }

    @Test
    fun playerHoldingMediaSuppresses() {
        // Relaunch into a still-playing background session.
        assertFalse(
            decideSplash(
                shownThisProcess = false,
                playbackHasHappened = false,
                playerHasMedia = true,
            ),
        )
    }

    @Test
    fun durationIsTheSpecdThreePointEightSeconds() {
        assertEquals(3_800L, SplashGate.SPLASH_DURATION_MS)
    }
}
