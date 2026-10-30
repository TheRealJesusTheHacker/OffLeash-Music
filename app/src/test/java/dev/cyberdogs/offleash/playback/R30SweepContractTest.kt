package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 30 sweep contracts (confirmation round — fresh-eyes XXIII,
 * the sign-out → sign-in-as-someone-else data-hygiene census):
 *
 * The home feed reload IS identity-keyed (HomeScreen's effect keys
 * on signedIn + identityEmail, round 1), and its sections are
 * never carried across a reload — the state resets to Loading and
 * only the new response's sections publish. The CHIPS were the
 * exception: HomeViewModel.loadFeed captured the standing
 * Content's chips before the reset and re-attached them to any
 * new response that carried none of its own. That carry exists
 * for chip re-browses (the row the user is tapping must not
 * vanish mid-tap), but load() — the entry the identity effect,
 * the first composition, and the ErrorPanel retry all use —
 * routed through the same path. On an account switch that never
 * passes through a landed signed-out feed (the round-1 switch
 * shape: the email changes, `signedIn` never flips), the previous
 * account's chips therefore rendered as the new account's chip
 * row whenever the new feed returned no chips of its own — and a
 * chip is a browse endpoint (label + browseId + params) minted by
 * the previous account's response, so tapping one browsed the
 * NEW account's home with the OLD account's endpoint.
 *
 * Fix: the carry is scoped to chip re-browses. load() passes
 * carryChips = false, so a full load starts from the new
 * response's own chips alone; onChipClick keeps the default
 * carry. (An Error state holds no Content, so the retry path
 * loses nothing: there was never a standing chip row to keep
 * after an error.)
 *
 * HomeScreen is Compose-bound, so the shape is the pin (house
 * style; CI is the compile gate). The pin lives in the playback
 * test source set, like R20SweepContractTest, so the local
 * harness runs it.
 *
 * Round 34 strengthening: round 33 asserted the capture's
 * block, counts, and ordering on the comment-stripped view
 * (noComments), which keeps literal contents verbatim —
 * proven live in round 34, a raw-string constant before
 * the reset carried the exact block on both the raw and
 * the comment-stripped views while the genuine capture
 * was renamed unconsumed and the consumed variable was
 * redeclared after the reset token-broken, green across
 * the whole suite. The same four claims are now also
 * asserted on strictCode, the literals-stripped view, in
 * which a string carrier supplies nothing.
 *
 * Round 35 strengthening: every claim in this pin is
 * file-level — proven live in round 35, a never-invoked
 * LOCAL FUNCTION declared inside loadFeed before the
 * reset, whose body holds the exact gated block as its
 * own statement, satisfies the block, both counts, and
 * the ordering on all three views (it is real code, so
 * strictCode carries it verbatim), while the genuine
 * capture is renamed unconsumed and the consumed variable
 * is redeclared after the reset token-broken, exactly as
 * in rounds 33/34 — green across the whole suite, the
 * carry dead again. The block must be a DIRECT statement
 * of loadFeed's own body: no declaration and no carrier
 * block may intervene between loadFeed's opening and the
 * capture, which the round-35 assertions below pin on
 * the strict view.
 *
 * Round 36 strengthening: the round-35 span binds the
 * capture's declaration as a direct statement of
 * loadFeed, but the capture is CONSUMED inside the load's
 * launch lambda — proven live in round 36, a token-broken
 * shadow of keptChips declared inside that lambda
 * re-read the just-reset Loading state and shadowed the
 * genuine capture for the copy call, with no carrier
 * anywhere and every prior assertion green across the
 * whole suite. The pin now also counts the name itself
 * on the strict view (exactly three namings: capture,
 * empty-check, copy call). The round-36 charter's
 * top-level dead carrier (a private function before
 * HomeViewModel holding the exact block) is DISMISSED
 * with proof in the lane report: with the genuine
 * capture renamed, the span's substringBefore finds no
 * needle after loadFeed and the prefix becomes the whole
 * remainder of the file, failing the span's fun count.
 *
 * Round 37 strengthening: the round-36 census binds the
 * copy call's argument text, but not its result — proven
 * live in round 37, a second copy chained onto the
 * genuine copy call, its argument a post-reset re-read
 * of the state (always the Loading reset inside the load
 * lambda, so always empty), discarded the carried chips
 * while the capture, the census, the guard, and the
 * copy-call needle all stood verbatim, green across the
 * whole suite. The pin now also binds the copy call as
 * the branch's final value, counts `.copy(` on the
 * literals-stripped view (exactly one in the file), and
 * bans a direct HomeFeed construction outright.
 *
 * Round 38 strengthening: the round-37 `.copy(` count
 * keys on the DOT — proven live in round 38, the branch
 * result was overridden dot-free in two forms, each
 * green across the whole suite: (a) the if-expression
 * parenthesised and `.run { copy(chips = <post-reset
 * re-read>) }` applied to its result — inside `run` the
 * receiver is `this`, so the second copy is spelled
 * without a dot and the round-37 count stayed 1 while
 * the branch's copy line and else line stood verbatim;
 * and (b) the same parenthesised result transformed by
 * a cross-file helper (`HomeFeed.withChips(...)`, an
 * extension in HomeChrome.kt whose body holds the
 * second copy — outside every span this pin read). The
 * pin now also counts `copy(` in ANY receiver form on
 * the strict view (the genuine dotted call contributes
 * the one), binds the whole Content construction, the
 * feed acquisition, and the publish as exact blocks (a
 * transformation of the branch result, of the feed
 * before the branch, or of the result before the
 * publish each breaks one of them), counts Content
 * constructions (exactly one), and asserts the sibling
 * home file holds no copy call at all.
 *
 * Round 39 strengthening: the round-38 exact blocks
 * bind four ISLANDS (capture, acquisition, Content,
 * publish) — proven live in round 39, the water
 * BETWEEN the islands was unbound: a helper declared
 * in ChartsScreen.kt (a home-package file this pin
 * never read) cleared the standing feed's chips IN
 * PLACE — the parser returns its own mutableListOf
 * instance as HomeFeed.chips, and the capture holds
 * that very instance, so clearing it empties the
 * captured keptChips without any copy call, any
 * construction, or any naming of keptChips anywhere —
 * invoked from a single inserted call line in the
 * interstitial between the capture and the reset
 * (and, in a second form, between the try result and
 * the publish, emptying the published Content's
 * chips), with every round-38 assertion green across
 * the whole suite. The pin now binds loadFeed's WHOLE
 * body: every interstitial span between the bound
 * blocks (on the literals-stripped view, compared as
 * trimmed non-empty code lines, so comments and
 * whitespace cannot hide or supply a statement) must
 * hold exactly the shipped statements and nothing
 * else — an inserted call, shadow, or scope anywhere
 * in the function adds a line and fails. As a second
 * leg, the home package holds no mutable-list cast
 * anywhere (the in-place road needs one), and the two
 * home files this pin never read hold no copy call.
 */
