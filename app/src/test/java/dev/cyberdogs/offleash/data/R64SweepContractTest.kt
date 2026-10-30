package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 64 sweep contract (fresh-eyes lane): the Library
 * Downloads FAILED row is the ONLY place a download's failure
 * reason is ever shown (a whole-tree grep for `failureReason`
 * finds no other UI consumer — no dialog, no detail page, no
 * share text), and the row itself is not tappable for a FAILED
 * record (its click is enabled only for DONE, where it plays).
 * The status line was capped at 2 lines with ellipsis, so the
 * full reason was reachable NOWHERE: the stored reason runs to
 * 196 chars for a core failure (the AllRoadsRefused shape —
 * class name plus both roads' plain details, the round-25
 * worst case) and up to the 300-char cap for a non-core one,
 * displayed as "Download failed: …" in a column squeezed
 * between the artwork, the Retry button and the delete
 * button. Even the longest, most diagnostic reason — the one
 * whose tail names the second road's outcome — was cut.
 *
 * The fix is one line of presentation: a FAILED row's status
 * text is uncapped (the row grows; the LazyColumn scrolls),
 * every other state keeps the shipped 2-line bound. The
 * screen is Compose-bound, so the shape is the pin (house
 * style; CI is the compile gate).
 */
class R64SweepContractTest {

    private fun mainSource(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/$relativePath")
            candidates += File(dir, "app/src/main/$relativePath")
            dir = dir.parentFile
            hops++
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$relativePath not found from " + File(".").absolutePath
            )
        return found.readText()
    }

    private fun libraryScreen(): String =
        mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")

    @Test
    fun failedDownloadReasonIsNeverEllipsizedOutOfReach() {
        val screen = libraryScreen()
        // The FAILED branch still renders the full stored reason.
        assertEquals(
            "the Downloads row must render the record's full " +
                "failure reason in its FAILED branch",
            1,
            screen.split(
                "\"Download failed: \${record.failureReason ?: \"something went wrong\"}\"",
            ).size - 1,
        )
        // And that rendering is not capped at 2 lines for FAILED:
        // the status Text's maxLines is the conditional uncapped
        // form, exactly once in the file.
        assertEquals(
            "a FAILED download's status text must be uncapped — " +
                "it is the only surface that ever shows the " +
                "reason, the row is not tappable, and the stored " +
                "reason runs to 196–300 chars, so a 2-line cap " +
                "ellipsizes the full reason out of reach",
            1,
            screen.split(
                "maxLines = if (record.state == DownloadState.FAILED) Int.MAX_VALUE else 2,",
            ).size - 1,
        )
        // The status block itself must hold no unconditional cap.
        val statusBlock = screen.substringAfter(
            "text = downloadStatusText(record,",
        ).substringBefore("if (record.state == DownloadState.DOWNLOADING)")
        assertFalse(
            "the download status block must not reinstate an " +
                "unconditional 2-line cap on the FAILED reason",
            statusBlock.contains("maxLines = 2,"),
        )
        assertTrue(
            "the download status block must carry the FAILED " +
                "uncapped maxLines form",
            statusBlock.contains(
                "maxLines = if (record.state == DownloadState.FAILED) Int.MAX_VALUE else 2,",
            ),
        )
    }
}
