package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for the 2026-10-24 stream-gate hunt (fleet
 * precedent: ManifestPlaybackContractTest reads the shipped
 * manifest the same way).
 *
 * The regression: the ladder probe and the player fetched the same
 * URL in different shapes — the probe with no UA of its own, the
 * player with an iOS app UA set on its data-source factory (and an
 * `Accept-Encoding: identity` Media3 adds) — and the gate served
 * the probe's shape (206) while refusing the player's (403), on
 * the owner's phone, in the same second. The fix makes the shared
 * stack the one shaping point (RangeInterceptor + the probe riding
 * it bare); these assertions keep the two call sites from quietly
 * growing their own shapes again:
 *
 * - AppContainer.buildPlayer must not set a User-Agent on the
 *   player's OkHttpDataSource factory — the fetch UA is the shared
 *   client's default, exactly the probe's.
 * - QueueController.probeUrl must not preset any header — its whole
 *   evidentiary value is that it sends what the player sends.
 */
class PlayerFetchShapeContractTest {

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
            ?: throw AssertionError(
                "$relativeFromAppModule not found from working dir " +
                    File(".").absolutePath + " (tried: " +
                    candidates.joinToString { it.path } + ")",
            )
    }

    @Test
    fun playerFactorySetsNoUserAgent() {
        val source = locateSource(
            "src/main/java/dev/cyberdogs/offleash/OffleashApp.kt",
        ).readText()
        assertFalse(
            "buildPlayer must not set a User-Agent on the player factory " +
                "(2026-10-24 hunt: the fetch UA is the shared client's default)",
            source.contains("setUserAgent"),
        )
    }

    @Test
    fun probePresetsNoHeaders() {
        val source = locateSource(
            "src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt",
        ).readText()
        // Round 12 strengthening (meta-audit): the isNotEmpty
        // check below is NOT a presence guard — substringAfter
        // returns the whole file when its anchor is absent, and
        // a whole file is never empty. (The pin happened to stay
        // deletion-sensitive only because the probe's KDoc above
        // it mentions `bytes=0-0`; that accident is not a guard.)
        assertTrue(
            "probeUrl must exist at all in QueueController",
            source.contains("private suspend fun probeUrl"),
        )
        val probeBody = source
            .substringAfter("private suspend fun probeUrl")
            .substringBefore("private fun setResolving")
        assertTrue("probeUrl not found in QueueController", probeBody.isNotEmpty())
        assertFalse(
            "the pre-hunt probe shape (Range: bytes=0-0) must not return",
            probeBody.contains("bytes=0-0"),
        )
        assertFalse(
            "probeUrl must not preset headers — it rides the shared stack bare",
            probeBody.contains(".header("),
        )
    }
}
