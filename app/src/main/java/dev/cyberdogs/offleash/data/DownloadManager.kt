package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.StreamInfo
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.core.playback.QualityPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A download attempt failed in a way that already has a plain name. */
class DownloadFailed(message: String) : Exception(message)

/**
 * Fetches exactly the bytes of one bounded [window] of [url].
 *
 * Implementations must request `Range: bytes=<first>-<last>` — a
 * bounded header built from the window, never a range-less or
 * open-ended GET (the RangeInterceptor discipline) — and must fail
 * rather than return the wrong number of bytes.
 */
interface ChunkFetcher {
    /** Bytes for [window] exactly, or a thrown failure naming why not. */
    fun fetchRange(url: String, window: LongRange): ByteArray

    /**
     * The total size behind [url] from a bounded `bytes=0-0` probe's
     * Content-Range, or null when the server does not state one.
     */
    fun probeTotalBytes(url: String): Long?
}

/** [ChunkFetcher] over the app's ONE shared OkHttp client. */
class OkHttpChunkFetcher(
    private val client: OkHttpClient,
) : ChunkFetcher {

    override fun fetchRange(url: String, window: LongRange): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${window.first}-${window.last}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != HTTP_PARTIAL_CONTENT) {
                throw DownloadFailed(
                    "the stream server answered HTTP ${response.code} " +
                        "instead of a partial range (206)",
                )
            }
            val body = response.body ?: throw DownloadFailed("the stream server sent no audio")
            val bytes = body.bytes()
            val expected = DownloadChunkPlanner.windowSize(window)
            if (bytes.size.toLong() != expected) {
                throw DownloadFailed(
                    "the stream sent ${bytes.size} bytes for a $expected-byte range",
                )
            }
            return bytes
        }
    }

    override fun probeTotalBytes(url: String): Long? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != HTTP_PARTIAL_CONTENT) return null
                parseContentRangeTotal(response.header("Content-Range"))
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        const val HTTP_PARTIAL_CONTENT: Int = 206

        /** `bytes 0-0/12345` → 12345; anything else (incl. `bytes 0-0/*`) → null. */
        fun parseContentRangeTotal(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            val total = header.substringAfter('/', "").trim()
            if (total.isEmpty() || total == "*") return null
            return total.toLongOrNull()?.takeIf { it >= 0L }
        }
    }
}

/**
 * The app-facing download engine.
 *
 * Resolution goes through the INJECTED resolver — in production that
 * is the core `PlaybackResolver` over the app's client and current
 * session, i.e. exactly the road-switching path playback uses; a
 * download never invents its own resolution. The quality handed to
 * the resolver is read fresh per download from [qualityProvider]
 * (production wires `AppPreferences.effectiveDownloadQuality()`, so
 * the download-quality preference is honored for downloads only).
 *
 * Fetching is bounded-chunk by bounded-chunk (see
 * [DownloadChunkPlanner]) into a `.part` file inside [downloadsDir];
 * the discard policy from Downloads.kt applies: no resume, a `.part`
 * file is deleted on any failure, and the final name appears only
 * after an exact size check.
 *
 * Downloads run one at a time: enqueued jobs serialize on a mutex, so
 * a bulk auto-download of Liked cannot open dozens of parallel chunk
 * streams on a metered carrier.
 */
