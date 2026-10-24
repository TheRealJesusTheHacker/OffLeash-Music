package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sleep-timer decisions (Lane G): the pure (state, now) matrix the
 * QueueController's countdown and track-end stop are built on.
 */
class SleepTimerTest {

    private val now = 1_700_000_000_000L

    @Test
    fun timedTimerArmsWithDeadlineAndMinutes() {
        val state = sleepTimerTimed(15, now)
        assertTrue(state.isArmed)
        assertFalse(state.endOfTrack)
        assertEquals(15, state.requestedMinutes)
        assertEquals(now + 15 * 60_000L, state.deadlineEpochMs)
    }

    @Test
    fun offeredOptionsAreThePlayerChoices() {
        assertEquals(listOf(5, 10, 15, 30, 45, 60), SLEEP_TIMER_MINUTE_OPTIONS)
    }

    @Test
    fun shouldPauseOnlyAtOrAfterTheDeadline() {
        val state = sleepTimerTimed(10, now)
        assertFalse(sleepTimerShouldPause(state, now))
        assertFalse(sleepTimerShouldPause(state, now + 599_999L))
        assertTrue(sleepTimerShouldPause(state, now + 600_000L))
        assertTrue(sleepTimerShouldPause(state, now + 900_000L))
    }

    @Test
    fun unarmedAndEndOfTrackNeverPauseByClock() {
        assertFalse(sleepTimerShouldPause(SleepTimerState(), now + 86_400_000L))
        assertFalse(sleepTimerShouldPause(sleepTimerEndOfTrack(), now + 86_400_000L))
    }

    @Test
    fun remainingCountsDownAndNeverGoesNegative() {
        val state = sleepTimerTimed(30, now)
        assertEquals(30 * 60_000L, sleepTimerRemainingMs(state, now))
        assertEquals(60_000L, sleepTimerRemainingMs(state, now + 29 * 60_000L))
        assertEquals(0L, sleepTimerRemainingMs(state, now + 31 * 60_000L))
        assertNull(sleepTimerRemainingMs(SleepTimerState(), now))
        assertNull(sleepTimerRemainingMs(sleepTimerEndOfTrack(), now))
    }

    @Test
    fun endOfTrackStopsAtTrackEndAndOnlyThen() {
        assertTrue(sleepTimerShouldStopAtTrackEnd(sleepTimerEndOfTrack()))
        assertFalse(sleepTimerShouldStopAtTrackEnd(sleepTimerTimed(10, now)))
        assertFalse(sleepTimerShouldStopAtTrackEnd(SleepTimerState()))
        assertTrue(sleepTimerEndOfTrack().isArmed)
    }

    @Test
    fun remainingFormatsAsMinutesSecondsRoundingUp() {
        assertEquals("0:01", formatSleepTimerRemaining(1L))
        assertEquals("1:00", formatSleepTimerRemaining(59_500L))
        assertEquals("12:34", formatSleepTimerRemaining(754_000L))
        assertEquals("60:00", formatSleepTimerRemaining(3_600_000L))
        assertEquals("0:00", formatSleepTimerRemaining(0L))
        assertEquals("0:00", formatSleepTimerRemaining(-5L))
    }
}
