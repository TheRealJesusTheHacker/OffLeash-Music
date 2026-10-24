package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speed/pitch parameter mapping (Lane G): the pair handed to
 * Media3's PlaybackParameters — pitch 1.0 preserves pitch
 * (time-stretch); pitch == speed resamples (pitch shifts with tempo).
 */
class PlaybackSpeedTest {

    @Test
    fun preservePitchKeepsPitchFactorAtOneForEveryStep() {
        for (step in PLAYBACK_SPEED_STEPS) {
            val params = playbackParametersFor(step, preservePitch = true)
            assertEquals(step, params.speed, 0.0f)
            assertEquals(1.0f, params.pitch, 0.0f)
        }
    }

    @Test
    fun noPreservePitchSetsPitchFactorEqualToSpeed() {
        for (step in PLAYBACK_SPEED_STEPS) {
            val params = playbackParametersFor(step, preservePitch = false)
            assertEquals(step, params.speed, 0.0f)
            assertEquals(step, params.pitch, 0.0f)
        }
    }

    @Test
    fun normalSpeedIsIdentityEitherWay() {
        assertEquals(SpeedPitch(1.0f, 1.0f), playbackParametersFor(1.0f, true))
        assertEquals(SpeedPitch(1.0f, 1.0f), playbackParametersFor(1.0f, false))
    }

    @Test
    fun speedsClampIntoTheOfferedRange() {
        assertEquals(2.0f, playbackParametersFor(3.0f, true).speed, 0.0f)
        assertEquals(0.5f, playbackParametersFor(0.1f, true).speed, 0.0f)
        assertEquals(0.5f, playbackParametersFor(0.1f, false).pitch, 0.0f)
    }

    @Test
    fun invalidSpeedsReadAsNormalSpeed() {
        assertEquals(1.0f, playbackParametersFor(0.0f, true).speed, 0.0f)
        assertEquals(1.0f, playbackParametersFor(-1.0f, false).speed, 0.0f)
        assertEquals(1.0f, playbackParametersFor(Float.NaN, true).speed, 0.0f)
    }

    @Test
    fun stepsAreTheSensibleRangeInOrder() {
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f),
            PLAYBACK_SPEED_STEPS,
        )
        assertTrue(PLAYBACK_SPEED_STEPS.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun nearestStepSnapsToAnOfferedStep() {
        assertEquals(1.0f, nearestSpeedStep(1.1f), 0.0f)
        assertEquals(1.25f, nearestSpeedStep(1.2f), 0.0f)
        assertEquals(0.5f, nearestSpeedStep(0.2f), 0.0f)
        assertEquals(2.0f, nearestSpeedStep(9.0f), 0.0f)
    }

    @Test
    fun speedLabelsTrimTrailingZeros() {
        assertEquals("1×", formatPlaybackSpeed(1.0f))
        assertEquals("0.5×", formatPlaybackSpeed(0.5f))
        assertEquals("1.25×", formatPlaybackSpeed(1.25f))
        assertEquals("2×", formatPlaybackSpeed(2.0f))
    }
}
