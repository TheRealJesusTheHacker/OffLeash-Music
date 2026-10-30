package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
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
    fun toRecord(): DownloadRecord {
        val parsedState = try {
            DownloadState.valueOf(state)
        } catch (e: IllegalArgumentException) {
            // An unknown stored state must never read as DONE.
            DownloadState.FAILED
        }
        return DownloadRecord(
            track = track.toTrack(),
            state = parsedState,
            // A tampered or torn file must not be able to store
            // negative progress or a negative expected size.
            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
            totalBytes = totalBytes?.takeIf { it >= 0L },
            // The stored file name is used to build paths (playback,
            // delete). Anything that is not a plain file name — a
            // path separator, a traversal step — is replaced with
            // the name the id itself derives, so a corrupt or
            // tampered store can never point the app outside the
            // downloads directory.
            fileName = fileName.takeIf { name ->
                name.isNotBlank() && name != "." && name != ".." &&
                    !name.contains('/') && !name.contains('\\') &&
                    // A name in the partial-file namespace is not a
                    // finished file's name: the download manager's
                    // startup sweep deletes every `.part` file
                    // unconditionally, so a DONE record stored with
                    // such a name (tampered/foreign store — the same
                    // threat model as the traversal check above)
                    // would have its own file swept from under it
                    // while the record still claimed DONE (bug
                    // sweep round 3). The check is case-insensitive
                    // (round 4): the sweep matches the partial suffix
                    // case-insensitively too, and the two sides must
                    // agree on what the partial namespace IS — a
                    // `.PART`/`.Part` name the store kept but the
                    // sweep deleted (or vice versa) is exactly the
                    // round-3 hole in a different case.
                    !name.endsWith(".part", ignoreCase = true)
            } ?: downloadFileName(track.videoId),
            mimeType = mimeType,
            bitrate = bitrate,
            // A FAILED record must name its failure (the state
            // machine's rule) — a stored failure with no name gets
            // an honest one rather than a blank.
            failureReason = if (parsedState == DownloadState.FAILED) {
                failureReason?.takeIf { it.isNotBlank() }
                    ?: "DownloadFailed: the earlier failure was not recorded"
            } else {
                failureReason
            },
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }

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
 * A corrupt file never crashes the app over its own data: records
 * are salvaged per entry (round 2 — see [loadFromDisk]), and a file
 * that cannot be read as a downloads document at all reads as empty.
 * Note the honest consequence: records lost to
 * corruption leave orphan files behind, never phantom downloads —
 * playback precedence requires a DONE record AND the file. The loss
 * is not silent, though: [loadRecoveredFromCorruption] says the file
 * on disk could not be read, so a surface can tell the user their
 * download list could not be read and was started fresh (the
 * download manager also sweeps the now-orphaned files at start).
 * The next mutation rewrites the file cleanly, so recovery is a
 * one-time reset, never a crash loop.
 */
class DownloadStore(private val file: File) {

    private val lock = Any()

    /**
     * True when the records file existed but could not be read as a
     * downloads document (truncated, garbage, a shape this build
     * cannot parse) and the store therefore started empty. Set once,
     * at load; healthy and missing files read false.
     */
    var loadRecoveredFromCorruption: Boolean = false
        private set

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
            return transitionLocked(
                existing = existing,
                toState = toState,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                clearTotalBytes = clearTotalBytes,
                mimeType = mimeType,
                bitrate = bitrate,
                failureReason = failureReason,
                nowMs = nowMs,
            )
        }
    }

    /**
     * [transition], applied only while the record still stands in
     * [expectedState] — the state check and the transition are ONE
     * locked step (bug sweep round 8). The download engine's
     * terminal applies used to guard by reading the record and
     * then transitioning in a second call: a delete + re-enqueue
     * landing between the read and the transition was invisible to
     * the guard, and because QUEUED -> FAILED is a legal move
     * (unlike QUEUED -> DONE, which threw and was caught), the
     * stale run's FAILED stamp landed on the fresh record after
     * all. Returns null when no record exists or its state is no
     * longer [expectedState]; the record is left exactly as it
     * stands in both cases.
     */
    fun transitionIfState(
        videoId: String,
        expectedState: DownloadState,
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
            if (existing.state != expectedState) return null
            return transitionLocked(
                existing = existing,
                toState = toState,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                clearTotalBytes = clearTotalBytes,
                mimeType = mimeType,
                bitrate = bitrate,
                failureReason = failureReason,
                nowMs = nowMs,
            )
        }
    }

    /**
     * [transition], applied only while the standing record is
     * still EXACTLY [expected] — value check and transition are
     * ONE locked step (bug sweep round 9). Round 8's
     * `transitionOwned` in the download manager checked the
     * standing record against the record its run owned and then
     * transitioned in a second, separate call: a delete +
     * re-enqueue landing between the check and the transition
     * was invisible to the check, and because QUEUED ->
     * DOWNLOADING is a legal move the stale run's progress write
     * adopted the fresh record through the window — the exact
     * check-then-act shape round 8 closed for the terminal
     * applies with [transitionIfState], left open on every
     * progress write. Returns null when no record exists or the
     * standing record is no longer [expected]; the record is
     * left exactly as it stands in both cases.
     */
    fun transitionIfRecord(
        expected: DownloadRecord,
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
            val existing = recordFor(expected.videoId) ?: return null
            if (existing != expected) return null
            return transitionLocked(
                existing = existing,
                toState = toState,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                clearTotalBytes = clearTotalBytes,
                mimeType = mimeType,
                bitrate = bitrate,
                failureReason = failureReason,
                nowMs = nowMs,
            )
        }
    }

    /** The shared body of [transition] and [transitionIfState]. Caller holds [lock]. */
    private fun transitionLocked(
        existing: DownloadRecord,
        toState: DownloadState,
        downloadedBytes: Long?,
        totalBytes: Long?,
        clearTotalBytes: Boolean,
        mimeType: String?,
        bitrate: Int?,
        failureReason: String?,
        nowMs: Long,
    ): DownloadRecord {
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
        persistLocked(_records.value.map { if (it.videoId == existing.videoId) updated else it })
        return updated
    }

    fun remove(videoId: String) {
        synchronized(lock) {
            if (_records.value.none { it.videoId == videoId }) return
            persistLocked(_records.value.filterNot { it.videoId == videoId })
        }
    }

    private fun loadFromDisk(): List<DownloadRecord> {
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            loadRecoveredFromCorruption = true
            return emptyList()
        }
        // Per-entry salvage (bug sweep round 2), the Stores.kt rule
        // this store was missing: the old whole-document decode let
        // ONE malformed record — a torn write in an old build, a
        // future build's changed field type, a hand edit — read
        // back as "no downloads at all", and the download manager's
        // startup orphan sweep then deleted every downloaded audio
        // file the lost records had named. Now a record that cannot
        // decode is dropped on its own (and the loss is flagged,
        // never silent); every good record — and its file — stands.
        val root = try {
            downloadJson.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        }
        val array = root?.get("downloads") as? JsonArray
        if (array == null) {
            loadRecoveredFromCorruption = true
            return emptyList()
        }
        var droppedAny = false
        // Ids of entries that fail to decode (bug sweep round 9):
        // the entry is dropped, but its track id can often still
        // be read from the raw element — and the id alone is
        // enough to register the group's DERIVED file as owned
        // below. Round 8's derivation ownership registered only
        // groups that reached `recordsById` (i.e. decoded), so a
        // group lost at THIS pass owned nothing, and a dropped
        // claimant group's fallback could take its derived file
        // while the file still stood on disk with that track's
        // bytes in it — the cross-play swap, through the decode
        // door.
        val undecodableIds = mutableListOf<String>()
        val records = array.mapNotNull { element ->
            try {
                downloadJson.decodeFromJsonElement(
                    DownloadRecordDto.serializer(), element,
                ).toRecord()
            } catch (e: Exception) {
                droppedAny = true
                val rawId = ((element as? JsonObject)?.get("track") as? JsonObject)
                    ?.get("videoId")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                if (!rawId.isNullOrBlank()) undecodableIds += rawId
                null
            }
        }
        if (droppedAny) loadRecoveredFromCorruption = true
        // One record per track, whatever the file claims: a
        // duplicated id (a torn write in an old build, a hand
        // edit) must not fork one download into two records
        // that fight over the same file. The group's preferred
        // record wins (see [preferredDownloadRecord]) — plain
        // first-wins let a poisoned first entry (one claiming
        // another track's file) shadow the same track's honest
        // entry, which the file dedupe below then dropped
        // entirely, orphaning the honest entry's real file
        // (round 5).
        val recordsById = records.groupBy { it.videoId }
        val deduped = recordsById.values.map { group ->
            preferredDownloadRecord(group)
        }
        // One record per FILE, too (bug sweep round 3): two records
        // for different tracks that name the same file — only a
        // tampered/foreign store can produce that, since every
        // app-written name derives from its own videoId — would
        // play each other's audio and delete each other's file.
        // First record wins, as with duplicate ids — with one
        // refinement (round 4): when one record in a colliding
        // group names the file its OWN videoId derives (the
        // honest owner of that name) and another merely points at
        // it, the OWNER wins regardless of order. First-wins alone
        // let a poisoned first record — one naming a second
        // track's derived file — keep the file and play the second
        // track's audio under the first track's name, while the
        // clean second record was the one dropped. The dropped
        // record never had a file of its own (the file it named
        // was the owner's), so dropping it loses nothing real;
        // dropping the owner lost a genuine download and left the
        // cross-play in place. Round 5 adds the state half of the
        // preference (see [preferredDownloadRecord]): a DONE
        // claimant beats a FAILED/QUEUED owner, because the bytes
        // on disk are the DONE record's own — a FAILED record by
        // the discard policy keeps no bytes, so the file cannot be
        // the owner's, and dropping the DONE record lost the one
        // genuine completed download in the group.
        val winnerIds = deduped.groupBy { it.fileName }.values.map { group ->
            preferredDownloadRecord(group).videoId
        }.toSet()
        val uniqueFiles = deduped.filter { it.videoId in winnerIds }
        // Round 6: the two dedupe passes run in sequence, and the
        // first pass's winner can lose the second pass — a DONE
        // entry for aaa claiming bbb's file wins aaa's id group,
        // then loses bbb.audio to bbb's own DONE entry. Before this
        // round aaa was then gone ENTIRELY, even when its id group
        // held a fallback entry (its honest QUEUED record naming
        // aaa.audio) whose file nobody else claimed and which
        // could have coexisted with every survivor. A dropped id
        // now falls back to its group's next record in preference
        // order whose file is uncontested: the survivors' files
        // are reserved up front, so a fallback can never steal a
        // file a survivor owns, and one fallback never takes a
        // file another fallback just took.
        val reservedFiles = uniqueFiles.mapTo(mutableSetOf()) { it.fileName }
        // Round 7: a dropped group's HONEST file — the name its own
        // videoId derives — is claimed for that group up front, on
        // the same footing as a survivor's file. Round 6 reserved
        // only the survivors' files, so the fallback walks ran in
        // group order with no regard for ownership between dropped
        // groups: an earlier group's CLAIMANT fallback (a record
        // naming a third track's file) could take a later dropped
        // group's honest file before that group's own walk ran,
        // and the later group — the file's real owner, holding the
        // honest record round 6 exists to salvage — was dropped
        // entirely while the claimant kept its name. A claimant
        // fallback may still take any file no dropped group owns
        // honestly; it may never take one another dropped group
        // owns, and an owner's own walk is unaffected by its claim.
        // Round 8: ownership is by DERIVATION, not only by a held
        // record. Round 7 registered a dropped group's honest file
        // only when the group happened to HOLD a record naming it —
        // two dropped groups whose only fallbacks claimed EACH
        // OTHER's derived files, neither holding an honest record,
        // registered nothing at all, and each walk took the other's
        // file: both groups survived swapped, each record pointing
        // at the file the other track's bytes live under. A file
        // named for a track's own id can only be that track's
        // bytes (every app-written name derives from its id), so
        // every group present in the store owns its derived name
        // here, whether or not it holds a record naming it.
        val honestFallbackOwner = mutableMapOf<String, String>()
        for (videoId in recordsById.keys) {
            honestFallbackOwner.putIfAbsent(downloadFileName(videoId), videoId)
        }
        // Groups lost at the decode pass own their derived names
        // too (round 9, see `undecodableIds` above): their files
        // can still stand on disk, and no claimant fallback may
        // take a name whose owner's bytes those are.
        for (videoId in undecodableIds) {
            honestFallbackOwner.putIfAbsent(downloadFileName(videoId), videoId)
        }
        for (group in recordsById.values) {
            if (preferredDownloadRecord(group).videoId in winnerIds) continue
            for (record in group) {
                if (record.fileName == downloadFileName(record.videoId)) {
                    honestFallbackOwner.putIfAbsent(record.fileName, record.videoId)
                }
            }
        }
        val finalRecords = recordsById.values.mapNotNull { group ->
            val winner = preferredDownloadRecord(group)
            if (winner.videoId in winnerIds) {
                winner
            } else {
                group.withIndex()
                    .sortedWith(
                        compareBy(
                            { downloadPreferenceTier(it.value) },
                            { it.index },
                        ),
                    )
                    .map { it.value }
                    .firstOrNull {
                        it.fileName !in reservedFiles &&
                            (honestFallbackOwner[it.fileName] == null ||
                                honestFallbackOwner[it.fileName] == it.videoId)
                    }
                    ?.also { reservedFiles += it.fileName }
            }
        }
        // A record dropped by either dedupe is a loss exactly like
        // an undecodable entry: it is flagged, never silent (the
        // round-2 rule this method's salvage comment states).
        // Before round 3 the dedupe drops were the one silent loss
        // in this loader, so the Library's corruption notice (which
        // reads this flag) missed them. The flag still compares
        // against the raw record count, so a fallback salvage —
        // which replaces a dropped winner one-for-one within its
        // group but still drops the group's other entries — is
        // flagged exactly when any stored record was lost.
        if (finalRecords.size != records.size) {
            loadRecoveredFromCorruption = true
        }
        return finalRecords
    }

    private fun persistLocked(records: List<DownloadRecord>) {
        _records.value = records
        try {
            file.parentFile?.mkdirs()
            val payload = DownloadsFile(records.map { DownloadRecordDto.from(it) })
            writeTextAtomically(file, downloadJson.encodeToString(payload))
        } catch (e: Exception) {
            // Best-effort persistence; in-memory state stays
            // authoritative for this session (Stores.kt's rule).
        }
    }
}

