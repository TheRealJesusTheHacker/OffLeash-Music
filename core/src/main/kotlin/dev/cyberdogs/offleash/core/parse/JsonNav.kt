package dev.cyberdogs.offleash.core.parse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Small, total helpers for walking youtubei JSON trees. Every lookup is
 * nullable-safe: a missing or mistyped node yields null, never a crash,
 * and renderers we don't understand are skipped by the parsers above.
 */

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

fun JsonObject?.obj(key: String): JsonObject? = this?.get(key).asObj()

fun JsonObject?.arr(key: String): JsonArray? = this?.get(key).asArr()

fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

fun JsonObject?.int(key: String): Int? {
    val prim = this?.get(key) as? JsonPrimitive ?: return null
    return prim.intOrNull ?: prim.contentOrNull?.toIntOrNull()
}

fun JsonObject?.long(key: String): Long? {
    val prim = this?.get(key) as? JsonPrimitive ?: return null
    return prim.longOrNull ?: prim.contentOrNull?.toLongOrNull()
}

/** The runs of a text node, as objects (empty when the node has none). */
fun runsOf(node: JsonElement?): List<JsonObject> =
    node.asObj().arr("runs")?.mapNotNull { it.asObj() } ?: emptyList()

/**
 * Plain text of a youtubei text node: concatenated `runs`, a
 * `simpleText`, or a bare `content` string (used by newer view-model
 * shapes such as the playlist facepile).
 */
