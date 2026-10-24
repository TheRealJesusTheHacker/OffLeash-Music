package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// ---------------------------------------------------------------------------
// Serialization DTOs (mirrors Stores.kt: :core models stay annotation-free)
// ---------------------------------------------------------------------------

@Serializable
data class DownloadRecordDto(
    val track: TrackDto,
    val state: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val fileName: String,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val failureReason: String? = null,
    val updatedAtEpochMs: Long = 0L,
) {
    fun toRecord(): DownloadRecord = DownloadRecord(
        track = track.toTrack(),
        state = try {
            DownloadState.valueOf(state)
        } catch (e: IllegalArgumentException) {
            // An unknown stored state must never read as DONE.
            DownloadState.FAILED
        },
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        fileName = fileName,
        mimeType = mimeType,
        bitrate = bitrate,
        failureReason = failureReason,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    companion object {
        fun from(record: DownloadRecord): DownloadRecordDto = DownloadRecordDto(
            track = TrackDto.from(record.track),
            state = record.state.name,
            downloadedBytes = record.downloadedBytes,
            totalBytes = record.totalBytes,
            fileName = record.fileName,
            mimeType = record.mimeType,
            bitrate = record.bitrate,
            failureReason = record.failureReason,
            updatedAtEpochMs = record.updatedAtEpochMs,
        )
    }
}

@Serializable
private data class DownloadsFile(val downloads: List<DownloadRecordDto> = emptyList())

private val downloadJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Download records, stored as one JSON document (`downloads.json` in
 * the app files dir — the same store family as playlists/recents in
 * Stores.kt). The audio bytes themselves live in the sibling
 * `downloads/` directory (see [downloadsDirIn]); this file holds only
 * records.
 *
 * Unlike the playlist store this one is synchronous: the download
 * engine runs blocking network work on IO threads and updates records
 * as chunks land, so methods take a lock, update the in-memory flow,
 * and persist inline. Record files are small; callers that care run
 * on IO dispatchers.
 *
 * A corrupt file reads as empty — the store never crashes the app over
 * its own data. Note the honest consequence: records lost to
 * corruption leave orphan files behind, never phantom downloads —
 * playback precedence requires a DONE record AND the file.
 */
class DownloadStore(private val file: File) {

    private val lock = Any()

    private val _records = MutableStateFlow<List<DownloadRecord>>(loadFromDisk())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    fun recordFor(videoId: String): DownloadRecord? =
        _records.value.firstOrNull { it.videoId == videoId }

    /** Insert a fresh QUEUED record for [track], or return the existing one untouched. */
    fun enqueueIfAbsent(track: Track, nowMs: Long): DownloadRecord {
        synchronized(lock) {
            recordFor(track.videoId)?.let { return it }
            val record = DownloadRecord(
                track = track,
                state = DownloadState.QUEUED,
                updatedAtEpochMs = nowMs,
            )
            persistLocked(_records.value + record)
            return record
        }
    }

    /** Replace (or insert) [record] wholesale. Used when a (re-)download is queued. */
    fun upsert(record: DownloadRecord) {
        synchronized(lock) {
            val current = _records.value
            val next = if (current.any { it.videoId == record.videoId }) {
                current.map { if (it.videoId == record.videoId) record else it }
            } else {
                current + record
            }
            persistLocked(next)
        }
    }

    /**
     * Move the record for [videoId] through the state machine (see
     * [DownloadStateMachine]). Returns the updated record, or null
     * when no record exists (a download deleted mid-flight must not
     * resurrect its record when its job unwinds).
     */
    fun transition(
        videoId: String,
        toState: DownloadState,
        downloadedBytes: Long? = null,
        totalBytes: Long? = null,
        clearTotalBytes: Boolean = false,
        mimeType: String? = null,
        bitrate: Int? = null,
        failureReason: String? = null,
        nowMs: Long,
    ): DownloadRecord? {
        synchronized(lock) {
            val existing = recordFor(videoId) ?: return null
            val updated = DownloadStateMachine.transition(
                record = existing,
                toState = toState,
                downloadedBytes = downloadedBytes ?: existing.downloadedBytes,
                totalBytes = if (clearTotalBytes) null else totalBytes ?: existing.totalBytes,
                mimeType = mimeType ?: existing.mimeType,
                bitrate = bitrate ?: existing.bitrate,
                failureReason = failureReason,
                updatedAtEpochMs = nowMs,
            )
            persistLocked(_records.value.map { if (it.videoId == videoId) updated else it })
            return updated
        }
    }

    fun remove(videoId: String) {
        synchronized(lock) {
            if (_records.value.none { it.videoId == videoId }) return
            persistLocked(_records.value.filterNot { it.videoId == videoId })
        }
    }

    private fun loadFromDisk(): List<DownloadRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val parsed = downloadJson.decodeFromString<DownloadsFile>(file.readText())
            parsed.downloads.map { it.toRecord() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persistLocked(records: List<DownloadRecord>) {
        _records.value = records
        try {
            file.parentFile?.mkdirs()
            val payload = DownloadsFile(records.map { DownloadRecordDto.from(it) })
            file.writeText(downloadJson.encodeToString(payload))
        } catch (e: Exception) {
            // Best-effort persistence; in-memory state stays
            // authoritative for this session (Stores.kt's rule).
        }
    }
}

/** Where the download records file lives: the app files dir (NOT the no-backup session dir). */
fun downloadRecordsFileIn(filesDir: File): File = File(filesDir, "downloads.json")

/** Where downloaded audio lives: `downloads/` under the app files dir (NOT the no-backup session dir). */
fun downloadsDirIn(filesDir: File): File = File(filesDir, "downloads")
