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
 *   Read by the queue controller at radio-start time.
 *
 * A malformed stored value (anything that is not an enum name / not
 * "true" or "false") reads as the key's default, never as a crash.
 *
 * Changes are published on each key's flow and visible through its
 * property immediately, so the NEXT resolve — including one already
 * queued — picks the new value up with no restart: readers read the
 * current value at use time.
 *
 * The plain getters below read the persisted value FRESH on every
 * access (and refresh the cached flow to match). That read-through
 * is load-bearing: [ParityPreferences] writes the same keys in the
 * same file from Settings, and two in-memory caches over one file
 * would otherwise disagree until a restart — a Settings change to
 * the download quality or the auto-download toggle would silently
 * not take effect for the download engine, the worker, or radio.
 */
class AppPreferences(private val store: KeyValueStore) {

    private val _streamQualityFlow = MutableStateFlow(readStreamQuality())

    /** The current stream-quality preference, updated on every set. */
    val streamQualityFlow: StateFlow<QualityPreference> = _streamQualityFlow.asStateFlow()

    /** The current stream-quality preference, read fresh from the store. */
    val streamQuality: QualityPreference
        get() {
            val fresh = readStreamQuality()
            if (_streamQualityFlow.value != fresh) _streamQualityFlow.value = fresh
            return fresh
        }

    fun setStreamQuality(quality: QualityPreference) {
        store.putString(KEY_STREAM_QUALITY, quality.name)
        _streamQualityFlow.value = quality
    }

    // -- Lane G: radio audio-version preference + local likes -----------

    private val _audioVersionInRadioFlow = MutableStateFlow(readAudioVersionInRadio())

    /**
     * "Play the audio version instead of the music video in radio."
     * DEFAULT is false — Phase 1/2 behavior — and the queue controller
     * reads the current value at radio-start time through its
     * defaulted provider, exactly like the stream-quality read.
     */
    val audioVersionInRadioFlow: StateFlow<Boolean> = _audioVersionInRadioFlow.asStateFlow()

    val audioVersionInRadio: Boolean
        get() {
            val fresh = readAudioVersionInRadio()
            if (_audioVersionInRadioFlow.value != fresh) _audioVersionInRadioFlow.value = fresh
            return fresh
        }

    fun setAudioVersionInRadio(enabled: Boolean) {
        store.putString(KEY_AUDIO_VERSION_IN_RADIO, enabled.toString())
        _audioVersionInRadioFlow.value = enabled
    }

    private fun readAudioVersionInRadio(): Boolean {
        val raw = try {
            store.getString(KEY_AUDIO_VERSION_IN_RADIO)
        } catch (e: Exception) {
            null
        }
        return raw?.trim()?.lowercase() == "true"
    }

