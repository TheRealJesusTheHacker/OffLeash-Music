# OffLeash Music

**Music with the leash off.** A Cyberdogs YouTube Music client for Android — our own clean implementation, built in the open.

OffLeash Music is a *client*. It hosts no music, stores no music on any server, and sells nothing. It plays from YouTube Music's own servers through YouTube's unofficial internal API — the same surface the music.youtube.com web app uses. That API is not a public contract: Google can change it at any time, and when that happens this app will break in the open, say so plainly, and get fixed in the open.

If you love the music, subscribe to YouTube Premium — that is how the artists get paid. This app exists because the pack builds its own tools.

**Breed don't fall.**

---

A Cyberdogs project — the unofficial side of Sentinel Safeguard. Inspirations, credited honestly: the category of open YouTube Music clients (SimpMusic, InnerTune and friends) proved the shape of the thing. OffLeash is an independent implementation; no code from those projects lives here.

**Phase 1 (built):** anonymous browse (home / charts / moods & genres) / search with type filters, album & artist & playlist pages, background playback (Media3) with a real play queue (shuffle, repeat), on-device local playlists, and a replay cache for recently played streams. **Phase 2 (built):** account sign-in — a Google device-code flow (you approve on Google's own page; the app stores only the token Google hands it, encrypted on this device) — bringing your liked songs and YouTube playlists into the app, plus offline downloads. See `docs/SPEC.md` and `docs/ROADMAP.md`.

**Playback truth (proven, not assumed):** anonymous playback works through the **iOS 20.10.4** client context (`com.google.ios.youtube/20.10.4`), which answers player requests with direct, unciphered audio URLs that stream (HTTP 206, real bytes). WEB_REMIX / WEB contexts answer UNPLAYABLE anonymously and are not on the playback path. Proven end-to-end twice: from the sandbox (browse → search → player → ranged stream GET, all green) and again from GitHub CI runners via the `live-smoke` job. If YouTube changes or retires that context, the app names the failure in plain language (`YouTube refused the app's request`) instead of spinning forever — a refused context is a named error, demonstrated by a unit test.

**Build state:** CI runs the full gate on every push to `main` — the `:core` JVM suite (304 tests, pinned to recorded youtubei fixtures) plus the `:app` unit tests (741), then assembles the debug APK as a personal-use build artifact (never a release). The `:app` release story is a later decision.

## License

OffLeash Music is free software under the **GNU General Public License v3.0** — the full text is in `LICENSE`. The source is this repo; if you fork it, remix it, or ship it, your version stays open under the same terms. That's the deal: the pack shares its work, and the work stays shared. **Breed don't fall.**
