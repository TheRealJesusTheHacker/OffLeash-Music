package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import java.io.File

/**
 * Offline downloads (parity step 2, Lane F) — the pure half.
 *
 * A downloaded track is a complete audio file in app-private storage
 * plus a [DownloadRecord]. This file holds everything about downloads
 * that can be decided without Android, the network, or a player: the
 * record shape, the state machine, the bounded-chunk planner, storage
 * accounting, file naming, and the local-playback precedence decision.
 * [DownloadStore] persists the records; [DownloadManager] does the
 * fetching. Everything here is JVM-tested.
 *
 * Resume policy — DISCARD, stated plainly: partial files are never
 * resumed and never presented as complete. A download (including a
 * retry of a failed one) starts by deleting any `.part` file and any
 * previous final file for the track, fetches the whole file in
 * bounded chunks, and only renames the `.part` file to its final name
 * once its length matches the resolved size exactly. A failure at any
 * point deletes the `.part` file, so the bytes on disk can never be
 * mistaken for a finished download. (Bounded chunks stay small, and
 * stream URLs expire within hours — a kept partial would usually be
 * unresumable anyway, and silently resuming against a fresh URL for
 * the same track cannot be proven to serve identical bytes.)
 */

/** Where a single track's download stands. */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    DONE,
    FAILED,
}

/**
 * One track's download record. The track snapshot is the full [Track]
 * (stream URLs are never stored — they expire; see SPEC §4), so a
 * downloaded row renders and plays with no network at all.
 *
 * [downloadedBytes] is progress while DOWNLOADING and the final file
 * size once DONE; on FAILED it reads 0, matching the discard policy
 * (no partial bytes are kept). [failureReason] is set exactly when the
 * state is FAILED, and names the failure in plain language.
 */
data class DownloadRecord(
    val track: Track,
    val state: DownloadState,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val fileName: String = downloadFileName(track.videoId),
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val failureReason: String? = null,
    val updatedAtEpochMs: Long = 0L,
) {
    val videoId: String get() = track.videoId
}

/**
 * The download state machine. Self-transitions are always allowed
 * (progress updates re-enter DOWNLOADING; a re-enqueue re-enters
 * QUEUED). The cross-state moves are:
 *
 * - QUEUED → DOWNLOADING (work starts), QUEUED → FAILED
 * - DOWNLOADING → DONE (only after the size check), DOWNLOADING →
 *   FAILED, DOWNLOADING → QUEUED (cancelled back to the queue)
 * - FAILED → QUEUED (retry is queued first), FAILED → DOWNLOADING
 * - DONE → QUEUED (an explicit re-download)
 *
 * Notably illegal: QUEUED → DONE and DONE → DOWNLOADING / DONE →
 * FAILED — a track can only become DONE by passing through
 * DOWNLOADING, and a finished download cannot quietly become a
 * failure.
 */
object DownloadStateMachine {

    private val ALLOWED: Map<DownloadState, Set<DownloadState>> = mapOf(
        DownloadState.QUEUED to setOf(
            DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED,
        ),
        DownloadState.DOWNLOADING to setOf(
            DownloadState.QUEUED, DownloadState.DOWNLOADING,
            DownloadState.DONE, DownloadState.FAILED,
        ),
        DownloadState.DONE to setOf(DownloadState.QUEUED, DownloadState.DONE),
        DownloadState.FAILED to setOf(
            DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED,
        ),
    )

    fun canTransition(from: DownloadState, to: DownloadState): Boolean =
        ALLOWED[from]?.contains(to) == true

    /**
     * [record] moved to [toState], with the progress fields replaced
     * when given. Entering FAILED requires a non-blank [failureReason]
     * — a failure with no name is the bug this taxonomy exists to kill —
     * and every other state clears the reason.
     */
    fun transition(
        record: DownloadRecord,
        toState: DownloadState,
        downloadedBytes: Long = record.downloadedBytes,
        totalBytes: Long? = record.totalBytes,
        mimeType: String? = record.mimeType,
        bitrate: Int? = record.bitrate,
        failureReason: String? = null,
        updatedAtEpochMs: Long = record.updatedAtEpochMs,
    ): DownloadRecord {
        require(canTransition(record.state, toState)) {
            "illegal download transition ${record.state} -> $toState for ${record.videoId}"
        }
        if (toState == DownloadState.FAILED) {
            require(!failureReason.isNullOrBlank()) {
                "a FAILED download must name its failure"
            }
        }
        return record.copy(
            state = toState,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            mimeType = mimeType,
            bitrate = bitrate,
            failureReason = if (toState == DownloadState.FAILED) failureReason else null,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }
}

/**
 * Plans the bounded-range windows that fetch a file of [totalBytes]
 * exactly. Every window is bounded (an explicit end), windows are
 * contiguous and in order, and the last window ends at
 * `totalBytes - 1` — there is no open-ended tail, ever (the
 * RangeInterceptor discipline, applied by construction here: the
 * fetcher is handed windows, never a start alone).
 */
object DownloadChunkPlanner {

    /** Chunk size: the shared stack's proven bounded window, 1 MiB. */
    const val DEFAULT_CHUNK_BYTES: Long = 1024L * 1024L

    fun plan(totalBytes: Long, chunkBytes: Long = DEFAULT_CHUNK_BYTES): List<LongRange> {
        require(totalBytes >= 0L) { "totalBytes must not be negative" }
        require(chunkBytes > 0L) { "chunkBytes must be positive" }
        if (totalBytes == 0L) return emptyList()
        val windows = mutableListOf<LongRange>()
        var start = 0L
        while (start < totalBytes) {
            val endInclusive = minOf(start + chunkBytes - 1L, totalBytes - 1L)
            windows.add(start..endInclusive)
            start = endInclusive + 1L
        }
        return windows
    }

