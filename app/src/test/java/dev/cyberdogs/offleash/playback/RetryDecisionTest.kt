package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryDecisionTest {

    @Test
    fun first403Retries() {
        assertTrue(shouldRetryAfterPlayerError(403, alreadyRetriedForTrack = false))
    }

    @Test
    fun second403DoesNotRetry() {
        assertFalse(shouldRetryAfterPlayerError(403, alreadyRetriedForTrack = true))
    }

    @Test
    fun non403DoesNotRetry() {
        assertFalse(shouldRetryAfterPlayerError(404, alreadyRetriedForTrack = false))
        assertFalse(shouldRetryAfterPlayerError(500, alreadyRetriedForTrack = false))
        assertFalse(shouldRetryAfterPlayerError(null, alreadyRetriedForTrack = false))
    }
}