class DownloadManager(
    val store: DownloadStore,
    private val downloadsDir: File,
    private val resolveStream: (track: Track, quality: QualityPreference) -> StreamInfo,
    private val fetcher: ChunkFetcher,
    private val qualityProvider: () -> QualityPreference = { QualityPreference.HIGH },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val chunkBytes: Long = DownloadChunkPlanner.DEFAULT_CHUNK_BYTES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    val records: StateFlow<List<DownloadRecord>> = store.records

    private val jobs = mutableMapOf<String, Job>()
    private val runMutex = Mutex()

    init {
        // Process-death reconciliation: a record left DOWNLOADING by a
        // killed process is not downloading any more. Its partial is
        // discarded (the discard policy) and it returns to QUEUED —
        // never DONE, never stuck — and queued records left behind by
        // the previous process get a fresh job.
        for (record in store.records.value) {
            if (record.state == DownloadState.DOWNLOADING) {
                deleteQuietly(File(downloadsDir, partialFileName(record.fileName)))
                store.transition(
                    videoId = record.videoId,
                    toState = DownloadState.QUEUED,
                    downloadedBytes = 0L,
                    clearTotalBytes = true,
                    nowMs = nowMs(),
                )
            }
        }
        store.records.value
            .filter { it.state == DownloadState.QUEUED }
            .forEach { launchJob(it.track) }
    }

    // -- Public API ---------------------------------------------------------

    /**
     * Queue [track] for download (or retry it, when FAILED). A DONE,
     * QUEUED, or DOWNLOADING track is left exactly as it is — enqueue
     * is idempotent, which is what auto-download relies on.
     */
    fun enqueue(track: Track) {
        val existing = store.recordFor(track.videoId)
        when (existing?.state) {
            DownloadState.DONE -> return
            DownloadState.QUEUED, DownloadState.DOWNLOADING -> {
                // Already going — unless its job is gone (cancelled
                // outside delete), in which case give it a fresh one.
                if (isJobActive(track.videoId)) return
                if (existing.state == DownloadState.QUEUED) {
                    launchJob(existing.track)
                    return
                }
                // A DOWNLOADING record with no job falls through and
                // is re-queued below.
            }
            else -> Unit
        }
        val queued = if (existing == null) {
            store.enqueueIfAbsent(track, nowMs())
        } else {
            store.transition(
                videoId = track.videoId,
                toState = DownloadState.QUEUED,
                downloadedBytes = 0L,
                clearTotalBytes = true,
                nowMs = nowMs(),
            ) ?: return
        }
        launchJob(queued.track)
    }

    /** Enqueue every track in [tracks] that [tracksToAutoDownload] selects. Returns the selected tracks. */
    fun enqueueMissing(tracks: List<Track>): List<Track> {
        val selected = tracksToAutoDownload(tracks, store.records.value)
        selected.forEach { enqueue(it) }
        return selected
    }

    /** Cancel any in-flight job for [videoId] and remove the record and its files. */
    fun delete(videoId: String) {
        synchronized(jobs) { jobs.remove(videoId) }?.cancel()
        val record = store.recordFor(videoId)
        store.remove(videoId)
        if (record != null) {
            deleteQuietly(File(downloadsDir, record.fileName))
            deleteQuietly(File(downloadsDir, partialFileName(record.fileName)))
        }
    }

    /** Cancel the in-flight job for [videoId], leaving its record for a later resume decision by the engine. */
    fun cancel(videoId: String) {
        synchronized(jobs) { jobs[videoId] }?.cancel()
    }

    /** The playable local file for [videoId], when it is DONE-downloaded — see [downloadedPlaybackFor]. */
    fun downloadedPlayback(videoId: String): DownloadedPlayback? =
        downloadedPlaybackFor(store.recordFor(videoId), downloadsDir)

    /** Actual length of [record]'s finished file, or null when there is none. */
    fun fileLength(record: DownloadRecord): Long? {
        val file = File(downloadsDir, record.fileName)
        return if (file.isFile) file.length() else null
    }

    /** Honest storage totals over the current records — see [summariseDownloads]. */
    fun storageSummary(): DownloadStorageSummary =
        summariseDownloads(store.records.value) { record -> fileLength(record) }

    // -- The download itself --------------------------------------------------

    private fun launchJob(track: Track) {
        // LAZY start: the job is registered in [jobs] before it can
        // run, so its own isActive check can never read "no job".
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runMutex.withLock {
                runDownload(track) { isJobActive(track.videoId) }
            }
        }
        synchronized(jobs) { jobs[track.videoId] = job }
        job.invokeOnCompletion {
            synchronized(jobs) {
                if (jobs[track.videoId] === job) jobs.remove(track.videoId)
            }
        }
        job.start()
    }

    private fun isJobActive(videoId: String): Boolean =
        synchronized(jobs) { jobs[videoId]?.isActive == true }

    /**
     * Runs one download to completion, synchronously. Internal (not
     * private) so JVM tests can drive it with a fake fetcher/resolver
     * without standing up coroutines or WorkManager. [isActive] is
     * consulted before each chunk: when it turns false the download
     * stops, discards its partial, and records a named FAILED
     * ("Download was cancelled") — unless its record was deleted
     * meanwhile, in which case nothing is recorded.
     *
     * Returns the final record (DONE or FAILED), or null when the
     * record vanished mid-flight.
     */
    internal fun runDownload(track: Track, isActive: () -> Boolean = { true }): DownloadRecord? {
        val videoId = track.videoId
        val fileName = downloadFileName(videoId)
        val finalFile = File(downloadsDir, fileName)
        val partFile = File(downloadsDir, partialFileName(fileName))

        // Discard policy, part 1: any stale bytes go before we start.
        deleteQuietly(partFile)
        deleteQuietly(finalFile)

        // Enter DOWNLOADING through the machine: a record that is not
        // already queued (a direct retry of FAILED, a re-download of
        // DONE) passes through QUEUED first, as the machine requires.
        val existing = store.recordFor(videoId) ?: store.enqueueIfAbsent(track, nowMs())
        if (existing.state != DownloadState.QUEUED &&
            existing.state != DownloadState.DOWNLOADING
        ) {
            store.transition(
                videoId = videoId,
                toState = DownloadState.QUEUED,
                downloadedBytes = 0L,
                clearTotalBytes = true,
                nowMs = nowMs(),
            )
        }
        store.transition(
            videoId = videoId,
            toState = DownloadState.DOWNLOADING,
            downloadedBytes = 0L,
            clearTotalBytes = true,
            nowMs = nowMs(),
        )

        return try {
            if (!isActive()) throw DownloadFailed("Download was cancelled")

            // Initial resolution goes through the existing resolver
            // path (production: PlaybackResolver) at the download
            // quality — never a download-only shortcut around it.
            val info = resolveStream(track, qualityProvider())
            store.transition(
                videoId = videoId,
                toState = DownloadState.DOWNLOADING,
                totalBytes = info.contentLength,
                mimeType = info.mimeType,
                bitrate = info.bitrate,
                nowMs = nowMs(),
            )

            val total = info.contentLength
                ?: fetcher.probeTotalBytes(info.url)
                ?: throw DownloadFailed(
                    "YouTube did not say how big this track's file is, " +
                        "so it cannot be downloaded in checked pieces.",
                )
            if (total <= 0L) {
                throw DownloadFailed("YouTube reported an empty file for this track.")
            }
            store.transition(
                videoId = videoId,
                toState = DownloadState.DOWNLOADING,
                totalBytes = total,
                mimeType = info.mimeType,
                bitrate = info.bitrate,
                nowMs = nowMs(),
            )

            downloadsDir.mkdirs()
            var downloaded = 0L
            FileOutputStream(partFile).use { out ->
                for (window in DownloadChunkPlanner.plan(total, chunkBytes)) {
                    if (!isActive()) throw DownloadFailed("Download was cancelled")
                    val bytes = fetcher.fetchRange(info.url, window)
                    out.write(bytes)
                    downloaded += bytes.size
                    store.transition(
                        videoId = videoId,
                        toState = DownloadState.DOWNLOADING,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        mimeType = info.mimeType,
                        bitrate = info.bitrate,
                        nowMs = nowMs(),
                    )
                }
            }

            // Only an exact-size partial may become the finished file.
            val partLength = if (partFile.isFile) partFile.length() else -1L
            if (partLength != total) {
                throw DownloadFailed(
                    "the downloaded file came to $partLength bytes, not the expected $total",
                )
            }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                deleteQuietly(partFile)
            }
            store.transition(
                videoId = videoId,
                toState = DownloadState.DONE,
                downloadedBytes = total,
                totalBytes = total,
                mimeType = info.mimeType,
                bitrate = info.bitrate,
                nowMs = nowMs(),
            )
        } catch (e: Exception) {
            // Discard policy, part 2: no partial survives a failure,
            // and a failed record reports 0 kept bytes.
            deleteQuietly(partFile)
            deleteQuietly(finalFile)
            if (store.recordFor(videoId) == null) {
                null
            } else {
                try {
                    store.transition(
                        videoId = videoId,
                        toState = DownloadState.FAILED,
                        downloadedBytes = 0L,
                        failureReason = downloadFailureReason(e),
                        nowMs = nowMs(),
                    )
                } catch (transitionError: Exception) {
                    // The record is in a state the machine will not
                    // move to FAILED from (it should not happen — the
                    // run entered DOWNLOADING above); report the
                    // record as it stands rather than crash the job.
                    store.recordFor(videoId)
                }
            }
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            // A stuck file is an orphan, never a phantom download:
            // playback requires a DONE record AND the file.
        }
    }
}