class R30SweepContractTest {

    @Test
    fun homeFullLoadDoesNotCarryThePreviousResponsesChips() {
        val home = homeScreen()
        assertEquals(
            "a full load must be the one call site that opts " +
                "OUT of the chip carry — it is the entry the " +
                "identity-keyed reload uses, and the previous " +
                "response's chips belong to that response's " +
                "session, not to the account now signed in",
            1,
            occurrences(home, "carryChips = false"),
        )
        assertTrue(
            "load() itself must pass the opt-out",
            home.contains("fun load() = loadFeed(chip = null, carryChips = false)"),
        )
        assertTrue(
            "loadFeed must take the carry as a parameter whose " +
                "default keeps the chip re-browse behaviour",
            home.contains("private fun loadFeed(chip: HomeChip?, carryChips: Boolean = true)"),
        )
        assertTrue(
            "the kept chips must be captured only under the " +
                "carry flag, as one exact block — an " +
                "unconditional capture restores the " +
                "cross-account chip row under this same text",
            home.contains(
                "val keptChips = if (carryChips) {\n" +
                    "            (_state.value as? UiState.Content)?.data?.chips\n" +
                    "        } else {\n" +
                    "            null\n" +
                    "        }",
            ),
        )
        // Round 31: the exact-block assertion above binds
        // the capture's TEXT, not its POSITION — proven live
        // in round 31, the gated capture moved to AFTER the
        // Loading reset kept every assertion in this pin
        // green across the whole suite while the capture
        // read the just-reset Loading state, so keptChips
        // was always null and the chip carry was silently
        // dead on the very re-browses round 30 scoped it to
        // (the mid-tap chip row vanishes again). The capture
        // is a claim about ORDER: it must read the standing
        // state before the reset replaces it. Pin that
        // order, and pin the reset's uniqueness so the
        // index comparison names one site.
        assertEquals(
            "the Loading reset must occur exactly once — " +
                "it is the point the chip capture must " +
                "precede, and a second reset site would make " +
                "the ordering claim below ambiguous",
            1,
            occurrences(home, "_state.value = UiState.Loading"),
        )
        assertTrue(
            "the kept-chips capture must run BEFORE the " +
                "Loading reset — after it, the capture reads " +
                "the reset state, keptChips is always null, " +
                "and the carry this pin exists to scope is " +
                "dead on every path",
            home.indexOf("val keptChips = if (carryChips) {") <
                home.indexOf("_state.value = UiState.Loading"),
        )
        // Round 32: the ordering above compares FIRST
        // occurrences — it binds the genuine capture's
        // position, not the value the copy call consumes.
        // Proven live in round 32: a SECOND declaration of
        // the same gated capture inside the load's launch
        // lambda (where it re-reads the just-reset Loading
        // state, so it is always null) shadowed the genuine
        // capture for the copy call inside that lambda,
        // while the genuine capture stayed first, the exact
        // block stayed present, and every count in this pin
        // stayed green — the carry dead on re-browse again.
        // The capture is a single-assignment claim: exactly
        // one declaration, and no reassignment anywhere (a
        // var-form recompute after the reset necessarily
        // rewrites the exact block above and already fails
        // there; these counts close the shadowing form,
        // which leaves the genuine text untouched).
        assertEquals(
            "keptChips must be declared exactly once — a " +
                "second declaration inside the load lambda " +
                "shadows the pre-reset capture for the copy " +
                "call that consumes it, and the shadow reads " +
                "the reset state",
            1,
            occurrences(home, "val keptChips"),
        )
        assertEquals(
            "keptChips must be assigned exactly once — a " +
                "reassignment after the Loading reset " +
                "recomputes it from the reset state whatever " +
                "the first-occurrence ordering above says",
            1,
            occurrences(home, "keptChips ="),
        )
        // Round 33: every assertion in this pin reads the
        // RAW text — proven live in round 33, a block comment
        // placed before the reset carried the gated capture's
        // exact block verbatim, supplying the exact-block
        // contains, the first-occurrence ordering, AND both
        // round-32 counts (the comment's `val keptChips` and
        // `keptChips =` are the counted ones), while the
        // genuine capture was renamed to a seed the copy call
        // never consumes and the variable the copy call DOES
        // consume was declared after the reset with its
        // tokens broken by an inline comment and a spaceless
        // assignment (`val /*carry*/ keptChips= …`), reading
        // the just-reset Loading state — the carry dead
        // again, green across the whole suite. The charter's
        // literal rename shape dies earlier (the exact-block
        // contains first, with the declaration count at 2 —
        // `val keptChipsSeed` contains the `val keptChips`
        // needle as a prefix — and the unconditional-capture
        // ban behind it); only the comment-supplied form
        // escapes, so assert the same claims on the
        // comment-stripped text, where a comment supplies
        // nothing and a token-broken declaration still does
        // not read as the gated capture.
        val code = noComments(home)
        assertTrue(
            "the gated capture's exact block must exist in " +
                "CODE — a copy inside a block comment must " +
                "not be able to stand in for a capture that " +
                "was renamed away from the copy call",
            code.contains(
                "val keptChips = if (carryChips) {\n" +
                    "            (_state.value as? UiState.Content)?.data?.chips\n" +
                    "        } else {\n" +
                    "            null\n" +
                    "        }",
            ),
        )
        assertEquals(
            "keptChips must be declared exactly once in " +
                "CODE — the counted declaration must be a " +
                "real declaration, not a comment copy, and " +
                "a token-broken second declaration reading " +
                "the reset state is not the gated capture",
            1,
            occurrences(code, "val keptChips"),
        )
        assertEquals(
            "keptChips must be assigned exactly once in CODE",
            1,
            occurrences(code, "keptChips ="),
        )
        assertTrue(
            "the kept-chips capture in CODE must run BEFORE " +
                "the Loading reset — the ordering claim on " +
                "the stripped text, so a comment copy placed " +
                "early cannot satisfy it for a real capture " +
                "placed after the reset",
            code.indexOf("val keptChips = if (carryChips) {") <
                code.indexOf("_state.value = UiState.Loading"),
        )
        // Round 34: the comment-stripped view above is the
        // R21 pin's noComments — it keeps literal CONTENTS
        // verbatim, by design. Proven live in round 34, the
        // round-33 construction works one level down: a
        // raw-string constant declared before the reset
        // carried the gated capture's exact block verbatim
        // (supplying the exact-block contains, the
        // first-occurrence ordering, and both counts on
        // BOTH the raw text and the comment-stripped view,
        // whose raw-string mode keeps the carrier's
        // contents), while the genuine capture was renamed
        // to an unconsumed seed and the consumed variable
        // was redeclared after the reset token-broken, as
        // in round 33 — green across the whole suite, the
        // carry dead again. Assert the same four claims on
        // strictCode, the literals-stripped view (the R21
        // pin's round-24 lexer): a string carrier
        // contributes only newlines there, so the block,
        // the counts, and the ordering can only be
        // satisfied by the capture itself, in code. (The
        // gated block holds no literal, so the strict view
        // carries it verbatim, as it does the reset.)
        val strict = strictCode(home)
        assertTrue(
            "the gated capture's exact block must exist " +
                "outside comments AND literals — a copy " +
                "inside a raw string must not be able to " +
                "stand in for a capture renamed away from " +
                "the copy call",
            strict.contains(
                "val keptChips = if (carryChips) {\n" +
                    "            (_state.value as? UiState.Content)?.data?.chips\n" +
                    "        } else {\n" +
                    "            null\n" +
                    "        }",
            ),
        )
        assertEquals(
            "keptChips must be declared exactly once " +
                "outside comments AND literals — the " +
                "counted declaration must be the capture " +
                "itself, not a string carrier placed " +
                "before the reset",
            1,
            occurrences(strict, "val keptChips"),
        )
        assertEquals(
            "keptChips must be assigned exactly once " +
                "outside comments AND literals",
            1,
            occurrences(strict, "keptChips ="),
        )
        assertTrue(
            "the kept-chips capture outside comments AND " +
                "literals must run BEFORE the Loading " +
                "reset — the ordering claim on the " +
                "literals-stripped text, so a string " +
                "carrier placed early cannot satisfy it " +
                "for a real capture placed after the reset",
            strict.indexOf("val keptChips = if (carryChips) {") <
                strict.indexOf("_state.value = UiState.Loading"),
        )
        // Round 35: the claims above are file-level, so a
        // carrier that is real CODE satisfies them all —
        // proven live in round 35, the exact block sat as
        // the body statement of a never-invoked local
        // function declared inside loadFeed before the
        // reset, while the genuine capture was renamed
        // unconsumed and the consumed variable was
        // redeclared after the reset token-broken (the
        // rounds-33/34 spellings): every view's block,
        // counts, and ordering stayed green and the carry
        // was dead. Bind the block to its site: between
        // loadFeed's opening and the capture there may be
        // no declaration (a local fun or a lambda-holding
        // val is a carrier's header) and no carrier block
        // (the only brace in that span is loadFeed's own
        // opening brace, the last character of its
        // signature line).
        val loadFeedPrefix = strict
            .substringAfter("private fun loadFeed(")
            .substringBefore("val keptChips = if (carryChips) {")
        assertEquals(
            "no function declaration may intervene between " +
                "loadFeed's opening and the kept-chips " +
                "capture — a local function declared there " +
                "can carry the exact gated block as its own " +
                "statement while the capture the copy call " +
                "consumes is rebound after the reset",
            0,
            keywordCount(loadFeedPrefix, "fun"),
        )
        assertEquals(
            "no value declaration may intervene between " +
                "loadFeed's opening and the kept-chips " +
                "capture either — a declared lambda is the " +
                "same dead carrier under a val header",
            0,
            occurrences(loadFeedPrefix, "val "),
        )
        assertEquals(
            "the only opening brace between loadFeed's " +
                "opening and the capture is loadFeed's own " +
                "— any carrier body (a local function, a " +
                "lambda, a run block) opens a second one " +
                "before the block it carries",
            1,
            occurrences(loadFeedPrefix, "{"),
        )
        // Round 36: the span above binds the capture's
        // DECLARATION, but its consumption lives inside the
        // load's launch lambda — proven live in round 36, a
        // token-broken shadow of keptChips declared inside
        // that lambda (`val /*carry*/ keptChips= …`, the
        // rounds-33/34 spelling, which no count above can
        // see) re-read the just-reset Loading state and
        // shadowed the genuine capture for the copy call,
        // the carry dead again with no carrier anywhere and
        // every round-35 assertion green across the whole
        // suite. The name itself is the claim: outside
        // comments and literals it may occur exactly three
        // times — the declaration, the isNullOrEmpty guard,
        // and the copy call. A shadow under any spelling
        // (token-broken, comment-split, spaceless) still
        // spells the name in code and is a fourth.
        assertEquals(
            "keptChips must be named exactly three times " +
                "outside comments AND literals — the capture, " +
                "the empty-check, and the copy call; a fourth " +
                "naming is a shadow declaration inside the " +
                "load lambda (under a token-broken spelling " +
                "no declaration count above can see) that " +
                "re-reads the reset state and steals the " +
                "copy call from the genuine capture",
            3,
            occurrences(strict, "keptChips"),
        )
        // Round 37: the census and the copy-call needle bind
        // the copy call's ARGUMENT, not its RESULT — proven
        // live in round 37, the genuine copy call stood
        // verbatim (census 3, copy needle 1, guard exact)
        // while its result was immediately re-copied inside
        // the same branch with a post-reset re-read
        // (`feed.copy(chips = keptChips).copy(chips =
        // (_state.value as? …)?.chips ?: emptyList())`):
        // inside the lambda the state is the Loading reset,
        // so the override always lands empty and the carry
        // is dead with every round-36 assertion green across
        // the whole suite. Bind the result: the copy call
        // must be the branch's final value (it closes
        // directly into the else), it must be the file's
        // only `.copy(` anywhere outside comments and
        // literals, and no direct HomeFeed construction may
        // stand in for a copy-based override either.
        assertTrue(
            "the kept-chips copy must be the branch's final " +
                "value — a chained or subsequent override " +
                "copy discards the carried chips after the " +
                "pinned copy call has produced them",
            home.contains(
                "feed.copy(chips = keptChips)\n" +
                    "                    } else {",
            ),
        )
        assertEquals(
            "the kept-chips copy must be the only `.copy(` " +
                "in the file outside comments AND literals — " +
                "a second copy, chained or sequential, in any " +
                "line-breaking is the round-37 override that " +
                "replaces the carried chips with a post-reset " +
                "re-read",
            1,
            occurrences(strict, ".copy("),
        )
        assertEquals(
            "no direct HomeFeed construction may appear in " +
                "the file outside comments AND literals — " +
                "constructing the feed anew is the same " +
                "override without a copy call",
            0,
            occurrences(strict, "HomeFeed("),
        )
        // Round 38: the `.copy(` count above keys on the
        // dot, and the final-value binding keys on the two
        // lines it spans — proven live in round 38, the
        // branch result was overridden with NO second
        // dotted copy anywhere: the if-expression
        // parenthesised and a dot-free `copy(...)` applied
        // to its result from inside a `run` block (the
        // receiver is `this` there, so the call is spelled
        // bare), and in a second form an extension helper
        // in HomeChrome.kt applied to the same
        // parenthesised result. Both kept the copy line,
        // the else line, the census, and the `.copy(`
        // count verbatim and ran green across the whole
        // suite. Count the call in any receiver form, and
        // bind every site a transformation would have to
        // touch: the feed acquisition, the whole Content
        // construction, and the publish.
        assertEquals(
            "the kept-chips copy must be the only `copy(` " +
                "in the file outside comments AND literals, " +
                "in ANY receiver form — a dot-free copy " +
                "inside a run/let/with block applied to the " +
                "branch result (the round-38 override) is a " +
                "second copy call the dotted count cannot " +
                "see; the genuine dotted call contributes " +
                "the one",
            1,
            occurrences(strict, "copy("),
        )
        assertTrue(
            "the Content construction must be exactly the " +
                "shipped block — the branch if-expression " +
                "as the direct argument, closing straight " +
                "into the call's close; a parenthesised " +
                "branch result with a transformation applied " +
                "(a run block, a helper extension) rewrites " +
                "the lines around the branch and breaks " +
                "this block even though the branch's own " +
                "lines stand verbatim",
            home.contains(
                "UiState.Content(\n" +
                    "                    if (feed.chips.isEmpty() && !keptChips.isNullOrEmpty()) {\n" +
                    "                        feed.copy(chips = keptChips)\n" +
                    "                    } else {\n" +
                    "                        feed\n" +
                    "                    },\n" +
                    "                )",
            ),
        )
        assertEquals(
            "exactly one Content construction may appear " +
                "in the file — a second construction after " +
                "the branch (re-wrapping a transformed feed " +
                "for the publish) is the sequential form of " +
                "the same override",
            1,
            occurrences(home, "UiState.Content("),
        )
        assertTrue(
            "the feed must be acquired exactly as shipped " +
                "— the client response as the direct value; " +
                "a transformation applied at the acquisition " +
                "(a helper call on the response before the " +
                "branch ever sees it) rewrites this block",
            home.contains(
                "val feed = if (bearer != null) {\n" +
                    "                    client.homeFeedOAuth(bearer, chip)\n" +
                    "                } else {\n" +
                    "                    client.homeFeed(auth, chip)\n" +
                    "                }",
            ),
        )
        assertTrue(
            "the publish must hand the try's result to the " +
                "state exactly as shipped — a transformed " +
                "value published instead (the sequential " +
                "override's last step) rewrites this block",
            home.contains(
                "if (isActive) {\n" +
                    "                _state.value = result\n" +
                    "            }",
            ),
        )
        assertEquals(
            "the sibling home file must hold no `copy(` " +
                "outside comments AND literals either — the " +
                "round-38 helper form hid its second copy " +
                "in HomeChrome.kt as a HomeFeed extension, " +
                "outside every span this pin reads; that " +
                "file ships no copy call at all",
            0,
            occurrences(
                strictCode(
                    mainSource(
                        "java/dev/cyberdogs/offleash/ui/home/HomeChrome.kt",
                    ),
                ),
                "copy(",
            ),
        )
        // Round 39: the exact blocks above are islands —
        // proven live in round 39, a one-line call inserted
        // in the water between them (`retireStandingChips(
        // _state.value)`, a helper in ChartsScreen.kt that
        // clears the standing feed's chips list in place —
        // the parser hands its mutableListOf instance
        // through as HomeFeed.chips, and the capture holds
        // that instance) emptied the captured keptChips
        // with no copy call, no construction, and no
        // naming of keptChips, green across the whole
        // suite; the same helper invoked between the try
        // result and the publish emptied the published
        // Content's chips instead. Bind the whole body:
        // every span between the bound blocks, compared
        // on the literals-stripped view as trimmed
        // non-empty code lines, must hold exactly the
        // shipped statements — any inserted statement
        // (call, shadow, scope, second launch) anywhere
        // in loadFeed adds or changes a line and fails.
        val captureBlock =
            "val keptChips = if (carryChips) {\n" +
                "            (_state.value as? UiState.Content)?.data?.chips\n" +
                "        } else {\n" +
                "            null\n" +
                "        }"
        assertEquals(
            "between loadFeed's opening and the capture " +
                "there may be NO statement at all — the " +
                "round-35 census bans declarations and " +
                "carrier braces there, but a bare mutating " +
                "call on the standing state (round 39) is " +
                "neither, and it empties the chips before " +
                "the capture ever reads them",
            listOf(
                "chip: HomeChip?, carryChips: Boolean = true) {",
            ),
            codeLines(loadFeedPrefix),
        )
        assertEquals(
            "between the capture and the Loading reset " +
                "only the selected-chip assignment may " +
                "stand — an inserted call there mutates " +
                "the captured list in place before the " +
                "load lambda ever runs (round 39)",
            listOf("_selectedChip.value = chip"),
            codeLines(
                strict.substringAfter(captureBlock)
                    .substringBefore("_state.value = UiState.Loading"),
            ),
        )
        assertEquals(
            "between the reset and the feed acquisition " +
                "only the shipped launch prologue may " +
                "stand — an inserted statement there (a " +
                "second launch, a mutating call) rides " +
                "the same unbound water",
            listOf(
                "loadJob?.cancel()",
                "loadJob = viewModelScope.launch(Dispatchers.IO) {",
                "val auth = sessionProvider()",
                "val result = try {",
                "val bearer = bearerProvider()",
            ),
            codeLines(
                strict.substringAfter("_state.value = UiState.Loading")
                    .substringBefore("val feed = if (bearer != null) {"),
            ),
        )
        assertEquals(
            "the Content construction must follow the " +
                "feed acquisition directly — no statement " +
                "may intervene (a shadow of the feed's " +
                "name would need a scope, and any scope " +
                "or statement here is the transformation " +
                "slot the round-38 blocks left open)",
            emptyList<String>(),
            codeLines(
                strict.substringAfter(
                    "val feed = if (bearer != null) {\n" +
                        "                    client.homeFeedOAuth(bearer, chip)\n" +
                        "                } else {\n" +
                        "                    client.homeFeed(auth, chip)\n" +
                        "                }",
                ).substringBefore("UiState.Content("),
            ),
        )
        assertEquals(
            "between the Content construction and the " +
                "publish only the shipped catch may stand " +
                "— an inserted call on the try's result " +
                "there mutates the constructed Content's " +
                "chips in place before it publishes " +
                "(round 39's second form)",
            listOf(
                "} catch (e: Exception) {",
                "if (e is kotlinx.coroutines.CancellationException) throw e",
                "UiState.Error(ErrorMessages.userMessage(e))",
                "}",
            ),
            codeLines(
                strict.substringAfter(
                    "UiState.Content(\n" +
                        "                    if (feed.chips.isEmpty() && !keptChips.isNullOrEmpty()) {\n" +
                        "                        feed.copy(chips = keptChips)\n" +
                        "                    } else {\n" +
                        "                        feed\n" +
                        "                    },\n" +
                        "                )",
                ).substringBefore("if (isActive) {"),
            ),
        )
        assertEquals(
            "after the publish, loadFeed and its class " +
                "must close with no further statement — a " +
                "trailing call is the same unbound water " +
                "at the function's tail",
            listOf("}", "}", "}"),
            codeLines(
                strict.substringAfter(
                    "if (isActive) {\n" +
                        "                _state.value = result\n" +
                        "            }",
                ).substringBefore("@Composable"),
            ),
        )
        // Round 39, second leg: the in-place road needs a
        // mutable-list cast somewhere, and the two home
        // files this pin never read are where the round-38
        // helper hid. The whole home package ships no
        // such cast, and neither unread file holds a copy
        // call (HomeChrome is bound above).
        for (sibling in listOf("ChartsScreen.kt", "MoodsScreen.kt")) {
            assertEquals(
                "the unread home file $sibling must hold " +
                    "no `copy(` outside comments AND " +
                    "literals — the round-38 cross-file " +
                    "helper road runs through exactly the " +
                    "files this pin does not read",
                0,
                occurrences(
                    strictCode(
                        mainSource(
                            "java/dev/cyberdogs/offleash/ui/home/$sibling",
                        ),
                    ),
                    "copy(",
                ),
            )
        }
        for (homeFile in listOf(
            "HomeScreen.kt", "HomeChrome.kt",
            "ChartsScreen.kt", "MoodsScreen.kt",
        )) {
            val strictFile = strictCode(
                mainSource(
                    "java/dev/cyberdogs/offleash/ui/home/$homeFile",
                ),
            )
            assertEquals(
                "no home file may cast to MutableList — " +
                    "the parser's chips list is mutable " +
                    "under its List type, and an in-place " +
                    "clear of the captured list is the " +
                    "round-39 override no copy count can " +
                    "see ($homeFile)",
                0,
                occurrences(strictFile, "MutableList"),
            )
            assertEquals(
                "no home file may cast to ArrayList " +
                    "either — the same in-place road under " +
                    "the concrete type's name ($homeFile)",
                0,
                occurrences(strictFile, "ArrayList"),
            )
        }
        assertFalse(
            "the pre-round-30 unconditional capture must not " +
                "survive anywhere in the file",
            home.contains(
                "val keptChips = (_state.value as? UiState.Content)?.data?.chips",
            ),
        )
        // The same-feed re-browse keeps its carry: onChipClick
        // routes both taps through loadFeed WITHOUT the opt-out.
        val chipClick = home
            .substringAfter("fun onChipClick(chip: HomeChip)")
            .substringBefore("private fun loadFeed")
        assertTrue(
            "tapping the selected chip again must still reload " +
                "the full feed through loadFeed",
            chipClick.contains("loadFeed(chip = null)"),
        )
        assertTrue(
            "tapping a chip must still browse it through loadFeed",
            chipClick.contains("loadFeed(chip = chip)"),
        )
        assertFalse(
            "onChipClick must not opt out of the carry — the " +
                "row it renders is the row being tapped",
            chipClick.contains("carryChips"),
        )
        // The carry's landing shape is unchanged: kept chips are
        // used only when the new response brings none of its own.
        assertEquals(
            "the kept chips must land in exactly one place",
            1,
            occurrences(home, "feed.copy(chips = keptChips)"),
        )
        assertTrue(
            "kept chips may only fill an empty chip cloud",
            home.contains("if (feed.chips.isEmpty() && !keptChips.isNullOrEmpty())"),
        )
        // And the identity keying this fix rides on must stand:
        // the reload effect keys on the signed-in flag AND the
        // account's email, exactly once.
        assertEquals(
            "the home reload must stay identity-keyed",
            1,
            occurrences(
                home,
                "LaunchedEffect(sessionState.signedIn, sessionState.identityEmail)",
            ),
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
     * Round 34: a whole-text lexical strip — removes line
     * comments, (nested) block comments, regular and raw
     * string contents, and char literals, while keeping code
     * (including string-template expressions) and newlines.
     * The same lexer as the R21 pin's strictCode. Where
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
                            out.append("\"\"\"")
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

    /**
     * Round 39: a source span as its trimmed, non-empty
     * code lines — the comparison form for the whole-body
     * binding, immune to comment and whitespace drift (on
     * the literals-stripped view a comment leaves only
     * blank lines and a single space, both filtered here)
     * while any real statement survives as a line.
     */
    private fun codeLines(span: String): List<String> =
        span.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Round 35: whole-word keyword census on a stripped view. */
    private fun keywordCount(text: String, keyword: String): Int =
        Regex("\\b" + Regex.escape(keyword) + "\\b").findAll(text).count()

    private fun homeScreen(): String =
        mainSource("java/dev/cyberdogs/offleash/ui/home/HomeScreen.kt")

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
