# OffLeash Music — Roadmap

## Decisions on record

- **License: GPL-3.0 — DECIDED BY OWNER (2026-10-23).** Landed in the repo the same day: root `LICENSE` carries the canonical GPL-3.0 text under the holder line "Copyright (c) 2026 Sentinel Safeguard LLC", and the README's interim "license undecided" line was replaced with a GPL-3.0 section. Between repo creation and this decision the project was unlicensed (all rights reserved by default); nothing shipped in that window beyond the seed README. This call is closed.
- **Sign-in is Phase 2, by the owner's design.** No account code exists until credential handling gets its own dedicated security pass.
- **Originality is a standing rule, not a phase.** OffLeash is an independent implementation. Other open clients in the category are concept references only — no code is copied or adapted from them, and the project's GPL-3.0 license covers *our* work; it changes nothing about where our code comes from.

## Phase 1 — the player (BUILT, 2026-10-23)

Shipped as code + a green CI gate, no release made. Evidence on record:

- `:core` JVM suite: 31/31 tests green, pinned to 9 recorded youtubei fixtures + 2 synthetic fixtures (fixtures are named as such); playback-resolution logic proven on fixtures (direct audio chosen by bitrate with audio/mp4 tie-break; `PlayerRefused` on UNPLAYABLE; `NoPlayableStream` on ciphered-only; fake-transport 400 → `ContextRejected`, throw → `NetworkFailure`; shuffle permutation seeded Random(42)).
- CI (`.github/workflows/android.yml`, SHA-pinned fleet actions): runs `:core:test` + `:app:testDebugUnitTest` and assembles the debug APK on every push to `main`. The `live-smoke` job (non-gating by design) replays the anonymous probes — browse, search, iOS-context player, ranged stream GET — and passes from GitHub's runners too.
- Debug APK artifact from the green run: a personal-use build only (Android Debug signer), held in `~/workspace/offleash-apk/` with size + SHA-256 recorded in the coordinator's report — never called or treated as a release.

Honest remainder: no agent can exercise the on-device surface (media notification, lock-screen controls, background playback across task removal) from CI — the owner's install test on a real phone is the only runtime proof. That verdict is still open.

**Playback hunt (2026-10-23, branch `hunt-playback`):** the owner's install test found the Phase 1 failure — browse and queue work, playback fails. The hunt (full record in `docs/AUDITS.md`) proved agent-side that a range-less first stream fetch is refused with HTTP 403 on the resolver's chosen format, aligned the player onto the shared OkHttp stack with a guaranteed Range header, replaced the catch-all player toast with named §6 player errors plus a one-time 403 re-resolve retry, and added a Playback diagnostics screen with a Test playback probe. It ships as a separate `hunt` flavor ("OffLeash Hunt", `dev.cyberdogs.offleash.hunt`) so it can sit beside the standard build. **The remaining on-device proof is the owner's diagnostics screenshot** — from the hunt build on his phone and network, after an attempt (failed or successful). Until that lands, on-device playback stays honestly unproven.

Anonymous, no account:

- Browse: home feed, charts, moods & genres (categories → playlists)
- Search with type filters (songs / videos / albums / artists / playlists)
- Album, artist, and playlist pages with real track lists
- Playback: Media3/ExoPlayer, background service with media notification, play queue (next/previous, shuffle, repeat)
- On-device local playlists (create / add / remove)
- Replay cache for streams
- Named, plain-language errors when YouTube's unofficial API changes shape or refuses a track — never an endless spinner

## Phase 2 — accounts (gated on its security pass)

- Google sign-in for YouTube Music account features (library, likes, personal playlists)
- The credential/storage design is reviewed and recorded in `docs/AUDITS.md` *before* any sign-in code lands

## Later / open questions

- Lyrics providers
- SponsorBlock
- Desktop builds (the category is cross-platform; OffLeash is Android-first and desktop is undecided)
