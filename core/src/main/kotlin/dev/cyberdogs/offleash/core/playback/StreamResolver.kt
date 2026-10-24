package dev.cyberdogs.offleash.core.playback

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
            throw ParseFailure("player response was not valid JSON (${e.message})")
        } ?: throw ParseFailure("player response was not a JSON object")

        val playability = root.obj("playabilityStatus")
        val status = playability.str("status")
        if (status != "OK") {
            val reason = playability.str("reason")
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
            throw PlayerRefused(reason)
        }

        val streamingData = root.obj("streamingData") ?: throw NoPlayableStream()
        val formats = mutableListOf<JsonObject>()
        for (key in listOf("formats", "adaptiveFormats")) {
            val arr = streamingData[key] as? kotlinx.serialization.json.JsonArray ?: continue
            for (element in arr) element.asObj()?.let { formats.add(it) }
        }
        val direct = formats.filter { it.str("url") != null }
        if (direct.isEmpty()) throw NoPlayableStream()

        val audioOnly = direct.filter { it.str("mimeType")?.startsWith("audio/") == true }
        val pool = if (audioOnly.isNotEmpty()) audioOnly else direct
        val best = selectFormat(pool, quality)

        return StreamInfo(
            url = best.str("url")!!,
            mimeType = best.str("mimeType") ?: "",
            bitrate = best.int("bitrate") ?: 0,
            contentLength = best.long("contentLength"),
            approxDurationMs = best.long("approxDurationMs"),
        )
    }

    private const val BALANCED_CAP_BPS = 160_000
    private const val LOW_CAP_BPS = 80_000

    /** HIGH's ordering, exactly as Phase 1 shipped: highest bitrate first, audio/mp4 wins ties. */
    private val BEST_FIRST: Comparator<JsonObject> =
        compareByDescending<JsonObject> { it.int("bitrate") ?: 0 }
            .thenBy { format ->
                if (format.str("mimeType")?.startsWith("audio/mp4") == true) 0 else 1
            }

    /** The mirror ordering for LOW's no-format-under-the-cap fallback. */
    private val LOWEST_FIRST: Comparator<JsonObject> =
        compareBy<JsonObject> { it.int("bitrate") ?: 0 }
            .thenBy { format ->
                if (format.str("mimeType")?.startsWith("audio/mp4") == true) 0 else 1
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
