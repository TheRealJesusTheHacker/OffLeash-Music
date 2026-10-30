package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 21 sweep contracts (confirmation round — fresh-eyes XIV):
 *
 * The artist page was the one browse surface with no empty state.
 * Album and playlist pages in the same file render an explicit
 * "No tracks were returned for this …" line when their track list
 * is empty, and Charts, Moods, Home (round 20) and Search all name
 * their empty results. The artist page rendered its header and
 * then, when the browse returned a header but zero sections —
 * parseArtist imposes no floor on the section list — silence:
 * no way to tell an empty result from a broken list.
 *
 * Fix: the artist empty state keys on the sections alone, in the
 * sibling surfaces' wording shape.
 *
 * DetailScreens is Compose-bound, so the shape is the pin (house
 * style; CI is the compile gate). The pin lives in the playback
 * test source set, like R20SweepContractTest, so the local
 * harness runs it.
 *
 * Round 22 strengthening: as written in round 21 this pin was
 * presence-only — the exact shape round 21 itself proved hollow
 * for the R20 Home pin. Logic replay in round 22 showed it green
 * with (a) the sections-only guard surviving in a comment while
 * the real guard went compound
 * (`sections.isEmpty() && thumbnailUrl != null` — a header-only
 * artist page without artwork goes silent again), (b) the guard
 * kept verbatim but the empty text moved under an inner
 * thumbnail condition, and (c) the guard respelled
 * `sections.size == 0` with the needle comment-only. The pin now
 * counts the guard and the text (exactly once each), pins the
 * exact nested block (guard -> item -> Text), and bans a
 * compound whose first leg is the sections check — the R20
 * pin's round-21 shape, applied to this pin. Simple deletions
 * (guard removed or neutralised, text removed, guard re-keyed
 * on the page name) already failed the original pin and still
 * fail. Brittleness direction is safe, as with R20: a legitimate
 * equivalent respelling fails this pin closed.
 *
 * Round 23 strengthening: the round-22 counts are a linchpin
 * only while the real code contributes one of each needle —
 * a block comment carrying the exact nested block inflates
 * both counts to two and fails (re-verified in round 23).
 * But REPLACE the real guard with a wrong one
 * (`page.name.isEmpty()`) and SPLIT the real rendered text
 * into a concatenation (`"No content was returned " +
 * "for this artist."`, which renders identically), and the
 * real code contributes ZERO of each needle: the one block
 * comment then supplies both counts, the nested block, and
 * both presence needles, and every round-22 assertion stayed
 * green while a named artist page with no sections went
 * silent again (proven live in round 23). The pin now ALSO
 * asserts the guard count, the text count, and the exact
 * nested block on noComments — the file with line and
 * (nested) block comments lexically stripped and literals
 * kept verbatim — where a comment can supply nothing. A
 * comment-supplied block now fails the stripped assertions,
 * and the concatenated real text (zero needles in code)
 * fails with it, in the same safe direction as before.
 *
 * Round 24 strengthening: noComments keeps literal CONTENTS
 * verbatim — that is its documented design — so the round-23
 * construction works one level down: a dead RAW STRING whose
 * content is the entire nested block verbatim (newlines and
 * indentation included) supplies the guard count, the text
 * count, and the nested block on BOTH the raw view and the
 * noComments view, while the real guard is wrong and the
 * real text is the same needle-free concatenation (proven
 * green in round 24; no assertion anywhere in this pin read
 * a view where literals are stripped). The pin now ALSO
 * asserts on strictCode — the whole-text lexical strip that
 * blanks regular/raw string and char literal CONTENTS
 * (template expressions kept as code), as in the R18 pin:
 * the guard must occur exactly once there, and the exact
 * nested block must exist there in its stripped form (the
 * rendered-text literal blanked to ""). A raw-string decoy
 * contributes only newlines to that view, and the real
 * block under a wrong guard with a concatenated text
 * (`text = "" + ""`) cannot match the stripped block
 * either, in the same fail-closed direction as every prior
 * strengthening of this pin. The text needle itself is
 * NOT asserted on the strict view: the needle IS literal
 * content, so the strict view blanks the legitimate
 * occurrence too — the guard and the block's code skeleton
 * are the claims that view can soundly carry.
 *
 * Round 25 strengthening: the round-24 stripped view
 * counts the SOLO guard needle, and the ban covers only
 * a compound whose FIRST leg is the sections check.
 * Proven live in round 25: the real block moved under a
 * compound whose first leg is the thumbnail check
 * (`page.thumbnailUrl != null && page.sections.isEmpty()`)
 * with its text split into the round-23 concatenation,
 * while a genuine solo block sat dead inside an
 * `if (false)` wrapper elsewhere in the same screen —
 * real code, so it supplied the guard count, the text
 * count, and the nested block on the raw, noComments,
 * AND strict views alike, while a header-only artist
 * page without artwork went silent again. The pin now
 * counts EVERY `page.sections.isEmpty()` occurrence in
 * the file (exactly one, taken on the raw text, where
 * comment and literal copies count too — the same
 * fail-closed direction as this pin's other counts):
 * the solo block and any compound use of the check, in
 * either leg, cannot coexist.
 *
 * Round 26 strengthening: the round-25 count keys on
 * the exact receiver spelling `page.sections.isEmpty()`.
 * Proven live in round 26: the real block moved under a
 * compound guard on an ALIASED receiver
 * (`val secs = page.sections` /
 * `secs.isEmpty() && page.thumbnailUrl != null`, its
 * text split into the concatenation) while the genuine
 * solo block sat dead inside an `if (false)` wrapper —
 * the alias carries no `page.sections.isEmpty()`
 * footprint at all, so the round-25 count stayed 1 and
 * every view passed. The pin now also counts EVERY
 * `isEmpty()` call in the file on any receiver (exactly
 * the four legitimate guards), counts every
 * `page.sections` reference (the guard and the items
 * call — an alias assignment is a third), and bans the
 * alternative emptiness spellings on sections outright
 * (`size`, `count()`, `isNotEmpty`, `none` — none occur
 * in the shipped file). The chartered seams that key on
 * the sections expression itself were re-proven
 * fail-closed in round 26: a needle-free real guard
 * (`count() == 0`), a `with(page)` receiver, and the
 * spaced spelling `page . sections . isEmpty()` (legal
 * Kotlin, toy-proven) each pass every raw assertion
 * behind a comment decoy and fail exactly at the
 * noComments guard count; a template-split member name
 * is not valid Kotlin at all (compiler: "Call has no
 * callee").
 *
 * Round 27 strengthening: the round-26 `isEmpty() == 4`
 * count is a file-wide NET over four unrelated guards.
 * Proven live in round 27: the artist construction of
 * round 26 (aliased real guard, dead solo block) was
 * paid for by respelling the ALBUM page's guard to the
 * behaviour-identical `page.tracks.size == 0`, and by
 * moving the artist items call onto the alias so the
 * `page.sections == 2` count also held — every round-26
 * assertion stayed green across the whole suite while
 * a header-only artist page without artwork went
 * silent again. The census behind the total: none of
 * the other three guards (shuffleAll's early return,
 * the album empty state, the playlist empty state) is
 * watched by any other pin — the total was their only
 * coverage, and a total can always be compensated
 * across pages. The pin now also counts each
 * non-artist guard in its exact shipped form (the two
 * `page.tracks` guards together, and shuffleAll's
 * return guard), so freeing a token for an artist fake
 * breaks the freed guard's own count.
 *
 * Round 28 strengthening: the round-27 per-guard counts
 * read the RAW whole file, exactly the view the artist
 * pin itself stopped trusting in round 23 — proven live
 * in round 28: a block comment carrying the exact album
 * guard spelling paid for the live album guard
 * respelled to `page.tracks.size == 1` (empty album
 * silent, one-track album showing the empty text), the
 * same comment restoring the file-wide `isEmpty()`
 * total, green across the whole suite. The album and
 * playlist nested blocks were pinned nowhere. A new
 * pin below counts both guard spellings on the
 * comment-stripped and literals-stripped views and
 * binds each counted guard to its body (the album and
 * playlist nested blocks; shuffleAll's guarded
 * statement).
 *
 * Round 29 strengthening: the round-28 guard-to-body
 * binding is a whole-file `contains` — it does not care
 * WHERE the bound pair lives. Proven live in round 29:
 * the live album guard respelled to
 * `page.tracks.count() == 1` (an empty album renders
 * silence, a one-track album renders the empty text)
 * while the exact guard line plus its exact pinned
 * body sat dead inside an `if (false)` block — parked
 * in the album screen itself, in the artist screen,
 * and (for the playlist guard, same shape) across
 * screens — ran green across the whole suite, three
 * times. Nothing counted the album/playlist empty
 * TEXTS' occurrences (the artist pin counts its text;
 * these were contains-only), nothing counted
 * `if (false)` in this file, and no assertion bound
 * either block to its own screen. The artist control
 * (same construction on the sections guard) FAILED —
 * at its text count first, with the file-wide
 * `isEmpty()` total behind it — which is exactly the
 * asymmetry this strengthening closes: the pin below
 * now also counts both track texts exactly once on
 * the raw and comment-stripped views, bans
 * `if (false)` in this file outright, and requires
 * each nested block inside its own screen's slice
 * (AlbumScreen's block before ArtistScreen begins;
 * PlaylistScreen's block after PlaylistScreen
 * begins).
 *
 * Round 30 strengthening: the round-29 screen slices are
 * cut by substringAfter/substringBefore on the raw text,
 * and the raw search cannot tell code from prose — the
 * R18 pins' anchor-integrity treatment (rounds 23/24)
 * was never extended to these delimiters. Proven live
 * in round 30: a fake `fun PlaylistScreen(` inside a
 * line comment in AlbumScreen shifted the playlist
 * slice's start back into AlbumScreen, where a dead
 * exact playlist pair sat parked, while the live
 * playlist guard was respelled and its text split —
 * green across the whole suite. The pin now asserts
 * anchor integrity for all three screen delimiters
 * (exactly once on the raw text and exactly once under
 * strictCode). The round-29 dead-wrapper ban also named
 * only `if (false)`: the same dead pair parked in the
 * SAME screen under `when (false)`, under
 * `if (1 == 2)`, and under a named always-false val
 * (`val never = false; if (never)`) each ran green in
 * round 30 (the file counts no conditional in
 * DetailScreens.kt at all). The pin now also bans
 * `when (false)` outright (the shipped file's whens are
 * all `when (val current = state)`) and asserts each
 * track screen slice's complete conditional inventory
 * under strictCode — album: exactly three `if`s (its
 * guard plus the two in ArtistViewModel, which sits
 * between AlbumScreen and ArtistScreen inside this
 * pin's slice) and one `when` (its state dispatch);
 * playlist: exactly four `if`s and one `when`; no
 * while/for in either — so a dead wrapper of ANY shape parked in
 * either screen adds a conditional and fails, and each
 * nested block must occur exactly once inside its own
 * slice, not merely somewhere in it.
 *
 * Round 34 strengthening: the round-33 file-wide
 * `DetailHeader(` count (== 3) is a net — proven live in
 * round 34, the two live track-screen calls were routed
 * through one private helper composable declared inside
 * the album slice whose body respelled the actions
 * argument off for every page, and the playlist slice's
 * counted occurrence was supplied by a decoy (a comment,
 * then a dead function at file end), the total staying 3
 * (definition + helper body + decoy) across the whole
 * suite. The second pin now also reads the count on the
 * literals-stripped view, partitions the file by its own
 * declarations and pins each partition's count, bans any
 * function declaration inside either screen's partition,
 * binds each header to its LazyColumn header item, and
 * pins each slice's item count.
 *
 * Round 35 strengthening: the round-34 declaration ban
 * counts the raw substring `fun ` — proven live in round
 * 35, a helper composable declared in the gap after
 * AlbumScreen (spelled `private fun` + newline + name,
 * which the raw needle never sees; toy-proven legal, and
 * named arguments at its call site stay legal, unlike a
 * function-type val, for which the compiler prohibits
 * them) held the album partition's one DetailHeader text
 * inside its own LazyColumn item — supplying the
 * item-bound needle from its own body — with the actions
 * argument respelled off, while AlbumScreen's live header
 * routed through it from a KEYED item (`item(key = …)`),
 * whose spelling the slice's `item {` count never sees,
 * so the helper body's item paid that count too: green
 * across the whole suite. The second pin now also counts
 * declarations as keywords on the literals-stripped
 * partitions (whitespace games cannot hide a keyword),
 * binds each partition's counted header to before its
 * screen's empty-state guard (a gap helper sits after
 * the whole screen, its guard included), and counts
 * each slice's items under any spelling.
 *
 * Round 36 strengthening: the round-35 census counts
 * the `fun` keyword — proven live in round 36, a header
 * helper held in a function-type VAL (no `fun` keyword
 * anywhere) declared in the gap after AlbumScreen held
 * the partition's counted DetailHeader in its own
 * LazyColumn item, its own guard, both slice items, and
 * the exact actionsEnabled line, while the live site
 * invoked it positionally with a doctored page (track
 * list emptied), disabling the album header's actions
 * on every album, green across the whole suite. The
 * second pin now also binds each counted header to
 * before its screen's trackItems call (inside the
 * screen's body; a gap helper sits after it) and bans
 * any LazyListScope spelling in either screen's
 * partition (a LazyColumn-content helper must name its
 * receiver type wherever it is declared).
 *
 * Round 38 strengthening: every prior helper lived
 * AFTER its screen or in the gap — proven live in
 * round 38, the decoy moved INTO the screen: a
 * never-invoked composable function-type val in
 * AlbumScreen's Content arm prologue (an existing
 * counted `when` arm, so the conditional inventory
 * held; its receiver spelled through a typealias
 * declared before AlbumScreen, so the LazyListScope
 * ban held) held the partition's one DetailHeader
 * text in its own item at the item-bound needle's
 * exact indentation, before the guard and before
 * trackItems, so every count, partition, and ordering
 * claim in this pin stood — while the LIVE header was
 * displaced out of the counted spellings entirely:
 * rendered through an `items(...)` call (the plural
 * spelling neither item count sees) that passed
 * `::DetailHeader` by reference to a host composable
 * declared before AlbumScreen, which invoked it
 * positionally. The live site's Play-all and Shuffle
 * lambdas were swapped, and no assertion in this pin
 * could see the live invocation's arguments at all —
 * green across the whole suite. The second pin now
 * also bans a DetailHeader function reference outright
 * and binds each counted header to AFTER its screen's
 * LazyColumn opening (the live header item sits inside
 * the column; a prologue decoy sits before it).
 *
 * Round 39 strengthening: the round-38 fix pinned the
 * live call's FORM and POSITION, but not its handler
 * bindings — proven live in round 39, the counted,
 * correctly-placed live call in AlbumScreen kept every
 * count, partition, ordering, adjacency, and reference
 * claim in this pin while its onPlayAll and onShuffle
 * lambda bodies were swapped (Play all shuffled,
 * Shuffle played), green across the whole suite: no
 * assertion anywhere read the live sites' lambda
 * lines. The second pin now also binds both live
 * sites' handler arguments exactly (each correct
 * binding twice, file-wide, raw and strict; each
 * crossed binding zero times).
 */
