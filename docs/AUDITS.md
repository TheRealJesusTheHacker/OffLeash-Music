# OffLeash Music — Audit Log

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

**Noted, not changed:** the app's `CacheDataSource` deliberately does not set `FLAG_IGNORE_CACHE_ON_ERROR` — cache write failures surface as errors rather than being masked; that's the SPEC §6 honesty stance, by design. The `Contexts` carry their own apiKey because the iOS 20.10.4 version+key pairing is load-bearing (a stale version is refused with HTTP 400) — a context change is a code change, as the spec already says.

**Open items:** the on-device runtime proof (owner's install test; see ROADMAP). Phase 2 (sign-in) is unchanged and gated on its own security pass — no sign-in code, no credential handling, anywhere in this build.

**CI runs on record:** 40044353755 (HEAD `58fa44ec` — build failed at AAR metadata against the SDK 34 skeleton; superseded by the toolchain fix), 40044599083 (HEAD `4162a0ce`, SDK-35 toolchain fix — build failed at the 12 app-compile errors above; superseded), 40044844778 (HEAD `b7552188`, integration fixes — **build success, live-smoke success**).
