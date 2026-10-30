package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.playback.QualityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two preference accessors over ONE shared backing store —
 * the production shape (AppPreferences and ParityPreferences both
 * wrap the same `offleash_prefs` file, and Settings writes through
 * ParityPreferences while the download engine, the auto-download
 * worker, and the queue controller read through AppPreferences).
 *
 * The sweep defect these tests pin: each class used to serve its
 * getters from its own construction-time cache, so a Settings
 * change persisted correctly yet did not take effect for any
 * AppPreferences reader until the process restarted — the
 * download-quality rows and both toggles were, in effect, dead
 * controls for the values that mattered. Getters now read the
 * persisted value fresh; a write through either accessor must be
 * visible through the other immediately.
 */
class PreferencesCoherenceTest {

    /** One backing map behind BOTH seams, with the production encodings. */
    private class SharedStore : KeyValueStore, ParityKeyValueStore {
        private val map = mutableMapOf<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun putString(key: String, value: String) {
            map[key] = value
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            when (map[key]?.trim()?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> defaultValue
            }

        override fun putBoolean(key: String, value: Boolean) {
            map[key] = value.toString()
        }
    }

    @Test
    fun parityDownloadQualityWriteDrivesTheDownloadEngineRead() {
        val shared = SharedStore()
        // AppPreferences is constructed FIRST, exactly as in the
        // container — its cache holds the FOLLOW_STREAM default.
        val app = AppPreferences(shared)
        val parity = ParityPreferences(shared)
        assertEquals(QualityPreference.LOW, app.effectiveDownloadQuality())

        parity.setDownloadQuality(DownloadQualityPreference.HIGH)
        // The value DownloadManager's qualityProvider reads.
        assertEquals(DownloadQuality.HIGH, app.downloadQuality)
        assertEquals(QualityPreference.HIGH, app.effectiveDownloadQuality())

        parity.setDownloadQuality(DownloadQualityPreference.LOW)
        assertEquals(QualityPreference.LOW, app.effectiveDownloadQuality())
    }

    @Test
    fun parityToggleWritesDriveTheWorkerAndRadioReads() {
        val shared = SharedStore()
        val app = AppPreferences(shared)
        val parity = ParityPreferences(shared)

        parity.setAutoDownloadLiked(true)
        // The value AutoDownloadLikedWorker gates on.
        assertTrue(app.autoDownloadLiked)
        assertTrue(app.autoDownloadLikedFlow.value)

        parity.setAudioVersionInRadio(true)
        // The value the queue controller's radio provider reads.
        assertTrue(app.audioVersionInRadio)
    }

    @Test
    fun appPreferencesWritesAreVisibleThroughParity() {
        val shared = SharedStore()
        val parity = ParityPreferences(shared)
        val app = AppPreferences(shared)

        app.setAutoDownloadLiked(true)
        assertTrue(parity.autoDownloadLiked)
        app.setAudioVersionInRadio(true)
        assertTrue(parity.audioVersionInRadio)
        app.setDownloadQuality(DownloadQuality.BALANCED)
        assertEquals(DownloadQualityPreference.BALANCED, parity.downloadQuality)
    }

    @Test
    fun theParallelDownloadQualityEnumsShareTheirNames() {
        // Settings' name-based mapping (downloadQualityFor) is only
        // sound while the two enums stay name-for-name parallel.
        assertEquals(
            DownloadQuality.entries.map { it.name },
            DownloadQualityPreference.entries.map { it.name },
        )
    }

    @Test
    fun followStreamStillTracksTheStreamQualityAcrossAccessors() {
        val shared = SharedStore()
        val app = AppPreferences(shared)
        val parity = ParityPreferences(shared)
        parity.setDownloadQuality(DownloadQualityPreference.FOLLOW_STREAM)
        app.setStreamQuality(QualityPreference.HIGH)
        assertEquals(QualityPreference.HIGH, app.effectiveDownloadQuality())
        app.setStreamQuality(QualityPreference.BALANCED)
        assertEquals(QualityPreference.BALANCED, app.effectiveDownloadQuality())
    }
}