fun textOf(node: JsonElement?): String? {
    val obj = node.asObj() ?: return (node as? JsonPrimitive)?.contentOrNull
    obj.arr("runs")?.let { runs ->
        return runs.joinToString("") { run -> run.asObj().str("text") ?: "" }
    }
    obj.str("simpleText")?.let { return it }
    (obj["content"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    return null
}

/**
 * The deepest tree level the recursive walks in this file will
 * descend to. Real youtubei responses nest a few dozen levels; a
 * hostile or drifted body nested deeper than this is junk below the
 * cap, not data — the walk simply stops descending there instead of
 * risking the call stack (a ~1000-deep body already overflows the
 * JSON parser itself; these walks must stay well clear of the same
 * failure on trees that did parse). Bug sweep round 1 (2026-10-27).
 */
const val TREE_WALK_MAX_DEPTH: Int = 100

/** All values stored under [key], anywhere in the tree, in document order. */
fun findAll(node: JsonElement?, key: String, out: MutableList<JsonElement> = mutableListOf()): List<JsonElement> =
    findAll(node, key, out, depth = 0)

private fun findAll(
    node: JsonElement?,
    key: String,
    out: MutableList<JsonElement>,
    depth: Int,
): List<JsonElement> {
    if (depth > TREE_WALK_MAX_DEPTH) return out
    when (node) {
        is JsonObject -> {
            node[key]?.let { out.add(it) }
            for (value in node.values) findAll(value, key, out, depth + 1)
        }
        is JsonArray -> for (element in node) findAll(element, key, out, depth + 1)
        else -> Unit
    }
    return out
}

/**
 * The first value stored under [key] anywhere in the tree, if any.
 *
 * Document order is exactly [findAll]'s: an object's own [key] entry
 * wins over anything nested inside its values, and values/elements
 * are searched in iteration order. Unlike the old
 * `findAll(node, key).firstOrNull()` this search SHORT-CIRCUITS at
 * the first match — the old form walked the entire tree (and built
 * the full match list) on every call, which cost ~16 ms per call on
 * a key sitting near the top of the 409 KB home fixture (Fleet
 * Sweep #5 measurement, 2026-10-24). Absent keys still cost a full
 * walk — there is no honest way to prove absence sooner.
 */
fun findFirst(node: JsonElement?, key: String): JsonElement? = findFirst(node, key, depth = 0)

private fun findFirst(node: JsonElement?, key: String, depth: Int): JsonElement? {
    if (depth > TREE_WALK_MAX_DEPTH) return null
    return when (node) {
        is JsonObject -> {
            node[key]?.let { return it }
            var found: JsonElement? = null
            for (value in node.values) {
                found = findFirst(value, key, depth + 1)
                if (found != null) break
            }
            found
        }
        is JsonArray -> {
            var found: JsonElement? = null
            for (element in node) {
                found = findFirst(element, key, depth + 1)
                if (found != null) break
            }
            found
        }
        else -> null
    }
}

/** Largest thumbnail URL from a `thumbnails` array (by width; last wins ties). */
fun largestThumbnailUrl(thumbnails: JsonElement?): String? {
    val arr = thumbnails.asArr() ?: return null
    var bestUrl: String? = null
    var bestWidth = -1
    for (thumb in arr) {
        val obj = thumb.asObj() ?: continue
        val url = obj.str("url") ?: continue
        val width = obj.int("width") ?: 0
        if (width >= bestWidth) {
            bestWidth = width
            bestUrl = url
        }
    }
    return bestUrl
}

/**
 * The best thumbnail URL for a renderer node, following the thumbnail
 * shapes youtubei actually uses (musicThumbnailRenderer under `thumbnail`
 * or `thumbnailRenderer`, cropped-square variants, and the bare
 * `thumbnail.thumbnails` of panel renderers).
 */
fun thumbnailUrlOf(node: JsonObject?): String? {
    if (node == null) return null
    val candidates: List<JsonElement?> = listOf(
        node.obj("thumbnail").obj("musicThumbnailRenderer").obj("thumbnail").arr("thumbnails"),
        node.obj("thumbnail").obj("croppedSquareThumbnailRenderer").obj("thumbnail").arr("thumbnails"),
        node.obj("thumbnail").arr("thumbnails"),
        node.obj("thumbnailRenderer").obj("musicThumbnailRenderer").obj("thumbnail").arr("thumbnails"),
        node.obj("thumbnailRenderer").obj("croppedSquareThumbnailRenderer").obj("thumbnail").arr("thumbnails"),
        node.obj("straplineThumbnail").obj("musicThumbnailRenderer").obj("thumbnail").arr("thumbnails"),
        node.obj("foregroundThumbnail").obj("musicThumbnailRenderer").obj("thumbnail").arr("thumbnails"),
    )
    for (candidate in candidates) {
        largestThumbnailUrl(candidate)?.let { return it }
    }
    return null
}

/** "3:25" -> 205, "1:02:03" -> 3723; anything else -> null. */
fun parseDurationSeconds(text: String?): Int? {
    val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!trimmed.matches(Regex("""\d+(:\d{1,2}){1,2}"""))) return null
    // Fold in Long with an Int-range check per step (round 2): the
    // leading field is unbounded digits, so the old Int fold wrapped
    // silently — "40000000:00" read back as a NEGATIVE duration on
    // the track. An absurd duration is unknown, never negative.
    var total = 0L
    for (part in trimmed.split(":")) {
        val value = part.toLongOrNull() ?: return null
        if (value > Int.MAX_VALUE) return null
        total = total * 60 + value
        if (total > Int.MAX_VALUE) return null
    }
    return total.toInt()
}

fun isDurationText(text: String): Boolean = parseDurationSeconds(text) != null

/** A run of text between " • " separators in a byline, with its source runs. */
class TextSegment(val runs: List<JsonObject>) {
    val text: String
        get() = runs.joinToString("") { it.str("text") ?: "" }.trim()

    /** The first browse endpoint carried by any run in this segment. */
    fun browseEndpoint(): JsonObject? =
        runs.firstNotNullOfOrNull { run ->
            run.obj("navigationEndpoint").obj("browseEndpoint")
        }

    fun browseId(): String? = browseEndpoint().str("browseId")

    fun pageType(): String? = browseEndpoint()
        .obj("browseEndpointContextSupportedConfigs")
        .obj("browseEndpointContextMusicConfig")
        .str("pageType")
}

/** Split byline runs into segments on "•" separator runs. */
fun segmentsOf(runs: List<JsonObject>): List<TextSegment> {
    if (runs.isEmpty()) return emptyList()
    val segments = mutableListOf<TextSegment>()
    var current = mutableListOf<JsonObject>()
    fun flush() {
        if (current.isNotEmpty()) {
            segments.add(TextSegment(current.toList()))
            current = mutableListOf()
        }
    }
    for (run in runs) {
        val text = run.str("text") ?: ""
        if (text.trim() == "•") {
            flush()
        } else {
            current.add(run)
        }
    }
    flush()
    return segments.filter { it.text.isNotEmpty() }
}
