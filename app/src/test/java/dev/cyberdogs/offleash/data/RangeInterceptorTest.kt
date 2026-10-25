package dev.cyberdogs.offleash.data

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class RangeInterceptorTest {

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

    private fun run(request: Request): Request {
        val chain = FakeChain(request)
        RangeInterceptor().intercept(chain)
        return chain.proceeded!!
    }

    private fun runWithStore(request: Request, store: StreamWindowStore): Request {
        val chain = FakeChain(request)
        RangeInterceptor(store).intercept(chain)
        return chain.proceeded!!
    }

    @Test
    fun absentRangeBecomesBoundedFromZero() {
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback").get().build(),
        )
        assertEquals("bytes=0-1048575", out.header("Range"))
    }

    @Test
    fun openEndedRangeBecomesBoundedPreservingStart() {
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("Range", "bytes=2048-").get().build(),
        )
        assertEquals("bytes=2048-1050623", out.header("Range"))
        val fromZero = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("Range", "bytes=0-").get().build(),
        )
        assertEquals("bytes=0-1048575", fromZero.header("Range"))
    }

    @Test
    fun boundedRangeIsUnchanged() {
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("Range", "bytes=100-200").get().build(),
        )
        assertEquals("bytes=100-200", out.header("Range"))
    }

    @Test
    fun leavesPostsUntouched() {
        val body = "{}".toRequestBody(null)
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback").post(body).build(),
        )
        assertNull(out.header("Range"))
        // Even a POST that already carries an open-ended Range is untouched.
        val posted = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("Range", "bytes=0-").post(body).build(),
        )
        assertEquals("bytes=0-", posted.header("Range"))
    }

    @Test
    fun leavesNonGooglevideoHostsUntouched() {
        val out = run(
            Request.Builder().url("https://music.youtube.com/youtubei/v1/browse").get().build(),
        )
        assertNull(out.header("Range"))
        val ranged = run(
            Request.Builder().url("https://example.com/file")
                .header("Range", "bytes=0-").get().build(),
        )
        assertEquals("bytes=0-", ranged.header("Range"))
    }

    @Test
    fun googlevideoGetStripsAcceptEncoding() {
        // Media3's OkHttpDataSource adds "Accept-Encoding: identity"
        // to every stream fetch; the probe-proven shape carries no
        // Accept-Encoding at all (2026-10-24 hunt). The strip happens
        // here, scoped to googlevideo GETs exactly like the range rule.
        val out = run(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("Accept-Encoding", "identity").get().build(),
        )
        assertNull(out.header("Accept-Encoding"))
        assertEquals("bytes=0-1048575", out.header("Range"))
    }

    @Test
    fun nonGooglevideoRequestsKeepTheirAcceptEncoding() {
        val out = run(
            Request.Builder().url("https://music.youtube.com/youtubei/v1/browse")
                .header("Accept-Encoding", "identity").get().build(),
        )
        assertEquals("identity", out.header("Accept-Encoding"))
    }

    @Test
    fun shapeCallbackReportsTheFinalShape() {
        var capturedStatus: Int? = null
        var capturedHost: String? = null
        var capturedShape: StreamFetchShape? = null
        val chain = FakeChain(
            Request.Builder().url("https://rr1---sn-example.googlevideo.com/videoplayback")
                .header("User-Agent", "some-custom-agent/1.0")
                .header("Accept-Encoding", "identity")
                .get().build(),
        )
        RangeInterceptor { status, host, shape ->
            capturedStatus = status
            capturedHost = host
            capturedShape = shape
        }.intercept(chain)
        assertEquals(200, capturedStatus)
        assertEquals("rr1---sn-example.googlevideo.com", capturedHost)
        // The reported shape is the shape that LEFT the stack:
        // bounded range, the custom UA class, encoding stripped.
        assertEquals(
            StreamFetchShape(
                range = "bytes=0-1048575",
                userAgent = "custom",
                acceptEncoding = "absent",
            ),
            capturedShape,
        )
    }

    @Test
    fun describeShapeClasses() {
        assertEquals(
            StreamFetchShape("bytes=0-0", "okhttp-default", "absent"),
            RangeInterceptor.describeShape("bytes=0-0", null, null),
        )
        assertEquals(
            "okhttp-default",
            RangeInterceptor.describeShape(null, "okhttp/4.12.0", null).userAgent,
        )
        assertEquals(
            "custom",
            RangeInterceptor.describeShape(null, "com.google.ios.youtube/20.10.4", null).userAgent,
        )
        assertEquals(
            "identity",
            RangeInterceptor.describeShape(null, null, "identity").acceptEncoding,
        )
        assertEquals(
            "none",
            RangeInterceptor.describeShape(null, null, null).range,
        )
    }

    @Test
    fun normalizerRuleMatchesSpec() {
        assertEquals(1024L * 1024L, RangeInterceptor.STREAM_RANGE_WINDOW_BYTES)
        assertEquals(
            "bytes=0-1048575",
            RangeInterceptor.normalizedRangeHeader("GET", "x.googlevideo.com", null),
        )
        assertEquals(
            "bytes=5-1048580",
            RangeInterceptor.normalizedRangeHeader("GET", "x.googlevideo.com", "bytes=5-"),
        )
        assertNull(RangeInterceptor.normalizedRangeHeader("GET", "x.googlevideo.com", "bytes=5-9"))
        assertNull(RangeInterceptor.normalizedRangeHeader("POST", "x.googlevideo.com", null))
        assertNull(RangeInterceptor.normalizedRangeHeader("POST", "x.googlevideo.com", "bytes=5-"))
        assertNull(RangeInterceptor.normalizedRangeHeader("GET", "example.com", null))
        assertNull(RangeInterceptor.normalizedRangeHeader("GET", "example.com", "bytes=5-"))
    }

    // -- Round 3: the shared window ladder + per-stream store ---------------

    @Test
    fun windowLadderIsTheSharedDescent() {
        // The ONE shared definition the probe descends and the
        // interceptor shapes with: 1 MiB -> 256 KiB -> 64 KiB -> 2 KiB.
        assertEquals(
            listOf(1048576L, 262144L, 65536L, 2048L),
            RangeInterceptor.STREAM_WINDOW_LADDER,
        )
        assertEquals(
            RangeInterceptor.STREAM_RANGE_WINDOW_BYTES,
            RangeInterceptor.STREAM_WINDOW_LADDER.first(),
        )
        assertEquals(
            RangeInterceptor.STREAM_WINDOW_LADDER.sortedDescending(),
            RangeInterceptor.STREAM_WINDOW_LADDER,
        )
    }

    @Test
    fun normalizerUsesThePerStreamWindow() {
        assertEquals(
            "bytes=0-262143",
            RangeInterceptor.normalizedRangeHeader("GET", "x.googlevideo.com", null, 262144L),
        )
        assertEquals(
            "bytes=5000-70535",
            RangeInterceptor.normalizedRangeHeader("GET", "x.googlevideo.com", "bytes=5000-", 65536L),
        )
        // Already bounded stays untouched at any window.
        assertNull(
            RangeInterceptor.normalizedRangeHeader("GET", "x.googlevideo.com", "bytes=5-9", 2048L),
        )
        assertEquals("bytes=0-2047", RangeInterceptor.boundedRangeHeader(0L, 2048L))
    }

    @Test
    fun storeWindowShapesAbsentAndOpenEndedFetches() {
        val url = "https://rr1---sn-example.googlevideo.com/videoplayback?expire=1"
        val store = StreamWindowStore()
        store.recordWinner(url, 262144L)
        val absent = runWithStore(Request.Builder().url(url).get().build(), store)
        assertEquals("bytes=0-262143", absent.header("Range"))
        val openEnded = runWithStore(
            Request.Builder().url(url).header("Range", "bytes=4096-").get().build(),
            store,
        )
        assertEquals("bytes=4096-266239", openEnded.header("Range"))
    }

    @Test
    fun storeEntryForAnotherUrlDoesNotLeak() {
        val store = StreamWindowStore()
        store.recordWinner("https://rr1---sn-example.googlevideo.com/videoplayback?expire=1", 2048L)
        val out = runWithStore(
            Request.Builder()
                .url("https://rr1---sn-example.googlevideo.com/videoplayback?expire=2")
                .get().build(),
            store,
        )
        assertEquals("bytes=0-1048575", out.header("Range"))
    }

    @Test
    fun boundedFetchUntouchedEvenWithAStoreEntry() {
        val url = "https://rr1---sn-example.googlevideo.com/videoplayback?expire=1"
        val store = StreamWindowStore()
        store.recordWinner(url, 2048L)
        val out = runWithStore(
            Request.Builder().url(url).header("Range", "bytes=100-200").get().build(),
            store,
        )
        assertEquals("bytes=100-200", out.header("Range"))
    }
}
