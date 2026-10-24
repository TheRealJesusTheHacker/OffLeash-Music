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
 */
object StreamResolver {

    fun resolve(playerResponseJson: String): StreamInfo {
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
        val best = pool.sortedWith(
            compareByDescending<JsonObject> { it.int("bitrate") ?: 0 }
                .thenBy { format -> if (format.str("mimeType")?.startsWith("audio/mp4") == true) 0 else 1 }
        ).first()

        return StreamInfo(
            url = best.str("url")!!,
            mimeType = best.str("mimeType") ?: "",
            bitrate = best.int("bitrate") ?: 0,
            contentLength = best.long("contentLength"),
            approxDurationMs = best.long("approxDurationMs"),
        )
    }
}
