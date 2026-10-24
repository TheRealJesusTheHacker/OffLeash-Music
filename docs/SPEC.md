# OffLeash Music — Phase 1 Specification

**Status:** Phase 1 build spec, 2026-10-23. This document is the contract the
code is built against. If the code and this spec disagree, one of them is
wrong and the fix lands in the open (see `docs/AUDITS.md`).

OffLeash Music is a Cyberdogs YouTube Music client for Android: an
independent, clean-room implementation (GPL-3.0, our own code) that plays
from YouTube Music's servers through YouTube's unofficial internal API
("youtubei" — the same surface the music.youtube.com web app uses). It
hosts nothing, stores nothing on any server, and sells nothing.

---

## 1. Recon evidence (verified live from the build sandbox, 2026-10-23)

These are facts the founding probes established and the Phase 1 coordinator
re-verified first-hand. The design is built around them.

| Surface | Context | Result |
|---|---|---|
| `browse` (home, `FEmusic_home`) | WEB_REMIX | ✅ Real data (418 KB; shelves "Quick picks", "Throwbacks") |
| `search` (+ filter chips) | WEB_REMIX | ✅ Real data; filter chips carry their own params |
| `browse` album / playlist / artist / charts / moods | WEB_REMIX | ✅ Real data (album shelf, 52-track playlist, 40 mood categories) |
| `next` (radio mix `RDAMVM<videoId>`) | WEB_REMIX | ✅ 50-item queue |
| `player` | **iOS `20.10.4`** | ✅ playability OK, **27 formats, all direct (unciphered)**, 5 audio; best audio itag 251 (opus, ~137 kbps); ranged GET → HTTP 206, real audio bytes |
| `player` | iOS `19.29.1` | ❌ HTTP 400 "Precondition check failed." — stale client versions are refused |
| `player` | WEB_REMIX / WEB | ❌ playability UNPLAYABLE anonymously |

Design consequences:

- **Browse/search run on the WEB_REMIX context; playback runs on the iOS
  context.** Contexts are configuration, not code paths — a context is a
  (clientName, clientVersion, userAgent, apiKey, host) record, so when
  Google retires a version the fix is a data update.
- **Client-version freshness is a maintenance duty.** The 19.29.1 refusal is
  the shape of things to come: the app must surface it as a named error
  (see §6), never as a hang.
- Playback URLs are used exactly as returned (direct, unciphered). There is
  **no cipher/signature deciphering anywhere in this codebase** — if a
  context ever returns ciphered-only formats, the resolver reports
  `NoPlayableStream` (§6) instead of pretending.

## 2. Architecture

Two Gradle modules:

- **`:core`** — pure Kotlin/JVM, no Android imports, no coroutines. Owns:
  the API client, all response parsing, the playback stream resolver, the
  play-queue logic, local-playlist data shapes, and the error taxonomy.
  Public API never leaks JSON types: transport is string-in/string-out.
  Everything in `:core` is JVM-testable against recorded fixtures.
- **`:app`** — Android. Owns: Compose UI, Media3 playback service +
  session, OkHttp transport, on-device storage (local playlists, recents),
  the replay cache. The app is a shell around `:core`; no youtubei parsing
  lives here.

Data flow for a tap on a track:

```
UI tap → QueueController.setQueue(tracks, index)
       → core StreamResolver.resolve(videoId)        [IO thread]
       → YoutubeiClient.player(videoId)  (iOS context)
       → resolver picks best direct audio format
       → MediaItem(uri = stream url, mediaId = videoId) → ExoPlayer
       → CacheDataSource (replay cache) under the player
```

## 3. API layer (`:core`)

- `HttpTransport` — one method: `post(url, headers, body): HttpResponse`.
  The app implements it with OkHttp; tests implement it with fixtures.
- `YoutubeiClient(transport)` — synchronous/blocking by design; callers
  (the app) run it on an IO dispatcher.
  - `home(): List<Section>` — browse `FEmusic_home`
  - `charts(): List<Section>` — browse `FEmusic_charts`
  - `moods(): List<MoodCategory>` — browse `FEmusic_moods_and_genres`;
    a category opens via browse `FEmusic_moods_and_genres_category` + its
    params → `moodPlaylists(category): List<Section>`
  - `search(query, filter): SearchResults` — filter chips (All / Songs /
    Videos / Albums / Artists / Playlists) use the params the API itself
    publishes; the five pinned params live in `YoutubeiContexts` and are
    fixture-verified against the chips in the recorded search response
  - `album(browseId): AlbumPage`, `artist(browseId): ArtistPage`,
    `playlist(browseId, params?): PlaylistPage`
  - `related(videoId): List<Track>` — `next` with playlistId
    `RDAMVM<videoId>`, audio-only; skips the seed track itself
  - `streamInfo(videoId): StreamInfo` — iOS-context player + resolver
