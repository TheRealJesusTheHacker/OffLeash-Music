package dev.cyberdogs.offleash.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for the Sweep #5 stale-search fix, in the repo's
 * established contract-test style (see PlayerFetchShapeContractTest).
 *
 * The defect: SearchViewModel cancels the previous search job when
 * a new query arrives, but the client call is blocking — a
 * cancelled job's network call still runs to completion and the old
 * code then published its result unconditionally, overwriting the
 * newer search with stale results whenever the slow old query
 * finished last. The fix publishes only while the job is still
 * active. SearchViewModel itself needs AndroidX lifecycle + a main
 * dispatcher to drive on the JVM, which this suite does not carry;
 * this contract keeps the guard from being quietly removed, and
 * the reasoning is recorded in SearchScreen.kt at the guard.
 */
class SearchStaleResultContractTest {

    private fun locateSearchScreen(): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt")
            candidates += File(dir, "app/src/main/java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("SearchScreen.kt not found from " + File(".").absolutePath)
    }

    @Test
    fun searchResultIsPublishedOnlyWhileTheJobIsActive() {
        val source = locateSearchScreen().readText()
        val viewModelBody = source
            .substringAfter("class SearchViewModel")
            .substringBefore("@Composable")
        assertTrue(
            "SearchViewModel must guard its publish with isActive — " +
                "a superseded (cancelled) blocking search must not " +
                "overwrite the current search's state",
            viewModelBody.contains("if (isActive)"),
        )
        assertTrue(
            "the guarded publish must assign the computed result to _state",
            viewModelBody.contains("_state.value = result"),
        )
    }
}
