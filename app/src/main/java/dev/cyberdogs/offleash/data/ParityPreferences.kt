package dev.cyberdogs.offleash.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The download-quality preference (parity contract key
 * `download_quality`, owned by Lane F). Stored as the enum name,
 * exactly the strings the contract fixes: FOLLOW_STREAM / LOW /
 * BALANCED / HIGH. FOLLOW_STREAM means downloads follow whatever the
 * stream-quality preference resolves — the fresh-install default.
 */
enum class DownloadQualityPreference {
    FOLLOW_STREAM,
    LOW,
    BALANCED,
    HIGH,
    ;

    companion object {
        /** Parses a stored value; anything unrecognized reads as the default. */
        fun fromStored(raw: String?): DownloadQualityPreference {
            if (raw.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(raw)
            } catch (e: IllegalArgumentException) {
                DEFAULT
            }
        }

        val DEFAULT: DownloadQualityPreference = FOLLOW_STREAM
    }
}

/**
 * A string+boolean key-value seam for [ParityPreferences], mirroring
 * [KeyValueStore]'s testability pattern: Android enters only through
 * [ParityPreferences.fromContext]; tests use the in-memory fake.
 */
interface ParityKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/**
 * [ParityKeyValueStore] over the app's SharedPreferences, using Lane
 * F's encodings exactly: every value in `offleash_prefs` is a STRING —
 * the boolean keys are stored as the strings "true"/"false" through
 * [AppPreferences]' string seam (coordinator integration fixup: Lane H
 * originally assumed native SharedPreferences booleans, which would
 * have thrown ClassCastException against Lane F's stored strings and
 * split the two accessors' encodings). A native boolean found under a
 * key (no shipped build wrote one) is still read correctly.
 */
class SharedPreferencesParityStore(
    private val prefs: SharedPreferences,
) : ParityKeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val raw = try {
            prefs.getString(key, null)
        } catch (e: ClassCastException) {
            return try {
                prefs.getBoolean(key, defaultValue)
            } catch (e2: Exception) {
                defaultValue
            }
        }
        return when (raw?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> defaultValue
        }
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putString(key, value.toString()).apply()
    }
}

/**
 * In-memory [ParityKeyValueStore] for tests — no disk, no Android.
 * Booleans are stored as the strings "true"/"false" in the SAME
 * map as strings, matching [SharedPreferencesParityStore]'s
 * production encoding exactly: a fake that kept booleans in a
 * separate native map would let a test pass while the real store's
 * string read of the same key (AppPreferences' seam) saw nothing.
 */
class InMemoryParityKeyValueStore : ParityKeyValueStore {

    private val strings = mutableMapOf<String, String>()

    override fun getString(key: String): String? = strings[key]