class R21SweepContractTest {

    @Test
    fun artistEmptyStateKeysOnSectionsAlone() {
        val detail = detailScreens()
        assertTrue(
            "the artist empty-state text must exist at all",
            detail.contains("No content was returned for this artist."),
        )
        assertTrue(
            "the artist empty state must render whenever the landed " +
                "page has no sections — a header-only artist page " +
                "would otherwise render a silent blank",
            detail.contains("if (page.sections.isEmpty()) {"),
        )
        // Round 22: presence needles survive as comment decoys
        // (the round-21 lesson, applied to this pin). Count both
        // needles (exactly once each), pin the exact nested
        // block so the text cannot move under a second, inner
        // condition, and ban the compound in any form whose
        // first leg is the sections check.
        assertEquals(
            "the sections-only guard must occur exactly once — " +
                "a second occurrence means the asserted shape " +
                "survives as a comment or dead copy while the " +
                "real guard is something else",
            1,
            occurrences(detail, "if (page.sections.isEmpty()) {"),
        )
        assertEquals(
            "the empty-state text must occur exactly once — a " +
                "second occurrence means a decoy copy can keep " +
                "the presence needle alive while the rendered " +
                "text sits under a different condition",
            1,
            occurrences(detail, "No content was returned for this artist."),
        )
        assertTrue(
            "the empty text must sit DIRECTLY inside the " +
                "sections-only guard, as its item's Text — an " +
                "inner condition between the guard and the text " +
                "suppresses it exactly as a compound guard would",
            detail.contains(
                "if (page.sections.isEmpty()) {\n" +
                    "                    item {\n" +
                    "                        Text(\n" +
                    "                            text = \"No content was returned for this artist.\",",
            ),
        )
        assertFalse(
            "the sections check must not be the first leg of " +
                "any compound guard — a compound keyed partly on " +
                "the thumbnail or name suppresses the empty state " +
                "for exactly the header-only pages it exists for",
            detail.contains("page.sections.isEmpty() &&"),
        )
        // Round 23: the counts and the block above are raw
        // whole-file claims, so one block comment can supply
        // all of them while the real guard is wrong and the
        // real text is a needle-free concatenation. Assert
        // the same claims on the comment-stripped text, where
        // only real code (literals kept) can satisfy them.
        val code = noComments(detail)
        assertEquals(
            "the sections-only guard must occur exactly once " +
                "in CODE — a copy inside a comment must not be " +
                "able to stand in for a real guard that was " +
                "replaced by a wrong one",
            1,
            occurrences(code, "if (page.sections.isEmpty()) {"),
        )
        assertEquals(
            "the empty-state text must occur exactly once in " +
                "CODE — a comment copy must not be able to mask " +
                "a real text split into a concatenation the " +
                "needle cannot see",
            1,
            occurrences(code, "No content was returned for this artist."),
        )
        assertTrue(
            "the exact nested block must exist in CODE, not " +
                "only inside a comment — the block assertion is " +
                "a whole-file contains, so without this the " +
                "comment copy alone satisfies it while the real " +
                "text sits under a different guard",
            code.contains(
                "if (page.sections.isEmpty()) {\n" +
                    "                    item {\n" +
                    "                        Text(\n" +
                    "                            text = \"No content was returned for this artist.\",",
            ),
        )
        // Round 24: noComments keeps literal contents
        // verbatim, so a dead raw string carrying the entire
        // block verbatim supplies every assertion above. Read
        // a literals-stripped view as well: only the guard
        // and the block's code skeleton can be asserted there
        // (the text needle is literal content and is blanked
        // in the real code too), and a raw-string decoy —
        // whose contents strictCode blanks to newlines —
        // supplies neither.
        val strict = strictCode(detail)
        assertEquals(
            "the sections-only guard must occur exactly once " +
                "outside comments AND literals — a copy inside " +
                "a raw string must not be able to stand in for " +
                "a real guard that was replaced by a wrong one",
            1,
            occurrences(strict, "if (page.sections.isEmpty()) {"),
        )
        assertTrue(
            "the exact nested block must exist with its " +
                "literal contents stripped — guard, item, and " +
                "Text in real code, the rendered text a single " +
                "literal (blanked to \"\" in this view). A " +
                "raw-string decoy contributes only newlines " +
                "here, and a real text split into a " +
                "concatenation reads \"\" + \"\" and cannot " +
                "match, in the same fail-closed direction as " +
                "the raw assertions",
            strict.contains(
                "if (page.sections.isEmpty()) {\n" +
                    "                    item {\n" +
                    "                        Text(\n" +
                    "                            text = \"\",",
            ),
        )
        // Round 25: the strict guard count above counts
        // the SOLO spelling only, and the compound ban
        // covers only a compound whose first leg is the
        // sections check — a compound with the check as
        // its SECOND leg (`thumbnail != null &&
        // sections.isEmpty()`) evaded both, while a
        // dead-but-real solo block elsewhere in the
        // screen supplied every view's assertions (proven
        // live in round 25). Count every use of the check
        // in the file: exactly one — the solo guard
        // itself. Any compound use, in either leg, or any
        // second copy anywhere, breaks it.
        assertEquals(
            "the sections check must be used exactly once " +
                "in the whole file — a second use (a compound " +
                "guard with the check as either leg, or a " +
                "decoy solo block) means the asserted solo " +
                "block is not the block that renders",
            1,
            occurrences(detail, "page.sections.isEmpty()"),
        )
        // Round 26: the count above keys on the exact
        // receiver spelling — an aliased receiver
        // (`val secs = page.sections`, guard on
        // `secs.isEmpty()`) carries no such footprint, so
        // a compound guard on the alias passed every view
        // while a dead solo block supplied them (proven
        // live in round 26). Count the check on ANY
        // receiver, count every reference to the sections
        // list itself, and ban the alternative emptiness
        // spellings on sections: the guard may be spelled
        // exactly one way in this file, on the page
        // itself, exactly once.
        assertEquals(
            "the file must hold exactly five emptiness " +
                "guards — the four track guards (the 0.8.2 " +
                "anonymous-Liked fallback guard among them) " +
                "and the artist sections guard; a sixth, on " +
                "any receiver (an alias, a with-receiver), " +
                "means the asserted solo block is not the " +
                "only sections check in the file",
            5,
            occurrences(detail, "isEmpty()"),
        )
        assertEquals(
            "the sections list must be referenced exactly " +
                "twice — the guard and the items call; an " +
                "alias assignment (`val secs = " +
                "page.sections`) is a third reference and " +
                "the usual vehicle for a receiver the " +
                "spelling counts cannot see",
            2,
            occurrences(detail, "page.sections"),
        )
        // Round 27: the total above is a net across four
        // unrelated guards — a token freed by respelling
        // any one of them pays for an artist fake (proven
        // live in round 27 with the album guard respelled
        // to a size check). Count the non-artist guards in
        // their exact shipped forms as well, so each one
        // is pinned at its own site: the album and
        // playlist empty states share one guard spelling
        // (exactly two occurrences), and shuffleAll's
        // early return is the fourth.
        assertEquals(
            "the album and playlist empty-state guards " +
                "must each keep their exact shipped form — " +
                "respelling either one (a size check, a " +
                "negated isNotEmpty) frees its isEmpty() " +
                "token to compensate an added artist fake " +
                "under the file-wide total above",
            2,
            occurrences(detail, "if (page.tracks.isEmpty()) {"),
        )
        assertEquals(
            "shuffleAll's early return must keep its " +
                "exact shipped guard — respelling it frees " +
                "the fourth isEmpty() token the same way",
            1,
            occurrences(detail, "if (tracks.isEmpty()) return"),
        )
        assertFalse(
            "the sections emptiness must not be spelled " +
                "through its size — the same check under a " +
                "needle-free spelling",
            detail.contains("sections.size"),
        )
        assertFalse(
            "the sections emptiness must not be spelled " +
                "through a count comparison",
            detail.contains("sections.count()"),
        )
        assertFalse(
            "the sections emptiness must not be spelled " +
                "as a negated isNotEmpty",
            detail.contains("sections.isNotEmpty"),
        )
        assertFalse(
            "the sections emptiness must not be spelled " +
                "through a none() scan",
            detail.contains("sections.none"),
        )
    }

