package dev.cyberdogs.offleash.ui.settings

import dev.cyberdogs.offleash.core.playback.QualityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Settings screen's quality labels and plain-language
 * captions: every preference has exactly one label and one caption,
 * and the captions carry the numbers the owner reads when choosing
 * (Low ~ up to 80 kbps for mobile data, Balanced ~ up to 160 kbps,
 * High = best available).
 */
class QualityMappingTest {

    @Test
    fun labelsAreThePlainNames() {
        assertEquals("Low", qualityLabel(QualityPreference.LOW))
        assertEquals("Balanced", qualityLabel(QualityPreference.BALANCED))
        assertEquals("High", qualityLabel(QualityPreference.HIGH))
    }

    @Test
    fun captionsCarryThePromisedFigures() {
        assertEquals(
            "Smallest streams — about up to 80 kbps. Best for mobile data.",
            qualityCaption(QualityPreference.LOW),
        )
        assertEquals(
            "Up to about 160 kbps.",
            qualityCaption(QualityPreference.BALANCED),
        )
        assertEquals(
            "Best quality YouTube offers for the track.",
            qualityCaption(QualityPreference.HIGH),
        )
    }

    @Test
    fun everyQualityHasALabelAndCaption() {
        for (quality in QualityPreference.entries) {
            assertTrue(qualityLabel(quality).isNotBlank())
            assertTrue(qualityCaption(quality).isNotBlank())
        }
    }
}
