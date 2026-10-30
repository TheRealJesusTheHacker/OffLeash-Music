package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.LocalPlaylist
import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.UUID

// ---------------------------------------------------------------------------
// Serialization DTOs (app-side mirrors of the core models; :core stays free
// of serialization annotations on its public models).
// ---------------------------------------------------------------------------

@Serializable
data class TrackDto(
    val videoId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val artistBrowseId: String? = null,
    val album: String? = null,
    val albumBrowseId: String? = null,
    val durationSeconds: Int? = null,
    val thumbnailUrl: String? = null,
) {
    fun toTrack(): Track = Track(
        videoId = videoId,
        title = title,
        artists = artists,
        artistBrowseId = artistBrowseId,
        album = album,
        albumBrowseId = albumBrowseId,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
    )

    companion object {
        fun from(track: Track): TrackDto = TrackDto(
            videoId = track.videoId,
            title = track.title,
            artists = track.artists,
            artistBrowseId = track.artistBrowseId,
            album = track.album,
            albumBrowseId = track.albumBrowseId,
            durationSeconds = track.durationSeconds,
            thumbnailUrl = track.thumbnailUrl,
        )
    }
}

@Serializable
data class LocalPlaylistDto(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val tracks: List<TrackDto> = emptyList(),
) {
    fun toPlaylist(): LocalPlaylist = LocalPlaylist(
        id = id,
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        tracks = tracks.map { it.toTrack() },
    )

    companion object {
        fun from(playlist: LocalPlaylist): LocalPlaylistDto = LocalPlaylistDto(
            id = playlist.id,
            name = playlist.name,
            createdAtEpochMs = playlist.createdAtEpochMs,
            tracks = playlist.tracks.map { TrackDto.from(it) },
        )
    }
}

@Serializable
private data class PlaylistsFile(val playlists: List<LocalPlaylistDto> = emptyList())

@Serializable
private data class RecentsFile(val tracks: List<TrackDto> = emptyList())

private val storeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Decodes [element] as a [TrackDto], or returns null when the entry
 * is malformed. Store files are decoded entry by entry (bug sweep
 * round 1): the old whole-document decode let ONE bad entry — a
 * truncated write, a hand-edited file, a future schema's extra
 * shape — read back as "no playlists / no recents at all", wiping
 * every good entry with it. Salvage keeps the good entries and
 * drops only the bad one; a file that isn't JSON at all still
 * reads as empty, as before.
 */
private fun decodeTrackLenient(element: kotlinx.serialization.json.JsonElement): TrackDto? =
    try {
        storeJson.decodeFromJsonElement(TrackDto.serializer(), element)
    } catch (e: Exception) {
        null
    }

