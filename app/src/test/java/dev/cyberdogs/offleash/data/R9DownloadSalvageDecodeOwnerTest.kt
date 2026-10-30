package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Round 9 (break the round-8 derivation ownership): round 8
 * registered a dropped group's derived file as owned for every
 * group present in `recordsById` — but a group whose entries all
 * fail the per-entry DECODE never reaches `recordsById` at all,
 * so it owned nothing, and a dropped claimant group's fallback
 * could take its derived file while the file still stood on disk
 * holding that track's bytes: the cross-play swap through the
 * decode door. The loader now salvages the dropped entry's track
 * id from the raw element and registers its derived name too.
 */
class R9DownloadSalvageDecodeOwnerTest {

    private fun storeFileWith(content: String): File {
        val dir = Files.createTempDirectory("offleash-r9-salvage").toFile()
        val file = File(dir, "downloads.json")
        file.writeText(content)
        return file
    }

    @Test
    fun undecodableGroupStillOwnsItsDerivedFileAgainstAClaimantFallback() {
        // xxx's honest DONE record owns xxx.audio. aaa is a
        // dropped group (its DONE winner claims xxx.audio and
        // loses it); its only fallback claims ccc.audio. ccc's
        // only entry is malformed (a non-numeric byte count) and
        // drops at decode — but ccc.audio on disk is ccc's file,
        // and aaa's fallback must not take it.
        val aaaWinner = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val aaaClaimant = """{"track":{"videoId":"aaa","title":"A"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"ccc.audio"}"""
        val cccMalformed = """{"track":{"videoId":"ccc","title":"C"},"state":"DONE",""" +
            """"downloadedBytes":"ten","totalBytes":10,"fileName":"ccc.audio"}"""
        val owner = """{"track":{"videoId":"xxx","title":"X"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val file = storeFileWith(
            """{"downloads":[$aaaWinner,$aaaClaimant,$cccMalformed,$owner]}""",
        )
        val store = DownloadStore(file)
        assertEquals(
            "the claimant fallback must not take the undecodable group's " +
                "derived file; aaa drops and only the honest owner survives",
            listOf("xxx"),
            store.records.value.map { it.videoId },
        )
        assertTrue(store.loadRecoveredFromCorruption)
    }
}
