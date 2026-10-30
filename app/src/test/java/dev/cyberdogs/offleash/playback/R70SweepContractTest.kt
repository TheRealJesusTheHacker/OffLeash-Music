package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 70 sweep contracts (adversarial lane, charter 2): the two
 * QueueController job-cancellation legs the whole cancel census
 * found unpinned — every other leg in the controller (19 legs
 * doctored one at a time) fails at least one named pin.
 *
 *  - resetAutoplayChain's `autoplayJob?.cancel()`: the function
 *    is called by every queue takeover (playTracks,
 *    playQueueItem), and its doc states the cancel as its first
 *    job. Dropping the leg ran GREEN across all 285 local
 *    playback-owner tests and is unpinned in the CI-only
 *    AutoplayWiringContractTest too (it pins that playTracks
 *    CALLS resetAutoplayChain, not what the reset does). The
 *    surviving fetch's landing is epoch-guarded, but the fetch
 *    itself — the thing the cancel exists to stop — was owned
 *    by no pin.
 *
 *  - resolveAndPlay's `resolveJob?.cancel()` before the
 *    relaunch: the superseded resolve's SUCCESS landing
 *    (`handToPlayer` after `resolveWithRoads`) carries no
 *    identity or epoch guard — the identity leg exists only in
 *    its CancellationException catch. The cancel at the
 *    successor's launch is therefore the only supersession
 *    stop on the success path, and dropping it ran GREEN
 *    across the same 285 tests: two resolves race, and the
 *    stale one hands its track to the player whenever it
 *    finishes last.
 *
 * QueueController is media3-bound (CI is its compile gate);
 * these pins are textual in the house style, slice-bound with
 * anchor-presence guards (the round-12 lesson).
 */
class R70SweepContractTest {

    @Test
    fun resetAutoplayChainCancelsTheInFlightContinuation() {
        val source = queueControllerSource()
        assertTrue(
            "resetAutoplayChain must exist at all (anchor guard)",
            source.contains("private fun resetAutoplayChain()"),
        )
        val body = source
            .substringAfter("private fun resetAutoplayChain()")
            .substringBefore("\n    fun playTracks(")
        assertTrue(
            "the reset must cancel the in-flight continuation fetch",
            body.contains("autoplayJob?.cancel()"),
        )
        assertTrue(
            "the cancel must land before the chain's bookkeeping " +
                "is cleared — a cancel after the clear would race " +
                "the job it forgets",
            body.indexOf("autoplayJob?.cancel()") <
                body.indexOf("autoplayTrackIds.clear()"),
        )
        // Round 71: presence + ordering is not liveness — the
        // cancel guarded by a named always-false condition
        // (`val cancelChain = false` /
        // `if (cancelChain) autoplayJob?.cancel()`) kept both
        // assertions above and ran GREEN across the full suite,
        // with the leg dead in place. The reset's whole body is
        // four statements; pin them as one exact block, and the
        // cancel token exactly once in the slice.
        assertTrue(
            "the reset body must stand as its exact shipped " +
                "block — a guarded, wrapped, or reordered cancel " +
                "is a dead leg wearing the token",
            body.contains(
                "        autoplayJob?.cancel()\n" +
                    "        autoplayJob = null\n" +
                    "        autoplayTrackIds.clear()\n" +
                    "        consecutiveAutoplayFailures = 0\n",
            ),
        )
        assertEquals(
            "the slice must hold exactly one autoplayJob?.cancel()",
            1,
            occurrences(body, "autoplayJob?.cancel()"),
        )
        // Round 72: the exact block above is asserted on the RAW
        // slice, and two respells kept it verbatim while killing
        // the leg, both GREEN across the full suite: the block
        // parked inside a block comment in the slice (the live
        // body kept the other three statements and lost the
        // cancel entirely), and a local `var autoplayJob`
        // shadowing the field (every block line then acts on
        // the local null; the field's job is never cancelled).
        // The block and its count are therefore re-asserted on
        // the CODE-ONLY view (comments stripped, string/char
        // contents blanked — a parked copy vanishes there), and
        // the slice must declare no local namesake of the field.
        val codeBody = stripCommentsAndStrings(source)
            .substringAfter("private fun resetAutoplayChain()")
            .substringBefore("\n    fun playTracks(")
        assertTrue(
            "the reset body must stand as its exact shipped " +
                "block in CODE — a copy parked in a comment or a " +
                "string is not the reset doing anything",
            codeBody.contains(
                "        autoplayJob?.cancel()\n" +
                    "        autoplayJob = null\n" +
                    "        autoplayTrackIds.clear()\n" +
                    "        consecutiveAutoplayFailures = 0\n",
            ),
        )
        assertEquals(
            "the code slice must hold exactly one autoplayJob?.cancel()",
            1,
            occurrences(codeBody, "autoplayJob?.cancel()"),
        )
        assertTrue(
            "the reset must not shadow the field it cancels — " +
                "a local val/var named autoplayJob makes the " +
                "exact block act on a null local while the real " +
                "continuation runs on",
            !body.contains("val autoplayJob") &&
                !body.contains("var autoplayJob"),
        )
    }

