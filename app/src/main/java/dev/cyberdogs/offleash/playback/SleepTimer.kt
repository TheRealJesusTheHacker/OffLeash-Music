package dev.cyberdogs.offleash.playback

/**
 * Sleep-timer state and decisions (Lane G). The state is a plain value
 * and every decision is a pure function of (state, now), so the whole
 * feature is unit-testable without a player, a clock, or Android.
 *
 * The timer itself lives in [QueueController], which is owned by the
 * process-lifetime container next to the player the PlaybackService
 * adopts — so an armed timer keeps counting while the screen is off,
 * and expiry pauses playback from there, not from any UI.
 */

/** The minute choices the player offers, in display order. */
val SLEEP_TIMER_MINUTE_OPTIONS: List<Int> = listOf(5, 10, 15, 30, 45, 60)

data class SleepTimerState(
    /**
     * Deadline for a timed timer, in milliseconds on the MONOTONIC
     * clock the controller feeds these functions
     * (`SystemClock.elapsedRealtime()`); null when not timed. Bug
     * sweep round 2: the deadline used to be a wall-clock epoch
     * while the controller's scheduled pause ran on a monotonic
     * delay — a wall-clock jump backward left the pause fired but
     * the expiry check saying "not yet" (the timer never fired),
     * and a jump forward zeroed the displayed remaining while
     * playback ran on. One clock, everywhere: the deadline, the
     * delay, the expiry check, and the countdown all read it.
     */
    val deadlineMs: Long? = null,
    /** True when playback should stop at the end of the current track. */
    val endOfTrack: Boolean = false,
    /** The requested minutes for a timed timer (0 for end-of-track/off). */
    val requestedMinutes: Int = 0,
) {
    val isArmed: Boolean get() = deadlineMs != null || endOfTrack
}

/** Arms a timed timer [minutes] from [nowMs] (monotonic clock). */
fun sleepTimerTimed(minutes: Int, nowMs: Long): SleepTimerState {
    val safeMinutes = minutes.coerceAtLeast(1)
    return SleepTimerState(
        deadlineMs = nowMs + safeMinutes * 60_000L,
        endOfTrack = false,
        requestedMinutes = safeMinutes,
    )
}

/** Arms the end-of-track timer. */
fun sleepTimerEndOfTrack(): SleepTimerState =
    SleepTimerState(deadlineMs = null, endOfTrack = true, requestedMinutes = 0)

/** Remaining milliseconds for a timed timer; null when not timed. Never negative. */
fun sleepTimerRemainingMs(state: SleepTimerState, nowMs: Long): Long? {
    val deadline = state.deadlineMs ?: return null
    return (deadline - nowMs).coerceAtLeast(0L)
}

/** The expiry decision: a timed timer pauses playback once now reaches its deadline. */
fun sleepTimerShouldPause(state: SleepTimerState, nowMs: Long): Boolean {
    val deadline = state.deadlineMs ?: return false
    return nowMs >= deadline
}

/** The track-end decision: an end-of-track timer stops instead of advancing. */
fun sleepTimerShouldStopAtTrackEnd(state: SleepTimerState): Boolean = state.endOfTrack

/** "12:34" for a remaining duration; minutes may exceed 59. */
fun formatSleepTimerRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
