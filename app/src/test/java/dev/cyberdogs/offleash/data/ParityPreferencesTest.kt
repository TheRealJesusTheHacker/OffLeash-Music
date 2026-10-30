package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity preferences (Lane H) over the in-memory fake: the contract
 * defaults (download_quality FOLLOW_STREAM, both toggles false),
 * round-trips through a fresh instance over the same store — the
 * persisted-bytes property the Settings controls depend on — the
 * EXACT contract key names and encodings, and malformed-value
 * tolerance.
 */
class ParityPreferencesTest {

    @Test
    fun defaultsWithEmptyStoreAreTheContractDefaults() {
        val prefs = ParityPreferences(InMemoryParityKeyValueStore())
        assertEquals(DownloadQualityPreference.FOLLOW_STREAM, prefs.downloadQuality)
        assertEquals(DownloadQualityPreference.FOLLOW_STREAM, prefs.downloadQualityFlow.value)
        assertFalse(prefs.autoDownloadLiked)
        assertFalse(prefs.autoDownloadLikedFlow.value)
        assertFalse(prefs.audioVersionInRadio)
        assertFalse(prefs.audioVersionInRadioFlow.value)
    }

    @Test
    fun contractKeyNamesAreExact() {
        assertEquals("download_quality", ParityPreferences.KEY_DOWNLOAD_QUALITY)
        assertEquals("auto_download_liked", ParityPreferences.KEY_AUTO_DOWNLOAD_LIKED)
        assertEquals("audio_version_in_radio", ParityPreferences.KEY_AUDIO_VERSION_IN_RADIO)
    }

    @Test
    fun downloadQualityRoundTripsForAllFourValues() {
        val store = InMemoryParityKeyValueStore()
        val prefs = ParityPreferences(store)
        for (quality in DownloadQualityPreference.entries) {
            prefs.setDownloadQuality(quality)
            assertEquals(quality, prefs.downloadQuality)
            assertEquals(quality, prefs.downloadQualityFlow.value)
            // A fresh instance over the same store reads the persisted value.
            assertEquals(quality, ParityPreferences(store).downloadQuality)
        }
    }

    @Test
    fun storedDownloadQualityIsTheEnumName() {
        val store = InMemoryParityKeyValueStore()
        ParityPreferences(store).setDownloadQuality(DownloadQualityPreference.HIGH)
        assertEquals("HIGH", store.getString(ParityPreferences.KEY_DOWNLOAD_QUALITY))
    }

    @Test
    fun malformedStoredDownloadQualityReadsAsFollowStream() {
        val store = InMemoryParityKeyValueStore()
        store.putString(ParityPreferences.KEY_DOWNLOAD_QUALITY, "ULTRA_HD_PLUS")
        assertEquals(
            DownloadQualityPreference.FOLLOW_STREAM,
            ParityPreferences(store).downloadQuality,
        )
        store.putString(ParityPreferences.KEY_DOWNLOAD_QUALITY, "")
        assertEquals(
            DownloadQualityPreference.FOLLOW_STREAM,
            ParityPreferences(store).downloadQuality,
        )
        store.putString(ParityPreferences.KEY_DOWNLOAD_QUALITY, "low")
        assertEquals(
            DownloadQualityPreference.FOLLOW_STREAM,
            ParityPreferences(store).downloadQuality,
        )
    }

    @Test
    fun autoDownloadLikedRoundTrips() {
        val store = InMemoryParityKeyValueStore()
        val prefs = ParityPreferences(store)
        prefs.setAutoDownloadLiked(true)
        assertTrue(prefs.autoDownloadLiked)
        assertTrue(prefs.autoDownloadLikedFlow.value)
        assertTrue(store.getBoolean(ParityPreferences.KEY_AUTO_DOWNLOAD_LIKED, false))
        assertTrue(ParityPreferences(store).autoDownloadLiked)
        prefs.setAutoDownloadLiked(false)
        assertFalse(prefs.autoDownloadLiked)
        assertFalse(ParityPreferences(store).autoDownloadLiked)
    }

    @Test
    fun audioVersionInRadioRoundTrips() {
        val store = InMemoryParityKeyValueStore()
        val prefs = ParityPreferences(store)
        prefs.setAudioVersionInRadio(true)
        assertTrue(prefs.audioVersionInRadio)
        assertTrue(prefs.audioVersionInRadioFlow.value)
        assertTrue(store.getBoolean(ParityPreferences.KEY_AUDIO_VERSION_IN_RADIO, false))
        assertTrue(ParityPreferences(store).audioVersionInRadio)
        prefs.setAudioVersionInRadio(false)
        assertFalse(prefs.audioVersionInRadio)
        assertFalse(ParityPreferences(store).audioVersionInRadio)
    }

    @Test
    fun booleansAreStoredAsStringsMatchingTheProductionEncoding() {
        // SharedPreferencesParityStore writes booleans as the
        // strings "true"/"false" (AppPreferences' string seam reads
        // the same keys). The fake must encode identically, or a
        // test could pass over bytes the device never writes.
        val store = InMemoryParityKeyValueStore()
        val prefs = ParityPreferences(store)
        prefs.setAutoDownloadLiked(true)
        assertEquals("true", store.getString(ParityPreferences.KEY_AUTO_DOWNLOAD_LIKED))
        prefs.setAudioVersionInRadio(true)
        assertEquals("true", store.getString(ParityPreferences.KEY_AUDIO_VERSION_IN_RADIO))
        prefs.setAutoDownloadLiked(false)
        assertEquals("false", store.getString(ParityPreferences.KEY_AUTO_DOWNLOAD_LIKED))
    }

    @Test
    fun theThreePreferencesAreIndependent() {
        val store = InMemoryParityKeyValueStore()
        val prefs = ParityPreferences(store)
        prefs.setDownloadQuality(DownloadQualityPreference.LOW)
        prefs.setAutoDownloadLiked(true)
        assertEquals(DownloadQualityPreference.LOW, prefs.downloadQuality)
        assertTrue(prefs.autoDownloadLiked)
        assertFalse(prefs.audioVersionInRadio)
    }
}