    /**
     * The on-device record of liked track videoIds. Signed in, this is
     * a cache of the account likes this app has set or read back;
     * signed out, it IS the like state (device-only, and labeled as
     * such in the UI). Stored newline-joined; blank ids are dropped.
     */
    fun localLikedVideoIds(): Set<String> {
        val combined = combinedLikedState()
        if (combined != null) return combined.first
        val raw = try {
            store.getString(KEY_LOCAL_LIKED_IDS)
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return emptySet()
        // Round 43: the legacy fallback applies the format's
        // validity rule at parse too, exactly as the combined
        // reader does (round 42). The legacy encoding has no
        // separator line, so a marker-named entry stored here
        // is not a separator — it read back as a phantom liked
        // id in the state map, and a carriage-return-bearing
        // line read back as a malformed id the same way (the
        // split is on '\n' only; the trim cannot reach an
        // interior '\r'). Dropping invalid entries per-entry
        // keeps the well-formed remainder's salvage.
        return raw.split('\n').map { it.trim() }
            .filter { it.isStorableLikedId() }.toSet()
    }

    fun setLocalLikedVideoIds(ids: Set<String>) {
        store.putString(
            KEY_LOCAL_LIKED_IDS,
            ids.filter { it.isNotBlank() }.sorted().joinToString("\n"),
        )
    }

    /**
     * The liked record and its seed provenance as ONE stored value
     * (bug sweep round 39). Rounds 37/38 persisted the pair as two
     * keys written by two calls, and every mutation path therefore
     * had a process-death window between the writes: on the seed
     * path (record first) the window left a seeded id in the record
     * with no provenance — round 38's leak in miniature, the id
     * loading as an ordinary device-local like after the relaunch;
     * on the refresh-takeover path (also record first) it left
     * provenance naming an entry the account's own report had
     * taken over, so a later flip dropped that entry as if it were
     * still a seed. No write ORDER closes both directions (the
     * flip-drop needs record-first; the seed needs
     * provenance-first), so the pair now moves in a single
     * [KeyValueStore.putString]: the stored value is the record's
     * ids, a marker line, then the provenance's ids, and a crash
     * can only keep the whole previous pair or the whole next one.
     * The legacy two keys remain the fallback for stores written
     * before this key existed (and for the individual setters'
     * callers); the first combined write supersedes them.
     */
    fun setLikedState(localIds: Set<String>, seededIds: Set<String>) {
        // Round 41: an entry equal to the separator line, or one
        // carrying a line break, is not an id this format can hold —
        // the marker-named entry would be found FIRST by the
        // reader's indexOf and read as the separator itself (the
        // whole record before it discarded, the real record demoted
        // to provenance), and a line break would split one entry
        // into two phantom ids. Ids here are YouTube video ids,
        // which contain neither, so such entries are dropped at
        // write rather than corrupting the pair at read.
        val record = localIds.filter { it.isStorableLikedId() }.sorted()
        val seeded = seededIds.filter { it.isStorableLikedId() }.sorted()
        store.putString(
            KEY_LIKED_STATE,
            (record + SEEDED_MARKER + seeded).joinToString("\n"),
        )
    }

    /** An id the liked-state format can hold without corrupting its line format. */
    private fun String.isStorableLikedId(): Boolean =
        isNotBlank() && this != SEEDED_MARKER && !contains('\n') && !contains('\r')

    /** The combined pair, or null when no combined value was ever written. */
    private fun combinedLikedState(): Pair<Set<String>, Set<String>>? {
        val raw = try {
            store.getString(KEY_LIKED_STATE)
        } catch (e: Exception) {
            null
        } ?: return null
        val lines = raw.split('\n')
        val marker = lines.indexOf(SEEDED_MARKER)
        // Round 40: a value under this key with no marker line is
        // not a combined value this writer produced — it is
        // truncated or foreign. Returning an empty pair here made
        // the corruption TOTAL and, because the result is
        // non-null, shadowed the legacy pair the getters keep as
        // the fallback: a damaged v2 silently zeroed a liked
        // record whose legacy copy was intact. A markerless value
        // is no combined value at all (null), so the legacy
        // fallback decides, exactly as for a store this key was
        // never written to.
        if (marker < 0) return null
        // Round 42: the round-41 validity rule applies at parse
        // too, not only at write. A value already on disk (written
        // before the writer filter, damaged, or written by a
        // foreign writer) can carry entries no current writer
        // would produce: a second marker line in the provenance
        // segment read back AS a provenance id, and a
        // carriage-return-bearing line read back as a malformed
        // id (the split is on '\n' only, so an interior '\r'
        // survives the trim). Dropping invalid entries from both
        // segments keeps the reader's salvage per-entry: the
        // well-formed remainder of the pair still reads.
        val record = lines.subList(0, marker)
            .map { it.trim() }.filter { it.isStorableLikedId() }.toSet()
        val seeded = lines.subList(marker + 1, lines.size)
            .map { it.trim() }.filter { it.isStorableLikedId() }.toSet()
        return record to seeded
    }

    /**
     * The subset of the local liked record whose provenance is a
     * Liked Songs page seed (bug sweep round 38): those ids are one
     * account's page snapshot, not the device's own likes, and a
     * session flip must drop them — including after a process
     * restart, which is why the provenance is persisted beside the
     * record it describes instead of living only in the like
     * controller's memory. Stored newline-joined, like the record
     * itself; blank ids are dropped.
     */
    fun seededLikedVideoIds(): Set<String> {
        val combined = combinedLikedState()
        if (combined != null) return combined.second
        val raw = try {
            store.getString(KEY_SEEDED_LIKED_IDS)
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return emptySet()
        // Round 43: same reader-side validity rule as the
        // record's legacy fallback above — a marker-named or
        // carriage-return-bearing provenance entry is a
        // phantom seed, not provenance this pair ever held.
        return raw.split('\n').map { it.trim() }
            .filter { it.isStorableLikedId() }.toSet()
    }

    fun setSeededLikedVideoIds(ids: Set<String>) {
        store.putString(
            KEY_SEEDED_LIKED_IDS,
            ids.filter { it.isNotBlank() }.sorted().joinToString("\n"),
        )
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

    /** The current download-quality preference, read fresh from the store. */
    val downloadQuality: DownloadQuality
        get() {
            val fresh = readDownloadQuality()
            if (_downloadQualityFlow.value != fresh) _downloadQualityFlow.value = fresh
            return fresh
        }

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
        return when (raw?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    private val _autoDownloadLikedFlow =
        MutableStateFlow(readBoolean(KEY_AUTO_DOWNLOAD_LIKED, DEFAULT_AUTO_DOWNLOAD_LIKED))

    /** Whether liked tracks auto-download, updated on every set. */
    val autoDownloadLikedFlow: StateFlow<Boolean> = _autoDownloadLikedFlow.asStateFlow()

    /** Whether liked tracks auto-download (default off), read fresh from the store. */
    val autoDownloadLiked: Boolean
        get() {
            val fresh = readBoolean(KEY_AUTO_DOWNLOAD_LIKED, DEFAULT_AUTO_DOWNLOAD_LIKED)
            if (_autoDownloadLikedFlow.value != fresh) _autoDownloadLikedFlow.value = fresh
            return fresh
        }

    fun setAutoDownloadLiked(enabled: Boolean) {
        store.putString(KEY_AUTO_DOWNLOAD_LIKED, enabled.toString())
        _autoDownloadLikedFlow.value = enabled
    }

    /**
     * The playback speed the owner last chose in the player (bug
     * sweep round 1: until now the choice lived only in the queue
     * controller's memory and every process death silently reset
     * playback to 1.0×). Read fresh from the store like every other
     * getter here; a missing/malformed/out-of-range value reads as
     * normal speed. The bounds mirror PlaybackSpeed's (0.5×–2.0×) —
     * they are duplicated as literals so this layer stays free of
     * playback imports; the mapping itself re-clamps on apply.
     */
    val playbackSpeed: Float
        get() {
            // Total like every other getter in this class (bug
            // sweep round 2): this was the one read that let a
            // throwing store — SharedPreferences.getString throws
            // ClassCastException when a key holds a non-string —
            // escape as a crash instead of reading as the default.
            val raw = try {
                store.getString(KEY_PLAYBACK_SPEED)
            } catch (e: Exception) {
                null
            }
            val parsed = raw?.toFloatOrNull()
            return when {
                parsed == null || parsed.isNaN() || parsed <= 0f ->
                    DEFAULT_PLAYBACK_SPEED
                else -> parsed.coerceIn(0.5f, 2.0f)
            }
        }

    fun setPlaybackSpeed(speed: Float) {
        store.putString(KEY_PLAYBACK_SPEED, speed.toString())
    }

    /** Whether pitch is preserved at non-1× speeds (default on, as in the player's SpeedUiState). */
    val keepPitchAtSpeed: Boolean
        get() = readBoolean(KEY_KEEP_PITCH_AT_SPEED, DEFAULT_KEEP_PITCH_AT_SPEED)

    fun setKeepPitchAtSpeed(enabled: Boolean) {
        store.putString(KEY_KEEP_PITCH_AT_SPEED, enabled.toString())
    }

    companion object {
        /** The SharedPreferences file every plain preference lives in. */
        const val PREFS_FILE_NAME: String = "offleash_prefs"

        const val KEY_STREAM_QUALITY: String = "stream_quality"

        const val KEY_DOWNLOAD_QUALITY: String = "download_quality"

        const val KEY_AUTO_DOWNLOAD_LIKED: String = "auto_download_liked"

        const val KEY_AUDIO_VERSION_IN_RADIO: String = "audio_version_in_radio"

        const val KEY_LOCAL_LIKED_IDS: String = "local_liked_video_ids"

        const val KEY_SEEDED_LIKED_IDS: String = "seeded_liked_video_ids"

        const val KEY_LIKED_STATE: String = "liked_state_v2"

        /** Separates the record from the provenance inside [KEY_LIKED_STATE]. */
        const val SEEDED_MARKER: String = "@@seeded@@"

        const val KEY_PLAYBACK_SPEED: String = "playback_speed"

        const val KEY_KEEP_PITCH_AT_SPEED: String = "keep_pitch_at_speed"

        /** Fresh-install default: the owner's metered carrier makes Low the honest default. */
        val DEFAULT_STREAM_QUALITY: QualityPreference = QualityPreference.LOW

        /** Fresh-install default: downloads follow the stream-quality preference. */
        val DEFAULT_DOWNLOAD_QUALITY: DownloadQuality = DownloadQuality.FOLLOW_STREAM

        /** Fresh-install default: liked tracks are never auto-downloaded until the owner turns it on. */
        const val DEFAULT_AUTO_DOWNLOAD_LIKED: Boolean = false

        /** Fresh-install default: radio plays the version YouTube queues. */
        const val DEFAULT_AUDIO_VERSION_IN_RADIO: Boolean = false

        const val DEFAULT_PLAYBACK_SPEED: Float = 1.0f

        const val DEFAULT_KEEP_PITCH_AT_SPEED: Boolean = true

        /** Builds the production preferences over the app's SharedPreferences. */
        fun fromContext(context: Context): AppPreferences = AppPreferences(
            SharedPreferencesKeyValueStore(
                context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE),
            ),
        )
    }
}
