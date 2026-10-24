# OffLeash Music — Specification

**Status:** Phase 1 build spec, 2026-10-23; Phase 2 (account access) landed
the same night under its own security pass — see §10, which supersedes the
§7 boundary. This document is the contract the
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
  notification + lock-screen controls come from the session. The session
  is registered with the service via `addSession(...)` in
  `PlaybackService.onCreate` AND a process-lifetime `MediaController`
  (held by `AppContainer`) connects to the service's `SessionToken` —
  both are required wiring: Media3 posts the media notification from the
  session and promotes the service to foreground (`mediaPlayback`) while
  playback is active only for sessions it knows about. Service start is
  attempted on every play (no one-shot latch), so a play after
  task-removal restarts the service. Queue
  semantics (order, shuffle, repeat off/all/one) live in `:core`
  `PlayQueue` (pure, unit-tested); the app's `QueueController` drives the
  player one resolved item at a time and resolves the next item when the
  current ends. Repeat-one is the player's own repeat mode. A
  battery-restriction card (Player + About) deep-links to the system
  battery-optimization exemption when the app is restricted, and the
  Playback diagnostics screen shows a live "Session & battery" block
  (service running, foreground, battery state) above the attempt log.
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
  network identity (`ip=` param). Stream GETs always carry a **bounded**
  Range header: `gir=yes` stream URLs refuse both a range-less GET and
  an open-ended `bytes=N-` GET with HTTP 403, and answer bounded ranges
  with 206 (see `docs/AUDITS.md`, 2026-10-23 playback hunt). A scoped
  OkHttp application interceptor (a range normalizer, GETs on
  `*.googlevideo.com` hosts only) enforces it: no Range header →
  `bytes=0-1048575` (a 1 MiB window); open-ended `bytes=N-` →
  `bytes=N-(N+1048575)`; already bounded `bytes=N-M` → untouched. On an
  HTTP 403 player error the track is re-resolved and retried exactly
  once; a second 403 surfaces the named §6 player error — no retry loop.

## 5. On-device data

- **Local playlists:** a single JSON document (`playlists.json`, app
  files dir) — `LocalPlaylist(id, name, createdAtEpochMs, tracks)`.
  Create / rename-lite (delete + recreate is *not* acceptable: rename is
  supported) / add / remove / move-up / move-down. Stored tracks are full
  `Track` records (minus stream URLs, which are never stored).
- **Recently played:** last 50 tracks, most-recent-first, deduped by
  videoId, same JSON store family (`recents.json`).
- **Account session (Phase 2, §10):** exactly ONE store — a single file
  (`session.bin`) in the app's no-backup files directory, sealed with
  AES-256-GCM under a key held in the Android Keystore. No plaintext copy
  exists anywhere (not preferences, not a database, not a second file).
  The store is excluded from cloud backup and device-to-device transfer
  on every API level (data-extraction + legacy backup rules, pinned by a
  contract test). Cookie values and their derivatives are never logged
  and never displayed; diagnostics and the account area show identity
  display name and session health only.
- Nothing leaves the device except the owner's own authenticated
  requests to YouTube while signed in. There is no sync, no telemetry,
  no third-party endpoint.

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
| `AllRoadsRefused` (Phase 2) | signed in, and the account road AND every anonymous context refused the track | "YouTube refused this track on both paths. Signed-in path: <account road's named error> Anonymous path: <anonymous road's named error>" — both roads are always named; a track may surface an error only when every road has refused it (§10) |

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

## 7. Phase 2 boundary (historical — superseded by §10)

Phase 1 contained **no sign-in code, no Google session/cookie handling, no
credential storage — anywhere, in any form.** The client contexts are
anonymous and public (they ship in every web page load of
music.youtube.com). Phase 2 was gated on its own security pass; that pass
ran and the account build landed — the boundary is recorded here as the
Phase 1 contract, and §10 is the Phase 2 contract.

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
  range-less GET — and an open-ended GET — of the chosen stream URL
  informationally (printed as INFO, never a failure): range-less and
  open-ended refusals are the known `gir=yes` shapes the §4 fetch
  contract defends against, and watching them keeps that defence honest
  if googlevideo behaviour changes.
- The debug APK is a **personal-use build artifact**, never a release.

## 10. Phase 2 — account access (landed 2026-10-23)

The owner's product definition: "YouTube but ad free and able to play in
the background," with his liked videos, his playlists, and YouTube's
recommendations for him. Signed-out behavior is Phase 1, unchanged;
signing in only adds.

- **Sign-in.** An embedded WebView runs Google's ServiceLogin music
  flow; the app never sees the password — the WebView talks to Google
  only. Navigation is scope-enforced in code: an allowlist of
  Google/YouTube auth hosts (plus their subdomains); any other
  navigation is blocked with a plain note. Success = arrival at
  `https://music.youtube.com/`. A manual cookie-paste fallback exists
  for the WebView-loop failure class and runs through the exact same
  subset + validation pipeline.