    /**
     * Round 28: the round-27 per-guard counts in the pin
     * above read the RAW whole file only — proven live in
     * round 28, a block comment carrying the exact album
     * guard spelling paid for a live album guard respelled
     * to `page.tracks.size == 1` (an empty album then
     * renders silence and a one-track album renders the
     * empty text), with the file-wide `isEmpty()` total
     * also restored by the same comment: green across the
     * whole suite. The album/playlist guards' bodies
     * (their nested empty-state blocks) were pinned
     * nowhere — the texts occur in the test tree only in
     * this class's header prose. Count both guard
     * spellings on the comment-stripped and
     * literals-stripped views as well (where a comment or
     * raw-string copy supplies nothing), and bind each
     * guard to its body: the album and playlist nested
     * blocks in raw, comment-stripped, and stripped
     * skeleton form, and shuffleAll's guard together with
     * the statement it guards.
     */
    @Test
    fun albumPlaylistAndShuffleGuardsKeepTheirExactFormsInCode() {
        val detail = detailScreens()
        val code = noComments(detail)
        val strict = strictCode(detail)
        assertEquals(
            "the album and playlist guards must occur " +
                "exactly twice in CODE — a copy inside a " +
                "comment must not be able to pay for a live " +
                "guard respelled to a size comparison",
            2,
            occurrences(code, "if (page.tracks.isEmpty()) {"),
        )
        assertEquals(
            "the album and playlist guards must occur " +
                "exactly twice outside comments AND " +
                "literals — a copy inside a raw string " +
                "must not be able to pay for the respell " +
                "either",
            2,
            occurrences(strict, "if (page.tracks.isEmpty()) {"),
        )
        assertEquals(
            "shuffleAll's guard must occur exactly once " +
                "in CODE — it is watched by the same " +
                "raw-only count and is compensatable the " +
                "same way",
            1,
            occurrences(code, "if (tracks.isEmpty()) return"),
        )
        assertEquals(
            "shuffleAll's guard must occur exactly once " +
                "outside comments AND literals as well",
            1,
            occurrences(strict, "if (tracks.isEmpty()) return"),
        )
        assertTrue(
            "the album guard must head its own nested " +
                "empty-state block in code — guard and " +
                "text bound as one block, as the artist " +
                "pin binds its block",
            code.contains(
                "if (page.tracks.isEmpty()) {\n" +
                    "                    item {\n" +
                    "                        Text(\n" +
                    "                            text = \"No tracks were returned for this album.\",",
            ),
        )
        assertTrue(
            "the album nested block must exist with its " +
                "literal contents stripped — a raw-string " +
                "or comment copy supplies only newlines " +
                "in this view",
            strict.contains(
                "if (page.tracks.isEmpty()) {\n" +
                    "                    item {\n" +
                    "                        Text(\n" +
                    "                            text = \"\",",
            ),
        )
        assertTrue(
            "the playlist guard must head its own nested " +
                "empty-state block in code",
            code.contains(
                "if (page.tracks.isEmpty()) {\n" +
                    "                    item {\n" +
                    "                        Text(\n" +
                    "                            text = \"No tracks were returned for this playlist.\",",
            ),
        )
        assertTrue(
            "shuffleAll's guard must be bound to the " +
                "statement it guards — the shuffle call " +
                "that may only run for a non-empty list",
            code.contains(
                "if (tracks.isEmpty()) return\n" +
                    "    container.queueController.setShuffle(true)",
            ),
        )
        assertTrue(
            "shuffleAll's guard-to-body binding must " +
                "hold in the literals-stripped view too",
            strict.contains(
                "if (tracks.isEmpty()) return\n" +
                    "    container.queueController.setShuffle(true)",
            ),
        )
        // Round 29: the bindings above are whole-file
        // contains claims — a dead pair (the exact guard
        // plus its exact body, parked inside `if (false)`
        // anywhere in the file) satisfies every one of
        // them while the live guard is respelled (proven
        // live in round 29 for both track guards, with
        // the pair parked in three different screens).
        // The artist pin's defences against exactly this
        // shape are a counted TEXT and a receiver-free
        // total; give the track guards the equivalents
        // that fit this file: count both empty texts,
        // ban the dead wrapper here, and bind each block
        // to its own screen's slice by position.
        assertEquals(
            "the album empty text must occur exactly " +
                "once — a dead pair carrying the pinned " +
                "block duplicates it, and the contains " +
                "bindings above cannot tell the copy " +
                "from the live block",
            1,
            occurrences(detail, "No tracks were returned for this album."),
        )
        assertEquals(
            "the album empty text must occur exactly " +
                "once in CODE as well",
            1,
            occurrences(code, "No tracks were returned for this album."),
        )
        assertEquals(
            "the playlist empty text must occur exactly " +
                "once — the same dead-pair shape on the " +
                "playlist guard duplicates it the same way",
            1,
            occurrences(detail, "No tracks were returned for this playlist."),
        )
        assertEquals(
            "the playlist empty text must occur exactly " +
                "once in CODE as well",
            1,
            occurrences(code, "No tracks were returned for this playlist."),
        )
        assertFalse(
            "this file must hold no `if (false)` wrapper " +
                "— a dead block is the vehicle that parks " +
                "an exact guard-plus-body pair for the " +
                "whole-file bindings above while the live " +
                "guard is respelled (the shipped file has " +
                "none; the R18 pins ban the same wrapper " +
                "in their slices)",
            detail.contains("if (false)"),
        )
        assertTrue(
            "the album nested block must sit inside " +
                "AlbumScreen itself (before ArtistScreen " +
                "begins) — a pair parked in another screen " +
                "is not the album page's empty state, " +
                "whatever the whole-file contains above " +
                "says",
            detail
                .substringAfter("fun AlbumScreen(")
                .substringBefore("fun ArtistScreen(")
                .contains(
                    "if (page.tracks.isEmpty()) {\n" +
                        "                    item {\n" +
                        "                        Text(\n" +
                        "                            text = \"No tracks were returned for this album.\",",
                ),
        )
        assertTrue(
            "the playlist nested block must sit inside " +
                "PlaylistScreen itself (the file's last " +
                "screen) — the same positional claim for " +
                "the playlist pair",
            detail
                .substringAfter("fun PlaylistScreen(")
                .contains(
                    "if (page.tracks.isEmpty()) {\n" +
                        "                    item {\n" +
                        "                        Text(\n" +
                        "                            text = \"No tracks were returned for this playlist.\",",
                ),
        )
        // Round 30 (1): anchor integrity for this pin's own
        // slice delimiters — the treatment the R18 pins got
        // in rounds 23/24. The slices above are cut on the
        // RAW text, so a delimiter duplicated inside a
        // comment or a literal shifts a slice boundary: a
        // fake `fun PlaylistScreen(` in a line comment
        // inside AlbumScreen moved the playlist slice's
        // start back into AlbumScreen, where a dead exact
        // pair was parked, while the live playlist guard
        // was respelled and its text split (proven live in
        // round 30, green across the whole suite). Every
        // delimiter must occur exactly once, in code.
        for (anchor in listOf(
            "fun AlbumScreen(",
            "fun ArtistScreen(",
            "fun PlaylistScreen(",
        )) {
            assertEquals(
                "the screen delimiter `$anchor` must occur " +
                    "exactly once in the file — a second " +
                    "occurrence (a comment or literal copy, " +
                    "or a duplicated definition) is a fake " +
                    "slice boundary that can pull a dead " +
                    "pair parked in another screen inside " +
                    "this screen's slice",
                1,
                occurrences(detail, anchor),
            )
            assertEquals(
                "the screen delimiter `$anchor` must occur " +
                    "exactly once outside comments and " +
                    "literals as well — a copy the raw slice " +
                    "can see but this view cannot is exactly " +
                    "the fake boundary above",
                1,
                occurrences(strict, anchor),
            )
        }
        assertFalse(
            "this file must hold no `when (false)` wrapper " +
                "either — it is the same dead-pair vehicle " +
                "as `if (false)` under a spelling the " +
                "round-29 ban did not name (the shipped " +
                "file's whens are all state dispatches)",
            detail.contains("when (false)"),
        )
        // Round 30 (2): the dead wrapper's shape is not the
        // point — ANY wrapper parked in a track screen adds
        // a conditional the shipped screen does not have.
        // Assert each slice's complete conditional
        // inventory under strictCode (where comment and
        // literal prose cannot pose as constructs), and
        // count each nested block inside its own slice:
        // exactly once, not merely present. A dead pair in
        // the same screen — under `when (false)`,
        // `if (1 == 2)`, a named always-false val, or bare —
        // adds its guard (and any wrapper) to the slice's
        // inventory while the live guard is respelled, and
        // fails here.
        val albumSlice = strict
            .substringAfter("fun AlbumScreen(")
            .substringBefore("fun ArtistScreen(")
        val playlistSlice = strict.substringAfter("fun PlaylistScreen(")
        assertEquals(
            "AlbumScreen's slice must hold exactly three " +
                "`if`s in its code — its empty-state guard " +
                "plus ArtistViewModel's two (the " +
                "CancellationException rethrow check and " +
                "the isActive publish guard; the view model " +
                "sits between the two screens inside this " +
                "slice); a dead pair parked in this screen " +
                "(bare or wrapped) is a fourth one",
            3,
            keywordCount(albumSlice, "if"),
        )
        assertEquals(
            "AlbumScreen's slice must hold exactly one " +
                "`when` in its code — its state dispatch; " +
                "a `when (false)` dead wrapper is a second",
            1,
            keywordCount(albumSlice, "when"),
        )
        assertEquals(
            "AlbumScreen's slice must hold no while or for",
            0,
            keywordCount(albumSlice, "while") +
                keywordCount(albumSlice, "for"),
        )
        assertEquals(
            "PlaylistScreen's slice must hold exactly " +
                "four `if`s in its code — the sign-in " +
                "reload check, its empty-state guard, the " +
                "load-more error check, and the hasMore " +
                "else-if; a dead pair parked in this screen " +
                "is a fifth",
            4,
            keywordCount(playlistSlice, "if"),
        )
        assertEquals(
            "PlaylistScreen's slice must hold exactly one " +
                "`when` in its code — its state dispatch",
            1,
            keywordCount(playlistSlice, "when"),
        )
        assertEquals(
            "PlaylistScreen's slice must hold no while or for",
            0,
            keywordCount(playlistSlice, "while") +
                keywordCount(playlistSlice, "for"),
        )
        assertEquals(
            "the album nested block must occur exactly " +
                "once inside AlbumScreen's own slice — a " +
                "dead copy beside a respelled live guard " +
                "is a second occurrence in the slice even " +
                "when the file-wide text count is paid for " +
                "by splitting the live text",
            1,
            occurrences(
                detail
                    .substringAfter("fun AlbumScreen(")
                    .substringBefore("fun ArtistScreen("),
                "if (page.tracks.isEmpty()) {\n" +
                    "                    item {\n" +
                    "                        Text(\n" +
                    "                            text = \"No tracks were returned for this album.\",",
            ),
        )
        assertEquals(
            "the playlist nested block must occur exactly " +
                "once inside PlaylistScreen's own slice",
            1,
            occurrences(
                detail.substringAfter("fun PlaylistScreen("),
                "if (page.tracks.isEmpty()) {\n" +
                    "                    item {\n" +
                    "                        Text(\n" +
                    "                            text = \"No tracks were returned for this playlist.\",",
            ),
        )
        // Round 31: the per-slice count above binds the
        // block to its SLICE, not to its POSITION inside the
        // slice — the artist block is bound guard-to-body,
        // but nothing bound the track blocks to where they
        // sit in their own LazyColumn. Proven live in round
        // 31: the genuine album block parked after an
        // unconditional `return@LazyColumn` at the bottom of
        // AlbumScreen's LazyColumn (dead text, still in the
        // slice, still the slice's one counted block) while
        // the live guard was respelled to a count check and
        // its text split — the slice's conditional inventory
        // paid for by converting ArtistViewModel's
        // CancellationException rethrow `if` to a let-throw
        // (-1 if against the dead pair's +1). An empty album
        // rendered silence; a one-track album rendered the
        // empty text. Bind each block to its shipped
        // position: it must directly follow its DetailHeader
        // item's close, as one adjacency, inside its own
        // slice.
        assertTrue(
            "the album nested block must directly follow " +
                "the DetailHeader item's close inside " +
                "AlbumScreen's slice — a block parked later " +
                "in the slice (after the track items, behind " +
                "a labelled return, in a dead lambda) is not " +
                "the album page's live empty state",
            detail
                .substringAfter("fun AlbumScreen(")
                .substringBefore("fun ArtistScreen(")
                .contains(
                    "actionsEnabled = page.tracks.isNotEmpty(),\n" +
                        "                    )\n" +
                        "                }\n" +
                        "                if (page.tracks.isEmpty()) {\n" +
                        "                    item {\n" +
                        "                        Text(\n" +
                        "                            text = \"No tracks were returned for this album.\",",
                ),
        )
        assertTrue(
            "the playlist nested block must directly " +
                "follow the DetailHeader item's close " +
                "inside PlaylistScreen's slice — the same " +
                "positional claim for the playlist pair",
            detail
                .substringAfter("fun PlaylistScreen(")
                .contains(
                    "actionsEnabled = page.tracks.isNotEmpty(),\n" +
                        "                    )\n" +
                        "                }\n" +
                        "                if (page.tracks.isEmpty()) {\n" +
                        "                    item {\n" +
                        "                        Text(\n" +
                        "                            text = \"No tracks were returned for this playlist.\",",
                ),
        )
        // Round 32: the adjacency above is a `contains` —
        // it binds SOME header close in the slice to the
        // block, not the LIVE header. Proven live in round
        // 32: a second, dead DetailHeader item plus the
        // exact block parked behind `return@LazyColumn`
        // supplied the adjacency while the live block was
        // displaced (guard respelled to a count check, text
        // split) — the file-wide `actionsEnabled` count the
        // decoy header would break was paid for by
        // respelling the LIVE header's argument to a size
        // comparison, and the slice's conditional inventory
        // was paid for as in round 31. A screen renders ONE
        // header: count the header calls per slice, where
        // no respell of the live call can pay for a decoy —
        // the composable's name is not respellable.
        assertEquals(
            "AlbumScreen's slice must hold exactly one " +
                "DetailHeader call — a second, dead header " +
                "item parked in the slice supplies the " +
                "adjacency above while the live block is " +
                "displaced under it",
            1,
            occurrences(
                detail
                    .substringAfter("fun AlbumScreen(")
                    .substringBefore("fun ArtistScreen("),
                "DetailHeader(",
            ),
        )
        assertEquals(
            "PlaylistScreen's slice must hold exactly one " +
                "DetailHeader call — the same decoy-pair " +
                "claim for the playlist slice",
            1,
            occurrences(
                detail.substringAfter("fun PlaylistScreen("),
                "DetailHeader(",
            ),
        )
        // Round 33: the per-slice counts above are blind to a
        // header call that lives in NO slice — proven live in
        // round 33, a private wrapper composable declared at
        // file level BEFORE the first screen delimiter (above
        // AlbumScreen, inside no slice) called DetailHeader
        // with its actions argument respelled off (actions
        // disabled on a non-empty page), the live album call
        // routed through the wrapper, and an in-slice decoy
        // supplied this slice's count and adjacency: every
        // assertion above stayed green while the pin could no
        // longer see the live header's arguments at all.
        // The file legitimately holds exactly three
        // `DetailHeader(` occurrences — the definition and
        // the two live screen calls. Any wrapper body is a
        // fourth, wherever in the file it is declared.
        assertEquals(
            "the whole file must hold exactly three " +
                "DetailHeader occurrences — the definition " +
                "and the album and playlist live calls; a " +
                "fourth, in a wrapper composable declared " +
                "outside every screen slice, hides the live " +
                "header's arguments from the per-slice " +
                "counts above while a decoy supplies them",
            3,
            occurrences(detail, "DetailHeader("),
        )
        // Round 34: the file-wide count above is a NET over
        // the whole file — it cannot tell a live screen call
        // from any other code occurrence. Proven live in
        // round 34, the three screens' headers were
        // consolidated through ONE private helper composable
        // declared inside the album slice (after AlbumScreen,
        // before the Artist section): its body held the
        // slice's one DetailHeader text with an argument
        // respelled off (actions disabled on every page),
        // both live track-screen calls were renamed to the
        // helper (their argument lines and closing shapes
        // unchanged, so the adjacencies and the R4 count
        // still read as live), and the playlist slice's one
        // counted occurrence was supplied by a decoy — a
        // comment in one run, a dead private function at the
        // end of the file in another — while the file-wide
        // total stayed 3 (definition + helper body + decoy)
        // and every assertion above stayed green across the
        // whole suite. The count must therefore be read
        // where decoys cannot stand: on the
        // literals-stripped view (a comment or raw-string
        // decoy supplies nothing), partitioned by the
        // file's own declarations (a helper body or a code
        // decoy lands in exactly one partition and breaks
        // its count), with no function definition inside
        // either screen's own partition (a cross-screen
        // header helper has to be declared somewhere, and
        // every somewhere is a partition whose count or
        // definition ban it breaks), and with each counted
        // header bound to its screen's LazyColumn header
        // item and the slices' item counts pinned (a dead
        // header item parked in a slice is an extra item).
        assertEquals(
            "AlbumScreen's slice must hold exactly one " +
                "DetailHeader occurrence outside comments " +
                "AND literals — a decoy the raw count can " +
                "see but this view cannot is not the " +
                "screen's live header",
            1,
            occurrences(
                strict
                    .substringAfter("fun AlbumScreen(")
                    .substringBefore("fun ArtistScreen("),
                "DetailHeader(",
            ),
        )
        assertEquals(
            "PlaylistScreen's slice must hold exactly one " +
                "DetailHeader occurrence outside comments " +
                "AND literals as well",
            1,
            occurrences(
                strict.substringAfter("fun PlaylistScreen("),
                "DetailHeader(",
            ),
        )
        assertEquals(
            "the whole file must hold exactly three " +
                "DetailHeader occurrences outside comments " +
                "AND literals — the definition and the two " +
                "live screen calls, in code",
            3,
            occurrences(strict, "DetailHeader("),
        )
        assertEquals(
            "exactly one DetailHeader occurrence may sit " +
                "before AlbumScreen — the definition itself; " +
                "a helper body declared at file level (the " +
                "round-33 wrapper's territory) is a second",
            1,
            occurrences(
                detail.substringBefore("fun AlbumScreen("),
                "DetailHeader(",
            ),
        )
        assertEquals(
            "AlbumScreen's own partition (the screen up to " +
                "ArtistViewModel) must hold exactly one " +
                "DetailHeader occurrence — its live call; a " +
                "helper body declared anywhere inside the " +
                "album slice is either this count's second " +
                "occurrence or another partition's first",
            1,
            occurrences(
                detail
                    .substringAfter("fun AlbumScreen(")
                    .substringBefore("class ArtistViewModel("),
                "DetailHeader(",
            ),
        )
        assertEquals(
            "no DetailHeader occurrence may sit between " +
                "ArtistViewModel and ArtistScreen — no live " +
                "header lives there, so one there is a " +
                "helper body or a decoy",
            0,
            occurrences(
                detail
                    .substringAfter("class ArtistViewModel(")
                    .substringBefore("fun ArtistScreen("),
                "DetailHeader(",
            ),
        )
        assertEquals(
            "no DetailHeader occurrence may sit inside " +
                "ArtistScreen's partition — the artist page " +
                "renders its own inline header, so a header " +
                "call there is a helper body or a decoy " +
                "paying for a consolidated live call",
            0,
            occurrences(
                detail
                    .substringAfter("fun ArtistScreen(")
                    .substringBefore("class PlaylistViewModel("),
                "DetailHeader(",
            ),
        )
        assertEquals(
            "no DetailHeader occurrence may sit between " +
                "PlaylistViewModel and PlaylistScreen either",
            0,
            occurrences(
                detail
                    .substringAfter("class PlaylistViewModel(")
                    .substringBefore("fun PlaylistScreen("),
                "DetailHeader(",
            ),
        )
        assertEquals(
            "AlbumScreen's own partition must declare no " +
                "function at all — the screen body holds " +
                "lambdas only; any `fun` declared there is " +
                "a helper (a cross-screen header helper " +
                "declared in this slice hides the live " +
                "headers' arguments behind its own body)",
            0,
            occurrences(
                detail
                    .substringAfter("fun AlbumScreen(")
                    .substringBefore("class ArtistViewModel("),
                "fun ",
            ),
        )
        assertEquals(
            "PlaylistScreen's own partition must declare " +
                "no function either — it is the file's last " +
                "declaration, so a helper or decoy function " +
                "appended after it lands in this partition",
            0,
            occurrences(
                detail.substringAfter("fun PlaylistScreen("),
                "fun ",
            ),
        )
        assertEquals(
            "AlbumScreen's live header must be the call " +
                "inside its LazyColumn header item — exactly " +
                "one item-bound DetailHeader call in the " +
                "screen's partition; a helper body at file " +
                "level or a decoy elsewhere in the slice is " +
                "not inside the item that renders",
            1,
            occurrences(
                detail
                    .substringAfter("fun AlbumScreen(")
                    .substringBefore("class ArtistViewModel("),
                "item {\n                    DetailHeader(",
            ),
        )
        assertEquals(
            "PlaylistScreen's live header must be the " +
                "call inside its LazyColumn header item too",
            1,
            occurrences(
                detail.substringAfter("fun PlaylistScreen("),
                "item {\n                    DetailHeader(",
            ),
        )
        assertEquals(
            "AlbumScreen's slice must hold exactly two " +
                "LazyColumn items — its header item and its " +
                "empty-state item; a dead header item parked " +
                "in the slice to supply the counts above is " +
                "a third (a live header cannot leave its " +
                "item: LazyColumn content renders through " +
                "items only)",
            2,
            occurrences(
                detail
                    .substringAfter("fun AlbumScreen(")
                    .substringBefore("fun ArtistScreen("),
                "item {",
            ),
        )
        assertEquals(
            "PlaylistScreen's slice must hold exactly " +
                "four LazyColumn items — header, empty " +
                "state, load-more error, and load-more; a " +
                "parked dead header item is a fifth",
            4,
            occurrences(
                detail.substringAfter("fun PlaylistScreen("),
                "item {",
            ),
        )
        // Round 35: the two declaration bans above count
        // the raw substring `fun ` — a declaration spelled
        // with a newline between `fun` and its name is
        // legal Kotlin (toy-proven) and never produces that
        // substring, so a gap helper declared that way held
        // this partition's one DetailHeader text while
        // AlbumScreen's live header routed through it
        // (proven live in round 35, green across the whole
        // suite; a function-type val cannot substitute —
        // the compiler prohibits named arguments for
        // function types, also toy-proven, and the live
        // sites call by name). Count declarations as
        // KEYWORDS on the literals-stripped partitions,
        // where no whitespace spelling can hide one.
        assertEquals(
            "AlbumScreen's own partition must declare no " +
                "function under any whitespace spelling — " +
                "the round-34 ban counted the raw `fun ` " +
                "substring, and a declaration spelled " +
                "`fun` + newline + name never produces it " +
                "while remaining a real, name-callable " +
                "helper in the gap after the screen",
            0,
            keywordCount(
                strict
                    .substringAfter("fun AlbumScreen(")
                    .substringBefore("class ArtistViewModel("),
                "fun",
            ),
        )
        assertEquals(
            "PlaylistScreen's own partition must declare " +
                "no function under any whitespace spelling " +
                "either",
            0,
            keywordCount(
                strict.substringAfter("fun PlaylistScreen("),
                "fun",
            ),
        )
        // Round 35: the item-bound counts above bind A
        // header text to an item text inside the
        // partition — proven live in round 35, the counted
        // occurrence was a gap helper's OWN item (its body
        // written at the needle's exact indentation),
        // sitting after the whole screen. Bind the counted
        // header to its site: inside each screen's
        // partition the DetailHeader occurrence must come
        // BEFORE that screen's empty-state guard, which
        // directly follows the live header item — a helper
        // declared after the screen's close sits after
        // the guard as well and cannot be the counted one.
        val albumPartition = detail
            .substringAfter("fun AlbumScreen(")
            .substringBefore("class ArtistViewModel(")
        assertTrue(
            "AlbumScreen's counted DetailHeader must sit " +
                "before its empty-state guard — the guard " +
                "directly follows the live header item, so " +
                "a header text behind it is a gap helper's " +
                "body, not the album page's live header",
            albumPartition.indexOf("DetailHeader(") <
                albumPartition.indexOf("if (page.tracks.isEmpty()) {"),
        )
        val playlistPartition = detail.substringAfter("fun PlaylistScreen(")
        assertTrue(
            "PlaylistScreen's counted DetailHeader must " +
                "sit before its empty-state guard too",
            playlistPartition.indexOf("DetailHeader(") <
                playlistPartition.indexOf("if (page.tracks.isEmpty()) {"),
        )
        // Round 35: the slice item counts above count the
        // exact substring `item {` — respelling the live
        // header item as a keyed item (`item(key = …) {`)
        // removes it from that count, and a gap helper's
        // own item pays the difference (the round-35
        // construction's second half). Count each slice's
        // items under ANY spelling on the stripped view:
        // a bare, keyed, or braceless item call is an item
        // wherever it is declared.
        assertEquals(
            "AlbumScreen's slice must hold exactly two " +
                "LazyColumn items under any spelling — a " +
                "keyed or braceless respell of the live " +
                "header item cannot free its count slot " +
                "for a gap helper's item to pay",
            2,
            Regex("\\bitem\\s*[( {]").findAll(albumSlice).count(),
        )
        assertEquals(
            "PlaylistScreen's slice must hold exactly " +
                "four LazyColumn items under any spelling",
            4,
            Regex("\\bitem\\s*[( {]").findAll(playlistSlice).count(),
        )
        // Round 36: the round-35 keyword census counts
        // `fun` — a header helper held in a function-type
        // VAL has no `fun` keyword at all. Proven live in
        // round 36, a `private val` in the gap after
        // AlbumScreen, typed as a LazyListScope extension
        // lambda, held the album partition's counted
        // DetailHeader inside its own item, its own guard
        // after it (so the round-35 guard binding held),
        // its two items paying the slice's item counts,
        // its guard paying the slice's conditional
        // inventory, and its body keeping the exact
        // actionsEnabled line (so the adjacency and the
        // R4 count held) — while AlbumScreen's live site
        // invoked it POSITIONALLY (named arguments are
        // prohibited for function types) with a doctored
        // page whose track list was emptied, disabling
        // the album header's actions on every album,
        // green across the whole suite. (The charter's
        // prescribed form — actionsEnabled = false inside
        // the val body — dies at the adjacency and the R4
        // count; the doctored positional argument is the
        // same disablement one level out.) Two claims
        // close the class: the counted header must sit
        // inside its own screen's body — before that
        // screen's trackItems call, which follows the
        // header item and the guard inside the screen; a
        // helper declared after the screen's close sits
        // after trackItems as well — and neither screen's
        // partition may name LazyListScope at all: a
        // LazyColumn-content helper must spell its
        // receiver type wherever it is declared, in the
        // gap or inside the screen, and the shipped
        // partitions never do (the one legitimate
        // LazyListScope spelling, the trackItems
        // extension, sits before AlbumScreen).
        assertTrue(
            "AlbumScreen's counted DetailHeader must sit " +
                "before its trackItems call — the header " +
                "item and its guard precede trackItems " +
                "inside the screen, so a header text behind " +
                "it is a gap helper's body (a function or a " +
                "function-type val), not the album page's " +
                "live header",
            albumPartition.indexOf("DetailHeader(") <
                albumPartition.indexOf("trackItems("),
        )
        assertTrue(
            "PlaylistScreen's counted DetailHeader must " +
                "sit before its trackItems call too",
            playlistPartition.indexOf("DetailHeader(") <
                playlistPartition.indexOf("trackItems("),
        )
        assertEquals(
            "AlbumScreen's partition must hold no " +
                "LazyListScope spelling — a LazyColumn-" +
                "content helper (the round-36 val) must " +
                "name its receiver type wherever it is " +
                "declared, and no live code in this " +
                "partition does",
            0,
            occurrences(albumPartition, "LazyListScope"),
        )
        assertEquals(
            "PlaylistScreen's partition must hold no " +
                "LazyListScope spelling either",
            0,
            occurrences(playlistPartition, "LazyListScope"),
        )
        // Round 38: the counted header can also be a
        // decoy hosted INSIDE the screen — proven live
        // in round 38, a never-invoked function-type val
        // in AlbumScreen's Content arm prologue held the
        // partition's DetailHeader text in its own item
        // (the arm already existed, so the conditional
        // inventory held; its receiver was spelled
        // through a typealias declared before
        // AlbumScreen, so the LazyListScope ban held),
        // satisfying the guard and trackItems orderings
        // above, while the live header was invoked by
        // REFERENCE (`::DetailHeader` passed to a host
        // composable declared before AlbumScreen) from
        // an `items(...)` call — a spelling neither item
        // count sees — with its Play-all and Shuffle
        // lambdas swapped at the live site, invisible to
        // every assertion above. Two claims close the
        // class: no function reference to the header may
        // appear anywhere (the live call must be spelled
        // as a call, so the file-wide count of three
        // governs it), and each counted header must sit
        // AFTER its screen's LazyColumn opening — the
        // live header item is inside the column, while a
        // prologue decoy (in the Content arm's
        // declarations, before the column) is not.
        assertEquals(
            "the file must hold no DetailHeader function " +
                "reference — a live header invoked through " +
                "::DetailHeader (passed to a host that " +
                "calls it positionally) leaves no " +
                "DetailHeader( text at its site, so the " +
                "counts above govern a decoy instead and " +
                "the live invocation's arguments are " +
                "invisible to this pin",
            0,
            occurrences(detail, "::DetailHeader"),
        )
        assertEquals(
            "no DetailHeader function reference may hide " +
                "in a comment or literal either",
            0,
            occurrences(strict, "::DetailHeader"),
        )
        assertTrue(
            "AlbumScreen's counted DetailHeader must sit " +
                "after its LazyColumn opening — the live " +
                "header item is inside the column; a decoy " +
                "held in the Content arm's prologue (before " +
                "the column) is not the album page's live " +
                "header, whatever the orderings above say",
            albumPartition.indexOf("DetailHeader(") >
                albumPartition.indexOf("LazyColumn("),
        )
        assertTrue(
            "PlaylistScreen's counted DetailHeader must " +
                "sit after its LazyColumn opening too",
            playlistPartition.indexOf("DetailHeader(") >
                playlistPartition.indexOf("LazyColumn("),
        )
        // Round 39: the reference ban and the LazyColumn
        // binding above pin the live call's form and
        // position, but its ARGUMENTS stayed unread —
        // proven live in round 39, the genuine counted
        // call in AlbumScreen (after the column opening,
        // adjacency and item counts intact) carried its
        // onPlayAll and onShuffle lambda bodies SWAPPED,
        // so the album's Play all shuffled and its
        // Shuffle played, with every assertion in this
        // pin green across the whole suite: the
        // adjacency pin reads only the actionsEnabled
        // line, and R4's file-wide count reads that same
        // line — neither names a handler. Bind the
        // handler arguments at both live sites: each
        // correct binding exactly twice (album and
        // playlist spell them identically), each crossed
        // binding never, on the raw text and on the
        // literals-stripped view (a comment or literal
        // copy supplies nothing).
        assertEquals(
            "both live headers must bind Play all to the " +
                "play-all handler — a swapped lambda at " +
                "the counted live site is invisible to " +
                "every count and ordering above",
            2,
            occurrences(
                detail,
                "onPlayAll = { playAll(container, page.tracks) }",
            ),
        )
        assertEquals(
            "both live headers must bind Shuffle to the " +
                "shuffle handler",
            2,
            occurrences(
                detail,
                "onShuffle = { shuffleAll(container, page.tracks) }",
            ),
        )
        assertEquals(
            "no live header may bind Play all to the " +
                "shuffle handler",
            0,
            occurrences(
                detail,
                "onPlayAll = { shuffleAll(container, page.tracks) }",
            ),
        )
        assertEquals(
            "no live header may bind Shuffle to the " +
                "play-all handler",
            0,
            occurrences(
                detail,
                "onShuffle = { playAll(container, page.tracks) }",
            ),
        )
        assertEquals(
            "the Play-all binding must hold outside " +
                "comments AND literals too",
            2,
            occurrences(
                strict,
                "onPlayAll = { playAll(container, page.tracks) }",
            ),
        )
        assertEquals(
            "the Shuffle binding must hold outside " +
                "comments AND literals too",
            2,
            occurrences(
                strict,
                "onShuffle = { shuffleAll(container, page.tracks) }",
            ),
        )
        assertEquals(
            "no crossed Play-all binding may hide in a " +
                "comment or literal either",
            0,
            occurrences(
                strict,
                "onPlayAll = { shuffleAll(container, page.tracks) }",
            ),
        )
        assertEquals(
            "no crossed Shuffle binding may hide in a " +
                "comment or literal either",
            0,
            occurrences(
                strict,
                "onShuffle = { playAll(container, page.tracks) }",
            ),
        )
        // Round 40: the handler bindings were the last
        // arguments this pin counted — the header's title,
        // meta, description and artwork bindings at both
        // live sites stayed unread by every pin in the tree,
        // and swapping the album site's title and meta
        // expressions (title = meta, meta = album.title —
        // both Strings, the call otherwise verbatim) ran
        // green across the full suite: the album page
        // headlined its "Artist • Year" line and demoted
        // the album title to the small meta line. Count the
        // remaining bindings exactly, raw and strict, with
        // the crossed forms at zero.
        assertEquals(
            "the album header must title itself from the album",
            1,
            occurrences(detail, "title = album.title"),
        )
        assertEquals(
            "the playlist header must title itself from the page",
            1,
            occurrences(detail, "title = page.title"),
        )
        assertEquals(
            "both headers must take the meta line from their " +
                "screen's composed meta string",
            2,
            occurrences(detail, "meta = meta"),
        )
        assertEquals(
            "the album header must show the page description",
            1,
            occurrences(detail, "description = page.description"),
        )
        assertEquals(
            "the playlist header must pass no description",
            1,
            occurrences(detail, "description = null"),
        )
        assertEquals(
            "the album header's artwork must be the album's",
            1,
            occurrences(detail, "thumbnailUrl = album.thumbnailUrl"),
        )
        assertEquals(
            "the playlist header's artwork must be the page's",
            1,
            occurrences(detail, "thumbnailUrl = page.thumbnailUrl"),
        )
        assertEquals(
            "no header may title itself from the meta line",
            0,
            occurrences(detail, "title = meta"),
        )
        assertEquals(
            "no header may demote the album title to its meta line",
            0,
            occurrences(detail, "meta = album.title"),
        )
        assertEquals(
            "no header may demote the page title to its meta line",
            0,
            occurrences(detail, "meta = page.title"),
        )
        assertEquals(
            "no header may blank its artwork with a constant",
            0,
            occurrences(detail, "thumbnailUrl = null"),
        )
        assertEquals(
            "the album title binding must hold outside " +
                "comments AND literals too",
            1,
            occurrences(strict, "title = album.title"),
        )
        assertEquals(
            "the page title binding must hold outside " +
                "comments AND literals too",
            1,
            occurrences(strict, "title = page.title"),
        )
        assertEquals(
            "the meta binding must hold outside comments " +
                "AND literals too",
            2,
            occurrences(strict, "meta = meta"),
        )
        assertEquals(
            "the album description binding must hold " +
                "outside comments AND literals too",
            1,
            occurrences(strict, "description = page.description"),
        )
        assertEquals(
            "the playlist's null description must hold " +
                "outside comments AND literals too",
            1,
            occurrences(strict, "description = null"),
        )
        assertEquals(
            "the album artwork binding must hold outside " +
                "comments AND literals too",
            1,
            occurrences(strict, "thumbnailUrl = album.thumbnailUrl"),
        )
        assertEquals(
            "the playlist artwork binding must hold " +
                "outside comments AND literals too",
            1,
            occurrences(strict, "thumbnailUrl = page.thumbnailUrl"),
        )
        assertEquals(
            "no crossed title binding may hide in a comment " +
                "or literal either",
            0,
            occurrences(strict, "title = meta"),
        )
        assertEquals(
            "no demoted album title may hide in a comment " +
                "or literal either",
            0,
            occurrences(strict, "meta = album.title"),
        )
        assertEquals(
            "no demoted page title may hide in a comment " +
                "or literal either",
            0,
            occurrences(strict, "meta = page.title"),
        )
        assertEquals(
            "no blanked artwork may hide in a comment or " +
                "literal either",
            0,
            occurrences(strict, "thumbnailUrl = null"),
        )
    }

