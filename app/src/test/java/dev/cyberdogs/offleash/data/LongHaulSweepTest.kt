package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.StreamInfo
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.core.playback.QualityPreference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Bug sweep round 2 (lane C3): time, scale, and version pressure.
 *
 * The failures that only appear after weeks of use or an upgrade:
 * every persisted artifact read in its OLD shape (constructed from
 * the DTO's oldest fields — this tree carries no git history to
 * recover the true old bytes from), in a FUTURE shape (unknown
 * fields, unknown enum values, missing newer fields); growth at
 * 100–1000× realistic
 * scale; writes against a failing disk; and preferences with every
 * key malformed at once, including a store that throws.
 */
class LongHaulSweepTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    // -- helpers ------------------------------------------------------

    private fun track(id: String) = Track(
        videoId = id,
        title = "t-$id",
        artists = listOf("a"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 60,
        thumbnailUrl = null,
    )

    private fun await(label: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for: $label")
            Thread.sleep(10)
        }
    }

    /** Seals [json] under [box] into a fresh in-memory storage. */
    // -- 1. Sealed session stores: REMOVED in 0.9.0 -----------------
    //
    // This section pinned the sealed stores under old/future shapes
    // and a different key generation. The stores are deleted with
    // the sign-in product (owner order, 2026-11-04); what an
    // upgrading device still needs — the sealed files destroyed at
    // startup — is pinned in AccountFreeContractTest. The sections
    // below (downloads, playlists, recents, atomic writes) are the
    // surviving stores and keep their long-haul pins unchanged.

    // -- 2. DownloadStore: per-entry salvage + scale -------------------

    private fun downloadEntry(id: String, state: String = "FAILED"): String =
        """{"track":{"videoId":"$id","title":"T-$id","artists":["A"]},""" +
            """"state":"$state","downloadedBytes":0,"totalBytes":null,""" +
            """"fileName":"$id.audio","failureReason":"NetworkFailure: offline",""" +
            """"updatedAtEpochMs":1000}"""

    @Test
    fun downloadStoreSalvagesGoodRecordsAroundOneBadEntry() {
        val file = File(temp.newFolder(), "downloads.json")
        file.writeText(
            """{"downloads":[${downloadEntry("good1")},""" +
                """{"track":{"videoId":42,"title":true},"state":"DONE","fileName":"bad.audio"},""" +
                """${downloadEntry("good2")},""" +
                """${downloadEntry("futureState", state = "STREAMING")}]}""",
        )
        val store = DownloadStore(file)
        // The good records survive; only the malformed one is gone.
        assertEquals(
            listOf("good1", "good2", "futureState"),
            store.records.value.map { it.videoId },
        )
        // The loss is flagged, never silent.
        assertTrue(store.loadRecoveredFromCorruption)
        // An unknown (future) state degrades to a NAMED failure,
        // never to DONE and never to a crash.
        val future = store.recordFor("futureState")
        assertEquals(DownloadState.FAILED, future?.state)
        assertTrue(future?.failureReason?.isNotBlank() == true)
    }

    @Test
    fun salvagedDownloadRecordsKeepTheirFilesThroughTheOrphanSweep() {
        // The round-2 severity chain: before per-entry salvage, ONE
        // bad record read the whole store as empty and the
        // manager's startup sweep deleted every downloaded file as
        // an "orphan". Salvaged records keep their files; a truly
        // record-less file is still swept.
        val root = temp.newFolder()
        val downloadsDir = File(root, "downloads").apply { mkdirs() }
        File(downloadsDir, "good1.audio").writeBytes(ByteArray(10))
        File(downloadsDir, "orphan.audio").writeBytes(ByteArray(10))
        val file = File(root, "downloads.json")
        file.writeText(
            """{"downloads":[${downloadEntry("good1")},""" +
                """{"track":null,"state":"DONE","fileName":"x.audio"}]}""",
        )
        val store = DownloadStore(file)
        assertTrue(store.loadRecoveredFromCorruption)
        DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> throw DownloadFailed("unused in this test") },
            fetcher = object : ChunkFetcher {
                override fun fetchRange(url: String, window: LongRange): ByteArray =
                    throw DownloadFailed("unused in this test")

                override fun probeTotalBytes(url: String): Long? = null
            },
        )
        assertTrue("salvaged record's file must survive the sweep", File(downloadsDir, "good1.audio").isFile)
        assertFalse("a record-less file is still an orphan", File(downloadsDir, "orphan.audio").exists())
    }

    @Test
    fun downloadStoreScalesToTwoThousandRecords() {
        val file = File(temp.newFolder(), "downloads.json")
        val entries = (1..2000).joinToString(",") { downloadEntry("v$it") }
        file.writeText("""{"downloads":[$entries]}""")
        val store = DownloadStore(file)
        assertEquals(2000, store.records.value.size)
        assertFalse(store.loadRecoveredFromCorruption)
        // A mutation re-persists the whole set; a fresh store (the
        // next process start) reads all of it back.
        store.upsert(
            DownloadRecord(
                track = track("extra"),
                state = DownloadState.FAILED,
                failureReason = "NetworkFailure: offline",
                updatedAtEpochMs = 7L,
            ),
        )
        val reloaded = DownloadStore(file)
        assertEquals(2001, reloaded.records.value.size)
        assertNotNull(reloaded.recordFor("v1999"))
    }

    @Test
    fun downloadManagerReconciliationOverTwoThousandRecordsKeepsThemAll() {
        val root = temp.newFolder()
        val file = File(root, "downloads.json")
        val entries = (1..2000).joinToString(",") { downloadEntry("v$it") }
        file.writeText("""{"downloads":[$entries]}""")
        val store = DownloadStore(file)
        DownloadManager(
            store = store,
            downloadsDir = File(root, "downloads"),
            resolveStream = { _, _ -> throw DownloadFailed("unused in this test") },
            fetcher = object : ChunkFetcher {
                override fun fetchRange(url: String, window: LongRange): ByteArray =
                    throw DownloadFailed("unused in this test")

                override fun probeTotalBytes(url: String): Long? = null
            },
        )
        assertEquals(2000, store.records.value.size)
    }

    @Test
    fun partFileThatCannotBeOpenedFailsByNameAndKeepsNoFinalFile() {
        // Disk-pressure at the .part seam: the partial's path is
        // occupied by a directory (the on-disk shape of a part that
        // cannot be created/written — a full or failing volume
        // fails the same open). The download must land FAILED with
        // a named reason and no final file.
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads").apply { mkdirs() }
        // Non-empty, so the discard policy's delete cannot remove it
        // either — the part genuinely cannot be created or written.
        val partDir = File(downloadsDir, partialFileName(downloadFileName("vid1")))
        partDir.mkdirs()
        File(partDir, "occupant").writeBytes(ByteArray(1))
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ ->
                StreamInfo(
                    url = "https://example.invalid/stream",
                    mimeType = "audio/mp4",
                    bitrate = 128_000,
                    contentLength = 100L,
                    approxDurationMs = 200_000L,
                )
            },
            fetcher = object : ChunkFetcher {
                override fun fetchRange(url: String, window: LongRange): ByteArray =
                    ByteArray((window.last - window.first + 1).toInt())

                override fun probeTotalBytes(url: String): Long? = 100L
            },
            nowMs = { 42L },
        )
        store.enqueueIfAbsent(track("vid1"), nowMs = 1L)
        val result = manager.runDownload(track("vid1"))
        assertEquals(DownloadState.FAILED, result?.state)
        assertTrue(
            "failure must be named, was: ${result?.failureReason}",
            result?.failureReason?.contains("FileNotFoundException") == true ||
                result?.failureReason?.contains("IOException") == true,
        )
        assertFalse(File(downloadsDir, downloadFileName("vid1")).exists())
    }

    // -- 3. Playlists / recents: scale + duplicate shapes --------------

    @Test
    fun playlistsLoadAtScaleFiveHundredPlaylists() {
        val file = File(temp.newFolder(), "playlists.json")
        val trackJson = """{"videoId":"v","title":"t","artists":["a"]}"""
        val sb = StringBuilder("""{"playlists":[""")
        for (p in 1..500) {
            if (p > 1) sb.append(',')
            sb.append("""{"id":"p$p","name":"Mix $p","createdAtEpochMs":$p,"tracks":[""")
            for (t in 1..200) {
                if (t > 1) sb.append(',')
                sb.append(trackJson.replace("\"v\"", "\"v$p-$t\""))
            }
            sb.append("]}")
        }
        sb.append("]}")
        file.writeText(sb.toString())
        val store = LocalPlaylistStore(file)
        await("500 playlists loaded") { store.playlists.value.size == 500 }
        assertEquals(200, store.playlists.value.first().tracks.size)
        assertEquals(100_000, store.playlists.value.sumOf { it.tracks.size })
    }

    @Test
    fun playlistsWithDuplicateIdsLoadAsOnePlaylist() = runBlocking {
        val file = File(temp.newFolder(), "playlists.json")
        file.writeText(
            """{"playlists":[""" +
                """{"id":"p1","name":"First","createdAtEpochMs":1,"tracks":[]},""" +
                """{"id":"p1","name":"Fork","createdAtEpochMs":2,"tracks":[]},""" +
                """{"id":"p2","name":"Other","createdAtEpochMs":3,"tracks":[]}]}""",
        )
        val store = LocalPlaylistStore(file)
        await("playlists loaded") { store.playlists.value.isNotEmpty() }
        assertEquals(listOf("p1", "p2"), store.playlists.value.map { it.id })
        assertEquals("First", store.playlists.value.first().name)
    }

    @Test
    fun recentsFileWithDuplicatesAndThousandsOfEntriesLoadsCappedAndDeduped() {
        val file = File(temp.newFolder(), "recents.json")
        val sb = StringBuilder("""{"tracks":[""")
        // 3,000 entries: ids cycle through only 40 distinct tracks.
        for (i in 1..3000) {
            if (i > 1) sb.append(',')
            sb.append("""{"videoId":"v${i % 40}","title":"t","artists":["a"]}""")
        }
        sb.append("]}")
        file.writeText(sb.toString())
        val store = RecentsStore(file)
        await("recents loaded") { store.recents().value.isNotEmpty() }
        val recents = store.recents().value
        assertEquals(40, recents.size)
        assertEquals(recents.size, recents.map { it.videoId }.distinct().size)
    }

    @Test
    fun recentsAndPlaylistsLoadFutureShapesWithUnknownFields() {
        val recentsFile = File(temp.newFolder(), "recents.json")
        recentsFile.writeText(
            """{"tracks":[{"videoId":"a","title":"T","artists":["A"],"futureTrackField":1}],""" +
                """"futureTopLevel":true}""",
        )
        val recents = RecentsStore(recentsFile)
        await("recents loaded") { recents.recents().value.isNotEmpty() }
        assertEquals(listOf("a"), recents.recents().value.map { it.videoId })

        val playlistsFile = File(temp.newFolder(), "playlists.json")
        playlistsFile.writeText(
            """{"playlists":[{"id":"p1","name":"Mix","createdAtEpochMs":1,""" +
                """"tracks":[],"futurePlaylistField":{"x":1}}]}""",
        )
        val playlists = LocalPlaylistStore(playlistsFile)
        await("playlists loaded") { playlists.playlists.value.isNotEmpty() }
        assertEquals(listOf("p1"), playlists.playlists.value.map { it.id })
    }

    // -- 4. Storage pressure: atomic write + store persist -------------

    @Test
    fun atomicWriteFailureLeavesThePreviousGoodValueUntouched() {
        val dir = temp.newFolder()
        val target = File(dir, "store.json")
        target.writeText("previous-good-value")
        // Occupy the temp path with a directory: the temp write
        // itself fails (the full/failing-disk shape), so the rename
        // never happens and the old contents must stand byte-exact.
        File(dir, "store.json.tmp").mkdirs()
        assertThrows(Exception::class.java) {
            writeTextAtomically(target, "new-value-that-must-not-half-land")
        }
        assertEquals("previous-good-value", target.readText())
    }

    @Test
    fun playlistPersistFailureKeepsMemoryAndThePreviousFile() = runBlocking {
        val dir = temp.newFolder()
        val file = File(dir, "playlists.json")
        val store = LocalPlaylistStore(file)
        val first = store.createPlaylist("Kept")
        await("first playlist persisted") {
            file.isFile && file.readText().contains("Kept")
        }
        // Sabotage the next persist: the atomic temp path is a
        // directory, so the write throws inside the store, which
        // keeps in-memory state authoritative (its documented rule)
        // and must NOT corrupt the previous good file.
        File(dir, "playlists.json.tmp").mkdirs()
        store.createPlaylist("MemoryOnly")
        assertEquals(
            listOf("Kept", "MemoryOnly"),
            store.playlists.value.map { it.name },
        )
        assertTrue(file.readText().contains("Kept"))
        assertFalse(file.readText().contains("MemoryOnly"))
        // A fresh store (next process) reads the last GOOD value —
        // the failed save degraded honestly, never half-loaded.
        val fresh = LocalPlaylistStore(file)
        await("fresh store loaded") { fresh.playlists.value.isNotEmpty() }
        assertEquals(listOf(first.id), fresh.playlists.value.map { it.id })
    }

    // -- 5. Preferences: every key malformed at once -------------------

    private class ThrowingKeyValueStore : KeyValueStore {
        override fun getString(key: String): String? =
            throw ClassCastException("key holds a non-string")

        override fun putString(key: String, value: String) =
            throw RuntimeException("prefs write failed")
    }

    private class ThrowingParityStore : ParityKeyValueStore {
        override fun getString(key: String): String? =
            throw ClassCastException("key holds a non-string")

        override fun putString(key: String, value: String) =
            throw RuntimeException("prefs write failed")

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            throw ClassCastException("key holds a non-boolean")

        override fun putBoolean(key: String, value: Boolean) =
            throw RuntimeException("prefs write failed")
    }

    @Test
    fun appPreferencesWithEveryKeyMalformedReadAsDefaults() {
        val store = InMemoryKeyValueStore()
        store.putString(AppPreferences.KEY_STREAM_QUALITY, "ULTRA_HD_FUTURE")
        store.putString(AppPreferences.KEY_DOWNLOAD_QUALITY, "low") // renamed/casing drift
        store.putString(AppPreferences.KEY_AUTO_DOWNLOAD_LIKED, "yes")
        store.putString(AppPreferences.KEY_AUDIO_VERSION_IN_RADIO, "1")
        store.putString(AppPreferences.KEY_LOCAL_LIKED_IDS, "\n\n")
        store.putString(AppPreferences.KEY_PLAYBACK_SPEED, "ludicrous")
        store.putString(AppPreferences.KEY_KEEP_PITCH_AT_SPEED, "maybe")
        val prefs = AppPreferences(store)
        assertEquals(QualityPreference.LOW, prefs.streamQuality)
        assertEquals(DownloadQuality.FOLLOW_STREAM, prefs.downloadQuality)
        assertEquals(QualityPreference.LOW, prefs.effectiveDownloadQuality())
        assertFalse(prefs.autoDownloadLiked)
        assertFalse(prefs.audioVersionInRadio)
        assertEquals(1.0f, prefs.playbackSpeed)
        assertTrue(prefs.keepPitchAtSpeed)
        assertEquals(emptySet<String>(), prefs.localLikedVideoIds())
    }

    @Test
    fun appPreferencesOverAThrowingStoreReadAsDefaults() {
        // Every getter — playbackSpeed included (round 2 fix: it was
        // the one read that let the throw escape) — reads its
        // default when the store itself throws.
        val prefs = AppPreferences(ThrowingKeyValueStore())
        assertEquals(QualityPreference.LOW, prefs.streamQuality)
        assertEquals(DownloadQuality.FOLLOW_STREAM, prefs.downloadQuality)
        assertFalse(prefs.autoDownloadLiked)
        assertFalse(prefs.audioVersionInRadio)
        assertEquals(1.0f, prefs.playbackSpeed)
        assertTrue(prefs.keepPitchAtSpeed)
        assertEquals(emptySet<String>(), prefs.localLikedVideoIds())
    }

    @Test
    fun parityPreferencesOverAThrowingStoreReadAsDefaults() {
        val prefs = ParityPreferences(ThrowingParityStore())
        assertEquals(DownloadQualityPreference.FOLLOW_STREAM, prefs.downloadQuality)
        assertFalse(prefs.autoDownloadLiked)
        assertFalse(prefs.audioVersionInRadio)
    }

}
