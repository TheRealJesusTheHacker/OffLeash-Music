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
}
