package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 19 sweep contracts (confirmation round — fresh-eyes XII):
 *
 * The search screen's empty state read "No results for "$query""
 * with the LIVE text field interpolated. The field is not the
 * searched query: a 400 ms debounce separates typing from
 * searching, every keystroke restarts that debounce, and clearing
 * the field leaves the empty result on screen until the debounced
 * search lands as Idle. In those windows the label attributed an
 * empty result to a query that had never been searched — most
 * plainly "No results for ""." after the field was cleared, and
 * indefinitely while the listener kept typing past an empty
 * result. SearchResults carries no query, so nothing on the state
 * could correct the label.
 *
 * Fix: SearchUiState.Content carries the query its results were
 * actually searched for — set from the trimmed query at publish,
 * preserved across Load-more merges — and the label names that.
 *
 * SearchScreen is Compose-bound, so the shape is the pin (house
 * style; CI is the compile gate). The pin lives in the playback
 * test source set, like UiSweepContractTest, so the local harness
 * runs it.
 *
 * Round 20 strengthening (pins 1 and 3): both stamp pins asserted
 * their needle as slice presence. The needle occurs exactly once
 * in each slice at baseline, so dropping the stamp outright was
 * caught — but keeping the literal alive in a comment or dead
 * expression while the real construction stamped something else
 * passed. The load-more case is not cosmetic: stamping the
 * view-model's lastQuery field instead of the landed state's own
 * query breaks the stamp's atomicity with the state it labels
 * (the field advances at the next search() entry, in a separate
 * segment from the merge's state read, so a racing merge could
 * label one search's pages with the next search's query — the
 * round-19 defect class). Both pins now count their needle and
 * the load-more pin forbids reading the field at all.
 */
class R19SweepContractTest {

    @Test
    fun contentStateCarriesTheQueryItsResultsWereSearchedFor() {
        assertTrue(
            "the search Content state must exist at all",
            searchScreen().contains("data class Content("),
        )
        val declaration = searchScreen()
            .substringAfter("data class Content(")
            .substringBefore(") : SearchUiState")
        assertTrue(
            "Content must carry the searched query alongside the results",
            declaration.contains("val query: String"),
        )
        val search = searchScreen()
            .substringAfter("fun search(query: String")
            .substringBefore("fun loadMore()")
        assertTrue(
            "the publish must stamp Content with the trimmed query " +
                "that was actually searched, not a later field value",
            search.contains("SearchUiState.Content(results, trimmed)"),
        )
        assertEquals(
            "the search body must hold exactly one Content " +
                "construction — with the stamped call as the sole " +
                "construction, the needle cannot survive as a " +
                "comment or dead copy while the real publish " +
                "stamps something else",
            1,
            occurrences(search, "SearchUiState.Content("),
        )
    }

    @Test
    fun emptyResultsLabelNamesTheSearchedQueryNotTheLiveTextField() {
        val labelLines = searchScreen().lines()
            .filter { it.contains("No results for") }
        assertTrue(
            "the empty-results label must exist at all",
            labelLines.isNotEmpty(),
        )
        assertTrue(
            "the label must name the landed search's own query " +
                "(current.query) — the live text field is ahead of " +
                "the searched query by the debounce and keeps moving " +
                "while the user types, so it names searches that " +
                "never ran",
            labelLines.all { it.contains("current.query") },
        )
        assertFalse(
            "the label must not interpolate the live text field",
            labelLines.any { it.contains("\$query") },
        )
    }

    @Test
    fun loadMorePreservesTheSearchedQuery() {
        val loadMore = searchScreen()
            .substringAfter("fun loadMore()")
            .substringBefore("fun retry()")
        assertTrue(
            "a Load-more merge rebuilds Content from the latest " +
                "page; it must carry that state's query forward, " +
                "not drop or replace it",
            loadMore.contains("latestContent.query"),
        )
        assertEquals(
            "the merged state's query must be read from the landed " +
                "state exactly once — a second occurrence means " +
                "the literal survives as a decoy while the " +
                "construction stamps something else",
            1,
            occurrences(loadMore, "latestContent.query"),
        )
        assertFalse(
            "Load more must never stamp the view-model's lastQuery " +
                "field: the field advances at the next search() " +
                "entry, in a separate segment from the merge's " +
                "state read, so the stamp would no longer be " +
                "atomic with the results it labels",
            loadMore.contains("lastQuery"),
        )
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    private fun searchScreen(): String =
        mainSource("java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt")

    /** Reads a file under app/src/main, located like the other contract tests. */
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
                "$relativePath not found from " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
        return found.readText()
    }
}