/**
 * Which record of a colliding group (same videoId, or same
 * fileName) survives the load dedupes. A completed download is the
 * only entry that can own real bytes on disk, so DONE beats every
 * other state; within a state class the honest owner — the record
 * whose fileName is the name its OWN videoId derives — beats a
 * mere claimant; anything still tied falls back to first-in-file,
 * the round-3 rule.
 */
private fun preferredDownloadRecord(group: List<DownloadRecord>): DownloadRecord =
    group.firstOrNull {
        it.state == DownloadState.DONE && it.fileName == downloadFileName(it.videoId)
    } ?: group.firstOrNull { it.state == DownloadState.DONE }
        ?: group.firstOrNull { it.fileName == downloadFileName(it.videoId) }
        ?: group.first()

/**
 * The preference tier of one record (round 6): the ordering
 * [preferredDownloadRecord] applies within a group, as a rank —
 * DONE+owner first, then DONE, then owner, then the rest. The
 * loader's fallback salvage walks a dropped id's group in this
 * order (first-in-file within a tier), so the fallback is the
 * record the preference itself would have picked next.
 */
private fun downloadPreferenceTier(record: DownloadRecord): Int = when {
    record.state == DownloadState.DONE &&
        record.fileName == downloadFileName(record.videoId) -> 0
    record.state == DownloadState.DONE -> 1
    record.fileName == downloadFileName(record.videoId) -> 2
    else -> 3
}

/** Where the download records file lives: the app files dir (NOT the no-backup session dir). */
fun downloadRecordsFileIn(filesDir: File): File = File(filesDir, "downloads.json")

/** Where downloaded audio lives: `downloads/` under the app files dir (NOT the no-backup session dir). */
fun downloadsDirIn(filesDir: File): File = File(filesDir, "downloads")