    /** The byte count one planned [window] covers. */
    fun windowSize(window: LongRange): Long = window.last - window.first + 1L
}

/**
 * The on-disk name for a track's finished download. videoIds are
 * already filename-safe in practice; any other character becomes
 * `_` so a hostile or odd id can never escape the downloads
 * directory. The extension is deliberately not an audio extension:
 * the type lives in the record's mimeType, and the file is played
 * through the record, never guessed from its name.
 */
fun downloadFileName(videoId: String): String {
    val safe = videoId.map { ch ->
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
    }.joinToString("")
    return (safe.ifEmpty { "track" }) + ".audio"
}

/** The partial-file name for [fileName] — never playable, never DONE. */
fun partialFileName(fileName: String): String = "$fileName.part"

// ---------------------------------------------------------------------------
// Storage accounting
// ---------------------------------------------------------------------------

/** Honest totals for the Downloads surface, computed — never stored. */
data class DownloadStorageSummary(
    val totalBytes: Long,
    val downloadedCount: Int,
    val queuedCount: Int,
    val downloadingCount: Int,
    val failedCount: Int,
)

/**
 * Summarises [records]. Only DONE records contribute bytes, and a DONE
 * record contributes the ACTUAL length of its file (via [fileLength],
 * null when the file is missing) — not the size the resolver once
 * promised. A DONE record whose file has gone missing counts as a
 * downloaded track with 0 bytes, which the surface shows honestly;
 * queued / downloading records contribute no bytes at all, because
 * partial bytes are discarded by policy and must not be accounted as
 * storage the user owns.
 */
fun summariseDownloads(
    records: List<DownloadRecord>,
    fileLength: (DownloadRecord) -> Long?,
): DownloadStorageSummary {
    var total = 0L
    var done = 0
    var queued = 0
    var downloading = 0
    var failed = 0
    for (record in records) {
        when (record.state) {
            DownloadState.DONE -> {
                done++
                total += fileLength(record) ?: 0L
            }
            DownloadState.QUEUED -> queued++
            DownloadState.DOWNLOADING -> downloading++
            DownloadState.FAILED -> failed++
        }
    }
    return DownloadStorageSummary(
        totalBytes = total,
        downloadedCount = done,
        queuedCount = queued,
        downloadingCount = downloading,
        failedCount = failed,
    )
}

/** Download progress as a whole percent, or null when the size is unknown yet. */
fun downloadProgressPercent(record: DownloadRecord): Int? {
    val total = record.totalBytes ?: return null
    if (total <= 0L) return null
    if (record.state == DownloadState.DONE) return 100
    val percent = (record.downloadedBytes.coerceIn(0L, total) * 100L) / total
    return percent.toInt().coerceIn(0, 100)
}

/** Plain byte formatting for the UI: "0 B", "512 B", "1.5 KB", "12 MB". */
fun formatDownloadBytes(bytes: Long): String {
    if (bytes < 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val text = if (value >= 100.0 || value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        "%.1f".format(value)
    }
    return "$text ${units[unitIndex]}"
}

// ---------------------------------------------------------------------------
// Playback precedence + auto-download selection (pure decisions)
// ---------------------------------------------------------------------------

/** A playable local download: the file to hand the player, and its type. */
data class DownloadedPlayback(
    val file: File,
    val mimeType: String?,
)

/**
 * THE offline promise, as a pure decision: a track plays from its
 * local file exactly when a record exists, the record is DONE, and
 * the file is really there with bytes in it. Anything else returns
 * null and playback falls back to network resolution. The queue
 * controller consults this (through an injected provider) BEFORE any
 * network resolution, so a DONE download takes precedence over every
 * road.
 */
fun downloadedPlaybackFor(
    record: DownloadRecord?,
    downloadsDir: File,
    fileLength: (File) -> Long? = { file -> if (file.isFile) file.length() else null },
): DownloadedPlayback? {
    if (record == null || record.state != DownloadState.DONE) return null
    val file = File(downloadsDir, record.fileName)
    val length = fileLength(file) ?: return null
    if (length <= 0L) return null
    return DownloadedPlayback(file = file, mimeType = record.mimeType)
}

/**
 * Which of [tracks] auto-download should enqueue: those with no
 * record at all, plus those whose last attempt FAILED (a retry is the
 * honest reading of "not already downloaded/queued"). DONE, QUEUED
 * and DOWNLOADING tracks are skipped, and a track listed twice is
 * enqueued once.
 */
fun tracksToAutoDownload(
    tracks: List<Track>,
    records: List<DownloadRecord>,
): List<Track> {
    val byVideoId = records.associateBy { it.videoId }
    val seen = mutableSetOf<String>()
    return tracks.filter { track ->
        if (!seen.add(track.videoId)) return@filter false
        val state = byVideoId[track.videoId]?.state
        state == null || state == DownloadState.FAILED
    }
}

/** A named, plain-language reason for a failed download attempt. */
fun downloadFailureReason(error: Throwable): String {
    val detail = error.message?.takeIf { it.isNotBlank() }
    // Core failures already carry plain-language messages; the class
    // name is prepended so the failure is NAMED (SPEC §6 shape), not
    // a bare catch-all.
    return if (detail != null) {
        "${error.javaClass.simpleName}: $detail"
    } else {
        error.javaClass.simpleName
    }
}
