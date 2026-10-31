package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 66 sweep contracts (adversarial lane, charter 2):
 * the two session-guard sites the runtime census could
 * not reach — the auto-download worker's dormancy gates
 * and the container's app-start dormancy gate — pinned
 * VERBATIM, because round 65's occurrence-count form of
 * the worker pin proved hollow.
 *
 *  (F4) Round 9's worker pin counts
 *  `hasStoredSession()` / `seen()` occurrences over the
 *  whole `doWork` body (≥ 2 each). Neutering ONE site's
 *  leg semantically while keeping its literal —
 *  `false && container.googleSessionManager…` at the
 *  capture gate, `… seen() && false` on the seen leg —
 *  leaves both counts at 2 and ran GREEN: the legacy
 *  session would fetch (and, with the matching re-check
 *  neuter, enqueue) behind a standing Google session
 *  while every pin stayed green. The first test below
 *  pins both gates' condition lines exactly as shipped,
 *  at both sites: any neuter, deletion, or reordering of
 *  a leg breaks the literal. (Deleting the re-check legs
 *  outright already failed round 9's pin; this pin closes
 *  the keep-the-literal direction.)
 *
 *  (F5) The container's app-start validation gate
 *  (`currentAuthSession() != null && !hasStoredSession()
 *  && !seen()`) had no pin at all: dropping either
 *  dormancy leg ran GREEN across the full suite. The legs
 *  are not redundant — the seen-marker's mark() is
 *  best-effort by design, and in the window where a mark
 *  failed while a Google session stands stored, the
 *  hasStoredSession leg is the only thing keeping the
 *  dormant legacy session from being validated at start
 *  (the revalidate gate beside it is already pinned
 *  verbatim by round 59; this gate was missed). The
 *  second test pins the gate's condition verbatim.
 */
class R66SweepContractTest {