    override fun putString(key: String, value: String) {
        strings[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        when (strings[key]?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> defaultValue
        }

    override fun putBoolean(key: String, value: Boolean) {
        strings[key] = value.toString()
    }
}

/**
 * The Lane F-owned parity preferences, read and written by Lane H's
 * Settings depth through the contract's EXACT keys in the shared
 * `offleash_prefs` SharedPreferences file, with the contract's
 * defaults:
 *
 * - `download_quality` — string enum, default FOLLOW_STREAM
 * - `auto_download_liked` — boolean, default false
 * - `audio_version_in_radio` — boolean, default false
 *
 * Lane H deliberately does NOT add these keys to [AppPreferences]:
 * Lane F owns their accessors there. This class talks to the same
 * file under the same key names and encodings (enum name as a
 * string, booleans as the strings "true"/"false" — Lane F's
 * [AppPreferences] string seam, matched by the coordinator at
 * integration), so the two lanes' writes are interchangeable, and
 * this class can be retired onto F's accessors without a data
 * migration. A malformed stored value reads as the contract
 * default, never as a crash.
 *
 * Every value is published on a [StateFlow] and visible through its
 * plain getter immediately after a set, so consumers (downloads,
 * radio) read the current value at use time with no restart. The
 * plain getters read the persisted value FRESH on every access
 * (and refresh the cached flow to match): [AppPreferences] holds
 * its own cache over the same file, and a getter that served only
 * its own cache would let the two accessors disagree — a write
 * through one invisible to readers of the other — until a restart.
 */
class ParityPreferences(private val store: ParityKeyValueStore) {

    private val _downloadQualityFlow =
        MutableStateFlow(readDownloadQuality())
    val downloadQualityFlow: StateFlow<DownloadQualityPreference> =
        _downloadQualityFlow.asStateFlow()
    val downloadQuality: DownloadQualityPreference
        get() {
            val fresh = readDownloadQuality()
            if (_downloadQualityFlow.value != fresh) _downloadQualityFlow.value = fresh
            return fresh
        }

    private val _autoDownloadLikedFlow =
        MutableStateFlow(readBoolean(KEY_AUTO_DOWNLOAD_LIKED, DEFAULT_AUTO_DOWNLOAD_LIKED))
    val autoDownloadLikedFlow: StateFlow<Boolean> = _autoDownloadLikedFlow.asStateFlow()
    val autoDownloadLiked: Boolean
        get() {
            val fresh = readBoolean(KEY_AUTO_DOWNLOAD_LIKED, DEFAULT_AUTO_DOWNLOAD_LIKED)
            if (_autoDownloadLikedFlow.value != fresh) _autoDownloadLikedFlow.value = fresh
            return fresh
        }

    private val _audioVersionInRadioFlow =
        MutableStateFlow(readBoolean(KEY_AUDIO_VERSION_IN_RADIO, DEFAULT_AUDIO_VERSION_IN_RADIO))
    val audioVersionInRadioFlow: StateFlow<Boolean> = _audioVersionInRadioFlow.asStateFlow()
    val audioVersionInRadio: Boolean
        get() {
            val fresh = readBoolean(KEY_AUDIO_VERSION_IN_RADIO, DEFAULT_AUDIO_VERSION_IN_RADIO)
            if (_audioVersionInRadioFlow.value != fresh) _audioVersionInRadioFlow.value = fresh
            return fresh
        }

    fun setDownloadQuality(quality: DownloadQualityPreference) {
        store.putString(KEY_DOWNLOAD_QUALITY, quality.name)
        _downloadQualityFlow.value = quality
    }

    fun setAutoDownloadLiked(enabled: Boolean) {
        store.putBoolean(KEY_AUTO_DOWNLOAD_LIKED, enabled)
        _autoDownloadLikedFlow.value = enabled
    }

    fun setAudioVersionInRadio(enabled: Boolean) {
        store.putBoolean(KEY_AUDIO_VERSION_IN_RADIO, enabled)
        _audioVersionInRadioFlow.value = enabled
    }

    private fun readDownloadQuality(): DownloadQualityPreference {
        val raw = try {
            store.getString(KEY_DOWNLOAD_QUALITY)
        } catch (e: Exception) {
            null
        }
        return DownloadQualityPreference.fromStored(raw)
    }

    private fun readBoolean(key: String, defaultValue: Boolean): Boolean =
        try {
            store.getBoolean(key, defaultValue)
        } catch (e: Exception) {
            defaultValue
        }

    companion object {
        const val KEY_DOWNLOAD_QUALITY: String = "download_quality"
        const val KEY_AUTO_DOWNLOAD_LIKED: String = "auto_download_liked"
        const val KEY_AUDIO_VERSION_IN_RADIO: String = "audio_version_in_radio"

        const val DEFAULT_AUTO_DOWNLOAD_LIKED: Boolean = false
        const val DEFAULT_AUDIO_VERSION_IN_RADIO: Boolean = false

        /** Builds the production preferences over the app's SharedPreferences. */
        fun fromContext(context: Context): ParityPreferences = ParityPreferences(
            SharedPreferencesParityStore(
                context.getSharedPreferences(
                    AppPreferences.PREFS_FILE_NAME,
                    Context.MODE_PRIVATE,
                ),
            ),
        )
    }
}