- **Capture = a named subset, never the jar.** Exactly the named
  session family is captured: the classic seven (`SAPISID`,
  `__Secure-3PAPISID`, `APISID`, `HSID`, `SSID`, `SID`, `LOGIN_INFO`)
  plus the secure-prefixed PSID family (`__Secure-3PSID`,
  `__Secure-3PSIDTS`, `__Secure-3PSIDCC`, `__Secure-1PSID`,
  `__Secure-1PAPISID`, `__Secure-1PSIDTS`, `__Secure-1PSIDCC`,
  `SIDCC`) that carries the modern Google session (widened
  2026-10-24, sign-in hunt — see AUDITS). The WebView's own cookie
  store is wiped when a confirmation attempt completes, success or
  failure.
- **Validation with rollback, patiently.** A captured (or pasted)
  session is accepted only after the account-switcher identity call
  answers with at least one identity; the selected identity's real
  `authUser` / `pageId` are adopted (never a hard-coded index —
  brand-channel accounts live at other indices). Confirmation is
  not one-shot: it re-reads the cookie source and re-asks across a
  settling window (five attempts over ~30 s), because a fresh
  login's cookies keep landing and YouTube can take seconds to
  recognize the session (owner directive 2026-10-24: completing
  Google's login must converge to signed-in). Only an exhausted
  window fails; nothing is persisted before confirmation and any
  prior session state stands untouched — rollback by construction —
  and the user is told plainly, with the failing stage recorded in
  the sign-in diagnostics.
- **Authorization.** `Authorization: SAPISIDHASH <epochSeconds>_<sha1>`
  in the classic form: SHA-1 of `"<epochSeconds> <hashSource>
  https://music.youtube.com"`, raw epoch seconds, the scheme word in
  the header value. Hash source = `SAPISID`, else `__Secure-3PAPISID`.
  The presence rule is deliberate and unit-tested: a session is
  plausible iff a hash source is present AND (`SID` or `LOGIN_INFO`)
  is present. Absence = a signed-out state — never a silently
  header-less "signed-in."
- **Storage.** §5's one encrypted store. Sign-out = verified wipe
  (the sealed bytes are zero-overwritten, then deleted; the WebView
  data is cleared) and an automated test asserts no auth material
  remains anywhere the app writes.
- **Keep-alive.** Validation runs at app start and periodically
  (~12 h, WorkManager, connected-network constraint) via the identity
  call. Rotated cookie values (subset names only) arriving on
  `*.youtube.com` responses and refreshed visitor data are absorbed
  into the store. Health states: Healthy / Needs sign-in again /
  Signed out. A confirmed-dead session (401/403 or an empty identity
  answer) is destroyed with a calm "sign in again" state; a network
  failure NEVER reads as a dead session. A playback-time auth refusal
  triggers ONE silent re-validation + retry before anything surfaces.
- **Surfaces (signed in).** Personalized home (`FEmusic_home` with
  auth) replaces the anonymous home; Library gains "Your YouTube
  library": Liked Songs (the `LM` playlist) and his playlists
  (`FEmusic_liked_playlists`), list + open + play. YouTube-side
  playlist *editing* is deferred — see ROADMAP for the reason.
- **Road-switching (the completeness rule).** Each track resolves
  through the core `PlaybackResolver`: signed in, the account road
  (authenticated WEB_REMIX player, Premium formats when offered) goes
  first; on refusal the anonymous road cascades silently — iOS
  20.10.4, then ANDROID 20.31.34 (both proven to serve anonymously;
  ANDROID_VR and TVHTML5 are bot-gated anonymously and are not in the
  cascade). An error surfaces only when every road refuses:
  `AllRoadsRefused` (§6) naming both roads, with the diagnostics
  attempt log recording each road's outcome.
- **Anonymous-road probe ladder.** On the owner's carrier a resolved
  URL's bounded fetches can be refused 403 deterministically for a
  given network identity while the same track serves fine elsewhere
  (probe evidence in AUDITS). So before the player sees an
  anonymous-road URL, up to 3 (resolve → immediate bounded probe)
  pairs run through the shared OkHttp stack: pair 1 probes the URL the
  resolver just served; pair 2 resolves fresh on the OTHER cascade
  context; pair 3 returns to the first context with a fresh
  resolution. The probe sends the player's exact first-fetch shape
  (no headers of its own — the shared stack bounds the range — and
  the first bytes are read), so a 2xx pair genuinely predicts the
  player's own fetch; the first 2xx pair serves. If every pair refuses, the LAST resolved URL goes
  to the player anyway — its own fetch and the §4 player-stage 403
  retry remain final chances. Account-road serves are never probed.
  Every pair's outcome is recorded in diagnostics.
- **Tap-to-play.** Tapping any track, anywhere (feed, search, library,
  album/playlist/artist rows), starts playback immediately — the queue
  hands off at once, `isResolving` drives a visible loading state
  (MiniPlayer spinner, PlayerScreen "Loading…") until READY or a named
  error. There is no dead press-play step and no silent stall.

## 9. Branding

Cyberdogs: black-and-tan from the Doberman mark (asset:
`app/src/main/res/drawable-nodpi/cyberdogs_logo.jpg`, shown on the About
screen). Palette (Material 3 dark scheme): warm near-black background
`#0D0B09`, surface `#16120E`, raised surface `#211A13`, primary tan
`#D9A441`, lighter tan `#EBCB8B`, text `#F4EDE1`, secondary text `#B9AB94`,
error = fleet danger red `#B03A2E` on white. No purple anywhere (fleet
rule: violet is Beerus-only). All text pairings ≥ 4.5:1.
