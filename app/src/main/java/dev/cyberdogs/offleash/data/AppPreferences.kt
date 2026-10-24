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
 * Plain, non-secret app preferences, persisted in the `offleash_prefs`
 * SharedPreferences file (the same file the battery card's dismissal
 * flag already uses).
 *
 * This lane's key: the stream-quality preference. The DEFAULT is
 * [QualityPreference.LOW] — the owner's carrier is metered and his
 * reference client's setting is Low (~66 kbps) — so a fresh install
 * resolves small streams until the owner says otherwise in Settings.
 * A malformed stored value (anything that is not an enum name) reads
 * as the default, never as a crash.
 *
 * Changes are published on [streamQualityFlow] and visible through
 * [streamQuality] immediately, so the NEXT resolve — including one
 * already queued — picks the new value up with no restart: the queue
 * controller reads the current value at resolve time.
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

    companion object {
        /** The SharedPreferences file every plain preference lives in. */
        const val PREFS_FILE_NAME: String = "offleash_prefs"

        const val KEY_STREAM_QUALITY: String = "stream_quality"

        /** Fresh-install default: the owner's metered carrier makes Low the honest default. */
        val DEFAULT_STREAM_QUALITY: QualityPreference = QualityPreference.LOW

        /** Builds the production preferences over the app's SharedPreferences. */
        fun fromContext(context: Context): AppPreferences = AppPreferences(
            SharedPreferencesKeyValueStore(
                context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE),
            ),
        )
    }
}