    /**
     * Round 41: the header pins above count the DetailHeader
     * CALL's arguments, but the detail screens' shared bodies
     * below the header — trackItems' row bindings and the
     * playAll/shuffleAll helpers' own statements — were counted
     * by no pin (only shuffleAll's guard and its first statement
     * were bound, by the guard pin). Proven live in round 41:
     * the row click playing index + 1 (every tap plays the NEXT
     * track), shuffleAll with its play call deleted (the button
     * sets the mode and plays nothing), and playAll handing the
     * player a shuffled list (Play all plays out of order) each
     * ran green across the whole suite. Pin the bodies' key
     * bindings exactly, on the comment-stripped and
     * literals-stripped views like the rest of this file.
     */
    @Test
    fun trackItemsPlayAllAndShuffleAllBodiesKeepTheirKeyBindings() {
        val detail = detailScreens()
        val code = noComments(detail)
        val strict = strictCode(detail)
        assertEquals(
            "the track row click must play the row's own " +
                "index exactly once in code",
            1,
            occurrences(code, "playTracks(tracks, index)"),
        )
        assertEquals(
            "the row's own index must survive outside " +
                "comments and literals too",
            1,
            occurrences(strict, "playTracks(tracks, index)"),
        )
        assertEquals(
            "no row binding may play the next track instead",
            0,
            occurrences(code, "playTracks(tracks, index + 1)"),
        )
        assertEquals(
            "the crossed row binding must not hide in a " +
                "literal either",
            0,
            occurrences(strict, "playTracks(tracks, index + 1)"),
        )
        assertTrue(
            "the row's click / play-next / add-to-queue " +
                "bindings must stand as one block in code",
            code.contains(
                "onClick = { container.queueController.playTracks(tracks, index) },\n" +
                    "            onPlayNext = { container.queueController.playNext(it) },\n" +
                    "            onAddToQueue = { container.queueController.addToQueue(it) },",
            ),
        )
        assertEquals(
            "Play all and Shuffle must each hand the player " +
                "the page's list in order from its head — the " +
                "in-order head call occurs exactly twice in code",
            2,
            occurrences(code, "playTracks(tracks, 0)"),
        )
        assertEquals(
            "the in-order head call must occur exactly " +
                "twice outside comments and literals too",
            2,
            occurrences(strict, "playTracks(tracks, 0)"),
        )
        assertEquals(
            "no helper may hand the player a shuffled list",
            0,
            occurrences(code, "playTracks(tracks.shuffled(), 0)"),
        )
        assertTrue(
            "playAll's body must be exactly its guarded " +
                "in-order head call",
            code.contains(
                "private fun playAll(container: AppContainer, tracks: List<Track>) {\n" +
                    "    if (tracks.isNotEmpty()) container.queueController.playTracks(tracks, 0)\n" +
                    "}",
            ),
        )
        assertTrue(
            "shuffleAll's guard must be bound to BOTH of its " +
                "statements — the shuffle call and the play " +
                "call that makes the button play",
            code.contains(
                "if (tracks.isEmpty()) return\n" +
                    "    container.queueController.setShuffle(true)\n" +
                    "    container.queueController.playTracks(tracks, 0)",
            ),
        )
        assertTrue(
            "shuffleAll's full body binding must hold in " +
                "the literals-stripped view too",
            strict.contains(
                "if (tracks.isEmpty()) return\n" +
                    "    container.queueController.setShuffle(true)\n" +
                    "    container.queueController.playTracks(tracks, 0)",
            ),
        )
    }

