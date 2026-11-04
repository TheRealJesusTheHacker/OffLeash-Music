package dev.cyberdogs.offleash.ui.player

import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.playback.QueueUiState
import dev.cyberdogs.offleash.core.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Now Playing soak (owner order, 2026-11-04 — the Motion Ui
 * Interface reel's six details), pinned at the pure-function
 * layer:
 *
 *  01 cover colour — the reduced colour is dark and muted by
 *     construction, and every text pairing over the player keeps
 *     at least the 4.5:1 pairing rule with a wide margin; hues in
 *     the purple band fall back to the tan glow (no purple in
 *     product, and a cover may not smuggle it in).
 *  03 scrubber — thin resting line, thicker while dragging,
 *     44dp-class touch target.
 *  04 the right-hand label is −remaining, never the total.
 *  06 Up next is the queue's own peeked next track, and Repeat
 *     One suppresses it (the current track IS next).
 */
class PlayerSoakTest {

    private fun track(id: String) = Track(
        videoId = id,
        title = "t-$id",
        artists = listOf("a"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 60,
        thumbnailUrl = null,
    )

    @Test
    fun purpleBandCoversFallBackToTheTanGlow() {
        // Fully saturated violet / magenta / purple samples.
        val violet = IntArray(64) { 0xFF8B00FF.toInt() }
        val magenta = IntArray(64) { 0xFFFF00FF.toInt() }
        assertEquals(COVER_GLOW_FALLBACK_ARGB, coverGlowArgb(violet))
        assertEquals(COVER_GLOW_FALLBACK_ARGB, coverGlowArgb(magenta))
    }

    @Test
    fun emptyOrTransparentSamplesFallBack() {
        assertEquals(COVER_GLOW_FALLBACK_ARGB, coverGlowArgb(IntArray(0)))
        assertEquals(COVER_GLOW_FALLBACK_ARGB, coverGlowArgb(IntArray(16) { 0 }))
    }

    @Test
    fun nonPurpleCoverYieldsADarkMutedColourWithHighContrastText() {
        // A saturated warm-red cover, the hardest bright case.
        val red = IntArray(64) { 0xFFFF2A1A.toInt() }
        val glow = coverGlowArgb(red)
        assertTrue(glow != COVER_GLOW_FALLBACK_ARGB)
        // Both theme text colours clear the pairing rule with room.
        assertTrue(
            contrastRatioArgb(0xFFF4EDE1.toInt(), glow) >= 4.5,
        )
        assertTrue(
            contrastRatioArgb(0xFFB9AB94.toInt(), glow) >= 4.5,
        )
        // And it is dark: luminance well below mid.
        assertTrue(relativeLuminanceArgb(glow) < 0.25)
    }

    @Test
    fun scrubberThicknessAndTargetFollowTheReel() {
        assertEquals(3, scrubberTrackThicknessDp(dragging = false))
        assertEquals(7, scrubberTrackThicknessDp(dragging = true))
        assertEquals(44, SCRUBBER_TOUCH_TARGET_DP)
        assertTrue(scrubberTrackThicknessDp(true) > scrubberTrackThicknessDp(false))
    }

    @Test
    fun remainingLabelCountsDownAndClampsAtZero() {
        assertEquals("-2:30", remainingTimeLabel(180_000, 30_000))
        assertEquals("-3:00", remainingTimeLabel(180_000, 0))
        assertEquals("-0:00", remainingTimeLabel(180_000, 180_000))
        assertEquals("-0:00", remainingTimeLabel(180_000, 240_000))
        assertEquals("-0:00", remainingTimeLabel(0, 0))
    }

    @Test
    fun upNextIsThePeekedTrackAndRepeatOneSuppressesIt() {
        val next = track("next")
        val state = QueueUiState(nextTrack = next)
        assertEquals(next, upNextTrack(state))
        assertNull(
            upNextTrack(state.copy(repeatMode = RepeatMode.ONE)),
        )
        assertNull(upNextTrack(QueueUiState()))
    }
}
