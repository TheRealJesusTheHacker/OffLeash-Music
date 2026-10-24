# OffLeash Music — Roadmap

## Decisions on record

- **License: GPL-3.0 — DECIDED BY OWNER (2026-10-23).** Landed in the repo the same day: root `LICENSE` carries the canonical GPL-3.0 text under the holder line "Copyright (c) 2026 Sentinel Safeguard LLC", and the README's interim "license undecided" line was replaced with a GPL-3.0 section. Between repo creation and this decision the project was unlicensed (all rights reserved by default); nothing shipped in that window beyond the seed README. This call is closed.
- **Sign-in is Phase 2, by the owner's design.** No account code exists until credential handling gets its own dedicated security pass.
- **Originality is a standing rule, not a phase.** OffLeash is an independent implementation. Other open clients in the category are concept references only — no code is copied or adapted from them, and the project's GPL-3.0 license covers *our* work; it changes nothing about where our code comes from.

## Phase 1 — the player (in progress)

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
