package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 65 sweep contracts (adversarial lane — breaking the
 * round-64 fixes):
 *
 *  (F1) Round 64's normalized getter census widened round
 *  63's census exactly one step: onto the normalized view,
 *  plus an extension zero census whose receiver is a chain
 *  of dot-separated plain identifiers. Both patterns still
 *  key on a fixed TYPE ALPHABET (`[A-Za-z0-9_.<>?,]+` — no
 *  space, no `*`, no parentheses) and a fixed RECEIVER
 *  ALPHABET (plain identifiers and dots only). Six valid
 *  spellings stand outside both, each verified green
 *  across the full 667-test suite with all six applied at
 *  once (shadow-compiled, 0 errors, before the combined
 *  run):
 *   - an extension getter on a GENERIC receiver
 *     (`val List<DownloadRecord>.doctorFreshCount: Int
 *     get() = size`),
 *   - on a NULLABLE receiver
 *     (`val DownloadRecord?.doctorFreshId: String get() = …`),
 *   - on a FULLY-QUALIFIED NULLABLE receiver,
 *   - an extension getter with SPACES around the receiver
 *     dot (`val DownloadRecord . doctorSpacedId …`),
 *   - a plain getter whose type is a SPACED GENERIC
 *     (`val doctorProbeMap: Map<String, String> get() = …`),
 *   - a plain getter whose type carries a STAR PROJECTION
 *     (`val doctorStar: List<*> get() = …`).
 *  (The seventh spelling tried, a function-typed getter
 *  `(String) -> Boolean`, dies on round 64's lambda-type
 *  zero census in the remainder-families test — named
 *  there, not here.) The second test below re-runs the
 *  inventory with a DECLARATION PARSER instead of a type
 *  alphabet: every `val`/`var` head that reaches a `get()`
 *  is split at its top-level colon and its last top-level
 *  dot, so receiver and type are whatever Kotlin allows
 *  them to be; extension getters and extension properties
 *  are censused at their shipped zero under the same parse.
 *
 *  (F2, scanner canaries — no finding) Round 64's
 *  frame-stack scanner was attacked with the round-2
 *  shapes: nested templates, a template inside a raw
 *  string, char literals holding `\"` and `\'` inside a
 *  template, `${` inside a line comment, an escaped dollar
 *  whose content carries an escaped quote, a brace inside
 *  a nested string, and a brace inside a block comment
 *  inside a template. Every one is HANDLED: a comment-gap
 *  write behind each prelude fails round 64's census test
 *  by name in doctor runs. (The bare-quote escaped-dollar
 *  spelling that desynchronizes a naive port —
 *  `"\${ '"' }"` — is not valid Kotlin: the bare quote
 *  ends the string in Kotlin's own lexer too, proven by
 *  kotlinc; the CI compile gate owns that shape, as with
 *  round 64's two by-construction dismissals.) The first
 *  test pins the scanner's behaviour on those shapes
 *  synthetically, so they are named where they close.
 */
class R65SweepContractTest {

    // -- F2: the frame-stack scanner on the round-2 shapes -------------

    @Test
    fun scannerHandlesRoundTwoTemplateShapesByConstruction() {
        val write = Regex("""(_[A-Za-z0-9]+)\.value\s*(?:=(?!=)|\+=|-=)""")
        val shapes: Map<String, String> = mapOf(
            "nested template (a template inside a template)" to
                "val s: String = \"\${ \"\${ '\"' }\" }\"\n",
            "template inside a raw string" to
                "val s: String = \"\"\"\${ '\"' }\"\"\"\n",
            "char literal holding a backslash double-quote, in a template" to
                "val s: String = \"\${ '\\\"' }\"\n",
            "char literal holding a backslash single-quote, in a template" to
                "val s: String = \"\${ '\\\'' }\"\n",
            "dollar-brace inside a line comment" to
                "// \${ '\"' }\n",
            "escaped dollar whose content carries an escaped quote" to
                "val s: String = \"\\\${ '\\\"' }\"\n",
            "unicode-escaped dollar whose content carries an escaped quote" to
                "val s: String = \"\\u0024{ '\\\"' }\"\n",
            "brace inside a string nested in a template" to
                "val s: String = \"\${ \"a}b\" }\"\n",
            "brace inside a block comment inside a template" to
                "val s: String = \"\${ 1 /* } */ }\"\n",
            "raw string with a nested template inside a template" to
                "val s: String = \"\${ \"\"\"\${ '\"' }\"\"\" }\"\n",
        )
        for ((shape, prelude) in shapes) {
            val sample = prelude + "_state.value /* gap */ = X\n"
            assertEquals(
                "$shape must not desynchronize the frame-stack " +
                    "normalizer: the code after the prelude is " +
                    "code, the gap comment is a comment, and " +
                    "the write behind it must count — each of " +
                    "these preludes was also planted ahead of a " +
                    "gap write in SearchViewModel.search() in a " +
                    "doctor run, and every one failed round 64's " +
                    "template-aware census by name",
                1,
                write.findAll(stripComments(sample)).count(),
            )
        }
    }

    // -- F1: the getter census by declaration parse --------------------

    /** One parsed property declaration head. */
    private data class Decl(
        val name: String,
        val type: String?,
        val receiver: String?,
        val hasGetter: Boolean,
    )

    /**
     * Splits a declaration head at its top-level colon
     * (outside `<>` and `()`), then splits the name part at
     * its last top-level dot: everything before it is the
     * receiver, whatever shape Kotlin gives it (generic,
     * nullable, fully-qualified, space-padded).
     */
    private fun parseDecl(head: String, hasGetter: Boolean): Decl? {
        val trimmed = head.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("(")) return null
        var depth = 0
        var colon = -1
        for (i in trimmed.indices) {
            when (trimmed[i]) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                ':' -> if (depth == 0) {
                    colon = i
                    break
                }
            }
        }
        val namePart = if (colon >= 0) trimmed.substring(0, colon) else trimmed
        val typePart = if (colon >= 0) {
            trimmed.substring(colon + 1).trim().ifEmpty { null }
        } else {
            null
        }
        depth = 0
        var dot = -1
        for (i in namePart.indices) {
            when (namePart[i]) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                '.' -> if (depth == 0) dot = i
            }
        }
        val receiver = if (dot >= 0) {
            namePart.substring(0, dot).trim().ifEmpty { null }
        } else {
            null
        }
        val name = if (dot >= 0) {
            namePart.substring(dot + 1).trim()
        } else {
            namePart.trim()
        }.removeSurrounding("`")
        if (!name.matches(Regex("""[A-Za-z_][A-Za-z0-9_]*"""))) return null
        return Decl(name, typePart, receiver, hasGetter)
    }

    /** Every `val`/`var` declaration head that reaches a `get()`. */
    private fun getterDecls(text: String): List<Decl> {
        val regex = Regex("""(?:val|var)\s+([^\n;{}=]+?)\s+get\s*\(\s*\)""")
        return regex.findAll(text).mapNotNull {
            parseDecl(it.groupValues[1], hasGetter = true)
        }.toList()
    }

    /** Every `val`/`var` declaration head (getter or not). */
    private fun allDecls(text: String): List<Decl> {
        val regex = Regex(
            """(?:val|var)\s+([^\n;{}]+?)\s*(?::\s*[^\n;{}=]+?)?\s*(=|get\s*\()""",
        )
        return regex.findAll(text).mapNotNull { match ->
            parseDecl(
                match.groupValues[1],
                hasGetter = match.groupValues[2].startsWith("get"),
            )
        }.toList()
    }

    @Test
    fun getterParserClassifiesTheEscapedSpellingsByConstruction() {
        val samples: Map<String, Decl> = mapOf(
            "val List<DownloadRecord>.doctorFreshCount: Int get() = size" to
                Decl("doctorFreshCount", "Int", "List<DownloadRecord>", true),
            "val DownloadRecord?.doctorFreshId: String get() = \"\"" to
                Decl("doctorFreshId", "String", "DownloadRecord?", true),
            "val dev.example.DownloadRecord?.doctorFqId: String get() = \"\"" to
                Decl("doctorFqId", "String", "dev.example.DownloadRecord?", true),
            "val DownloadRecord . doctorSpacedId: String get() = \"\"" to
                Decl("doctorSpacedId", "String", "DownloadRecord", true),
            "val doctorProbeMap: Map<String, String> get() = emptyMap()" to
                Decl("doctorProbeMap", "Map<String, String>", null, true),
            "val doctorStar: List<*> get() = emptyList<Any>()" to
                Decl("doctorStar", "List<*>", null, true),
            "val continuationTracker: ContinuationTracker get() = x" to
                Decl("continuationTracker", "ContinuationTracker", null, true),
        )
        for ((sample, expected) in samples) {
            assertEquals(
                "the declaration parser must classify `$sample` " +
                    "structurally — round 64's fixed type and " +
                    "receiver alphabets let every one of these " +
                    "spellings past the census, green suite-wide",
                listOf(expected),
                getterDecls(sample),
            )
        }
    }

    @Test
    fun getterCensusByDeclarationParseMatchesTheShippedElevenAndZeroExtensions() {
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
            val getters = getterDecls(text)
            val inventory = getters.filter { it.receiver == null }
                .associate { it.name to (it.type ?: "") }
            assertEquals(
                "$path must hold exactly its shipped getter-" +
                    "computed properties under the DECLARATION " +
                    "PARSE — round 64's inventory keys on a " +
                    "fixed type alphabet, so a getter whose " +
                    "type carries a space (`Map<String, " +
                    "String>`) or a star projection (`List<*>`) " +
                    "is invisible to it at any normalization: " +
                    "this round's two such additions each ran " +
                    "green suite-wide",
                expectedGetters[path] ?: emptyMap<String, String>(),
                inventory,
            )
            assertEquals(
                "$path must declare no extension getter under " +
                    "the declaration parse — round 64's zero " +
                    "census models the receiver as plain " +
                    "identifiers and dots, so a generic, " +
                    "nullable, fully-qualified-nullable or " +
                    "space-padded receiver is invisible to it: " +
                    "this round's four such additions each ran " +
                    "green suite-wide",
                emptyList<Decl>(),
                getters.filter { it.receiver != null },
            )
            assertEquals(
                "$path must declare no extension property of " +
                    "any kind under the declaration parse (the " +
                    "non-getter half of the same zero)",
                emptyList<Decl>(),
                allDecls(text).filter { it.receiver != null },
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
            val next = if (i + 1 < n) text[i + 1] else ' '
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
}