    @Test
    fun trackItemsRowArgumentsAndAddToPlaylistLambdaStayBound() {
        // Round 42: the round-41 pin bound the row's click /
        // play-next / add-to-queue trio and both helpers' play
        // calls, but the rest of the TrackRow call stood unbound —
        // above all its add-to-playlist lambda, the row menu's
        // write path. Handing the store the page's FIRST track
        // instead of the picked one ran green across the full
        // suite (258/580) while every row's "Add to playlist"
        // filed the wrong track. The whole TrackRow argument
        // block is now bound verbatim on both views: the row's
        // own track, the trio, the playlists passthrough, the
        // add-to-playlist lambda (the picked track filed under
        // the picked playlist's id, launched in the row scope),
        // and the actions passthrough.
        val detail = detailScreens()
        val code = noComments(detail)
        val strict = strictCode(detail)
        val rowBlock =
            "        TrackRow(\n" +
                "            track = track,\n" +
                "            onClick = { container.queueController.playTracks(tracks, index) },\n" +
                "            onPlayNext = { container.queueController.playNext(it) },\n" +
                "            onAddToQueue = { container.queueController.addToQueue(it) },\n" +
                "            playlists = playlists,\n" +
                "            onAddToPlaylist = { playlist, picked ->\n" +
                "                scope.launch { container.localPlaylistStore.addTrack(playlist.id, picked) }\n" +
                "            },\n" +
                "            trackActions = trackActions,\n" +
                "        )"
        assertTrue(
            "the TrackRow argument block must stand verbatim in code",
            code.contains(rowBlock),
        )
        assertTrue(
            "the TrackRow argument block must stand verbatim " +
                "outside comments and literals too",
            strict.contains(rowBlock),
        )
        assertEquals(
            "the picked track must be filed exactly once in code",
            1,
            occurrences(code, "addTrack(playlist.id, picked)"),
        )
        assertEquals(
            "the picked-track filing must occur exactly once " +
                "outside comments and literals too",
            1,
            occurrences(strict, "addTrack(playlist.id, picked)"),
        )
        assertEquals(
            "no filing may substitute the page's first track",
            0,
            occurrences(code, "addTrack(playlist.id, tracks.first())"),
        )
        assertEquals(
            "the first-track substitution must not hide in a literal either",
            0,
            occurrences(strict, "addTrack(playlist.id, tracks.first())"),
        )
    }

