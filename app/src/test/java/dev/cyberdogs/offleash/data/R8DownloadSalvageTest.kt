package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Round 8 (complete the round-7 honest-claim matrix): the
 * `honestFallbackOwner` map registered only the honest files a
 * dropped group happened to HOLD a record for. Two dropped groups
 * whose only fallbacks claim EACH OTHER's derived files — neither
 * holding an honest record for its own file — therefore took each
 * other's names and survived swapped: each track's record pointing
 * at the file the other track's bytes live under, the cross-play
 * disease rounds 3–5 fought, re-entering through the salvage walk.
 * A file named for a track's own id is that track's honest file by
 * DERIVATION, whether or not its group holds a record naming it.
 */
class R8DownloadSalvageTest {

    private fun storeFileWith(content: String): File {
        val dir = Files.createTempDirectory("offleash-r8-salvage").toFile()
        val file = File(dir, "downloads.json")
        file.writeText(content)
        return file
    }

    @Test
    fun crossClaimedDroppedGroupsDoNotSurviveSwapped() {
        // xxx's honest DONE record owns xxx.audio. aaa and bbb are
        // both dropped groups (their DONE winners claim xxx.audio
        // and lose it). Neither holds an honest record: aaa's only
        // fallback claims bbb.audio, bbb's only fallback claims
        // aaa.audio. Before round 8 the honest-claim map was empty
        // for both, so each walk took the other's file — aaa kept
        // a record naming bbb's file, bbb kept one naming aaa's.
        val aaaWinner = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val aaaClaimant = """{"track":{"videoId":"aaa","title":"A"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"bbb.audio"}"""
        val bbbWinner = """{"track":{"videoId":"bbb","title":"B"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val bbbClaimant = """{"track":{"videoId":"bbb","title":"B"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"aaa.audio"}"""
        val owner = """{"track":{"videoId":"xxx","title":"X"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val file = storeFileWith(
            """{"downloads":[$aaaWinner,$aaaClaimant,$bbbWinner,$bbbClaimant,$owner]}""",
        )
        val store = DownloadStore(file)
        assertEquals(
            "neither cross-claimant may keep the other's derived file; " +
                "both groups drop and only the honest owner survives",
            listOf("xxx"),
            store.records.value.map { it.videoId },
        )
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun survivorDoneClaimantBeatsDroppedGroupsQueuedHonestRecord() {
        // Matrix cell (round 8, dismissal-grade): a dropped group's
        // HONEST file is claimed by a survivor. bbb's DONE record
        // claims aaa.audio and wins the file dedupe (round 5's
        // rule: a DONE claimant's bytes are real, a QUEUED owner's
        // are not — a queued record keeps no bytes). aaa's honest
        // QUEUED fallback therefore cannot take aaa.audio back;
        // it salvages onto zzz.audio, a file no group derives or
        // owns, which the salvage has always allowed.
        val claimant = """{"track":{"videoId":"bbb","title":"B"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"aaa.audio"}"""
        val honestQueued = """{"track":{"videoId":"aaa","title":"A"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"aaa.audio"}"""
        val foreignClaim = """{"track":{"videoId":"aaa","title":"A"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"zzz.audio"}"""
        val file = storeFileWith(
            """{"downloads":[$claimant,$honestQueued,$foreignClaim]}""",
        )
        val store = DownloadStore(file)
        assertEquals(
            listOf("aaa", "bbb"),
            store.records.value.map { it.videoId }.sorted(),
        )
        assertEquals("aaa.audio", store.recordFor("bbb")?.fileName)
        assertEquals(DownloadState.DONE, store.recordFor("bbb")?.state)
        assertEquals("zzz.audio", store.recordFor("aaa")?.fileName)
        assertEquals(DownloadState.QUEUED, store.recordFor("aaa")?.state)
    }

    @Test
    fun doneClaimantFallbackStillLosesToTheHonestQueuedOwner() {
        // Matrix cell (the round-7 pinned case, claimant varied to
        // DONE): aaa's fallback claimant is DONE (tier 1, ahead of
        // its own group's honest records in walk order) naming
        // ccc.audio, while ccc's fallback is its honest QUEUED
        // record. The honest-claim guard applies regardless of the
        // claimant's tier: aaa drops, ccc keeps its own file.
        val aaaWinner = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val aaaClaimant = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"ccc.audio"}"""
        val cccWinner = """{"track":{"videoId":"ccc","title":"C"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val cccHonest = """{"track":{"videoId":"ccc","title":"C"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"ccc.audio"}"""
        val owner = """{"track":{"videoId":"xxx","title":"X"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val file = storeFileWith(
            """{"downloads":[$aaaWinner,$aaaClaimant,$cccWinner,$cccHonest,$owner]}""",
        )
        val store = DownloadStore(file)
        assertEquals(
            listOf("ccc", "xxx"),
            store.records.value.map { it.videoId },
        )
        assertEquals("ccc.audio", store.recordFor("ccc")?.fileName)
        assertEquals(DownloadState.QUEUED, store.recordFor("ccc")?.state)
    }
}
