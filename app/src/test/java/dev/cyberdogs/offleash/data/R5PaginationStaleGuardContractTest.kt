package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 5 fresh-eyes sweep: the stale-result guards from round 1 /
 * Sweep #5 protected the published STATE in the two paging view
 * models (Search, Playlist), but not the shared ContinuationTracker
 * sitting next to it — and the tracker is what "load more" reads.
 *
 * Two unguarded mutations survived:
 *
 * 1. `search()`/`load()` called `continuationTracker.start(...)`
 *    inside the try, BEFORE the `if (isActive)` publish guard. The
 *    client call is blocking, so a superseded job runs to completion
 *    and — when it returns after the newer search/refresh started
 *    its own paging session — it still executed start() with the
 *    stale token, resetting the NEW session's tracker (seen set
 *    included) to the old session's token while _state correctly
 *    stayed new. The new session's Load more then fetched the old
 *    session's pages, its merge guard refused every one, and
 *    hasMore stayed on: a dead Load more control for that session.
 *
 * 2. `loadMore()` advanced the tracker with a plain `advance(...)`
 *    from a coroutine that is NOT the search/load job, so nothing
 *    ever cancels it and its isActive is always true. A refresh
 *    landing mid-fetch reset/re-started the tracker; the stale
 *    chunk's return then advanced the NEW session to the OLD
 *    session's next token — the merge-side comment even claimed
 *    "its tracker was reset by the refresh, ending paging above",
 *    which the unguarded advance made false.
 *
 * The fix: start() moved inside the isActive publish block in both
 * view models, and both loadMore()s advance through
 * [dev.cyberdogs.offleash.core.model.ContinuationTracker.tryAdvance]
 * (unit-tested in :core), which refuses — leaving the tracker
 * untouched — when the sent token is no longer the current one.
 * The view models are Compose/lifecycle-bound (not JVM-compilable
 * in this suite), so this contract pins the source shapes, in the
 * house style of SearchStaleResultContractTest / R4FreshEyes.
 */
class R5PaginationStaleGuardContractTest {

    @Test
    fun searchStartsItsTrackerOnlyInsideThePublishGuard() {
        val search = mainSource("java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt")
        val body = search
            .substringAfter("fun search(query: String")
            .substringBefore("fun loadMore()")
        val guardAt = body.indexOf("if (isActive)")
        val startAt = body.indexOf("continuationTracker.start(")
        assertTrue("search() must publish under an isActive guard", guardAt >= 0)
        assertTrue(
            "search() must start the continuation tracker only inside the " +
                "isActive publish guard — a superseded blocking search " +
                "otherwise clobbers the newer search's paging session",
            startAt > guardAt,
        )
    }

    @Test
    fun searchLoadMoreAdvancesThroughTheSupersededSessionGuard() {
        val search = mainSource("java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt")
        val body = search
            .substringAfter("fun loadMore()")
            .substringBefore("fun retry()")
        assertTrue(
            "search loadMore() must advance via tryAdvance so a stale chunk " +
                "cannot move a newer search's tracker",
            body.contains("continuationTracker.tryAdvance(token"),
        )
        assertFalse(
            "search loadMore() must not call the unguarded advance()",
            body.contains("continuationTracker.advance("),
        )
    }

    @Test
    fun playlistLoadStartsItsTrackerOnlyInsideThePublishGuard() {
        val detail = mainSource("java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt")
        val body = detail
            .substringAfter("class PlaylistViewModel")
            .substringAfter("fun load()")
            .substringBefore("fun loadMore()")
        val guardAt = body.indexOf("if (isActive)")
        val startAt = body.indexOf("continuationTracker.start(")
        assertTrue("playlist load() must publish under an isActive guard", guardAt >= 0)
        assertTrue(
            "playlist load() must start the continuation tracker only inside " +
                "the isActive publish guard — a superseded blocking load " +
                "otherwise clobbers the refreshed page's paging session",
            startAt > guardAt,
        )
    }

    @Test
    fun playlistLoadMoreAdvancesThroughTheSupersededSessionGuard() {
        val detail = mainSource("java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt")
        val body = detail
            .substringAfter("class PlaylistViewModel")
            .substringAfter("fun loadMore()")
            .substringBefore("@Composable")
        assertTrue(
            "playlist loadMore() must advance via tryAdvance so a stale chunk " +
                "cannot move a refreshed page's tracker",
            body.contains("continuationTracker.tryAdvance(token"),
        )
        assertFalse(
            "playlist loadMore() must not call the unguarded advance()",
            body.contains("continuationTracker.advance("),
        )
    }

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
