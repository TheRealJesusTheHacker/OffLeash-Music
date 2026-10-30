package dev.cyberdogs.offleash.data

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Round 3 (window descent): the WINNER HANDOFF, pinned at the
 * interceptor/construction level exactly like the shape-parity
 * tests pin the wire shape.
 *
 * The claim under test: when the ladder probe wins a window for a
 * stream, the player's loads of that stream are shaped with THAT
 * window — because resolution hands the winner to the player as
 * `ResolvedPlayback.windowBytes`, QueueController.handToPlayer
 * records it into the shared [StreamWindowStore] BEFORE
 * `setMediaItem`, and the production [RangeInterceptor] (the
 * player's only fetch-shaping path) reads the store per URL.
 *
 * The requests below replicate the player's fetches as documented
 * in StreamShapeParityTest (Media3 1.5.1 OkHttpDataSource shapes).
 */
class WinningWindowHandoffTest {

    private class FakeChain(private val original: Request) : Interceptor.Chain {
        var proceeded: Request? = null
        override fun request(): Request = original
        override fun proceed(request: Request): Response {
            proceeded = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .body("".toResponseBody(null))
                .build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private fun throughStack(request: Request, store: StreamWindowStore): Request {
        val chain = FakeChain(request)
        RangeInterceptor(store).intercept(chain)
        return chain.proceeded!!
    }

    private val gvUrl = "https://rr8---sn-example.googlevideo.com/videoplayback?expire=1"

    /** The player's first fetch (DataSpec(0, LENGTH_UNSET) shape). */
    private fun playerFirstFetch(): Request =
        Request.Builder().url(gvUrl)
            .header("Accept-Encoding", "identity")
            .get().build()

    @Test
    fun playersFirstLoadUsesTheWinningWindow() {
        // The exact hand-off sequence: the winner is recorded for
        // the URL, then the player's first load is constructed.
        val store = StreamWindowStore()
        store.recordWinner(gvUrl, 262144L)
        val out = throughStack(playerFirstFetch(), store)
        assertEquals("bytes=0-262143", out.header("Range"))
    }

    @Test
    fun smallestWindowWinReachesThePlayer() {
        // The tiny-window family of the proven on-track wins: a
        // 2 KiB win must reach the player as bytes=0-2047, not be
        // rounded back up to the 1 MiB default anywhere.
        val store = StreamWindowStore()
        store.recordWinner(gvUrl, 2048L)
        val out = throughStack(playerFirstFetch(), store)
        assertEquals("bytes=0-2047", out.header("Range"))
    }

    @Test
    fun reResolveOverwritesTheWinnerForTheNewUrlOnly() {
        // A player-stage 403 re-resolve mints a NEW URL and wins a
        // new window on it; the old URL's winner must not leak onto
        // the new URL, and clearing must restore the default.
        val store = StreamWindowStore()
        store.recordWinner(gvUrl, 2048L)
        val newUrl = "https://rr8---sn-example.googlevideo.com/videoplayback?expire=2"
        val fresh = throughStack(Request.Builder().url(newUrl).get().build(), store)
        assertEquals("bytes=0-1048575", fresh.header("Range"))
        store.recordWinner(newUrl, 65536L)
        val won = throughStack(Request.Builder().url(newUrl).get().build(), store)
        assertEquals("bytes=0-65535", won.header("Range"))
        store.clear(newUrl)
        val cleared = throughStack(Request.Builder().url(newUrl).get().build(), store)
        assertEquals("bytes=0-1048575", cleared.header("Range"))
    }

    @Test
    fun everyPlayerLoadStaysBoundedAtTheWinner() {
        // No load of the winning stream may leave open-ended or
        // range-less: first load, seek load (open-ended in), and a
        // bounded segment load (untouched) all stay bounded.
        val store = StreamWindowStore()
        store.recordWinner(gvUrl, 65536L)
        val first = throughStack(playerFirstFetch(), store)
        assertEquals("bytes=0-65535", first.header("Range"))
        val seek = throughStack(
            Request.Builder().url(gvUrl).header("Range", "bytes=100000-").get().build(),
            store,
        )
        assertEquals("bytes=100000-165535", seek.header("Range"))
        val segment = throughStack(
            Request.Builder().url(gvUrl).header("Range", "bytes=200000-265535").get().build(),
            store,
        )
        assertEquals("bytes=200000-265535", segment.header("Range"))
        assertNull(segment.header("Accept-Encoding"))
    }

    @Test
    fun winnerPersistsPerUrlAcrossRepeatedLoads() {
        // H1 pin (full bug sweep): the winning window is a
        // per-URL record, not a one-shot — every later load of the
        // same stream (segments, seeks, the recovery re-prepare's
        // reloads) is shaped with it, a different stream's loads
        // are untouched by it, and the ladder contents themselves
        // are the shipped descent (largest first, tiny last).
        assertEquals(
            listOf(1048576L, 262144L, 65536L, 2048L),
            RangeInterceptor.STREAM_WINDOW_LADDER,
        )
        val store = StreamWindowStore()
        store.recordWinner(gvUrl, 2048L)
        repeat(3) {
            val out = throughStack(playerFirstFetch(), store)
            assertEquals("bytes=0-2047", out.header("Range"))
        }
        assertEquals(2048L, store.windowFor(gvUrl))
        val otherUrl = "https://rr9---sn-other.googlevideo.com/videoplayback?expire=9"
        assertNull(store.windowFor(otherUrl))
        val other = throughStack(Request.Builder().url(otherUrl).get().build(), store)
        assertEquals("bytes=0-1048575", other.header("Range"))
    }

    @Test
    fun handToPlayerRecordsTheWinnerBeforeSettingTheItem() {
        // Source contract (the PlayerFetchShapeContractTest style):
        // the hand-off records the winner into the shared store
        // strictly before the player sees the item, so even the
        // player's first load is shaped with it.
        val source = locateSource(
            "src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt",
        ).readText()
        val handOff = source.substringAfter("private fun handToPlayer")
        val recordAt = handOff.indexOf("recordWinner")
        val setItemAt = handOff.indexOf("player.setMediaItem")
        assertTrue("handToPlayer not found", handOff.isNotEmpty())
        assertTrue("handToPlayer must record the winning window", recordAt >= 0)
        assertTrue("handToPlayer must set the media item", setItemAt >= 0)
        assertTrue(
            "the winner must be recorded BEFORE player.setMediaItem",
            recordAt < setItemAt,
        )
        assertTrue(
            "the winning window must be reported to diagnostics at the hand-off",
            handOff.substringBefore("player.setMediaItem").contains("recordWinningWindow"),
        )
    }

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
}
