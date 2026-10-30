package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 66 sweep contracts (adversarial lane — breaking the
 * round-65 fixes):
 *
 *  (F1) Round 65's declaration-parser getter census widened
 *  the census exactly one step again: a depth-tracking
 *  colon/dot splitter over a SINGLE-LINE regex head
 *  (`[^\n;{}=]+?` up to `get()`). Six valid spellings stand
 *  outside that head, each toy-verified as compilable Kotlin
 *  before it was planted:
 *   - a plain getter whose TYPE is split across lines
 *     (`val doctorSplitType:` newline `Int` newline
 *     `get() = …`) — the head cannot cross the newline
 *     (rounds 63's and 64's censuses catch this one; it
 *     is pinned here so THIS census's inventory is true
 *     of its own parse, as with the delegated spelling
 *     below);
 *   - a getter carrying its annotation on its OWN line
 *     between the declaration and `get()` — the head ends
 *     at the newline and the next token is `@`, not `get`;
 *   - a getter whose annotation sits between the type and
 *     `get()` on one line with a NAMED argument
 *     (`@DoctorAnn(v = "x")`) — the head cannot cross the
 *     argument's `=`;
 *   - a getter on a BACKTICK name containing a space
 *     (`` val `doctor fresh`: Int get() = … ``) — round 65's
 *     parseDecl rejects the name against its identifier
 *     alphabet and drops the declaration silently;
 *   - an extension getter whose RECEIVER is split across
 *     lines (`val DownloadRecord` newline
 *     `.doctorRecvSplit: …`) — the head cannot cross the
 *     newline (this one ran green across the full 677-test
 *     suite: round 64's and round 51's censuses miss it too);
 *   - a DELEGATED extension property whose in-file delegate
 *     never names `kotlin.reflect.KProperty` (its
 *     `getValue` second parameter is the supertype `Any`) —
 *     round 65's all-declarations parse only terminates a
 *     head at `=` or `get(`, never at `by`, so the delegated
 *     declaration is invisible to its extension zero census
 *     (this one is caught by round 64's and round 51's
 *     censuses in the shipped suite; it is pinned here so
 *     THIS census's own zero claim is true).
 *  The census below re-runs round 65's inventory with a
 *  declaration scanner instead of a line regex: a head runs
 *  from `val`/`var` to its depth-0 terminator (`get(`, `=`,
 *  `by`, `;`, or a following declaration/block boundary)
 *  across newlines, annotations are stripped wherever they
 *  sit in the head, backtick names are names, and delegated
 *  declarations are declarations. The shipped 11-getter
 *  inventory and the extension zero are asserted under the
 *  new parse, and a canary test pins the scanner's
 *  classification of every escaped spelling above plus
 *  round 65's six.
 *
 *  Dismissals recorded in the lane report: an annotation
 *  between `val` and the name, a `const` getter, and a
 *  `lateinit` getter are all invalid Kotlin (kotlinc
 *  proofs — the CI compile gate owns those shapes);
 *  object/companion-scope getters, two declarations on one
 *  line separated by a semicolon, and a colon inside a
 *  trailing comment are all counted by round 65's census
 *  (doctor runs fail it by name).
 */
class R66SweepContractTest {

    // -- F1: the getter census by multiline declaration scan ------------

    /** One scanned property declaration. */
    private data class Decl(
        val name: String,
        val type: String?,
        val receiver: String?,
        val hasGetter: Boolean,
        val delegated: Boolean,
    )

    /** Removes one balanced annotation starting at [start] (the `@`). */
    private fun skipAnnotation(text: String, start: Int): Int {
        var i = start + 1
        // Use-site target (`@get:`, `@field:` ...) and the name.
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.' || text[i] == ':')) i++
        // Optional argument list, balanced, string-aware enough
        // for the shapes annotations actually carry here.
        var j = i
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        if (j < text.length && text[j] == '(') {
            var depth = 0
            var k = j
            while (k < text.length) {
                when (text[k]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return k + 1
                    }
                    '"' -> {
                        k++
                        while (k < text.length && text[k] != '"') {
                            if (text[k] == '\\') k++
                            k++
                        }
                    }
                }
                k++
            }
            return k
        }
        return i
    }

    /** Strips every annotation token from a declaration head fragment. */
    private fun stripAnnotations(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '@' && i + 1 < text.length &&
                (text[i + 1].isLetter() || text[i + 1] == '_')
            ) {
                i = skipAnnotation(text, i)
                out.append(' ')
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /**
     * Splits a (terminator-free) declaration head at its
     * top-level colon, then at the name part's last top-level
     * dot. Annotations are stripped first, so a colon or dot
     * inside an annotation argument can never be mistaken
     * for the declaration's own.
     */
    private fun parseHead(head: String, hasGetter: Boolean, delegated: Boolean): Decl? {
        val cleaned = stripAnnotations(head).trim()
        if (cleaned.isEmpty() || cleaned.startsWith("(")) return null
        var depth = 0
        var colon = -1
        for (i in cleaned.indices) {
            when (cleaned[i]) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> depth--
                ':' -> if (depth == 0) {
                    colon = i
                    break
                }
            }
        }
        val namePart = if (colon >= 0) cleaned.substring(0, colon) else cleaned
        val typePart = if (colon >= 0) {
            cleaned.substring(colon + 1).trim()
                .replace(Regex("""\s+"""), " ")
                .ifEmpty { null }
        } else {
            null
        }
        depth = 0
        var dot = -1
        for (i in namePart.indices) {
            when (namePart[i]) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> depth--
                '.' -> if (depth == 0) dot = i
            }
        }
        val receiver = if (dot >= 0) {
            namePart.substring(0, dot).trim()
                .replace(Regex("""\s+"""), " ")
                .ifEmpty { null }
        } else {
            null
        }
        val rawName = if (dot >= 0) {
            namePart.substring(dot + 1).trim()
        } else {
            namePart.trim()
        }
        // The name is the LAST token of the name part: a
        // backtick name (spaces and all) or a plain identifier.
        val name = if (rawName.startsWith("`")) {
            val end = rawName.indexOf('`', 1)
            if (end < 0) return null
            rawName.substring(1, end)
        } else {
            rawName.split(Regex("""\s+""")).lastOrNull() ?: return null
        }
        if (name.isEmpty()) return null
        if (!rawName.startsWith("`") &&
            !name.matches(Regex("""[A-Za-z_][A-Za-z0-9_]*"""))
        ) {
            return null
        }
        return Decl(name, typePart, receiver, hasGetter, delegated)
    }

    /**
     * Every `val`/`var` declaration in [text]: the head runs
     * from the keyword to its depth-0 terminator — `get(`,
     * `set(`, `=`, a `by` delegate, or `;` — across newlines,
     * so a split type, a split receiver, or an annotation
     * line in between cannot hide it. A candidate DIES
     * (it was a constructor parameter, a destructuring, or
     * a keyword inside a larger construct) when its head
     * meets a block brace at depth 0, a blank line, or more
     * than eight lines: a real declaration head never does
     * any of those, and dying keeps the scan from swallowing
     * the declarations that follow inside the enclosing
     * block. A head with a colon that runs into the NEXT
     * `val`/`var` keyword is a no-initializer declaration
     * and ends there. (The input is the comment-stripped
     * view, so no terminator can hide inside a comment;
     * terminators inside annotation parentheses sit at
     * depth > 0 by construction.)
     */
    /**
     * Skips one string or char literal starting at [start]
     * (round 67): the head scan's depth and terminator
     * checks must not see literal CONTENTS — an annotation
     * argument of "(" raises the scan's depth so the real
     * `get(` never terminates the head, and one of ") = x"
     * drops the depth early and terminates the head at the
     * `=` inside the string; both spellings are valid,
     * executable Kotlin and both ran green across the full
     * 690-test suite against the round-66 scanner. Handles
     * escapes, char literals, raw strings, and `${}`
     * template nesting (a template expression is code
     * again, skipped with the same literal awareness).
     * Returns the index just past the literal and the
     * number of newlines it spanned.
     */
    private fun skipLiteral(text: String, start: Int): Pair<Int, Int> {
        var newlines = 0
        if (text.startsWith("\"\"\"", start)) {
            var i = start + 3
            while (i < text.length) {
                if (text[i] == '\n') newlines++
                if (text.startsWith("\"\"\"", i)) return (i + 3) to newlines
                if (text[i] == '$' && i + 1 < text.length && text[i + 1] == '{') {
                    var depth = 0
                    var j = i + 1
                    while (j < text.length) {
                        val cj = text[j]
                        if (cj == '\n') newlines++
                        when {
                            cj == '{' -> depth++
                            cj == '}' -> {
                                depth--
                                if (depth == 0) break
                            }
                            cj == '"' || cj == '\'' -> {
                                val (e2, n2) = skipLiteral(text, j)
                                newlines += n2
                                j = e2
                                continue
                            }
                        }
                        j++
                    }
                    i = j + 1
                    continue
                }
                i++
            }
            return text.length to newlines
        }
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i++
                c == '\n' -> return i to newlines
                c == quote -> return (i + 1) to newlines
                quote == '"' && c == '$' &&
                    i + 1 < text.length && text[i + 1] == '{' -> {
                    var depth = 0
                    var j = i + 1
                    while (j < text.length) {
                        val cj = text[j]
                        if (cj == '\n') newlines++
                        when {
                            cj == '{' -> depth++
                            cj == '}' -> {
                                depth--
                                if (depth == 0) break
                            }
                            cj == '"' || cj == '\'' -> {
                                val (e2, n2) = skipLiteral(text, j)
                                newlines += n2
                                j = e2
                                continue
                            }
                        }
                        j++
                    }
                    i = j
                }
            }
            i++
        }
        return text.length to newlines
    }

    private fun allDecls(text: String): List<Decl> {
        val out = mutableListOf<Decl>()
        val keyword = Regex("""\b(?:val|var)\b""")
        var searchFrom = 0
        while (true) {
            val match = keyword.find(text, searchFrom) ?: break
            searchFrom = match.range.last + 1
            val headStart = searchFrom
            var i = headStart
            var depth = 0
            var lines = 0
            var hasGetter = false
            var delegated = false
            var terminated = false
            var failed = false
            while (i < text.length) {
                if (text[i] == '"' || text[i] == '\'') {
                    val (end, spanned) = skipLiteral(text, i)
                    lines += spanned
                    if (lines > 8) {
                        failed = true
                        break
                    }
                    i = end
                    continue
                }
                val c = text[i]
                if (c == '\n') {
                    lines++
                    if (lines > 8) {
                        failed = true
                        break
                    }
                    var j = i + 1
                    while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
                    if (j < text.length && text[j] == '\n') {
                        failed = true
                        break
                    }
                }
                when {
                    c == '(' || c == '<' || c == '[' -> depth++
                    c == ')' || c == '>' || c == ']' -> if (depth > 0) depth--
                    (c == '{' || c == '}') && depth == 0 -> {
                        failed = true
                        break
                    }
                    depth == 0 && c == ';' -> {
                        terminated = true
                        break
                    }
                    depth == 0 && c == '=' &&
                        (i + 1 >= text.length || text[i + 1] != '=') -> {
                        terminated = true
                        break
                    }
                    depth == 0 &&
                        text.startsWith("get", i) &&
                        (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_') &&
                        text.substring(i + 3).trimStart().startsWith("(") -> {
                        hasGetter = true
                        terminated = true
                        break
                    }
                    depth == 0 &&
                        text.startsWith("set", i) &&
                        (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_') &&
                        text.substring(i + 3).trimStart().startsWith("(") -> {
                        terminated = true
                        break
                    }
                    depth == 0 &&
                        text.startsWith("by", i) &&
                        (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_') &&
                        (i + 2 >= text.length || !text[i + 2].isLetterOrDigit() && text[i + 2] != '_') -> {
                        delegated = true
                        terminated = true
                        break
                    }
                    depth == 0 &&
                        (text.startsWith("val ", i) || text.startsWith("var ", i) ||
                            text.startsWith("val\t", i) || text.startsWith("var\t", i) ||
                            text.startsWith("val\n", i) || text.startsWith("var\n", i)) &&
                        i > headStart + 1 &&
                        text.substring(headStart, i).contains(':') -> {
                        terminated = true
                        break
                    }
                }
                i++
            }
            if (terminated && !failed) {
                parseHead(text.substring(headStart, i), hasGetter, delegated)
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    @Test
    fun declarationScannerClassifiesTheRoundSixtySixEscapedSpellings() {
        fun declsOf(sample: String): List<Decl> = allDecls(stripComments(sample))
        val cases: Map<String, List<Decl>> = mapOf(
            // Round 66's escapes:
            "val doctorSplitType:\n    Int\n    get() = 2" to
                listOf(Decl("doctorSplitType", "Int", null, true, false)),
            "val doctorGetterAnnot: Int\n    @Deprecated(\"d\") get() = 3" to
                listOf(Decl("doctorGetterAnnot", "Int", null, true, false)),
            "val doctorNamedArg: Int @DoctorAnn(v = \"x\") get() = 4" to
                listOf(Decl("doctorNamedArg", "Int", null, true, false)),
            // Round 67's escapes: terminator/depth tokens inside
            // the annotation's own string (or char) literal —
            // the round-66 scan counted depth and terminators
            // over literal contents, so the first two of these
            // ran green across the full suite.
            "val doctorParenString: Int\n    @Deprecated(\"(\") get() = 7" to
                listOf(Decl("doctorParenString", "Int", null, true, false)),
            "val doctorEqString: Int @Deprecated(\") = x\") get() = 8" to
                listOf(Decl("doctorEqString", "Int", null, true, false)),
            "val doctorCharParen: Int @DoctorAnn(c = '(') get() = 9" to
                listOf(Decl("doctorCharParen", "Int", null, true, false)),
            "val `doctor fresh`: Int get() = 5" to
                listOf(Decl("doctor fresh", "Int", null, true, false)),
            "val DownloadRecord\n    .doctorRecvSplit: String get() = \"\"" to
                listOf(Decl("doctorRecvSplit", "String", "DownloadRecord", true, false)),
            "val DownloadRecord.doctorDelegated: String by DoctorAnyParamDelegate()" to
                listOf(Decl("doctorDelegated", "String", "DownloadRecord", false, true)),
            // Round 65's six escapes, still classified:
            "val List<DownloadRecord>.doctorFreshCount: Int get() = size" to
                listOf(Decl("doctorFreshCount", "Int", "List<DownloadRecord>", true, false)),
            "val DownloadRecord?.doctorFreshId: String get() = \"\"" to
                listOf(Decl("doctorFreshId", "String", "DownloadRecord?", true, false)),
            "val dev.example.DownloadRecord?.doctorFqId: String get() = \"\"" to
                listOf(Decl("doctorFqId", "String", "dev.example.DownloadRecord?", true, false)),
            "val DownloadRecord . doctorSpacedId: String get() = \"\"" to
                listOf(Decl("doctorSpacedId", "String", "DownloadRecord", true, false)),
            "val doctorProbeMap: Map<String, String> get() = emptyMap()" to
                listOf(Decl("doctorProbeMap", "Map<String, String>", null, true, false)),
            "val doctorStar: List<*> get() = emptyList<Any>()" to
                listOf(Decl("doctorStar", "List<*>", null, true, false)),
            // The shipped shape:
            "val continuationTracker: ContinuationTracker get() = x" to
                listOf(Decl("continuationTracker", "ContinuationTracker", null, true, false)),
            // One line, two declarations, semicolon-separated:
            "val doctorSemiA: Int = 1; val doctorSemiB: Int get() = 2" to
                listOf(
                    Decl("doctorSemiA", "Int", null, false, false),
                    Decl("doctorSemiB", "Int", null, true, false),
                ),
        )
        for ((sample, expected) in cases) {
            assertEquals(
                "the round-66 declaration scanner must classify " +
                    "`$sample` structurally — round 65's single-line " +
                    "regex head lets the first six of these spellings " +
                    "past its census entirely",
                expected,
                declsOf(sample),
            )
        }
    }

    @Test
    fun getterCensusByMultilineDeclarationScanMatchesTheShippedElevenAndZeroExtensions() {
        val expectedGetters: Map<String, Map<String, String>> = mapOf(
            "dev/cyberdogs/offleash/data/AppPreferences.kt" to mapOf(
                "streamQuality" to "QualityPreference",
                "audioVersionInRadio" to "Boolean",
                "downloadQuality" to "DownloadQuality",
                "autoDownloadLiked" to "Boolean",
                "playbackSpeed" to "Float",
                "keepPitchAtSpeed" to "Boolean",
            ),
            "dev/cyberdogs/offleash/data/ParityPreferences.kt" to mapOf(
                "downloadQuality" to "DownloadQualityPreference",
                "autoDownloadLiked" to "Boolean",
                "audioVersionInRadio" to "Boolean",
            ),
            "dev/cyberdogs/offleash/data/Downloads.kt" to mapOf(
                "videoId" to "String",
            ),
            "dev/cyberdogs/offleash/playback/SleepTimer.kt" to mapOf(
                "isArmed" to "Boolean",
            ),
        )
        for ((path, raw) in moduleSources()) {
            val text = stripComments(raw)
            val decls = allDecls(text)
            val getters = decls.filter { it.hasGetter }
            val inventory = getters.filter { it.receiver == null }
                .associate { it.name to (it.type ?: "") }
            assertEquals(
                "$path must hold exactly its shipped getter-" +
                    "computed properties under the MULTILINE " +
                    "DECLARATION SCAN — round 65's census keys " +
                    "on a single-line regex head, so a getter " +
                    "whose type is split across lines, whose " +
                    "annotation sits on its own line or carries " +
                    "a named argument before `get()`, or whose " +
                    "name is a backtick name with a space is " +
                    "invisible to it: three of this round's " +
                    "four such additions ran green suite-wide, " +
                    "and the fourth (the split type) is caught " +
                    "only by the older censuses",
                expectedGetters[path] ?: emptyMap<String, String>(),
                inventory,
            )
            assertEquals(
                "$path must declare no extension getter under " +
                    "the multiline declaration scan — a receiver " +
                    "split across lines is invisible to round " +
                    "65's single-line head: this round's such " +
                    "addition ran green across the full suite",
                emptyList<Decl>(),
                getters.filter { it.receiver != null },
            )
            assertEquals(
                "$path must declare no extension property of " +
                    "any kind under the multiline declaration " +
                    "scan, delegated properties included — round " +
                    "65's parse terminates a head only at `=` or " +
                    "`get(`, so a delegated extension property " +
                    "is invisible to it (the shipped suite's " +
                    "older censuses catch that spelling; this " +
                    "census's own zero now does too)",
                emptyList<Decl>(),
                decls.filter { it.receiver != null },
            )
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

    private fun moduleSources(): Map<String, String> {
        val javaRoot = locateAppJavaRoot()
        val out = sortedMapOf<String, String>()
        javaRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val relative = file.relativeTo(javaRoot).invariantSeparatorsPath
                out[relative] = file.readText()
            }
        for (anchor in listOf(
            "dev/cyberdogs/offleash/ui/OffleashRoot.kt",
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt",
        )) {
            assertTrue(
                "the module walk must find its anchor file $anchor",
                out.containsKey(anchor),
            )
        }
        return out
    }

    private fun locateAppJavaRoot(): File {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            for (candidate in listOf(
                File(dir, "src/main/java"),
                File(dir, "app/src/main/java"),
            )) {
                if (File(candidate, "dev/cyberdogs/offleash/ui/OffleashRoot.kt").isFile) {
                    return candidate
                }
            }
            dir = dir.parentFile
            hops++
        }
        throw AssertionError(
            "app/src/main/java not found from " + File(".").absolutePath,
        )
    }
    @Test
    fun lyricsUnavailableReasonIsRenderedWithoutAStutterOrDoubledStop() {
        val player = File(locateAppJavaRoot(), "dev/cyberdogs/offleash/ui/player/PlayerScreen.kt")
            .readText()
        val branchStart = player.indexOf("is LyricsUiState.Unavailable")
        assertTrue("LyricsSection must have an Unavailable branch", branchStart >= 0)
        val branchEnd = player.indexOf("is LyricsUiState.Found", branchStart)
        assertTrue("the Unavailable branch must precede the Found branch", branchEnd > branchStart)
        val branch = player.substring(branchStart, branchEnd)
        assertTrue(
            "the Unavailable branch must test whether the reason is already a " +
                "complete sentence (endsWith(\".\")) before composing it — without " +
                "the test, core's full-sentence failure reason renders through the " +
                "fragment wrapper as \"Lyrics unavailable — Lyrics are unavailable " +
                "for this track..\"",
            branch.contains("if (current.reason.endsWith(\".\"))"),
        )
        assertTrue(
            "a complete-sentence reason must stand alone as the rendered text",
            branch.contains("current.reason\n"),
        )
        assertTrue(
            "the fragment reason must still be wrapped as " +
                "\"Lyrics unavailable — <fragment>.\"",
            branch.contains("\"Lyrics unavailable — \${current.reason}.\""),
        )
        assertTrue(
            "the two legs must stand in THIS order, as one exact " +
                "composition (round 67: the three presence checks " +
                "above all still pass with the legs SWAPPED — the " +
                "sentence wrapped and doubled, the fragment left " +
                "bare — which is the round-66 defect returning " +
                "through the neighbouring door)",
            branch.contains(
                "text = if (current.reason.endsWith(\".\")) {\n" +
                    "                    current.reason\n" +
                    "                } else {\n" +
                    "                    \"Lyrics unavailable — \${current.reason}.\"\n" +
                    "                },",
            ),
        )
        // Round 70: the Found leg's header was pinned nowhere —
        // respelling it to `"Lyrics • ${current.source}."` (a
        // doubled stop waiting for any source name that ends
        // with one, and a stray period against every source
        // that does not) ran GREEN across all 276 playback
        // tests. The found header is a bare attribution, no
        // sentence punctuation:
        val foundBranch = player.substring(branchEnd)
        assertTrue(
            "the Found branch must render the source attribution " +
                "exactly as \"Lyrics • <source>\" — no trailing period",
            foundBranch.contains("\"Lyrics • \${current.source}\""),
        )
        assertTrue(
            "the Found header must not gain a trailing period",
            !foundBranch.contains("\"Lyrics • \${current.source}.\""),
        )
        // Round 71: the Found header is a CONDITIONAL, and its
        // synced leg was pinned nowhere — the round-70 asserts
        // above name only the unsynced string. Two neighbours
        // ran GREEN across the full suite: the synced leg
        // gaining the same trailing period
        // (`"Synced lyrics • ${current.source}."`), and the two
        // legs SWAPPED, so plain lyrics render as "Synced
        // lyrics" and synced lyrics as plain — a wrong claim
        // about the panel's own highlighting. Pin the synced
        // attribution exactly, its no-period form, and the
        // whole conditional as one exact composition.
        assertTrue(
            "the Found branch must render the SYNCED source " +
                "attribution exactly as \"Synced lyrics • <source>\"",
            foundBranch.contains("\"Synced lyrics • \${current.source}\""),
        )
        assertTrue(
            "the synced Found header must not gain a trailing period",
            !foundBranch.contains("\"Synced lyrics • \${current.source}.\""),
        )
        assertTrue(
            "the two Found legs must stand in THIS order, as " +
                "one exact composition — synced first, plain " +
                "second; swapped legs mislabel every panel",
            foundBranch.contains(
                "text = if (current.synced) {\n" +
                    "                        \"Synced lyrics • \${current.source}\"\n" +
                    "                    } else {\n" +
                    "                        \"Lyrics • \${current.source}\"\n" +
                    "                    },",
            ),
        )
        // Round 72: every Found assert above reads the RAW
        // file, and the whole composition parked inside a
        // block comment satisfied all of them while the live
        // legs stood swapped (the round-71 defect itself),
        // GREEN across the full suite. The claims are
        // re-asserted on the COMMENT-STRIPPED Found branch —
        // a parked copy vanishes there; the live composition
        // is what must stand.
        val strippedPlayer = stripComments(player)
        val strippedFoundStart =
            strippedPlayer.indexOf("is LyricsUiState.Found")
        assertTrue(
            "the Found branch must exist in stripped code too",
            strippedFoundStart >= 0,
        )
        val strippedFound = strippedPlayer.substring(strippedFoundStart)
        assertTrue(
            "in CODE, the Found branch must render the plain " +
                "attribution exactly, with no trailing period",
            strippedFound.contains("\"Lyrics • \${current.source}\"") &&
                !strippedFound.contains("\"Lyrics • \${current.source}.\""),
        )
        assertTrue(
            "in CODE, the Found branch must render the synced " +
                "attribution exactly, with no trailing period",
            strippedFound.contains("\"Synced lyrics • \${current.source}\"") &&
                !strippedFound.contains("\"Synced lyrics • \${current.source}.\""),
        )
        assertTrue(
            "in CODE, the two Found legs must stand in THIS " +
                "order, as one exact composition — a copy " +
                "parked in a comment is not the panel rendering",
            strippedFound.contains(
                "text = if (current.synced) {\n" +
                    "                        \"Synced lyrics • \${current.source}\"\n" +
                    "                    } else {\n" +
                    "                        \"Lyrics • \${current.source}\"\n" +
                    "                    },",
            ),
        )
        // The premise, in core: exactly the two reason shapes.
        val repoRoot = File(locateAppJavaRoot(), "../../../..").canonicalFile
        val client = File(
            repoRoot,
            "core/src/main/kotlin/dev/cyberdogs/offleash/core/api/YoutubeiClient.kt",
        ).readText()
        assertTrue(
            "core's no-lyrics reason must stay the fragment (no trailing period)",
            client.contains(
                "const val NO_LYRICS_REASON = \"YouTube offers no lyrics for this track\"",
            ),
        )
        assertTrue(
            "core's failure reason must stay the full sentence (period included)",
            client.contains(
                "LyricsResult.Unavailable(\"Lyrics are unavailable for this track.\")",
            ),
        )
    }

}
