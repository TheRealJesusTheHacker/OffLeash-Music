package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.playback.QualityPreference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * App preferences over the in-memory key-value fake: the LOW fresh-
 * install default (the owner's metered carrier), set/get round-trips
 * for every quality, malformed-value tolerance, and the flow/current
 * value reflecting a set immediately — the property the queue
 * controller's resolve-time read depends on.
 */
class AppPreferencesTest {

    @Test
    fun defaultIsLowWithEmptyStore() {
        val prefs = AppPreferences(InMemoryKeyValueStore())
        assertEquals(QualityPreference.LOW, prefs.streamQuality)
        assertEquals(QualityPreference.LOW, prefs.streamQualityFlow.value)
    }

    @Test
    fun setGetRoundTripsForAllThreeValues() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        for (quality in QualityPreference.entries) {
            prefs.setStreamQuality(quality)
            assertEquals(quality, prefs.streamQuality)
            // A fresh instance over the same store reads the persisted value.
            assertEquals(quality, AppPreferences(store).streamQuality)
        }
    }

    @Test
    fun storedValueIsTheEnumName() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        prefs.setStreamQuality(QualityPreference.BALANCED)
        assertEquals("BALANCED", store.getString(AppPreferences.KEY_STREAM_QUALITY))
    }

    @Test
    fun malformedStoredStringReadsAsLow() {
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_STREAM_QUALITY, "ULTRA_HD_PLUS")
        assertEquals(QualityPreference.LOW, AppPreferences(store).streamQuality)

        store.putString(AppPreferences.KEY_STREAM_QUALITY, "")
        assertEquals(QualityPreference.LOW, AppPreferences(store).streamQuality)

        store.putString(AppPreferences.KEY_STREAM_QUALITY, "low")
        assertEquals(QualityPreference.LOW, AppPreferences(store).streamQuality)
    }

    @Test
    fun flowAndCurrentValueReflectASetImmediately() {
        val prefs = AppPreferences(InMemoryKeyValueStore())
        assertEquals(QualityPreference.LOW, prefs.streamQualityFlow.value)
        prefs.setStreamQuality(QualityPreference.HIGH)
        assertEquals(QualityPreference.HIGH, prefs.streamQuality)
        assertEquals(QualityPreference.HIGH, prefs.streamQualityFlow.value)
        prefs.setStreamQuality(QualityPreference.LOW)
        assertEquals(QualityPreference.LOW, prefs.streamQuality)
        assertEquals(QualityPreference.LOW, prefs.streamQualityFlow.value)
    }

    // -- Download quality (Lane F) ------------------------------------------

    @Test
    fun downloadQualityDefaultsToFollowStream() {
        val prefs = AppPreferences(InMemoryKeyValueStore())
        assertEquals(DownloadQuality.FOLLOW_STREAM, prefs.downloadQuality)
        assertEquals(DownloadQuality.FOLLOW_STREAM, prefs.downloadQualityFlow.value)
        assertEquals(AppPreferences.DEFAULT_DOWNLOAD_QUALITY, DownloadQuality.FOLLOW_STREAM)
    }

    @Test
    fun downloadQualityRoundTripsForAllValues() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        for (quality in DownloadQuality.entries) {
            prefs.setDownloadQuality(quality)
            assertEquals(quality, prefs.downloadQuality)
            assertEquals(quality, prefs.downloadQualityFlow.value)
            assertEquals(quality, AppPreferences(store).downloadQuality)
            assertEquals(quality.name, store.getString(AppPreferences.KEY_DOWNLOAD_QUALITY))
        }
    }

    @Test
    fun malformedDownloadQualityReadsAsFollowStream() {
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_DOWNLOAD_QUALITY, "ULTRA")
        assertEquals(DownloadQuality.FOLLOW_STREAM, AppPreferences(store).downloadQuality)
        store.putString(AppPreferences.KEY_DOWNLOAD_QUALITY, "")
        assertEquals(DownloadQuality.FOLLOW_STREAM, AppPreferences(store).downloadQuality)
        store.putString(AppPreferences.KEY_DOWNLOAD_QUALITY, "low")
        assertEquals(DownloadQuality.FOLLOW_STREAM, AppPreferences(store).downloadQuality)
    }

    @Test
    fun effectiveDownloadQualityFollowsStreamWhenSetToFollowStream() {
        val prefs = AppPreferences(InMemoryKeyValueStore())
        // Stream default is LOW; FOLLOW_STREAM tracks whatever it is.
        assertEquals(QualityPreference.LOW, prefs.effectiveDownloadQuality())
        prefs.setStreamQuality(QualityPreference.HIGH)
        assertEquals(QualityPreference.HIGH, prefs.effectiveDownloadQuality())
        prefs.setStreamQuality(QualityPreference.BALANCED)
        assertEquals(QualityPreference.BALANCED, prefs.effectiveDownloadQuality())
    }

    @Test
    fun effectiveDownloadQualityOverridesForDownloadsOnly() {
        val prefs = AppPreferences(InMemoryKeyValueStore())
        prefs.setStreamQuality(QualityPreference.HIGH)
        prefs.setDownloadQuality(DownloadQuality.LOW)
        assertEquals(QualityPreference.LOW, prefs.effectiveDownloadQuality())
        // The stream preference itself is untouched by the download key.
        assertEquals(QualityPreference.HIGH, prefs.streamQuality)
        prefs.setDownloadQuality(DownloadQuality.BALANCED)
        assertEquals(QualityPreference.BALANCED, prefs.effectiveDownloadQuality())
        prefs.setDownloadQuality(DownloadQuality.HIGH)
        assertEquals(QualityPreference.HIGH, prefs.effectiveDownloadQuality())
    }

    // -- Boolean plumbing keys (Lane F) --------------------------------------

    @Test
    fun autoDownloadLikedDefaultsOffAndRoundTrips() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        assertEquals(false, prefs.autoDownloadLiked)
        assertEquals(false, AppPreferences.DEFAULT_AUTO_DOWNLOAD_LIKED)
        prefs.setAutoDownloadLiked(true)
        assertEquals(true, prefs.autoDownloadLiked)
        assertEquals(true, prefs.autoDownloadLikedFlow.value)
        assertEquals("true", store.getString(AppPreferences.KEY_AUTO_DOWNLOAD_LIKED))
        assertEquals(true, AppPreferences(store).autoDownloadLiked)
        prefs.setAutoDownloadLiked(false)
        assertEquals(false, AppPreferences(store).autoDownloadLiked)
    }

    @Test
    fun audioVersionInRadioDefaultsOffAndRoundTrips() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        assertEquals(false, prefs.audioVersionInRadio)
        assertEquals(false, AppPreferences.DEFAULT_AUDIO_VERSION_IN_RADIO)
        prefs.setAudioVersionInRadio(true)
        assertEquals(true, prefs.audioVersionInRadio)
        assertEquals(true, prefs.audioVersionInRadioFlow.value)
        assertEquals("true", store.getString(AppPreferences.KEY_AUDIO_VERSION_IN_RADIO))
        assertEquals(true, AppPreferences(store).audioVersionInRadio)
        prefs.setAudioVersionInRadio(false)
        assertEquals(false, AppPreferences(store).audioVersionInRadio)
    }

    @Test
    fun malformedBooleanValuesReadAsTheirDefaults() {
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_AUTO_DOWNLOAD_LIKED, "yes")
        store.putString(AppPreferences.KEY_AUDIO_VERSION_IN_RADIO, "1")
        val prefs = AppPreferences(store)
        assertEquals(false, prefs.autoDownloadLiked)
        assertEquals(false, prefs.audioVersionInRadio)
    }
}