- **Parsing strategy:** navigate the JSON tree (kotlinx-serialization
  `JsonElement`) with small, total helpers — every lookup is nullable-safe,
  every renderer we don't understand is skipped, never crashed on. Track
  rows are read from `musicResponsiveListItemRenderer` (flexColumns:
  title / artists / album; fixedColumns: duration; `playlistItemData.videoId`
  or the row's navigation endpoint). Cards come from
  `musicTwoRowItemRenderer`. Thumbnails: largest by width wins.
- Models are plain data classes (`Track`, `AlbumSummary`, `ArtistSummary`,
  `PlaylistSummary`, `Section`, page types) — see `:core` `model` package.

## 4. Playback

- **Resolver rule:** from the iOS player response, consider formats with a
  direct `url`; prefer audio-only by (bitrate desc), preferring
  `audio/mp4` at equal bitrate for device compatibility; muxed formats are
  a last resort. No direct formats at all → `NoPlayableStream`.
- **Player:** Media3 ExoPlayer inside a `MediaSessionService`; media
  notification + lock-screen controls come from the session. Queue
  semantics (order, shuffle, repeat off/all/one) live in `:core`
  `PlayQueue` (pure, unit-tested); the app's `QueueController` drives the
  player one resolved item at a time and resolves the next item when the
  current ends. Repeat-one is the player's own repeat mode.
- **On resolve failure during playback:** the queue stops, the error state
  carries the named failure (§6) and the UI shows it in the player and a
  snackbar. No silent skipping — a skipped track the user never hears about
  is a lie by omission.
- **Replay cache:** Media3 `SimpleCache` (512 MB, least-recently-used) in
  the app's cache dir, wrapped by `CacheDataSource` so replays and seeks
  hit local bytes first. The cache is a replay accelerator, not a download
  feature: no user-facing "downloaded library" exists in Phase 1.
- Stream URLs expire (hours). The resolver is consulted at play time, never
  persisted; recents/queues store videoIds, not URLs.
- **Player fetch contract (2026-10-23, playback hunt):** stream fetches go
  through the shared OkHttp stack — the same client that resolved the URLs
  fetches them (`CacheDataSource` → `OkHttpDataSource` on the shared
  `OkHttpClient`), because googlevideo binds stream URLs to the resolving
  network identity (`ip=` param). A Range header is always present on
  stream GETs: a scoped OkHttp application interceptor adds
  `Range: bytes=0-` to any GET on a `*.googlevideo.com` host that carries
  no Range header yet (some formats refuse a range-less GET with HTTP
  403; see `docs/AUDITS.md`, 2026-10-23 playback hunt). On an HTTP 403
  player error the track is re-resolved and retried exactly once; a
  second 403 surfaces the named §6 player error — no retry loop.

## 5. On-device data

- **Local playlists:** a single JSON document (`playlists.json`, app
  files dir) — `LocalPlaylist(id, name, createdAtEpochMs, tracks)`.
  Create / rename-lite (delete + recreate is *not* acceptable: rename is
  supported) / add / remove / move-up / move-down. Stored tracks are full
  `Track` records (minus stream URLs, which are never stored).
- **Recently played:** last 50 tracks, most-recent-first, deduped by
  videoId, same JSON store family (`recents.json`).
- Nothing leaves the device. There is no account, no sync, no telemetry.

## 6. Failure honesty — the error taxonomy

Every failure the user can hit has a name, a cause class, and plain-language
copy. Spinners always have a timeout and an error state behind them.

| Error (`:core`) | Trigger | User copy (shape) |
|---|---|---|
| `ContextRejected` | HTTP 4xx from youtubei with an error body (e.g. 400 "Precondition check failed.") | "YouTube refused the app's request — the API has likely changed. (server said: …)" |
| `PlayerRefused` | `playabilityStatus` ≠ OK (UNPLAYABLE / LOGIN_REQUIRED / ERROR) | "YouTube won't play this track: <reason from the server>" |
| `NoPlayableStream` | OK status but no direct (unciphered) audio URL | "No playable stream for this track — YouTube served only locked formats." |
| `NetworkFailure` | transport throws / HTTP 5xx | "Couldn't reach YouTube Music — check your connection." |
| `ParseFailure` | response shape unrecognizable | "YouTube Music answered in a shape this app doesn't understand yet — the API has likely changed." |

Player-stage failures (Media3 `PlaybackException`, mapped by
`ErrorMessages.playerMessage(errorCode, httpStatus)`) have their own rows —
the player stage is part of this taxonomy, not a catch-all:

| Player error | Trigger | User copy (shape) |
|---|---|---|
| HTTP 403 | `ERROR_CODE_IO_BAD_HTTP_STATUS` + 403 (after the one §4 re-resolve retry) | "YouTube refused this track's stream (HTTP 403) — the stream link was likely issued for a different network address or has expired. Try again; if it keeps happening, open About → Playback diagnostics and send a screenshot." |
| HTTP 404 | `ERROR_CODE_IO_BAD_HTTP_STATUS` + 404 | "YouTube says this track's stream is gone (HTTP 404) — the link expired. Try again to fetch a fresh one." |
| HTTP other | `ERROR_CODE_IO_BAD_HTTP_STATUS` + any other status | "YouTube's stream server answered HTTP \<status\>. Try again." |
| Network / content-type / read-position | `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` / `_TIMEOUT` / `ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE` / `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE` | "Couldn't load this track's audio — the connection failed. Check your connection and try again." |
| Format / decoder | `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` / `ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED` / `ERROR_CODE_DECODER_INIT_FAILED` / `ERROR_CODE_DECODING_FAILED` | "This track's audio format can't be played on this device." |
| Anything else | any other player error code | "Something went wrong playing this track (player error \<code\>). Try again." — the code is always named, so a screenshot is actionable. |

Browse/search/detail failures reuse the same taxonomy and render as an
error panel with a Retry button — never a blank screen, never an endless
spinner.

## 7. Phase 2 boundary

Phase 1 contains **no sign-in code, no Google session/cookie handling, no
credential storage — anywhere, in any form.** The client contexts are
anonymous and public (they ship in every web page load of
music.youtube.com). If a Phase 1 task appears to need a key, a login, or a
user token, the correct move is to stop and report: that is Phase 2
territory and it gets its own security pass first (see `docs/ROADMAP.md`).

## 8. Testing & CI

- `:core` JVM tests run against the recorded fixtures in
  `core/src/test/resources/fixtures/` (real responses captured 2026-10-23,
  plus two clearly-derived synthetic fixtures: an UNPLAYABLE player
  response and a ciphered-only player response). Parsers are pinned to
  fixture truth (section titles, track counts, known titles/ids).
- The resolver is proven on fixtures: picks the direct audio format;
  refuses UNPLAYABLE with its reason; refuses ciphered-only with
  `NoPlayableStream`; HTTP error bodies map to `ContextRejected`.
- CI (`.github/workflows/android.yml`, actions pinned to full commit SHAs,
  fleet standard): `:core:test`, `:app:testDebugUnitTest`, `assembleDebug`,
  debug APK uploaded as a workflow artifact. A separate non-gating
  live-smoke job replays the §1 probes (browse/search/player/stream range
  GET) and reports the true result. The live smoke now also probes a
  range-less GET of the chosen stream URL informationally (printed as
  INFO, never a failure): a range-less refusal is the known `gir=yes`
  shape the §4 fetch contract defends against, and watching it keeps
  that defence honest if googlevideo behaviour changes.
- The debug APK is a **personal-use build artifact**, never a release.

## 9. Branding

Cyberdogs: black-and-tan from the Doberman mark (asset:
`app/src/main/res/drawable-nodpi/cyberdogs_logo.jpg`, shown on the About
screen). Palette (Material 3 dark scheme): warm near-black background
`#0D0B09`, surface `#16120E`, raised surface `#211A13`, primary tan
`#D9A441`, lighter tan `#EBCB8B`, text `#F4EDE1`, secondary text `#B9AB94`,
error = fleet danger red `#B03A2E` on white. No purple anywhere (fleet
rule: violet is Beerus-only). All text pairings ≥ 4.5:1.
