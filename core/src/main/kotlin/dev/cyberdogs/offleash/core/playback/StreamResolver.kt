package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NoPlayableStream
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.error.PlayerRefused
import dev.cyberdogs.offleash.core.model.StreamInfo
import dev.cyberdogs.offleash.core.parse.asObj
import dev.cyberdogs.offleash.core.parse.int
import dev.cyberdogs.offleash.core.parse.long
import dev.cyberdogs.offleash.core.parse.obj
import dev.cyberdogs.offleash.core.parse.str
import dev.cyberdogs.offleash.core.parse.textOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Picks the playable stream out of an iOS-context player response
 * (SPEC §4). Playback URLs are used exactly as returned: there is no
 * cipher/signature deciphering anywhere in this codebase — a response
 * whose formats are all ciphered yields [NoPlayableStream], honestly.
 *
 * Which format wins is a [QualityPreference] (Wave 2). HIGH is the
 * Phase 1 rule and stays the default, so existing callers resolve
 * exactly as they always did; BALANCED and LOW only ever narrow the
 * pick inside the same candidate pool HIGH chooses from.
 */
object StreamResolver {

    fun resolve(
        playerResponseJson: String,
        quality: QualityPreference = QualityPreference.HIGH,
    ): StreamInfo {
        val root = try {
            Json.parseToJsonElement(playerResponseJson).asObj()
        } catch (e: Exception) {
            // The failure class only — NEVER the exception's message:
            // kotlinx decoding errors echo the raw body inside their
            // message, and a player body carries signed stream URLs
            // that must never reach diagnostics (bug sweep round 2;
            // round 1 fixed this exact leak in the browse-path parse
            // boundary but missed this one).
            throw ParseFailure(
                "player response was not valid JSON (${e.javaClass.simpleName})"
            )
        } catch (t: Throwable) {
            // The JSON parser recurses: a deeply nested hostile body
            // overflows its stack (an Error), and that must degrade
            // to the named ParseFailure, never crash the caller.
            throw ParseFailure(
                "player response could not be read (${t.javaClass.simpleName})"
            )
        } ?: throw ParseFailure("player response was not a JSON object")

        // A 200 carrying a top-level error payload is the refusal it
        // actually is, not a "playability unknown" mystery — the
        // browse path learned this in round 1 (parseJsonObject); the
        // player path bypasses that boundary (it parses raw), so the
        // check lives here.
        root.obj("error")?.let { error ->
            throw ContextRejected(error.str("message") ?: "unknown server error")
        }

        val playability = root.obj("playabilityStatus")
        val status = playability.str("status")
        if (status != "OK") {
            val rawReason = playability.str("reason")
                ?: textOf(
                    playability.obj("errorScreen")
                        .obj("playerErrorMessageRenderer")
                        .obj("reason")
                )
                ?: textOf(
                    playability.obj("errorScreen")
                        .obj("playerErrorMessageRenderer")
                        .obj("subreason")
                )
                ?: status
                ?: "unknown"
            // Keep the LOGIN_REQUIRED status token on the reason:
            // the server's human text for that status ("Sign in to
            // confirm you're not a bot") does not contain it, and
            // isAuthRefusal() — which drives the session
            // revalidation retry — keys on the token. Without this
            // the refusal was misread as a plain unplayable and a
            // dead session was never revalidated (round 2).
            val reason = if (
                status == "LOGIN_REQUIRED" &&
                !rawReason.contains("LOGIN_REQUIRED", ignoreCase = true)
            ) {
                "$rawReason (LOGIN_REQUIRED)"
            } else {
                rawReason
            }
            throw PlayerRefused(reason)
        }

        val streamingData = root.obj("streamingData") ?: throw NoPlayableStream()
        val formats = mutableListOf<JsonObject>()
        for (key in listOf("formats", "adaptiveFormats")) {
            val arr = streamingData[key] as? kotlinx.serialization.json.JsonArray ?: continue
            for (element in arr) element.asObj()?.let { formats.add(it) }
        }
        // A blank url is not a playable stream: it used to count as
        // direct and win selection on bitrate, handing the player a
        // StreamInfo whose url was "" — a fake success that failed
        // downstream as an unnamed media error (round 2).
        val direct = formats.filter { !it.str("url").isNullOrBlank() }
        if (direct.isEmpty()) throw NoPlayableStream()

        // MIME type tokens are case-insensitive (RFC 2045) and this
        // string arrives raw from the server with no normalization
        // upstream: a case-sensitive check lets an "AUDIO/MP4"
        // format fall out of the audio pool entirely, after which
        // HIGH picks a higher-bitrate VIDEO format (round 6).
        val audioOnly = direct.filter {
            it.str("mimeType")?.startsWith("audio/", ignoreCase = true) == true
        }
        val pool = if (audioOnly.isNotEmpty()) audioOnly else direct
        val best = selectFormat(pool, quality)

        return StreamInfo(
            url = best.str("url")!!,
            mimeType = best.str("mimeType") ?: "",
            // Hostile numbers are clamped to the honest floor: a
            // negative bitrate/length/duration is junk data, not a
            // fact about the stream (round 2).
            bitrate = (best.int("bitrate") ?: 0).coerceAtLeast(0),
            contentLength = best.long("contentLength")?.coerceAtLeast(0L),
            approxDurationMs = best.long("approxDurationMs")?.coerceAtLeast(0L),
        )
    }

    private const val BALANCED_CAP_BPS = 160_000
    private const val LOW_CAP_BPS = 80_000

    /** HIGH's ordering, exactly as Phase 1 shipped: highest bitrate first, audio/mp4 wins ties. */
    private val BEST_FIRST: Comparator<JsonObject> =
        compareByDescending<JsonObject> { it.int("bitrate") ?: 0 }
            .thenBy { format ->
                if (format.str("mimeType")?.startsWith("audio/mp4", ignoreCase = true) == true) 0 else 1
            }

    /** The mirror ordering for LOW's no-format-under-the-cap fallback. */
    private val LOWEST_FIRST: Comparator<JsonObject> =
        compareBy<JsonObject> { it.int("bitrate") ?: 0 }
            .thenBy { format ->
                if (format.str("mimeType")?.startsWith("audio/mp4", ignoreCase = true) == true) 0 else 1
            }

    /**
     * The format [quality] selects out of [pool] (never empty — the
     * caller throws [NoPlayableStream] before selecting).
     *
     * BALANCED takes the best format at or below [BALANCED_CAP_BPS]
     * and falls back to HIGH's pick when every format is above the cap.
     * LOW takes the best format at or below [LOW_CAP_BPS] and falls
     * back to the pool's LOWEST-bitrate format when none is under the
     * cap, so a data-saver pick never costs more than it has to.
     */
    private fun selectFormat(pool: List<JsonObject>, quality: QualityPreference): JsonObject =
        when (quality) {
            QualityPreference.HIGH -> pool.sortedWith(BEST_FIRST).first()
            QualityPreference.BALANCED -> {
                val capped = pool.filter { (it.int("bitrate") ?: 0) <= BALANCED_CAP_BPS }
                capped.ifEmpty { pool }.sortedWith(BEST_FIRST).first()
            }
            QualityPreference.LOW -> {
                val capped = pool.filter { (it.int("bitrate") ?: 0) <= LOW_CAP_BPS }
                if (capped.isNotEmpty()) {
                    capped.sortedWith(BEST_FIRST).first()
                } else {
                    pool.sortedWith(LOWEST_FIRST).first()
                }
            }
        }
}
