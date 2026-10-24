package dev.cyberdogs.offleash.ui.settings

import dev.cyberdogs.offleash.data.DownloadQualityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Settings Downloads section's labels and captions (Lane H):
 * every download quality has exactly one label and one caption, and
 * "Follow stream quality" — the contract default — explains itself
 * in plain language.
 */
class DownloadQualityMappingTest {

    @Test
    fun labelsAreThePlainNames() {
        assertEquals(
            "Follow stream quality",
            downloadQualityLabel(DownloadQualityPreference.FOLLOW_STREAM),
        )
        assertEquals("Low", downloadQualityLabel(DownloadQualityPreference.LOW))
        assertEquals("Balanced", downloadQualityLabel(DownloadQualityPreference.BALANCED))
        assertEquals("High", downloadQualityLabel(DownloadQualityPreference.HIGH))
    }

    @Test
    fun followStreamCaptionPointsAtTheStreamQualityAbove() {
        assertEquals(
            "Downloads use the same quality as the stream quality above.",
            downloadQualityCaption(DownloadQualityPreference.FOLLOW_STREAM),
        )
    }

    @Test
    fun everyDownloadQualityHasALabelAndCaption() {
        for (quality in DownloadQualityPreference.entries) {
            assertTrue(downloadQualityLabel(quality).isNotBlank())
            assertTrue(downloadQualityCaption(quality).isNotBlank())
        }
    }
}
