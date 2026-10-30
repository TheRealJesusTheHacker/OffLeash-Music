package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 36 sweep contract (fresh-eyes XXIX — the Liked Songs
 * screen's unlike action):
 *
 * Every track on the account's Liked Songs page is account-liked
 * by construction — the page IS the account's like list. But the
 * like controller learned an account like from exactly one road:
 * the player screen's per-track status fetch, for the one
 * currently playing track, only while the full player was
 * composed. Every other track on the page held no state entry,
 * so its overflow menu and heart fell back to `liked = false`:
 * the row offered "Like" (border heart) for a track the account
 * likes, and its first tap ran as a LIKE write — the unlike the
 * user intended needed a second tap, after the first one had
 * silently re-liked. The playing track was covered only if the
 * user opened the full player before unliking; from the Liked
 * page itself (mini-player showing) it lied the same way.
 *
 * Fix: LikeController.noteAccountLiked seeds state from the
 * page — filling ONLY tracks it holds no entry for, so a stale
 * page snapshot can never override a toggle's intent, a pending
 * write, or a refresh report — and PlaylistScreen calls it for
 * the fetched page whenever the page is the Liked Music page
 * (browseId VLLM) and the session is signed in. The controller
 * half is runtime-pinned in LikeControllerTest; the wiring is
 * Compose-bound, so its shape is pinned here (house style; CI
 * is the compile gate).
 */
class R36SweepContractTest {

    @Test
    fun likedSongsPageSeedsTheLikeController() {
        // Round 70: every assertion below runs on a CODE-ONLY
        // view (comments stripped, string contents blanked).
        // This pin used to read the RAW file — the shipped seed
        // call commented out inside its own `?.let { }` block
        // kept the exact call text for the contains check and
        // the bare token for the count, and ran GREEN across
        // the full suite with the seed deleted in effect.
        val detail = stripCommentsAndStrings(
            mainSource(
                "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
            ),
        )
        val playlistScreen = detail.substringAfter("fun PlaylistScreen(")
        assertTrue(
            "PlaylistScreen must seed the like controller from " +
                "the Liked Songs page it just fetched — without " +
                "the call, a page row's like state defaults to " +
                "unliked and its unlike tap re-likes first",
            playlistScreen.contains(
                "container.likeController.noteAccountLiked(it)",
            ),
        )
        assertTrue(
            "the seed must be gated on the page being the " +
                "account's Liked Music page — seeding from an " +
                "ordinary playlist would mark every track on it " +
                "liked",
            playlistScreen.contains(
                "browseId == LIKED_MUSIC_BROWSE_ID && sessionState.signedIn",
            ),
        )
        assertEquals(
            "exactly one seeding call site may exist in " +
                "DetailScreens — a second, ungated one is the " +
                "ordinary-playlist bug above. Round 69: the " +
                "count is on the bare method-name token, not " +
                "the `noteAccountLiked(` spelling — a second " +
                "seed spelled as a method reference " +
                "(`tracks.let(controller::noteAccountLiked)`) " +
                "kept the parenthesised count at one and ran " +
                "green across the full suite",
            1,
            occurrences(detail, "noteAccountLiked"),
        )
        // Round 71: the code-only view PRESERVES `${}` template
        // expressions as code — and the seed deleted while its
        // exact call text survived inside a NEVER-INVOKED lambda
        // carried by a string template in the same `?.let { }`
        // block (`val seed = "${{ ....(it) }}"`) satisfied the
        // contains check and the count above and ran GREEN
        // across the full suite: the string stringifies the
        // lambda and discards it, so nothing is ever seeded.
        // The count is therefore re-asserted on a TEMPLATE-
        // BLANKED view: the one seeding call must exist as
        // plain code, not as a template's payload.
        val plainDetail = stripCommentsStringsAndTemplates(
            mainSource(
                "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
            ),
        )
        assertEquals(
            "exactly one seeding call site may exist in " +
                "DetailScreens outside any string template — " +
                "a seed that exists only as a never-evaluated " +
                "template payload is a deleted seed",
            1,
            occurrences(plainDetail, "noteAccountLiked"),
        )
        val likeController = stripCommentsAndStrings(
            mainSource(
                "java/dev/cyberdogs/offleash/data/LikeController.kt",
            ),
        )
        assertTrue(
            "the controller must expose the seeding entry point",
            likeController.contains(
                "fun noteAccountLiked(tracks: List<Track>)",
            ),
        )
        assertTrue(
            "the seed must fill only tracks with NO existing " +
                "entry — an unconditional write would let a " +
                "stale page snapshot resurrect a track the user " +
                "unliked this session",
            likeController.contains(
                "if (_states.value[track.videoId] == null) {",
            ),
        )
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    // -- the CODE-ONLY view (round 70, the scanner of
    // data/R66SweepContractTest.kt): comments stripped AND
    // string/char contents blanked; `${}` template expressions
    // are code and are preserved verbatim. ------------

    private fun stripCommentsAndStrings(text: String): String {
        val out = StringBuilder()
        class Frame(val kind: Int, var templateDepth: Int = -1)
        val stack = mutableListOf(Frame(0))
        fun top() = stack[stack.size - 1]
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else ' '
            when (top().kind) {
                1 -> when {
                    c == '\\' && i + 1 < n -> {
                        out.append(' '); out.append(' '); i += 2
                    }
                    c == '"' -> {
                        stack.removeAt(stack.size - 1)
                        out.append(c); i++
                    }
                    c == '$' && next == '{' -> {
                        stack.add(Frame(0, 0))
                        out.append(c); out.append(next); i += 2
                    }
                    else -> {
                        out.append(if (c == '\n') '\n' else ' '); i++
                    }
                }
                2 -> when {
                    c == '"' && i + 2 < n &&
                        text[i + 1] == '"' && text[i + 2] == '"' -> {
                        stack.removeAt(stack.size - 1)
                        out.append("\"\"\""); i += 3
                    }
                    c == '$' && next == '{' -> {
                        stack.add(Frame(0, 0))
                        out.append(c); out.append(next); i += 2
                    }
                    else -> {
                        out.append(if (c == '\n') '\n' else ' '); i++
                    }
                }
                3 -> when {
                    c == '\\' && i + 1 < n -> {
                        out.append(' '); out.append(' '); i += 2
                    }
                    else -> {
                        if (c == '\'') {
                            stack.removeAt(stack.size - 1)
                            out.append(c)
                        } else {
                            out.append(' ')
                        }
                        i++
                    }
                }
                else -> when {
                    c == '/' && next == '/' -> {
                        out.append(' '); i += 2
                        while (i < n && text[i] != '\n') i++
                    }
                    c == '/' && next == '*' -> {
                        out.append(' '); i += 2
                        var depth = 1
                        while (i < n && depth > 0) {
                            if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                                depth++; i += 2
                            } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                                depth--; i += 2
                            } else {
                                if (text[i] == '\n') out.append('\n')
                                i++
                            }
                        }
                    }
                    c == '"' && i + 2 < n &&
                        text[i + 1] == '"' && text[i + 2] == '"' -> {
                        stack.add(Frame(2))
                        out.append("\"\"\""); i += 3
                    }
                    c == '"' -> {
                        stack.add(Frame(1))
                        out.append(c); i++
                    }
                    c == '\'' -> {
                        stack.add(Frame(3))
                        out.append(c); i++
                    }
                    c == '{' && top().templateDepth >= 0 -> {
                        top().templateDepth++
                        out.append(c); i++
                    }
                    c == '}' && top().templateDepth >= 0 -> {
                        if (top().templateDepth == 0) {
                            stack.removeAt(stack.size - 1)
                        } else {
                            top().templateDepth--
                        }
                        out.append(c); i++
                    }
                    else -> {
                        out.append(c); i++
                    }
                }
            }
        }
        return out.toString()
    }

    // -- the TEMPLATE-BLANKED view (round 71): the code-only
    // scanner with template `${}` frame contents blanked too
    // (structure still tracked, so the frame stack never
    // desyncs). A token that exists only as a template's
    // payload is not a call. -----------------------------

    private fun stripCommentsStringsAndTemplates(text: String): String {
        val out = StringBuilder()
        class Frame(val kind: Int, var templateDepth: Int = -1, val blank: Boolean = false)
        val stack = mutableListOf(Frame(0))
        fun top() = stack[stack.size - 1]
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else ' '
            when (top().kind) {
                1 -> when {
                    c == '\\' && i + 1 < n -> {
                        out.append(' '); out.append(' '); i += 2
                    }
                    c == '"' -> {
                        stack.removeAt(stack.size - 1)
                        out.append(c); i++
                    }
                    c == '$' && next == '{' -> {
                        stack.add(Frame(0, 0, blank = true))
                        out.append(c); out.append(next); i += 2
                    }
                    else -> {
                        out.append(if (c == '\n') '\n' else ' '); i++
                    }
                }
                2 -> when {
                    c == '"' && i + 2 < n &&
                        text[i + 1] == '"' && text[i + 2] == '"' -> {
                        stack.removeAt(stack.size - 1)
                        out.append("\"\"\""); i += 3
                    }
                    c == '$' && next == '{' -> {
                        stack.add(Frame(0, 0, blank = true))
                        out.append(c); out.append(next); i += 2
                    }
                    else -> {
                        out.append(if (c == '\n') '\n' else ' '); i++
                    }
                }
                3 -> when {
                    c == '\\' && i + 1 < n -> {
                        out.append(' '); out.append(' '); i += 2
                    }
                    else -> {
                        if (c == '\'') {
                            stack.removeAt(stack.size - 1)
                            out.append(c)
                        } else {
                            out.append(' ')
                        }
                        i++
                    }
                }
                else -> when {
                    c == '/' && next == '/' -> {
                        out.append(' '); i += 2
                        while (i < n && text[i] != '\n') i++
                    }
                    c == '/' && next == '*' -> {
                        out.append(' '); i += 2
                        var depth = 1
                        while (i < n && depth > 0) {
                            if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                                depth++; i += 2
                            } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                                depth--; i += 2
                            } else {
                                if (text[i] == '\n') out.append('\n')
                                i++
                            }
                        }
                    }
                    c == '"' && i + 2 < n &&
                        text[i + 1] == '"' && text[i + 2] == '"' -> {
                        stack.add(Frame(2))
                        out.append("\"\"\""); i += 3
                    }
                    c == '"' -> {
                        stack.add(Frame(1))
                        out.append(c); i++
                    }
                    c == '\'' -> {
                        stack.add(Frame(3))
                        out.append(c); i++
                    }
                    c == '{' && top().templateDepth >= 0 -> {
                        top().templateDepth++
                        out.append(c); i++
                    }
                    c == '}' && top().templateDepth >= 0 -> {
                        if (top().templateDepth == 0) {
                            stack.removeAt(stack.size - 1)
                        } else {
                            top().templateDepth--
                        }
                        out.append(c); i++
                    }
                    else -> {
                        out.append(
                            if (top().blank && c != '\n') ' ' else c,
                        ); i++
                    }
                }
            }
        }
        return out.toString()
    }

    /** Reads a file under app/src/main, located like the other contract tests. */
    private fun mainSource(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/$relativePath")
            candidates += File(dir, "app/src/main/$relativePath")
            dir = dir.parentFile
            hops++
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "could not locate $relativePath from ${File(".").absolutePath}",
            )
        return found.readText()
    }
}
