package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.core.model.Track

/**
 * Radio queue construction and share text (Lane G), pure so both are
 * unit-testable without Android.
 *
 * Radio evidence: the core client already carries the radio call —
 * `YoutubeiClient.related(videoId)` POSTs `next` on WEB_REMIX with
 * playlistId `RDAMVM<videoId>` and `isAudioOnly`, pinned to the
 * recorded `next.json` fixture (50 panels, seed excluded, 49 tracks).
 * No new core call was needed for this lane.
 */

/**
 * Builds the queue a "Start radio" tap plays: the seed track first,
 * then the radio tracks in the API's own order, deduped by videoId
 * (the seed wins; a duplicate inside the radio list keeps its first
 * occurrence). Starting radio REPLACES the current queue — that is
 * what [QueueController.startRadio] does with the result.
 *
 * [preferAudioVersion] is the `audio_version_in_radio` preference
 * ("play the audio version instead of the music video in radio"),
 * read by the controller and passed in. It is currently a DOCUMENTED
 * NO-OP: the core [Track] model carries no audio-vs-video distinction
 * — no `musicVideoType`, badge, or equivalent field is parsed anywhere
 * in `:core`, and the `next` radio response's panel renderers do not
 * expose one the parser could honestly read — so there is nothing to
 * reorder or filter by. The preference is read and threaded so the
 * moment the API evidence supports the distinction, the reorder lands
 * here, in this one function, instead of being faked today.
 */
fun buildRadioQueue(
    seed: Track,
    related: List<Track>,
    preferAudioVersion: Boolean,
): List<Track> {
    // See the KDoc: preferAudioVersion intentionally does not alter
    // the queue yet — the Track model cannot tell an audio version
    // from a music-video version, and pretending otherwise would be
    // a fake feature.
    val seen = mutableSetOf(seed.videoId)
    val queue = mutableListOf(seed)
    for (track in related) {
        if (seen.add(track.videoId)) {
            queue.add(track)
        }
    }
    return queue
}

/** The track's canonical YouTube Music URL — the share target. */
fun youtubeMusicTrackUrl(videoId: String): String =
    "https://music.youtube.com/watch?v=$videoId"

/** The plain-text share payload: title, artists when known, then the URL. */
fun shareTextFor(track: Track): String {
    val artists = track.artists.joinToString(", ")
    val headline = if (artists.isBlank()) track.title else "${track.title} — $artists"
    return "$headline\n${youtubeMusicTrackUrl(track.videoId)}"
}