    @Test
    fun workerDormancyGatesArePinnedVerbatimAtBothSites() {
        val worker = mainSource(
            "java/dev/cyberdogs/offleash/account/AutoDownloadLikedWorker.kt",
        )
        // Round 67: the pins below run on the COMMENT-STRIPPED
        // body and count occurrences — a verbatim pin is
        // satisfied just as well by the leg's exact text parked
        // in a comment or a dead `if (false)` branch while the
        // live gate is neutered, and both respells ran GREEN
        // across the full 690-test suite against the round-66
        // form of this pin. Stripped of comments, a parked copy
        // vanishes; counted, a dead-branch copy is a second
        // occurrence. The live gate must be THE one occurrence.
        val body = stripComments(worker)
            .substringAfter("override suspend fun doWork()")
            .substringBefore("\nobject AutoDownloadLiked")
        val captureGate =
            "if (container.googleSessionManager.hasStoredSession() ||\n" +
                "                            container.googleSignInSeenMarker.seen()\n" +
                "                        ) {"
        assertTrue(
            "the worker's cookie-road capture must be dormancy-" +
                "gated by the stored-Google-session leg and the " +
                "seen-marker leg, verbatim — round 9's pin counts " +
                "occurrences over the whole body, so a leg " +
                "neutered in place (`false && …`, `… && false`) " +
                "kept its count and ran green",
            body.contains(captureGate),
        )
        assertTrue(
            "the capture gate must stand BEFORE the consistent " +
                "session/generation pair is read (the gate guards " +
                "the fetch itself, not only the enqueue)",
            body.indexOf(captureGate) <
                body.indexOf("container.sessionManager.currentSessionWithGeneration()"),
        )
        val recheckLegs =
            "container.sessionManager.sessionGeneration() == fetchSessionGeneration &&\n" +
                "                        !container.googleSessionManager.hasStoredSession() &&\n" +
                "                        !container.googleSignInSeenMarker.seen()"
        assertTrue(
            "the worker's enqueue re-check must re-run BOTH " +
                "dormancy legs beside the generation equality, " +
                "verbatim — a Google session adopted (or a " +
                "sign-in landed and wiped) mid-fetch retires the " +
                "legacy road the list was fetched under",
            body.contains(recheckLegs),
        )
        assertEquals(
            "the capture gate's verbatim text must occur exactly " +
                "once in the comment-stripped body — a second " +
                "occurrence is a parked copy (comment or dead " +
                "branch) covering a neutered live gate",
            1,
            occurrences(body, captureGate),
        )
        assertEquals(
            "the re-check legs' verbatim text must occur exactly " +
                "once in the comment-stripped body, for the same " +
                "parked-copy reason",
            1,
            occurrences(body, recheckLegs),
        )
        // A count of one is still not liveness: a dead copy is
        // one occurrence too, with the live gate neutered away.
        // So the gate must also stand in its live SPAN — the
        // regexes below run over the stripped body (stripped
        // comments are whitespace to \s) and anchor each gate
        // to the body it actually guards, which a parked copy
        // does not reproduce; and the stripped body must hold
        // no `if (false)` for a fuller copy to hide inside.
        val captureSpan = Regex(
            Regex.escape(captureGate) +
                "\\s+null\\s+\\}\\s+else\\s+\\{" +
                "\\s+val \\(session, generation\\) =\\s+" +
                Regex.escape(
                    "container.sessionManager.currentSessionWithGeneration()",
                ),
        )
        assertTrue(
            "the capture gate must stand in its live span: " +
                "gate, null branch, else, and the consistent " +
                "session/generation pair read it guards",
            captureSpan.containsMatchIn(body),
        )
        val recheckSpan = Regex(
            Regex.escape(
                "container.sessionManager.currentAuthSession() != null &&",
            ) +
                "\\s+" + Regex.escape(recheckLegs) +
                "\\s+else -> false",
        )
        assertTrue(
            "the re-check legs must stand in their live span: " +
                "the stillCurrent when-branch they form, closed " +
                "by the else -> false leg",
            recheckSpan.containsMatchIn(body),
        )
        assertTrue(
            "the worker body must hold no `if (false)` dead " +
                "branch for a parked gate copy to hide inside",
            !body.contains("if (false)"),
        )
        // Round 68: a span anchor proves the gate STANDS in its
        // live span, but not that its result is USED — assigning
        // the gated if/else to a discarded local and fetching
        // again unconditionally below it kept every round-67
        // assert green across the full suite. The gated fetch
        // must be the body's ONLY cookie-road fetch and ONLY
        // session/generation pair read, and the gated branch
        // must close straight into the page it produced.
        assertEquals(
            "the cookie-road fetch must occur exactly once in " +
                "the worker body — a second, ungated fetch below " +
                "a discarded gate result defeats the dormancy " +
                "gate while its text stands verbatim in its span",
            1,
            occurrences(body, "container.client.likedSongs("),
        )
        assertEquals(
            "the consistent session/generation pair must be " +
                "read exactly once in the worker body — the " +
                "gated read the capture span anchors; a second " +
                "read serves a second, ungated fetch",
            1,
            occurrences(
                body,
                "container.sessionManager.currentSessionWithGeneration()",
            ),
        )
        // Round 69: the two counts above are RECEIVER-bound —
        // they count one exact spelling, `container.client.…`
        // / `container.sessionManager.…`. An ungated second
        // fetch spelled through a local alias
        // (`val c = container.client; c.likedSongs(…)`), a
        // parenthesised receiver (`(container.client)
        // .likedSongs(…)`), or a method reference
        // (`container.client::likedSongs`) — and a second pair
        // read through a parenthesised receiver — kept both
        // counts at one and ran GREEN across the full suite.
        // The bare method-name tokens are receiver-blind: any
        // extra fetch or pair read, however spelled, adds one.
        // (`likedSongs` counts 2 on the shipped body: the
        // token road's `likedSongsOAuth(` and the cookie
        // road's one fetch.)
        assertEquals(
            "the worker body must hold exactly two likedSongs " +
                "method-name tokens (the OAuth road and the one " +
                "gated cookie-road fetch) — a second cookie " +
                "fetch spelled through an alias, a parenthesised " +
                "receiver, or a method reference defeats the " +
                "receiver-bound count above while the gate " +
                "stands verbatim",
            2,
            occurrences(body, "likedSongs"),
        )
        assertEquals(
            "the worker body must hold exactly one " +
                "currentSessionWithGeneration method-name token " +
                "— a second pair read through an alias or a " +
                "parenthesised receiver defeats the " +
                "receiver-bound count above",
            1,
            occurrences(body, "currentSessionWithGeneration"),
        )
        // Round 70: the counts above run on a COMMENT-stripped
        // body, but the stripper KEEPS string literals — so a
        // removed call whose text survives inside a string in
        // live code kept every count: the OAuth fetch deleted
        // and replaced by `val road = "likedSongsOAuth(bearer)"`
        // (branch value null) ran GREEN across the full suite.
        // The bare-token counts are therefore re-asserted on a
        // CODE-ONLY view (comments stripped AND string/char
        // contents blanked; template `${}` expressions are code
        // and stay): a token that exists only as string content
        // is not a call.
        val codeBody = stripCommentsAndStrings(worker)
            .substringAfter("override suspend fun doWork()")
            .substringBefore("\nobject AutoDownloadLiked")
        assertEquals(
            "the worker body must hold exactly two likedSongs " +
                "tokens in CODE (the OAuth road and the one gated " +
                "cookie-road fetch) — a fetch deleted while its " +
                "name survives inside a string literal is still " +
                "a deleted fetch",
            2,
            occurrences(codeBody, "likedSongs"),
        )
        assertEquals(
            "the worker body must hold exactly one " +
                "currentSessionWithGeneration token in CODE, " +
                "for the same string-mask reason",
            1,
            occurrences(codeBody, "currentSessionWithGeneration"),
        )
        assertEquals(
            "the gated cookie-road fetch must occur exactly " +
                "once in CODE, for the same string-mask reason",
            1,
            occurrences(codeBody, "container.client.likedSongs("),
        )
        // Round 71: the code-only view PRESERVES `${}` template
        // expressions as code — and a deleted call whose text
        // survives inside a NEVER-EVALUATED template expression
        // kept every round-70 count and ran GREEN across the
        // full suite, three times over: the OAuth fetch carried
        // by `"${if (marker.isNotEmpty()) ....}"` on an empty
        // marker, the same shape nested one template deep, and
        // the container's revalidate call carried the same way
        // (see the app-start pin below). A template payload the
        // string never evaluates is not a call the body makes,
        // so the counts are re-asserted on a TEMPLATE-BLANKED
        // view (comments stripped, literal contents blanked,
        // template expressions blanked too). The shipped body
        // holds no counted token inside a template, so the
        // numbers are the same — on the doctored bodies they
        // are not.
        val plainBody = stripCommentsStringsAndTemplates(worker)
            .substringAfter("override suspend fun doWork()")
            .substringBefore("\nobject AutoDownloadLiked")
        assertEquals(
            "the worker body must hold exactly two likedSongs " +
                "tokens outside any string template — a fetch " +
                "deleted while its text survives inside a " +
                "never-evaluated template expression is still " +
                "a deleted fetch",
            2,
            occurrences(plainBody, "likedSongs"),
        )
        assertEquals(
            "the worker body must hold exactly one " +
                "currentSessionWithGeneration token outside " +
                "any string template, for the same reason",
            1,
            occurrences(plainBody, "currentSessionWithGeneration"),
        )
        assertEquals(
            "the gated cookie-road fetch must occur exactly " +
                "once outside any string template, for the " +
                "same reason",
            1,
            occurrences(plainBody, "container.client.likedSongs("),
        )
        val captureTail = Regex(
            Regex.escape(
                "session?.let { container.client.likedSongs(it) }",
            ) +
                "\\s+\\}\\s+\\}\\s+\\}\\s+\\}\\s+" +
                Regex.escape("page ?: return Result.success()"),
        )
        assertTrue(
            "the gated fetch must close its branch straight " +
                "into the page check — the gated if/else is the " +
                "branch's value, not a discarded local followed " +
                "by an unconditional fetch",
            captureTail.containsMatchIn(body),
        )
    }

