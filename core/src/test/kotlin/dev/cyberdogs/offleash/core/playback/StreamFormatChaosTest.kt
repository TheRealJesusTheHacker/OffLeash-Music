package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NoPlayableStream
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.error.PlayerRefused
import dev.cyberdogs.offleash.core.error.isAuthRefusal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stream-format chaos (bug sweep round 2, charter 5) and the
 * playability matrix (charter 2): every documented non-OK
 * playability shape must map to its honest named outcome, and the
 * format pool must survive cipher-only, blank-url, zero/negative-
 * bitrate, duplicate-itag and mistyped-field variants without
 * inventing a stream or a number.
 */
class StreamFormatChaosTest {

    private fun format(
        url: JsonElement? = JsonPrimitive("https://example.com/a.m4a"),
        mime: String? = "audio/mp4",
        bitrate: JsonElement? = JsonPrimitive(128000),
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = buildJsonObject {
        url?.let { put("url", it) }
        mime?.let { put("mimeType", JsonPrimitive(it)) }
        bitrate?.let { put("bitrate", it) }
        for ((k, v) in extra) put(k, v)
    }

    private fun playerBody(
        playability: JsonObject? = buildJsonObject { put("status", "OK") },
        formats: JsonElement? = null,
        adaptive: JsonElement? = null,
    ): String = buildJsonObject {
        playability?.let { put("playabilityStatus", it) }
        if (formats != null || adaptive != null) {
            put("streamingData", buildJsonObject {
                formats?.let { put("formats", it) }
                adaptive?.let { put("adaptiveFormats", it) }
            })
        }
    }.toString()

    private fun refused(body: String): PlayerRefused {
        try {
            StreamResolver.resolve(body)
            fail("expected PlayerRefused")
            throw AssertionError("unreachable")
        } catch (e: PlayerRefused) {
            return e
        }
    }

    // ---------------------------------------------------------------
    // Playability matrix
    // ---------------------------------------------------------------

    @Test
    fun loginRequiredKeepsItsStatusTokenUnderHumanText() {
        // The server's human reason for LOGIN_REQUIRED does not
        // contain the token, and isAuthRefusal() — which drives the
        // one-shot session revalidation in the player — keys on it.
        // Before round 2 this refusal read as a plain unplayable.
        val e = refused(
            playerBody(
                playability = buildJsonObject {
                    put("status", "LOGIN_REQUIRED")
                    put("reason", "Sign in to confirm you're not a bot")
                },
            )
        )
        assertTrue(e.reason.contains("Sign in to confirm"))
        assertTrue(e.reason.contains("LOGIN_REQUIRED"))
        assertTrue(e.isAuthRefusal())
    }

    @Test
    fun ageGateShapeIsAnAuthRefusal() {
        val e = refused(
            playerBody(
                playability = buildJsonObject {
                    put("status", "LOGIN_REQUIRED")
                    put(
                        "errorScreen",
                        buildJsonObject {
                            put(
                                "playerErrorMessageRenderer",
                                buildJsonObject {
                                    put(
                                        "reason",
                                        buildJsonObject {
                                            put("simpleText", "Sign in to confirm your age")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        )
        assertTrue(e.reason.contains("confirm your age"))
        assertTrue(e.isAuthRefusal())
    }

    @Test
    fun unplayableKeepsItsExactServerReason() {
        val e = refused(
            playerBody(
                playability = buildJsonObject {
                    put("status", "UNPLAYABLE")
                    put("reason", "Video unavailable")
                },
            )
        )
        assertEquals("Video unavailable", e.reason)
        assertFalse(e.isAuthRefusal())
    }

    @Test
    fun liveStreamOfflineNamesItsReason() {
        val e = refused(
            playerBody(
                playability = buildJsonObject {
                    put("status", "LIVE_STREAM_OFFLINE")
                    put("reason", "This live stream recording is not available yet.")
                },
            )
        )
        assertEquals("This live stream recording is not available yet.", e.reason)
        assertFalse(e.isAuthRefusal())
    }

    @Test
    fun errorScreenSubreasonIsTheFallbackText() {
        val e = refused(
            playerBody(
                playability = buildJsonObject {
                    put("status", "ERROR")
                    put(
                        "errorScreen",
                        buildJsonObject {
                            put(
                                "playerErrorMessageRenderer",
                                buildJsonObject {
                                    put(
                                        "subreason",
                                        buildJsonObject {
                                            put("simpleText", "Playback error detail")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        )
        assertEquals("Playback error detail", e.reason)
    }

    @Test
    fun missingOrMistypedPlayabilityIsARefusalNeverAPlay() {
        // No playabilityStatus at all, one carrying no status, and
        // one mistyped as a string: all refuse as "unknown" rather
        // than falling through to the formats — an unvetted body is
        // not permission to play.
        assertEquals("unknown", refused(playerBody(playability = null)).reason)
        assertEquals(
            "unknown",
            refused(playerBody(playability = buildJsonObject { })).reason,
        )
        val mistyped = buildJsonObject {
            put("playabilityStatus", "junk")
            put("streamingData", buildJsonObject {
                put("adaptiveFormats", buildJsonArray { add(format()) })
            })
        }.toString()
        assertEquals("unknown", refused(mistyped).reason)
    }

    // ---------------------------------------------------------------
    // Format chaos
    // ---------------------------------------------------------------

    @Test
    fun cipherOnlyFormatsAreNoPlayableStream() {
        val body = playerBody(
            adaptive = JsonArray(
                listOf(
                    format(url = null, extra = mapOf("signatureCipher" to JsonPrimitive("s=cipher"))),
                    format(url = null, extra = mapOf("cipher" to JsonPrimitive("s=cipher"))),
                )
            ),
        )
        try {
            StreamResolver.resolve(body)
            fail("expected NoPlayableStream")
        } catch (e: NoPlayableStream) {
            Unit
        }
    }

    @Test
    fun blankUrlFormatsDoNotWinSelection() {
        // Before round 2 a "" url counted as a direct format and won
        // on bitrate: the player was handed url="" as a "success".
        val body = playerBody(
            adaptive = JsonArray(
                listOf(
                    format(url = JsonPrimitive(""), bitrate = JsonPrimitive(999999)),
                    format(url = JsonPrimitive("   "), bitrate = JsonPrimitive(888888)),
                    format(
                        url = JsonPrimitive("https://example.com/real.m4a"),
                        bitrate = JsonPrimitive(50000),
                    ),
                )
            ),
        )
        val info = StreamResolver.resolve(body)
        assertEquals("https://example.com/real.m4a", info.url)
        assertEquals(50000, info.bitrate)

        val onlyBlank = playerBody(
            adaptive = JsonArray(listOf(format(url = JsonPrimitive("")))),
        )
        try {
            StreamResolver.resolve(onlyBlank)
            fail("expected NoPlayableStream for a blank-only pool")
        } catch (e: NoPlayableStream) {
            Unit
        }
    }

    @Test
    fun zeroAndNegativeNumbersAreClampedToTheHonestFloor() {
        val body = playerBody(
            adaptive = JsonArray(
                listOf(
                    format(
                        bitrate = JsonPrimitive(-5000),
                        extra = mapOf(
                            "contentLength" to JsonPrimitive("-123"),
                            "approxDurationMs" to JsonPrimitive(-9),
                        ),
                    )
                )
            ),
        )
        val info = StreamResolver.resolve(body)
        assertEquals(0, info.bitrate)
        assertEquals(0L, info.contentLength)
        assertEquals(0L, info.approxDurationMs)

        val zero = playerBody(
            adaptive = JsonArray(listOf(format(bitrate = JsonPrimitive(0)))),
        )
        assertEquals(0, StreamResolver.resolve(zero).bitrate)
    }

    @Test
    fun duplicateItagsResolveToTheBetterDuplicate() {
        val body = playerBody(
            adaptive = JsonArray(
                listOf(
                    format(
                        url = JsonPrimitive("https://example.com/low.m4a"),
                        bitrate = JsonPrimitive(100000),
                        extra = mapOf("itag" to JsonPrimitive(140)),
                    ),
                    format(
                        url = JsonPrimitive("https://example.com/high.m4a"),
                        bitrate = JsonPrimitive(130000),
                        extra = mapOf("itag" to JsonPrimitive(140)),
                    ),
                )
            ),
        )
        val info = StreamResolver.resolve(body, QualityPreference.HIGH)
        assertEquals("https://example.com/high.m4a", info.url)
        val low = StreamResolver.resolve(body, QualityPreference.LOW)
        assertEquals("https://example.com/low.m4a", low.url)
    }

    @Test
    fun audioPoolBeatsMuxedVideoAndMuxedOnlyStillPlays() {
        val muxed = format(
            url = JsonPrimitive("https://example.com/muxed.mp4"),
            mime = "video/mp4",
            bitrate = JsonPrimitive(5000000),
        )
        val audio = format(
            url = JsonPrimitive("https://example.com/audio.m4a"),
            bitrate = JsonPrimitive(100000),
        )
        val mixed = playerBody(adaptive = JsonArray(listOf(muxed, audio)))
        assertEquals("https://example.com/audio.m4a", StreamResolver.resolve(mixed).url)
        val onlyMuxed = playerBody(adaptive = JsonArray(listOf(muxed)))
        assertEquals("https://example.com/muxed.mp4", StreamResolver.resolve(onlyMuxed).url)
    }

    @Test
    fun mistypedStreamingShapesDegradeHonestly() {
        // formats as an object, adaptiveFormats as a string, entries
        // that are not objects at all.
        val junkEntries = playerBody(
            adaptive = JsonArray(
                listOf(JsonNull, JsonPrimitive(42), JsonPrimitive("x"), format()),
            ),
        )
        assertEquals(
            "https://example.com/a.m4a",
            StreamResolver.resolve(junkEntries).url,
        )
        val objectFormats = playerBody(
            formats = buildJsonObject { put("url", "https://example.com/x") },
        )
        try {
            StreamResolver.resolve(objectFormats)
            fail("expected NoPlayableStream")
        } catch (e: NoPlayableStream) {
            Unit
        }
        val noStreamingData = playerBody()
        try {
            StreamResolver.resolve(noStreamingData)
            fail("expected NoPlayableStream")
        } catch (e: NoPlayableStream) {
            Unit
        }
    }

    @Test
    fun stringNumbersCoerceAndHugeNumbersDoNotCrash() {
        // The wire sends contentLength/duration as strings; a string
        // bitrate coerces by the JsonNav rule. A JSON bitrate beyond
        // Int range reads as unknown (0), never a wrapped negative.
        val body = playerBody(
            adaptive = JsonArray(
                listOf(
                    format(
                        bitrate = JsonPrimitive("128000"),
                        extra = mapOf(
                            "contentLength" to JsonPrimitive("3400000"),
                            "approxDurationMs" to JsonPrimitive("213000"),
                        ),
                    )
                )
            ),
        )
        val info = StreamResolver.resolve(body)
        assertEquals(128000, info.bitrate)
        assertEquals(3400000L, info.contentLength)
        assertEquals(213000L, info.approxDurationMs)

        val huge = playerBody(
            adaptive = JsonArray(listOf(format(bitrate = JsonPrimitive(3000000000L)))),
        )
        val hugeInfo = StreamResolver.resolve(huge)
        assertEquals(0, hugeInfo.bitrate)
        assertTrue(hugeInfo.bitrate >= 0)
    }

    @Test
    fun malformedPlayerJsonLeaksNothingAndNamesTheClass() {
        // Round 2's headline leak: this ParseFailure interpolated
        // e.message, and kotlinx decoding errors echo the raw body —
        // a player body is where signed URLs live.
        val body = "{\"playabilityStatus\": MARKERSECRET https://x.googlevideo.com/?sig=MARKERSIG"
        try {
            StreamResolver.resolve(body)
            fail("expected ParseFailure")
        } catch (e: ParseFailure) {
            assertFalse(e.message!!.contains("MARKERSECRET"))
            assertFalse(e.message!!.contains("MARKERSIG"))
            assertFalse(e.message!!.contains("googlevideo"))
            assertTrue(e.message!!.contains("player response was not valid JSON"))
        }
    }

    @Test
    fun mixedCaseMimeTypesStillFormTheAudioPool() {
        // Round 6 (area D): MIME type tokens are case-insensitive
        // (RFC 2045), and this one arrives raw from the server's
        // JSON with no normalization anywhere upstream. Before the
        // fix the audio-pool filter compared case-sensitively, so
        // an "AUDIO/MP4" format fell out of the pool, the pool
        // became every direct format, and HIGH picked the
        // higher-bitrate VIDEO format for an audio player.
        val audio = format(
            url = JsonPrimitive("https://example.com/audio-only"),
            mime = "AUDIO/MP4; codecs=\"mp4a.40.2\"",
            bitrate = JsonPrimitive(128000),
        )
        val video = format(
            url = JsonPrimitive("https://example.com/video"),
            mime = "video/mp4",
            bitrate = JsonPrimitive(500000),
        )
        val body = playerBody(
            adaptive = buildJsonArray { add(video); add(audio) },
        )
        val info = StreamResolver.resolve(body)
        assertEquals("https://example.com/audio-only", info.url)
    }

    @Test
    fun mixedCaseMp4StillWinsTheBitrateTieBreak() {
        // Round 6 (area D): the same case hole in the comparator's
        // audio/mp4 tie-break — with equal bitrates a mixed-case
        // MP4 format must still beat the webm format listed first.
        val webm = format(
            url = JsonPrimitive("https://example.com/webm"),
            mime = "audio/webm",
            bitrate = JsonPrimitive(128000),
        )
        val mp4 = format(
            url = JsonPrimitive("https://example.com/mp4"),
            mime = "Audio/Mp4",
            bitrate = JsonPrimitive(128000),
        )
        val body = playerBody(
            adaptive = buildJsonArray { add(webm); add(mp4) },
        )
        val info = StreamResolver.resolve(body)
        assertEquals("https://example.com/mp4", info.url)
    }

    @Test
    fun errorPayloadPlayerBodyIsContextRejectedNotPlayerRefused() {
        val body = """{"error": {"code": 400, "message": "Precondition check failed."}}"""
        try {
            StreamResolver.resolve(body)
            fail("expected ContextRejected")
        } catch (e: ContextRejected) {
            assertEquals("Precondition check failed.", e.serverMessage)
        }
    }

    @Test
    fun deeplyNestedPlayerBodyNeverEscapesAsAnError() {
        val sb = StringBuilder()
        repeat(5000) { sb.append("{\"a\":") }
        sb.append("{}")
        repeat(5000) { sb.append("}") }
        var outcome: Throwable? = null
        val thread = Thread(
            null,
            {
                try {
                    StreamResolver.resolve(sb.toString())
                } catch (t: Throwable) {
                    outcome = t
                }
            },
            "deep-player-parse",
            256L * 1024L,
        )
        thread.start()
        thread.join(30_000)
        assertFalse("the parse thread must have finished", thread.isAlive)
        assertTrue(
            "only a named OffleashException may escape, was $outcome",
            outcome is OffleashException,
        )
        assertFalse(outcome!!.message!!.contains("{\"a\""))
    }
}
