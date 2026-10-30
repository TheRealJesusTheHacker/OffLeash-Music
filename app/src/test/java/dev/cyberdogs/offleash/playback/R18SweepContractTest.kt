package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 18 sweep contracts (confirmation round — breaking round
 * 17's fixes):
 *
 * Round 17 cancelled an in-flight resolve at all three
 * end-of-track sleep-stop branches. Its core claim — the stopped
 * resolve no longer lands and plays — holds. Its flag claims do
 * not, in the both-in-flight case:
 *
 * 1. The continuation branch is NOT equivalent to its siblings.
 *    onTrackEnded's and the repeat twin's branches route their
 *    clear through the orphan helper, which defers to a live
 *    radio fetch (whose landing, staled by the branch's epoch
 *    bump, then releases the flag). The continuation branch's
 *    terminal clear was an unconditional setResolving(false):
 *    with a radio fetch also in flight it dropped the loading
 *    state under that fetch at the stop, instead of letting the
 *    stale radio landing release it. Fix: the terminal clear is
 *    guarded by the radio fetch's inactivity, exactly the
 *    helper's deferral.
 *
 * 2. The cancelled resolve's own cancellation catch defeated
 *    the helper's deferral at the OTHER two branches. The
 *    helper sees the cancelled resolve inactive and defers to
 *    a live radio — but the resolve's catch, on identity alone,
 *    cleared the flag when its cancellation was delivered, radio
 *    or no radio. Fix: the catch's clear gains the same two
 *    deferral legs as every other guarded clear (a live
 *    continuation or a live radio keeps the flag). Every other
 *    cancelling site either supersedes the job (identity leg)
 *    or clears the flag itself in the same segment (the timed
 *    expiry, the transport stop), so the deferral strands the
 *    flag nowhere; at the continuation branch the still-running
 *    branch's own terminal clear owns the release.
 *
 * QueueController is Media3-bound, so the shape is the pin
 * (house style; CI is the compile gate).
 *
 * Round 19 strengthening (both pins): round 18 asserted the
 * guard/legs as presence (+ one ordering) over wide slices.
 * Mutation runs in round 19 showed both pins green under
 * behavior-changing relocations — the F1 guard moved to wrap
 * the branch's disarm with the clear unconditional again, and
 * the catch's second && narrowed to ||. Both pins now assert
 * the exact guarded block in place (and, for F1, its placement
 * before the branch's return, against a dead-code relocation).
 *
 * Round 20 strengthening (both pins): round 19's exact-block
 * contains is a presence claim — mutation runs in round 20
 * showed both pins green with (a) the exact block kept AND a
 * live unconditional clear added beside it (for F1 respelled
 * `setResolving( false )`, which the literal count cannot see;
 * for F2 in the original spelling, the catch pin having no
 * count at all), restoring the very bugs the blocks guard, and
 * (b) the exact block wrapped in `if (false)` — present,
 * ordered, and dead, stranding the flag. Both pins now also
 * count the call stem in-slice (exactly one clear call of any
 * spelling), forbid the concrete inert wrapper, and forbid a
 * direct _playbackState write as an alternate clear spelling.
 *
 * Round 21 strengthening (both pins): round 20's stem count
 * counts only calls SPELLED setResolving(. Mutation runs in
 * round 21 showed both pins green with a second unconditional
 * clear inside each slice routed through a NEW private helper
 * defined outside the slice (`clearResolvingNow()`, and again
 * under the arbitrary name `releaseFlagNow()` — no stem count
 * of any fixed spelling can see an arbitrarily-named helper),
 * and green with the exact blocks dead-wrapped in
 * `when (false) { … }`, in `val never = false; if (never) { … }`,
 * and in `if (1 == 2) { … }` (the always-false residual round
 * 20 documented for shapes beyond `if (false)`). Both pins
 * now assert the slice's complete CODE inventory (comments
 * stripped): exactly the guard's one conditional and no other
 * conditional construct of any kind, no local function
 * definition, and exactly the calls the slice legitimately
 * makes today — so any added call, under any name, and any
 * added conditional wrapper, of any shape, fails the pin.
 * Residual (documented, proven in round 21): a clear
 * PIGGYBACKED onto the body of a helper the slice already
 * legitimately calls (round 21 added setResolving(false) to
 * cancelSleepTimer()'s body) leaves the slice byte-identical
 * and is beyond ANY slice-local text pin in principle — the
 * same documented-residual class as round 9's schema residual
 * and round 20's note; closing it would require pinning every
 * transitively called body, recursively.
 *
 * Round 22: the piggyback residual is CLOSED for the F1 pin,
 * and the comment stripper is hardened. (1) Piggyback census:
 * of the F1 branch's seven calls, exactly TWO are hosts that
 * can carry a piggybacked clear — cancelSleepTimer() and
 * syncPlaybackState(), both QueueController members (round 22
 * proved the syncPlaybackState piggyback live too, in two
 * spellings: an added setResolving(false), and the snapshot
 * rebuild's carry-over respelled `isResolving = false`). The
 * rest cannot host one: sleepTimerShouldStopAtTrackEnd is a
 * pure top-level function with no controller receiver,
 * diagnostics.log lives on a separate object holding no
 * controller state, and player.pause / resolveJob?.cancel are
 * library interfaces with no body in this tree. The transitive
 * closure TERMINATES at depth 1: cancelSleepTimer's body calls
 * only Job.cancel (library) and the body-less SleepTimerState
 * constructor; syncPlaybackState's body calls only the
 * body-less PlaybackUiState constructor, Long.coerceAtLeast
 * (stdlib), and Media3 Player property reads. The F1 pin now
 * pins BOTH host bodies (below). The F2 catch has no host at
 * all — its only calls are currentCoroutineContext (stdlib)
 * and setResolving itself — so its round-21 closure stands.
 * (2) Stripper hardening: round 22 showed the line-wise
 * codeOnly stripper EATS real code in two shapes — a call
 * after a `//` that sits inside a block comment on the same
 * line (`/* note // note */ clearResolvingNow()`), and a call
 * after a raw string's closing quotes on a line whose raw
 * content contains `//` — hiding an in-slice helper call from
 * the inventory under the old pins (both mutations green).
 * Both pins now ALSO assert the inventories on strictCode, a
 * whole-text lexer that strips line AND (nested) block
 * comments, regular/raw string contents, and char literals,
 * tracking templates as code. A fake call stem inside a
 * string remains fail-closed via the legacy count (round 22
 * verified), and no balancing pair exists: the counters a
 * fake stem inflates (raw stem count, inventory list) are not
 * the counter a hidden helper call removes (its own name).
 *
 * Round 23 strengthening (both pins): round 22's closures had
 * two holes left. (1) SLICE-ANCHOR GAMES: every slice above is
 * cut by substringAfter/substringBefore on anchor text, and
 * the raw-text search cannot tell code from prose — an anchor
 * duplicated inside a `//` comment in the host body (a fake
 * `private fun expireSleepTimer()` line inside
 * cancelSleepTimer, a fake `private companion object` line
 * inside syncPlaybackState) or inside a raw string in the
 * branch (a fake `val timer = …` content line) became the
 * slice's end, and a real piggybacked/unconditional
 * setResolving(false) placed after the fake anchor, still
 * inside the real body/branch, sat outside the slice while
 * every assertion stayed green (all three proven live in
 * round 23, as was the same game on the catch's end anchor).
 * Both pins now assert ANCHOR INTEGRITY: every delimiter
 * they slice on occurs exactly as many times in the raw
 * controller text as in strictCode's comment/string-stripped
 * text — an anchor occurrence hiding in a comment or a
 * literal inflates only the raw count and fails the pin.
 * (2) CALLEE-SHAPE BLINDNESS: callInventory's regex only saw
 * `name(` — a second clear routed through an outside helper
 * under any other valid Kotlin callee shape (a backticked
 * call `` `helper`() ``, an explicit-type-argument call
 * `helper<Boolean>(…)`, an indexed callable `helpers[0](…)`,
 * a parenthesised callable value `(helperVal)(…)`) was
 * invisible to BOTH strippers' inventories (all four proven
 * live in round 23; the shapes compile and execute).
 * callInventory now strips backticks before scanning and
 * also matches the generic, indexed, and parenthesised
 * callee shapes, so an added call under any of them enters
 * the inventory and fails the pin.
 *
 * Round 24 strengthening (both pins): round 23's two
 * closures had three holes left, all proven live in round
 * 24. (1) ANCHOR INTEGRITY WAS EQUALITY-ONLY: it required
 * raw and strict counts to AGREE, so a fake anchor that
 * BOTH views count — the end anchor spelled as code inside
 * a string-template hole (`"${run { val timer = …; timer }}"`,
 * whose hole strictCode lexes as code and therefore keeps)
 * or simply as a duplicated plain code statement
 * (`val timer = …` inserted in the branch after the guarded
 * block) — kept the counts equal (3/3) while the raw slice
 * still truncated at the fake and hid an unconditional
 * clear behind it (both constructions green). The pins now
 * also assert each anchor's EXACT whole-file occurrence
 * count, measured on the shipped file (val timer 2,
 * handToPlayer( 3, the catch anchors 7 and 9, the rest 1):
 * any extra occurrence — comment, literal, template hole,
 * or duplicated statement — breaks the count. (2) CALLEE
 * SHAPES ROUND 2: the widened regex still missed a
 * trailing-lambda call (`helper { }` — no parentheses at
 * all), an infix call (`1 helper 2`), and a parenthesised
 * CALLABLE REFERENCE (`(::helper)()` — the `::` breaks the
 * parenthesised-callee pattern, which expects a chain
 * right after the paren); each routed a second clear
 * through an outside helper with both inventories intact
 * (all three green; validity proven by a compiled toy, as
 * were the shapes that still fail: `helperVal.invoke()`
 * records the chain `helperVal.invoke`, and
 * `listOf(::helper).forEach { it() }` records listOf/it).
 * callInventory now also records trailing-lambda callees
 * (a chain directly followed by `{`) and callable
 * references (`::name` — a reference occurrence in these
 * slices has no legitimate use and is recorded wherever
 * it appears), and both pins additionally assert the
 * strict views hold NO infix-position identifier (an
 * infix call's operator name sits between two operands;
 * the strict view is used because its blanked literals
 * keep string prose out of the pattern). (3) The artist
 * pin's raw-string decoy is the R21 file's round-24 fix.
 *
 * Round 25 strengthening (both pins + the inventory):
 * round 24's exact whole-file counts are NETS, and a net
 * survives compensation — a fake anchor added in the slice
 * balanced by a legitimate occurrence of the same delimiter
 * respelled away elsewhere. Proven live in round 25 twice:
 * a duplicated `val timer …` inside the branch hid an
 * unconditional clear while the polling loop's identical
 * statement was respelled as a typed declaration (count
 * stayed 2), and a fake `catch (e: Exception)` heading an
 * empty try inside the cancellation catch hid an
 * unconditional clear while the radio fetch's catch
 * parameter was renamed (its `null` body never used the
 * name; count stayed 9). The F1 pin now also counts the
 * end anchor's two legitimate CONTEXTS (the timed branch's
 * following condition; the polling line's following
 * statement), and the F2 pin counts both catch anchors
 * INSIDE the resolve body (exactly one each), where a
 * compensation elsewhere cannot reach. Census result for
 * the remaining delimiters: every other anchor's sole
 * occurrence IS the sliced landmark itself (the two host
 * definitions, the companion, resolveAndPlay, the stream
 * try) or, for `handToPlayer(`, a removable call sits in a
 * region no protected clear can be hidden from — the
 * catch sub-slice is delimited by the catches, not by the
 * hand-off; no compensation construction exists for them
 * (full census in the round-25 lane report). Round 24
 * also left three trailing-lambda callee shapes
 * unrecorded — an indexed callable (`helpers[0] { }`), a
 * generic callee (`helper<Unit> { }`), and a parenthesised
 * callable value (`(helperVal) { }`) each routed a clear
 * past both inventories (green; execution proven by a
 * compiled toy); the member-chain form
 * (`gate.clearResolvingNow { }`) and bound references
 * (`this::helper`) were re-verified RECORDED (full chain /
 * bare name) and fail. callInventory's trailing-lambda
 * pattern now allows the generic and indexed segments
 * before the brace, and a parenthesised trailing-lambda
 * callee is recorded as well.
 *
 * Round 26 strengthening (both pins + the inventory):
 * round 25's context counts are nets too — one level up.
 * Proven live in round 26: a fake end anchor inside the
 * branch carrying the timed branch's exact following line
 * supplied the end context, a dead `if (false)` pair
 * supplied the polling context, and BOTH legitimate
 * anchors were respelled as typed declarations — every
 * round-25 count held while the unconditional clear sat
 * in the branch. The F1 pin now also asserts the whole
 * branch REGION (branch start to the continuation
 * computation): its clear-stem count, its conditional
 * count, and its complete call inventory — an added
 * clear anywhere between the branch and the timed twin
 * lands in that region whatever anchor games are played
 * at its edges. The F2 pin's slice-local catch counts
 * fell the same way from INSIDE the slice: a fake catch
 * in the cancellation catch was paid for by renaming the
 * resolve body's OWN failure catch (parameter plus its
 * single use, `userMessage(e)` -> `userMessage(ex)`);
 * the pin now also counts both catches' opening contexts
 * inside the body and asserts the body's clear-stem
 * count and full inventory. And the inventory itself had
 * a keyword-shaped hole: callInventory strips backticks
 * and then FILTERS keyword names, so a callee whose name
 * is a backticked keyword (`else`, `finally`) was
 * recorded and then discarded — a call of its result
 * (`else`()(), `finally`()({ })) executed an outside
 * clearer while the inventory recorded nothing (plain
 * `factory()()` and a `with(...)` receiver shape were
 * re-verified RECORDED and fail). Backticked identifiers
 * are now substituted with a `bt_` prefix before
 * scanning, so a keyword-spelled callee is recorded
 * under a name no filter drops.
 *
 * Round 27 strengthening (both items in this class):
 * (1) The round-26 region assertions count calls and
 * conditionals but never bind a CONDITION to its site.
 * Proven live in round 27: the sleep branch's guard and
 * the timed branch's condition exchanged meanings — the
 * guard took a deadline/remaining property expression
 * (the stop check kept only as an `&& false` dead leg,
 * which preserves its inventory token), and the timed
 * branch took `timer.endOfTrack` (the stop check kept
 * only as a dead `sleepTimerShouldPause(...) && false`
 * leg, which preserves its tokens and the round-25
 * context line's first leg) — while every stem count,
 * conditional count, context count, inventory, and the
 * sibling clock/R16/R17 pins stayed green across the
 * whole suite: an end-of-track stop then routes through
 * expireSleepTimer (no resolve cancel), and a timed
 * expiry inside the polling cadence routes through
 * neither branch and autoplay continues. The F1 pin now
 * also asserts both conditions' exact shipped text, in
 * place, exactly once each. (2) The `bt_` substitution
 * token COLLIDES with a plain callee literally named
 * `bt_else`: the two spellings coexist in one file and
 * execute differently, yet the inventory recorded both
 * slices as the same token (compiled toy, round 27).
 * The substitution now uses `bt$` — `$` cannot occur in
 * a plain Kotlin identifier (compiler-proven), and the
 * inventory's chain pattern accepts `$` inside a name
 * so the token is still captured whole.
 *
 * Round 28 strengthening (both items in this class):
 * (1) The round-27 timed-condition text assertion reads
 * the RAW whole file, and the timed condition is the one
 * condition in this pin whose text is not itself a
 * counted anchor — proven live in round 28: an inert
 * empty-body copy of the exact text, placed after the
 * continuation computation (past the region slice's end,
 * past every anchor count), supplied the text while the
 * live branch ran the round-27 swapped condition, green
 * across the whole suite. The F1 pin now also asserts
 * the timed condition and its body's first statement
 * (`expireSleepTimer()`) as one block, exactly once, on
 * the raw text and on strictCode. The guard half of the
 * same attack is DISMISSED with evidence: the guard's
 * text contains the slice anchor `if (fromTrackEnd &&`,
 * whose exact whole-file count (1) a dead copy
 * necessarily breaks (round-28 construction failed
 * there, expected 1, was 2). (2) The `bt$` token was
 * not injective: a backticked `` `bt$else` `` — a legal
 * callee distinct from `` `else` `` — fell through the
 * substitution regex (which allowed only plain
 * identifier characters) to the bare backtick strip and
 * produced the identical token `bt$else`; a backticked
 * `` `else where` `` likewise recorded as the plain
 * callee `where` (compiled toy, both collisions
 * identical=true with different execution). The
 * substitution now encodes any backticked content as
 * `bt$` plus the $-separated hex of its characters —
 * injective over plain and backticked spellings alike.
 *
 * Round 29 strengthening (this class): the round-28 timed
 * block binds the condition to its body's FIRST statement
 * only — proven live in round 29, deleting the timed
 * body's `return@launch` (the branch's exit) and, in a
 * second run, flipping its `setResolving(false)` to
 * `setResolving(true)` (an argument change with an
 * identical call multiset) both ran green across the
 * whole suite: the region inventory counts call NAMES,
 * the keyword counts ignore `return`, and no other pin
 * reads the timed body's remainder. Without the return,
 * a timed expiry falls through to the continuation
 * computation and can append and play related tracks
 * past the expired timer. The block assertions below
 * now carry the FULL timed body — condition, expiry
 * call, flag clear, and the branch's return — as one
 * block, exactly once, on the raw text and on
 * strictCode.
 *
 * Round 30 strengthening (this class): the round-29 full-body
 * block is still a whole-file count — it does not care WHERE
 * its one occurrence lives. Proven live in round 30: an
 * unused private function elsewhere in the controller,
 * hosting a `scope.launch` lambda carrying the exact block
 * (typed timer declaration, so no anchor count moves),
 * supplied both block counts while the LIVE timed branch
 * ran a respelled condition (`&& true` appended to its
 * second leg — the exact condition text then lives only
 * in the copy) with its flag clear flipped to
 * `setResolving(true)`; a second run dropped the live
 * `return@launch` instead. Both ran green across the
 * whole suite: the region inventory counts call names
 * (the flip keeps the multiset; the condition respell
 * keeps every call), the stem counts are argument-blind,
 * and no assertion bound the block to its site. The pin
 * now also asserts the block's index in the file lies
 * INSIDE the branch region — after the sleep branch's
 * start and before the continuation computation — the
 * same site-binding the F1 guard's ordering assertions
 * have always had. A copy outside the region (before it
 * or after it) cannot stand in for the live body; a copy
 * inside the region breaks the region's own stem,
 * conditional, and inventory counts.
 *
 * Round 34 strengthening (this class): the round-33 bans
 * close the lambda hosts (a declaration, a labelled-lambda
 * header), and the inventories close every call-shaped and
 * conditional-shaped host — but `try`/`catch`/`finally`
 * are none of those: no inventory counts them (the keyword
 * census names only if/when/while/for/fun, and the call
 * scan filters try/catch as keywords). Proven live in
 * round 34: the branch's live statements wrapped in a
 * `try` whose `catch (e: Throwable)` block held the whole
 * guarded pair, verbatim and at the adjacency's
 * indentation — the try body never throws, so the catch
 * never executes, the branch falls through past its stop,
 * and every round-33 assertion stayed green across the
 * whole suite (a `catch (e: Exception)` spelling would
 * have died at the F2 pin's whole-file catch count; the
 * Throwable spelling is counted nowhere). The branch and
 * the region legitimately hold no exception construct at
 * all; the F1 pin now asserts exactly that on both.
 */
class R18SweepContractTest {

    @Test
    fun continuationSleepStopClearDefersToALiveRadioFetch() {
        // Round 23: anchor integrity (see the header). Every
        // delimiter this pin slices on must live in code — an
        // occurrence inside a comment or a literal is a fake
        // boundary that truncates a slice and hides whatever
        // is placed after it in the real body.
        val fullText = controller()
        val fullStrict = strictCode(fullText)
        for ((anchor, expected) in listOf(
            "if (fromTrackEnd &&" to 1,
            "val timer = _sleepTimerState.value" to 2,
            "    fun cancelSleepTimer() {" to 1,
            "    private fun expireSleepTimer()" to 1,
            "    private fun syncPlaybackState() {" to 1,
            "    private companion object" to 1,
        )) {
            assertEquals(
                "the slice anchor `$anchor` must occur only in " +
                    "code — an occurrence inside a comment or a " +
                    "string/raw literal is a fake slice boundary " +
                    "that can hide a piggybacked clear behind it",
                occurrences(fullStrict, anchor),
                occurrences(fullText, anchor),
            )
            // Round 24: equality alone is not integrity — a
            // fake anchor spelled as CODE (inside a template
            // hole, which strictCode keeps as code, or as a
            // duplicated plain statement) inflates BOTH counts
            // equally and still truncates the raw slice. Pin
            // the anchor's exact whole-file count instead.
            assertEquals(
                "the slice anchor `$anchor` must occur exactly " +
                    "$expected time(s) in the shipped file — any " +
                    "extra occurrence, in a comment, a literal, " +
                    "a template hole, or as a duplicated code " +
                    "statement, is a fake slice boundary that " +
                    "can hide a piggybacked clear behind it",
                expected,
                occurrences(fullText, anchor),
            )
        }
        // Round 25: the exact count above is a whole-file
        // NET — it survives a compensation that ADDS a fake
        // anchor inside the branch while RESPELLING a
        // legitimate occurrence elsewhere so the net is
        // unchanged (proven live in round 25: a duplicated
        // `val timer …` in the branch hid an unconditional
        // clear while the polling loop's identical statement
        // was respelled as a typed declaration,
        // `val timer: SleepTimerState = …`, and the count
        // stayed 2). Count this delimiter's two legitimate
        // CONTEXTS as well: the end-anchor occurrence must
        // be the one followed by the timed branch's own
        // condition, and the polling-loop occurrence must
        // keep its exact shipped spelling and its own
        // following line — a respell of either breaks its
        // context count even when the net is preserved.
        assertEquals(
            "the branch's end anchor must be the occurrence " +
                "followed by the timed branch's condition — a " +
                "fake anchor inside the branch (with a " +
                "compensating respell elsewhere) leaves this " +
                "context count short",
            1,
            occurrences(
                fullText,
                "val timer = _sleepTimerState.value\n" +
                    "            if (timer.isArmed &&",
            ),
        )
        assertEquals(
            "the polling loop's timer read must keep its " +
                "exact shipped spelling — respelling it (a " +
                "typed declaration, an inlined read) to " +
                "compensate a fake anchor elsewhere breaks " +
                "this context count",
            1,
            occurrences(
                fullText,
                "val timer = _sleepTimerState.value\n" +
                    "                _sleepTimerRemainingMs.value =",
            ),
        )
        // Round 26: the two context counts above are nets
        // one level up — proven live in round 26, where a
        // fake end anchor inside the branch carried the
        // timed branch's exact following line (supplying
        // the end context), a dead `if (false)` block
        // carried a duplicated polling pair (supplying the
        // polling context), and BOTH legitimate anchors
        // were respelled as typed declarations, so every
        // round-25 count held while an unconditional clear
        // sat in the branch. Assert the whole REGION from
        // the branch start to the continuation computation
        // instead: an added clear anywhere in that span
        // changes its stem count, its conditional count, or
        // its call inventory, whatever anchor games are
        // played at the edges — and no respell inside the
        // region can pay for it, because every legitimate
        // clear in the region is itself counted here.
        assertTrue(
            "the continuation computation after the timed " +
                "branch must exist at all (the region slice's " +
                "end delimiter)",
            controller().contains(
                "val continuation = if (related == null) {",
            ),
        )
        val branchRegion = controller()
            .substringAfter("if (fromTrackEnd &&")
            .substringBefore(
                "val continuation = if (related == null) {",
            )
        assertEquals(
            "the branch region (the sleep branch plus its " +
                "timed twin) must hold exactly two " +
                "resolving-clear calls — the guarded branch " +
                "clear and the timed branch's own; a third, " +
                "unconditional clear anywhere in the region " +
                "restores the round-18 bug under every " +
                "anchor count above",
            2,
            occurrences(strictCode(branchRegion), "setResolving("),
        )
        assertEquals(
            "the branch region must hold exactly two " +
                "conditionals in its code — the radio guard " +
                "and the timed branch's condition; a fake " +
                "context line or a dead wrapper adds one",
            2,
            keywordCount(codeOnly(branchRegion), "if"),
        )
        assertEquals(
            "the branch region must hold exactly two " +
                "conditionals under the strict lexical strip " +
                "as well",
            2,
            keywordCount(strictCode(branchRegion), "if"),
        )
        assertEquals(
            "the branch region must hold no when, while, " +
                "for, or local fun in its code",
            0,
            keywordCount(codeOnly(branchRegion), "when") +
                keywordCount(codeOnly(branchRegion), "while") +
                keywordCount(codeOnly(branchRegion), "for") +
                keywordCount(codeOnly(branchRegion), "fun"),
        )
        assertEquals(
            "the branch region must make exactly the calls " +
                "it legitimately makes today — an added " +
                "clear routed through any helper, fake " +
                "context, or dead block changes this " +
                "inventory or the counts above",
            listOf(
                "SystemClock.elapsedRealtime",
                "cancelSleepTimer",
                "diagnostics.log",
                "expireSleepTimer",
                "player.pause",
                "resolveJob?.cancel",
                "setResolving",
                "setResolving",
                "sleepTimerShouldPause",
                "sleepTimerShouldStopAtTrackEnd",
                "syncPlaybackState",
            ),
            callInventory(codeOnly(branchRegion)),
        )
        assertEquals(
            "the branch region's call inventory under the " +
                "strict lexical strip must match the legacy " +
                "inventory exactly",
            listOf(
                "SystemClock.elapsedRealtime",
                "cancelSleepTimer",
                "diagnostics.log",
                "expireSleepTimer",
                "player.pause",
                "resolveJob?.cancel",
                "setResolving",
                "setResolving",
                "sleepTimerShouldPause",
                "sleepTimerShouldStopAtTrackEnd",
                "syncPlaybackState",
            ),
            callInventory(strictCode(branchRegion)),
        )
        // Round 27: the region counts above are satisfied
        // by a condition SWAP whose dead legs preserve
        // every counted token (see the header) — counts
        // bind calls to the region, not conditions to
        // their sites. Pin both conditions' exact shipped
        // text, in place: the sleep branch's guard is the
        // end-of-track check alone, and the timed branch's
        // condition is the armed check plus the monotonic
        // expiry check alone. Any swap, negation, dead
        // leg, or property respelling changes one of these
        // texts and fails here, in the same fail-closed
        // direction as this pin's other exact counts.
        assertEquals(
            "the continuation sleep branch's guard must be " +
                "exactly the end-of-track check — a guard " +
                "that also (or instead) reads the timed " +
                "deadline routes the end-of-track stop " +
                "through the wrong branch while every " +
                "region count above stays green",
            1,
            occurrences(
                fullText,
                "if (fromTrackEnd &&\n" +
                    "                sleepTimerShouldStopAtTrackEnd(_sleepTimerState.value)\n" +
                    "            ) {",
            ),
        )
        assertEquals(
            "the timed branch's condition must be exactly " +
                "the armed check plus the monotonic expiry " +
                "check — a condition that also (or instead) " +
                "reads the end-of-track flag routes a timed " +
                "expiry through the wrong branch (or no " +
                "branch) while every region count above " +
                "stays green",
            1,
            occurrences(
                fullText,
                "if (timer.isArmed &&\n" +
                    "                sleepTimerShouldPause(timer, SystemClock.elapsedRealtime())\n" +
                    "            ) {",
            ),
        )
        // Round 28: the assertion above counts the timed
        // condition's text on the RAW whole file — proven
        // live in round 28, the text can be supplied by an
        // inert copy AFTER the region (an empty-body `if`
        // carrying the exact text, placed after the
        // continuation computation, where the region slice
        // has already ended and no anchor count reads it:
        // this condition's first line is not a counted
        // anchor, and the round-25 context count needs only
        // that first line, which the swapped live branch
        // keeps) while the live timed branch carries the
        // round-27 swapped condition. Bind the condition
        // to its body: the exact condition text and the
        // expiry call that is its body's first statement,
        // as one block, exactly once, on the raw text and
        // on the strict lexical strip (where a copy inside
        // a comment or a literal supplies nothing). A dead
        // copy has no expiry body; a swapped live branch
        // has no exact condition text; neither can supply
        // this block. (The sleep guard needed no such
        // binding: its text contains the counted slice
        // anchor itself, whose exact whole-file count a
        // dead copy necessarily breaks — proven in round
        // 28, where the same decoy shape on the guard
        // failed at that count, expected 1, was 2.)
        assertEquals(
            "the timed branch's condition must head the " +
                "expiry body itself, as one block — the " +
                "exact condition text standing alone (a " +
                "dead copy after the region) while the live " +
                "branch runs a swapped condition routes a " +
                "timed expiry through the wrong branch " +
                "while the text count above stays green; " +
                "round 29: the block runs through the " +
                "body's exit — a body that keeps the " +
                "condition and the expiry call but drops " +
                "its return (or flips its flag clear) " +
                "falls through to the continuation " +
                "computation under this same text",
            1,
            occurrences(
                fullText,
                "if (timer.isArmed &&\n" +
                    "                sleepTimerShouldPause(timer, SystemClock.elapsedRealtime())\n" +
                    "            ) {\n" +
                    "                expireSleepTimer()\n" +
                    "                setResolving(false)\n" +
                    "                return@launch",
            ),
        )
        assertEquals(
            "the timed condition-plus-body block must " +
                "occur exactly once outside comments and " +
                "literals as well — a copy in a comment or " +
                "a raw string must not be able to stand in " +
                "for the live branch; round 29: the block " +
                "carries the full body through its return, " +
                "in this view too",
            1,
            occurrences(
                fullStrict,
                "if (timer.isArmed &&\n" +
                    "                sleepTimerShouldPause(timer, SystemClock.elapsedRealtime())\n" +
                    "            ) {\n" +
                    "                expireSleepTimer()\n" +
                    "                setResolving(false)\n" +
                    "                return@launch",
            ),
        )
        // Round 30: the two block counts above are
        // whole-file nets — a dead copy of the exact block
        // parked OUTSIDE the branch region (in an unused
        // launch lambda elsewhere in the controller)
        // supplies both while the live body is altered
        // (its clear flipped, its return dropped, its
        // condition respelled by a dead leg), because the
        // region assertions count call names and stems,
        // not this block's position (proven live in round
        // 30, both variants green across the whole suite).
        // Bind the block to its site: its one occurrence
        // must lie inside the branch region, between the
        // sleep branch's start and the continuation
        // computation.
        val timedBlockText =
            "if (timer.isArmed &&\n" +
                "                sleepTimerShouldPause(timer, SystemClock.elapsedRealtime())\n" +
                "            ) {\n" +
                "                expireSleepTimer()\n" +
                "                setResolving(false)\n" +
                "                return@launch"
        val timedBlockIndex = fullText.indexOf(timedBlockText)
        assertTrue(
            "the timed condition-plus-body block must sit " +
                "inside the branch region itself — a copy " +
                "parked before the region or after the " +
                "continuation computation is not the timed " +
                "branch's body, whatever the whole-file " +
                "counts above say",
            timedBlockIndex > fullText.indexOf("if (fromTrackEnd &&") &&
                timedBlockIndex <
                fullText.indexOf(
                    "val continuation = if (related == null) {",
                ),
        )
        // Round 31: the index binding above binds the block's
        // POSITION, not its REACHABILITY — proven live in
        // round 31, an unconditional `return@launch` parked
        // between the sleep branch's close and the timer read
        // left every round-30 assertion green (block count 1,
        // index in-region, region inventory and conditional
        // counts untouched, the val-timer context adjacency
        // preserved) while the timed block — and in fact the
        // whole continuation computation after it — never
        // executes: autoplay dies after the fetch with the
        // resolving flag stranded on. The region segment
        // before the timed condition must hold exactly one
        // labelled return: the sleep branch's own, inside
        // its guard. Any second one parked ahead of the
        // timed block dead-codes the block under its own
        // text.
        assertEquals(
            "the branch region before the timed condition " +
                "must hold exactly one labelled return — the " +
                "sleep branch's own; a second, unconditional " +
                "return parked before the timed block makes " +
                "the in-region block dead text while its " +
                "index, counts, and inventory all still pass",
            1,
            occurrences(
                branchRegion.substringBefore(
                    "if (timer.isArmed &&",
                ),
                "return@launch",
            ),
        )
        assertTrue(
            "the continuation's end-of-track sleep branch must exist at all",
            controller().contains("if (fromTrackEnd &&"),
        )
        val branch = controller()
            .substringAfter("if (fromTrackEnd &&")
            .substringBefore("val timer = _sleepTimerState.value")
        assertEquals(
            "the continuation sleep branch must keep exactly one " +
                "terminal clear in its slice",
            1,
            occurrences(branch, "setResolving(false)"),
        )
        assertTrue(
            "the continuation branch's terminal clear must defer " +
                "to a live radio fetch — its siblings route through " +
                "the orphan helper, which defers in exactly this " +
                "case, and the radio fetch this branch staled with " +
                "its epoch bump releases the flag at its landing; " +
                "an unconditional clear drops the loading state " +
                "under the still-in-flight radio fetch",
            branch.contains("if (radioJob?.isActive != true)"),
        )
        assertTrue(
            "the guarded clear must come after the resolve cancel " +
                "inside the branch — it is the branch's terminal " +
                "step, releasing (or deferring) the flag once every " +
                "fetch the stop kills has been cancelled",
            branch.indexOf("resolveJob?.cancel()") <
                branch.indexOf("if (radioJob?.isActive != true)"),
        )
        // Round 19 strengthening: presence + ordering alone passed
        // with the guard moved to wrap a DIFFERENT statement in the
        // branch (the disarm) while the terminal clear went back to
        // unconditional — every asserted literal stayed in the
        // slice. Pin the guard to the clear itself: the exact
        // guarded block, and its placement before the branch's
        // return (a guarded block moved below the return is dead
        // code that strands the flag whenever no radio is live).
        assertTrue(
            "the radio guard must wrap the terminal clear itself, " +
                "as one exact block — a guard around any other " +
                "statement in the branch leaves the clear " +
                "unconditional and drops the flag under a live " +
                "radio fetch exactly as the unpatched branch did",
            branch.contains(
                "if (radioJob?.isActive != true) {\n" +
                    "                    setResolving(false)\n" +
                    "                }",
            ),
        )
        assertTrue(
            "the guarded clear must sit before the branch's " +
                "return — below it, the block never executes and " +
                "the flag is stranded with no owner left to " +
                "release it",
            branch.indexOf("if (radioJob?.isActive != true)") <
                branch.indexOf("return@launch"),
        )
        // Round 32: the ordering assertion above reads the
        // branch's FIRST `return@launch` wherever it sits, and
        // the round-31 segment count counts the text wherever
        // it sits — proven live in round 32, the branch's own
        // return was DELETED (the branch then falls through to
        // the timer read and the continuation computation
        // after its stop) while a textually identical
        // `return@launch` sat inside a nested explicitly
        // labelled lambda (`launch@ { return@launch }`, never
        // invoked; its label binds the nested lambda, not the
        // continuation) in the same segment: the segment count
        // stayed 1, the ordering still found a return after
        // the guard, and neither inventory records the lambda
        // (no call shape, no conditional). Bind the return to
        // its site: it must directly follow the guarded clear
        // block, as one adjacency, at the branch's top level.
        assertTrue(
            "the branch's own return must directly follow " +
                "the guarded clear block — a return deleted " +
                "from that site and replaced by an identical " +
                "text inside a nested lambda leaves the count " +
                "and the ordering green while the branch " +
                "falls through past its stop",
            branch.contains(
                "if (radioJob?.isActive != true) {\n" +
                    "                    setResolving(false)\n" +
                    "                }\n" +
                    "                return@launch",
            ),
        )
        // Round 33: the adjacency above is still a `contains`
        // over the branch slice — proven live in round 33, the
        // WHOLE pair (guarded block plus its return, exactly as
        // the adjacency spells it) parked inside a never-invoked
        // explicitly-labelled lambda (`val parkedPair: () ->
        // Unit = launch@ { …pair… }`) supplied the adjacency,
        // the segment return count, the branch stem count, and
        // both call inventories 1-for-1, once the live pair was
        // deleted — the branch then fell through past its stop
        // exactly as in round 32. The inventories cannot see
        // the host: a value lambda records no call, no
        // conditional, and no `fun`. But the branch
        // legitimately declares NOTHING and labels NOTHING:
        // any lambda host for the pair needs a local
        // declaration or a labelled-lambda header, and any
        // call-shaped host is already inventoried. Pin both
        // absences. (A plain unlabelled value lambda cannot
        // host the return at all — `return@launch` targeting
        // the outer lambda is prohibited there, compiler- and
        // toy-proven in round 33 — so the label must be
        // `launch`, the very header banned here.)
        assertEquals(
            "the continuation sleep branch must declare no " +
                "local value — a declared lambda is a host " +
                "that can park the guarded pair as dead text " +
                "while every count and inventory above stays " +
                "green and the branch falls through",
            0,
            occurrences(branch, "val "),
        )
        assertFalse(
            "the continuation sleep branch must hold no " +
                "labelled-lambda header — `launch@ {` is the " +
                "one host under which the pair's own " +
                "`return@launch` both compiles (bound to the " +
                "nested lambda) and matches the adjacency " +
                "needle above",
            branch.contains("launch@ {"),
        )
        // Round 20: the exact block's presence does not exclude a
        // SECOND, unconditional clear beside it — respelled
        // `setResolving( false )` it evades the literal count
        // above while dropping the flag under a live radio fetch
        // exactly as the unpatched branch did. Count the call
        // stem: any spelling of the call contains it, and the
        // branch holds exactly one clear call.
        assertEquals(
            "the continuation sleep branch must hold exactly one " +
                "resolving-clear call of ANY spelling — a second, " +
                "unconditional call beside the guarded block " +
                "restores the round-18 bug under the pin",
            1,
            occurrences(branch, "setResolving("),
        )
        assertFalse(
            "the guarded block must not sit inside a dead " +
                "`if (false)` wrapper — present and ordered but " +
                "never executed, it strands the flag whenever no " +
                "radio fetch is live to release it",
            branch.contains("if (false)"),
        )
        assertFalse(
            "the branch must not clear the flag by writing the " +
                "playback state directly — that is the same " +
                "unconditional clear under a spelling no " +
                "setResolving count can see",
            branch.contains("_playbackState"),
        )
        // Round 21: the stem count sees only calls spelled
        // setResolving(. A second clear routed through a helper
        // of ANY name (defined outside the slice) and any
        // always-false wrapper beyond `if (false)` (a when, a
        // named false val, a constant expression) both passed
        // in round 21's mutation runs. Assert the branch's
        // complete code inventory instead: exactly the guard's
        // one conditional, no other conditional construct, no
        // local function definition, and exactly the calls the
        // branch legitimately makes — an added call or wrapper
        // of any shape or name changes the inventory.
        val branchCode = codeOnly(branch)
        assertEquals(
            "the continuation sleep branch must hold exactly " +
                "one conditional in its code — the radio guard " +
                "itself; any second conditional (if/when/while/" +
                "for) is a wrapper that can dead-code the " +
                "guarded clear or gate an added one",
            1,
            keywordCount(branchCode, "if"),
        )
        assertEquals(
            "the continuation sleep branch must hold no when, " +
                "while, for, or local fun in its code — each is " +
                "a wrapper shape that can hide or dead-code a " +
                "clear beyond the stem count's reach",
            0,
            keywordCount(branchCode, "when") +
                keywordCount(branchCode, "while") +
                keywordCount(branchCode, "for") +
                keywordCount(branchCode, "fun"),
        )
        assertEquals(
            "the continuation sleep branch must make exactly " +
                "the calls it legitimately makes today — a " +
                "second clear routed through a helper of any " +
                "name (the round-21 clearResolvingNow / " +
                "releaseFlagNow evasions) adds a call the stem " +
                "count cannot see",
            listOf(
                "cancelSleepTimer",
                "diagnostics.log",
                "player.pause",
                "resolveJob?.cancel",
                "setResolving",
                "sleepTimerShouldStopAtTrackEnd",
                "syncPlaybackState",
            ),
            callInventory(branchCode),
        )
        // Round 22 (2): the line-wise stripper above eats real
        // code after a `//` that Kotlin treats as block-comment
        // content, and after a raw string's closing quotes on a
        // `//`-carrying raw line — an in-slice helper call hid
        // there under the legacy inventory alone. Assert the
        // same inventories on strictCode's whole-text lexical
        // strip (comments, string/char contents removed;
        // templates kept as code).
        val branchStrict = strictCode(branch)
        assertEquals(
            "the continuation sleep branch must hold exactly " +
                "one conditional under the strict lexical strip " +
                "as well — the two strippers disagreeing means " +
                "a construct is hiding in a comment or literal " +
                "shape one of them misreads",
            1,
            keywordCount(branchStrict, "if"),
        )
        assertEquals(
            "the continuation sleep branch must hold no when, " +
                "while, for, or local fun under the strict " +
                "lexical strip either",
            0,
            keywordCount(branchStrict, "when") +
                keywordCount(branchStrict, "while") +
                keywordCount(branchStrict, "for") +
                keywordCount(branchStrict, "fun"),
        )
        assertEquals(
            "the continuation sleep branch's call inventory " +
                "under the strict lexical strip must match the " +
                "legacy inventory exactly — a call the legacy " +
                "stripper ate (block-comment or raw-string " +
                "shape) shows up here and nowhere else",
            listOf(
                "cancelSleepTimer",
                "diagnostics.log",
                "player.pause",
                "resolveJob?.cancel",
                "setResolving",
                "sleepTimerShouldStopAtTrackEnd",
                "syncPlaybackState",
            ),
            callInventory(branchStrict),
        )
        // Round 34: every host the rounds above close is a
        // declaration, a label, a call, or a conditional —
        // an exception construct is none of those, and no
        // inventory in this pin counts one. Proven live in
        // round 34, the whole guarded pair parked inside a
        // `catch (e: Throwable)` block (the branch's live
        // statements wrapped in the matching `try`, which
        // never throws, so the catch never executes) left
        // the branch falling through past its stop while
        // the val/label bans, the stem counts, the
        // conditional counts, and both call inventories
        // all stayed green. The branch and its region
        // legitimately contain no try, catch, or finally
        // at all — assert exactly that, on both strippers
        // and on the wider region (whose timed twin could
        // host the same shape one level down).
        assertEquals(
            "the continuation sleep branch must hold no " +
                "try, catch, or finally in its code — an " +
                "exception block is a host no inventory " +
                "above records, and a catch that the try " +
                "body never triggers parks the guarded " +
                "pair as dead text while the branch falls " +
                "through past its stop",
            0,
            keywordCount(branchCode, "try") +
                keywordCount(branchCode, "catch") +
                keywordCount(branchCode, "finally"),
        )
        assertEquals(
            "the continuation sleep branch must hold no " +
                "try, catch, or finally under the strict " +
                "lexical strip either",
            0,
            keywordCount(branchStrict, "try") +
                keywordCount(branchStrict, "catch") +
                keywordCount(branchStrict, "finally"),
        )
        assertEquals(
            "the branch region must hold no try, catch, " +
                "or finally in its code either — the same " +
                "parked pair hosted in the timed twin's " +
                "territory is the same fall-through",
            0,
            keywordCount(codeOnly(branchRegion), "try") +
                keywordCount(codeOnly(branchRegion), "catch") +
                keywordCount(codeOnly(branchRegion), "finally"),
        )
        assertEquals(
            "the branch region must hold no try, catch, " +
                "or finally under the strict lexical strip " +
                "either",
            0,
            keywordCount(strictCode(branchRegion), "try") +
                keywordCount(strictCode(branchRegion), "catch") +
                keywordCount(strictCode(branchRegion), "finally"),
        )
        // Round 24: an infix call (`1 helper 2`) has no
        // parentheses and no dot — no call pattern can see
        // its operator name. Assert the strict view holds no
        // identifier in infix position (between two operands
        // on one line) at all; the strict view is used so
        // blanked literal prose cannot pose as an operand.
        assertEquals(
            "the continuation sleep branch must hold no infix " +
                "call under the strict lexical strip — an infix " +
                "operator name between two operands is a call " +
                "no parenthesis-based inventory can record, and " +
                "routing the clear through an outside infix " +
                "helper passed both inventories in round 24",
            emptyList<String>(),
            infixCalls(branchStrict),
        )
        // Round 22 (1): piggyback closure. The branch calls two
        // QueueController members whose bodies could carry a
        // piggybacked clear past every slice-local assertion —
        // cancelSleepTimer() (proven round 21) and
        // syncPlaybackState() (proven round 22, both as an
        // added setResolving(false) and as the rebuild's
        // carry-over respelled `isResolving = false`). Their
        // transitive closure terminates at depth 1 (library /
        // body-less-constructor leaves only — see the header),
        // so pin both bodies directly: no resolving stem of
        // any kind, an exact call inventory under both
        // strippers, and for syncPlaybackState the exact
        // carry-over line plus the exact _playbackState count
        // (a direct state write is the stem-free spelling).
        assertTrue(
            "cancelSleepTimer must exist at all",
            controller().contains("    fun cancelSleepTimer() {"),
        )
        assertTrue(
            "syncPlaybackState must exist at all",
            controller().contains("    private fun syncPlaybackState() {"),
        )
        val cancelBody = controller()
            .substringAfter("    fun cancelSleepTimer() {")
            .substringBefore("    private fun expireSleepTimer()")
        assertEquals(
            "cancelSleepTimer's body must hold no resolving " +
                "clear in any spelling — piggybacked there, it " +
                "fires unconditionally at this branch's disarm " +
                "and drops the flag under a live radio fetch " +
                "while the branch slice stays byte-identical",
            0,
            occurrences(cancelBody, "setResolving"),
        )
        assertEquals(
            "cancelSleepTimer's body must not write the " +
                "playback state directly — the stem-free " +
                "spelling of the same piggyback",
            0,
            occurrences(cancelBody, "_playbackState"),
        )
        assertEquals(
            "cancelSleepTimer's body must make exactly its " +
                "legitimate calls — an added helper call there " +
                "is a piggyback route the stem ban cannot see",
            listOf("SleepTimerState", "sleepTimerJob?.cancel"),
            callInventory(codeOnly(cancelBody)),
        )
        assertEquals(
            "cancelSleepTimer's call inventory under the " +
                "strict lexical strip must match as well",
            listOf("SleepTimerState", "sleepTimerJob?.cancel"),
            callInventory(strictCode(cancelBody)),
        )
        val syncBody = controller()
            .substringAfter("    private fun syncPlaybackState() {")
            .substringBefore("    private companion object")
        assertEquals(
            "syncPlaybackState's body must hold no resolving " +
                "clear call — piggybacked there, it fires at " +
                "this branch's sync (and at every other sync " +
                "site) while the branch slice stays " +
                "byte-identical",
            0,
            occurrences(syncBody, "setResolving"),
        )
        assertTrue(
            "syncPlaybackState must carry the resolving flag " +
                "over its snapshot rebuild verbatim — respelled " +
                "`isResolving = false` there is a piggybacked " +
                "clear with no call at all",
            syncBody.contains(
                "isResolving = _playbackState.value.isResolving,",
            ),
        )
        assertEquals(
            "syncPlaybackState's body must reference the " +
                "playback state exactly twice (the rebuild " +
                "target and the flag carry-over read) — a " +
                "third reference is a direct write, the " +
                "stem-free piggyback spelling",
            2,
            occurrences(syncBody, "_playbackState"),
        )
        assertEquals(
            "syncPlaybackState's body must make exactly its " +
                "legitimate calls — an added helper call " +
                "there is a piggyback route the stem ban " +
                "cannot see",
            listOf(
                "PlaybackUiState",
                "player.currentPosition.coerceAtLeast",
            ),
            callInventory(codeOnly(syncBody)),
        )
        assertEquals(
            "syncPlaybackState's call inventory under the " +
                "strict lexical strip must match as well",
            listOf(
                "PlaybackUiState",
                "player.currentPosition.coerceAtLeast",
            ),
            callInventory(strictCode(syncBody)),
        )
        assertEquals(
            "syncPlaybackState's body must hold exactly one " +
                "conditional (its duration check) under both " +
                "strippers — a second conditional there is a " +
                "wrapper a piggyback can hide behind",
            1,
            keywordCount(codeOnly(syncBody), "if"),
        )
        assertEquals(
            "syncPlaybackState's conditional count under " +
                "the strict lexical strip must match as well",
            1,
            keywordCount(strictCode(syncBody), "if"),
        )
        // Round 24: the host bodies get the infix ban too —
        // an infix piggyback inside a host is invisible to
        // its call inventory exactly as in the branch.
        assertEquals(
            "cancelSleepTimer's body must hold no infix call " +
                "under the strict lexical strip",
            emptyList<String>(),
            infixCalls(strictCode(cancelBody)),
        )
        assertEquals(
            "syncPlaybackState's body must hold no infix call " +
                "under the strict lexical strip",
            emptyList<String>(),
            infixCalls(strictCode(syncBody)),
        )
    }

    @Test
    fun cancelledResolveCatchDefersToLiveContinuationAndRadio() {
        // Round 23: anchor integrity, as in the F1 pin — every
        // delimiter this pin slices on (including the catch's
        // own start/end anchors inside the resolve body) must
        // live in code only.
        val fullText = controller()
        val fullStrict = strictCode(fullText)
        for ((anchor, expected) in listOf(
            "private fun resolveAndPlay" to 1,
            "val stream = try {" to 1,
            "handToPlayer(" to 3,
            "catch (e: CancellationException)" to 7,
            "catch (e: Exception)" to 9,
        )) {
            assertEquals(
                "the slice anchor `$anchor` must occur only in " +
                    "code — an occurrence inside a comment or a " +
                    "string/raw literal is a fake slice boundary " +
                    "that can hide an added clear behind it",
                occurrences(fullStrict, anchor),
                occurrences(fullText, anchor),
            )
            // Round 24: exact count as well as equality — a
            // fake anchor spelled as code (template hole or
            // duplicated statement) inflates both views
            // equally; see the F1 pin.
            assertEquals(
                "the slice anchor `$anchor` must occur exactly " +
                    "$expected time(s) in the shipped file — " +
                    "any extra occurrence is a fake slice " +
                    "boundary that can hide an added clear " +
                    "behind it",
                expected,
                occurrences(fullText, anchor),
            )
        }
        // Round 25: the same compensation class as the F1
        // pin, on the catch anchors (proven live in round
        // 25: a fake `catch (e: Exception)` heading an
        // empty try inside the cancellation catch truncated
        // that catch's slice and hid an unconditional clear
        // after it, while the radio fetch's catch parameter
        // was renamed — `catch (ex: Exception)`, its body
        // the literal `null`, which never used the name —
        // so the whole-file count stayed 9). No context
        // count can be placed on this slice's own boundary
        // pair (their spellings are the content under
        // test), so pin the slice-local counts instead:
        // inside the resolve body each catch heads exactly
        // one try, and any fake catch — compensated or
        // not — inflates a local count the whole-file net
        // cannot see.
        val resolveBodyForCounts = controller()
            .substringAfter("private fun resolveAndPlay")
            .substringAfter("val stream = try {")
            .substringBefore("handToPlayer(")
        assertEquals(
            "the resolve body must hold exactly one " +
                "cancellation catch — a fake catch inside it " +
                "truncates the catch slice and hides an " +
                "unconditional clear behind it, whatever " +
                "compensating respell keeps the whole-file " +
                "count at 7",
            1,
            occurrences(
                resolveBodyForCounts,
                "catch (e: CancellationException)",
            ),
        )
        assertEquals(
            "the resolve body must hold exactly one failure " +
                "catch — a fake `catch (e: Exception)` inside " +
                "the cancellation catch is that slice's end " +
                "anchor, and a compensating parameter rename " +
                "elsewhere (proven on the radio fetch's " +
                "null-bodied catch in round 25) keeps the " +
                "whole-file count at 9 while the slice " +
                "truncates",
            1,
            occurrences(
                resolveBodyForCounts,
                "catch (e: Exception)",
            ),
        )
        // Round 26: the slice-local counts above are nets
        // INSIDE the slice — proven live in round 26, where
        // a fake `catch (e: Exception)` inside the
        // cancellation catch (the round-25 shape) was paid
        // for by renaming the resolve body's OWN failure
        // catch — `catch (ex: Exception)` with its single
        // use respelled `userMessage(ex)` — so the local
        // count stayed 1 and the whole-file count stayed 9
        // while the hidden clear sat in the catch. Count
        // both catches' opening CONTEXTS inside the body
        // (a rename breaks the real one's; the fake,
        // heading an empty try, carries neither), and
        // assert the body's clear-stem count and complete
        // inventory, which no in-body respell can pay for:
        // every legitimate clear in the body is counted
        // here, and respelling one into a helper changes
        // the inventory.
        assertEquals(
            "the resolve body must hold exactly one " +
                "cancellation catch with its shipped opening " +
                "comment — renaming it to compensate a fake " +
                "catch elsewhere in the body breaks this " +
                "context count",
            1,
            occurrences(
                resolveBodyForCounts,
                "catch (e: CancellationException) {\n" +
                    "                // A superseded resolve",
            ),
        )
        assertEquals(
            "the resolve body must hold exactly one failure " +
                "catch with its shipped opening comment — " +
                "renaming it (parameter plus its `userMessage` " +
                "use) to compensate a fake catch inside the " +
                "cancellation catch breaks this context count " +
                "even though the bare-anchor count stays 1",
            1,
            occurrences(
                resolveBodyForCounts,
                "catch (e: Exception) {\n" +
                    "                // An autoplay-lined track",
            ),
        )
        assertEquals(
            "the resolve body must hold exactly two " +
                "resolving-clear calls — the cancellation " +
                "catch's guarded clear and the failure catch's " +
                "own; a third clear hidden behind a fake " +
                "catch restores the round-18 premature clear " +
                "under every catch count above",
            2,
            occurrences(strictCode(resolveBodyForCounts), "setResolving("),
        )
        assertEquals(
            "the resolve body must hold exactly two " +
                "conditionals in its code — the three-leg " +
                "guard and the failure catch's skip check",
            2,
            keywordCount(codeOnly(resolveBodyForCounts), "if"),
        )
        assertEquals(
            "the resolve body must hold no when, while, " +
                "for, or local fun in its code",
            0,
            keywordCount(codeOnly(resolveBodyForCounts), "when") +
                keywordCount(codeOnly(resolveBodyForCounts), "while") +
                keywordCount(codeOnly(resolveBodyForCounts), "for") +
                keywordCount(codeOnly(resolveBodyForCounts), "fun"),
        )
        assertEquals(
            "the resolve body must make exactly the calls " +
                "it legitimately makes today — a clear " +
                "routed through a helper of any name, or a " +
                "legitimate call respelled into one, changes " +
                "this inventory",
            listOf(
                "ErrorMessages.userMessage",
                "currentCoroutineContext",
                "player.pause",
                "resolveWithRoads",
                "setResolving",
                "setResolving",
                "syncPlaybackState",
                "trySkipFailedAutoplayTrack",
            ),
            callInventory(codeOnly(resolveBodyForCounts)),
        )
        assertEquals(
            "the resolve body's call inventory under the " +
                "strict lexical strip must match the legacy " +
                "inventory exactly",
            listOf(
                "ErrorMessages.userMessage",
                "currentCoroutineContext",
                "player.pause",
                "resolveWithRoads",
                "setResolving",
                "setResolving",
                "syncPlaybackState",
                "trySkipFailedAutoplayTrack",
            ),
            callInventory(strictCode(resolveBodyForCounts)),
        )
        assertTrue(
            "resolveAndPlay must exist at all",
            controller().contains("private fun resolveAndPlay"),
        )
        assertTrue(
            "the resolve's stream try must exist at all",
            controller().contains("val stream = try {"),
        )
        val body = controller()
            .substringAfter("private fun resolveAndPlay")
            .substringAfter("val stream = try {")
            .substringBefore("handToPlayer(")
        assertTrue(
            "the resolve body must keep its cancellation catch",
            body.contains("catch (e: CancellationException)"),
        )
        assertTrue(
            "a cancelled resolve's own clear must defer to every " +
                "other live owner of the flag, not just check its " +
                "own identity: with identity alone, a sleep stop " +
                "that cancelled a resolve while a radio fetch was " +
                "also in flight dropped the flag under the radio " +
                "regardless of the stop branch's orphan-helper " +
                "deferral, and a resolve cancelled by the " +
                "continuation branch dropped it while that branch " +
                "was still executing its stop",
            body.contains("resolveJob === currentCoroutineContext()[Job]") &&
                body.contains("autoplayJob?.isActive != true") &&
                body.contains("radioJob?.isActive != true"),
        )
        // Round 19 strengthening: the three leg literals were
        // asserted as slice-wide presence over a 2,652-char slice
        // that also contains the failure catch's unconditional
        // clear — narrowing the second && to || (which clears
        // whenever the radio is inactive, even under a live
        // continuation or a successor resolve) left every literal
        // in place and the pin green. Pin the condition as one
        // exact block inside the cancellation catch itself.
        val cancellationCatch = body
            .substringAfter("catch (e: CancellationException)")
            .substringBefore("catch (e: Exception)")
        assertTrue(
            "the cancellation catch's clear must be guarded by " +
                "the exact three-leg condition joined by &&, as " +
                "one block inside that catch — legs asserted only " +
                "as slice-wide presence can be re-joined, " +
                "re-ordered, or moved to another clear in the " +
                "slice while the catch itself reverts to clearing " +
                "under a live owner",
            cancellationCatch.contains(
                "if (resolveJob === currentCoroutineContext()[Job] &&\n" +
                    "                    autoplayJob?.isActive != true &&\n" +
                    "                    radioJob?.isActive != true\n" +
                    "                ) {\n" +
                    "                    setResolving(false)\n" +
                    "                }",
            ),
        )
        // Round 20: this pin carried no count at all — the exact
        // block's presence passed with a live unconditional
        // setResolving(false) added beneath it in the same catch
        // (the round-18 premature clear, restored verbatim), and
        // with the block wrapped in `if (false)` (present but
        // dead; the flag then strands whenever this catch was
        // the owner that had to release it). Count the call stem
        // inside the catch and forbid both bypass shapes.
        assertEquals(
            "the cancellation catch must hold exactly one " +
                "resolving-clear call of ANY spelling — a second, " +
                "unconditional call beside the guarded block " +
                "clears under a live continuation or radio exactly " +
                "as the unpatched catch did",
            1,
            occurrences(cancellationCatch, "setResolving("),
        )
        assertFalse(
            "the guarded block must not sit inside a dead " +
                "`if (false)` wrapper — present but never " +
                "executed, it strands the flag whenever this " +
                "catch owns the release",
            cancellationCatch.contains("if (false)"),
        )
        assertFalse(
            "the catch must not clear the flag by writing the " +
                "playback state directly — the same unconditional " +
                "clear under a spelling no setResolving count " +
                "can see",
            cancellationCatch.contains("_playbackState"),
        )
        // Round 21: same evasion class as the F1 pin — a second
        // clear through an arbitrarily-named helper defined
        // outside the catch, and always-false wrappers beyond
        // `if (false)`, both passed the stem count in round
        // 21's mutation runs. Assert the catch's complete code
        // inventory: exactly the guard's one conditional, no
        // other conditional construct, no local fun, and
        // exactly the calls the catch legitimately makes.
        val catchCode = codeOnly(cancellationCatch)
        assertEquals(
            "the cancellation catch must hold exactly one " +
                "conditional in its code — the three-leg guard " +
                "itself; any second conditional is a wrapper " +
                "that can dead-code the guarded clear or gate " +
                "an added one",
            1,
            keywordCount(catchCode, "if"),
        )
        assertEquals(
            "the cancellation catch must hold no when, while, " +
                "for, or local fun in its code — each is a " +
                "wrapper shape beyond the stem count's reach",
            0,
            keywordCount(catchCode, "when") +
                keywordCount(catchCode, "while") +
                keywordCount(catchCode, "for") +
                keywordCount(catchCode, "fun"),
        )
        assertEquals(
            "the cancellation catch must make exactly the " +
                "calls it legitimately makes today — a second " +
                "clear routed through a helper of any name adds " +
                "a call the stem count cannot see",
            listOf("currentCoroutineContext", "setResolving"),
            callInventory(catchCode),
        )
        // Round 22: same stripper hardening as the F1 pin —
        // the legacy line-wise stripper eats real code in the
        // block-comment and raw-string shapes (proven on the
        // F1 slice), so the catch's inventories are asserted
        // on strictCode as well. (The catch's piggyback census
        // needs no host pins: its only calls are
        // currentCoroutineContext, a stdlib function with no
        // body in this tree, and setResolving itself — there
        // is no already-called in-tree host here, so this
        // slice's closure is complete as round 21 recorded.)
        val catchStrict = strictCode(cancellationCatch)
        assertEquals(
            "the cancellation catch must hold exactly one " +
                "conditional under the strict lexical strip " +
                "as well",
            1,
            keywordCount(catchStrict, "if"),
        )
        assertEquals(
            "the cancellation catch must hold no when, while, " +
                "for, or local fun under the strict lexical " +
                "strip either",
            0,
            keywordCount(catchStrict, "when") +
                keywordCount(catchStrict, "while") +
                keywordCount(catchStrict, "for") +
                keywordCount(catchStrict, "fun"),
        )
        assertEquals(
            "the cancellation catch's call inventory under " +
                "the strict lexical strip must match the " +
                "legacy inventory exactly",
            listOf("currentCoroutineContext", "setResolving"),
            callInventory(catchStrict),
        )
        // Round 24: infix ban, as in the F1 pin — an infix
        // call in the catch routes a clear past both
        // parenthesis-based inventories.
        assertEquals(
            "the cancellation catch must hold no infix call " +
                "under the strict lexical strip",
            emptyList<String>(),
            infixCalls(catchStrict),
        )
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /**
     * Strips `//` line comments (string-literal aware) so the
     * round-21 inventories count CODE, not prose: the slices'
     * comments legitimately contain words like "when" and
     * "while", and parenthesised asides, that are not constructs
     * or calls. The slices' string literals contain no `//`.
     */
    private fun codeOnly(text: String): String =
        text.lines().joinToString("\n") { line ->
            val out = StringBuilder()
            var inString = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (inString) {
                    out.append(c)
                    if (c == '\\' && i + 1 < line.length) {
                        out.append(line[i + 1])
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                    i++
                    continue
                }
                if (c == '"') {
                    inString = true
                    out.append(c)
                    i++
                    continue
                }
                if (c == '/' && i + 1 < line.length && line[i + 1] == '/') break
                out.append(c)
                i++
            }
            out.toString()
        }

    private data class LexFrame(
        val mode: String,
        val template: Boolean = false,
        var depth: Int = 0,
    )

    /**
     * Round 22: a whole-text lexical strip — removes line
     * comments, (nested) block comments, regular and raw
     * string contents, and char literals, while keeping code
     * (including string-template expressions) and newlines.
     * Unlike [codeOnly]'s line-wise scan, its state carries
     * across lines, so a `//` inside a block comment or a raw
     * string cannot eat the real code that follows the
     * comment's end or the string's closing quotes on that
     * line (the two round-22 evasions). Unterminated input
     * (which cannot occur in compiled production text) simply
     * strips to the end.
     */
    private fun strictCode(text: String): String {
        val out = StringBuilder()
        val stack = mutableListOf(LexFrame("code"))
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
                            stack.add(LexFrame("code", template = true))
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
                            stack.add(LexFrame("code", template = true))
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
                            stack.add(LexFrame("raw"))
                            out.append("\"\"")
                            i += 3
                        }
                        c == '"' -> {
                            stack.add(LexFrame("string"))
                            out.append('"')
                            i++
                        }
                        c == '\'' -> {
                            stack.add(LexFrame("char"))
                            out.append('\'')
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

    private fun keywordCount(code: String, keyword: String): Int =
        Regex("\\b" + keyword + "\\b").findAll(code).count()

    /**
     * The sorted inventory of call names in [code]: identifiers
     * (including receiver chains like `diagnostics.log` and
     * `resolveJob?.cancel`) followed by `(`, excluding the
     * conditional keywords, which the keyword counts above
     * account for separately. Round 23: the scan first strips
     * backticks (a backticked callee `` `helper`() `` is a
     * plain call once the delimiters are gone) and also
     * matches the callee shapes round 23 proved invisible to
     * the old `name(`-only regex — explicit type arguments
     * (`helper<Boolean>(…)`), an indexed callable
     * (`helpers[0](…)`), and a parenthesised callable value
     * (`(helperVal)(…)`) — so a clear routed through an
     * outside helper enters the inventory under any spelling
     * Kotlin will execute.
     *
     * Round 24: three more shapes the round-23 regex missed,
     * all proven live (green) and execution-proven by a
     * compiled toy. A trailing-lambda call (`helper { … }`)
     * has no parentheses at all: a chain directly followed
     * by `{` is recorded. A callable reference (`::helper`)
     * has no call shape of its own, and the parenthesised
     * form `( ::helper )()` defeated the paren-callee
     * pattern (which expects a chain right after the paren):
     * a `::name` occurrence is recorded wherever it appears
     * — these slices have no legitimate reference use, so
     * recording the name itself is fail-closed and catches
     * the reference under every invocation form built on
     * it. An infix call (`1 helper 2`) has neither
     * parentheses, a dot, nor a brace: it is covered not
     * here but by [infixCalls], asserted empty on the
     * strict views, because literal prose in the legacy
     * view could pose as infix operands.
     *
     * Round 25: the round-24 trailing-lambda pattern keyed
     * the brace to the chain itself, so three more shapes
     * routed a clear past both inventories (all green in
     * round 25, execution proven by a compiled toy): an
     * indexed callable's trailing lambda
     * (`helpers[0] { }` — the brace follows the bracket,
     * not the chain), a generic callee's trailing lambda
     * (`helper<Unit> { }` — the brace follows the type
     * arguments), and a parenthesised callable value's
     * trailing lambda (`(helperVal) { }` — the brace
     * follows the paren, which the round-23 paren-callee
     * pattern only matches before a `(`). The
     * trailing-lambda pattern now allows the same generic
     * and indexed segments as the direct pattern before
     * the brace, and the parenthesised form is recorded
     * by its own pattern. The member-chain form
     * (`gate.clearResolvingNow { }`) was re-verified in
     * round 25 to record the full chain
     * (`gate.clearResolvingNow`) and fail, as was a bound
     * callable reference (`this::helper`), which the
     * reference pattern records by bare name.
     *
     * Round 26: the backtick strip fed the keyword
     * filter a blind spot — a callee whose name is a
     * backticked KEYWORD (`else`, `finally`) was stripped
     * to the bare keyword, recorded, and then discarded
     * by the filter below, so a call of its result
     * (`else`()()) or a lambda passed to its result
     * (`finally`()({ })) executed an outside clearer
     * while the inventory recorded nothing at all
     * (proven live in round 26; execution proven by a
     * compiled toy). Backticked identifiers are now
     * substituted with a prefixed token before scanning,
     * so a keyword-spelled callee is recorded under a
     * name no filter drops and no control construct can
     * produce.
     *
     * Round 27: the round-26 prefix (`bt_`) produced a
     * token a PLAIN callee can also produce — a function
     * literally named `bt_else` and a backticked `else`
     * coexist in one file, execute differently, and the
     * inventory recorded both as `bt_else` (compiled
     * toy). The prefix is now `bt$`: `$` cannot occur
     * in a plain Kotlin identifier (the compiler splits
     * `bt$else` at the `$`), so no plain callee's name
     * can ever equal a substituted token; the chain
     * pattern below accepts `$` inside a name so the
     * token is captured whole (e.g. `bt$else`).
     */
    private fun callInventory(code: String): List<String> {
        // Round 28: the round-27 `bt$` prefix is not
        // injective — a backticked identifier can itself
        // contain the prefix (`` `bt$else` `` is a legal,
        // distinct callee from `` `else` ``), and the
        // round-27 substitution regex only matched plain
        // identifier characters inside the backticks, so
        // `` `bt$else` `` fell through to the bare backtick
        // strip and produced the very token `bt$else` the
        // substitution assigns to `` `else` `` (compiled
        // toy, round 28: both inventories [bt$else],
        // identical=true, different execution). A
        // backticked name containing a space (`` `else
        // where` ``) fell through the same way and was
        // recorded as the plain callee `where`. The
        // substitution now matches ANY backticked content
        // and encodes it as `bt$` plus the $-separated
        // hex of each character: `$` cannot occur in a
        // plain identifier, distinct backticked contents
        // encode distinctly (each $-segment is one
        // character's hex, in order), and no plain or
        // backticked callee can produce another's token.
        val cleaned = Regex("`([^`]*)`")
            .replace(code) { match ->
                "bt$" + match.groupValues[1].map { ch ->
                    "$" + ch.code.toString(16).padStart(2, '0')
                }.joinToString("")
            }
            .replace("`", "")
        val chain =
            "[A-Za-z_][A-Za-z0-9_$]*(?:\\?\\.[A-Za-z_][A-Za-z0-9_$]*" +
                "|\\.[A-Za-z_][A-Za-z0-9_$]*)*"
        val direct = Regex(
            "($chain)(?:<[^<>\\n]*>)?(?:\\[[^\\]\\n]*\\])?\\s*\\(",
        ).findAll(cleaned).map { it.groupValues[1] }
        val parenCallee = Regex(
            "\\(\\s*($chain)\\s*\\)\\s*\\(",
        ).findAll(cleaned).map { it.groupValues[1] }
        val trailingLambda = Regex(
            "($chain)(?:<[^<>\\n]*>)?(?:\\[[^\\]\\n]*\\])?\\s*\\{",
        ).findAll(cleaned).map { it.groupValues[1] }
        val parenTrailingLambda = Regex(
            "\\(\\s*($chain)\\s*\\)\\s*\\{",
        ).findAll(cleaned).map { it.groupValues[1] }
        val callableReference = Regex(
            "::($chain)",
        ).findAll(cleaned).map { it.groupValues[1] }
        return (direct + parenCallee + trailingLambda +
            parenTrailingLambda + callableReference)
            .filter {
                it !in setOf(
                    "if", "when", "while", "for", "catch",
                    "else", "try", "do", "finally",
                )
            }
            .sorted()
            .toList()
    }

    /**
     * Round 24: identifiers in infix position — a bare name
     * between two operands on one line (`1 helper 2`,
     * `track helper other`). An infix call's operator name
     * carries no parenthesis, dot, or brace for a call
     * pattern to key on, so the pins assert this list is
     * EMPTY on the strict views of every pinned slice (the
     * strict view blanks literal contents, so prose inside
     * a string cannot pose as an operand; keyword operands
     * and keyword operators — `else`, `is`, `in` — are not
     * calls and are excluded).
     */
    private fun infixCalls(code: String): List<String> {
        val keywords = setOf(
            "if", "when", "while", "for", "catch", "else", "try",
            "do", "finally", "return", "throw", "val", "var",
            "fun", "in", "is", "as", "null", "true", "false",
            "this", "super", "class", "object", "break", "continue",
        )
        val pattern = Regex(
            "(?<=[0-9A-Za-z_\\])}\"])[ \\t]+" +
                "([A-Za-z_][A-Za-z0-9_]*)[ \\t]+" +
                "([A-Za-z_][A-Za-z0-9_]*|[0-9][0-9A-Za-z_.]*|\"|\\()",
        )
        return pattern.findAll(code)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (name, next) -> name !in keywords && next !in keywords }
            .map { it.first }
            .toList()
    }

    private fun controller(): String =
        mainSource("java/dev/cyberdogs/offleash/playback/QueueController.kt")

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