    @Test
    fun trackItemsIteratesTheSameListTheHeaderAndPlayAllUse() {
        // Round 43: the round-41/42 pins bound the TrackRow
        // argument block verbatim and both helpers' play calls,
        // but nothing bound WHAT trackItems iterates — no pin
        // in the tree mentions itemsIndexed at all — and
        // neither call site's tracks argument was counted.
        // Proven live in round 43: iterating
        // tracks.drop(1) (every row shifted by one, the first
        // track never rendered, row clicks playing the track
        // BELOW the tapped row because the click index is
        // relative to the iterated list, while Play all —
        // bound to the page's own list — queued the full page)
        // and, separately, passing page.tracks.drop(1) at the
        // playlist call site each ran green across the full
        // suite (585). The iteration source, and both call
        // sites' argument, are now bound to the page's list —
        // the same list the header's Play all and Shuffle hand
        // the player.
        val detail = detailScreens()
        val code = noComments(detail)
        val strict = strictCode(detail)
        assertEquals(
            "trackItems must iterate its tracks parameter " +
                "itself exactly once in code",
            1,
            occurrences(code, "itemsIndexed(tracks) { index, track ->"),
        )
        assertEquals(
            "the iteration source must survive outside " +
                "comments and literals too",
            1,
            occurrences(strict, "itemsIndexed(tracks) { index, track ->"),
        )
        for (form in listOf(
            "itemsIndexed(tracks.drop(1))",
            "itemsIndexed(tracks.reversed())",
            "itemsIndexed(tracks.distinctBy",
            "itemsIndexed(tracks.sortedBy",
            "itemsIndexed(tracks.filter",
        )) {
            assertEquals(
                "no transformed iteration source may stand: $form",
                0,
                occurrences(code, form),
            )
            assertEquals(
                "the transformed source must not hide in a literal either: $form",
                0,
                occurrences(strict, form),
            )
        }
        assertEquals(
            "both detail screens must hand trackItems the " +
                "page's own track list — the list the header " +
                "plays — exactly twice in code",
            2,
            occurrences(code, "tracks = page.tracks"),
        )
        assertEquals(
            "the page-list handoff must occur exactly twice " +
                "outside comments and literals too",
            2,
            occurrences(strict, "tracks = page.tracks"),
        )
        assertEquals(
            "no call site may hand trackItems a transformed list",
            0,
            occurrences(code, "tracks = page.tracks.drop(1)"),
        )
    }

