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
 *  0.9.0 NOTE: both subjects below were deleted with the
 *  sign-in product (owner order, 2026-11-04) — the worker
 *  itself and the container's dormancy gate — and both pins
 *  now assert their ABSENCE, in the 0.8.3 inverted form.
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
    fun workerDormancyGatesAreRemovedWithTheWorker() {
        // Retired 0.9.0 (owner order, 2026-11-04): the subject
        // of the original pin — AutoDownloadLikedWorker and its
        // two dormancy gates — is DELETED with the sign-in
        // product. Auto-download of liked songs now runs off
        // the on-device liked list from the Library screen; no
        // WorkManager worker remains in the app at all. As with
        // the app-start gate pin below (0.8.3 form), every form
        // of the pin inverts to ZERO: the worker file must not
        // exist and no shipped source may reference the worker
        // or its dormancy gates.
        var dir: File? = File(".").absoluteFile
        var workerFile: File? = null
        var hops = 0
        while (dir != null && hops < 8 && workerFile == null) {
            val candidate = File(
                dir,
                "app/src/main/java/dev/cyberdogs/offleash/account/AutoDownloadLikedWorker.kt",
            )
            val candidate2 = File(
                dir,
                "src/main/java/dev/cyberdogs/offleash/account/AutoDownloadLikedWorker.kt",
            )
            if (candidate.exists()) workerFile = candidate
            if (candidate2.exists()) workerFile = candidate2
            dir = dir.parentFile
            hops++
        }
        assertTrue(
            "the auto-download worker file must NOT exist — it " +
                "was deleted with the sign-in product (0.9.0)",
            workerFile == null,
        )
        val app = mainSource(
            "java/dev/cyberdogs/offleash/OffleashApp.kt",
        )
        assertEquals(
            "the container must hold no reference to the deleted worker",
            0,
            occurrences(app, "AutoDownloadLikedWorker"),
        )
        assertEquals(
            "the container must hold no dormancy-gate leg in any spelling",
            0,
            occurrences(app, "hasStoredSession"),
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