/** The leniently decoded tracks of a DTO-shaped `tracks` array element. */
private fun decodeTracksLenient(element: kotlinx.serialization.json.JsonElement): List<TrackDto> {
    val obj = element as? kotlinx.serialization.json.JsonObject ?: return emptyList()
    val arr = obj["tracks"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return arr.mapNotNull { decodeTrackLenient(it) }
}

/** Decodes one playlist entry, salvaging its good tracks when the entry as a whole is malformed. */
private fun decodePlaylistLenient(
    element: kotlinx.serialization.json.JsonElement,
): LocalPlaylistDto? {
    try {
        return storeJson.decodeFromJsonElement(LocalPlaylistDto.serializer(), element)
    } catch (e: Exception) {
        // Fall through to the salvage path: header fields plus the
        // tracks that do decode.
    }
    val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
    // Header fields must be genuine strings: contentOrNull would
    // coerce a numeric id / boolean name into a phantom playlist
    // ("99" / "false") — a malformed entry is dropped, not dressed
    // up as data the user never created.
    val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull ?: return null
    val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull ?: return null
    val created = (obj["createdAtEpochMs"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.longOrNull ?: 0L
    return LocalPlaylistDto(
        id = id,
        name = name,
        createdAtEpochMs = created,
        tracks = decodeTracksLenient(element),
    )
}

/** The top-level array stored under [key] in a store file, or null when the file isn't that shape. */
private fun storeFileArray(text: String, key: String): kotlinx.serialization.json.JsonArray? =
    try {
        val root = storeJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        root?.get(key) as? kotlinx.serialization.json.JsonArray
    } catch (e: Exception) {
        null
    }

/**
 * Local playlists, stored as one JSON document in the app files dir
 * (SPEC §5). All mutations are suspend functions serialized on a mutex and
 * persisted on the IO dispatcher. A corrupt file reads as empty — the store
 * never crashes the app over its own data.
 */
class LocalPlaylistStore(private val file: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    init {
        scope.launch { loadFromDisk() }
    }

    private suspend fun loadFromDisk() {
        mutex.withLock {
            _playlists.value = readFromDisk()
        }
    }

    private fun readFromDisk(): List<LocalPlaylist> {
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return emptyList()
        }
        // Corruption-tolerant per entry: a wholly unreadable file
        // reads as empty; a file with some bad entries keeps the rest.
        val array = storeFileArray(text, "playlists") ?: return emptyList()
        // One playlist per id (round 2): a duplicated id in the file
        // (torn write in an old build, a hand edit) would otherwise
        // fork one playlist into two rows that every id-addressed
        // mutation then edits together. First entry wins, the same
        // rule DownloadStore applies to duplicate record ids.
        return array.mapNotNull { decodePlaylistLenient(it)?.toPlaylist() }
            .distinctBy { it.id }
    }

    private suspend fun persistLocked(playlists: List<LocalPlaylist>) {
        _playlists.value = playlists
        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                val payload = PlaylistsFile(playlists.map { LocalPlaylistDto.from(it) })
                writeTextAtomically(file, storeJson.encodeToString(payload))
            } catch (e: Exception) {
                // Best-effort persistence; in-memory state stays authoritative
                // for this session rather than crashing the UI.
            }
        }
    }

    private suspend fun update(transform: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        mutex.withLock {
            if (_playlists.value.isEmpty() && file.exists()) {
                // Guard against a mutation racing the initial load.
                _playlists.value = readFromDisk()
            }
            persistLocked(transform(_playlists.value))
        }
    }

    suspend fun createPlaylist(name: String): LocalPlaylist {
        val playlist = LocalPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            createdAtEpochMs = System.currentTimeMillis(),
            tracks = emptyList(),
        )
        update { current -> current + playlist }
        return playlist
    }

    suspend fun renamePlaylist(id: String, name: String) {
        update { current ->
            current.map { if (it.id == id) it.copy(name = name.trim()) else it }
        }
    }

    suspend fun deletePlaylist(id: String) {
        update { current -> current.filterNot { it.id == id } }
    }

    suspend fun addTrack(playlistId: String, track: Track) {
        update { current ->
            current.map {
                if (it.id == playlistId) it.copy(tracks = it.tracks + track) else it
            }
        }
    }

    suspend fun removeTrack(playlistId: String, videoId: String) {
        update { current ->
            current.map {
                if (it.id == playlistId) {
                    it.copy(tracks = it.tracks.filterNot { t -> t.videoId == videoId })
                } else {
                    it
                }
            }
        }
    }

    /**
     * Remove exactly the row at [index] — the per-row delete the
     * playlist screen offers (bug sweep round 5). The screen
     * deliberately supports the same track appearing twice (its
     * rows are index-keyed since round 4), but [removeTrack]
     * removes by videoId, so deleting ONE duplicate row deleted
     * EVERY copy of that track. An out-of-range index is a no-op.
     */
    suspend fun removeTrackAt(playlistId: String, index: Int) {
        update { current ->
            current.removingRowAt(playlistId, index, expectedVideoId = null, expectedTrack = null)
        }
    }

    /**
     * [removeTrackAt], guarded by the identity of the row the user
     * actually tapped (bug sweep round 6). The screen captures
     * [index] at render time and the removal runs in a launched
     * coroutine; another mutation that lands in between (a move, a
     * removal of an earlier row, a second tap on the same row
     * before the first removal settles) shifts the list, and the
     * unguarded index then deletes a track the user never tapped
     * Delete on — silently, with no confirmation or undo on this
     * screen. A tap whose row no longer holds [expectedVideoId] is
     * stale and drops as a no-op (the house rule for stale
     * actions); the user sees the settled list and can tap again.
     * When the row at [index] still holds the expected track —
     * including a duplicate copy of it — the removal is exact,
     * exactly as [removeTrackAt].
     */
    suspend fun removeTrackAt(playlistId: String, index: Int, expectedVideoId: String) {
        update { current ->
            current.removingRowAt(playlistId, index, expectedVideoId, expectedTrack = null)
        }
    }

    /**
     * [removeTrackAt], guarded by the row's full [Track] value
     * (bug sweep round 8). The videoId guard cannot tell duplicate
     * copies of one track apart — and copies of the same videoId
     * are not always equal values: a track added from search and
     * the same track added from a playlist page can differ in
     * duration, thumbnail, or album fields. A stale launch
     * replayed after such copies swapped places passed the
     * videoId guard and removed the copy the user never tapped
     * on. Comparing the whole value distinguishes those copies;
     * fully identical copies remain indistinguishable AND
     * interchangeable — removing either of two equal values is
     * the same list afterwards, so no stronger guard exists or
     * is needed there.
     */
    suspend fun removeTrackAt(playlistId: String, index: Int, expectedTrack: Track) {
        update { current ->
            current.removingRowAt(playlistId, index, expectedVideoId = null, expectedTrack)
        }
    }

    private fun List<LocalPlaylist>.removingRowAt(
        playlistId: String,
        index: Int,
        expectedVideoId: String?,
        expectedTrack: Track?,
    ): List<LocalPlaylist> = map { playlist ->
        if (playlist.id == playlistId && index in playlist.tracks.indices &&
            (expectedTrack != null && playlist.tracks[index] == expectedTrack ||
                expectedTrack == null &&
                (expectedVideoId == null || playlist.tracks[index].videoId == expectedVideoId))
        ) {
            val tracks = playlist.tracks.toMutableList()
            tracks.removeAt(index)
            playlist.copy(tracks = tracks)
        } else {
            playlist
        }
    }

    suspend fun moveTrack(playlistId: String, fromIndex: Int, toIndex: Int) {
        update { current ->
            current.movingRow(playlistId, fromIndex, toIndex, expectedVideoId = null, expectedTrack = null)
        }
    }

    /**
     * [moveTrack], guarded by the identity of the row the user
     * actually tapped (bug sweep round 7 — the round-6
     * [removeTrackAt] guard's sibling, which the move buttons never
     * got). The screen captures both indices at render time and the
     * move runs in a launched coroutine; another mutation landing
     * in between (a removal of an earlier row, a double-tap on the
     * same move button before the first move settles) shifts the
     * list, and the unguarded indices then move a track the user
     * never tapped Move on. A tap whose row no longer holds
     * [expectedVideoId] is stale and drops as a no-op, exactly as
     * a stale row delete does; the user sees the settled list and
     * can tap again.
     */
    suspend fun moveTrack(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        expectedVideoId: String,
    ) {
        update { current ->
            current.movingRow(playlistId, fromIndex, toIndex, expectedVideoId, expectedTrack = null)
        }
    }

    /**
     * [moveTrack], guarded by the row's full [Track] value (bug
     * sweep round 8 — the [removeTrackAt] full-value guard's
     * sibling, for the reason given there: duplicate copies of
     * one videoId can be unequal values, and the videoId guard
     * alone let a stale launch move the copy the user never
     * tapped on; in the double-tap shape it silently undid the
     * first move by moving the other copy back).
     */
    suspend fun moveTrack(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        expectedTrack: Track,
    ) {
        update { current ->
            current.movingRow(playlistId, fromIndex, toIndex, expectedVideoId = null, expectedTrack)
        }
    }

    private fun List<LocalPlaylist>.movingRow(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        expectedVideoId: String?,
        expectedTrack: Track?,
    ): List<LocalPlaylist> = map { playlist ->
        if (playlist.id != playlistId) {
            playlist
        } else {
            val tracks = playlist.tracks.toMutableList()
            if (fromIndex in tracks.indices && toIndex in tracks.indices &&
                fromIndex != toIndex &&
                (expectedTrack != null && tracks[fromIndex] == expectedTrack ||
                    expectedTrack == null &&
                    (expectedVideoId == null || tracks[fromIndex].videoId == expectedVideoId))
            ) {
                val moved = tracks.removeAt(fromIndex)
                tracks.add(toIndex, moved)
            }
            playlist.copy(tracks = tracks)
        }
    }
}

