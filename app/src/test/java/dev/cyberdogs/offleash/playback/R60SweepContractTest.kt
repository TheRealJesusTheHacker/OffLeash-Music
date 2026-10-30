package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 60 sweep contracts (adversarial lane — reconciling
 * round 59 lane B's carried observations O1/O2 line-by-line
 * against the post-round-59 tree):
 *
 *  Round 59's fix bound every O1 item (the QueueController
 *  lambda argument lines and the DownloadManager
 *  resolveStream body) and five O2 items (signInDiagnostics'
 *  RHS, sessionScope, appPreferences, parityPreferences,
 *  the player = buildPlayer(app) call line) — each verified
 *  this round by a direct doctor that fails exactly round
 *  59's feeding pin (revalidation dormancy leg, quality /
 *  local-file / audio-version re-points, speed source,
 *  speed-change line deletion, bearer drop, diagnostics
 *  RHS re-point, dispatcher respell, both fromContext
 *  re-points). Five O2 remainders were bound by NO test
 *  and each ran GREEN across the full 639-test app suite
 *  this round (files restored md5-verified after):
 *
 *  (F1) googlePendingGrantStore's box lines — round 58
 *  bound only its storage line, unlike the identical box
 *  lines of the other two stores. Doctor: the pending
 *  grant's box re-pointed at a different literal key alias
 *  ("offleash-pending-key"), so sealed grants would be
 *  unreadable under the shipped alias — GREEN 639/639.
 *
 *  (F2) buildPlayer's BODY — round 59 bound only the call
 *  line; the body's cache root was free (wake/focus/noisy
 *  and the UA absence are bound elsewhere). Doctor: the
 *  replay cache root moved cacheDir -> filesDir — GREEN
 *  639/639.
 *
 *  (F3) mediaControllerFuture and buildMediaController's
 *  SessionToken component — only the Builder/future.get()
 *  spellings are contains-pinned elsewhere. Doctor: the
 *  token's component re-pointed PlaybackService ->
 *  MainActivity, so the process-lifetime controller could
 *  never connect — GREEN 639/639.
 *
 *  (F4) _showPlayerRequests' declaration — the R50-R55
 *  censuses count its one compound WRITE, never its
 *  initial value or its exposure. Doctor: initial value
 *  0 -> 1, so the root would expand the player on every
 *  cold start — GREEN 639/639.
 *
 *  (F5) _accountUiState's declaration and
 *  accountUiStateSnapshot's BRANCHES — the snapshot's
 *  condition arguments are bound (GoogleSignInSeenMarker
 *  Test), its then/else results were not. Doctor: the two
 *  branches swapped, resurrecting the dormant legacy
 *  session as the presented account exactly when the
 *  dormancy rule says it must not — the round-1
 *  resurrection bug re-entering through the one function
 *  that exists to prevent it — GREEN 639/639.
 *
 *  The pins below bind those five remainders in the
 *  standing house style; F5's branches are bound as one
 *  verbatim block because presence-in-slice cannot see a
 *  swap of two lines that both belong there.
 *
 * The ViewModel/UI files are Compose-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R60SweepContractTest {

    @Test
    fun pendingGrantBoxMediaControllerAndShowPlayerRemaindersAreBound() {
        val app = fileText(
            "dev/cyberdogs/offleash/OffleashApp.kt",
        )
        // F1: the pending-grant store's seal, in its own
        // slice (round 58 bound the other two stores' box
        // lines but only this store's storage line).
        assertSliceLines(
            app,
            "GooglePendingGrantStore(",
            "val googleSignInSeenMarker",
            listOf(
                "box = AesGcmSecretBox {",
                "KeystoreSecretKey.getOrCreate(SESSION_KEY_ALIAS)",
            ),
            "googlePendingGrantStore box",
        )
        assertEquals(
            "exactly the three sealed stores may open the " +
                "session key alias — a fourth getOrCreate or " +
                "a re-pointed literal alias (round-60 F1 " +
                "doctor, GREEN across the full suite) moves " +
                "no slice",
            3,
            occurrences(
                app,
                "KeystoreSecretKey.getOrCreate(SESSION_KEY_ALIAS)",
            ),
        )
        // F3: the process-lifetime controller's declaration
        // and the session token's component.
        assertTrue(
            "mediaControllerFuture must be built by the " +
                "container's own buildMediaController",
            app.contains(
                "val mediaControllerFuture: " +
                    "ListenableFuture<MediaController>? = " +
                    "buildMediaController(app)",
            ),
        )
        assertSliceLines(
            app,
            "private fun buildMediaController",
            "private fun buildPlayer",
            listOf(
                "val token = SessionToken(",
                "ComponentName(app, PlaybackService::class.java)",
                "val future = MediaController.Builder(app, token).buildAsync()",
            ),
            "buildMediaController body",
        )
        // F4: the show-player counter's declaration and
        // exposure (its write is censused by R50-R55; its
        // birth value was bound by nothing).
        assertSliceLines(
            app,
            "private val _showPlayerRequests",
            "fun requestShowPlayer()",
            listOf(
                "kotlinx.coroutines.flow.MutableStateFlow(0)",
                "_showPlayerRequests",
            ),
            "_showPlayerRequests declaration",
        )
    }

    @Test
    fun accountUiStateSnapshotBranchesAreBoundVerbatim() {
        val app = fileText(
            "dev/cyberdogs/offleash/OffleashApp.kt",
        )
        assertTrue(
            "the presented account state must be born from " +
                "the snapshot function",
            app.contains(
                "private val _accountUiState = " +
                    "MutableStateFlow(accountUiStateSnapshot())",
            ),
        )
        assertTrue(
            "accountUiState must expose the snapshot flow " +
                "read-only",
            app.contains(
                "val accountUiState: " +
                    "StateFlow<SessionUiState> = " +
                    "_accountUiState.asStateFlow()",
            ),
        )
        // F5: the branches as ONE verbatim block — the
        // legacy state is the THEN branch of the dormancy
        // condition, the Google state the ELSE; swapping
        // them (round-60 doctor, GREEN across the full
        // suite) resurrects the dormant legacy session as
        // the presented account.
        assertTrue(
            "accountUiStateSnapshot must return the legacy " +
                "state only when the dormancy fallback " +
                "applies, and the Google state otherwise — " +
                "bound as a verbatim block because a branch " +
                "swap keeps every line present in any slice",
            app.contains(
                "return if (legacyFallbackApplies(" +
                    "google.signedIn, " +
                    "googleSignInSeenMarker.seen())) {\n" +
                    "            sessionManager.uiState.value\n" +
                    "        } else {\n" +
                    "            google\n" +
                    "        }",
            ),
        )
    }

    @Test
    fun buildPlayerCacheAndFactoryBodyIsBoundVerbatim() {
        val app = fileText(
            "dev/cyberdogs/offleash/OffleashApp.kt",
        )
        // F2: the player body's cache root, evictor and
        // factory chain (round 59 bound only the call line;
        // wake/focus/noisy are bound by
        // BackgroundPlaybackContractTest and the UA absence
        // by PlayerFetchShapeContractTest).
        assertSliceLines(
            app,
            "private fun buildPlayer",
            "private companion object",
            listOf(
                "val cache = SimpleCache(",
                "File(app.cacheDir, \"replay-cache\")",
                "LeastRecentlyUsedCacheEvictor(REPLAY_CACHE_BYTES)",
                "StandaloneDatabaseProvider(app)",
                "val httpFactory = OkHttpDataSource.Factory(okHttpClient)",
                "val upstreamFactory = DefaultDataSource.Factory(app, httpFactory)",
                ".setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)",
                "val mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory)",
            ),
            "buildPlayer body",
        )
        assertTrue(
            "the replay cache bound must stay 512 MB",
            app.contains(
                "const val REPLAY_CACHE_BYTES: Long = " +
                    "512L * 1024L * 1024L",
            ),
        )
    }

    // -- helpers ------------------------------------------------------------

    /** The text between two anchors, with both anchors
     *  required to exist in order (round 12's presence-guard
     *  discipline). */
    private fun slice(
        text: String,
        startAnchor: String,
        endAnchor: String,
        label: String,
    ): String {
        val start = text.indexOf(startAnchor)
        assertTrue(
            "$label: start anchor must exist: $startAnchor",
            start >= 0,
        )
        val end = text.indexOf(endAnchor, start)
        assertTrue(
            "$label: end anchor must exist after the start: $endAnchor",
            end > start,
        )
        return text.substring(start, end)
    }

    /** Every expected line must appear, trimmed, as a whole
     *  line inside the named slice. */
    private fun assertSliceLines(
        text: String,
        startAnchor: String,
        endAnchor: String,
        expectedLines: List<String>,
        label: String,
    ) {
        val body = slice(text, startAnchor, endAnchor, label)
        val lines = body.lines()
            .map { it.trim().trimEnd(',') }
            .toSet()
        for (expected in expectedLines) {
            assertTrue(
                "$label must wire exactly `$expected` — the " +
                    "round-59 reconciliation (round 60) found " +
                    "this remainder bound by no test, its " +
                    "doctor GREEN across the full 639-test " +
                    "app suite",
                expected.trim().trimEnd(',') in lines,
            )
        }
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    private fun fileText(relativePath: String): String {
        val file = File(locateAppJavaRoot(), relativePath)
        assertTrue("source must exist: $relativePath", file.isFile)
        return file.readText()
    }

    private fun locateAppJavaRoot(): File {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            for (candidate in listOf(
                File(dir, "src/main/java"),
                File(dir, "app/src/main/java"),
            )) {
                if (File(candidate, "dev/cyberdogs/offleash/ui/OffleashRoot.kt").isFile) {
                    return candidate
                }
            }
            dir = dir.parentFile
            hops++
        }
        throw AssertionError(
            "app/src/main/java not found from " + File(".").absolutePath,
        )
    }
}
