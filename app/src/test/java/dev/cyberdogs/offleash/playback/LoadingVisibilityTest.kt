package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Truth table for the shared loading-visibility decision. */
class LoadingVisibilityTest {

    @Test
    fun resolvingShowsLoading() {
        assertTrue(showLoadingIndicator(isResolving = true, isBuffering = false))
    }

    @Test
    fun bufferingShowsLoading() {
        assertTrue(showLoadingIndicator(isResolving = false, isBuffering = true))
    }

    @Test
    fun resolvingAndBufferingShowsLoading() {
        assertTrue(showLoadingIndicator(isResolving = true, isBuffering = true))
    }

    @Test
    fun idleShowsNoLoading() {
        assertFalse(showLoadingIndicator(isResolving = false, isBuffering = false))
    }
}
