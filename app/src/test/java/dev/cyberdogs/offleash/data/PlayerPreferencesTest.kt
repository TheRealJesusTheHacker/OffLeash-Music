package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane G preference additions over the in-memory fake: the
 * audio-version-in-radio default (false = pre-preference behavior),
 * its round-trip, malformed tolerance, and the local liked-ids record
 * the LikeController persists.
 */
class PlayerPreferencesTest {

    @Test
    fun audioVersionInRadioDefaultsToFalse() {
        val prefs = AppPreferences(InMemoryKeyValueStore())
        assertFalse(prefs.audioVersionInRadio)
        assertFalse(prefs.audioVersionInRadioFlow.value)
    }

    @Test
    fun audioVersionInRadioRoundTripsAndPersists() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        prefs.setAudioVersionInRadio(true)
        assertTrue(prefs.audioVersionInRadio)
        assertTrue(prefs.audioVersionInRadioFlow.value)
        assertEquals("true", store.getString(AppPreferences.KEY_AUDIO_VERSION_IN_RADIO))
        assertTrue(AppPreferences(store).audioVersionInRadio)

        prefs.setAudioVersionInRadio(false)
        assertFalse(AppPreferences(store).audioVersionInRadio)
    }

    @Test
    fun audioVersionInRadioMalformedReadsAsFalse() {
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_AUDIO_VERSION_IN_RADIO, "yes-please")
        assertFalse(AppPreferences(store).audioVersionInRadio)
    }

    @Test
    fun localLikedIdsDefaultEmptyAndRoundTrip() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        assertEquals(emptySet<String>(), prefs.localLikedVideoIds())

        prefs.setLocalLikedVideoIds(setOf("bbb", "aaa"))
        assertEquals(setOf("aaa", "bbb"), prefs.localLikedVideoIds())
        assertEquals(setOf("aaa", "bbb"), AppPreferences(store).localLikedVideoIds())

        prefs.setLocalLikedVideoIds(emptySet())
        assertEquals(emptySet<String>(), AppPreferences(store).localLikedVideoIds())
    }

    @Test
    fun localLikedIdsDropBlanksAndTolerateJunk() {
        val store = InMemoryKeyValueStore()
        val prefs = AppPreferences(store)
        prefs.setLocalLikedVideoIds(setOf("aaa", "", "  "))
        assertEquals(setOf("aaa"), prefs.localLikedVideoIds())

        store.putString(AppPreferences.KEY_LOCAL_LIKED_IDS, "\n\nccc\n\n")
        assertEquals(setOf("ccc"), AppPreferences(store).localLikedVideoIds())
    }
}