/**
 * Recently played tracks: last [MAX_RECENTS], most-recent-first, deduped by
 * videoId (SPEC §5). Same corruption tolerance as the playlist store.
 */
class RecentsStore(private val file: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _recents = MutableStateFlow<List<Track>>(emptyList())
    val recentsFlow: StateFlow<List<Track>> = _recents.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                _recents.value = readFromDisk()
            }
        }
    }

    fun recents(): StateFlow<List<Track>> = recentsFlow

    private fun readFromDisk(): List<Track> {
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return emptyList()
        }
        val array = storeFileArray(text, "tracks") ?: return emptyList()
        // Deduped by videoId before the cap (round 2): recordPlayed
        // dedupes on write, but a file an old build or a hand edit
        // left with the same track twice must not load as duplicate
        // rows — nor let duplicates eat slots under the cap.
        return array.mapNotNull { decodeTrackLenient(it)?.toTrack() }
            .distinctBy { it.videoId }
            .take(MAX_RECENTS)
    }

    suspend fun recordPlayed(track: Track) {
        mutex.withLock {
            if (_recents.value.isEmpty() && file.exists()) {
                _recents.value = readFromDisk()
            }
            val updated = (listOf(track) + _recents.value.filterNot { it.videoId == track.videoId })
                .take(MAX_RECENTS)
            _recents.value = updated
            withContext(Dispatchers.IO) {
                try {
                    file.parentFile?.mkdirs()
                    val payload = RecentsFile(updated.map { TrackDto.from(it) })
                    writeTextAtomically(file, storeJson.encodeToString(payload))
                } catch (e: Exception) {
                    // Best-effort; see LocalPlaylistStore.
                }
            }
        }
    }

    companion object {
        const val MAX_RECENTS = 50
    }
}
