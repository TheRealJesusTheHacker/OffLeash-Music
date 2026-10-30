package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sleep timer's one-clock contract (bug sweep round 2):
 * QueueController is media3-bound (CI-compiled), so the clock
 * source is pinned by source shape — every sleep-timer read in the
 * controller must use the monotonic clock, matching the monotonic
 * delay() the pause is scheduled with and the monotonic deadline
 * SleepTimerState carries. A wall-clock read anywhere in the timer
 * path reopens the clock-jump defects (timer never fires after a
 * backward jump; countdown zeroes early after a forward jump).
 */
class SleepTimerClockContractTest {

    private fun source(relativeFromAppModule: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, relativeFromAppModule)
            candidates += File(dir, "app/$relativeFromAppModule")
            dir = dir.parentFile
            hops++
        }
        val hit = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$relativeFromAppModule not found from ${File(".").absolutePath}",
            )
        return hit.readText()
    }

    @Test
    fun everySleepTimerReadInTheControllerUsesTheMonotonicClock() {
        val qc = source("src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt")
        assertTrue(qc.contains("sleepTimerRemainingMs(timer, SystemClock.elapsedRealtime())"))
        assertTrue(qc.contains("sleepTimerShouldPause(_sleepTimerState.value, SystemClock.elapsedRealtime())"))
        assertTrue(qc.contains("sleepTimerShouldPause(timer, SystemClock.elapsedRealtime())"))
        assertTrue(
            "arming must read the monotonic clock",
            qc.contains("val now = SystemClock.elapsedRealtime()"),
        )
        assertFalse(
            "no wall-clock read may remain in the controller's timer path",
            qc.contains("currentTimeMillis"),
        )
    }

    @Test
    fun theStateCarriesAMonotonicDeadline() {
        val timer = source("src/main/java/dev/cyberdogs/offleash/playback/SleepTimer.kt")
        assertTrue(timer.contains("val deadlineMs: Long?"))
        assertFalse(timer.contains("deadlineEpochMs"))
    }
}
