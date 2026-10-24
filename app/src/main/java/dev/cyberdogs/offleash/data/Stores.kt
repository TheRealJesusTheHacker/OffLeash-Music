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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        return try {
            val parsed = storeJson.decodeFromString<PlaylistsFile>(file.readText())
            parsed.playlists.map { it.toPlaylist() }
        } catch (e: Exception) {
            // Corruption-tolerant: a bad file reads as empty, never crashes.
            emptyList()
        }
    }

    private suspend fun persistLocked(playlists: List<LocalPlaylist>) {
        _playlists.value = playlists
        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                val payload = PlaylistsFile(playlists.map { LocalPlaylistDto.from(it) })
                file.writeText(storeJson.encodeToString(payload))
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

    suspend fun moveTrack(playlistId: String, fromIndex: Int, toIndex: Int) {
        update { current ->
            current.map { playlist ->
                if (playlist.id != playlistId) {
                    playlist
                } else {
                    val tracks = playlist.tracks.toMutableList()
                    if (fromIndex in tracks.indices && toIndex in tracks.indices && fromIndex != toIndex) {
                        val moved = tracks.removeAt(fromIndex)
                        tracks.add(toIndex, moved)
                    }
                    playlist.copy(tracks = tracks)
                }
            }
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
        return try {
            val parsed = storeJson.decodeFromString<RecentsFile>(file.readText())
            parsed.tracks.map { it.toTrack() }.take(MAX_RECENTS)
        } catch (e: Exception) {
            emptyList()
        }
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
                    file.writeText(storeJson.encodeToString(payload))
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