    private data class CommentFrame(
        val mode: String,
        val template: Boolean = false,
        var depth: Int = 0,
    )

    /**
     * Round 23: the whole text with line comments and
     * (nested) block comments removed, and everything else —
     * including string/raw/char literal CONTENTS — kept
     * verbatim. A lexical scan (string state carries across
     * lines; string-template expressions are code, so a
     * comment marker inside a literal is not a comment, and
     * a quote inside a comment starts no literal). Used so
     * the pin's needles can only be satisfied by real code.
     */
    private fun noComments(text: String): String {
        val out = StringBuilder()
        val stack = mutableListOf(CommentFrame("code"))
        var i = 0
        fun top() = stack[stack.size - 1]
        while (i < text.length) {
            val c = text[i]
            when (top().mode) {
                "string" -> {
                    when {
                        c == '\\' -> {
                            out.append(text.substring(i, minOf(i + 2, text.length)))
                            i += 2
                        }
                        c == '"' -> {
                            stack.removeAt(stack.size - 1)
                            out.append(c)
                            i++
                        }
                        c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                            stack.add(CommentFrame("code", template = true))
                            out.append("\${")
                            i += 2
                        }
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                }
                "raw" -> {
                    when {
                        c == '"' && text.startsWith("\"\"\"", i) -> {
                            stack.removeAt(stack.size - 1)
                            out.append("\"\"\"")
                            i += 3
                        }
                        c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                            stack.add(CommentFrame("code", template = true))
                            out.append("\${")
                            i += 2
                        }
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                }
                "char" -> {
                    when {
                        c == '\\' -> {
                            out.append(text.substring(i, minOf(i + 2, text.length)))
                            i += 2
                        }
                        c == '\'' -> {
                            stack.removeAt(stack.size - 1)
                            out.append(c)
                            i++
                        }
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                }
                else -> { // code
                    when {
                        c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                            while (i < text.length && text[i] != '\n') i++
                        }
                        c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                            var depth = 1
                            i += 2
                            while (i < text.length && depth > 0) {
                                if (text[i] == '/' && i + 1 < text.length && text[i + 1] == '*') {
                                    depth++
                                    i += 2
                                } else if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '/') {
                                    depth--
                                    i += 2
                                } else {
                                    if (text[i] == '\n') out.append('\n')
                                    i++
                                }
                            }
                            out.append(' ')
                        }
                        c == '"' && text.startsWith("\"\"\"", i) -> {
                            stack.add(CommentFrame("raw"))
                            out.append("\"\"\"")
                            i += 3
                        }
                        c == '"' -> {
                            stack.add(CommentFrame("string"))
                            out.append(c)
                            i++
                        }
                        c == '\'' -> {
                            stack.add(CommentFrame("char"))
                            out.append(c)
                            i++
                        }
                        c == '{' && top().template -> {
                            top().depth++
                            out.append(c)
                            i++
                        }
                        c == '}' && top().template -> {
                            if (top().depth == 0) {
                                stack.removeAt(stack.size - 1)
                            } else {
                                top().depth--
                            }
                            out.append(c)
                            i++
                        }
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                }
            }
        }
        return out.toString()
    }

    /**
     * Round 24: a whole-text lexical strip — removes line
     * comments, (nested) block comments, regular and raw
     * string contents, and char literals, while keeping code
     * (including string-template expressions) and newlines.
     * The same lexer as the R18 pin's strictCode. Where
     * [noComments] keeps literal contents verbatim (so a raw
     * string can carry a decoy block), this view blanks
     * them: a literal can supply no needle here.
     */
    private fun strictCode(text: String): String {
        val out = StringBuilder()
        val stack = mutableListOf(CommentFrame("code"))
        var i = 0
        fun top() = stack[stack.size - 1]
        while (i < text.length) {
            val c = text[i]
            when (top().mode) {
                "string" -> {
                    when {
                        c == '\\' -> i += 2
                        c == '"' -> {
                            stack.removeAt(stack.size - 1)
                            out.append('"')
                            i++
                        }
                        c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                            stack.add(CommentFrame("code", template = true))
                            i += 2
                        }
                        else -> i++
                    }
                }
                "raw" -> {
                    when {
                        c == '"' && text.startsWith("\"\"\"", i) -> {
                            stack.removeAt(stack.size - 1)
                            out.append("\"\"")
                            i += 3
                        }
                        c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                            stack.add(CommentFrame("code", template = true))
                            i += 2
                        }
                        else -> {
                            if (c == '\n') out.append('\n')
                            i++
                        }
                    }
                }
                "char" -> {
                    when {
                        c == '\\' -> i += 2
                        c == '\'' -> {
                            stack.removeAt(stack.size - 1)
                            out.append('\'')
                            i++
                        }
                        else -> i++
                    }
                }
                else -> { // code
                    when {
                        c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                            while (i < text.length && text[i] != '\n') i++
                        }
                        c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                            var depth = 1
                            i += 2
                            while (i < text.length && depth > 0) {
                                if (text[i] == '/' && i + 1 < text.length && text[i + 1] == '*') {
                                    depth++
                                    i += 2
                                } else if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '/') {
                                    depth--
                                    i += 2
                                } else {
                                    if (text[i] == '\n') out.append('\n')
                                    i++
                                }
                            }
                            out.append(' ')
                        }
                        c == '"' && text.startsWith("\"\"\"", i) -> {
                            stack.add(CommentFrame("raw"))
                            out.append("\"\"")
                            i += 3
                        }
                        c == '"' -> {
                            stack.add(CommentFrame("string"))
                            out.append(c)
                            i++
                        }
                        c == '\'' -> {
                            stack.add(CommentFrame("char"))
                            out.append(c)
                            i++
                        }
                        c == '{' && top().template -> {
                            top().depth++
                            out.append(c)
                            i++
                        }
                        c == '}' && top().template -> {
                            if (top().depth == 0) {
                                stack.removeAt(stack.size - 1)
                            } else {
                                top().depth--
                                out.append(c)
                            }
                            i++
                        }
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                }
            }
        }
        return out.toString()
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /** Round 30: whole-word keyword census on a stripped view. */
    private fun keywordCount(text: String, keyword: String): Int =
        Regex("\\b" + Regex.escape(keyword) + "\\b").findAll(text).count()

    private fun detailScreens(): String =
        mainSource("java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt")

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
                "$relativePath not found from " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
        return found.readText()
    }
}
