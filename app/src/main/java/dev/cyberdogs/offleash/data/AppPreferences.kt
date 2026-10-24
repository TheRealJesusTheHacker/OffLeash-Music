package dev.cyberdogs.offleash.data

import android.content.Context
import android.content.SharedPreferences
import dev.cyberdogs.offleash.core.playback.QualityPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tiny string key-value seam so [AppPreferences] is testable on the
 * JVM with an in-memory fake — the same seam style as the session
 * store's [SecretStorage]. Android-backed and fake implementations
 * live here; Android only enters through [AppPreferences.fromContext].
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** [KeyValueStore] over the app's SharedPreferences. */
class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/** In-memory [KeyValueStore] for tests — no disk, no Android. */
class InMemoryKeyValueStore : KeyValueStore {

    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

/**
 * The download-quality preference (parity step 2, Lane F).
 *
 * [FOLLOW_STREAM] is the default: a download resolves at exactly the
 * stream-quality preference, so there is one quality knob until the
 * owner says otherwise. The other values override the stream-quality
 * preference FOR DOWNLOADS ONLY — streaming itself never reads this
 * enum. The mapping onto a core [QualityPreference] lives in
 * [AppPreferences.effectiveDownloadQuality].
 */
enum class DownloadQuality {
    FOLLOW_STREAM,
    LOW,
    BALANCED,
    HIGH,
}

/**
 * Plain, non-secret app preferences, persisted in the `offleash_prefs`
 * SharedPreferences file (the same file the battery card's dismissal
 * flag already uses).
 *
 * Keys:
 * - `stream_quality` (Lane E): the stream-quality preference. The
 *   DEFAULT is [QualityPreference.LOW] — the owner's carrier is
 *   metered and his reference client's setting is Low (~66 kbps) —
 *   so a fresh install resolves small streams until the owner says
 *   otherwise in Settings.
 * - `download_quality` (Lane F): a [DownloadQuality], default
 *   [DownloadQuality.FOLLOW_STREAM]. When it is not FOLLOW_STREAM it
 *   overrides `stream_quality` for downloads only (see
 *   [effectiveDownloadQuality]).
 * - `auto_download_liked` (Lane F): boolean, default false. When on,
 *   liked tracks are enqueued for download (see the auto-download
 *   worker). Stored through the string seam as "true"/"false".
 * - `audio_version_in_radio` (Lane F): boolean, default false.
 *   Plumbing only for a later lane — no behavior reads it yet.
 *
 * A malformed stored value (anything that is not an enum name / not
 * "true" or "false") reads as the key's default, never as a crash.
 *
 * Changes are published on each key's flow and visible through its
 * property immediately, so the NEXT resolve — including one already
 * queued — picks the new value up with no restart: readers read the
 * current value at use time.
 */
class AppPreferences(private val store: KeyValueStore) {

    private val _streamQualityFlow = MutableStateFlow(readStreamQuality())

    /** The current stream-quality preference, updated on every set. */
    val streamQualityFlow: StateFlow<QualityPreference> = _streamQualityFlow.asStateFlow()

    /** The current stream-quality preference. */
    val streamQuality: QualityPreference
        get() = _streamQualityFlow.value

    fun setStreamQuality(quality: QualityPreference) {
        store.putString(KEY_STREAM_QUALITY, quality.name)
        _streamQualityFlow.value = quality
    }