    @Test
    fun resolveAndPlayCancelsTheSupersededResolveBeforeRelaunching() {
        val source = queueControllerSource()
        assertTrue(
            "resolveAndPlay must exist at all (anchor guard)",
            source.contains("private fun resolveAndPlay("),
        )
        val body = source
            .substringAfter("private fun resolveAndPlay(")
            .substringBefore("\n    /**\n     * Hands one prepared item")
        assertTrue(
            "resolveAndPlay must cancel the superseded resolve — " +
                "its success landing is unguarded, so the cancel " +
                "is the only supersession stop",
            body.contains("resolveJob?.cancel()"),
        )
        assertTrue(
            "the cancel must stand before the relaunch it supersedes",
            body.indexOf("resolveJob?.cancel()") <
                body.indexOf("resolveJob = scope.launch"),
        )
        // Round 71: the same guard-off shape as on the reset
        // pin above — `val supersedeResolve = false` /
        // `if (supersedeResolve) resolveJob?.cancel()` kept the
        // presence and ordering assertions and ran GREEN across
        // the full suite, with the superseded resolve free to
        // land last. Pin the cancel ADJACENT to the relaunch,
        // as one exact text, and the token exactly once in the
        // slice (the shipped slice holds it once).
        assertTrue(
            "the cancel must stand directly before the relaunch " +
                "as one exact adjacency — a guarded cancel is a " +
                "dead leg wearing the token",
            body.contains(
                "        resolveJob?.cancel()\n" +
                    "        resolveJob = scope.launch {",
            ),
        )
        assertEquals(
            "the slice must hold exactly one resolveJob?.cancel()",
            1,
            occurrences(body, "resolveJob?.cancel()"),
        )
        // Round 72: the same two respells as on the reset pin
        // above, both GREEN across the full suite against the
        // round-71 form: the exact adjacency parked inside a
        // block comment in the slice while the live cancel was
        // deleted, and a local `var resolveJob` shadowing the
        // field (the adjacency then cancels and replaces a
        // null local; the superseded resolve — whose success
        // landing is unguarded — runs on). The adjacency and
        // its count are re-asserted on the CODE-ONLY view, and
        // the slice must declare no local namesake of the
        // field. (The code slice ends at handToPlayer's
        // declaration: the raw slice's doc-comment anchor does
        // not survive comment stripping.)
        val codeBody = stripCommentsAndStrings(source)
            .substringAfter("private fun resolveAndPlay(")
            .substringBefore("\n    private fun handToPlayer(")
        assertTrue(
            "the cancel must stand directly before the relaunch " +
                "as one exact adjacency in CODE — a copy parked " +
                "in a comment or a string supersedes nothing",
            codeBody.contains(
                "        resolveJob?.cancel()\n" +
                    "        resolveJob = scope.launch {",
            ),
        )
        assertEquals(
            "the code slice must hold exactly one resolveJob?.cancel()",
            1,
            occurrences(codeBody, "resolveJob?.cancel()"),
        )
        assertTrue(
            "resolveAndPlay must not shadow the field it " +
                "cancels — a local val/var named resolveJob " +
                "makes the exact adjacency act on a null local " +
                "while the superseded resolve runs on",
            !body.contains("val resolveJob") &&
                !body.contains("var resolveJob"),
        )
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    private fun queueControllerSource(): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(
                dir,
                "src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt",
            )
            candidates += File(
                dir,
                "app/src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt",
            )
            dir = dir.parentFile
            hops++
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "QueueController.kt not found from " + File(".").absolutePath,
            )
        return found.readText()
    }

    // -- the CODE-ONLY view (round 72, the scanner of
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
}
