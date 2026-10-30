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
 * The record a run started with changed hands mid-flight (bug
 * sweep round 8): a delete removed it and a re-enqueue replaced
 * it, and the run's next write would land on the FRESH record.
 * The run unwinds without recording any outcome — its partial is
 * discarded and the fresh record is left exactly as it stands,
 * for its own job. Never surfaces as a failure reason.
 */
private class StaleDownloadRun : Exception("the download record changed hands mid-run")

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

        /** `bytes 0-0/12345` → 12345; a star total or anything else → null. */
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
        // DONE integrity: a record that claims DONE while its file
        // is missing, empty, or the wrong length (deleted or
        // trimmed behind the app's back) is not downloaded any
        // more. It returns to QUEUED and re-downloads, so the
        // record, the storage total, and what playback will do all
        // agree — a phantom DONE must never survive a restart.
        for (record in store.records.value) {
            if (record.state != DownloadState.DONE) continue
            val file = File(downloadsDir, record.fileName)
            val length = if (file.isFile) file.length() else null
            val intact = length != null && length > 0L &&
                (record.totalBytes == null || length == record.totalBytes)
            if (!intact) {
                store.transition(
                    videoId = record.videoId,
                    toState = DownloadState.QUEUED,
                    downloadedBytes = 0L,
                    nowMs = nowMs(),
                )
            }
        }
        // Orphan sweep: at this point no job of this process has
        // run, so every `.part` file on disk is a leftover from a
        // dead process (parts are never resumed), and every `.audio`
        // file no record names is unreachable — playback requires a
        // record — but still counted by the device as used storage.
        // Both are deleted; files a record names are never touched.
        sweepOrphanFiles()
        store.records.value
            .filter { it.state == DownloadState.QUEUED }
            .forEach { launchJob(it.track) }
    }

    /** Deletes stale partials and record-less audio files — see the init reconciliation. */
    private fun sweepOrphanFiles() {
        try {
            val tracked = store.records.value.map { it.fileName }.toSet()
            downloadsDir.listFiles()?.forEach { file ->
                val name = file.name
                // The partial suffix matches case-insensitively
                // (round 4): DownloadStore's stored-name check
                // rejects the same case variants as finished names,
                // and the two sides must agree on what the partial
                // namespace is — a `.PART` leftover the sweep
                // skipped would linger as unaccounted storage, and
                // a case-folding filesystem could make it the very
                // file a differently-cased record names.
                // The audio suffix matches case-insensitively too
                // (round 5): round 4 made `.part` case-insensitive
                // here but left `.audio` case-sensitive, so an
                // untracked `X.AUDIO` leftover — foreign-placed or
                // from a case-folding copy — was never swept and
                // sat as unaccounted storage forever, the same
                // split invariant in the sibling suffix.
                if (name.endsWith(".part", ignoreCase = true) ||
                    (name.endsWith(".audio", ignoreCase = true) && name !in tracked)
                ) {
                    deleteQuietly(file)
                }
            }
        } catch (e: Exception) {
            // A failed sweep leaves orphans, never phantom downloads.
        }
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

    /**
     * Enqueue the tracks in [tracks] that auto-download selects.
     * Two bounds keep an automatic caller (the Liked worker) honest:
     * at most [MAX_AUTO_DOWNLOADS_PER_RUN] tracks are enqueued per
     * call — a library of thousands must not become one endless
     * serial download session — and a track whose last attempt
     * FAILED less than [FAILED_AUTO_RETRY_COOLDOWN_MS] ago is
     * skipped, so a track that can never succeed (region-locked,
     * removed) is not re-fetched on every app start. A manual retry
     * goes through [enqueue] and is never cooled down. Returns the
     * tracks actually enqueued.
     */
    fun enqueueMissing(tracks: List<Track>): List<Track> {
        val now = nowMs()
        val selected = tracksToAutoDownload(tracks, store.records.value)
            .filter { track ->
                val record = store.recordFor(track.videoId)
                record?.state != DownloadState.FAILED ||
                    now - record.updatedAtEpochMs >= FAILED_AUTO_RETRY_COOLDOWN_MS
            }
            .take(MAX_AUTO_DOWNLOADS_PER_RUN)
        selected.forEach { enqueue(it) }
        return selected
    }

    /**
     * Remove every record and every file, tracked or orphaned:
     * the honest "clear all downloads". Jobs are cancelled first;
     * each record is then removed and the whole downloads
     * directory emptied, so no file can outlive the clear and be
     * counted forever. A download that was mid-flight when the
     * clear began unwinds against a missing record and leaves
     * nothing behind (see [runDownload]).
     */
    fun deleteAll() {
        val ids = synchronized(jobs) { jobs.keys.toList() }
        ids.forEach { videoId -> synchronized(jobs) { jobs.remove(videoId) }?.cancel() }
        store.records.value.map { it.videoId }.forEach { store.remove(it) }
        try {
            downloadsDir.listFiles()?.forEach { deleteQuietly(it) }
        } catch (e: Exception) {
            // Records are gone either way: nothing can read as
            // downloaded without a record.
        }
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
        // A QUEUED record's job never runs once cancelled — it is
        // parked on the run mutex, or not yet started — so no run
        // ever records the cancellation the in-flight path records
        // (bug sweep round 71): the record would sit QUEUED with
        // no live job until the next process start relaunched it,
        // showing "waiting to download" for a download the owner
        // already cancelled. Record the cancellation here, as the
        // FAILED the run would have written. The conditional
        // transition keeps a run that flipped the record to
        // DOWNLOADING in the same instant as the sole writer: its
        // own isActive check then records the cancellation.
        store.transitionIfState(
            videoId = videoId,
            expectedState = DownloadState.QUEUED,
            toState = DownloadState.FAILED,
            downloadedBytes = 0L,
            failureReason = "Download was cancelled",
            nowMs = nowMs(),
        )
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
        // One live job per track: a second launch while the first
        // is still active would only re-download the same bytes
        // from scratch behind the first job's back.
        synchronized(jobs) { if (jobs[track.videoId]?.isActive == true) return }
        // LAZY start: the job is registered in [jobs] before it can
        // run, so its own isActive check can never read "no job".
        var job: Job? = null
        val launched = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runMutex.withLock {
                runDownload(track) {
                    // The probe answers for THIS job, not for the
                    // videoId (bug sweep round 8): the old
                    // isJobActive(videoId) read true whenever ANY
                    // job stood registered, so a delete (which
                    // removes this job from the map) followed by a
                    // re-enqueue (which registers the FRESH job)
                    // left the cancelled stale job reading "still
                    // active" on the fresh job's registration — it
                    // downloaded on, into the fresh record.
                    val mine = job
                    mine != null && mine.isActive &&
                        synchronized(jobs) { jobs[track.videoId] === mine }
                }
            }
        }
        job = launched
        synchronized(jobs) { jobs[track.videoId] = launched }
        launched.invokeOnCompletion {
            synchronized(jobs) {
                if (jobs[track.videoId] === launched) jobs.remove(track.videoId)
            }
        }
        launched.start()
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
        // The record's stored file name is authoritative — it is
        // the name playback and delete look for — and only a track
        // with no record yet derives one from its id.
        val existingRecord = store.recordFor(videoId)
        val fileName = existingRecord?.fileName ?: downloadFileName(videoId)
        val finalFile = File(downloadsDir, fileName)
        val partFile = File(downloadsDir, partialFileName(fileName))

        // Discard policy, part 1: any stale bytes go before we start.
        deleteQuietly(partFile)
        deleteQuietly(finalFile)

        // Ownership (bug sweep round 8): this run owns exactly the
        // record it starts with, and every write it makes must land
        // on THAT record. Round 7 guarded only the terminal applies
        // (by state), so a delete + re-enqueue landing mid-flight
        // left a fresh QUEUED record standing — and the run's next
        // progress write simply ADOPTED it (QUEUED -> DOWNLOADING
        // is legal), after which the fresh job, arriving behind the
        // run mutex, deleted the file this run had just completed
        // at its own start and re-downloaded from scratch: a
        // completed download destroyed by its replacement, and
        // lost entirely when the replacement's run then failed.
        // The run therefore tracks the record value it last wrote
        // and refuses — as a stale run, recording nothing — the
        // moment the standing record is no longer that value.
        var ownedRecord: DownloadRecord? = null

        // Enter DOWNLOADING through the machine: a record that is not
        // already queued (a direct retry of FAILED, a re-download of
        // DONE) passes through QUEUED first, as the machine requires.
        val existing = existingRecord ?: store.enqueueIfAbsent(track, nowMs())
        ownedRecord = existing
        fun transitionOwned(
            toState: DownloadState,
            downloadedBytes: Long? = null,
            totalBytes: Long? = null,
            clearTotalBytes: Boolean = false,
            mimeType: String? = null,
            bitrate: Int? = null,
        ): DownloadRecord {
            // The ownership check and the write are ONE locked
            // step (bug sweep round 9): round 8 compared the
            // standing record here and transitioned in a second
            // store call, so a delete + re-enqueue landing between
            // the comparison and the transition was invisible to
            // the check — and QUEUED -> DOWNLOADING being legal,
            // the stale write adopted the fresh record through
            // that window, the same check-then-act shape round 8
            // closed for the terminal applies. The store now
            // re-checks the exact owned value inside its lock.
            val owned = ownedRecord ?: throw StaleDownloadRun()
            val updated = store.transitionIfRecord(
                expected = owned,
                toState = toState,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                clearTotalBytes = clearTotalBytes,
                mimeType = mimeType,
                bitrate = bitrate,
                nowMs = nowMs(),
            ) ?: throw StaleDownloadRun()
            ownedRecord = updated
            return updated
        }

        return try {
            if (existing.state != DownloadState.QUEUED &&
                existing.state != DownloadState.DOWNLOADING
            ) {
                transitionOwned(
                    toState = DownloadState.QUEUED,
                    downloadedBytes = 0L,
                    clearTotalBytes = true,
                )
            }
            transitionOwned(
                toState = DownloadState.DOWNLOADING,
                downloadedBytes = 0L,
                clearTotalBytes = true,
            )

            if (!isActive()) throw DownloadFailed("Download was cancelled")

            // Initial resolution goes through the existing resolver
            // path (production: PlaybackResolver) at the download
            // quality — never a download-only shortcut around it.
            val info = resolveStream(track, qualityProvider())
            transitionOwned(
                toState = DownloadState.DOWNLOADING,
                totalBytes = info.contentLength,
                mimeType = info.mimeType,
                bitrate = info.bitrate,
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
            transitionOwned(
                toState = DownloadState.DOWNLOADING,
                totalBytes = total,
                mimeType = info.mimeType,
                bitrate = info.bitrate,
            )

            downloadsDir.mkdirs()
            var downloaded = 0L
            FileOutputStream(partFile).use { out ->
                for (window in DownloadChunkPlanner.plan(total, chunkBytes)) {
                    if (!isActive()) throw DownloadFailed("Download was cancelled")
                    val bytes = fetcher.fetchRange(info.url, window)
                    // The fetcher contract is exact bytes for the
                    // window; verify it here too, at the seam, so
                    // no fetcher bug can assemble a shifted file
                    // whose total happens to add up.
                    val expectedWindow = DownloadChunkPlanner.windowSize(window)
                    if (bytes.size.toLong() != expectedWindow) {
                        throw DownloadFailed(
                            "the stream sent ${bytes.size} bytes for a $expectedWindow-byte range",
                        )
                    }
                    out.write(bytes)
                    downloaded += bytes.size
                    transitionOwned(
                        toState = DownloadState.DOWNLOADING,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        mimeType = info.mimeType,
                        bitrate = info.bitrate,
                    )
                }
                // The bytes must be on storage, not only in a
                // buffer, before the file may be renamed into a
                // "finished" download a later process will trust.
                out.flush()
                try {
                    out.fd.sync()
                } catch (e: Exception) {
                    // Best effort on exotic filesystems; the exact
                    // size check below still gates completion.
                }
            }

            // A cancel that landed during the FINAL window's fetch
            // must still count (bug sweep round 71): isActive was
            // last consulted before that fetch, so without this
            // re-check the run completes and lands DONE over the
            // owner's cancel — the job cancel is the only stop a
            // run has, and the completion path never consulted it.
            if (!isActive()) throw DownloadFailed("Download was cancelled")

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
            // Stale-outcome guard (bug sweep round 7): the DONE
            // apply is this run's only when the record is still in
            // the DOWNLOADING state THIS run put it in. A delete
            // landing while the bytes were in flight removes the
            // record; a delete followed by a fresh download tap
            // replaces it with a new QUEUED record that belongs to
            // the fresh job, not to this run. Applying DONE to that
            // fresh record is not merely wrong — QUEUED -> DONE is
            // an illegal transition, so the attempt threw into the
            // failure path below, which destroyed the finished
            // file and marked the fresh record FAILED with the
            // stale run's reason. A stale run now unwinds exactly
            // like a vanished record: its file goes, the record it
            // no longer owns is left exactly as it stands.
            val atCompletion = store.recordFor(videoId)
            when {
                atCompletion == null -> {
                    deleteQuietly(finalFile)
                    null
                }
                atCompletion.state == DownloadState.DONE -> atCompletion
                atCompletion.state != DownloadState.DOWNLOADING -> {
                    deleteQuietly(finalFile)
                    null
                }
                else -> {
                    // The apply itself is atomic (round 8): the
                    // state guard above is a separate read, and a
                    // delete + re-enqueue landing between it and
                    // the transition used to be saved only by the
                    // QUEUED -> DONE move happening to be illegal
                    // (the throw fell into the failure path). The
                    // conditional transition re-checks the state
                    // inside the store's lock, so the window is
                    // closed by construction, not by accident.
                    // The cancel gate is re-run LAST (round 72):
                    // round 71's re-check above guards the size
                    // check and the rename, but a cancel landing
                    // after it — while this stamp was being
                    // prepared — still landed DONE over the
                    // owner's cancel. The stamp's timestamp is
                    // taken FIRST, so the check below is the
                    // final word before the stamp: a cancel that
                    // has landed by then unwinds through the
                    // failure path like any other, discarding
                    // the renamed file and naming the cancel.
                    val stampMs = nowMs()
                    if (!isActive()) throw DownloadFailed("Download was cancelled")
                    val doneRecord = store.transitionIfState(
                        videoId = videoId,
                        expectedState = DownloadState.DOWNLOADING,
                        toState = DownloadState.DONE,
                        downloadedBytes = total,
                        totalBytes = total,
                        mimeType = info.mimeType,
                        bitrate = info.bitrate,
                        nowMs = stampMs,
                    )
                    if (doneRecord != null) {
                        doneRecord
                    } else {
                        // The record changed inside the window:
                        // mirror the branches above on a fresh read.
                        val changed = store.recordFor(videoId)
                        when {
                            changed == null -> {
                                deleteQuietly(finalFile)
                                null
                            }
                            changed.state == DownloadState.DONE -> changed
                            else -> {
                                deleteQuietly(finalFile)
                                null
                            }
                        }
                    }
                }
            }
        } catch (e: StaleDownloadRun) {
            // The record changed hands mid-run (round 8): this run
            // records no outcome at all. Its partial is discarded;
            // the record it no longer owns — and any file standing
            // under the shared name, which is not this run's to
            // judge — is left exactly as it stands for the fresh
            // record's own job.
            deleteQuietly(partFile)
            null
        } catch (e: Exception) {
            // Discard policy, part 2: no partial survives a failure,
            // and a failed record reports 0 kept bytes.
            deleteQuietly(partFile)
            val current = store.recordFor(videoId)
            when {
                current == null -> {
                    deleteQuietly(finalFile)
                    null
                }
                // The same stale-outcome guard as the completion
                // path (round 7): a failure applies only to the
                // record this run owns (DOWNLOADING). A fresh
                // QUEUED record left by a delete + re-download tap
                // during this run's flight must not be marked
                // FAILED with this run's reason — its own job has
                // not even started, and a FAILED stamp would also
                // start the auto-download cooldown against it.
                current.state != DownloadState.DOWNLOADING -> {
                    if (current.state != DownloadState.DONE) {
                        deleteQuietly(finalFile)
                    }
                    if (current.state == DownloadState.DONE) current else null
                }
                else -> {
                    deleteQuietly(finalFile)
                    // The apply is atomic (round 8), for the same
                    // reason as the completion path — and here the
                    // window was NOT saved by an illegal-transition
                    // throw: QUEUED -> FAILED is legal, so a fresh
                    // record landing between the guard read above
                    // and an unconditional transition would have
                    // been stamped FAILED with this run's reason.
                    // The conditional transition re-checks
                    // DOWNLOADING inside the store's lock.
                    val failedRecord = store.transitionIfState(
                        videoId = videoId,
                        expectedState = DownloadState.DOWNLOADING,
                        toState = DownloadState.FAILED,
                        downloadedBytes = 0L,
                        failureReason = downloadFailureReason(e),
                        nowMs = nowMs(),
                    )
                    if (failedRecord != null) {
                        failedRecord
                    } else {
                        // The record changed inside the window;
                        // report a standing DONE (another run's
                        // finished outcome) or nothing — never the
                        // fresh record this run does not own.
                        store.recordFor(videoId)
                            ?.takeIf { it.state == DownloadState.DONE }
                    }
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

    companion object {
        /** Most tracks one automatic [enqueueMissing] call may queue. */
        const val MAX_AUTO_DOWNLOADS_PER_RUN: Int = 50

        /** How long a FAILED track waits before auto-download retries it (6 hours). */
        const val FAILED_AUTO_RETRY_COOLDOWN_MS: Long = 6L * 60L * 60L * 1000L
    }
}