    private fun readStreamQuality(): QualityPreference {
        val raw = try {
            store.getString(KEY_STREAM_QUALITY)
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return DEFAULT_STREAM_QUALITY
        return try {
            QualityPreference.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            DEFAULT_STREAM_QUALITY
        }
    }

    // -- Download quality (Lane F) ------------------------------------------

    private val _downloadQualityFlow = MutableStateFlow(readDownloadQuality())

    /** The current download-quality preference, updated on every set. */
    val downloadQualityFlow: StateFlow<DownloadQuality> = _downloadQualityFlow.asStateFlow()

    /** The current download-quality preference. */
    val downloadQuality: DownloadQuality
        get() = _downloadQualityFlow.value

    fun setDownloadQuality(quality: DownloadQuality) {
        store.putString(KEY_DOWNLOAD_QUALITY, quality.name)
        _downloadQualityFlow.value = quality
    }

    private fun readDownloadQuality(): DownloadQuality {
        val raw = try {
            store.getString(KEY_DOWNLOAD_QUALITY)
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return DEFAULT_DOWNLOAD_QUALITY
        return try {
            DownloadQuality.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            DEFAULT_DOWNLOAD_QUALITY
        }
    }

    /**
     * The quality a download must resolve at right now: the download
     * preference's own value, or — for [DownloadQuality.FOLLOW_STREAM]
     * — the current stream-quality preference. Purely a read: setting
     * a download quality never changes the stream preference.
     */
    fun effectiveDownloadQuality(): QualityPreference = when (downloadQuality) {
        DownloadQuality.FOLLOW_STREAM -> streamQuality
        DownloadQuality.LOW -> QualityPreference.LOW
        DownloadQuality.BALANCED -> QualityPreference.BALANCED
        DownloadQuality.HIGH -> QualityPreference.HIGH
    }

    // -- Boolean keys (Lane F) ----------------------------------------------
    //
    // Booleans ride the existing string seam as "true"/"false" (the seam
    // predates them and other code builds on its two methods); any other
    // stored text reads as the key's default.

    private fun readBoolean(key: String, default: Boolean): Boolean {
        val raw = try {
            store.getString(key)
        } catch (e: Exception) {
            null
        }
        return when (raw) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    private val _autoDownloadLikedFlow =
        MutableStateFlow(readBoolean(KEY_AUTO_DOWNLOAD_LIKED, DEFAULT_AUTO_DOWNLOAD_LIKED))

    /** Whether liked tracks auto-download, updated on every set. */
    val autoDownloadLikedFlow: StateFlow<Boolean> = _autoDownloadLikedFlow.asStateFlow()

    /** Whether liked tracks auto-download (default off). */
    val autoDownloadLiked: Boolean
        get() = _autoDownloadLikedFlow.value

    fun setAutoDownloadLiked(enabled: Boolean) {
        store.putString(KEY_AUTO_DOWNLOAD_LIKED, enabled.toString())
        _autoDownloadLikedFlow.value = enabled
    }

    private val _audioVersionInRadioFlow =
        MutableStateFlow(readBoolean(KEY_AUDIO_VERSION_IN_RADIO, DEFAULT_AUDIO_VERSION_IN_RADIO))

    /** Whether radio prefers audio versions, updated on every set. Plumbing only — nothing reads this for behavior yet. */
    val audioVersionInRadioFlow: StateFlow<Boolean> = _audioVersionInRadioFlow.asStateFlow()

    /** Whether radio prefers audio versions (default off). Plumbing only — nothing reads this for behavior yet. */
    val audioVersionInRadio: Boolean
        get() = _audioVersionInRadioFlow.value

    fun setAudioVersionInRadio(enabled: Boolean) {
        store.putString(KEY_AUDIO_VERSION_IN_RADIO, enabled.toString())
        _audioVersionInRadioFlow.value = enabled
    }

    companion object {
        /** The SharedPreferences file every plain preference lives in. */
        const val PREFS_FILE_NAME: String = "offleash_prefs"

        const val KEY_STREAM_QUALITY: String = "stream_quality"

        const val KEY_DOWNLOAD_QUALITY: String = "download_quality"

        const val KEY_AUTO_DOWNLOAD_LIKED: String = "auto_download_liked"

        const val KEY_AUDIO_VERSION_IN_RADIO: String = "audio_version_in_radio"

        /** Fresh-install default: the owner's metered carrier makes Low the honest default. */
        val DEFAULT_STREAM_QUALITY: QualityPreference = QualityPreference.LOW

        /** Fresh-install default: downloads follow the stream-quality preference. */
        val DEFAULT_DOWNLOAD_QUALITY: DownloadQuality = DownloadQuality.FOLLOW_STREAM

        /** Fresh-install default: liked tracks are never auto-downloaded until the owner turns it on. */
        const val DEFAULT_AUTO_DOWNLOAD_LIKED: Boolean = false

        /** Fresh-install default: radio plays the version YouTube queues. */
        const val DEFAULT_AUDIO_VERSION_IN_RADIO: Boolean = false

        /** Builds the production preferences over the app's SharedPreferences. */
        fun fromContext(context: Context): AppPreferences = AppPreferences(
            SharedPreferencesKeyValueStore(
                context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE),
            ),
        )
    }
}
