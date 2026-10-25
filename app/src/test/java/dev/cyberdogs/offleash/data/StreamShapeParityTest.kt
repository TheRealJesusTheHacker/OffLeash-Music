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
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The 2026-10-24 hunt's central guarantee, pinned: the ladder probe
 * and the player's fetches leave the shared stack in the SAME wire
 * shape, so a probe verdict predicts the player's own fetch.
 *
 * The on-device evidence this answers: on the owner's phone, the
 * probe (`Range: bytes=0-0`, no UA of its own) was served HTTP 206
 * on a freshly resolved URL, and the player's fetches of that same
 * URL were refused HTTP 403 four times in a row — twice, across a
 * re-resolve. Same phone, same network, same URL, sub-second apart:
 * the requests differed, so the gate's answer differed.
 *
 * The player-side requests below replicate, header for header, what
 * Media3 1.5.1's OkHttpDataSource.makeRequest builds (read from the
 * published 1.5.1 sources during the hunt):
 * - first fetch — DataSpec(position=0, length=LENGTH_UNSET), the
 *   DataSpec CacheDataSource passes upstream on a cold cache:
 *   Range is ABSENT (`HttpUtil.buildRangeRequestHeader(0,
 *   LENGTH_UNSET)` returns null), the factory User-Agent is ABSENT
 *   (AppContainer.buildPlayer sets none — pinned by
 *   PlayerFetchShapeContractTest), and `Accept-Encoding: identity`
 *   is PRESENT (the DataSpec does not carry FLAG_ALLOW_GZIP).
 * - seek fetch — DataSpec(position=5000, length=LENGTH_UNSET):
 *   `Range: bytes=5000-` (open-ended), same Accept-Encoding.
 *
 * The probe-side request replicates QueueController.probeUrl: no
 * headers of its own at all. Every request then passes through the
 * production [RangeInterceptor] — the stack's one shaping point —
 * and the emerged requests must agree on Range boundedness,
 * User-Agent (absent: OkHttp's default applies downstream), and
 * Accept-Encoding (absent).
 */
class StreamShapeParityTest {

    private class FakeChain(private val original: Request) : Interceptor.Chain {
        var proceeded: Request? = null
        override fun request(): Request = original
        override fun proceed(request: Request): Response {
            proceeded = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
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

    private fun throughStack(request: Request): Request {
        val chain = FakeChain(request)
        RangeInterceptor().intercept(chain)
        return chain.proceeded!!
    }

    private fun throughStack(request: Request, store: StreamWindowStore): Request {
        val chain = FakeChain(request)
        RangeInterceptor(store).intercept(chain)
        return chain.proceeded!!
    }

    private val gvUrl = "https://rr8---sn-example.googlevideo.com/videoplayback?expire=1"

    /** The ladder probe's request: no headers of its own (probeUrl). */
    private fun probeRequest(): Request =
        Request.Builder().url(gvUrl).get().build()

    /** The player's first fetch, per OkHttpDataSource 1.5.1 (above). */
    private fun playerFirstFetchRequest(): Request =
        Request.Builder().url(gvUrl)
            .header("Accept-Encoding", "identity")
            .get().build()

    /** The player's seek fetch, per OkHttpDataSource 1.5.1 (above). */
    private fun playerSeekRequest(): Request =
        Request.Builder().url(gvUrl)
            .header("Range", "bytes=5000-")
            .header("Accept-Encoding", "identity")
            .get().build()

    @Test
    fun probeAndPlayerFirstFetchEmergeIdentical() {
        val probe = throughStack(probeRequest())
        val player = throughStack(playerFirstFetchRequest())
        assertEquals(probe.header("Range"), player.header("Range"))
        assertEquals("bytes=0-1048575", player.header("Range"))
        assertNull(probe.header("User-Agent"))
        assertNull(player.header("User-Agent"))
        assertNull(probe.header("Accept-Encoding"))
        assertNull(player.header("Accept-Encoding"))
    }

    @Test
    fun playerSeekFetchIsBoundedAndShapedLikeTheProbe() {
        val seek = throughStack(playerSeekRequest())
        assertEquals("bytes=5000-1053575", seek.header("Range"))
        assertNull(seek.header("User-Agent"))
        assertNull(seek.header("Accept-Encoding"))
    }

    @Test
    fun downloadChunkFetchFollowsTheSameShape() {
        // OkHttpChunkFetcher (downloads) issues its own bounded
        // ranges through the same client; they must emerge with the
        // same UA/AE shape as playback fetches.
        val chunk = throughStack(
            Request.Builder().url(gvUrl)
                .header("Range", "bytes=1048576-2097151")
                .get().build(),
        )
        assertEquals("bytes=1048576-2097151", chunk.header("Range"))
        assertNull(chunk.header("User-Agent"))
        assertNull(chunk.header("Accept-Encoding"))
    }

    // -- Round 3: parity holds at the WINNING window, not only the default --

    @Test
    fun probeAndPlayerStayIdenticalAtTheWinningWindow() {
        // The ladder won 64 KiB for this URL and recorded it; the
        // probe fetch that won and the player's first fetch of the
        // same URL must emerge with that exact window.
        val store = StreamWindowStore()
        store.recordWinner(gvUrl, 65536L)
        val probe = throughStack(probeRequest(), store)
        val player = throughStack(playerFirstFetchRequest(), store)
        assertEquals("bytes=0-65535", probe.header("Range"))
        assertEquals(probe.header("Range"), player.header("Range"))
        assertNull(player.header("User-Agent"))
        assertNull(player.header("Accept-Encoding"))
    }

    @Test
    fun playerSeekFetchUsesTheWinningWindow() {
        val store = StreamWindowStore()
        store.recordWinner(gvUrl, 2048L)
        val seek = throughStack(playerSeekRequest(), store)
        assertEquals("bytes=5000-7047", seek.header("Range"))
        assertNull(seek.header("User-Agent"))
        assertNull(seek.header("Accept-Encoding"))
    }
}
