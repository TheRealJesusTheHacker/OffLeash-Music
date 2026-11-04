package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sync-freshness and browse wiring contracts (bug sweep round 1),
 * in the repo's source-contract style: the Compose screens can't
 * run on this JVM, so the wiring that decides freshness is pinned
 * by shape.
 *
 * The defects these pin:
 * - Home/Playlist/Library reloaded only on the `signedIn` boolean,
 *   so an account SWITCH (identity changes, signedIn stays true)
 *   kept the previous account's data on screen. The identity is
 *   now part of every reload key.
 * - Home/Charts/Moods/Detail loads published unconditionally from
 *   blocking IO calls — the stale-search race, unfixed on those
 *   screens — so a slow anonymous load could land after sign-in
 *   and overwrite the authed feed. Every load now cancels its
 *   predecessor and publishes only while active.
 * - Mood category pages browsed an EMPTY browseId (an explicit
 *   `browseId = ""` overrode the model's correct default), so the
 *   surface could never return its own data.
 * - Playlist/Search continuation wiring exists end to end.
 */
class SyncFreshnessContractTest {

    private fun locateSource(relativeFromAppModule: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, relativeFromAppModule)
            candidates += File(dir, "app/$relativeFromAppModule")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("$relativeFromAppModule not found from " + File(".").absolutePath)
    }

    private fun source(relative: String): String = locateSource(relative).readText()

    private val home = "src/main/java/dev/cyberdogs/offleash/ui/home/HomeScreen.kt"
    private val charts = "src/main/java/dev/cyberdogs/offleash/ui/home/ChartsScreen.kt"
    private val moods = "src/main/java/dev/cyberdogs/offleash/ui/home/MoodsScreen.kt"
    private val detail = "src/main/java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt"
    private val library = "src/main/java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt"
    private val search = "src/main/java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt"

    @Test
    fun noBrowseLoadIsKeyedOnAccountIdentity() {
        // Re-based 0.9.0 (owner order, 2026-11-04): the identity-
        // keyed reload this test used to pin belonged to the
        // sign-in product, which is removed. The account-free
        // truth it must now hold is the stronger one: no browse
        // surface consults session or identity state at all, so
        // nothing can reload — or leak — on an identity change.
        for ((name, path) in listOf(
            "Home" to home, "Library" to library, "Detail" to detail,
        )) {
            val text = source(path)
            assertTrue(
                "$name must not consult session state",
                !text.contains("sessionState"),
            )
            assertTrue(
                "$name must not consult account identity",
                !text.contains("identityEmail"),
            )
        }
    }

    @Test
    fun everyBrowseLoadCancelsItsPredecessorAndPublishesOnlyWhileActive() {
        for ((name, path) in listOf(
            "Home" to home, "Charts" to charts, "Moods" to moods, "Detail" to detail,
        )) {
            val text = source(path)
            assertTrue("$name loads must cancel the previous job", text.contains("loadJob?.cancel()"))
            assertTrue("$name loads must guard the publish with isActive", text.contains("if (isActive)"))
        }
        // Round 6 strengthening: the file-level contains() above is
        // per FILE, but Moods carries TWO view models and Detail
        // THREE — one of them could lose its cancel/guard while its
        // siblings' copies kept this test green (a hollow pin: the
        // test's name claims EVERY load). Pin the counts so each
        // view model's own discipline is what stands or falls here.
        val moodsText = source(moods)
        assertTrue(
            "both Moods view models must cancel their previous load",
            moodsText.split("loadJob?.cancel()").size - 1 >= 2,
        )
        assertTrue(
            "both Moods view models must guard their publish with isActive",
            moodsText.split("if (isActive)").size - 1 >= 2,
        )
        val detailText = source(detail)
        assertTrue(
            "all three Detail view models must cancel their previous load",
            detailText.split("loadJob?.cancel()").size - 1 >= 3,
        )
        assertTrue(
            "all three Detail view models must guard their publish with isActive",
            detailText.split("if (isActive)").size - 1 >= 3,
        )
    }

    @Test
    fun libraryHasNoRemoteLoadToMisrecord() {
        // Re-based 0.9.0 (owner order, 2026-11-04): the Library's
        // account-playlist load this test guarded is removed with
        // the sign-in product — the Library is on-device content
        // only, so there is no load whose cancellation could be
        // misrecorded. The browse loads that remain (Detail) keep
        // their cancel-and-guard discipline, pinned above.
        val text = source(library)
        assertTrue(!text.contains("libraryPlaylists"))
        assertTrue(!text.contains("ytPlaylists"))
    }

    @Test
    fun moodCategoryPagesBrowseTheRealCategoryId() {
        // Line comments are stripped first: the rule is about CODE,
        // and a comment explaining the old defect must not be able
        // to trip (or satisfy) the assertion either way.
        val text = source(moods).lines().joinToString("\n") { line ->
            line.substringBefore("//")
        }
        assertFalse(
            "an explicit empty browseId sends every mood page to a dead surface",
            text.contains("browseId = \"\""),
        )
        assertTrue(
            "the category must be built from the route's params/title with the model default id",
            text.contains("MoodCategory(title = title, params = params)"),
        )
    }

    @Test
    fun continuationWiringExistsOnPlaylistAndSearch() {
        val detailText = source(detail)
        assertTrue(detailText.contains("fun loadMore()"))
        assertTrue(detailText.contains("playlistContinuation"))
        assertTrue(detailText.contains("ContinuationTracker"))
        val searchText = source(search)
        assertTrue(searchText.contains("fun loadMore()"))
        assertTrue(searchText.contains("searchContinuation"))
        assertTrue(searchText.contains("ContinuationTracker"))
    }
}