    @Test
    fun appStartValidationGateIsRemovedWithTheLegacyRoad() {
        // 0.8.3 re-base: this pin guarded the dormancy gate on
        // the app-start legacy validation for rounds 66-71 —
        // verbatim gate text, live-span adjacency, and exactly
        // two validateNow call sites (app-start + revalidate),
        // receiver-bound, string-masked, and template-masked.
        // The gate's whole purpose was rationing validation of
        // the WebView-era session, and 0.8.3 REMOVES that road
        // (owner's verdict, 2026-10-30): there is no legacy
        // session to validate — the session manager discards a
        // stored one at construction — so every form of the pin
        // inverts to ZERO. The multi-view counting stays: a
        // validateNow surviving only inside a comment, a string,
        // or a template is a road not fully removed.
        val app = stripComments(
            mainSource(
                "java/dev/cyberdogs/offleash/OffleashApp.kt",
            ),
        )
        val gate =
            "if (sessionManager.currentAuthSession() != null &&\n" +
                "            !googleSessionManager.hasStoredSession() &&\n" +
                "            !googleSignInSeenMarker.seen()\n" +
                "        ) {"
        assertEquals(
            "the app-start dormancy gate must be GONE with the " +
                "legacy road it rationed (0.8.3)",
            0,
            occurrences(app, gate),
        )
        assertEquals(
            "the container must hold no validateNow call sites — " +
                "legacy validation is removed, not gated (0.8.3)",
            0,
            occurrences(app, "sessionManager.validateNow()"),
        )
        assertEquals(
            "the container must hold no validateNow method-name " +
                "tokens in any spelling (0.8.3)",
            0,
            occurrences(app, "validateNow"),
        )
        val codeApp = stripCommentsAndStrings(
            mainSource(
                "java/dev/cyberdogs/offleash/OffleashApp.kt",
            ),
        )
        assertEquals(
            "no validateNow token may survive in CODE behind a " +
                "string mask (0.8.3)",
            0,
            occurrences(codeApp, "validateNow"),
        )
        val plainApp = stripCommentsStringsAndTemplates(
            mainSource(
                "java/dev/cyberdogs/offleash/OffleashApp.kt",
            ),
        )
        assertEquals(
            "no validateNow token may survive inside a string " +
                "template (0.8.3)",
            0,
            occurrences(plainApp, "validateNow"),
        )
        assertTrue(
            "the container must hold no `if (false)` dead branch",
            !app.contains("if (false)"),
        )
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + 1
        }
    }

    // -- the template-aware comment scanner (round 64's, verbatim) -----

    private fun stripComments(text: String): String {
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
                        out.append(text[i]); out.append(text[i + 1]); i += 2
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
                        out.append(c); i++
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
                        out.append(c); i++
                    }
                }
                3 -> when {
                    c == '\\' && i + 1 < n -> {
                        out.append(text[i]); out.append(text[i + 1]); i += 2
                    }
                    else -> {
                        out.append(c); i++
                        if (c == '\'') stack.removeAt(stack.size - 1)
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
                "$relativePath not found from " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
        return found.readText()
    }

    // -- the CODE-ONLY view (round 70) --------------------------------
    // The comment scanner above with one change: inside a string,
    // raw-string, or char literal the CONTENT is blanked to spaces
    // (delimiters and newlines kept, escapes blanked as a pair),
    // while a `${}` template expression is code and is preserved
    // verbatim — a call inside a template is a real call, and a
    // token that exists only as literal content is not.

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

    // -- the TEMPLATE-BLANKED view (round 71) --------------------------
    // The code-only scanner above with one change: a `${}` template
    // frame's contents are blanked too (structure still tracked —
    // nested strings, chars, comments and brace depth — so the
    // frame stack never desyncs; only the emitted text is spaces).
    // A token that exists only as a template's payload — inside a
    // never-true branch or a never-invoked lambda the string
    // stringifies and discards — is not a call.

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
}
