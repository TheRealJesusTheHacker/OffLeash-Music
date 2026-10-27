package dev.cyberdogs.offleash.playback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the autoplay continuation wiring (2026-10-27
 * queue-advancement hunt), in the house contract-test style: the
 * controller's behavior needs a device, but the WIRING — end-of-track
 * and end-of-queue reaching the continuation, the sleep timer
 * cancelling the fetch, user-chosen queues resetting the chain —
 * is pinnable here so a future refactor can't silently unwire it.
 */
class AutoplayWiringContractTest {

    private fun queueControllerSource(): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidates = listOf(
                File(
                    dir,
                    "src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt",
                ),
                File(
                    dir,
                    "app/src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt",
                ),
            )
            val hit = candidates.firstOrNull { it.isFile }
            if (hit != null) return hit.readText()
            dir = dir.parentFile
        }
        throw AssertionError("QueueController.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun endOfTrackAndManualNextBothReachTheContinuation() {
        val source = queueControllerSource()
        assertTrue(source.contains("continueWithAutoplay(seed, fromTrackEnd = true)"))
        assertTrue(source.contains("continueWithAutoplay(seed, fromTrackEnd = false)"))
    }

    @Test
    fun continuationReusesTheRelatedMachineryAndThePureBuilder() {
        val source = queueControllerSource()
        assertTrue(source.contains("client.related(seed.videoId)"))
        assertTrue(source.contains("autoplayContinuation("))
    }

    @Test
    fun sleepTimerExpiryCancelsTheAutoplayFetch() {
        val source = queueControllerSource()
        val expire = source.substringAfter("private fun expireSleepTimer()")
            .substringBefore("private fun")
        assertTrue(expire.contains("autoplayJob?.cancel()"))
    }

    @Test
    fun listenerQueuesResetTheAutoplayChain() {
        val source = queueControllerSource()
        assertTrue(source.contains("private fun resetAutoplayChain()"))
        val playTracks = source.substringAfter("fun playTracks(")
            .substringBefore("fun play(")
        assertTrue(playTracks.contains("resetAutoplayChain()"))
    }

    @Test
    fun failureSkipsAreCappedAndScopedToAutoplayTracks() {
        val source = queueControllerSource()
        assertTrue(source.contains("MAX_AUTOPLAY_CONSECUTIVE_FAILURES"))
        assertTrue(source.contains("failed.videoId !in autoplayTrackIds"))
    }
}
