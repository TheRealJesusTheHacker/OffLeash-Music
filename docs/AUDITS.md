# OffLeash Music — Audit Log

## 2026-10-23 — Playback hunt (on-device failure)

**Trigger:** on the owner's phone, Phase 1 browses and queues, then playback fails with a toast reading only "Something went wrong playing this track. Try again." Resolution had succeeded; Media3 failed after the hand-off.

**The defect:** `QueueController.onPlayerError` was a generic catch-all that discarded the `PlaybackException` entirely — no error code, no HTTP status, no cause. That is a SPEC §6 taxonomy hole: the player stage had no named errors at all, so a diagnosable server refusal surfaced as a content-free toast and left nothing to act on. Fixed on branch `hunt-playback`: `ErrorMessages.playerMessage(errorCode, httpStatus)` now names every player-stage shape (see SPEC §6 player rows), the fallback still names the numeric error code, an HTTP 403 triggers exactly one re-resolve-and-retry for the same track, and a Playback diagnostics recorder + screen (About → Playback diagnostics, with a Test playback probe track) records the last attempt stage by stage.

**Probe evidence (agent-side, before the fix):**
- Fuller coordinator probe (owner's track, videoId `lY5V4hSLWY8`, fresh resolution, **both itag 251 and itag 140, both `gir=yes`**):

  | Request shape | Result |
  |---|---|
  | GET with **no Range header** | **HTTP 403** (deterministic, repeated) |
  | GET with `Range: bytes=0-` (**open-ended**) | **HTTP 403** |
  | GET with `Range: bytes=0-2047` (**bounded**) | **206** |
  | GET with a **bounded mid-file range** | **206** |

  The server requires a **bounded** range (an explicit end byte). This corrects the first probe's summary ("any Range form → 206" — an open-ended range does *not* pass) and its note on itag 140: with a fresh `gir=yes` URL, itag 140 is refused in the same shapes as 251.
- Media3's data sources produce exactly the refused shapes: no Range header when position is 0 and length is unset (`HttpUtil.buildRangeRequestHeader` returns null), and an open-ended `bytes=N-` when a length is unset at a non-zero position — so the player's fetches were refused by shape, not by content.
- Every prior smoke always sent a bounded Range explicitly; the range-less and open-ended seams were never exercised. The live smoke now also probes a range-less GET and an open-ended GET informationally (non-gating; see SPEC §8).
- Both stream URLs embed an `ip=` param: googlevideo binds streams to the resolver's egress identity, so resolver/player stack divergence is a real risk, not a theory.
- UA is **not** the gate: no-UA and foreign-UA fetches return 206 when the range is bounded.

**Hypotheses verdicts:**
- **H1 — resolver/player stack divergence (OkHttp vs HttpURLConnection, address-family choices on an IPv6-first carrier): plausible-contributor.** Fixed by alignment: stream fetches now go through the shared OkHttp stack (`media3-datasource-okhttp`, `CacheDataSource → OkHttpDataSource` on the shared client) — the same stack that resolved the URLs fetches them.
- **H2 — User-Agent mismatch is the gate: refuted.** No-UA and foreign-UA bounded-range fetches return 206 (see probe evidence above).
- **H3 — replay-cache interference: not indicated.** The upstream failure is cold-cache-shaped, and the cache factory sets `FLAG_IGNORE_CACHE_ON_ERROR` — the cache falls back to the network source on its own errors and is never a gate.
- **H4 — resolver/browse failure misreported as a player failure: refuted.** The toast string existed only in `QueueController.onPlayerError`; its provenance proves resolution succeeded and the failure was post-hand-off, in the player.
- **H5 — the player's first fetch shape (range-less or open-ended) is refused; bounded ranges required: PROVEN agent-side.** Deterministic on the owner's exact track, both itags (above): no-Range → 403, open-ended `bytes=0-` → 403, bounded → 206. An earlier draft of this hunt briefed a "add `bytes=0-` when absent" interceptor — that would **not** have fixed the phone, and the coordinator's fuller probe caught it before merge. Fixed instead by a scoped OkHttp application interceptor (`RangeInterceptor`, a range *normalizer*) on GET requests to `*.googlevideo.com` hosts only — never on youtubei POSTs, never on other hosts: no Range → `bytes=0-1048575` (1 MiB window, `STREAM_RANGE_WINDOW_BYTES`); open-ended `bytes=N-` → `bytes=N-(N+1048575)`; already bounded → untouched. Its normalization and scoping are unit-tested exactly (absent→bounded-from-0, open-ended→bounded preserving start, bounded→unchanged, POST untouched, non-googlevideo untouched).

**Remaining proof:** the on-device verdict is still the owner's — the hunt build (`hunt` flavor, applicationId `dev.cyberdogs.offleash.hunt`, label "OffLeash Hunt") plus the diagnostics screenshot from a failed or successful attempt on his phone and network. No agent-side result substitutes for that.

## 2026-10-23 — License landing (pre-code)

- **License: GPL-3.0 — DECIDED BY OWNER (2026-10-23)** via the owner's decision widget ("GPL-3.0 — copyleft shield"). Root `LICENSE` added: canonical GPL-3.0 text, copyright line in the fleet's established holder form — "Copyright (c) 2026 Sentinel Safeguard LLC" (Cyberdogs authorship is carried by the branding/README, not by a new legal holder string). The README's interim "license undecided" line was replaced with a GPL-3.0 section in the same commit.
- **Dependency ledger** seeded at `docs/LICENSES.md`, including the compatibility note: the project's GPL-3.0 is compatible with the planned Apache-2.0 stack (Kotlin / Compose / Media3 and friends) — Apache-2.0 components may be combined into a GPL-3.0 work; the combined work ships under GPL-3.0.
- **Originality rule re-affirmed at the license landing:** our GPL-3.0 licenses *our own* code. It does not license, excuse, or describe copying code from other GPL projects — SimpMusic, InnerTune, NewPipeExtractor and peers remain concept references only, never code sources.
- Repo state at this entry: seed README + license + docs only. No application code has been pushed yet.

## 2026-10-23 — Phase 1 self-audit (build coordinator)

**Scope:** the first application-code commit plus integration fixes, `docs/SPEC.md`, CI gate, live smoke, and the personal-use debug APK.

**First-hand verification (not worker claims):**
- The coordinator re-ran the entire `:core` suite from the delivered sources with a locally assembled classpath: **31/31 green** (9 fixture, 8 error-mapping, 4 resolver, 10 queue). Worker A's tally reconciles exactly.
- Every pushed commit was verified server-side: recursive tree diff against locally computed git-blob SHAs — zero mismatches on every push.
- CI on HEAD `b7552188` (the code commit): **build success, live-smoke success** (runs listed below). The green-run APK was downloaded and inspected: `aapt` badging confirms package `dev.cyberdogs.offleash`, versionCode 1 / `0.1.0-phase1`, compileSdk 35, minSdk 26, targetSdk 35; `apksigner` confirms the Android Debug signer; the APK contains the Cyberdogs logo asset.
- The live smoke passed twice: from the sandbox and from GitHub CI runners (anonymous browse → search → iOS 20.10.4 player → HTTP 206 ranged stream GET).

**Findings, all fixed:**
1. *Toolchain pairing (coordinator's own skeleton):* the initial AGP 8.5.2 / compileSdk 34 could not compile against Media3 1.5.1, which requires compileSdk 35. Fixed to AGP 8.7.2 + SDK 35 (CI installs android-35); minSdk stays 26.
2. *Contract friction between the workers:* the core worker shipped error subclasses as top-level classes (`ContextRejected` etc.) with trimmed fields (no `httpStatus` on `ContextRejected`, `networkDetail` instead of `cause` on `NetworkFailure`), while the app worker referenced them as `OffleashException.*` nested classes — plus three nullable `artist`/`author` uses in feed cards and detail meta. All 12 compile errors fixed by aligning the app side to the proven core side (top-level classes; `?: ""` / `?.` at the nullable sites). Nothing about user-facing behavior changed.
3. *Worker-B report overstated the About license surface:* the report claimed a bundled full-GPL-text viewer and a third-party credits section; the delivered `AboutScreen` had neither. Fixed by the coordinator: the canonical repo `LICENSE` text is now bundled byte-identical as `res/raw/gpl3.txt` behind a "Read the full license" toggle, the third-party credits list (all Apache-2.0) is rendered with a pointer to this ledger, and the sign-in-is-a-later-phase line is present. Worker-report skepticism vindicated — claims were re-checked against the delivered files.
4. *The coordinator's own push dropped the seed files:* the first application-code push built its tree from the staging directory only, silently removing `LICENSE`, `README.md`, and the seed docs from the repo. Caught within the same session by the server-side recursive-tree verification, restored at the original blob SHAs in the very next commit. Lesson recorded: every push's recursive tree is diffed against expectation before the push is declared done — done for every later push.

**Noted, not changed:** the app's replay cache sets `FLAG_IGNORE_CACHE_ON_ERROR` (in `OffleashApp`'s `CacheDataSource.Factory`): if the cache layer itself errors, playback falls back to the network source instead of failing — the cache is an optimization, never a gate, and the SPEC §6 named errors are unaffected (they are raised by the client/resolver, not the cache). *(Correction, same day: the first draft of this entry misstated the flag as unset; reconciling the app worker's report against the delivered source showed it set. The code was right; the sentence was wrong, and is fixed here.)* The `Contexts` carry their own apiKey because the iOS 20.10.4 version+key pairing is load-bearing (a stale version is refused with HTTP 400) — a context change is a code change, as the spec already says.

**Open items:** the on-device runtime proof (owner's install test; see ROADMAP). Phase 2 (sign-in) is unchanged and gated on its own security pass — no sign-in code, no credential handling, anywhere in this build.

**CI runs on record:** 40044353755 (HEAD `58fa44ec` — build failed at AAR metadata against the SDK 34 skeleton; superseded by the toolchain fix), 40044599083 (HEAD `4162a0ce`, SDK-35 toolchain fix — build failed at the 12 app-compile errors above; superseded), 40044844778 (HEAD `b7552188`, integration fixes — **build success, live-smoke success**).
